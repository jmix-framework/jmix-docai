package com.company.dms.view.repository;

import com.company.dms.DMSApplication;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.document.Document;
import com.company.dms.model.document.DocumentStatus;
import com.company.dms.model.folder.Folder;
import com.company.dms.test_support.EmployeeUiTestAuthenticator;
import com.company.dms.test_support.TestFiles;
import com.company.dms.test_support.TestGrids;
import com.company.dms.test_support.TestUploads;
import com.company.dms.view.document.DocumentDetailView;
import com.company.dms.view.main.MainView;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.IFrame;
import com.vaadin.flow.component.internal.PendingJavaScriptInvocation;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.server.AbstractStreamResource;
import com.vaadin.flow.server.StreamResourceRegistry;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.streams.ElementRequestHandler;
import io.jmix.core.DataManager;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.grid.TreeDataGrid;
import io.jmix.flowui.component.sidepanellayout.SidePanelLayout;
import io.jmix.flowui.component.upload.JmixUpload;
import io.jmix.flowui.fragment.FragmentUtils;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.kit.component.dropdownbutton.DropdownButton;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
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
    private TemporaryStorage temporaryStorage;

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
