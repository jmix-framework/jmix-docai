package com.company.dms.comment;

import com.company.dms.app.service.document.DocumentCommentService;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.app.service.trash.TrashService;
import com.company.dms.model.document.Document;
import com.company.dms.model.document.DocumentComment;
import com.company.dms.model.folder.Folder;
import com.company.dms.test_support.AuthenticatedAsAdmin;
import com.company.dms.test_support.TestComments;
import com.company.dms.test_support.TestFiles;
import com.company.dms.test_support.TestTrash;
import io.jmix.core.DataManager;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.data.PersistenceHints;
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

/**
 * The comments of a document in the trash: hidden while it lies there, back with it on a restore, and gone with it
 * when it is deleted forever, on its own or with its folder.
 */
@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class DocumentCommentTrashTest {

    private static final String AUTHOR = "peter";

    @Autowired
    private DocumentCommentService commentService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private TrashService trashService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        sandbox = folder(folderService.getRoot(), "document-comment-trash-test-" + UUID.randomUUID());
    }

    @AfterEach
    void deleteSandbox() {
        TestComments.removeAll(dataManager, sandbox);

        if (dataManager.load(Folder.class).id(sandbox.getId()).optional().isPresent()) {
            folderService.delete(sandbox);
        }
    }

    @Test
    void aDocumentInTheTrashHidesItsCommentsAndARestoreBringsThemBack() {
        Document lease = upload(sandbox, "lease.txt");
        post(lease, "Check item 4.2");
        post(lease, "Agreed at 0.2 percent");

        documentService.delete(List.of(lease));

        assertThat(textsSeenByTheAuthor(lease)).isEmpty();

        trashService.restore(List.of(TestTrash.itemOf(trashService.loadItems(), lease.getId())));

        assertThat(textsSeenByTheAuthor(lease)).containsExactlyInAnyOrder("Check item 4.2", "Agreed at 0.2 percent");
    }

    @Test
    void aCommentDeletedByItsAuthorDoesNotComeBackWithTheDocument() {
        Document lease = upload(sandbox, "lease.txt");
        post(lease, "Check item 4.2");
        DocumentComment withdrawn = post(lease, "Never mind");
        systemAuthenticator.runWithUser(AUTHOR, () -> commentService.delete(withdrawn));

        documentService.delete(List.of(lease));
        trashService.restore(List.of(TestTrash.itemOf(trashService.loadItems(), lease.getId())));

        assertThat(textsSeenByTheAuthor(lease)).containsExactly("Check item 4.2");
    }

    @Test
    void aDocumentDeletedForeverTakesItsCommentsAlong() {
        Document lease = upload(sandbox, "lease.txt");
        post(lease, "Check item 4.2");

        documentService.delete(List.of(lease));
        trashService.purge(List.of(TestTrash.itemOf(trashService.loadItems(), lease.getId())));

        assertThat(storedCommentsOf(lease)).isZero();
        assertThat(TestTrash.exists(dataManager, Document.class, lease.getId())).isFalse();
    }

    @Test
    void aFolderDeletedForeverTakesTheCommentsOfItsDocumentsAlong() {
        Folder contracts = folder(sandbox, "Contracts");
        Document lease = upload(contracts, "lease.txt");
        post(lease, "Check item 4.2");

        folderService.delete(contracts);
        trashService.purge(List.of(TestTrash.itemOf(trashService.loadItems(), contracts.getId())));

        assertThat(storedCommentsOf(lease)).isZero();
        assertThat(TestTrash.exists(dataManager, Folder.class, contracts.getId())).isFalse();
    }

    private DocumentComment post(Document document, String text) {
        return systemAuthenticator.withUser(AUTHOR, () -> commentService.post(document, text));
    }

    private List<String> textsSeenByTheAuthor(Document document) {
        return systemAuthenticator.withUser(AUTHOR, () -> dataManager.load(DocumentComment.class)
                .query("e.document.id = :document")
                .parameter("document", document.getId())
                .list()
                .stream()
                .map(DocumentComment::getText)
                .toList());
    }

    private long storedCommentsOf(Document document) {
        return dataManager.loadValue("select count(c) from DocumentComment c where c.document.id = :document",
                        Long.class)
                .parameter("document", document.getId())
                .hint(PersistenceHints.SOFT_DELETION, false)
                .one();
    }

    private Folder folder(Folder parent, String name) {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(parent);
        folder.setName(name);

        return folderService.save(folder);
    }

    private Document upload(Folder folder, String fileName) {
        return documentService.upload(folder, List.of(TestFiles.file(tempDir, fileName, "Lease")))
                .created()
                .getFirst();
    }
}
