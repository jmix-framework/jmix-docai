package com.company.docai.stand;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.app.service.document.ContentTypeDetector;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.document.UploadReport;
import com.company.docai.app.service.document.UploadedFile;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.app.service.trash.TrashService;
import com.company.docai.model.document.Document;
import com.company.docai.model.document.DocumentRevision;
import com.company.docai.test_support.StubAiConfiguration;
import com.company.docai.test_support.TestFiles;
import com.company.docai.test_support.TestSamples;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.FileRef;
import io.jmix.core.FileStorage;
import io.jmix.core.FileStorageLocator;
import io.jmix.core.Messages;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.data.PersistenceHints;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.company.docai.test_support.TestTrash.itemOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The files of the stand: a template keeps the files of its set in its own folder, a copy gets hard links to them and
 * keeps the files of its visitor in its own folder, which no other copy sees; deleting a file forever in a copy takes
 * only the link of the copy, a deleted copy leaves no folder, the delete refuses any folder but the one of a copy, and
 * the clearing of a template any folder but the one of a template.
 * A copy takes at most 20 MB of files of its own besides those of its template, only of the types of the demo, and no
 * copy takes a file while the disk runs low, whether the file comes with a document or straight into the file storage;
 * a template takes any file. Search is off and the index of fragments lives in memory, as in the other tests.
 */
@SpringBootTest(classes = {DocAiApplication.class, StubAiConfiguration.class},
        properties = "docai.stand.data-dir=build/test-stand-files")
@ActiveProfiles({"test", "stand"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StandFilesTest {

    private static final String ADMIN = "admin";

    @Autowired
    private TemplateBuilder templateBuilder;

    @Autowired
    private CopyFactory copyFactory;

    @Autowired
    private CopyRegistry registry;

    @Autowired
    private StandProperties properties;

    @Autowired
    private FileStorageLocator fileStorageLocator;

    @Autowired
    private StandFileStorage files;

    @Autowired
    private StandUploadGuard uploadGuard;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private TrashService trashService;

    @Autowired
    private ContentTypeDetector contentTypes;

    @Autowired
    private Messages messages;

    @Autowired
    private ApplicationContext applicationContext;

    @TempDir
    private Path tempDir;

    private final List<String> copies = new ArrayList<>();

    @BeforeAll
    void buildTheTemplate() {
        templateBuilder.build("en");
    }

    @AfterEach
    void deleteTheCopies() {
        copies.forEach(copyFactory::delete);
        copies.clear();
    }

    // the rows of the set refer to their files by the name of the storage of Jmix, which stays
    @Test
    void theStorageOfTheApplicationIsTheOneOfTheStand() {
        FileStorage storage = fileStorageLocator.getDefault();

        assertThat(storage).isInstanceOf(StandFileStorage.class);
        assertThat(storage.getStorageName()).isEqualTo("fs");
    }

    @Test
    void aCopyHasHardLinksToTheFilesOfItsTemplate() throws Exception {
        String key = newCopy();
        List<FileRef> versions = inScope(key, () -> systemAuthenticator.withSystem(this::filesOfTheVersions));

        assertThat(versions).isNotEmpty();

        for (FileRef file : versions) {
            Path original = templateFiles().resolve(file.getPath());

            assertThat(original).isRegularFile();
            assertThat(Files.isSameFile(filesOf(key).resolve(file.getPath()), original)).isTrue();
        }
    }

    @Test
    void aFileOfOneCopyIsNotInAnother() throws Exception {
        String first = newCopy();
        String second = newCopy();

        FileRef note = asAdmin(first, () -> uploaded("Copy note.txt"));

        assertThat(inScope(first, () -> files.fileExists(note))).isTrue();
        assertThat(inScope(second, () -> files.fileExists(note))).isFalse();
        assertThat(templateFiles().resolve(note.getPath())).doesNotExist();
    }

    @Test
    void purgingInACopyKeepsTheTemplateFile() throws Exception {
        String first = newCopy();
        String second = newCopy();
        Document document = inScope(first, () -> systemAuthenticator.withSystem(this::aDocumentOfTheSet));
        FileRef file = document.getCurrentRevision().getFile();
        Path original = templateFiles().resolve(file.getPath());

        asAdmin(first, () -> {
            documentService.delete(List.of(document));
            trashService.purge(List.of(itemOf(trashService.loadItems(), document.getId())));

            return null;
        });

        assertThat(inScope(first, () -> files.fileExists(file))).isFalse();
        assertThat(original).isRegularFile();
        assertThat(inScope(second, () -> read(file))).isEqualTo(Files.readAllBytes(original));
    }

    @Test
    void theQuotaRejectsTheFileOverTwentyMegabytes() throws Exception {
        String key = newCopy();

        // the stand takes files of 5 MB at most
        UploadReport first = asAdmin(key, () -> upload(
                file("Quota 1.txt", 5),
                file("Quota 2.txt", 5),
                file("Quota 3.txt", 5),
                file("Quota 4.txt", 4)));

        UploadReport second = asAdmin(key, () -> upload(file("Quota 5.txt", 2)));

        String refusal = asAdmin(key, () -> messages.formatMessage(StandUploadGuard.class, "uploadOverQuota",
                "Quota 5.txt", 20L));

        assertThat(first.created()).hasSize(4);
        assertThat(first.skipped()).isEmpty();
        assertThat(second.created()).isEmpty();
        assertThat(second.skipped()).containsExactly(new UploadReport.SkippedFile("Quota 5.txt", refusal));
        // the links to the files of the template count for nothing
        assertThat(bytesIn(templateFiles())).isGreaterThan(DataSize.ofMegabytes(1).toBytes());
    }

    @Test
    void lowDiskRejectsUploads() throws Exception {
        String key = newCopy();

        long free = Files.getFileStore(properties.dataDir())
                .getUsableSpace();

        StandProperties lowDisk = new StandProperties(properties.copyIdleTimeout(), properties.copyUploadQuota(),
                properties.templateLanguages(), properties.uploadTypes(),
                DataSize.ofBytes(free + DataSize.ofGigabytes(1).toBytes()), properties.dataDir(),
                properties.templateRetryPause(), properties.templateRetryPauseMax(), properties.autoStartup());

        StandUploadGuard guard = new StandUploadGuard(lowDisk, registry, contentTypes, messages);

        String refusal = asAdmin(key, () -> messages.formatMessage(StandUploadGuard.class, "uploadsPaused",
                "Note.txt"));

        assertThatThrownBy(() -> asAdmin(key, () -> checked(guard)))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessage(refusal);
        // the threshold of the stand lets the same file in, and the build of a template uploads nothing
        assertThat(asAdmin(key, () -> checked(uploadGuard))).isTrue();
        assertThat(StandScopeHolder.call(StandScope.template("en"), () -> checked(guard))).isTrue();
    }

    @Test
    void lowDiskRefusesAFileStoredStraightIntoACopy() throws Exception {
        String key = newCopy();

        long free = Files.getFileStore(properties.dataDir())
                .getUsableSpace();

        StandProperties lowDisk = new StandProperties(properties.copyIdleTimeout(), properties.copyUploadQuota(),
                properties.templateLanguages(), properties.uploadTypes(),
                DataSize.ofBytes(free + DataSize.ofGigabytes(1).toBytes()), properties.dataDir(),
                properties.templateRetryPause(), properties.templateRetryPauseMax(), properties.autoStartup());

        StandFileStorage storage = new StandFileStorage(lowDisk,
                new StandUploadGuard(lowDisk, registry, contentTypes, messages));

        // the local storage of Jmix takes its settings by fields
        applicationContext.getAutowireCapableBeanFactory()
                .autowireBean(storage);

        String refusal = asAdmin(key, () -> messages.formatMessage(StandUploadGuard.class, "uploadsPaused",
                "Note.txt"));

        assertThatThrownBy(() -> asAdmin(key, () -> storage.saveStream("Note.txt",
                new ByteArrayInputStream(new byte[]{'a'}))))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessage(refusal);
        assertThat(bytesIn(filesOf(key))).isEqualTo(bytesIn(templateFiles()));
    }

    // an upload field of a generic view stores into the file storage itself, past the service of the documents
    @Test
    void aFileStoredStraightIntoACopyOverTheQuotaIsRefusedAndGone() throws Exception {
        String key = newCopy();
        byte[] content = new byte[Math.toIntExact(DataSize.ofMegabytes(21).toBytes())];
        Arrays.fill(content, (byte) 'a');

        String refusal = asAdmin(key, () -> messages.formatMessage(StandUploadGuard.class, "uploadOverQuota",
                "Big.txt", 20L));

        assertThatThrownBy(() -> asAdmin(key, () -> files.saveStream("Big.txt", new ByteArrayInputStream(content))))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessage(refusal);
        assertThat(bytesIn(filesOf(key))).isEqualTo(bytesIn(templateFiles()));
    }

    @Test
    void aFileOfAnotherTypeStoredStraightIntoACopyIsRefusedAndGone() throws Exception {
        String key = newCopy();

        String refusal = asAdmin(key, () -> messages.formatMessage(StandUploadGuard.class, "uploadTypeNotAllowed",
                "Page.html", "PDF, DOCX, XLSX, TXT, CSV, PNG, JPEG"));

        assertThatThrownBy(() -> asAdmin(key, () -> files.saveStream("Page.html",
                new ByteArrayInputStream(TestSamples.html()))))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessage(refusal);
        assertThat(bytesIn(filesOf(key))).isEqualTo(bytesIn(templateFiles()));
    }

    // the build of a template stores the files of its set, of any type
    @Test
    void aTemplateTakesAFileOfAnyType() throws Exception {
        FileRef page = StandScopeHolder.call(StandScope.template("en"), () -> files.saveStream("Page.html",
                new ByteArrayInputStream(TestSamples.html())));

        try {
            assertThat(templateFiles().resolve(page.getPath())).isRegularFile();
        } finally {
            StandScopeHolder.run(StandScope.template("en"), () -> files.removeFile(page));
        }
    }

    @Test
    void aDeletedCopyLeavesNoFolder() throws Exception {
        String key = copyFactory.create("en").key();
        FileRef note = asAdmin(key, () -> uploaded("Gone note.txt"));
        Document ofTheSet = inScope(key, () -> systemAuthenticator.withSystem(this::aDocumentOfTheSet));
        Path original = templateFiles().resolve(ofTheSet.getCurrentRevision().getFile().getPath());

        Path folder = StandScope.copy(key)
                .folder(properties.dataDir());

        assertThat(filesOf(key).resolve(note.getPath())).isRegularFile();

        copyFactory.delete(key);

        assertThat(folder).doesNotExist();
        assertThat(original).isRegularFile();
    }

    @Test
    void aKeyOutsideTheFolderOfTheCopiesIsRefusedByTheDelete() {
        Path template = StandScope.template("en")
                .folder(properties.dataDir());

        assertThatThrownBy(() -> copyFactory.delete("../templates/en"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> files.deleteFolder(StandScope.template("en")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(template.resolve(TemplateBuilder.SCRIPT)).isRegularFile();
        assertThat(templateFiles()).isDirectory();
    }

    // a template built again starts in an empty folder, and the clearing takes nothing but the folder of a template
    @Test
    void onlyTheFolderOfATemplateIsCleared() throws IOException {
        Path other = StandScope.template("xx")
                .folder(properties.dataDir());

        Files.createDirectories(other.resolve(StandFileStorage.FILES));
        Files.writeString(other.resolve(Path.of(StandFileStorage.FILES, "stale.txt")), "a file of an earlier build");

        files.deleteTemplateFolder(StandScope.template("xx"));

        assertThat(other).doesNotExist();
        assertThatThrownBy(() -> files.deleteTemplateFolder(StandScope.copy(CopyRegistry.newKey())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(templateFiles()).isDirectory();
    }

    // the control database holds no documents, so a thread without a scope has no files
    @Test
    void aThreadWithoutAScopeStoresNoFile() {
        assertThatThrownBy(() -> files.saveStream("Control note.txt", new ByteArrayInputStream(new byte[]{1})))
                .isInstanceOf(IllegalStateException.class);
    }

    private String newCopy() {
        String key = copyFactory.create("en").key();
        copies.add(key);

        return key;
    }

    private <T> T inScope(String key, Callable<T> work) throws Exception {
        return StandScopeHolder.call(StandScope.copy(key), work);
    }

    private <T> T asAdmin(String key, Supplier<T> work) throws Exception {
        return StandScopeHolder.call(StandScope.copy(key), () -> systemAuthenticator.withUser(ADMIN, work::get));
    }

    private Path templateFiles() {
        return StandScope.template("en")
                .folder(properties.dataDir())
                .resolve("files");
    }

    private Path filesOf(String key) {
        return StandScope.copy(key)
                .folder(properties.dataDir())
                .resolve("files");
    }

    private List<FileRef> filesOfTheVersions() {
        return dataManager.load(DocumentRevision.class)
                .query("select r from DocumentRevision r")
                .hint(PersistenceHints.SOFT_DELETION, false)
                .list()
                .stream()
                .map(DocumentRevision::getFile)
                .toList();
    }

    private Document aDocumentOfTheSet() {
        return dataManager.load(Document.class)
                .query("select d from Document d where d.currentRevision is not null order by d.name")
                .fetchPlan(plan -> plan.addFetchPlan(FetchPlan.BASE)
                        .add("currentRevision", FetchPlan.BASE))
                .maxResults(1)
                .one();
    }

    private FileRef uploaded(String fileName) {
        Document document = upload(TestFiles.file(tempDir, fileName, "text"))
                .created()
                .getFirst();

        return dataManager.load(Document.class)
                .id(document.getId())
                .fetchPlan(plan -> plan.addFetchPlan(FetchPlan.BASE)
                        .add("currentRevision", FetchPlan.BASE))
                .one()
                .getCurrentRevision()
                .getFile();
    }

    private UploadReport upload(UploadedFile... uploaded) {
        return documentService.upload(folderService.getRoot(), List.of(uploaded));
    }

    // a text file, which every list of allowed types takes
    private UploadedFile file(String fileName, int megabytes) {
        byte[] content = new byte[Math.toIntExact(DataSize.ofMegabytes(megabytes).toBytes())];
        Arrays.fill(content, (byte) 'a');

        return TestFiles.file(tempDir, fileName, content);
    }

    private byte[] read(FileRef file) {
        try (InputStream content = files.openStream(file)) {
            return content.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private boolean checked(StandUploadGuard guard) {
        guard.check(TestFiles.file(tempDir, "Note.txt", "a"), 1);

        return true;
    }

    private static long bytesIn(Path folder) throws IOException {
        try (Stream<Path> paths = Files.walk(folder)) {
            return paths.filter(Files::isRegularFile)
                    .mapToLong(path -> path.toFile().length())
                    .sum();
        }
    }
}
