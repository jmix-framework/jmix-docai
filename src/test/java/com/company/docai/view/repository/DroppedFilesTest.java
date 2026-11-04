package com.company.docai.view.repository;

import com.company.docai.DocAiApplication;
import com.company.docai.app.config.FilesProperties;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.TestDrops;
import com.company.docai.test_support.TestMenus;
import com.company.docai.view.main.MainView;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.upload.UploadDropZone;
import com.vaadin.flow.component.upload.UploadManager;
import com.vaadin.flow.component.upload.UploadManager.FileRejectionReason;
import com.vaadin.flow.router.QueryParameters;
import io.jmix.core.DataManager;
import io.jmix.core.Messages;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.upload.JmixUpload;
import io.jmix.flowui.fragment.FragmentUtils;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Files dropped from the computer onto the document table. The zone around the table sends them into the folder
 * shown when the first of them arrived, gives a notice for each file it refuses, and leaves no file of a drop in the
 * temporary directory, neither after the drop nor when the page goes.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class DroppedFilesTest {

    private static final String MESSAGES = "com.company.docai.view.document";

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
    private OpenedNotifications openedNotifications;

    @Autowired
    private UserSettingsCache userSettingsCache;

    // the temporary files of a drop are found by the names of the files, so the names are this test's own
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        sandbox = folder(folderService.getRoot(), "dropped-files-test-" + UUID.randomUUID());
    }

    @AfterEach
    void deleteSandbox() {
        folderService.delete(sandbox);
        // leaving the view saves the column settings of the document list for the user
        userSettingsCache.delete("RepositoryView.documentList");
    }

    @Test
    void aDropGoesToTheFolderShownWhenItsFirstFileArrived() {
        Folder contracts = folder(sandbox, "Contracts");
        Folder invoices = folder(sandbox, "Invoices");
        UploadDropZone zone = dropZone(open(contracts));

        TestDrops.receive(zone, name("a"), "first");
        TestMenus.folderTree().select(invoices);
        TestDrops.receive(zone, name("b"), "second");
        TestDrops.finishAll(zone);

        assertThat(documentNamesIn(contracts)).containsExactly(name("a"), name("b"));
        assertThat(documentNamesIn(invoices)).isEmpty();
    }

    @Test
    void aRefusedFileGivesTheNoticeOfItsReason() {
        UploadDropZone zone = dropZone(open(sandbox));
        openedNotifications.closeOpenedNotifications();

        TestDrops.reject(zone, "a.txt", FileRejectionReason.TOO_MANY_FILES);
        TestDrops.reject(zone, "b.txt", FileRejectionReason.FILE_TOO_LARGE);
        TestDrops.reject(zone, "c.txt", FileRejectionReason.INCORRECT_FILE_TYPE);

        assertThat(documentNamesIn(sandbox)).isEmpty();

        assertThat(UiTestUtils.getOpenedNotifications())
                .extracting(NotificationInfo::getText, NotificationInfo::getType)
                .containsExactly(
                        tuple(messages.formatMessage(MESSAGES, "dropRejected.tooManyFiles", "a.txt"),
                                Notifications.Type.WARNING),
                        tuple(messages.formatMessage(MESSAGES, "dropRejected.fileTooLarge", "b.txt"),
                                Notifications.Type.WARNING),
                        tuple(messages.formatMessage(MESSAGES, "dropRejected.other", "c.txt"),
                                Notifications.Type.WARNING));
    }

    @Test
    void theFilesOfADropLeaveTheTemporaryDirectoryWhenTheDropEnds() {
        UploadDropZone zone = dropZone(open(sandbox));

        TestDrops.receive(zone, name("a"), "first");
        assertThat(TestDrops.temporaryFiles(name("a"))).hasSize(1);

        TestDrops.finishAll(zone);

        assertThat(TestDrops.temporaryFiles(name("a"))).isEmpty();
        assertThat(documentNamesIn(sandbox)).containsExactly(name("a"));
    }

    @Test
    void leavingThePageDeletesTheFilesOfAnUnfinishedDrop() {
        View<?> view = open(sandbox);
        TestDrops.receive(dropZone(view), name("a"), "first");
        assertThat(TestDrops.temporaryFiles(name("a"))).hasSize(1);

        // a page reload or a session timeout removes the view before the rest of the drop arrives
        view.getElement().removeFromParent();

        assertThat(TestDrops.temporaryFiles(name("a"))).isEmpty();
    }

    @Test
    void theZoneTakesAsManyFilesAsTheUploadButtonAndNoLargerOnes() {
        View<?> view = open(sandbox);
        JmixUpload<?> upload = fragmentComponent(view, "documentsUpload");

        UploadManager manager = dropZone(view).getUploadManager();

        assertThat(manager.getMaxFiles()).isEqualTo(upload.getMaxFiles());
        assertThat(manager.getMaxFileSize()).isEqualTo(filesProperties.maxFileSize().toBytes());
    }

    @Test
    void theZoneSetsUpItsDragEventsInTheBrowserWhenItIsAttached() {
        open(sandbox);

        assertThat(pendingClientCalls()).filteredOn(call -> call.contains("docaiDragEvents")).hasSize(1);
    }

    private View<?> open(Folder folder) {
        viewNavigators.view(UiTestUtils.getCurrentView(), RepositoryView.class)
                .withQueryParameters(QueryParameters.of(RepositoryView.FOLDER_PARAMETER, folder.getId().toString()))
                .navigate();

        return UiTestUtils.getCurrentView();
    }

    private UploadDropZone dropZone(View<?> view) {
        DataGrid<Document> grid = fragmentComponent(view, "documentsGrid");

        return (UploadDropZone) grid.getParent().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private <T> T fragmentComponent(View<?> view, String componentId) {
        return (T) FragmentUtils.getComponent(UiTestUtils.getComponent(view, "documentList"), componentId);
    }

    // the calls of the browser's code that the server sends with its next response
    private List<String> pendingClientCalls() {
        UI ui = UI.getCurrent();
        ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();

        return ui.getInternals()
                .dumpPendingJavaScriptInvocations()
                .stream()
                .map(invocation -> invocation.getInvocation().getExpression())
                .toList();
    }

    private String name(String prefix) {
        return prefix + "-" + suffix + ".txt";
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
