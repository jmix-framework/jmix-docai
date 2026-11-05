package com.company.docai.view.repository;

import com.company.docai.DocAiApplication;
import com.company.docai.app.config.FilesProperties;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.TestDrops;
import com.company.docai.test_support.TestFiles;
import com.company.docai.test_support.TestGrids;
import com.company.docai.test_support.TestUploads;
import com.company.docai.view.document.DocumentDetailView;
import com.company.docai.view.main.MainView;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.upload.FileRejectedEvent;
import com.vaadin.flow.component.upload.UploadDropZone;
import com.vaadin.flow.router.QueryParameters;
import io.jmix.core.DataManager;
import io.jmix.core.Messages;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.upload.JmixUpload;
import io.jmix.flowui.fragment.FragmentUtils;
import io.jmix.flowui.settings.UserSettingsCache;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.testassist.dialog.DialogInfo;
import io.jmix.flowui.testassist.dialog.OpenedDialogs;
import io.jmix.flowui.testassist.notification.NotificationInfo;
import io.jmix.flowui.testassist.notification.OpenedNotifications;
import io.jmix.flowui.view.DialogWindow;
import io.jmix.flowui.view.View;
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
import static org.assertj.core.api.Assertions.tuple;

/**
 * The notices of uploads in the repository and in the document card: the report of a batch upload, the notices of
 * a file an upload refuses or fails to transfer, and the largest file the upload components take.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class UploadNoticesTest {

    private static final String MESSAGES = "com.company.docai.view.document";
    // the error the upload component gives in the browser, passed on as it comes
    private static final String BROWSER_ERROR = "File is too big.";

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private DialogWindows dialogWindows;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FilesProperties filesProperties;

    @Autowired
    private Messages messages;

    @Autowired
    private OpenedDialogs openedDialogs;

    @Autowired
    private OpenedNotifications openedNotifications;

    @Autowired
    private UserSettingsCache userSettingsCache;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(folderService.getRoot());
        folder.setName("upload-notices-test-" + UUID.randomUUID());
        sandbox = folderService.save(folder);
    }

    @AfterEach
    void deleteSandbox() {
        folderService.delete(sandbox);
        // leaving the view saves the column settings of the document list for the user
        userSettingsCache.delete("RepositoryView.documentList");
    }

    @Test
    void theReportListsEachSkippedFileOnALineOfItsOwnUnderItsSummary() {
        upload("a.txt");
        upload("b.txt");
        UploadDropZone zone = dropZone(open(sandbox));

        TestDrops.receive(zone, "a.txt", "again");
        TestDrops.receive(zone, "b.txt", "again");
        TestDrops.receive(zone, "c.txt", "new");
        TestDrops.finishAll(zone);

        DialogInfo report = UiTestUtils.getLastOpenedDialog();

        assertThat(report.getDialog().getHeaderTitle()).isEqualTo(messages.getMessage(MESSAGES, "uploadReport.header"));

        assertThat(report.getContent().getChildren().map(line -> line.getElement().getText()))
                .containsExactly(
                        messages.formatMessage(MESSAGES, "uploadReport.summary", 1, 2),
                        "a.txt — " + nameTaken("a.txt"),
                        "b.txt — " + nameTaken("b.txt"));
    }

    @Test
    void anUploadThatSkipsNoFileSaysHowManyDocumentsItCreated() {
        UploadDropZone zone = dropZone(open(sandbox));
        openedDialogs.closeOpenedDialogs();

        TestDrops.receive(zone, "a.txt", "first");
        TestDrops.receive(zone, "b.txt", "second");
        TestDrops.finishAll(zone);

        assertThat(UiTestUtils.getOpenedDialogs()).isEmpty();

        assertThat(UiTestUtils.getLastOpenedNotification())
                .extracting(NotificationInfo::getText, NotificationInfo::getType)
                .containsExactly(messages.formatMessage(MESSAGES, "uploadDone", 2), Notifications.Type.SUCCESS);
    }

    @Test
    void aFileAnUploadRefusesIsNamedWithTheReasonOfTheBrowser() {
        Document document = upload("a.txt");
        View<?> view = open(sandbox);
        DataGrid<Document> grid = fragmentComponent(view, "documentsGrid");
        TestGrids.clickRow(grid, document, false);
        DocumentDetailView card = openCard(document);
        openedNotifications.closeOpenedNotifications();

        refuse(fragmentComponent(view, "versionUpload"), "a-v2.txt");
        refuse(UiTestUtils.getComponent(card, "newVersionUpload"), "a-v3.txt");

        assertThat(UiTestUtils.getOpenedNotifications())
                .extracting(NotificationInfo::getText, NotificationInfo::getType)
                .containsExactly(
                        tuple("a-v2.txt: " + BROWSER_ERROR, Notifications.Type.WARNING),
                        tuple("a-v3.txt: " + BROWSER_ERROR, Notifications.Type.WARNING));
    }

    @Test
    void aBrokenUploadNamesTheFileInTheSameWordsEverywhere() {
        Document document = upload("a.txt");
        View<?> view = open(sandbox);
        DataGrid<Document> grid = fragmentComponent(view, "documentsGrid");
        TestGrids.clickRow(grid, document, false);
        DocumentDetailView card = openCard(document);
        openedNotifications.closeOpenedNotifications();

        TestDrops.breakOff(dropZone(view), "b.txt");
        TestUploads.fail(fragmentComponent(view, "versionUpload"), "a-v2.txt");
        TestUploads.fail(UiTestUtils.getComponent(card, "newVersionUpload"), "a-v3.txt");

        assertThat(UiTestUtils.getOpenedNotifications())
                .extracting(NotificationInfo::getText, NotificationInfo::getType)
                .containsExactly(
                        tuple(messages.formatMessage(MESSAGES, "uploadFailed", "b.txt"), Notifications.Type.ERROR),
                        tuple(messages.formatMessage(MESSAGES, "uploadFailed", "a-v2.txt"), Notifications.Type.ERROR),
                        tuple(messages.formatMessage(MESSAGES, "uploadFailed", "a-v3.txt"), Notifications.Type.ERROR));
    }

    @Test
    void everyUploadTakesFilesUpToTheLargestSizeOfADocument() {
        View<?> view = open(sandbox);
        DocumentDetailView card = openCard(upload("a.txt"));

        List<JmixUpload<?>> uploads = List.of(fragmentComponent(view, "versionUpload"),
                UiTestUtils.getComponent(card, "newVersionUpload"));

        assertThat(uploads).allSatisfy(upload -> assertThat((long) upload.getMaxFileSize())
                .isEqualTo(filesProperties.maxFileSize().toBytes()));
    }

    private static void refuse(JmixUpload<?> upload, String fileName) {
        ComponentUtil.fireEvent(upload, new FileRejectedEvent(upload, BROWSER_ERROR, fileName));
    }

    private String nameTaken(String fileName) {
        return messages.formatMessage(DocumentService.class, "documentNameTaken", sandbox.getName(), fileName);
    }

    private View<?> open(Folder folder) {
        viewNavigators.view(UiTestUtils.getCurrentView(), RepositoryView.class)
                .withQueryParameters(QueryParameters.of(RepositoryView.FOLDER_PARAMETER, folder.getId().toString()))
                .navigate();

        return UiTestUtils.getCurrentView();
    }

    private DocumentDetailView openCard(Document document) {
        DialogWindow<DocumentDetailView> window = dialogWindows.detail(UiTestUtils.getCurrentView(), Document.class)
                .withViewClass(DocumentDetailView.class)
                .editEntity(document)
                .build();

        window.open();

        return window.getView();
    }

    // the upload dialog and the table hand their files to the manager of this zone
    private UploadDropZone dropZone(View<?> view) {
        DataGrid<Document> grid = fragmentComponent(view, "documentsGrid");

        return (UploadDropZone) grid.getParent().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private <T> T fragmentComponent(View<?> view, String componentId) {
        return (T) FragmentUtils.getComponent(UiTestUtils.getComponent(view, "documentList"), componentId);
    }

    private Document upload(String fileName) {
        return documentService.upload(sandbox, List.of(TestFiles.file(tempDir, fileName, "text")))
                .created()
                .getFirst();
    }
}
