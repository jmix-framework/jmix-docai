package com.company.docai.view.main;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.favorite.FavoriteService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.document.Document;
import com.company.docai.model.favorite.Favorite;
import com.company.docai.model.folder.Folder;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.TestFiles;
import com.company.docai.test_support.TestMenus;
import com.company.docai.view.document.DocumentDetailView;
import com.company.docai.view.repository.FolderTreeFragment;
import com.company.docai.view.repository.RepositoryView;
import com.company.docai.view.trash.TrashView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.QueryParameters;
import io.jmix.core.DataManager;
import io.jmix.core.MetadataTools;
import io.jmix.core.SaveContext;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.UiComponentUtils;
import io.jmix.flowui.component.grid.TreeDataGrid;
import io.jmix.flowui.fragment.FragmentUtils;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.view.View;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The folder tree in the main menu: the same tree on every view, which opens the repository on the chosen folder
 * outside it, marks the shown folder inside it, and names that folder in the header.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class MainViewFolderTreeTest {

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FavoriteService favoriteService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @Autowired
    private MetadataTools metadataTools;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        sandbox = folder(folderService.getRoot(), "main-view-folder-tree-test-" + UUID.randomUUID());
    }

    // no other test keeps favorites, so the marks of every user go
    @AfterEach
    void deleteSandbox() {
        unconstrainedDataManager.save(new SaveContext()
                .removing(unconstrainedDataManager.load(Favorite.class).all().list()));
        folderService.delete(sandbox);
    }

    @Test
    void theTreeStandsInTheMainMenuAndNotInTheRepository() {
        RepositoryView view = open(sandbox);

        assertThat(UiComponentUtils.findComponent(view, "folderTree")).isEmpty();
        assertThat(TestMenus.folderTree().getSelectedFolder()).isEqualTo(sandbox);
    }

    // the tree has no scroll of its own: the folders scroll with the views of the menu under them
    @Test
    void theTreeShowsAllItsRows() {
        open(sandbox);

        TreeDataGrid<Folder> tree = TestMenus.folderTreeComponent("foldersTree");
        assertThat(tree.isAllRowsVisible()).isTrue();
    }

    // the folder field and its button stay on top, while the folders and the views of the menu scroll together
    @Test
    void theViewsOfTheMenuScrollWithTheFolders() {
        open(sandbox);

        VerticalLayout treeFrame = TestMenus.folderTreeComponent("treeFrame");
        assertThat(treeFrame.getChildren().map(Component::getId)).contains(Optional.of("navigation"));
        assertThat(treeFrame.getChildren().toList())
                .doesNotContain(TestMenus.<Component>folderTreeComponent("folderToolbar"));
    }

    @Test
    void theTreeShowsTheFoldersOnAViewOtherThanTheRepository() {
        viewNavigators.view(UiTestUtils.getCurrentView(), TrashView.class).navigate();
        FolderTreeFragment folderTree = TestMenus.folderTree();

        assertThat(folderTree.getPath(folderService.getRoot())).containsExactly(folderService.getRoot());
        assertThat(folderTree.getSelectedFolder()).isNull();
    }

    @Test
    void theRootIsOpenOnAViewOtherThanTheRepository() {
        viewNavigators.view(UiTestUtils.getCurrentView(), TrashView.class).navigate();
        // the tree took its folders when the menu was drawn, before the test made its own, so its root had no children
        TestMenus.folderTree().load();

        TreeDataGrid<Folder> tree = TestMenus.folderTreeComponent("foldersTree");
        assertThat(tree.isExpanded(folderService.getRoot())).isTrue();
    }

    @Test
    void theFavoritesShowOnAViewOtherThanTheRepository() {
        viewNavigators.view(UiTestUtils.getCurrentView(), TrashView.class).navigate();

        // the employee has no marks, so the section shows its hint
        Component emptyHint = FragmentUtils.getComponent(TestMenus.folderTree().getFavorites(), "emptyHint");
        assertThat(emptyHint.isVisible()).isTrue();
    }

    @Test
    void aFolderChosenOnAnotherViewOpensTheRepositoryOnIt() {
        Folder contracts = folder(sandbox, "Contracts");
        viewNavigators.view(UiTestUtils.getCurrentView(), TrashView.class).navigate();
        FolderTreeFragment folderTree = TestMenus.folderTree();
        // the tree took its folders when the menu was drawn, before the test made its own
        folderTree.load();

        folderTree.select(contracts);

        View<?> opened = UiTestUtils.getCurrentView();
        assertThat(opened).isInstanceOf(RepositoryView.class);
        assertThat(folderTree.getSelectedFolder()).isEqualTo(contracts);
        assertThat(opened.getPageTitle()).isEqualTo("Contracts");
    }

    @Test
    void leavingTheRepositoryLeavesNoFolderCurrent() {
        open(sandbox);

        viewNavigators.view(UiTestUtils.getCurrentView(), TrashView.class).navigate();

        TreeDataGrid<Folder> tree = TestMenus.folderTreeComponent("foldersTree");
        assertThat(TestMenus.folderTree().getSelectedFolder()).isNull();
        assertThat(tree.getSelectedItems()).isEmpty();
    }

    @Test
    void theHeaderNamesTheShownFolderAndTheRootIsTheRepository() {
        RepositoryView view = open(sandbox);
        assertThat(view.getPageTitle()).isEqualTo(sandbox.getName());

        TestMenus.folderTree().select(folderService.getRoot());

        assertThat(view.getPageTitle()).isEqualTo(metadataTools.getInstanceName(folderService.getRoot()));
    }

    @Test
    void aDocumentOfTheFavoritesOpensItsCardOverAnotherView() {
        Document lease = documentService.upload(sandbox, List.of(TestFiles.file(tempDir, "lease.txt", "text")))
                .created()
                .getFirst();
        favoriteService.mark(List.of(lease));
        viewNavigators.view(UiTestUtils.getCurrentView(), TrashView.class).navigate();
        TestMenus.folderTree().getFavorites().reload();

        favoriteButton("lease.txt").click();

        DocumentDetailView card = UiTestUtils.getLastOpenedViewDialog();
        assertThat(card.getEditedEntity()).isEqualTo(lease);
        View<?> current = UiTestUtils.getCurrentView();
        assertThat(current).isInstanceOf(TrashView.class);
    }

    @Test
    void aFolderChosenOverANarrowWindowClosesTheMenu() {
        open(sandbox);
        AppLayout layout = (AppLayout) TestMenus.mainView().getChildren().findFirst().orElseThrow();
        // the browser reports the overlay mode of a narrow window
        layout.getElement().setProperty("overlay", true);
        layout.setDrawerOpened(true);

        TestMenus.folderTree().select(folderService.getRoot());

        assertThat(layout.isDrawerOpened()).isFalse();
    }

    private RepositoryView open(Folder folder) {
        viewNavigators.view(UiTestUtils.getCurrentView(), RepositoryView.class)
                .withQueryParameters(QueryParameters.of(RepositoryView.FOLDER_PARAMETER, folder.getId().toString()))
                .navigate();

        return UiTestUtils.getCurrentView();
    }

    // the first button of a row of the favorites opens the item
    private JmixButton favoriteButton(String name) {
        VerticalLayout box = (VerticalLayout) FragmentUtils.getComponent(TestMenus.folderTree().getFavorites(),
                "favoritesBox");
        Component row = box.getChildren()
                .filter(child -> child.getElement().getTextRecursively().startsWith(name))
                .findFirst()
                .orElseThrow();

        return row.getChildren()
                .filter(JmixButton.class::isInstance)
                .map(JmixButton.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private Folder folder(Folder parent, String name) {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(parent);
        folder.setName(name);
        return folderService.save(folder);
    }
}
