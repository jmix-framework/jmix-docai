package com.company.dms.comment;

import com.company.dms.app.service.OperationRejectedException;
import com.company.dms.app.service.access.AccessChecker;
import com.company.dms.app.service.document.DocumentCommentService;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.access.AccessEntry;
import com.company.dms.model.access.AccessLevel;
import com.company.dms.model.document.Document;
import com.company.dms.model.document.DocumentComment;
import com.company.dms.model.folder.Folder;
import com.company.dms.test_support.AuthenticatedAsAdmin;
import com.company.dms.test_support.TestAccess;
import com.company.dms.test_support.TestAccess.Grant;
import com.company.dms.test_support.TestComments;
import com.company.dms.test_support.TestFiles;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.security.AccessDeniedException;
import io.jmix.core.security.SystemAuthenticator;
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
 * Who reads and writes the comments: everyone who sees a document, also with the view level only, and nobody else.
 * "open.txt" inherits the access of the root, where employees edit; "viewed.txt" is viewed by peter alone; "closed.txt"
 * is viewed by maria alone.
 */
@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class DocumentCommentAccessTest {

    private static final String VIEWER = "peter";
    private static final String OUTSIDER = "maria";

    @Autowired
    private DocumentCommentService commentService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private AccessChecker accessChecker;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @TempDir
    private Path tempDir;

    private Folder sandbox;
    private Document open;
    private Document viewed;
    private Document closed;

    @BeforeEach
    void createSandbox() {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(folderService.getRoot());
        folder.setName("document-comment-access-test-" + UUID.randomUUID());
        sandbox = folderService.save(folder);
        open = upload("open.txt");
        viewed = TestAccess.giveOwnAccess(dataManager, upload("viewed.txt"), Grant.user(VIEWER, AccessLevel.VIEW));
        closed = TestAccess.giveOwnAccess(dataManager, upload("closed.txt"), Grant.user(OUTSIDER, AccessLevel.VIEW));
    }

    @AfterEach
    void deleteSandbox() {
        TestComments.removeAll(dataManager, sandbox);
        folderService.delete(sandbox);
    }

    @Test
    void aPersonWhoOnlyViewsTheDocumentComments() {
        DocumentComment comment = post(VIEWER, viewed, "Please add the annex");

        assertThat(dataManager.load(Id.of(comment)).one().getCreatedBy()).isEqualTo(VIEWER);
    }

    @Test
    void aPersonWhoDoesNotSeeTheDocumentCannotCommentOnIt() {
        assertThatThrownBy(() -> post(VIEWER, closed, "Let me in"))
                .isInstanceOf(OperationRejectedException.class);

        assertThat(commentsOf(closed)).isEmpty();
    }

    @Test
    void thePersonReadsTheCommentsOfTheDocumentsTheySeeOnly() {
        post(OUTSIDER, closed, "Behind closed doors");
        post(VIEWER, open, "In the open");

        List<String> seen = systemAuthenticator.withUser(VIEWER, () -> dataManager.load(DocumentComment.class)
                .query("e.document.folder = :folder")
                .parameter("folder", sandbox)
                .list()
                .stream()
                .map(DocumentComment::getText)
                .toList());

        assertThat(seen).containsExactly("In the open");
    }

    @Test
    void anAuthorWhoLostAccessCannotChangeTheirComment() {
        DocumentComment comment = post(VIEWER, viewed, "Please add the annex");
        dataManager.remove(dataManager.load(AccessEntry.class)
                .query("e.accessSet = :accessSet and e.username = :username")
                .parameter("accessSet", TestAccess.setOf(dataManager, viewed))
                .parameter("username", VIEWER)
                .one());

        assertThatThrownBy(() -> systemAuthenticator.runWithUser(VIEWER, () -> commentService.edit(comment, "Later")))
                .isInstanceOf(OperationRejectedException.class);
        assertThatThrownBy(() -> systemAuthenticator.runWithUser(VIEWER, () -> commentService.delete(comment)))
                .isInstanceOf(OperationRejectedException.class);
        assertThat(dataManager.load(Id.of(comment)).one().getText()).isEqualTo("Please add the annex");
    }

    // the access rules hold for a save past the comment service as well
    @Test
    void theRulesRefuseAChangeOfAnotherPersonsCommentThroughDataManager() {
        DocumentComment comment = post(VIEWER, open, "Check item 4.2");

        assertThatThrownBy(() -> systemAuthenticator.runWithUser("alice", () -> {
            DocumentComment stored = dataManager.load(Id.of(comment)).one();
            stored.setText("Rewritten");
            dataManager.save(stored);
        }))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageMatching("resource: .*DocumentComment.*, type: entity, action: update");
        assertThatThrownBy(() -> systemAuthenticator.runWithUser("alice",
                () -> dataManager.remove(dataManager.load(Id.of(comment)).one())))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageMatching("resource: .*DocumentComment.*, type: entity, action: delete");
    }

    @Test
    void aCommentThroughDataManagerNeedsADocumentThePersonSees() {
        assertThatThrownBy(() -> systemAuthenticator.runWithUser(VIEWER, () -> {
            DocumentComment comment = dataManager.create(DocumentComment.class);
            comment.setDocument(closed);
            comment.setText("Sneaked in");
            dataManager.save(comment);
        }))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageMatching("resource: .*DocumentComment.*, type: entity, action: create");
    }

    @Test
    void aDocumentIsOpenToTheReadersOfItsLiveRowOnly() {
        assertThat(systemAuthenticator.withUser(VIEWER, () -> accessChecker.canRead(viewed))).isTrue();
        assertThat(systemAuthenticator.withUser(VIEWER, () -> accessChecker.canRead(closed))).isFalse();

        documentService.delete(List.of(open));

        assertThat(systemAuthenticator.withUser(VIEWER, () -> accessChecker.canRead(open))).isFalse();
    }

    private DocumentComment post(String username, Document document, String text) {
        return systemAuthenticator.withUser(username, () -> commentService.post(document, text));
    }

    private List<DocumentComment> commentsOf(Document document) {
        return dataManager.load(DocumentComment.class)
                .query("e.document = :document")
                .parameter("document", document)
                .list();
    }

    private Document upload(String fileName) {
        return documentService.upload(sandbox, List.of(TestFiles.file(tempDir, fileName, "text")))
                .created()
                .getFirst();
    }
}
