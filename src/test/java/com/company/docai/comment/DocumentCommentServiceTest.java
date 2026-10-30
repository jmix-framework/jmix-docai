package com.company.docai.comment;

import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.app.service.document.DocumentCommentService;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.document.Document;
import com.company.docai.model.document.DocumentComment;
import com.company.docai.model.document.DocumentStatus;
import com.company.docai.model.folder.Folder;
import com.company.docai.test_support.AuthenticatedAsAdmin;
import com.company.docai.test_support.TestComments;
import com.company.docai.test_support.TestFiles;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.Messages;
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
 * The rules of the comments: the text, who changes and deletes a comment, and what a comment leaves untouched.
 * peter and alice are employees who may change the documents of the sandbox; admin has full access.
 */
@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class DocumentCommentServiceTest {

    private static final String AUTHOR = "peter";
    private static final String OTHER = "alice";
    private static final String ADMIN = "admin";

    @Autowired
    private DocumentCommentService commentService;

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

    @TempDir
    private Path tempDir;

    private Folder sandbox;
    private Document document;

    @BeforeEach
    void createSandbox() {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(folderService.getRoot());
        folder.setName("document-comment-service-test-" + UUID.randomUUID());
        sandbox = folderService.save(folder);
        document = documentService.upload(sandbox, List.of(TestFiles.file(tempDir, "lease.txt", "Lease")))
                .created()
                .getFirst();
    }

    @AfterEach
    void deleteSandbox() {
        TestComments.removeAll(dataManager, sandbox);
        folderService.delete(sandbox);
    }

    @Test
    void aCommentIsSavedAtOnceAndLeavesItsDocumentAsItWas() {
        Document before = dataManager.load(Id.of(document)).one();

        DocumentComment comment = post(AUTHOR, "Check item 4.2");

        DocumentComment stored = dataManager.load(Id.of(comment)).one();
        Document after = dataManager.load(Id.of(document)).one();
        assertThat(stored.getText()).isEqualTo("Check item 4.2");
        assertThat(stored.getCreatedBy()).isEqualTo(AUTHOR);
        assertThat(stored.getCreatedDate()).isNotNull();
        assertThat(stored.getEditedDate()).isNull();
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getChangedDate()).isEqualTo(before.getChangedDate());
    }

    @Test
    void theSpacesAtTheEdgesGoAndTheLineBreaksInsideStay() {
        DocumentComment comment = post(AUTHOR, "  First line\nsecond line \n");

        assertThat(dataManager.load(Id.of(comment)).one().getText()).isEqualTo("First line\nsecond line");
    }

    @Test
    void anEmptyCommentIsRefused() {
        assertThatThrownBy(() -> post(AUTHOR, " \n "))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessage(message(AUTHOR, "commentEmpty"));
        assertThatThrownBy(() -> post(AUTHOR, null))
                .isInstanceOf(OperationRejectedException.class);
    }

    // characters of two bytes each prove that the column counts characters, not bytes
    @Test
    void aCommentOfTwoThousandCharactersIsSavedAndALongerOneRefused() {
        String longest = "\u0436".repeat(DocumentComment.MAX_TEXT_LENGTH);

        DocumentComment comment = post(AUTHOR, longest);

        assertThat(dataManager.load(Id.of(comment)).one().getText()).isEqualTo(longest);
        assertThatThrownBy(() -> post(AUTHOR, longest + "x"))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessage(message(AUTHOR, "commentTooLong", DocumentComment.MAX_TEXT_LENGTH));
    }

    @Test
    void theAuthorEditsTheirCommentAndItIsMarkedEdited() {
        DocumentComment comment = post(AUTHOR, "Check item 4.2");

        systemAuthenticator.runWithUser(AUTHOR, () -> commentService.edit(comment, "Check items 4.2 and 4.3"));

        DocumentComment stored = dataManager.load(Id.of(comment)).one();
        assertThat(stored.getText()).isEqualTo("Check items 4.2 and 4.3");
        assertThat(stored.getEditedDate()).isNotNull();
        assertThat(stored.getCreatedBy()).isEqualTo(AUTHOR);
    }

    @Test
    void savingTheSameTextLeavesTheCommentAsItWas() {
        DocumentComment comment = post(AUTHOR, "Check item 4.2");
        Integer version = dataManager.load(Id.of(comment)).one().getVersion();

        systemAuthenticator.runWithUser(AUTHOR, () -> commentService.edit(comment, " Check item 4.2 "));

        DocumentComment stored = dataManager.load(Id.of(comment)).one();
        assertThat(stored.getEditedDate()).isNull();
        assertThat(stored.getVersion()).isEqualTo(version);
    }

    @Test
    void anotherEmployeeNeitherEditsNorDeletesAComment() {
        DocumentComment comment = post(AUTHOR, "Check item 4.2");

        assertThatThrownBy(() -> systemAuthenticator.runWithUser(OTHER, () -> commentService.edit(comment, "Mine")))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessage(message(OTHER, "commentEditNotYours"));
        assertThatThrownBy(() -> systemAuthenticator.runWithUser(OTHER, () -> commentService.delete(comment)))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessage(message(OTHER, "commentDeleteNotYours"));
        assertThat(dataManager.load(Id.of(comment)).one().getText()).isEqualTo("Check item 4.2");
    }

    @Test
    void theAdministratorDeletesAnyCommentButEditsOnlyTheirOwn() {
        DocumentComment comment = post(AUTHOR, "Check item 4.2");

        assertThatThrownBy(() -> systemAuthenticator.runWithUser(ADMIN, () -> commentService.edit(comment, "Mine")))
                .isInstanceOf(OperationRejectedException.class);

        systemAuthenticator.runWithUser(ADMIN, () -> commentService.delete(comment));

        assertThat(dataManager.load(Id.of(comment)).optional()).isEmpty();
    }

    @Test
    void theAuthorDeletesTheirComment() {
        DocumentComment comment = post(AUTHOR, "Check item 4.2");

        systemAuthenticator.runWithUser(AUTHOR, () -> commentService.delete(comment));

        assertThat(dataManager.load(Id.of(comment)).optional()).isEmpty();
    }

    @Test
    void anArchivedDocumentTakesComments() {
        documentService.changeStatus(List.of(document), DocumentStatus.ACTIVE);
        documentService.changeStatus(List.of(document), DocumentStatus.ARCHIVED);

        DocumentComment comment = post(AUTHOR, "Archived after the signature");

        assertThat(dataManager.load(Id.of(comment)).one().getText()).isEqualTo("Archived after the signature");
    }

    @Test
    void aDocumentInTheTrashTakesNoCommentsAndItsCommentsDoNotChange() {
        DocumentComment comment = post(AUTHOR, "Check item 4.2");
        documentService.delete(List.of(document));

        assertThatThrownBy(() -> post(AUTHOR, "Too late"))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessage(systemAuthenticator.withUser(AUTHOR, () -> messages.formatMessage(
                        DocumentCommentService.class, "documentUnavailable", "lease.txt")));
        assertThatThrownBy(() -> systemAuthenticator.runWithUser(AUTHOR, () -> commentService.edit(comment, "Later")))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessage(message(AUTHOR, "commentUnavailable"));
        assertThatThrownBy(() -> systemAuthenticator.runWithUser(ADMIN, () -> commentService.delete(comment)))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessage(message(ADMIN, "commentUnavailable"));
    }

    private DocumentComment post(String username, String text) {
        return systemAuthenticator.withUser(username, () -> commentService.post(document, text));
    }

    // a message in the language the user gets it in
    private String message(String username, String key, Object... args) {
        return systemAuthenticator.withUser(username,
                () -> messages.formatMessage(DocumentCommentService.class, key, args));
    }
}
