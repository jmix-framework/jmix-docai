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
import com.company.docai.test_support.TestMenus;
import com.company.docai.view.document.DocumentDetailView;
import com.company.docai.view.main.MainView;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.upload.UploadDropZone;
import com.vaadin.flow.component.upload.UploadManager;
import com.vaadin.flow.component.upload.UploadManager.FileRejectionReason;
import com.vaadin.flow.router.QueryParameters;
import io.jmix.core.DataManager;
import io.jmix.core.Messages;
import io.jmix.flowui.DialogWindows;
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
import io.jmix.flowui.view.DialogWindow;
import io.jmix.flowui.view.View;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Files dropped from the computer onto the document table. The zone around the table sends them into the folder
 * shown when they were dropped, gives a notice for each file it refuses or whose transfer breaks off, and leaves no
 * file of a drop in the temporary directory, neither after the drop nor when the page goes. A batch on its way goes
 * on and ends under a strictly modal dialog such as the document card.
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

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DialogWindows dialogWindows;

    @TempDir
    private Path tempDir;

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
    void aDropGoesToTheFolderShownWhenItWasDropped() {
        Folder contracts = folder(sandbox, "Contracts");
        Folder invoices = folder(sandbox, "Invoices");
        UploadDropZone zone = dropZone(open(contracts));

        // another folder opens once the transfer of the first file has started, before the file has arrived
        TestDrops.receive(zone, name("a"), "first", () -> TestMenus.folderTree().select(invoices));
        TestDrops.receive(zone, name("b"), "second");
        TestDrops.finishAll(zone);

        assertThat(documentNamesIn(contracts)).containsExactly(name("a"), name("b"));
        assertThat(documentNamesIn(invoices)).isEmpty();
    }

    @Test
    void aBrokenTransferGivesItsNoticeAndLeavesNothingBehind() {
        UploadDropZone zone = dropZone(open(sandbox));
        openedNotifications.closeOpenedNotifications();

        TestDrops.breakOff(zone, name("a"));
        TestDrops.finishAll(zone);

        assertThat(UiTestUtils.getOpenedNotifications())
                .extracting(NotificationInfo::getText, NotificationInfo::getType)
                .containsExactly(tuple(messages.formatMessage(MESSAGES, "uploadFailed", name("a")),
                        Notifications.Type.ERROR));
        assertThat(documentNamesIn(sandbox)).isEmpty();
        assertThat(TestDrops.temporaryFiles(name("a"))).isEmpty();
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
    void aFileThatArrivesAfterThePageHasGoneLeavesNoTemporaryFile() {
        View<?> view = open(sandbox);

        // a page reload or a session timeout removes the view while the file is on its way
        TestDrops.receive(dropZone(view), name("a"), "first", () -> view.getElement().removeFromParent());

        assertThat(TestDrops.temporaryFiles(name("a"))).isEmpty();
    }

    @Test
    void aBatchGoesOnAndEndsWhileTheDocumentCardIsOpen() {
        Document document = documentService.upload(sandbox, List.of(TestFiles.file(tempDir, name("card"), "text")))
                .created()
                .getFirst();

        View<?> view = open(sandbox);
        UploadDropZone zone = dropZone(view);
        JmixButton uploadButton = fragmentComponent(view, "uploadButton");

        TestDrops.receive(zone, name("a"), "first");
        DocumentDetailView card = openCard(document);

        // the card makes the page behind it inert, yet the queued files of the batch still arrive and the batch ends
        assertThat(TestDrops.takesTransfers(zone)).isTrue();
        TestDrops.receive(zone, name("b"), "second");
        TestDrops.finishAll(zone);

        assertThat(documentNamesIn(sandbox)).contains(name("a"), name("b"));
        assertThat(uploadButton.isEnabled()).isTrue();

        // with the card closed the list takes the next batch
        card.closeWithDefaultAction();
        assertThat(TestDrops.takesTransfers(zone)).isTrue();
        TestDrops.receive(zone, name("c"), "third");
        TestDrops.finishAll(zone);

        assertThat(documentNamesIn(sandbox)).contains(name("c"));
    }

    @Test
    void theZoneTakesTwentyFilesAtATimeAndNoLargerOnes() {
        UploadManager manager = dropZone(open(sandbox)).getUploadManager();

        assertThat(manager.getMaxFiles()).isEqualTo(20);
        assertThat(manager.getMaxFileSize()).isEqualTo(filesProperties.maxFileSize().toBytes());
    }

    @Test
    void theZoneIsTheElementOfTheFileDropZoneModule() throws IOException {
        UploadDropZone zone = dropZone(open(sandbox));

        List<String> modules = Arrays.stream(zone.getClass().getAnnotationsByType(JsModule.class))
                .map(JsModule::value)
                .toList();

        Path module = Path.of("src/main/frontend", modules.getFirst());

        // the module extends the drop zone of Vaadin in the browser: only files, and none while a batch is on its way
        assertThat(zone.getElement().getTag()).isEqualTo("docai-file-drop-zone");
        assertThat(modules).containsExactly("./components/file-drop-zone.js");
        assertThat(module).exists();
        // the browser knows the element by the tag the server gives it
        assertThat(Files.readString(module)).contains("'docai-file-drop-zone'");
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

    private UploadDropZone dropZone(View<?> view) {
        DataGrid<Document> grid = fragmentComponent(view, "documentsGrid");

        return (UploadDropZone) grid.getParent().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private <T> T fragmentComponent(View<?> view, String componentId) {
        return (T) FragmentUtils.getComponent(UiTestUtils.getComponent(view, "documentList"), componentId);
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
