package com.company.dms.view.favorite;

import com.company.dms.DMSApplication;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.favorite.FavoriteService;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.document.Document;
import com.company.dms.model.favorite.Favorite;
import com.company.dms.model.folder.Folder;
import com.company.dms.test_support.EmployeeUiTestAuthenticator;
import com.company.dms.test_support.TestFiles;
import com.company.dms.test_support.TestGrids;
import com.company.dms.view.document.DocumentDetailView;
import com.company.dms.view.folder.FolderDetailView;
import com.company.dms.view.main.MainView;
import com.company.dms.view.repository.FolderTreeFragment;
import com.company.dms.view.repository.RepositoryView;
import com.company.dms.view.trash.TrashView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.QueryParameters;
import io.jmix.core.DataManager;
import io.jmix.core.Messages;
import io.jmix.core.SaveContext;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.details.JmixDetails;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.grid.TreeDataGrid;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.fragment.FragmentUtils;
import io.jmix.flowui.icon.Icons;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.kit.icon.JmixFontIcon;
import io.jmix.flowui.settings.UserSettingsCache;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DMSApplication.class, FlowuiTestAssistConfiguration.class})
class FavoritesFragmentTest {

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private FavoriteService favoriteService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @Autowired
    private UserSettingsCache userSettingsCache;

    @Autowired
    private Messages messages;

    @Autowired
    private Icons icons;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        sandbox = folder(folderService.getRoot(), "favorites-fragment-test-" + UUID.randomUUID());
    }

    // no other test keeps favorites, so the marks of every user go
    @AfterEach
    void deleteSandbox() {
        unconstrainedDataManager.save(new SaveContext()
                .removing(unconstrainedDataManager.load(Favorite.class).all().list()));
        folderService.delete(sandbox);
        // leaving the view saves the settings of its fragments for the user
        userSettingsCache.delete("RepositoryView.folderTree");
        userSettingsCache.delete("RepositoryView.documentList");
    }

    @Test
    void theBlockShowsAHintUntilTheUserMarksSomething() {
        RepositoryView view = open(sandbox);
        assertThat(this.<Span>favoritesComponent(view, "emptyHint").isVisible()).isTrue();

        favoriteService.mark(List.of(sandbox));
        favorites(view).reload();

        assertThat(this.<Span>favoritesComponent(view, "emptyHint").isVisible()).isFalse();
        assertThat(rows(view)).containsExactly(sandbox.getName());
    }

    @Test
    void theFavoritesAreTheFirstSectionInTheFrameOfTheFolderTree() {
        RepositoryView view = open(sandbox);
        FolderTreeFragment folderTree = UiTestUtils.getComponent(view, "folderTree");
        VerticalLayout frame = (VerticalLayout) FragmentUtils.getComponent(folderTree, "treeFrame");
        JmixDetails section = (JmixDetails) FragmentUtils.getComponent(folderTree, "favoritesDetails");

        // under the Folder menu and the filter, which serve both sections
        assertThat(frame.getChildren().toList())
                .containsExactly(section, FragmentUtils.getComponent(folderTree, "foldersTree"));
        assertThat(section.getContent().toList()).containsExactly(favorites(view));
    }

    @Test
    void theBlockListsTheMarksWithTheirPlaces() {
        Folder contracts = folder(sandbox, "Contracts");
        Document lease = upload(contracts, "lease.txt");
        favoriteService.mark(List.of(lease, contracts));

        RepositoryView view = open(sandbox);

        assertThat(rows(view)).containsExactly("Contracts " + sandbox.getName(), "lease.txt Contracts");
    }

    // a narrow block cuts a long name and its folder with an ellipsis, so the hint shows them in full
    @Test
    void aRowShowsItsNameAndFolderInFullOnHover() {
        Folder contracts = folder(sandbox, "Contracts");
        Document lease = upload(contracts, "lease.txt");
        favoriteService.mark(List.of(lease));
        RepositoryView view = open(sandbox);

        Span location = button(view, "lease.txt", 0).getParent()
                .orElseThrow()
                .getChildren()
                .filter(Span.class::isInstance)
                .map(Span.class::cast)
                .findFirst()
                .orElseThrow();

        assertThat(button(view, "lease.txt", 0).getTitle()).isEqualTo("lease.txt");
        assertThat(location.getTitle()).hasValue("Contracts");
    }

    @Test
    void aFolderOfTheBlockOpensInTheTree() {
        Folder contracts = folder(sandbox, "Contracts");
        favoriteService.mark(List.of(contracts));
        RepositoryView view = open(sandbox);

        button(view, "Contracts", 0).click();

        FolderTreeFragment folderTree = UiTestUtils.getComponent(view, "folderTree");
        assertThat(folderTree.getSelectedFolder()).isEqualTo(contracts);
    }

    @Test
    void aDocumentOfTheBlockOpensItsCard() {
        Document lease = upload(sandbox, "lease.txt");
        favoriteService.mark(List.of(lease));
        RepositoryView view = open(sandbox);

        button(view, "lease.txt", 0).click();

        DocumentDetailView card = UiTestUtils.getLastOpenedViewDialog();
        assertThat(card.getEditedEntity()).isEqualTo(lease);
    }

    @Test
    void aDocumentDeletedSinceGivesANoticeAndLeavesTheBlock() {
        Document lease = upload(sandbox, "lease.txt");
        favoriteService.mark(List.of(lease));
        RepositoryView view = open(sandbox);
        documentService.delete(List.of(lease));

        button(view, "lease.txt", 0).click();

        assertThat(UiTestUtils.getLastOpenedNotification().getText())
                .isEqualTo(messages.getMessage(DocumentDetailView.class, "documentUnavailable"));
        assertThat(rows(view)).isEmpty();
    }

    @Test
    void theStarOfARowRemovesTheMark() {
        Folder contracts = folder(sandbox, "Contracts");
        favoriteService.mark(List.of(contracts));
        RepositoryView view = open(sandbox);

        button(view, "Contracts", 1).click();

        assertThat(rows(view)).isEmpty();
        assertThat(favoriteService.markedIds(List.of(contracts))).isEmpty();
    }

    // a document marked already stays as it is, and the table puts the star after the name of the other one
    @Test
    void documentsDroppedOnTheSectionAreMarked() {
        Document lease = upload(sandbox, "lease.txt");
        Document act = upload(sandbox, "act.txt");
        favoriteService.mark(List.of(lease));
        RepositoryView view = open(sandbox);
        DataGrid<Document> documentsGrid = documentsGrid(view);

        TestGrids.dragRows(documentsGrid, List.of(lease, act), section(view));

        assertThat(rows(view)).containsExactly("act.txt " + sandbox.getName(), "lease.txt " + sandbox.getName());
        assertThat(TestGrids.render(documentsGrid, "name", act).getChildren()
                .anyMatch(part -> part.getElement().getClassList().contains(FavoriteStars.MARKED_CLASS)))
                .isTrue();
    }

    @Test
    void aFolderDroppedOnTheSectionIsMarked() {
        Folder contracts = folder(sandbox, "Contracts");
        RepositoryView view = open(sandbox);

        TestGrids.dragRows(this.<TreeDataGrid<Folder>>treeComponent(view, "foldersTree"), List.of(contracts),
                section(view));

        assertThat(rows(view)).containsExactly("Contracts " + sandbox.getName());
    }

    @Test
    void aDropOnTheClosedSectionOpensIt() {
        Document lease = upload(sandbox, "lease.txt");
        RepositoryView view = open(sandbox);
        JmixDetails section = section(view);
        section.setOpened(false);

        TestGrids.dragRows(documentsGrid(view), List.of(lease), section);

        assertThat(section.isOpened()).isTrue();
        assertThat(rows(view)).containsExactly("lease.txt " + sandbox.getName());
    }

    @Test
    void theFilterOfTheTreeNarrowsTheFavoritesToo() {
        Folder contracts = folder(sandbox, "Contracts");
        Document lease = upload(contracts, "lease.txt");
        Document memo = upload(sandbox, "memo.txt");
        favoriteService.mark(List.of(contracts, lease, memo));
        RepositoryView view = open(sandbox);

        filterField(view).setValue("LEA");

        assertThat(rows(view)).containsExactly("lease.txt Contracts");

        filterField(view).setValue("nothing like it");

        assertThat(rows(view)).isEmpty();
        // the hint is for a user without favorites, not for a filter that hides them
        assertThat(this.<Span>favoritesComponent(view, "emptyHint").isVisible()).isFalse();
    }

    @Test
    void aClearedFilterShowsAllTheFavoritesAgain() {
        Folder contracts = folder(sandbox, "Contracts");
        Document memo = upload(sandbox, "memo.txt");
        favoriteService.mark(List.of(contracts, memo));
        RepositoryView view = open(sandbox);
        filterField(view).setValue("memo");

        filterField(view).clear();

        assertThat(rows(view)).containsExactly("Contracts " + sandbox.getName(), "memo.txt " + sandbox.getName());
    }

    @Test
    void theBlockFollowsAFolderRenamedInTheTree() {
        Folder contracts = folder(sandbox, "Contracts");
        favoriteService.mark(List.of(contracts));
        RepositoryView view = open(contracts);
        TreeDataGrid<Folder> tree = treeComponent(view, "foldersTree");

        tree.getAction("renameFolderAction").actionPerform(tree);
        FolderDetailView dialog = UiTestUtils.getLastOpenedViewDialog();
        UiTestUtils.<TypedTextField<String>>getComponent(dialog, "nameField").setValue("Agreements");
        UiTestUtils.<JmixButton>getComponent(dialog, "saveButton").click();

        assertThat(rows(view)).containsExactly("Agreements " + sandbox.getName());
    }

    @Test
    void theHeaderOfTheFavoritesCarriesAStarAsAFolderCarriesItsIcon() {
        RepositoryView view = open(sandbox);
        Component summary = this.<JmixDetails>treeComponent(view, "favoritesDetails").getSummary();

        assertThat(summary.getElement().getTextRecursively())
                .isEqualTo(messages.getMessage(FavoritesFragment.class, "favoritesDetails.summaryText"));
        assertThat(summary.getChildren().map(child -> child.getElement().getProperty("iconClass")))
                .contains(icons.get(JmixFontIcon.STAR).getElement().getProperty("iconClass"));
    }

    @Test
    void theBlockStaysCollapsedForTheUser() {
        RepositoryView view = open(sandbox);
        this.<JmixDetails>treeComponent(view, "favoritesDetails").setOpened(false);

        viewNavigators.view(view, TrashView.class).navigate();
        RepositoryView again = open(sandbox);

        assertThat(this.<JmixDetails>treeComponent(again, "favoritesDetails").isOpened()).isFalse();
    }

    private RepositoryView open(Folder folder) {
        viewNavigators.view(UiTestUtils.getCurrentView(), RepositoryView.class)
                .withQueryParameters(new QueryParameters(Map.of("folder", List.of(folder.getId().toString()))))
                .navigate();

        return UiTestUtils.getCurrentView();
    }

    // a row shows the name, then the folder it lies in
    private List<String> rows(RepositoryView view) {
        return this.<VerticalLayout>favoritesComponent(view, "favoritesBox").getChildren()
                .map(row -> row.getChildren()
                        .map(part -> part.getElement().getTextRecursively())
                        .filter(text -> !text.isBlank())
                        .collect(Collectors.joining(" ")))
                .toList();
    }

    // the name of a row is its first button, the star the second one
    private JmixButton button(RepositoryView view, String name, int index) {
        Component row = this.<VerticalLayout>favoritesComponent(view, "favoritesBox").getChildren()
                .filter(child -> child.getElement().getTextRecursively().startsWith(name))
                .findFirst()
                .orElseThrow();

        return row.getChildren()
                .filter(JmixButton.class::isInstance)
                .map(JmixButton.class::cast)
                .toList()
                .get(index);
    }

    // the favorites are a section of the folder tree
    private FavoritesFragment favorites(RepositoryView view) {
        return treeComponent(view, "favorites");
    }

    private JmixDetails section(RepositoryView view) {
        return treeComponent(view, "favoritesDetails");
    }

    // the filter above the frame serves both sections
    private TypedTextField<String> filterField(RepositoryView view) {
        return treeComponent(view, "folderFilterField");
    }

    @SuppressWarnings("unchecked")
    private static DataGrid<Document> documentsGrid(RepositoryView view) {
        return (DataGrid<Document>) FragmentUtils.getComponent(UiTestUtils.getComponent(view, "documentList"),
                "documentsGrid");
    }

    @SuppressWarnings("unchecked")
    private <T> T favoritesComponent(RepositoryView view, String componentId) {
        return (T) FragmentUtils.getComponent(favorites(view), componentId);
    }

    @SuppressWarnings("unchecked")
    private <T> T treeComponent(RepositoryView view, String componentId) {
        return (T) FragmentUtils.getComponent(UiTestUtils.getComponent(view, "folderTree"), componentId);
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
