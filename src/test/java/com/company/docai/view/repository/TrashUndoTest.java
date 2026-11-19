package com.company.docai.view.repository;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.app.service.trash.TrashService;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.TestFiles;
import com.company.docai.test_support.TestGrids;
import com.company.docai.test_support.TestMenus;
import com.company.docai.test_support.TestTrash;
import com.company.docai.view.main.MainView;
import com.company.docai.view.trash.TrashView;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.router.QueryParameters;
import io.jmix.core.DataManager;
import io.jmix.core.Messages;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.component.UiComponentUtils;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.grid.TreeDataGrid;
import io.jmix.flowui.data.grid.DataGridItems;
import io.jmix.flowui.fragment.FragmentUtils;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.testassist.notification.NotificationInfo;
import io.jmix.flowui.view.View;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.company.docai.test_support.TestUi.dialogButton;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The notice after a delete in the repository: how much went to the trash, the undo and the way to the trash.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class TrashUndoTest {

    // the private key RepositoryMoves keeps its notice under on the UI
    private static final String UNDO_NOTICE_KEY = "docai.undoNotice";

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private Messages messages;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private TrashService trashService;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        sandbox = folder(folderService.getRoot(), "trash-undo-test-" + UUID.randomUUID());
    }

    @AfterEach
    void deleteSandbox() {
        folderService.delete(sandbox);
    }

    @Test
    void deletingDocumentsSaysHowManyWentToTheTrashAndTheUndoBringsThemBack() {
        Document a = upload(sandbox, "a.txt");
        Document b = upload(sandbox, "b.txt");
        Document kept = upload(sandbox, "c.txt");
        RepositoryView view = open(sandbox);
        DataGrid<Document> grid = documentsGrid(view);

        TestGrids.clickRow(grid, a, false);
        TestGrids.clickRow(grid, b, true);
        deleteDocuments(grid);

        NotificationInfo notice = trashNotice();
        assertThat(text(notice)).contains(message("movedToTrash", 2));
        assertThat(documentNames(view)).containsExactly(kept.getName());

        button(notice, "undoButton.text").click();

        assertThat(notice.getNotification().isOpened()).isFalse();
        assertThat(documentNames(view)).containsExactly("a.txt", "b.txt", "c.txt");
        assertThat(TestTrash.isDeleted(dataManager, Document.class, a.getId())).isFalse();
        assertThat(TestTrash.isDeleted(dataManager, Document.class, b.getId())).isFalse();
    }

    @Test
    void theUndoOfAFolderBringsItBackWithItsContentAndShowsIt() {
        Folder contracts = folder(sandbox, "Contracts");
        Folder signed = folder(contracts, "Signed");
        Document lease = upload(signed, "lease.txt");
        open(contracts);
        TreeDataGrid<Folder> tree = TestMenus.folderTreeComponent("foldersTree");

        deleteFolder(tree);

        NotificationInfo notice = trashNotice();
        assertThat(text(notice)).contains(message("movedToTrash", 1));
        assertThat(TestMenus.folderTree().getSelectedFolder()).isEqualTo(sandbox);

        button(notice, "undoButton.text").click();

        assertThat(TestMenus.folderTree().getSelectedFolder()).isEqualTo(contracts);
        assertThat(TestTrash.isDeleted(dataManager, Folder.class, contracts.getId())).isFalse();
        assertThat(TestTrash.isDeleted(dataManager, Folder.class, signed.getId())).isFalse();
        assertThat(TestTrash.isDeleted(dataManager, Document.class, lease.getId())).isFalse();
    }

    // the undo takes back only the delete it names, as the trash restores a folder
    @Test
    void aDocumentDeletedOnItsOwnBeforeItsFolderStaysInTheTrashAfterTheUndo() {
        Folder contracts = folder(sandbox, "Contracts");
        Document draft = upload(contracts, "draft.txt");
        Document lease = upload(contracts, "lease.txt");
        documentService.delete(List.of(draft));

        systemAuthenticator.runWithUser("admin",
                () -> TestTrash.deletedEarlier(dataManager, draft, Duration.ofMinutes(5)));

        open(contracts);

        deleteFolder(TestMenus.folderTreeComponent("foldersTree"));
        button(trashNotice(), "undoButton.text").click();

        assertThat(TestTrash.isDeleted(dataManager, Document.class, lease.getId())).isFalse();
        assertThat(TestTrash.isDeleted(dataManager, Document.class, draft.getId())).isTrue();
    }

    @Test
    void theNoticeOpensTheTrash() {
        Document document = upload(sandbox, "a.txt");
        RepositoryView view = open(sandbox);
        DataGrid<Document> grid = documentsGrid(view);
        TestGrids.clickRow(grid, document, false);
        deleteDocuments(grid);
        NotificationInfo notice = trashNotice();

        button(notice, "openTrashButton.text").click();

        View<?> current = UiTestUtils.getCurrentView();
        assertThat(current).isInstanceOf(TrashView.class);
        assertThat(notice.getNotification().isOpened()).isFalse();
    }

    @Test
    void theNoticeClosesByItself() {
        Document document = upload(sandbox, "a.txt");
        DataGrid<Document> grid = documentsGrid(open(sandbox));
        TestGrids.clickRow(grid, document, false);

        deleteDocuments(grid);

        // zero would keep the notice open for good
        assertThat(trashNotice().getNotification().getDuration()).isBetween(5000, 15000);
    }

    @Test
    void theNoticeStandsBottomRightPlainWithInlineButtons() {
        Document document = upload(sandbox, "a.txt");
        DataGrid<Document> grid = documentsGrid(open(sandbox));
        TestGrids.clickRow(grid, document, false);

        deleteDocuments(grid);

        NotificationInfo notice = trashNotice();
        assertThat(notice.getNotification().getPosition()).isEqualTo(Notification.Position.BOTTOM_END);
        assertThat(notice.getNotification().getThemeNames()).isEmpty();
        assertThat(List.of(button(notice, "undoButton.text"), button(notice, "openTrashButton.text")))
                .allSatisfy(button -> assertThat(button.getThemeNames()).containsExactly("tertiary"));
    }

    @Test
    void aNewDeleteReplacesTheNoticeOfThePreviousOneAndItsUndo() {
        Document first = upload(sandbox, "a.txt");
        Document second = upload(sandbox, "b.txt");
        RepositoryView view = open(sandbox);
        DataGrid<Document> grid = documentsGrid(view);

        TestGrids.clickRow(grid, first, false);
        deleteDocuments(grid);
        NotificationInfo firstNotice = trashNotice();
        TestGrids.clickRow(grid, second, false);
        deleteDocuments(grid);

        assertThat(firstNotice.getNotification().isOpened()).isFalse();
        assertThat(trashNotices()).hasSize(1);

        button(trashNotice(), "undoButton.text").click();

        assertThat(documentNames(view)).containsExactly("b.txt");
        assertThat(TestTrash.isDeleted(dataManager, Document.class, first.getId())).isTrue();
    }

    @Test
    void anUndoAfterTheDocumentWasRestoredElsewhereBringsNothingAndSaysSo() {
        Document document = upload(sandbox, "a.txt");
        RepositoryView view = open(sandbox);
        DataGrid<Document> grid = documentsGrid(view);
        TestGrids.clickRow(grid, document, false);
        deleteDocuments(grid);
        Button undo = button(trashNotice(), "undoButton.text");
        trashService.restore(List.of(), List.of(document.getId()));

        undo.click();

        assertThat(lastWarning()).isEqualTo(message("nothingToUndo"));
        assertThat(TestTrash.isDeleted(dataManager, Document.class, document.getId())).isFalse();
    }

    @Test
    void anUndoAfterThePurgeOfTheDocumentBringsNothingAndSaysSo() {
        Document document = upload(sandbox, "a.txt");
        DataGrid<Document> grid = documentsGrid(open(sandbox));
        TestGrids.clickRow(grid, document, false);
        deleteDocuments(grid);
        Button undo = button(trashNotice(), "undoButton.text");

        systemAuthenticator.runWithUser("admin", () -> trashService.purge(trashService.loadItems()
                .stream()
                .filter(item -> document.getId().equals(item.getId()))
                .toList()));

        undo.click();

        assertThat(lastWarning()).isEqualTo(message("nothingToUndo"));
        assertThat(TestTrash.exists(dataManager, Document.class, document.getId())).isFalse();
    }

    @Test
    void anUndoThatBringsBackOnlyPartOfTheDocumentsSaysHowManyCameBack() {
        Document a = upload(sandbox, "a.txt");
        Document b = upload(sandbox, "b.txt");
        RepositoryView view = open(sandbox);
        DataGrid<Document> grid = documentsGrid(view);
        TestGrids.clickRow(grid, a, false);
        TestGrids.clickRow(grid, b, true);
        deleteDocuments(grid);
        Button undo = button(trashNotice(), "undoButton.text");
        trashService.restore(List.of(), List.of(a.getId()));

        undo.click();

        assertThat(lastWarning()).isEqualTo(message("undoPartial", 1, 2));
        assertThat(TestTrash.isDeleted(dataManager, Document.class, b.getId())).isFalse();
    }

    @Test
    void anUndoThatBringsEverythingBackShowsNoWarning() {
        Document document = upload(sandbox, "a.txt");
        DataGrid<Document> grid = documentsGrid(open(sandbox));
        TestGrids.clickRow(grid, document, false);
        deleteDocuments(grid);

        button(trashNotice(), "undoButton.text").click();

        assertThat(UiTestUtils.getOpenedNotifications())
                .extracting(NotificationInfo::getText)
                .noneMatch(message("nothingToUndo")::equals);
    }

    @Test
    void aFolderDeletedFromAnotherTabBetweenTheQuestionAndTheAnswerReportsNothingAndOffersNoUndo() {
        Folder contracts = folder(sandbox, "Contracts");
        open(contracts);
        TreeDataGrid<Folder> tree = TestMenus.folderTreeComponent("foldersTree");
        TestGrids.action(tree, "deleteFolderAction").actionPerform(tree);
        folderService.delete(contracts);

        dialogButton(DialogAction.Type.YES).click();

        assertThat(trashNotices()).isEmpty();
        assertThat(TestMenus.folderTree().getSelectedFolder()).isEqualTo(sandbox);
    }

    @Test
    void theClosedNoticeIsNotKeptOnThePage() {
        Document document = upload(sandbox, "a.txt");
        DataGrid<Document> grid = documentsGrid(open(sandbox));
        TestGrids.clickRow(grid, document, false);
        deleteDocuments(grid);
        NotificationInfo notice = trashNotice();
        assertThat(ComponentUtil.getData(UI.getCurrent(), UNDO_NOTICE_KEY)).isSameAs(notice.getNotification());

        notice.getNotification().close();

        assertThat(ComponentUtil.getData(UI.getCurrent(), UNDO_NOTICE_KEY)).isNull();
    }

    @Test
    void aNoticeThatTheNextDeleteReplacedLeavesTheNewOneOnThePage() {
        Document first = upload(sandbox, "a.txt");
        Document second = upload(sandbox, "b.txt");
        DataGrid<Document> grid = documentsGrid(open(sandbox));
        TestGrids.clickRow(grid, first, false);
        deleteDocuments(grid);
        TestGrids.clickRow(grid, second, false);
        deleteDocuments(grid);

        assertThat(ComponentUtil.getData(UI.getCurrent(), UNDO_NOTICE_KEY))
                .isSameAs(trashNotice().getNotification());
    }

    @Test
    void anUndoThatClashesWithANewDocumentOfTheSameNameIsRefusedAsTheTrashRefusesIt() {
        Document document = upload(sandbox, "a.txt");
        DataGrid<Document> grid = documentsGrid(open(sandbox));
        TestGrids.clickRow(grid, document, false);
        deleteDocuments(grid);
        Button undo = button(trashNotice(), "undoButton.text");
        upload(sandbox, "a.txt");

        assertThatThrownBy(undo::click)
                .isInstanceOf(OperationRejectedException.class)
                .hasMessageContaining("a.txt");
        assertThat(TestTrash.isDeleted(dataManager, Document.class, document.getId())).isTrue();
    }

    private void deleteDocuments(DataGrid<Document> grid) {
        TestGrids.action(grid, "deleteAction").actionPerform(grid);
        dialogButton(DialogAction.Type.YES).click();
    }

    private void deleteFolder(TreeDataGrid<Folder> tree) {
        TestGrids.action(tree, "deleteFolderAction").actionPerform(tree);
        dialogButton(DialogAction.Type.YES).click();
    }

    private NotificationInfo trashNotice() {
        List<NotificationInfo> notices = trashNotices();
        assertThat(notices).isNotEmpty();

        return notices.getLast();
    }

    private List<NotificationInfo> trashNotices() {
        return UiTestUtils.getOpenedNotifications()
                .stream()
                .filter(notice -> notice.getComponent() != null)
                .filter(notice -> notice.getNotification().isOpened())
                .filter(notice -> text(notice).startsWith(message("movedToTrash", "").strip()))
                .toList();
    }

    private static String text(NotificationInfo notice) {
        return notice.getComponent()
                .getElement()
                .getTextRecursively();
    }

    private Button button(NotificationInfo notice, String key) {
        String text = messages.getMessage(RepositoryMoves.class, key);

        return UiComponentUtils.getComponents(notice.getComponent())
                .stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No button " + text));
    }

    private String message(String key, Object... arguments) {
        return messages.formatMessage(RepositoryMoves.class, key, arguments);
    }

    private String lastWarning() {
        return Objects.requireNonNull(UiTestUtils.getLastOpenedNotification())
                .getText();
    }

    private RepositoryView open(Folder folder) {
        viewNavigators.view(UiTestUtils.getCurrentView(), RepositoryView.class)
                .withQueryParameters(QueryParameters.of(RepositoryView.FOLDER_PARAMETER, folder.getId().toString()))
                .navigate();

        return UiTestUtils.getCurrentView();
    }

    private static DataGrid<Document> documentsGrid(RepositoryView view) {
        @SuppressWarnings("unchecked")
        DataGrid<Document> grid = (DataGrid<Document>) FragmentUtils.getComponent(
                UiTestUtils.getComponent(view, "documentList"), "documentsGrid");

        return grid;
    }

    private static List<String> documentNames(RepositoryView view) {
        DataGridItems<Document> documents = documentsGrid(view).getItems();
        assertThat(documents).isNotNull();

        return documents.getItems()
                .stream()
                .map(Document::getName)
                .sorted()
                .toList();
    }

    private Folder folder(Folder parent, String name) {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(parent);
        folder.setName(name);

        return folderService.save(folder);
    }

    private Document upload(Folder folder, String fileName) {
        return documentService.upload(folder, List.of(TestFiles.file(tempDir, fileName, "text")))
                .created()
                .getFirst();
    }
}
