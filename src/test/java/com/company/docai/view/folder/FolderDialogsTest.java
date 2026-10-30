package com.company.docai.view.folder;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.folder.Folder;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.TestGrids;
import com.company.docai.view.main.MainView;
import com.company.docai.view.repository.FolderTreeFragment;
import com.company.docai.view.repository.RepositoryView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.NativeLabel;
import com.vaadin.flow.router.QueryParameters;
import io.jmix.core.DataManager;
import io.jmix.core.MetadataTools;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.Views;
import io.jmix.flowui.component.grid.TreeDataGrid;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.fragment.FragmentUtils;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.view.ViewControllerUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class FolderDialogsTest {

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private Views views;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private MetadataTools metadataTools;

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        sandbox = folder(folderService.getRoot(), "folder-dialogs-test-" + UUID.randomUUID());
    }

    @AfterEach
    void deleteSandbox() {
        folderService.delete(sandbox);
    }

    @Test
    void aNewFolderAppearsInsideTheSelectedOne() {
        FolderTreeFragment folderTree = openTree(sandbox);

        FolderDetailView dialog = openCreateDialog(folderTree);
        nameField(dialog).setValue("Reports");
        saveButton(dialog).click();

        assertThat(folderTree.getSelectedFolder().getName()).isEqualTo("Reports");
        assertThat(folderTree.getPath(folderTree.getSelectedFolder())).contains(sandbox);
    }

    @Test
    void aNewFolderShowsWhereItGoes() {
        FolderTreeFragment folderTree = openTree(folder(sandbox, "Clients"));

        FolderDetailView dialog = openCreateDialog(folderTree);

        TypedTextField<String> parentField = UiTestUtils.getComponent(dialog, "parentField");
        assertThat(parentField.isVisible()).isTrue();
        assertThat(parentField.isReadOnly()).isTrue();
        assertThat(parentField.getValue()).isEqualTo(String.join(" / ",
                metadataTools.getInstanceName(folderService.getRoot()), sandbox.getName(), "Clients"));
    }

    @Test
    void aRenamedFolderShowsOnlyItsName() {
        FolderTreeFragment folderTree = openTree(folder(sandbox, "Clients"));

        foldersTree(folderTree).getAction("renameFolderAction").actionPerform(foldersTree(folderTree));

        FolderDetailView dialog = UiTestUtils.getLastOpenedViewDialog();
        assertThat(UiTestUtils.<TypedTextField<String>>getComponent(dialog, "parentField").isVisible()).isFalse();
    }

    @Test
    void aTakenNameKeepsTheDialogOpen() {
        folder(sandbox, "Reports");
        FolderTreeFragment folderTree = openTree(sandbox);

        FolderDetailView dialog = openCreateDialog(folderTree);
        nameField(dialog).setValue("Reports");

        assertThatThrownBy(() -> saveButton(dialog).click()).hasRootCauseInstanceOf(OperationRejectedException.class);
        assertThat(UiTestUtils.getOpenedViewDialogs()).contains(dialog);
    }

    @Test
    void theRootOffersOnlyANewFolder() {
        TreeDataGrid<Folder> tree = foldersTree(openTree(folderService.getRoot()));

        assertThat(tree.getAction("createFolderAction").isEnabled()).isTrue();
        assertThat(List.of("renameFolderAction", "moveFolderAction", "deleteFolderAction"))
                .allSatisfy(actionId -> assertThat(tree.getAction(actionId).isEnabled()).as(actionId).isFalse());
    }

    @Test
    void theFolderActionsFollowTheClickedFolder() {
        Folder child = folder(sandbox, "Child");
        TreeDataGrid<Folder> tree = foldersTree(openTree(sandbox));
        Folder root = tree.getItems().getItems().stream()
                .filter(Folder::isRoot)
                .findFirst()
                .orElseThrow();

        List<String> actionIds = List.of("renameFolderAction", "moveFolderAction", "deleteFolderAction");

        TestGrids.clickRendered(tree, "name", root);
        assertThat(actionIds)
                .allSatisfy(actionId -> assertThat(tree.getAction(actionId).isEnabled()).as(actionId).isFalse());

        TestGrids.clickRendered(tree, "name", child);
        assertThat(actionIds)
                .allSatisfy(actionId -> assertThat(tree.getAction(actionId).isEnabled()).as(actionId).isTrue());
    }

    @Test
    void theMoveDialogHidesTheMovedFolderAndItsSubfolders() {
        Folder parent = folder(sandbox, "Parent");
        Folder child = folder(parent, "Child");
        Folder other = folder(sandbox, "Other");
        FolderTreeFragment folderTree = openTree(parent);

        foldersTree(folderTree).getAction("moveFolderAction").actionPerform(foldersTree(folderTree));

        FolderLookupView lookup = UiTestUtils.getLastOpenedViewDialog();
        CollectionContainer<Folder> foldersDc = ViewControllerUtils.getViewData(lookup).getContainer("foldersDc");
        assertThat(foldersDc.getItems()).contains(sandbox, other).doesNotContain(parent, child);
    }

    @Test
    void aClickOnTheNameSelectsAFolderWithSubfolders() {
        Folder parent = folder(sandbox, "Parent");
        Folder child = folder(parent, "Child");
        FolderTreeFragment folderTree = openTree(child);

        Component name = TestGrids.clickRendered(foldersTree(folderTree), "name", parent);

        // the expand toggle around the name lets a click on a label through
        assertThat(name.getChildren()).singleElement().isInstanceOf(NativeLabel.class);
        assertThat(folderTree.getSelectedFolder()).isEqualTo(parent);
    }

    @Test
    void aClickOnTheNameSelectsAFolderInTheMoveDialog() {
        Folder parent = folder(sandbox, "Parent");
        folder(parent, "Child");
        Folder other = folder(sandbox, "Other");
        FolderTreeFragment folderTree = openTree(other);
        foldersTree(folderTree).getAction("moveFolderAction").actionPerform(foldersTree(folderTree));
        FolderLookupView lookup = UiTestUtils.getLastOpenedViewDialog();
        TreeDataGrid<Folder> lookupTree = UiTestUtils.getComponent(lookup, "foldersTree");

        TestGrids.clickRendered(lookupTree, "name", parent);

        assertThat(lookupTree.getSelectedItems()).containsExactly(parent);
    }

    @Test
    void theMoveDialogFiltersFoldersByNameWithTheirParents() {
        Folder contracts = folder(sandbox, "Contracts");
        Folder signed = folder(contracts, "Signed 2026");
        Folder invoices = folder(sandbox, "Invoices");
        Folder moved = folder(sandbox, "Moved");
        FolderTreeFragment folderTree = openTree(moved);
        foldersTree(folderTree).getAction("moveFolderAction").actionPerform(foldersTree(folderTree));
        FolderLookupView lookup = UiTestUtils.getLastOpenedViewDialog();
        TypedTextField<String> filterField = UiTestUtils.getComponent(lookup, "folderFilterField");

        filterField.setValue("SIGNED");

        TreeDataGrid<Folder> lookupTree = UiTestUtils.getComponent(lookup, "foldersTree");
        assertThat(lookupTree.getItems().getItems())
                .contains(sandbox, contracts, signed)
                .doesNotContain(invoices);

        assertThat(lookupTree.isExpanded(contracts)).isTrue();
    }

    @Test
    void theFilterDoesNotBringBackTheMovedFolder() {
        Folder moved = folder(sandbox, "Contracts");
        Folder movedChild = folder(moved, "Contracts 2026");
        Folder other = folder(sandbox, "Old contracts");
        FolderTreeFragment folderTree = openTree(moved);
        foldersTree(folderTree).getAction("moveFolderAction").actionPerform(foldersTree(folderTree));
        FolderLookupView lookup = UiTestUtils.getLastOpenedViewDialog();
        TypedTextField<String> filterField = UiTestUtils.getComponent(lookup, "folderFilterField");

        filterField.setValue("contracts");

        TreeDataGrid<Folder> lookupTree = UiTestUtils.getComponent(lookup, "foldersTree");
        assertThat(lookupTree.getItems().getItems())
                .contains(other)
                .doesNotContain(moved, movedChild);
    }

    @Test
    void theMoveDialogOffersNoFolderBeforeItsFoldersLoad() {
        FolderLookupView lookup = views.create(FolderLookupView.class);
        TreeDataGrid<Folder> lookupTree = UiTestUtils.getComponent(lookup, "foldersTree");

        Component name = TestGrids.render(lookupTree, "name", sandbox);

        assertThat(name.getChildren()).singleElement()
                .satisfies(label -> assertThat(label.getElement().getClassList()).contains("folder-greyed"));
    }

    private FolderTreeFragment openTree(Folder folder) {
        viewNavigators.view(UiTestUtils.getCurrentView(), RepositoryView.class)
                .withQueryParameters(QueryParameters.of("folder", folder.getId().toString()))
                .navigate();

        return UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "folderTree");
    }

    private FolderDetailView openCreateDialog(FolderTreeFragment folderTree) {
        foldersTree(folderTree).getAction("createFolderAction").actionPerform(foldersTree(folderTree));
        return UiTestUtils.getLastOpenedViewDialog();
    }

    @SuppressWarnings("unchecked")
    private TreeDataGrid<Folder> foldersTree(FolderTreeFragment folderTree) {
        return (TreeDataGrid<Folder>) FragmentUtils.getComponent(folderTree, "foldersTree");
    }

    private TypedTextField<String> nameField(FolderDetailView dialog) {
        return UiTestUtils.getComponent(dialog, "nameField");
    }

    private JmixButton saveButton(FolderDetailView dialog) {
        return UiTestUtils.getComponent(dialog, "saveButton");
    }

    private Folder folder(Folder parent, String name) {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(parent);
        folder.setName(name);
        return folderService.save(folder);
    }
}
