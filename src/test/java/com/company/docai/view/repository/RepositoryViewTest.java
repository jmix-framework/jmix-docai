package com.company.docai.view.repository;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.document.Document;
import com.company.docai.model.document.DocumentStatus;
import com.company.docai.model.folder.Folder;
import com.company.docai.model.tag.Tag;
import com.company.docai.model.user.User;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.TestFiles;
import com.company.docai.test_support.TestGrids;
import com.company.docai.test_support.TestMenus;
import com.company.docai.test_support.TestStatuses;
import com.company.docai.test_support.TestTags;
import com.company.docai.test_support.TestUploads;
import com.company.docai.view.document.DocumentDetailView;
import com.company.docai.view.folder.FolderDetailView;
import com.company.docai.view.folder.FolderLookupView;
import com.company.docai.view.main.MainView;
import com.company.docai.view.search.DocumentSearchView;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.contextmenu.MenuItem;
import com.vaadin.flow.component.grid.ColumnResizeEvent;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.grid.contextmenu.GridMenuItem;
import com.vaadin.flow.component.html.IFrame;
import com.vaadin.flow.component.internal.PendingJavaScriptInvocation;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.upload.UploadDropZone;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.server.AbstractStreamResource;
import com.vaadin.flow.server.StreamResourceRegistry;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.streams.ElementRequestHandler;
import io.jmix.core.DataManager;
import io.jmix.core.EntityStates;
import io.jmix.core.FetchPlan;
import io.jmix.core.Id;
import io.jmix.core.MetadataTools;
import io.jmix.core.Messages;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.metamodel.datatype.DatatypeFormatter;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.dynattr.model.Category;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.grid.TreeDataGrid;
import io.jmix.flowui.component.gridcolumnvisibility.JmixGridColumnVisibility;
import io.jmix.flowui.component.sidepanellayout.SidePanelLayout;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.component.upload.JmixUpload;
import io.jmix.flowui.data.grid.DataGridItems;
import io.jmix.flowui.fragment.FragmentUtils;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.kit.component.dropdownbutton.ComponentItem;
import io.jmix.flowui.kit.component.dropdownbutton.DropdownButton;
import io.jmix.flowui.kit.component.dropdownbutton.DropdownButtonItem;
import io.jmix.flowui.settings.UserSettingsCache;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.testassist.notification.NotificationInfo;
import io.jmix.flowui.view.View;
import io.jmix.flowui.upload.TemporaryStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class RepositoryViewTest {

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

    @Autowired
    private Messages messages;

    @Autowired
    private MetadataTools metadataTools;

    @Autowired
    private DatatypeFormatter datatypeFormatter;

    @Autowired
    private CurrentAuthentication currentAuthentication;

    @Autowired
    private TemporaryStorage temporaryStorage;

    @Autowired
    private UserSettingsCache userSettingsCache;

    @Autowired
    private EntityStates entityStates;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        sandbox = folder(folderService.getRoot(), "repository-view-test-" + UUID.randomUUID());
    }

    @AfterEach
    void deleteSandbox() {
        folderService.delete(sandbox);
        // leaving the view saves the column settings of the document list for the user
        userSettingsCache.delete("RepositoryView.documentList");
    }

    @Test
    void theAddressOpensTheFolderWithItsDocuments() {
        Folder contracts = folder(sandbox, "Contracts");
        upload(contracts, "b.txt");
        upload(contracts, "a.txt");

        RepositoryView view = open(contracts, false);

        FolderTreeFragment folderTree = TestMenus.folderTree();
        assertThat(folderTree.getSelectedFolder()).isEqualTo(contracts);
        assertThat(documentNames(view)).containsExactly("a.txt", "b.txt");
    }

    @Test
    void openingWritesTheShownFolderIntoTheRepositoryAddress() {
        // the way the login opens the home view: a server-side navigation without parameters
        viewNavigators.view(UiTestUtils.getCurrentView(), RepositoryView.class).navigate();

        assertThat(replacedAddress()).contains("repository?folder=" + Folder.ROOT_ID);
    }

    @Test
    void theRootIsOpenWhenItIsTheShownFolder() {
        viewNavigators.view(UiTestUtils.getCurrentView(), RepositoryView.class).navigate();

        TreeDataGrid<Folder> tree = TestMenus.folderTreeComponent("foldersTree");
        assertThat(tree.isExpanded(folderService.getRoot())).isTrue();
    }

    @Test
    void theBreadcrumbsShowTheNewNameOfTheRenamedFolder() {
        RepositoryView view = open(folder(sandbox, "Clients"), false);
        TreeDataGrid<Folder> tree = TestMenus.folderTreeComponent("foldersTree");

        tree.getAction("renameFolderAction").actionPerform(tree);
        FolderDetailView dialog = UiTestUtils.getLastOpenedViewDialog();
        UiTestUtils.<TypedTextField<String>>getComponent(dialog, "nameField").setValue("Partners");
        UiTestUtils.<JmixButton>getComponent(dialog, "saveButton").click();

        assertThat(breadcrumbs(view)).endsWith(sandbox.getName(), "Partners");
    }

    @Test
    void theBreadcrumbsShowTheNewPlaceOfTheMovedFolder() {
        Folder archive = folder(sandbox, "Archive");
        RepositoryView view = open(folder(sandbox, "Clients"), false);
        TreeDataGrid<Folder> tree = TestMenus.folderTreeComponent("foldersTree");

        tree.getAction("moveFolderAction").actionPerform(tree);
        FolderLookupView lookup = UiTestUtils.getLastOpenedViewDialog();
        TreeDataGrid<Folder> lookupTree = UiTestUtils.getComponent(lookup, "foldersTree");
        TestGrids.clickRendered(lookupTree, "name", archive);
        UiTestUtils.<JmixButton>getComponent(lookup, "selectButton").click();

        assertThat(breadcrumbs(view)).endsWith(sandbox.getName(), "Archive", "Clients");
    }

    @Test
    void anUploadCreatesTheDocuments() {
        RepositoryView view = open(sandbox, false);
        JmixUpload<TemporaryStorage.FileInfo> upload = fragmentComponent(view, "documentList", "documentsUpload");

        TestUploads.succeed(upload, temporaryStorage, "a.txt", "first");
        TestUploads.succeed(upload, temporaryStorage, "b.txt", "second");
        TestUploads.finishAll(upload);

        assertThat(documentNames(view)).containsExactly("a.txt", "b.txt");
    }

    @Test
    void theTableTakesDroppedFilesAndNamesTheFolderTheyGoTo() {
        Folder contracts = folder(sandbox, "Contracts");
        RepositoryView view = open(sandbox, false);
        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");

        assertThat(grid.getParent()).get().isInstanceOf(UploadDropZone.class);

        UploadDropZone zone = (UploadDropZone) grid.getParent().orElseThrow();
        assertThat(zone.isEnabled()).isTrue();
        assertThat(zone.getElement().getAttribute("data-hint")).contains(sandbox.getName());

        TestMenus.folderTree().select(contracts);
        assertThat(zone.getElement().getAttribute("data-hint")).contains("Contracts");
    }

    @Test
    void anUploadCountsInTheFolderTree() {
        RepositoryView view = open(sandbox, false);
        TreeDataGrid<Folder> tree = TestMenus.folderTreeComponent("foldersTree");
        JmixUpload<TemporaryStorage.FileInfo> upload = fragmentComponent(view, "documentList", "documentsUpload");

        TestUploads.succeed(upload, temporaryStorage, "a.txt", "first");
        TestUploads.finishAll(upload);

        assertThat(TestGrids.render(tree, "name", sandbox).getElement().getTextRecursively()).endsWith("(1)");
    }

    @Test
    void anUploadGoesToTheFolderShownWhenItStarted() {
        Folder contracts = folder(sandbox, "Contracts");
        Folder invoices = folder(sandbox, "Invoices");
        RepositoryView view = open(contracts, false);
        FolderTreeFragment folderTree = TestMenus.folderTree();
        JmixUpload<TemporaryStorage.FileInfo> upload = fragmentComponent(view, "documentList", "documentsUpload");

        TestUploads.start(upload, "a.txt");
        folderTree.select(invoices);
        TestUploads.receive(upload, temporaryStorage, "a.txt", "first");
        TestUploads.finishAll(upload);

        assertThat(documentNamesIn(contracts)).containsExactly("a.txt");
        assertThat(documentNamesIn(invoices)).isEmpty();
    }

    @Test
    void aNewVersionGoesToTheDocumentSelectedWhenTheUploadStarted() {
        Document chosen = upload(sandbox, "a.txt");
        Document other = upload(sandbox, "b.txt");
        RepositoryView view = open(sandbox, false);
        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");
        JmixUpload<TemporaryStorage.FileInfo> upload = fragmentComponent(view, "documentList", "versionUpload");

        TestGrids.clickRow(grid, chosen, false);
        TestUploads.start(upload, "a-v2.txt");
        TestGrids.clickRow(grid, other, false);
        TestUploads.receive(upload, temporaryStorage, "a-v2.txt", "second");

        assertThat(currentRevisionNumber(chosen)).isEqualTo(2);
        assertThat(currentRevisionNumber(other)).isEqualTo(1);
    }

    @Test
    void aBrokenVersionUploadIsReported() {
        Document document = upload(sandbox, "a.txt");
        RepositoryView view = open(sandbox, false);
        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");
        TestGrids.clickRow(grid, document, false);

        TestUploads.fail(fragmentComponent(view, "documentList", "versionUpload"), "a-v2.txt");

        assertThat(UiTestUtils.getOpenedNotifications())
                .extracting(NotificationInfo::getText)
                .anyMatch(text -> text.contains("a-v2.txt"));
    }

    @Test
    void leavingThePageDeletesTheFilesOfAnUnfinishedUpload() {
        RepositoryView view = open(sandbox, false);
        JmixUpload<TemporaryStorage.FileInfo> upload = fragmentComponent(view, "documentList", "documentsUpload");
        TemporaryStorage.FileInfo received = TestUploads.succeed(upload, temporaryStorage, "a.txt", "first");

        // a page reload or a session timeout removes the view before the rest of the batch arrives
        view.getElement().removeFromParent();

        assertThat(temporaryStorage.getFile(received.getId())).isNull();
    }

    @Test
    void aClickSelectsTheRowAndCtrlAddsOrRemovesIt() {
        Document a = upload(sandbox, "a.txt");
        Document b = upload(sandbox, "b.txt");
        RepositoryView view = open(sandbox, false);
        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");

        TestGrids.clickRow(grid, a, false);
        assertThat(grid.getSelectedItems()).containsExactly(a);

        TestGrids.clickRow(grid, b, false);
        assertThat(grid.getSelectedItems()).containsExactly(b);

        TestGrids.clickRow(grid, a, true);
        assertThat(grid.getSelectedItems()).containsExactlyInAnyOrder(a, b);

        TestGrids.clickRow(grid, b, true);
        assertThat(grid.getSelectedItems()).containsExactly(a);
    }

    @Test
    void aRightClickSelectsTheRowUnderThePointer() {
        Document a = upload(sandbox, "a.txt");
        Document b = upload(sandbox, "b.txt");
        RepositoryView view = open(sandbox, false);
        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");
        TestGrids.clickRow(grid, a, false);

        TestGrids.openContextMenu(grid, b);
        assertThat(grid.getSelectedItems()).containsExactly(b);

        // inside the selection a right click keeps it, and the menu acts on every selected row
        TestGrids.clickRow(grid, a, true);
        TestGrids.openContextMenu(grid, a);
        assertThat(grid.getSelectedItems()).containsExactlyInAnyOrder(a, b);
    }

    // an open menu shows none of the items sent to it later, so the row is selected and the items go before it opens
    @Test
    void aRightClickSelectsTheRowBeforeTheMenuOpens() {
        Document a = upload(sandbox, "a.txt");
        Document b = upload(sandbox, "b.txt");
        RepositoryView view = open(sandbox, false);
        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");
        TestGrids.clickRow(grid, a, false);
        pendingClientCalls();

        TestGrids.requestContextMenu(grid, b);
        assertThat(grid.getSelectedItems()).containsExactly(b);
        assertThat(pendingClientCalls()).anyMatch(call -> call.contains("generateItems"));

        TestGrids.reportContextMenuOpened(grid.getContextMenu());
        assertThat(pendingClientCalls()).noneMatch(call -> call.contains("generateItems"));
    }

    // menu items take no action variant, so the stylesheet colours the delete item by its class
    @Test
    void theMenuOfADocumentOffersEveryActionAndMarksItsDeleteItem() {
        RepositoryView view = open(sandbox, false);
        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");
        List<GridMenuItem<Document>> items = grid.getContextMenu().getItems();

        assertThat(items)
                .extracting(item -> FragmentUtils.getComponentId(item).orElse(""))
                .containsExactly("openDocumentItem", "downloadDocumentItem", "discussDocumentItem",
                        "moveDocumentItem", "statusDocumentItem", "accessDocumentItem", "favoriteDocumentItem",
                        "deleteDocumentItem");
        assertThat(items.getLast().getClassNames()).contains("danger-menu-item");
    }

    @Test
    void withoutTheAiThereIsNoDiscussionOfDocuments() {
        Document document = upload(sandbox, "a.txt");
        RepositoryView view = open(sandbox, false);
        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");
        JmixButton discussButton = fragmentComponent(view, "documentList", "discussButton");

        TestGrids.clickRow(grid, document, false);

        assertThat(discussButton.isVisible()).isFalse();
    }

    @Test
    void theMenuOfAFolderOffersEveryActionAndMarksItsMainAndDeleteItems() {
        RepositoryView view = open(sandbox, false);
        TreeDataGrid<Folder> tree = TestMenus.folderTreeComponent("foldersTree");
        List<GridMenuItem<Folder>> items = tree.getContextMenu().getItems();

        assertThat(items)
                .extracting(item -> FragmentUtils.getComponentId(item).orElse(""))
                .containsExactly("createFolderContextItem", "renameFolderContextItem", "moveFolderContextItem",
                        "accessFolderContextItem", "favoriteFolderContextItem", "deleteFolderContextItem");
        assertThat(items.getFirst().getClassNames()).contains("main-menu-item");
        assertThat(items.getLast().getClassNames()).contains("danger-menu-item");
    }

    @Test
    void thePreviewFollowsTheToggle() {
        Document document = upload(sandbox, "a.txt");
        RepositoryView view = open(sandbox, false);
        SidePanelLayout previewLayout = UiTestUtils.getComponent(view, "previewLayout");
        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");
        JmixButton previewButton = fragmentComponent(view, "documentList", "previewButton");

        TestGrids.clickRow(grid, document, false);
        assertThat(previewLayout.isSidePanelOpened()).isFalse();

        previewButton.click();
        assertThat(previewLayout.isSidePanelOpened()).isTrue();
        assertThat(previewButton.getElement().getAttribute("aria-pressed")).isEqualTo("true");

        grid.deselectAll();
        assertThat(previewLayout.isSidePanelOpened()).isFalse();
        assertThat(previewButton.getElement().getAttribute("aria-pressed")).isEqualTo("true");

        previewButton.click();
        TestGrids.clickRow(grid, document, false);
        assertThat(previewLayout.isSidePanelOpened()).isFalse();
    }

    @Test
    void thePreviewShowsTheLastAddedDocumentAndStaysOnItAfterAnAction() {
        upload(sandbox, "a.txt");
        Document b = upload(sandbox, "b.txt");
        RepositoryView view = open(sandbox, true);
        DocumentListFragment documentList = UiTestUtils.getComponent(view, "documentList");
        SidePanelLayout previewLayout = UiTestUtils.getComponent(view, "previewLayout");
        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");

        grid.getItems().getItems().forEach(document -> TestGrids.clickRow(grid, document, true));
        assertThat(documentList.getFocusedDocument()).isEqualTo(b);

        clickStatus(fragmentComponent(view, "documentList", "statusButton"), TestStatuses.active(dataManager));

        assertThat(previewLayout.isSidePanelOpened()).isTrue();
        assertThat(documentList.getFocusedDocument()).isEqualTo(b);
        assertThat(documentList.getFocusedDocument().getStatus()).isEqualTo(TestStatuses.active(dataManager));
    }

    @Test
    void theEdgeOfThePreviewIsAHairline() {
        RepositoryView view = open(sandbox, true);
        SidePanelLayout previewLayout = UiTestUtils.getComponent(view, "previewLayout");

        // a thin line, with the grip shown on hover only
        assertThat(previewLayout.getThemeNames()).contains("resizer-small");
    }

    @Test
    void anActionDoesNotReloadThePreviewOfTheSameVersion() {
        Document document = upload(sandbox, "a.pdf", "%PDF-1.4\n%%EOF\n");
        RepositoryView view = open(sandbox, true);
        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");
        IFrame frame = fragmentComponent(view, "preview", "previewFrame");
        TestGrids.clickRow(grid, document, false);
        String shown = frame.getElement().getAttribute("src");

        clickStatus(fragmentComponent(view, "documentList", "statusButton"), TestStatuses.active(dataManager));

        assertThat(frame.getElement().getAttribute("src")).isEqualTo(shown);
    }

    // the column follows the lifecycle of the statuses, not the alphabet of their names
    @Test
    void theStatusColumnSortsTheDocumentsByThePlaceOfTheirStatus() {
        upload(sandbox, "a-draft.txt");
        Document active = upload(sandbox, "b-active.txt");
        Document archived = upload(sandbox, "c-archived.txt");
        documentService.changeStatus(List.of(active, archived), TestStatuses.active(dataManager));
        documentService.changeStatus(List.of(archived), TestStatuses.archived(dataManager));
        RepositoryView view = open(sandbox, false);
        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");

        grid.sort(List.of(new GridSortOrder<>(grid.getColumnByKey("status"), SortDirection.DESCENDING)));

        assertThat(grid.getItems()).isNotNull();
        assertThat(grid.getItems().getItems()).extracting(Document::getName)
                .containsExactly("c-archived.txt", "b-active.txt", "a-draft.txt");
    }

    @Test
    void theStatusMenuOffersTheStatusesEverySelectedDocumentMayHave() {
        Document draft = upload(sandbox, "draft.txt");
        Document active = upload(sandbox, "active.txt");
        documentService.changeStatus(List.of(active), TestStatuses.active(dataManager));
        RepositoryView view = open(sandbox, false);
        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");
        DropdownButton statusButton = fragmentComponent(view, "documentList", "statusButton");

        TestGrids.clickRow(grid, draft, false);
        TestGrids.clickRow(grid, active, true);
        // a draft may become active, and an active document is active already
        assertThat(statusButton.getItems()).extracting(DropdownButtonItem::getId)
                .containsExactly("status-" + DocumentStatus.ACTIVE_ID);

        TestGrids.clickRow(grid, active, false);
        assertThat(statusButton.getItems()).extracting(DropdownButtonItem::getId)
                .containsExactly("status-" + DocumentStatus.ARCHIVED_ID);

        clickStatus(statusButton, TestStatuses.archived(dataManager));

        assertThat(dataManager.load(Document.class).id(active.getId()).one().getStatus())
                .isEqualTo(TestStatuses.archived(dataManager));
    }

    @Test
    void theContextMenuOffersTheSameStatusesInASubmenu() {
        Document active = upload(sandbox, "active.txt");
        documentService.changeStatus(List.of(active), TestStatuses.active(dataManager));
        RepositoryView view = open(sandbox, false);
        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");

        TestGrids.openContextMenu(grid, active);
        GridMenuItem<Document> statusItem = grid.getContextMenu().getItems().stream()
                .filter(item -> FragmentUtils.getComponentId(item).orElse("").equals("statusDocumentItem"))
                .findFirst()
                .orElseThrow();

        assertThat(statusItem.isEnabled()).isTrue();
        assertThat(statusItem.getSubMenu().getItems()).hasSize(1);
    }

    @Test
    void theStatusButtonIsEnabledWhenTheSelectionHasAStatusToMoveTo() {
        Document draft = upload(sandbox, "draft.txt");
        Document active = upload(sandbox, "active.txt");
        documentService.changeStatus(List.of(active), TestStatuses.active(dataManager));
        RepositoryView view = open(sandbox, false);
        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");
        DropdownButton statusButton = fragmentComponent(view, "documentList", "statusButton");

        assertThat(statusButton.isEnabled()).isFalse();

        TestGrids.clickRow(grid, draft, false);
        assertThat(statusButton.isEnabled()).isTrue();

        // the draft may become active, and the active document stays as it is
        TestGrids.clickRow(grid, active, true);
        assertThat(statusButton.isEnabled()).isTrue();
    }

    @Test
    void thePreviewIsServedWhileADialogCoversIt() {
        Document document = upload(sandbox, "a.pdf", "%PDF-1.4\n%%EOF\n");
        RepositoryView view = open(sandbox, true);
        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");

        TestGrids.clickRow(grid, document, false);

        IFrame frame = fragmentComponent(view, "preview", "previewFrame");
        assertThat(frame.isVisible()).isTrue();
        // a double click opens the card while the browser is still fetching the preview behind it
        assertThat(requestHandlerOf(frame).isAllowInert()).isTrue();
    }

    @Test
    void theAddressKeepsThePreviewMode() {
        RepositoryView view = open(sandbox, true);

        JmixButton previewButton = fragmentComponent(view, "documentList", "previewButton");
        assertThat(previewButton.getElement().getAttribute("aria-pressed")).isEqualTo("true");
    }

    @Test
    void theOpenButtonOpensTheCardOfTheSelectedDocument() {
        Document document = upload(sandbox, "a.txt");
        RepositoryView view = open(sandbox, false);
        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");
        JmixButton openButton = fragmentComponent(view, "documentList", "openButton");

        TestGrids.clickRow(grid, document, false);
        openButton.click();

        DocumentDetailView card = UiTestUtils.getLastOpenedViewDialog();
        assertThat(card.getEditedEntity()).isEqualTo(document);
    }

    @Test
    void aDocumentDeletedSinceTheListLoadedIsSaidToBeUnavailableOnDoubleClick() {
        Document document = upload(sandbox, "a.txt");
        RepositoryView view = open(sandbox, false);
        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");

        unconstrainedDataManager.remove(document);
        TestGrids.doubleClickRow(grid, document);

        assertUnavailableNotice();
        assertThat(grid.getItems().getItems()).contains(document);
    }

    @Test
    void aDocumentDeletedSinceTheListLoadedIsSaidToBeUnavailableOnTheOpenButton() {
        Document document = upload(sandbox, "a.txt");
        RepositoryView view = open(sandbox, false);
        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");
        JmixButton openButton = fragmentComponent(view, "documentList", "openButton");

        TestGrids.clickRow(grid, document, false);
        unconstrainedDataManager.remove(document);
        openButton.click();

        assertUnavailableNotice();
    }

    @Test
    void theUploadIsTheMainButtonAndTheDeleteIsDangerous() {
        RepositoryView view = open(sandbox, false);
        JmixUpload<TemporaryStorage.FileInfo> upload = fragmentComponent(view, "documentList", "documentsUpload");
        JmixButton deleteButton = fragmentComponent(view, "documentList", "deleteButton");

        assertThat(((Button) upload.getUploadButton()).getThemeNames())
                .contains(ButtonVariant.PRIMARY.getVariantName());

        assertThat(deleteButton.getThemeNames()).contains(ButtonVariant.ERROR.getVariantName());
    }

    @Test
    void theDocumentListShowsWhoChangedTheDocumentItself() {
        RepositoryView view = open(sandbox, false);
        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");

        // who and when share one column, as in the card
        assertThat(grid.getColumnByKey("changedDate")).isNotNull();
        assertThat(grid.getColumnByKey("changedBy")).isNull();
        // the record stamps change with an access change and a move too
        assertThat(grid.getColumnByKey("lastModifiedBy")).isNull();
        assertThat(grid.getColumnByKey("lastModifiedDate")).isNull();
    }

    @Test
    void theDocumentListNamesWhoChangedTheDocumentAndWhenInThePersonsTimeZone() {
        Document document = unconstrainedDataManager.load(Id.of(upload(sandbox, "a.txt"))).one();
        document.setChangedBy("maria");
        document.setChangedDate(OffsetDateTime.of(2020, 1, 15, 12, 0, 0, 0, ZoneOffset.UTC));
        unconstrainedDataManager.save(document);

        // the person works in Kiritimati, where noon in UTC is already the next day; the user of the test's login
        // is loaded for this test alone
        ((User) currentAuthentication.getUser()).setTimeZoneId("Pacific/Kiritimati");
        assertThat(currentAuthentication.getTimeZone().getID()).isEqualTo("Pacific/Kiritimati");

        User maria = dataManager.load(User.class)
                .query("e.username = :username")
                .parameter("username", "maria")
                .one();

        RepositoryView view = open(sandbox, false);
        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");
        DataGridItems<Document> items = grid.getItems();

        assertThat(items).isNotNull();
        Document shown = items.getItems().iterator().next();

        assertThat(TestGrids.property(grid, "changedDate", "text", shown))
                .isEqualTo(metadataTools.getInstanceName(maria) + " · "
                        + datatypeFormatter.formatLocalDate(LocalDate.of(2020, 1, 16)));
        assertThat(TestGrids.property(grid, "changedDate", "time", shown))
                .isEqualTo(datatypeFormatter.formatLocalDateTime(LocalDateTime.of(2020, 1, 16, 2, 0)));
        // the time is the title of the cell: the tooltip that a column's tooltip generator puts into the grid fails in
        // the browser of Vaadin 25.3
        assertThat(grid.getElement().getChildren()).noneMatch(child -> "tooltip".equals(child.getAttribute("slot")));
    }

    @Test
    void theFileSizeIsHiddenUntilThePersonShowsIt() {
        RepositoryView view = open(sandbox, false);
        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");
        Grid.Column<Document> size = grid.getColumnByKey("fileSize");

        assertThat(size).isNotNull();
        assertThat(size.isVisible()).isFalse();
    }

    @Test
    void thePersonChoosesTheColumnsOfTheDocumentList() {
        RepositoryView view = open(sandbox, false);
        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");
        JmixGridColumnVisibility columns = fragmentComponent(view, "documentList", "columnsButton");

        assertThat(columns.getGrid()).isSameAs(grid);
        assertThat(columns.getMenuItem("fileSize")).isNotNull();
        // the name stays: rows without names leave nothing to know a document by
        assertThat(columns.getMenuItem("name")).isNull();
        assertThat(columns.isHideAllEnabled()).isFalse();
        // an icon with a tooltip, as the other buttons of the toolbar
        assertThat(columns.getText()).isEmpty();
        assertThat(columns.getTitle()).isNotBlank();
    }

    @Test
    void settingsSavedWithAColumnThatIsGoneStillApply() {
        RepositoryView first = open(sandbox, false);
        // the settings of an earlier version of the list kept a column it no longer has
        this.<DataGrid<Document>>fragmentComponent(first, "documentList", "documentsGrid")
                .addColumn(Document::getChangedBy)
                .setKey("changedBy");
        viewNavigators.view(UiTestUtils.getCurrentView(), MainView.class).navigate();

        RepositoryView view = open(sandbox, false);

        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");
        assertThat(grid.getColumnByKey("changedBy")).isNull();
        assertThat(grid.getColumnByKey("changedDate")).isNotNull();
    }

    @Test
    void theColumnsFitTheirContentAgainWhenTheDocumentsReload() {
        upload(sandbox, "a.txt");
        RepositoryView view = open(sandbox, false);
        DocumentListFragment documentList = UiTestUtils.getComponent(view, "documentList");
        pendingClientCalls();

        documentList.reload();

        assertThat(pendingClientCalls()).anyMatch(call -> call.contains("recalculateColumnWidths"));
    }

    @Test
    void aColumnTheUserResizedNoLongerFitsItsContent() {
        RepositoryView view = open(sandbox, false);
        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");
        Grid.Column<Document> status = grid.getColumnByKey("status");
        Grid.Column<Document> category = grid.getColumnByKey("category");
        assertThat(status).isNotNull();
        assertThat(category).isNotNull();

        // the browser reports a drag of the column border with the internal id of the column
        ComponentUtil.fireEvent(grid, new ColumnResizeEvent<>(grid, true, status.getElement().getProperty("_flowId")));

        assertThat(status.isAutoWidth()).isFalse();
        assertThat(category.isAutoWidth()).isTrue();
    }

    @Test
    void theTypeFollowsTheNameInTheDocumentList() {
        Category contract = dataManager.load(Category.class)
                .query("select c from dynat_Category c where c.name = 'Contract' and c.entityType = 'Document'")
                .one();

        Document document = upload(sandbox, "contract.txt");
        Document typed = dataManager.load(Id.of(document)).one();
        typed.setCategory(contract);
        documentService.save(typed, null, null);

        RepositoryView view = open(sandbox, false);
        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");
        DataGridItems<Document> items = grid.getItems();

        assertThat(grid.getColumns()).extracting(Grid.Column::getKey).startsWith("name", "category");
        assertThat(items).isNotNull();
        assertThat(items.getItems()).extracting(Document::getCategory).containsExactly(contract);
    }

    @Test
    void theTagsFollowTheTypeAndAClickOnOneOpensThePageOfTheTag() {
        String lease = "Lease " + UUID.randomUUID().toString().substring(0, 8);
        Tag tag = TestTags.tag(unconstrainedDataManager, lease);
        TestTags.tagged(unconstrainedDataManager, upload(sandbox, "a.txt"), tag);

        RepositoryView view = open(sandbox, false);
        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");
        DataGridItems<Document> items = grid.getItems();
        assertThat(items).isNotNull();
        Document document = items.getItems().iterator().next();
        Component tags = TestGrids.render(grid, "tags", document);
        Grid.Column<Document> tagsColumn = grid.getColumnByKey("tags");
        assertThat(tagsColumn).isNotNull();

        assertThat(grid.getColumns()).extracting(Grid.Column::getKey).startsWith("name", "category", "tags");
        assertThat(tagsColumn.isSortable()).isFalse();
        assertThat(entityStates.isLoaded(document, "tags")).isTrue();
        assertThat(TestTags.names(tags)).containsExactly(lease);

        TestTags.click(tags, lease);

        assertThat((View<?>) UiTestUtils.getCurrentView()).isInstanceOf(DocumentSearchView.class);
        assertThat(TestTags.tagInAddress()).hasValue(lease);
    }

    @Test
    void theNameKeepsTakingTheFreeSpaceWhenTheSavedSettingsComeBack() {
        open(sandbox, false);
        // leaving the view saves the settings of its fragments, and the next visit applies them
        viewNavigators.view(UiTestUtils.getCurrentView(), MainView.class).navigate();

        RepositoryView view = open(sandbox, false);

        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");
        assertThat(grid.getColumnByKey("name").getWidth()).isEqualTo("15em");
        assertThat(grid.getColumnByKey("name").getFlexGrow()).isEqualTo(1);
    }

    @Test
    void theNameKeepsTheWidthTheUserGaveIt() {
        RepositoryView first = open(sandbox, false);
        // a drag of the column border leaves the new width on the server
        this.<DataGrid<Document>>fragmentComponent(first, "documentList", "documentsGrid")
                .getColumnByKey("name")
                .setWidth("300px");
        viewNavigators.view(UiTestUtils.getCurrentView(), MainView.class).navigate();

        RepositoryView view = open(sandbox, false);

        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");
        assertThat(grid.getColumnByKey("name").getWidth()).isEqualTo("300px");
        assertThat(grid.getColumnByKey("name").getFlexGrow()).isZero();
    }

    private RepositoryView open(Folder folder, boolean preview) {
        Map<String, List<String>> parameters = new HashMap<>();
        parameters.put("folder", List.of(folder.getId().toString()));
        if (preview) {
            parameters.put("preview", List.of(""));
        }

        viewNavigators.view(UiTestUtils.getCurrentView(), RepositoryView.class)
                .withQueryParameters(new QueryParameters(parameters))
                .navigate();

        return UiTestUtils.getCurrentView();
    }

    /**
     * The address the view asks the browser to show. The view writes it right before the response,
     * as {@code history.replaceState} or as the router's "vaadin-navigate" event with {@code replace: true}.
     */
    private Optional<String> replacedAddress() {
        UI ui = UI.getCurrent();
        ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
        return ui.getInternals().dumpPendingJavaScriptInvocations().stream()
                .map(PendingJavaScriptInvocation::getInvocation)
                .filter(invocation -> invocation.getExpression().contains("replaceState")
                        || invocation.getExpression().contains("replace: true"))
                .map(invocation -> String.valueOf(invocation.getParameters().get(1)))
                .reduce((first, last) -> last);
    }

    // the calls of the browser's code that the server sends with its next response
    private List<String> pendingClientCalls() {
        UI ui = UI.getCurrent();
        ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
        return ui.getInternals().dumpPendingJavaScriptInvocations().stream()
                .map(invocation -> invocation.getInvocation().getExpression())
                .toList();
    }

    private ElementRequestHandler requestHandlerOf(IFrame frame) {
        URI src = URI.create(frame.getElement().getAttribute("src"));
        AbstractStreamResource resource = VaadinSession.getCurrent().getResourceRegistry().getResource(src)
                .orElseThrow();

        return ((StreamResourceRegistry.ElementStreamResource) resource).getElementRequestHandler();
    }

    private List<String> documentNames(RepositoryView view) {
        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");
        return grid.getItems().getItems().stream().map(Document::getName).toList();
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

    private int currentRevisionNumber(Document document) {
        return dataManager.load(Document.class)
                .id(document.getId())
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE).add("currentRevision", FetchPlan.BASE))
                .one()
                .getCurrentRevision()
                .getNumber();
    }

    private List<String> breadcrumbs(RepositoryView view) {
        HorizontalLayout breadcrumbs = UiTestUtils.getComponent(view, "breadcrumbs");
        return breadcrumbs.getChildren()
                .map(step -> step.getElement().getTextRecursively())
                .filter(text -> !text.equals("/"))
                .toList();
    }

    // a dropdown item has no click of its own: its listener hangs on the menu item that holds its content
    private static void clickStatus(DropdownButton button, DocumentStatus status) {
        ComponentItem item = (ComponentItem) button.getItems().stream()
                .filter(candidate -> candidate.getId().equals("status-" + status.getId()))
                .findFirst()
                .orElseThrow();

        MenuItem menuItem = (MenuItem) item.getContent().getParent().orElseThrow();
        ComponentUtil.fireEvent(menuItem, new ClickEvent<>(menuItem));
    }

    @SuppressWarnings("unchecked")
    private <T> T fragmentComponent(RepositoryView view, String fragmentId, String componentId) {
        return (T) FragmentUtils.getComponent(UiTestUtils.getComponent(view, fragmentId), componentId);
    }

    private Folder folder(Folder parent, String name) {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(parent);
        folder.setName(name);
        return folderService.save(folder);
    }

    private void assertUnavailableNotice() {
        NotificationInfo notification = UiTestUtils.getLastOpenedNotification();

        assertThat(UiTestUtils.getOpenedViewDialogs()).isEmpty();
        assertThat(notification).isNotNull();
        assertThat(notification.getText())
                .isEqualTo(messages.getMessage(DocumentDetailView.class, "documentUnavailable"));
    }

    private Document upload(Folder folder, String fileName) {
        return upload(folder, fileName, "text");
    }

    private Document upload(Folder folder, String fileName, String content) {
        return documentService.upload(folder, List.of(TestFiles.file(tempDir, fileName, content)))
                .created().getFirst();
    }
}
