package com.company.dms.view.repository;

import com.company.dms.DMSApplication;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.document.Document;
import com.company.dms.model.document.DocumentStatus;
import com.company.dms.model.folder.Folder;
import com.company.dms.model.tag.Tag;
import com.company.dms.model.user.User;
import com.company.dms.test_support.EmployeeUiTestAuthenticator;
import com.company.dms.test_support.TestFiles;
import com.company.dms.test_support.TestGrids;
import com.company.dms.test_support.TestTags;
import com.company.dms.test_support.TestUploads;
import com.company.dms.view.document.DocumentDetailView;
import com.company.dms.view.folder.FolderDetailView;
import com.company.dms.view.folder.FolderLookupView;
import com.company.dms.view.main.MainView;
import com.company.dms.view.search.DocumentSearchView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.ColumnResizeEvent;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.contextmenu.GridMenuItem;
import com.vaadin.flow.component.html.IFrame;
import com.vaadin.flow.component.internal.PendingJavaScriptInvocation;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
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
import io.jmix.flowui.kit.component.dropdownbutton.DropdownButton;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DMSApplication.class, FlowuiTestAssistConfiguration.class})
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

        FolderTreeFragment folderTree = UiTestUtils.getComponent(view, "folderTree");
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

        TreeDataGrid<Folder> tree = fragmentComponent(UiTestUtils.getCurrentView(), "folderTree", "foldersTree");
        assertThat(tree.isExpanded(folderService.getRoot())).isTrue();
    }

    @Test
    void theBreadcrumbsShowTheNewNameOfTheRenamedFolder() {
        RepositoryView view = open(folder(sandbox, "Clients"), false);
        TreeDataGrid<Folder> tree = fragmentComponent(view, "folderTree", "foldersTree");

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
        TreeDataGrid<Folder> tree = fragmentComponent(view, "folderTree", "foldersTree");

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
    void anUploadCountsInTheFolderTree() {
        RepositoryView view = open(sandbox, false);
        TreeDataGrid<Folder> tree = fragmentComponent(view, "folderTree", "foldersTree");
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
        FolderTreeFragment folderTree = UiTestUtils.getComponent(view, "folderTree");
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

    // menu items take no action variant, so the stylesheet colours the delete item by its class
    @Test
    void theMenuOfADocumentOffersEveryActionAndMarksItsDeleteItem() {
        RepositoryView view = open(sandbox, false);
        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");
        List<GridMenuItem<Document>> items = grid.getContextMenu().getItems();

        assertThat(items)
                .extracting(item -> FragmentUtils.getComponentId(item).orElse(""))
                .containsExactly("openDocumentItem", "downloadDocumentItem", "discussDocumentItem",
                        "moveDocumentItem", "activateDocumentItem", "archiveDocumentItem", "unarchiveDocumentItem",
                        "accessDocumentItem", "deleteDocumentItem");
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
        TreeDataGrid<Folder> tree = fragmentComponent(view, "folderTree", "foldersTree");
        List<GridMenuItem<Folder>> items = tree.getContextMenu().getItems();

        assertThat(items)
                .extracting(item -> FragmentUtils.getComponentId(item).orElse(""))
                .containsExactly("createFolderContextItem", "renameFolderContextItem", "moveFolderContextItem",
                        "accessFolderContextItem", "deleteFolderContextItem");
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

        grid.getAction("activateAction").actionPerform(grid);

        assertThat(previewLayout.isSidePanelOpened()).isTrue();
        assertThat(documentList.getFocusedDocument()).isEqualTo(b);
        assertThat(documentList.getFocusedDocument().getStatus()).isEqualTo(DocumentStatus.ACTIVE);
    }

    @Test
    void anActionDoesNotReloadThePreviewOfTheSameVersion() {
        Document document = upload(sandbox, "a.pdf", "%PDF-1.4\n%%EOF\n");
        RepositoryView view = open(sandbox, true);
        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");
        IFrame frame = fragmentComponent(view, "preview", "previewFrame");
        TestGrids.clickRow(grid, document, false);
        String shown = frame.getElement().getAttribute("src");

        grid.getAction("activateAction").actionPerform(grid);

        assertThat(frame.getElement().getAttribute("src")).isEqualTo(shown);
    }

    @Test
    void theStatusActionsOfferOnlyWhatEverySelectedDocumentAllows() {
        Document draft = upload(sandbox, "draft.txt");
        Document active = upload(sandbox, "active.txt");
        documentService.changeStatus(List.of(active), DocumentStatus.ACTIVE);
        RepositoryView view = open(sandbox, false);
        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");

        TestGrids.clickRow(grid, draft, false);
        TestGrids.clickRow(grid, active, true);
        assertThat(List.of("activateAction", "archiveAction", "unarchiveAction"))
                .allSatisfy(actionId -> assertThat(grid.getAction(actionId).isEnabled()).as(actionId).isFalse());

        TestGrids.clickRow(grid, active, false);
        assertThat(grid.getAction("archiveAction").isEnabled()).isTrue();
        assertThat(grid.getAction("activateAction").isEnabled()).isFalse();
    }

    @Test
    void theStatusButtonIsEnabledOnlyWhenATransitionFitsTheSelection() {
        Document draft = upload(sandbox, "draft.txt");
        Document active = upload(sandbox, "active.txt");
        documentService.changeStatus(List.of(active), DocumentStatus.ACTIVE);
        RepositoryView view = open(sandbox, false);
        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");
        DropdownButton statusButton = fragmentComponent(view, "documentList", "statusButton");

        assertThat(statusButton.isEnabled()).isFalse();

        TestGrids.clickRow(grid, draft, false);
        assertThat(statusButton.isEnabled()).isTrue();

        TestGrids.clickRow(grid, active, true);
        assertThat(statusButton.isEnabled()).isFalse();
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
    void theDocumentListNamesWhoChangedTheDocument() {
        Document document = unconstrainedDataManager.load(Id.of(upload(sandbox, "a.txt"))).one();
        document.setChangedBy("maria");
        unconstrainedDataManager.save(document);

        User maria = dataManager.load(User.class)
                .query("e.username = :username")
                .parameter("username", "maria")
                .one();

        RepositoryView view = open(sandbox, false);
        DataGrid<Document> grid = fragmentComponent(view, "documentList", "documentsGrid");
        DataGridItems<Document> items = grid.getItems();

        assertThat(items).isNotNull();
        Grid.Column<Document> changed = grid.getColumnByKey("changedDate");
        assertThat(changed).isNotNull();
        Document shown = items.getItems().iterator().next();
        String date = datatypeFormatter.formatLocalDate(shown.getChangedDate()
                .atZoneSameInstant(currentAuthentication.getTimeZone().toZoneId())
                .toLocalDate());

        assertThat(TestGrids.text(grid, "changedDate", shown))
                .isEqualTo(metadataTools.getInstanceName(maria) + " · " + date);
        assertThat(changed.getTooltipGenerator().apply(shown))
                .isEqualTo(datatypeFormatter.formatOffsetDateTime(shown.getChangedDate()));
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

    private Document upload(Folder folder, String fileName) {
        return upload(folder, fileName, "text");
    }

    private Document upload(Folder folder, String fileName, String content) {
        return documentService.upload(folder, List.of(TestFiles.file(tempDir, fileName, content)))
                .created().getFirst();
    }
}
