package com.company.docai.view.repository;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.access.AccessSet;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.security.role.EmployeeRole;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.TestAccess;
import com.company.docai.test_support.TestAccess.Grant;
import com.company.docai.test_support.TestFiles;
import com.company.docai.test_support.TestGrids;
import com.company.docai.test_support.TestMenus;
import com.company.docai.view.folder.FolderLookupView;
import com.company.docai.view.main.MainView;
import com.vaadin.flow.component.grid.dnd.GridDropMode;
import com.vaadin.flow.router.QueryParameters;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Messages;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.grid.TreeDataGrid;
import io.jmix.flowui.data.grid.DataGridItems;
import io.jmix.flowui.fragment.FragmentUtils;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.settings.UserSettingsCache;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.testassist.notification.NotificationInfo;
import io.jmix.flowui.testassist.notification.OpenedNotifications;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.company.docai.test_support.TestGrids.action;
import static com.company.docai.test_support.TestUi.dialogButton;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drag and drop in the repository as peter, an employee, does it.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class RepositoryDragTest {

    private static final String EMPLOYEE = EmployeeUiTestAuthenticator.USERNAME;
    private static final String ADMIN = "admin";
    private static final String MESSAGES = "com.company.docai.view.repository";

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private OpenedNotifications openedNotifications;

    @Autowired
    private UserSettingsCache userSettingsCache;

    @Autowired
    private Messages messages;

    @TempDir
    private Path tempDir;

    private Folder sandbox;
    private Folder drafts;
    private Folder old;
    private Folder archive;
    private Folder published;
    private Folder managed;
    private Document offer;
    private Document price;
    private Document memo;

    @BeforeEach
    void createTree() {
        systemAuthenticator.runWithUser(ADMIN, () -> {
            sandbox = folder(folderService.getRoot(), "repository-drag-test-" + UUID.randomUUID());
            drafts = folder(sandbox, "Drafts");
            old = folder(drafts, "Old");
            archive = folder(sandbox, "Archive");
            published = TestAccess.giveOwnAccess(dataManager, folder(sandbox, "Published"),
                    Grant.role(EmployeeRole.CODE, AccessLevel.VIEW));
            managed = TestAccess.giveOwnAccess(dataManager, folder(sandbox, "Managed"),
                    Grant.role(EmployeeRole.CODE, AccessLevel.EDIT), Grant.user(EMPLOYEE, AccessLevel.MANAGE));

            offer = upload(sandbox, "offer.txt");
            price = upload(sandbox, "price.txt");
            memo = TestAccess.giveOwnAccess(dataManager, upload(sandbox, "memo.txt"),
                    Grant.user(EMPLOYEE, AccessLevel.VIEW));
            upload(archive, "offer.txt");
        });
    }

    @AfterEach
    void deleteTree() {
        systemAuthenticator.runWithUser(ADMIN, () -> folderService.delete(sandbox));
        // leaving the view saves the column settings of the document list for the user
        userSettingsCache.delete("RepositoryView.documentList");
    }

    @Test
    void rowsAreDraggableWithoutTheAiAndTheTreeTakesDropsOnlyDuringADrag() {
        RepositoryView view = open(sandbox);

        assertThat(documentsGrid(view).isRowsDraggable()).isTrue();
        assertThat(foldersTree(view).isRowsDraggable()).isTrue();
        assertThat(foldersTree(view).getDropMode()).isNull();
    }

    @Test
    void draggedDocumentsLightUpOnlyTheFoldersTheyMayMoveInto() {
        RepositoryView view = open(sandbox);
        TreeDataGrid<Folder> tree = foldersTree(view);

        TestGrids.startDrag(documentsGrid(view), List.of(offer, price));

        assertThat(tree.getDropMode()).isEqualTo(GridDropMode.ON_TOP);
        assertThat(tree.getDropFilter().test(drafts)).isTrue();
        assertThat(tree.getDropFilter().test(managed)).isTrue();
        assertThat(tree.getDropFilter().test(published)).isFalse();
        assertThat(tree.getDropFilter().test(sandbox)).isFalse();

        TestGrids.endDrag(documentsGrid(view));

        assertThat(tree.getDropMode()).isNull();
    }

    @Test
    void aDocumentTheEmployeeOnlyViewsLightsUpNoFolder() {
        RepositoryView view = open(sandbox);

        TestGrids.startDrag(documentsGrid(view), List.of(offer, memo));

        assertThat(foldersTree(view).getDropMode()).isNull();
    }

    @Test
    void droppedDocumentsMoveAndTheListSaysWhere() {
        RepositoryView view = open(sandbox);
        DataGrid<Document> grid = documentsGrid(view);
        openedNotifications.closeOpenedNotifications();

        TestGrids.startDrag(grid, List.of(offer, price));
        TestGrids.dropOnRow(foldersTree(view), drafts);
        TestGrids.endDrag(grid);

        DataGridItems<Document> documents = grid.getItems();
        NotificationInfo notification = UiTestUtils.getLastOpenedNotification();

        assertThat(documentNamesIn(drafts)).containsExactly("offer.txt", "price.txt");
        assertThat(documents).isNotNull();
        assertThat(documents.getItems()).extracting(Document::getName).containsExactly("memo.txt");
        assertThat(folderTree(view).getSelectedFolder()).isEqualTo(sandbox);
        assertThat(notification).isNotNull();
        assertThat(notification.getText())
                .isEqualTo(messages.formatMessage(MESSAGES, "documentsMoved", "Drafts", 2));
        assertThat(notification.getType()).isEqualTo(Notifications.Type.SUCCESS);
    }

    @Test
    void aDropThatChangesTheAccessAsksFirst() {
        RepositoryView view = open(sandbox);
        DataGrid<Document> grid = documentsGrid(view);

        dragDocuments(view, List.of(offer), managed);
        dialogButton(DialogAction.Type.NO).click();

        assertThat(documentNamesIn(managed)).isEmpty();

        dragDocuments(view, List.of(offer), managed);
        dialogButton(DialogAction.Type.YES).click();

        DataGridItems<Document> documents = grid.getItems();

        assertThat(documentNamesIn(managed)).containsExactly("offer.txt");
        assertThat(documents).isNotNull();
        assertThat(documents.getItems()).extracting(Document::getName).doesNotContain("offer.txt");
    }

    @Test
    void aNamesakeInTheTargetRefusesTheDrop() {
        RepositoryView view = open(sandbox);
        TestGrids.startDrag(documentsGrid(view), List.of(offer));

        assertThatThrownBy(() -> TestGrids.dropOnRow(foldersTree(view), archive))
                .isInstanceOf(OperationRejectedException.class);
        assertThat(documentNamesIn(sandbox)).contains("offer.txt");
    }

    @Test
    void aDocumentDeletedDuringTheDragIsRefusedAsUnavailable() {
        RepositoryView view = open(sandbox);
        TestGrids.startDrag(documentsGrid(view), List.of(offer));
        systemAuthenticator.runWithUser(ADMIN, () -> documentService.delete(List.of(offer)));

        assertThatThrownBy(() -> TestGrids.dropOnRow(foldersTree(view), drafts))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessageContaining("offer.txt");
    }

    @Test
    void aDropOnAFolderDeletedSinceTheTreeLoadedIsRefusedAsUnavailable() {
        RepositoryView view = open(sandbox);
        TestGrids.startDrag(documentsGrid(view), List.of(offer));
        systemAuthenticator.runWithUser(ADMIN, () -> folderService.delete(drafts));

        assertThatThrownBy(() -> TestGrids.dropOnRow(foldersTree(view), drafts))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessageContaining("Drafts");
        assertThat(documentNamesIn(sandbox)).contains("offer.txt");
    }

    @Test
    void aFolderDroppedOnAFolderDeletedSinceTheTreeLoadedIsRefusedAsUnavailable() {
        TreeDataGrid<Folder> tree = foldersTree(open(sandbox));
        TestGrids.startDrag(tree, List.of(drafts));
        systemAuthenticator.runWithUser(ADMIN, () -> folderService.delete(archive));

        assertThatThrownBy(() -> TestGrids.dropOnRow(tree, archive))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessageContaining("Archive");
        assertThat(parentOf(drafts)).isEqualTo(sandbox);
    }

    @Test
    void aFolderDeletedDuringItsDragIsRefusedAsUnavailable() {
        TreeDataGrid<Folder> tree = foldersTree(open(sandbox));
        TestGrids.startDrag(tree, List.of(drafts));
        systemAuthenticator.runWithUser(ADMIN, () -> folderService.delete(drafts));

        assertThatThrownBy(() -> TestGrids.dropOnRow(tree, archive))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessageContaining("Drafts");
    }

    @Test
    void aDropWithNothingDraggedChangesNothing() {
        RepositoryView view = open(sandbox);
        openedNotifications.closeOpenedNotifications();

        TestGrids.dropOnRow(foldersTree(view), drafts);

        assertThat(documentNamesIn(drafts)).isEmpty();
        assertThat(UiTestUtils.getOpenedDialogs()).isEmpty();
        assertThat(UiTestUtils.getOpenedNotifications()).isEmpty();
    }

    @Test
    void aDropOnAFolderThatDoesNotLightUpChangesNothing() {
        RepositoryView view = open(sandbox);
        openedNotifications.closeOpenedNotifications();

        dragDocuments(view, List.of(offer), published);

        assertThat(documentNamesIn(published)).isEmpty();
        assertThat(UiTestUtils.getOpenedDialogs()).isEmpty();
        assertThat(UiTestUtils.getOpenedNotifications()).isEmpty();
    }

    // a folder the employee only views is dragged to the favorites
    @Test
    void everyFolderButTheRootIsDragged() {
        TreeDataGrid<Folder> tree = foldersTree(open(sandbox));

        assertThat(tree.getDragFilter().test(drafts)).isTrue();
        assertThat(tree.getDragFilter().test(published)).isTrue();
        assertThat(tree.getDragFilter().test(folderService.getRoot())).isFalse();
    }

    @Test
    void aFolderTheEmployeeOnlyViewsLightsUpNoFolder() {
        TreeDataGrid<Folder> tree = foldersTree(open(sandbox));

        TestGrids.startDrag(tree, List.of(published));

        assertThat(tree.getDropMode()).isNull();
    }

    @Test
    void aDraggedFolderLightsUpNeitherItselfNorItsSubfoldersNorItsParent() {
        TreeDataGrid<Folder> tree = foldersTree(open(sandbox));

        TestGrids.startDrag(tree, List.of(drafts));

        assertThat(tree.getDropMode()).isEqualTo(GridDropMode.ON_TOP);
        assertThat(tree.getDropFilter().test(archive)).isTrue();
        assertThat(tree.getDropFilter().test(managed)).isTrue();
        assertThat(tree.getDropFilter().test(drafts)).isFalse();
        assertThat(tree.getDropFilter().test(old)).isFalse();
        assertThat(tree.getDropFilter().test(sandbox)).isFalse();
        assertThat(tree.getDropFilter().test(published)).isFalse();

        TestGrids.endDrag(tree);

        assertThat(tree.getDropMode()).isNull();
    }

    @Test
    void aDroppedFolderMovesAndTheTreeFollowsIt() {
        RepositoryView view = open(sandbox);
        TreeDataGrid<Folder> tree = foldersTree(view);
        openedNotifications.closeOpenedNotifications();

        TestGrids.startDrag(tree, List.of(drafts));
        TestGrids.dropOnRow(tree, archive);
        TestGrids.endDrag(tree);

        NotificationInfo notification = UiTestUtils.getLastOpenedNotification();

        assertThat(parentOf(drafts)).isEqualTo(archive);
        assertThat(folderTree(view).getSelectedFolder()).isEqualTo(drafts);
        assertThat(notification).isNotNull();
        assertThat(notification.getText())
                .isEqualTo(messages.formatMessage(MESSAGES, "folderMoved", "Drafts", "Archive"));
    }

    @Test
    void aFolderDropThatChangesTheAccessAsksFirst() {
        TreeDataGrid<Folder> tree = foldersTree(open(sandbox));

        TestGrids.startDrag(tree, List.of(drafts));
        TestGrids.dropOnRow(tree, managed);
        TestGrids.endDrag(tree);

        assertThat(parentOf(drafts)).isEqualTo(sandbox);

        dialogButton(DialogAction.Type.YES).click();

        assertThat(parentOf(drafts)).isEqualTo(managed);
        assertThat(setOf(drafts)).isEqualTo(setOf(managed));
    }

    @Test
    void theMoveButtonsSayWhereTheDocumentsAndTheFolderWent() {
        RepositoryView view = open(sandbox);
        DataGrid<Document> grid = documentsGrid(view);
        TreeDataGrid<Folder> tree = foldersTree(view);
        grid.select(offer);

        action(grid, "moveAction").actionPerform(grid);
        pickInLookup(drafts);
        NotificationInfo documentsMoved = UiTestUtils.getLastOpenedNotification();

        assertThat(documentsMoved).isNotNull();
        assertThat(documentsMoved.getText())
                .isEqualTo(messages.formatMessage(MESSAGES, "documentsMoved", "Drafts", 1));

        folderTree(view).select(drafts);
        action(tree, "moveFolderAction").actionPerform(tree);
        pickInLookup(archive);
        NotificationInfo folderMoved = UiTestUtils.getLastOpenedNotification();

        assertThat(folderMoved).isNotNull();
        assertThat(folderMoved.getText())
                .isEqualTo(messages.formatMessage(MESSAGES, "folderMoved", "Drafts", "Archive"));
    }

    @Test
    void aMoveIntoTheFolderWhereItAlreadyIsSaysNothing() {
        RepositoryView view = open(sandbox);
        DataGrid<Document> grid = documentsGrid(view);
        TreeDataGrid<Folder> tree = foldersTree(view);
        grid.select(offer);
        openedNotifications.closeOpenedNotifications();

        action(grid, "moveAction").actionPerform(grid);
        pickInLookup(sandbox);
        folderTree(view).select(drafts);
        action(tree, "moveFolderAction").actionPerform(tree);
        pickInLookup(sandbox);

        assertThat(documentNamesIn(sandbox)).contains("offer.txt");
        assertThat(parentOf(drafts)).isEqualTo(sandbox);
        assertThat(UiTestUtils.getOpenedNotifications()).isEmpty();
    }

    private void dragDocuments(RepositoryView view, List<Document> documents, Folder target) {
        TestGrids.startDrag(documentsGrid(view), documents);
        TestGrids.dropOnRow(foldersTree(view), target);
        TestGrids.endDrag(documentsGrid(view));
    }

    private void pickInLookup(Folder folder) {
        FolderLookupView lookup = UiTestUtils.getLastOpenedViewDialog();
        TestGrids.clickRendered(UiTestUtils.getComponent(lookup, "foldersTree"), "name", folder);
        UiTestUtils.<JmixButton>getComponent(lookup, "selectButton").click();
    }

    private RepositoryView open(Folder folder) {
        viewNavigators.view(UiTestUtils.getCurrentView(), RepositoryView.class)
                .withQueryParameters(QueryParameters.simple(Map.of("folder", folder.getId().toString())))
                .navigate();

        return UiTestUtils.getCurrentView();
    }

    private FolderTreeFragment folderTree(RepositoryView view) {
        return TestMenus.folderTree();
    }

    @SuppressWarnings("unchecked")
    private TreeDataGrid<Folder> foldersTree(RepositoryView view) {
        return (TreeDataGrid<Folder>) FragmentUtils.getComponent(folderTree(view), "foldersTree");
    }

    @SuppressWarnings("unchecked")
    private DataGrid<Document> documentsGrid(RepositoryView view) {
        return (DataGrid<Document>) FragmentUtils.getComponent(UiTestUtils.getComponent(view, "documentList"),
                "documentsGrid");
    }

    private List<String> documentNamesIn(Folder folder) {
        return systemAuthenticator.withUser(ADMIN, () -> dataManager.load(Document.class)
                .query("select d from Document d where d.folder = :folder order by d.name")
                .parameter("folder", folder)
                .list()
                .stream()
                .map(Document::getName)
                .toList());
    }

    private Folder parentOf(Folder folder) {
        return systemAuthenticator.withUser(ADMIN, () -> dataManager.load(Folder.class)
                .id(folder.getId())
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE).add("parent", FetchPlan.INSTANCE_NAME))
                .one()
                .getParent());
    }

    private AccessSet setOf(Folder folder) {
        return systemAuthenticator.withUser(ADMIN, () -> TestAccess.setOf(dataManager, folder));
    }

    private Folder folder(Folder parent, String name) {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(parent);
        folder.setName(name);
        return folderService.save(folder);
    }

    private Document upload(Folder folder, String fileName) {
        return documentService.upload(folder, List.of(TestFiles.file(tempDir, fileName, "text")))
                .created().getFirst();
    }
}
