package com.company.dms.document;

import com.company.dms.app.service.OperationRejectedException;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.document.Document;
import com.company.dms.model.document.DocumentRevision;
import com.company.dms.model.document.DocumentStatus;
import com.company.dms.model.folder.Folder;
import com.company.dms.test_support.AuthenticatedAsAdmin;
import com.company.dms.test_support.TestFiles;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Id;
import io.jmix.core.Messages;
import io.jmix.data.exception.UniqueConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The document rules hold when code saves through DataManager directly, bypassing the service.
 */
@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class DocumentInvariantsTest {

    @Autowired
    private DataManager dataManager;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private Messages messages;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(folderService.getRoot());
        folder.setName("document-invariants-test-" + UUID.randomUUID());
        sandbox = folderService.save(folder);
    }

    @AfterEach
    void deleteSandbox() {
        folderService.delete(sandbox);
    }

    @Test
    void aDuplicateNameIsStoppedByTheConstraintWithAMessage() {
        dataManager.save(draft("Twin.txt"));
        Document second = draft("Twin.txt");

        assertThatThrownBy(() -> dataManager.save(second))
                .isInstanceOfSatisfying(UniqueConstraintViolationException.class, e -> assertThat(
                        messages.findMessage("databaseUniqueConstraintViolation." + e.getConstraintName(), null))
                        .isNotNull());
    }

    @Test
    void aNewDocumentIsADraft() {
        Document document = dataManager.create(Document.class);
        document.setFolder(sandbox);
        document.setName("active.txt");
        document.setStatus(DocumentStatus.ACTIVE);

        assertThatThrownBy(() -> dataManager.save(document)).isInstanceOf(OperationRejectedException.class);
    }

    @Test
    void aSavedRevisionCannotChange() {
        DocumentRevision revision = loadDocument(upload("a.txt")).getCurrentRevision();
        revision.setFileSize(1L);

        assertThatThrownBy(() -> dataManager.save(revision)).isInstanceOf(OperationRejectedException.class);
    }

    @Test
    void aRevisionOfALiveDocumentCannotBeDeletedAlone() {
        DocumentRevision revision = loadDocument(upload("a.txt")).getCurrentRevision();

        assertThatThrownBy(() -> dataManager.remove(revision)).isInstanceOf(OperationRejectedException.class);
    }

    @Test
    void theStatusCannotSkipATransition() {
        Document document = loadDocument(upload("a.txt"));
        document.setStatus(DocumentStatus.ARCHIVED);

        assertThatThrownBy(() -> dataManager.save(document)).isInstanceOf(OperationRejectedException.class);
    }

    @Test
    void anArchivedDocumentCannotBeRenamedDirectly() {
        Document document = upload("a.txt");
        documentService.changeStatus(List.of(document), DocumentStatus.ACTIVE);
        documentService.changeStatus(List.of(document), DocumentStatus.ARCHIVED);
        Document archived = loadDocument(document);
        archived.setName("b.txt");

        assertThatThrownBy(() -> dataManager.save(archived)).isInstanceOf(OperationRejectedException.class);
    }

    @Test
    void theCurrentRevisionBelongsToTheDocument() {
        Document first = loadDocument(upload("a.txt"));
        Document second = loadDocument(upload("b.txt"));
        first.setCurrentRevision(second.getCurrentRevision());

        assertThatThrownBy(() -> dataManager.save(first)).isInstanceOf(OperationRejectedException.class);
    }

    private Document draft(String name) {
        Document document = dataManager.create(Document.class);
        document.setFolder(sandbox);
        document.setName(name);
        return document;
    }

    private Document upload(String fileName) {
        return documentService.upload(sandbox, List.of(TestFiles.file(tempDir, fileName, "text")))
                .created().getFirst();
    }

    private Document loadDocument(Document document) {
        return dataManager.load(Id.of(document))
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("currentRevision", FetchPlan.BASE))
                .one();
    }
}
