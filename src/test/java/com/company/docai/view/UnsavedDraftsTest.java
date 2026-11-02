package com.company.docai.view;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.model.user.User;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.TestFiles;
import com.company.docai.view.document.DocumentDetailView;
import com.company.docai.view.main.MainView;
import com.company.docai.view.repository.RepositoryView;
import com.company.docai.view.user.UserDetailView;
import io.jmix.core.DataManager;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.testassist.dialog.DialogInfo;
import io.jmix.flowui.view.DialogWindow;
import io.jmix.uirecovery.entity.ViewDraft;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unsaved edits come back as a draft when a screen is opened again after its browser tab was closed. The other tests
 * run with the drafts off: the draft of a card one test leaves unsaved would offer itself to the next one.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class},
        properties = "jmix.uirecovery.drafts.enabled=true")
class UnsavedDraftsTest {

    @Autowired
    private DialogWindows dialogWindows;

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(folderService.getRoot());
        folder.setName("unsaved-drafts-test-" + UUID.randomUUID());
        sandbox = folderService.save(folder);
    }

    @AfterEach
    void deleteSandboxAndDrafts() {
        folderService.delete(sandbox);
        unconstrainedDataManager.load(ViewDraft.class)
                .all()
                .list()
                .forEach(unconstrainedDataManager::remove);
    }

    @Test
    void anUnsavedEditOfTheDocumentCardComesBackWhenTheCardIsOpenedAgain() {
        Document document = documentService.upload(sandbox, List.of(TestFiles.file(tempDir, "lease.txt", "text")))
                .created()
                .getFirst();

        DocumentDetailView card = openCard(document);
        card.getEditedEntity().setName("Lease with a draft");
        // a closed browser tab leaves the card without saving or discarding its edits
        card.clearChanges();

        DocumentDetailView reopened = openCard(document);
        restoreDraft();

        assertThat(reopened.getEditedEntity().getName()).isEqualTo("Lease with a draft");
    }

    @Test
    void anUnsavedEditOfAUserComesBackWhenTheUserIsOpenedAgain() {
        User peter = unconstrainedDataManager.load(User.class)
                .query("e.username = :username")
                .parameter("username", "peter")
                .one();

        UserDetailView screen = openUser(peter);
        screen.getEditedEntity().setFirstName("Pete");
        screen.clearChanges();

        UserDetailView reopened = openUser(peter);
        restoreDraft();

        assertThat(reopened.getEditedEntity().getFirstName()).isEqualTo("Pete");
    }

    // the dialog of a draft offers to restore it, to discard it or to decide later, in this order
    private static void restoreDraft() {
        DialogInfo dialog = UiTestUtils.getLastOpenedDialog();
        assertThat(dialog).isNotNull();
        assertThat(dialog.getButtons()).hasSize(3);

        dialog.getButtons().getFirst().click();
    }

    private DocumentDetailView openCard(Document document) {
        DialogWindow<DocumentDetailView> window = dialogWindows.detail(UiTestUtils.getCurrentView(), Document.class)
                .withViewClass(DocumentDetailView.class)
                .editEntity(document)
                .build();

        window.open();
        return window.getView();
    }

    // a screen opened again from a view of another class is a new one, as after a reload of the tab
    private UserDetailView openUser(User user) {
        viewNavigators.view(UiTestUtils.getCurrentView(), RepositoryView.class).navigate();
        viewNavigators.detailView(UiTestUtils.getCurrentView(), User.class)
                .withViewClass(UserDetailView.class)
                .editEntity(user)
                .navigate();

        return UiTestUtils.getCurrentView();
    }
}
