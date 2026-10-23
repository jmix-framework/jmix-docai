package com.company.dms.document;

import com.company.dms.app.service.OperationRejectedException;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.document.NewVersion;
import com.company.dms.app.service.document.UploadReport;
import com.company.dms.app.service.document.UploadedFile;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.document.Document;
import com.company.dms.model.document.DocumentRevision;
import com.company.dms.model.document.DocumentStatus;
import com.company.dms.model.folder.Folder;
import com.company.dms.test_support.AuthenticatedAsAdmin;
import com.company.dms.test_support.TestFiles;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.FileStorageLocator;
import io.jmix.core.Id;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class DocumentServiceTest {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private FileStorageLocator fileStorageLocator;

    @Value("${jmix.localfs.storage-dir}")
    private Path storageDir;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(folderService.getRoot());
        folder.setName("document-service-test-" + UUID.randomUUID());
        sandbox = folderService.save(folder);
    }

    @AfterEach
    void deleteSandbox() {
        folderService.delete(sandbox);
    }

    @Test
    void anUploadCreatesADocumentWithItsFirstVersion() {
        UploadReport report = documentService.upload(sandbox, List.of(
                TestFiles.file(tempDir, "a.txt", "first"), TestFiles.file(tempDir, "b.txt", "second")));

        assertThat(report.skipped()).isEmpty();
        assertThat(report.created()).extracting(Document::getName).containsExactly("a.txt", "b.txt");

        DocumentRevision revision = reload(report.created().getFirst()).getCurrentRevision();
        assertThat(revision.getNumber()).isEqualTo(1);
        assertThat(revision.getFileSize()).isEqualTo(5);
        assertThat(revision.getContentType()).isEqualTo("text/plain");
        assertThat(fileStorageLocator.getByName(revision.getFile().getStorageName()).fileExists(revision.getFile()))
                .isTrue();
    }

    @Test
    void anUploadSkipsATakenNameAndCreatesTheRest() {
        upload("a.txt");

        UploadReport report = documentService.upload(sandbox, List.of(
                TestFiles.file(tempDir, "a.txt", "again"), TestFiles.file(tempDir, "b.txt", "new")));

        assertThat(report.created()).extracting(Document::getName).containsExactly("b.txt");
        assertThat(report.skipped()).singleElement().satisfies(skipped -> {
            assertThat(skipped.fileName()).isEqualTo("a.txt");
            assertThat(skipped.reason()).contains("a.txt");
        });
    }

    @Test
    void aFileThatFailsToUploadIsReportedAndTheRestAreCreated() {
        UploadReport report = documentService.upload(sandbox, List.of(
                TestFiles.file(tempDir, "a.txt", "first"),
                new UploadedFile("b.txt", tempDir.resolve("gone.txt")),
                TestFiles.file(tempDir, "c.txt", "third")));

        assertThat(report.created()).extracting(Document::getName).containsExactly("a.txt", "c.txt");
        assertThat(report.skipped()).singleElement().satisfies(skipped -> {
            assertThat(skipped.fileName()).isEqualTo("b.txt");
            assertThat(skipped.reason()).contains("b.txt");
        });
    }

    @Test
    void theContentTypeComesFromTheBytes() {
        Document document = upload("report.pdf", "plain text, not a PDF");

        assertThat(reload(document).getCurrentRevision().getContentType()).isEqualTo("text/plain");
    }

    @Test
    void aNewVersionGetsTheNextNumberAndBecomesCurrent() {
        Document document = upload("a.txt");

        documentService.uploadRevision(document, TestFiles.file(tempDir, "a-v2.txt", "second version"));

        DocumentRevision current = reload(document).getCurrentRevision();
        assertThat(current.getNumber()).isEqualTo(2);
        assertThat(current.getFileSize()).isEqualTo(14);
    }

    @Test
    void aRollbackCopiesTheOldFileIntoANewVersion() {
        Document document = upload("a.txt");
        DocumentRevision first = reload(document).getCurrentRevision();
        documentService.uploadRevision(document, TestFiles.file(tempDir, "a-v2.txt", "second version"));

        documentService.save(reload(document), new NewVersion.FromRevision(first), null);

        DocumentRevision current = reload(document).getCurrentRevision();
        assertThat(current.getNumber()).isEqualTo(3);
        assertThat(current.getFile()).isEqualTo(first.getFile());
    }

    @Test
    void aVersionOfAnotherDocumentCannotBeRestored() {
        Document document = upload("a.txt");
        DocumentRevision foreign = reload(upload("b.txt")).getCurrentRevision();

        assertThatThrownBy(() -> documentService.save(reload(document), new NewVersion.FromRevision(foreign), null))
                .isInstanceOf(OperationRejectedException.class);

        assertThat(reload(document).getCurrentRevision().getNumber()).isEqualTo(1);
    }

    @Test
    void theStatusFollowsItsTransitions() {
        Document document = upload("a.txt");

        assertThatThrownBy(() -> documentService.changeStatus(List.of(document), DocumentStatus.ARCHIVED))
                .isInstanceOf(OperationRejectedException.class);

        documentService.changeStatus(List.of(document), DocumentStatus.ACTIVE);
        documentService.changeStatus(List.of(document), DocumentStatus.ARCHIVED);
        documentService.changeStatus(List.of(document), DocumentStatus.ACTIVE);

        assertThat(reload(document).getStatus()).isEqualTo(DocumentStatus.ACTIVE);
    }

    @Test
    void severalDocumentsChangeTheirStatusAllOrNone() {
        Document draft = upload("draft.txt");
        Document active = upload("active.txt");
        documentService.changeStatus(List.of(active), DocumentStatus.ACTIVE);

        // the active document goes first, so its change is ready when the draft is rejected
        assertThatThrownBy(() -> documentService.changeStatus(List.of(active, draft), DocumentStatus.ARCHIVED))
                .isInstanceOf(OperationRejectedException.class);

        assertThat(reload(active).getStatus()).isEqualTo(DocumentStatus.ACTIVE);
    }

    @Test
    void theCardCreatesADocumentInTheTargetStatus() {
        Document draft = dataManager.create(Document.class);
        draft.setFolder(sandbox);
        draft.setName("Contract");
        draft.setDocumentDate(LocalDate.of(2026, 10, 1));

        Document created = documentService.create(draft, TestFiles.file(tempDir, "scan.pdf", "text"),
                DocumentStatus.ACTIVE);

        Document reloaded = reload(created);
        assertThat(reloaded.getStatus()).isEqualTo(DocumentStatus.ACTIVE);
        assertThat(reloaded.getDocumentDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(reloaded.getCurrentRevision().getNumber()).isEqualTo(1);
    }

    @Test
    void aNameIsSavedWithoutSurroundingSpaces() {
        Document draft = dataManager.create(Document.class);
        draft.setFolder(sandbox);
        draft.setName("  Contract  ");

        Document created = documentService.create(draft, TestFiles.file(tempDir, "scan.pdf", "text"), null);

        assertThat(reload(created).getName()).isEqualTo("Contract");
    }

    @Test
    void anArchivedDocumentKeepsItsNameDateAndVersion() {
        Document document = upload("a.txt");
        documentService.changeStatus(List.of(document), DocumentStatus.ACTIVE);
        documentService.changeStatus(List.of(document), DocumentStatus.ARCHIVED);

        Document renamed = reload(document);
        renamed.setName("b.txt");
        assertThatThrownBy(() -> documentService.save(renamed, null, null))
                .isInstanceOf(OperationRejectedException.class);

        Document redated = reload(document);
        redated.setDocumentDate(LocalDate.of(2026, 1, 1));
        assertThatThrownBy(() -> documentService.save(redated, null, null))
                .isInstanceOf(OperationRejectedException.class);

        assertThatThrownBy(() -> documentService.uploadRevision(document, TestFiles.file(tempDir, "v2.txt", "v2")))
                .isInstanceOf(OperationRejectedException.class);
    }

    @Test
    void anArchivedDocumentCanBeMovedAndDeleted() {
        Document document = upload("a.txt");
        documentService.changeStatus(List.of(document), DocumentStatus.ACTIVE);
        documentService.changeStatus(List.of(document), DocumentStatus.ARCHIVED);
        Folder target = folder("Archive");

        documentService.move(List.of(document), target);
        assertThat(reload(document).getFolder()).isEqualTo(target);

        documentService.delete(List.of(document));
        assertThat(dataManager.load(Id.of(document)).optional()).isEmpty();
    }

    @Test
    void aMoveReportsEveryTakenName() {
        Folder target = folder("Target");
        documentService.upload(target,
                List.of(TestFiles.file(tempDir, "a.txt", "a"), TestFiles.file(tempDir, "b.txt", "b")));

        Document a = upload("a.txt");
        Document b = upload("b.txt");
        Document c = upload("c.txt");

        assertThatThrownBy(() -> documentService.move(List.of(a, b, c), target))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessageContaining("a.txt, b.txt");
        assertThat(reload(c).getFolder()).isEqualTo(sandbox);
    }

    @Test
    void aDeletedDocumentFreesItsName() {
        documentService.delete(List.of(upload("a.txt")));

        assertThat(upload("a.txt").getNameKey()).isEqualTo("a.txt");
    }

    @Test
    void aFailedCreateRemovesTheStoredFile() throws IOException {
        Folder gone = folder("Gone");
        folderService.delete(gone);
        long filesBefore = countStoredFiles();
        Document draft = dataManager.create(Document.class);
        draft.setFolder(gone);
        draft.setName("lost.txt");

        assertThatThrownBy(() -> documentService.create(draft, TestFiles.file(tempDir, "lost.txt", "text"), null))
                .isInstanceOf(OperationRejectedException.class);
        assertThat(countStoredFiles()).isEqualTo(filesBefore);
    }

    @Test
    void aStaleCopyCannotOverwriteANewerSave() {
        Document document = upload("a.txt");
        Document first = reload(document);
        Document second = reload(document);
        first.setName("first.txt");
        documentService.save(first, null, null);

        second.setName("second.txt");
        assertThatThrownBy(() -> documentService.save(second, null, null))
                .isInstanceOf(OptimisticLockException.class);
    }

    private Document upload(String fileName) {
        return upload(fileName, "text");
    }

    private Document upload(String fileName, String content) {
        return documentService.upload(sandbox, List.of(TestFiles.file(tempDir, fileName, content)))
                .created().getFirst();
    }

    private Folder folder(String name) {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(sandbox);
        folder.setName(name);
        return folderService.save(folder);
    }

    private Document reload(Document document) {
        return dataManager.load(Document.class)
                .id(document.getId())
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("folder", FetchPlan.BASE)
                        .add("currentRevision", FetchPlan.BASE))
                .one();
    }

    private long countStoredFiles() throws IOException {
        if (!Files.exists(storageDir)) {
            return 0;
        }

        try (Stream<Path> files = Files.walk(storageDir)) {
            return files.filter(Files::isRegularFile).count();
        }
    }
}
