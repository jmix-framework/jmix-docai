package com.company.dms.discussion;

import com.company.dms.DMSApplication;
import com.company.dms.app.service.discussion.DocumentDiscussionService;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.access.AccessLevel;
import com.company.dms.model.discussion.DiscussedDocument;
import com.company.dms.model.document.Document;
import com.company.dms.model.folder.Folder;
import com.company.dms.test_support.AuthenticatedAsAdmin;
import com.company.dms.test_support.TestAccess;
import com.company.dms.test_support.TestAccess.Grant;
import com.company.dms.test_support.TestFiles;
import io.jmix.aitoolsflowui.service.AiConversationService;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.UnconstrainedDataManager;
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
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The selections of documents of conversations with the assistant: their order, the access of the person who asks,
 * and the owner of the conversation.
 */
@SpringBootTest(classes = DMSApplication.class)
@ExtendWith(AuthenticatedAsAdmin.class)
class DocumentDiscussionServiceTest {

    private static final String EMPLOYEE = "peter";

    @Autowired
    private DocumentDiscussionService discussionService;

    @Autowired
    private AiConversationService conversationService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(folderService.getRoot());
        folder.setName("document-discussion-service-test-" + UUID.randomUUID());
        sandbox = folderService.save(folder);
    }

    @AfterEach
    void deleteSandbox() {
        dataManager.load(Id.of(sandbox))
                .optional()
                .ifPresent(folderService::delete);
    }

    @Test
    void theSelectionKeepsTheOrderInWhichItsDocumentsWereAdded() {
        Document a = upload("a.txt");
        Document b = upload("b.txt");
        Document c = upload("c.txt");
        UUID conversation = asEmployee(() -> conversationService.create().getId());

        asEmployee(() -> discussionService.saveSelection(conversation, List.of(b, a)));
        assertThat(asEmployee(() -> discussionService.documentsOf(conversation))).containsExactly(b, a);

        asEmployee(() -> discussionService.saveSelection(conversation, List.of(a, c)));
        assertThat(asEmployee(() -> discussionService.documentsOf(conversation))).containsExactly(a, c);
    }

    @Test
    void aDocumentThePersonMayNotOpenOrHasDeletedLeavesTheSelection() {
        Document open = upload("open.txt");
        Document hidden = TestAccess.giveOwnAccess(dataManager, upload("hidden.txt"),
                Grant.user("maria", AccessLevel.VIEW));

        Document deleted = upload("deleted.txt");
        UUID conversation = asEmployee(() -> conversationService.create().getId());
        asEmployee(() -> discussionService.saveSelection(conversation, List.of(open, hidden, deleted)));

        documentService.delete(List.of(deleted));

        assertThat(asEmployee(() -> discussionService.documentsOf(conversation))).containsExactly(open);
    }

    @Test
    void aDocumentDeletedOrClosedBeforeTheSaveStaysOutOfTheSelection() {
        Document open = upload("open.txt");
        Document hidden = TestAccess.giveOwnAccess(dataManager, upload("hidden.txt"),
                Grant.user("maria", AccessLevel.VIEW));

        Document deleted = upload("deleted.txt");
        UUID conversation = asEmployee(() -> conversationService.create().getId());
        documentService.delete(List.of(deleted));

        asEmployee(() -> discussionService.saveSelection(conversation, List.of(open, hidden, deleted)));

        assertThat(unconstrainedDataManager.load(DiscussedDocument.class)
                .query("e.conversation.id = :conversation")
                .parameter("conversation", conversation)
                .list()).hasSize(1);
        assertThat(asEmployee(() -> discussionService.documentsOf(conversation))).containsExactly(open);
    }

    @Test
    void anotherPersonNeitherSeesNorChangesTheSelection() {
        Document a = upload("a.txt");
        UUID conversation = asEmployee(() -> conversationService.create().getId());
        asEmployee(() -> discussionService.saveSelection(conversation, List.of(a)));

        assertThat(systemAuthenticator.withUser("maria", () -> discussionService.documentsOf(conversation))).isEmpty();
        assertThatThrownBy(() -> systemAuthenticator.withUser("maria", () -> {
            discussionService.saveSelection(conversation, List.of());
            return null;
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(asEmployee(() -> discussionService.documentsOf(conversation))).containsExactly(a);
    }

    @Test
    void aDeletedDocumentLeavesTheSelectionsOfEveryone() {
        Document a = upload("a.txt");
        Document b = upload("b.txt");
        UUID own = asEmployee(() -> conversationService.create().getId());
        UUID others = systemAuthenticator.withUser("maria", () -> conversationService.create().getId());
        asEmployee(() -> discussionService.saveSelection(own, List.of(a, b)));
        systemAuthenticator.runWithUser("maria", () -> discussionService.saveSelection(others, List.of(a)));

        asEmployee(() -> documentService.delete(List.of(a)));

        assertThat(unconstrainedDataManager.load(DiscussedDocument.class)
                .query("e.document.id = :document")
                .parameter("document", a.getId())
                .list()).isEmpty();
        assertThat(asEmployee(() -> discussionService.documentsOf(own))).containsExactly(b);
    }

    @Test
    void aRemovedConversationTakesItsSelectionAlong() {
        Document a = upload("a.txt");
        UUID conversation = asEmployee(() -> conversationService.create().getId());
        asEmployee(() -> discussionService.saveSelection(conversation, List.of(a)));

        asEmployee(() -> conversationService.remove(
                Objects.requireNonNull(conversationService.loadConversation(conversation))));

        assertThat(unconstrainedDataManager.load(DiscussedDocument.class)
                .query("e.conversation.id = :conversation")
                .parameter("conversation", conversation)
                .list()).isEmpty();
    }

    private Document upload(String fileName) {
        return documentService.upload(sandbox, List.of(TestFiles.file(tempDir, fileName, "text")))
                .created()
                .getFirst();
    }

    private <T> T asEmployee(Supplier<T> action) {
        return systemAuthenticator.withUser(EMPLOYEE, action::get);
    }

    private void asEmployee(Runnable action) {
        systemAuthenticator.runWithUser(EMPLOYEE, action);
    }
}
