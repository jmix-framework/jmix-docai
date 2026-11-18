package com.company.docai.stand;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.document.UploadReport;
import com.company.docai.app.service.document.UploadedFile;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.search.DocumentFileProcessor;
import com.company.docai.test_support.AuthenticatedAsAdmin;
import com.company.docai.test_support.StubAiConfiguration;
import com.company.docai.test_support.TestFiles;
import com.company.docai.test_support.TestSamples;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Id;
import io.jmix.core.Messages;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.search.utils.FileProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.NestedTestConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a visitor of the stand can upload into their copy: the formats of the demo, told by their bytes, whatever their
 * names say. The class has the configuration of the stand tests on {@code build/test-stand-files},
 * {@link StandFilesTest} and {@link StandCachesTest}, so the three share a Spring context: the heap of a full test run
 * takes no further context of the stand.
 */
@SpringBootTest(classes = {DocAiApplication.class, StubAiConfiguration.class},
        properties = "docai.stand.data-dir=build/test-stand-files")
@ActiveProfiles({"test", "stand"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StandUploadTypesTest {

    private static final String ADMIN = "admin";
    // the names of the formats of docai.stand.upload-types, as a visitor knows them
    private static final String FORMATS = "PDF, DOCX, XLSX, TXT, CSV, PNG, JPEG";

    @Autowired
    private TemplateBuilder templateBuilder;

    @Autowired
    private CopyFactory copyFactory;

    @Autowired
    private StandUploadGuard uploadGuard;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private Messages messages;

    @Autowired
    private FileProcessor fileProcessor;

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

    @Test
    void theSevenFormatsOfTheDemoAreUploaded() throws Exception {
        String key = newCopy();

        UploadReport report = asAdmin(key, () -> upload(
                file("Lease.pdf", TestSamples.pdf("Lease of a warehouse")),
                file("Memo.docx", TestSamples.docx("Memo on the lease")),
                file("Budget.xlsx", TestSamples.xlsx("Budget of the lease")),
                file("Note.txt", "A note on the lease"),
                file("Payments.csv", "month,amount\nJanuary,1200\n"),
                file("Plan.png", TestSamples.png()),
                file("Photo.jpg", TestSamples.jpeg())));

        assertThat(report.skipped()).isEmpty();
        assertThat(report.created())
                .extracting(Document::getName)
                .containsExactly("Lease.pdf", "Memo.docx", "Budget.xlsx", "Note.txt", "Payments.csv", "Plan.png",
                        "Photo.jpg");
        // a version keeps the type its name refines, as outside the stand: the bytes of a CSV are plain text
        assertThat(asAdmin(key, () -> contentTypesOf(report.created()))).containsExactly(
                "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "text/plain",
                "text/csv",
                "image/png",
                "image/jpeg");
    }

    @Test
    void otherFormatsAreSkippedWithTheFormatsOfTheDemo() throws Exception {
        String key = newCopy();

        UploadReport report = asAdmin(key, () -> upload(
                file("Logo.svg", TestSamples.svg()),
                file("Page.html", TestSamples.html()),
                file("Setup.exe", TestSamples.exe()),
                file("Archive.zip", TestSamples.zip(Map.of("note.txt", "A note"))),
                file("Letter.doc", TestSamples.oldOffice())));

        assertThat(report.created()).isEmpty();
        assertThat(report.skipped()).containsExactly(
                refused(key, "Logo.svg"),
                refused(key, "Page.html"),
                refused(key, "Setup.exe"),
                refused(key, "Archive.zip"),
                refused(key, "Letter.doc"));
        assertThat(report.skipped().getFirst().reason()).contains("Logo.svg", FORMATS);
    }

    // a name and the type a browser sends are the visitor's word; the bytes are what a preview and a parser get
    @Test
    void theFormatComesFromTheBytesNotFromTheName() throws Exception {
        String key = newCopy();

        UploadReport report = asAdmin(key, () -> upload(
                file("Invoice.pdf", TestSamples.html()),
                file("Contract.docx", TestSamples.zip(Map.of("word/document.xml", "<document/>"))),
                file("Scan.png", TestSamples.exe())));

        assertThat(report.created()).isEmpty();
        assertThat(report.skipped()).containsExactly(
                refused(key, "Invoice.pdf"),
                refused(key, "Contract.docx"),
                refused(key, "Scan.png"));
    }

    // Excel on a Russian Windows saves a CSV in Windows-1251 without a byte order mark, which is neither ASCII nor
    // UTF-8 to Tika: such a text has a name of a text and no control characters but tabs and line ends
    @Test
    void aTextInWindows1251IsUploaded() throws Exception {
        String key = newCopy();

        UploadReport report = asAdmin(key, () -> upload(
                file("Payments.csv", TestSamples.windows1251Csv()),
                file("Note.txt", TestSamples.windows1251Csv())));

        assertThat(report.skipped()).isEmpty();
        assertThat(asAdmin(key, () -> contentTypesOf(report.created()))).containsExactly("text/csv", "text/plain");
    }

    // bytes of no format with control characters in them are no text, whatever the name says
    @Test
    void bytesOfNoFormatWithControlCharactersAreSkippedThoughTheNameSaysText() throws Exception {
        String key = newCopy();
        byte[] content = TestSamples.windows1251Csv();
        content[content.length / 2] = 0x01;

        UploadReport report = asAdmin(key, () -> upload(file("Payments.csv", content)));

        assertThat(report.created()).isEmpty();
        assertThat(report.skipped()).containsExactly(refused(key, "Payments.csv"));
    }

    // the bytes of an empty file show no format at all
    @Test
    void anEmptyFileIsSkipped() throws Exception {
        String key = newCopy();

        UploadReport report = asAdmin(key, () -> upload(file("Empty.txt", new byte[0])));

        assertThat(report.created()).isEmpty();
        assertThat(report.skipped()).containsExactly(refused(key, "Empty.txt"));
    }

    @Test
    void theBuildOfATemplateIsNotChecked() throws Exception {
        UploadedFile program = file("Setup.exe", TestSamples.exe());

        assertThat(StandScopeHolder.call(StandScope.template("en"), () -> checked(program))).isTrue();
    }

    // the search add-on reads the whole text of a file with no limit of its own; the stand reads it within limits
    @Test
    void theSearchOfTheStandReadsTheTextsOfFilesWithinTheLimitsOfTheReader() {
        assertThat(fileProcessor).isInstanceOf(DocumentFileProcessor.class);
    }

    private String newCopy() {
        String key = copyFactory.create("en").key();
        copies.add(key);

        return key;
    }

    private <T> T asAdmin(String key, Supplier<T> work) throws Exception {
        return inScope(key, () -> systemAuthenticator.withUser(ADMIN, work::get));
    }

    private <T> T inScope(String key, Callable<T> work) throws Exception {
        return StandScopeHolder.call(StandScope.copy(key), work);
    }

    private UploadReport upload(UploadedFile... files) {
        return documentService.upload(folderService.getRoot(), List.of(files));
    }

    private UploadReport.SkippedFile refused(String key, String fileName) throws Exception {
        String reason = asAdmin(key, () -> messages.formatMessage(StandUploadGuard.class, "uploadTypeNotAllowed",
                fileName, FORMATS));

        return new UploadReport.SkippedFile(fileName, reason);
    }

    private List<String> contentTypesOf(List<Document> documents) {
        return documents.stream()
                .map(document -> dataManager.load(Document.class)
                        .id(document.getId())
                        .fetchPlan(plan -> plan.addFetchPlan(FetchPlan.BASE)
                                .add("currentRevision", FetchPlan.BASE))
                        .one()
                        .getCurrentRevision()
                        .getContentType())
                .toList();
    }

    private UploadedFile file(String fileName, String text) {
        return TestFiles.file(tempDir, fileName, text);
    }

    private UploadedFile file(String fileName, byte[] content) {
        return TestFiles.file(tempDir, fileName, content);
    }

    private boolean checked(UploadedFile file) {
        uploadGuard.check(file, 512);

        return true;
    }

    @Nested
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @SpringBootTest
    @ExtendWith(AuthenticatedAsAdmin.class)
    class OutsideTheStand {

        @Autowired
        private DocumentService documentService;

        @Autowired
        private FolderService folderService;

        @Autowired
        private DataManager dataManager;

        private Folder sandbox;

        @BeforeEach
        void createSandbox() {
            Folder folder = dataManager.create(Folder.class);
            folder.setParent(folderService.getRoot());
            folder.setName("upload-types-test-" + UUID.randomUUID());
            sandbox = folderService.save(folder);
        }

        @AfterEach
        void deleteSandbox() {
            dataManager.load(Id.of(sandbox))
                    .optional()
                    .ifPresent(folderService::delete);
        }

        @Test
        void everyFormatIsUploaded() {
            UploadReport report = documentService.upload(sandbox, List.of(
                    file("Page.html", TestSamples.html()),
                    file("Setup.exe", TestSamples.exe())));

            assertThat(report.skipped()).isEmpty();
            assertThat(report.created()).hasSize(2);
        }
    }
}
