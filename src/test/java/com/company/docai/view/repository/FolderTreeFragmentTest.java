package com.company.docai.view.repository;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.folder.Folder;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.TestFiles;
import com.company.docai.test_support.TestGrids;
import com.company.docai.test_support.TestMenus;
import com.company.docai.view.main.MainView;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.router.QueryParameters;
import io.jmix.core.DataManager;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.grid.TreeDataGrid;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.fragment.FragmentUtils;
import io.jmix.flowui.kit.action.Action;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.kit.component.dropdownbutton.DropdownButton;
import io.jmix.flowui.kit.component.dropdownbutton.DropdownButtonItem;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class FolderTreeFragmentTest {

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DataManager dataManager;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        sandbox = folder(folderService.getRoot(), "folder-tree-test-" + UUID.randomUUID());
    }

    @AfterEach
    void deleteSandbox() {
        folderService.delete(sandbox);
    }

    @Test
    void theFilterShowsTheMatchingFoldersWithTheirParents() {
        Folder contracts = folder(sandbox, "Contracts");
        Folder signed = folder(contracts, "Signed 2026");
        Folder invoices = folder(sandbox, "Invoices");
        FolderTreeFragment folderTree = open(sandbox);

        this.<TypedTextField<String>>component(folderTree, "folderFilterField").setValue("SIGNED");

        TreeDataGrid<Folder> tree = component(folderTree, "foldersTree");
        assertThat(tree.getItems().getItems())
                .contains(folderService.getRoot(), sandbox, contracts, signed)
                .doesNotContain(invoices);

        assertThat(tree.isExpanded(contracts)).isTrue();
    }

    @Test
    void reloadingTheTreeKeepsTheFilterTheFieldHolds() {
        Folder contracts = folder(sandbox, "Contracts");
        Folder signed = folder(contracts, "Signed 2026");
        Folder invoices = folder(sandbox, "Invoices");
        FolderTreeFragment folderTree = open(sandbox);
        this.<TypedTextField<String>>component(folderTree, "folderFilterField").setValue("SIGNED");

        folderTree.load();

        TreeDataGrid<Folder> tree = component(folderTree, "foldersTree");

        assertThat(tree.getItems().getItems())
                .contains(folderService.getRoot(), sandbox, contracts, signed)
                .doesNotContain(invoices);
    }

    @Test
    void clearingTheFilterSelectsTheShownFolderAgain() {
        Folder contracts = folder(sandbox, "Contracts");
        Folder invoices = folder(sandbox, "Invoices");
        FolderTreeFragment folderTree = open(contracts);
        TypedTextField<String> filterField = component(folderTree, "folderFilterField");

        filterField.setValue("Invoices");
        // the documents of the shown folder stay on screen while the filter hides it
        assertThat(folderTree.getSelectedFolder()).isEqualTo(contracts);

        filterField.clear();

        TreeDataGrid<Folder> tree = component(folderTree, "foldersTree");
        assertThat(tree.getItems().getItems()).contains(contracts, invoices);
        assertThat(tree.getSelectedItems()).containsExactly(contracts);
    }

    @Test
    void filteringDoesNotShowTheSameFolderAgain() {
        folder(sandbox, "Contracts");
        FolderTreeFragment folderTree = open(sandbox);
        List<Folder> shown = new ArrayList<>();
        folderTree.setSelectionHandler(shown::add);
        TypedTextField<String> filterField = component(folderTree, "folderFilterField");

        filterField.setValue("Contracts");
        filterField.clear();

        assertThat(shown).isEmpty();
    }

    @Test
    void aFolderShowsTheNumberOfItsDocumentsTogetherWithSubfolders() {
        Folder parent = folder(sandbox, "Parent");
        Folder child = folder(parent, "Child");
        Folder empty = folder(sandbox, "Empty");
        upload(parent, "a.txt");
        upload(child, "b.txt");
        upload(child, "c.txt");
        FolderTreeFragment folderTree = open(sandbox);
        TreeDataGrid<Folder> tree = component(folderTree, "foldersTree");

        assertThat(TestGrids.render(tree, "name", parent).getElement().getTextRecursively()).isEqualTo("Parent (3)");
        assertThat(TestGrids.render(tree, "name", child).getElement().getTextRecursively()).isEqualTo("Child (2)");
        assertThat(TestGrids.render(tree, "name", empty).getElement().getTextRecursively()).isEqualTo("Empty (0)");
    }

    @Test
    void aRightClickSelectsTheFolderUnderThePointer() {
        Folder contracts = folder(sandbox, "Contracts");
        Folder invoices = folder(sandbox, "Invoices");
        FolderTreeFragment folderTree = open(contracts);
        TreeDataGrid<Folder> tree = component(folderTree, "foldersTree");

        TestGrids.openContextMenu(tree, invoices);

        assertThat(tree.getSelectedItems()).containsExactly(invoices);
        // the menu acts on that folder, and its documents are on screen, as after a left click
        assertThat(folderTree.getSelectedFolder()).isEqualTo(invoices);
    }

    // one button creates a folder, the other lists the actions on the chosen folder; both show only their icons, so
    // the row leaves its width to the filter, and every folder opens by its own arrow
    @Test
    void theFolderRowHoldsTheFilterAButtonThatCreatesAFolderAndTheActionsOnTheChosenOne() {
        FolderTreeFragment folderTree = open(sandbox);
        HorizontalLayout toolbar = component(folderTree, "folderToolbar");
        JmixButton createButton = component(folderTree, "createFolderButton");
        DropdownButton actionsButton = component(folderTree, "folderActionsButton");
        TreeDataGrid<Folder> tree = component(folderTree, "foldersTree");
        Action create = tree.getAction("createFolderAction");

        assertThat(toolbar.getChildren().toList())
                .containsExactly(component(folderTree, "folderFilterField"), createButton, actionsButton);
        assertThat(createButton.getAction()).isSameAs(create);
        assertThat(createButton.getText()).isNullOrEmpty();
        assertThat(createButton.getTitle()).isEqualTo(create.getText());
        assertThat(actionsButton.getItems())
                .extracting(DropdownButtonItem::getId)
                .containsExactly("renameFolderItem", "moveFolderItem", "accessFolderItem", "favoriteFolderItem",
                        "deleteFolderItem");
        assertThat(tree.getAction("expandAllAction")).isNull();
        assertThat(tree.getAction("collapseAllAction")).isNull();
    }

    private FolderTreeFragment open(Folder folder) {
        viewNavigators.view(UiTestUtils.getCurrentView(), RepositoryView.class)
                .withQueryParameters(QueryParameters.of("folder", folder.getId().toString()))
                .navigate();

        return TestMenus.folderTree();
    }

    @SuppressWarnings("unchecked")
    private <T> T component(FolderTreeFragment folderTree, String id) {
        return (T) FragmentUtils.getComponent(folderTree, id);
    }

    private Folder folder(Folder parent, String name) {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(parent);
        folder.setName(name);
        return folderService.save(folder);
    }

    private void upload(Folder folder, String fileName) {
        documentService.upload(folder, List.of(TestFiles.file(tempDir, fileName, "text")));
    }
}
