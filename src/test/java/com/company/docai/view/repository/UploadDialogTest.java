package com.company.docai.view.repository;

import com.company.docai.DocAiApplication;
import com.company.docai.app.config.FilesProperties;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.TestDrops;
import com.company.docai.test_support.TestMenus;
import com.company.docai.view.document.FileSizeFormatter;
import com.company.docai.view.main.MainView;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.upload.UploadButton;
import com.vaadin.flow.component.upload.UploadDropZone;
import com.vaadin.flow.component.upload.UploadManager;
import com.vaadin.flow.component.upload.UploadManager.FileRejectionReason;
import com.vaadin.flow.dom.DomEvent;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.internal.nodefeature.ElementListenerMap;
import com.vaadin.flow.router.QueryParameters;
import io.jmix.core.DataManager;
import io.jmix.core.Messages;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.fragment.FragmentUtils;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.settings.UserSettingsCache;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.testassist.notification.NotificationInfo;
import io.jmix.flowui.testassist.notification.OpenedNotifications;
import io.jmix.flowui.view.View;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The upload dialog of the document list. It names the folder the files go to and the limits of the uploads, hands
 * the files dropped into it or picked with its button to the upload manager of the list and closes as soon as they
 * start to arrive; the files then travel as the files dropped on the table do.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class UploadDialogTest {

    private static final String MESSAGES = "com.company.docai.view.repository";
    // the notices of the uploads belong to the documents
    private static final String NOTICES = "com.company.docai.view.document";

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private Messages messages;

    @Autowired
    private FilesProperties filesProperties;

    @Autowired
    private FileSizeFormatter fileSizeFormatter;

    @Autowired
    private OpenedNotifications openedNotifications;

    @Autowired
    private UserSettingsCache userSettingsCache;

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        sandbox = folder(folderService.getRoot(), "upload-dialog-test-" + UUID.randomUUID());
    }

    @AfterEach
    void deleteSandbox() {
        folderService.delete(sandbox);
        // leaving the view saves the column settings of the document list for the user
        userSettingsCache.delete("RepositoryView.documentList");
    }

    @Test
    void theUploadButtonOpensTheDialogForTheFolderShown() {
        View<?> dialog = openDialog(open(sandbox));

        assertThat(UiTestUtils.getOpenedViewDialogs()).containsExactly(dialog);
        assertThat(dialog.getPageTitle()).isEqualTo(messages.getMessage(MESSAGES, "documentUploadView.title"));
        assertThat(UiTestUtils.<Span>getComponent(dialog, "folderText").getText())
                .isEqualTo(messages.formatMessage(MESSAGES, "documentUploadView.folder", sandbox.getName()));
    }

    @Test
    void theDialogShowsTheLimitsOfTheUploads() {
        View<?> dialog = openDialog(open(sandbox));
        String largestFile = fileSizeFormatter.format(filesProperties.maxFileSize().toBytes());

        assertThat(UiTestUtils.<Span>getComponent(dialog, "limitsText").getText())
                .isEqualTo(messages.formatMessage(MESSAGES, "documentUploadView.limits", 20, largestFile));
    }

    @Test
    void theZoneAndTheButtonOfTheDialogHandTheFilesToTheManagerOfTheList() {
        View<?> view = open(sandbox);
        UploadManager listManager = tableZone(view).getUploadManager();
        View<?> dialog = openDialog(view);
        UploadButton button = UiTestUtils.getComponent(dialog, "chooseFilesButton");

        assertThat(dropZone(dialog).getUploadManager()).isSameAs(listManager);
        assertThat(button.getUploadManager()).isSameAs(listManager);
        assertThat(button.getText()).isEqualTo(messages.getMessage(MESSAGES, "documentUploadView.chooseFiles"));
        assertThat(button.getThemeNames()).contains(ButtonVariant.PRIMARY.getVariantName());
    }

    @Test
    void theZoneOfTheDialogIsTheSameElementAsTheTableZone() {
        View<?> view = open(sandbox);
        String tableZoneTag = tableZone(view).getElement().getTag();

        // the element takes only files, and none while a batch is on its way
        assertThat(dropZone(openDialog(view)).getElement().getTag())
                .isEqualTo(tableZoneTag)
                .isEqualTo("docai-file-drop-zone");
    }

    @Test
    void filesFromTheDialogGoIntoTheFolderItNamesAndCloseIt() {
        Folder contracts = folder(sandbox, "Contracts");
        Folder invoices = folder(sandbox, "Invoices");
        View<?> view = open(contracts);
        UploadDropZone zone = dropZone(openDialog(view));
        openedNotifications.closeOpenedNotifications();

        // the dialog closes as the transfer of the first file starts, and another folder opens before the file arrives
        TestDrops.receive(zone, "a.txt", "first", () -> {
            assertThat(UiTestUtils.getOpenedViewDialogs()).isEmpty();
            TestMenus.folderTree().select(invoices);
        });

        // the rest of the files are with the manager of the list, which the closed dialog has let go of
        TestDrops.receive(tableZone(view), "b.txt", "second");
        TestDrops.finishAll(tableZone(view));

        assertThat(documentNamesIn(contracts)).containsExactly("a.txt", "b.txt");
        assertThat(documentNamesIn(invoices)).isEmpty();

        assertThat(UiTestUtils.getLastOpenedNotification())
                .extracting(NotificationInfo::getText, NotificationInfo::getType)
                .containsExactly(messages.formatMessage(NOTICES, "uploadDone", 2), Notifications.Type.SUCCESS);
    }

    @Test
    void aBrokenTransferFromTheDialogGivesItsNoticeBesideTheNoticeOfTheBatch() {
        View<?> view = open(sandbox);
        UploadDropZone zone = dropZone(openDialog(view));
        openedNotifications.closeOpenedNotifications();

        TestDrops.breakOff(zone, "a.txt");
        TestDrops.receive(tableZone(view), "b.txt", "second");
        TestDrops.finishAll(tableZone(view));

        assertThat(UiTestUtils.getOpenedViewDialogs()).isEmpty();
        assertThat(documentNamesIn(sandbox)).containsExactly("b.txt");

        assertThat(UiTestUtils.getOpenedNotifications())
                .extracting(NotificationInfo::getText, NotificationInfo::getType)
                .containsExactly(
                        tuple(messages.formatMessage(NOTICES, "uploadFailed", "a.txt"), Notifications.Type.ERROR),
                        tuple(messages.formatMessage(NOTICES, "uploadDone", 1), Notifications.Type.SUCCESS));
    }

    @Test
    void theListTakesNoOtherFilesWhileABatchIsOnItsWay() {
        View<?> view = open(sandbox);
        JmixButton uploadButton = fragmentComponent(view, "uploadButton");

        TestDrops.receive(tableZone(view), "a.txt", "first");
        // the files of another drop or pick would join the batch and go into its folder
        assertThat(uploadButton.isEnabled()).isFalse();

        TestDrops.finishAll(tableZone(view));
        assertThat(uploadButton.isEnabled()).isTrue();
    }

    @Test
    void aFileTheManagerRefusesGivesItsNoticeAndLeavesTheDialogOpen() {
        View<?> dialog = openDialog(open(sandbox));
        openedNotifications.closeOpenedNotifications();

        TestDrops.reject(dropZone(dialog), "a.txt", FileRejectionReason.FILE_TOO_LARGE);

        assertThat(UiTestUtils.getOpenedNotifications())
                .extracting(NotificationInfo::getText, NotificationInfo::getType)
                .containsExactly(tuple(messages.formatMessage(NOTICES, "dropRejected.fileTooLarge", "a.txt"),
                        Notifications.Type.WARNING));
        // no file has started to arrive, so the dialog stays for another choice
        assertThat(UiTestUtils.getOpenedViewDialogs()).containsExactly(dialog);
    }

    @Test
    void theNoticeOfARefusedFileStaysWhenTheDialogCloses() {
        View<?> view = open(sandbox);
        View<?> dialog = openDialog(view);
        UploadDropZone zone = dropZone(dialog);

        // the manager refuses one file of a drop, and the notice joins the page with the answer of the server
        TestDrops.reject(zone, "a.txt", FileRejectionReason.FILE_TOO_LARGE);
        Notification notice = UiTestUtils.getLastOpenedNotification().getNotification();
        UI.getCurrent().getInternals().getStateTree().runExecutionsBeforeClientResponse();

        // the dialog closes as the other file of the drop starts to arrive
        TestDrops.receive(zone, "b.txt", "second");
        finishClosing(dialog);
        TestDrops.finishAll(tableZone(view));

        // a strictly modal dialog would take the notice in, and the notice would leave the page with it
        assertThat(notice.isAttached()).isTrue();
    }

    @Test
    void aNavigationClosesTheDialog() {
        Folder other = folder(sandbox, "Other");
        View<?> dialog = openDialog(open(sandbox));

        // the back or forward button of the browser navigates while the dialog is open
        open(other);

        // the navigation ends while the dialog fades away, and Jmix then lists its view as opened again, so the test
        // asks the dialog itself
        assertThat(dialog.findAncestor(Dialog.class).isOpened()).isFalse();
    }

    @Test
    void aSecondClickOnTheUploadButtonOpensNoSecondDialog() {
        JmixButton uploadButton = fragmentComponent(open(sandbox), "uploadButton");

        uploadButton.click();
        uploadButton.click();

        assertThat(UiTestUtils.getOpenedViewDialogs()).hasSize(1);
    }

    @Test
    void closingTheDialogUploadsNothingAndLeavesTheTableTakingFiles() {
        View<?> view = open(sandbox);
        UploadManager listManager = tableZone(view).getUploadManager();
        View<?> dialog = openDialog(view);
        UploadButton button = UiTestUtils.getComponent(dialog, "chooseFilesButton");

        // Esc and the close button of the header both close the dialog with its default action
        assertThat(dialog.findAncestor(Dialog.class).isCloseOnEsc()).isTrue();
        dialog.closeWithDefaultAction();

        // the dialog fades away before it leaves the page, and meanwhile its zone and button keep the manager
        assertThat(dropZone(dialog).getUploadManager()).isSameAs(listManager);
        assertThat(button.getUploadManager()).isSameAs(listManager);

        finishClosing(dialog);

        assertThat(UiTestUtils.getOpenedViewDialogs()).isEmpty();
        assertThat(documentNamesIn(sandbox)).isEmpty();
        // the manager of the list outlives the dialog, so the dialog lets go of it when it leaves the page
        assertThat(dropZone(dialog).getUploadManager()).isNull();
        assertThat(button.getUploadManager()).isNull();

        UploadDropZone zone = tableZone(view);
        TestDrops.receive(zone, "a.txt", "first");
        TestDrops.finishAll(zone);

        assertThat(documentNamesIn(sandbox)).containsExactly("a.txt");
    }

    private View<?> open(Folder folder) {
        viewNavigators.view(UiTestUtils.getCurrentView(), RepositoryView.class)
                .withQueryParameters(QueryParameters.of(RepositoryView.FOLDER_PARAMETER, folder.getId().toString()))
                .navigate();

        return UiTestUtils.getCurrentView();
    }

    private View<?> openDialog(View<?> view) {
        this.<JmixButton>fragmentComponent(view, "uploadButton").click();

        return UiTestUtils.getLastOpenedViewDialog();
    }

    // the browser tells the server that a dialog has closed when its closing animation is over, and only then the
    // dialog leaves the page; no public API fires a browser event in a server-side test, so it goes through Flow's
    // listener map
    private void finishClosing(View<?> dialog) {
        Element element = dialog.findAncestor(Dialog.class).getElement();

        element.getNode()
                .getFeature(ElementListenerMap.class)
                .fireEvent(new DomEvent(element, "closed", JsonNodeFactory.instance.objectNode()));
    }

    private UploadDropZone dropZone(View<?> dialog) {
        return UiTestUtils.getComponent(dialog, "dropZone");
    }

    private UploadDropZone tableZone(View<?> view) {
        DataGrid<Document> grid = fragmentComponent(view, "documentsGrid");

        return (UploadDropZone) grid.getParent().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private <T> T fragmentComponent(View<?> view, String componentId) {
        return (T) FragmentUtils.getComponent(UiTestUtils.getComponent(view, "documentList"), componentId);
    }

    private List<String> documentNamesIn(Folder folder) {
        return dataManager.load(Document.class)
                .query("select d from Document d where d.folder = :folder order by d.name")
                .parameter("folder", folder)
                .list()
                .stream()
                .map(Document::getName)
                .toList();
    }

    private Folder folder(Folder parent, String name) {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(parent);
        folder.setName(name);

        return folderService.save(folder);
    }
}
