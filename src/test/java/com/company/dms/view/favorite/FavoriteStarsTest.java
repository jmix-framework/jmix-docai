package com.company.dms.view.favorite;

import com.company.dms.DMSApplication;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.favorite.FavoriteService;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.access.AccessLevel;
import com.company.dms.model.document.Document;
import com.company.dms.model.document.DocumentStatus;
import com.company.dms.model.favorite.Favorite;
import com.company.dms.model.folder.Folder;
import com.company.dms.test_support.EmployeeUiTestAuthenticator;
import com.company.dms.test_support.TestAccess;
import com.company.dms.test_support.TestFiles;
import com.company.dms.test_support.TestGrids;
import com.company.dms.view.document.DocumentDetailView;
import com.company.dms.view.main.MainView;
import com.company.dms.view.repository.DocumentListFragment;
import com.company.dms.view.repository.RepositoryView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.router.QueryParameters;
import io.jmix.core.DataManager;
import io.jmix.core.Messages;
import io.jmix.core.SaveContext;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.grid.TreeDataGrid;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.fragment.FragmentUtils;
import io.jmix.flowui.icon.Icons;
import io.jmix.flowui.kit.action.Action;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.kit.component.dropdownbutton.DropdownButton;
import io.jmix.flowui.kit.icon.JmixFontIcon;
import io.jmix.flowui.settings.UserSettingsCache;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.view.ViewControllerUtils;
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

import static org.assertj.core.api.Assertions.assertThat;

@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DMSApplication.class, FlowuiTestAssistConfiguration.class})
class FavoriteStarsTest {

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
    private SystemAuthenticator systemAuthenticator;

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
        sandbox = folder(folderService.getRoot(), "favorite-stars-test-" + UUID.randomUUID());
    }

    // no other test keeps favorites, so the marks of every user go
    @AfterEach
    void deleteSandbox() {
        unconstrainedDataManager.save(new SaveContext()
                .removing(unconstrainedDataManager.load(Favorite.class).all().list()));
        // the sandbox holds a document the employee only views, so the administrator deletes it
        systemAuthenticator.runWithUser("admin", () -> folderService.delete(sandbox));
        userSettingsCache.delete("RepositoryView.folderTree");
        userSettingsCache.delete("RepositoryView.documentList");
    }

    @Test
    void theStarOfTheCardMarksTheDocumentWithoutChangingTheCard() {
        Document lease = upload(sandbox, "lease.txt");
        RepositoryView view = open(sandbox);
        DocumentDetailView card = openCard(view, lease);
        JmixButton star = UiTestUtils.getComponent(card, "favoriteButton");
        assertThat(isMarkedStar(star.getIcon())).isFalse();

        star.click();

        assertThat(favoriteService.markedIds(List.of(lease))).containsExactly(lease.getId());
        assertThat(isMarkedStar(star.getIcon())).isTrue();
        // only the star changes: the text of the action stays, the button stays bare with its static tooltip
        assertThat(star.getAction().getText()).isEqualTo(text("favoriteAction.text"));
        assertThat(star.getText()).isEmpty();
        assertThat(star.getTitle()).isNullOrEmpty();
        assertThat(star.getTooltip().getText()).isEqualTo(text("favoriteAction.text"));
        assertThat(star.getAriaLabel()).hasValue(text("favoriteAction.text"));
        assertThat(ViewControllerUtils.getViewData(card).getDataContext().hasChanges()).isFalse();

        // the repository under the card shows the mark once the card closes
        card.closeWithDefaultAction();
        assertThat(blockNames(view)).containsExactly("lease.txt");
        assertThat(hasStar(TestGrids.render(component(view, "documentList", "documentsGrid"), "name", lease)))
                .isTrue();

        DocumentDetailView again = openCard(view, lease);
        JmixButton yellow = UiTestUtils.getComponent(again, "favoriteButton");
        assertThat(isMarkedStar(yellow.getIcon())).isTrue();
        yellow.click();
        assertThat(isMarkedStar(yellow.getIcon())).isFalse();
        again.closeWithDefaultAction();

        assertThat(favoriteService.markedIds(List.of(lease))).isEmpty();
        assertThat(blockNames(view)).isEmpty();
    }

    @Test
    void theStarWorksInACardTheUserOnlyViewsAndInAnArchivedOne() {
        Document viewed = systemAuthenticator.withUser("admin", () -> TestAccess.giveOwnAccess(dataManager,
                upload(sandbox, "viewed.txt"), TestAccess.Grant.user(EmployeeUiTestAuthenticator.USERNAME,
                        AccessLevel.VIEW)));
        Document archived = upload(sandbox, "archived.txt");
        documentService.changeStatus(List.of(archived), DocumentStatus.ACTIVE);
        documentService.changeStatus(List.of(archived), DocumentStatus.ARCHIVED);
        RepositoryView view = open(sandbox);

        for (Document document : List.of(viewed, archived)) {
            DocumentDetailView card = openCard(view, document);
            JmixButton star = UiTestUtils.getComponent(card, "favoriteButton");
            assertThat(star.isVisible()).isTrue();

            star.click();

            assertThat(favoriteService.markedIds(List.of(document))).containsExactly(document.getId());
            card.closeWithDefaultAction();
        }
    }

    @Test
    void aNewDocumentHasNoStarUntilItIsSaved() {
        RepositoryView view = open(sandbox);

        this.<JmixButton>component(view, "documentList", "createDocumentButton").click();
        DocumentDetailView card = UiTestUtils.getLastOpenedViewDialog();

        assertThat(UiTestUtils.<JmixButton>getComponent(card, "favoriteButton").isVisible()).isFalse();
    }

    @Test
    void theBlockFollowsADocumentRenamedInItsCard() {
        Document lease = upload(sandbox, "lease.txt");
        favoriteService.mark(List.of(lease));
        RepositoryView view = open(sandbox);

        DocumentDetailView card = openCard(view, lease);
        UiTestUtils.<TypedTextField<String>>getComponent(card, "nameField").setValue("lease-2026.txt");
        UiTestUtils.<JmixButton>getComponent(card, "saveButton").click();

        assertThat(blockNames(view)).containsExactly("lease-2026.txt");
    }

    @Test
    void theTableMarksTheSelectedDocumentsAndShowsTheirStars() {
        Document lease = upload(sandbox, "lease.txt");
        Document act = upload(sandbox, "act.txt");
        RepositoryView view = open(sandbox);
        DataGrid<Document> grid = component(view, "documentList", "documentsGrid");
        Action action = grid.getAction("favoriteAction");
        JmixButton button = component(view, "documentList", "favoriteButton");

        grid.select(lease);
        assertThat(isMarkedStar(button.getIcon())).isFalse();
        button.click();

        // only the star changes: the text of the action stays, the toolbar button stays bare with its static title
        assertThat(action.getText()).isEqualTo(text("favoriteAction.text"));
        assertThat(button.getText()).isEmpty();
        assertThat(button.getTitle()).isEqualTo(text("favoriteAction.text"));
        assertThat(isMarkedStar(button.getIcon())).isTrue();
        assertThat(hasStar(TestGrids.render(grid, "name", lease))).isTrue();
        assertThat(starAfterName(TestGrids.render(grid, "name", lease))).isTrue();
        assertThat(hasStar(TestGrids.render(grid, "name", act))).isFalse();
        assertThat(blockNames(view)).containsExactly("lease.txt");

        grid.asMultiSelect().select(act);
        assertThat(isMarkedStar(button.getIcon())).isFalse();
        action.actionPerform(grid);

        assertThat(favoriteService.markedIds(List.of(lease, act))).containsExactlyInAnyOrder(lease.getId(),
                act.getId());
        assertThat(isMarkedStar(button.getIcon())).isTrue();
        // the menu item keeps the text and shows the yellow star of the action
        assertThat(grid.getContextMenu().getItems())
                .anyMatch(item -> item.getElement().getTextRecursively().equals(text("favoriteAction.text"))
                        && hasMarkedStar(item.getElement()));
    }

    @Test
    void theFolderMenuMarksTheFolderButNotTheRoot() {
        Folder contracts = folder(sandbox, "Contracts");
        RepositoryView view = open(contracts);
        TreeDataGrid<Folder> tree = component(view, "folderTree", "foldersTree");
        Action action = tree.getAction("favoriteAction");
        DropdownButton folderActions = component(view, "folderTree", "folderActionsButton");

        assertThat(folderActions.getItem("favoriteFolderItem")).isNotNull();
        assertThat(isMarkedStar(action.getIcon())).isFalse();
        action.actionPerform(tree);

        assertThat(favoriteService.markedIds(List.of(contracts))).containsExactly(contracts.getId());
        assertThat(action.getText()).isEqualTo(text("favoriteAction.text"));
        assertThat(isMarkedStar(action.getIcon())).isTrue();
        assertThat(tree.getContextMenu().getItems())
                .anyMatch(item -> item.getElement().getTextRecursively().equals(text("favoriteAction.text"))
                        && hasMarkedStar(item.getElement()));
        assertThat(blockNames(view)).containsExactly("Contracts");

        tree.select(folderService.getRoot());
        assertThat(action.isEnabled()).isFalse();
    }

    private DocumentDetailView openCard(RepositoryView view, Document document) {
        UiTestUtils.<DocumentListFragment>getComponent(view, "documentList").openCard(document);
        return UiTestUtils.getLastOpenedViewDialog();
    }

    private RepositoryView open(Folder folder) {
        viewNavigators.view(UiTestUtils.getCurrentView(), RepositoryView.class)
                .withQueryParameters(new QueryParameters(Map.of("folder", List.of(folder.getId().toString()))))
                .navigate();

        return UiTestUtils.getCurrentView();
    }

    // the theme draws the star of an item that is not marked as an outline: the outline glyph is deprecated
    @Test
    void aStarIsAnOutlineUntilTheItemIsMarked() {
        Component outline = FavoriteStars.star(icons, false);
        Component filled = FavoriteStars.star(icons, true);

        assertThat(glyph(outline)).isNotBlank().isEqualTo(glyph(icons.get(JmixFontIcon.STAR)));
        assertThat(outline.getElement().getClassList())
                .contains(FavoriteStars.UNMARKED_CLASS)
                .doesNotContain(FavoriteStars.MARKED_CLASS);
        assertThat(glyph(filled)).isEqualTo(glyph(outline));
        assertThat(filled.getElement().getClassList())
                .contains(FavoriteStars.MARKED_CLASS)
                .doesNotContain(FavoriteStars.UNMARKED_CLASS);
    }

    // the names of the rows of the favorites section of the folder tree
    private List<String> blockNames(RepositoryView view) {
        VerticalLayout box = (VerticalLayout) FragmentUtils.getComponent(
                component(view, "folderTree", "favorites"), "favoritesBox");
        return box.getChildren()
                .map(row -> row.getChildren().findFirst().orElseThrow().getElement().getTextRecursively())
                .toList();
    }

    private static String glyph(Component icon) {
        return icon.getElement().getProperty("iconClass");
    }

    private static boolean hasStar(Component cell) {
        return cell.getChildren().anyMatch(child -> child.getElement().getClassList()
                .contains(FavoriteStars.MARKED_CLASS));
    }

    // the column cuts a long name with an ellipsis and keeps the star after it in sight
    private static boolean starAfterName(Component cell) {
        List<Component> parts = cell.getChildren().toList();
        int star = parts.indexOf(parts.stream().filter(FavoriteStarsTest::isMarkedStar).findFirst().orElseThrow());
        int name = parts.indexOf(parts.stream()
                .filter(part -> part.getElement().getClassList().contains("name-text"))
                .findFirst()
                .orElseThrow());

        return star > name;
    }

    private static boolean isMarkedStar(Component icon) {
        return icon.getElement().getClassList().contains(FavoriteStars.MARKED_CLASS);
    }

    // a menu item holds the icon of its action next to its text
    private static boolean hasMarkedStar(Element element) {
        return element.getClassList().contains(FavoriteStars.MARKED_CLASS)
                || element.getChildren().anyMatch(FavoriteStarsTest::hasMarkedStar);
    }

    private String text(String key) {
        return messages.getMessage(FavoriteListAction.class, key);
    }

    @SuppressWarnings("unchecked")
    private <T> T component(RepositoryView view, String fragmentId, String componentId) {
        return (T) FragmentUtils.getComponent(UiTestUtils.getComponent(view, fragmentId), componentId);
    }

    private Folder folder(Folder parent, String name) {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(parent);
        folder.setName(name);
        return folderService.save(folder);
    }

    private Document upload(Folder folder, String fileName) {
        return systemAuthenticator.withUser("admin", () -> documentService.upload(folder,
                        List.of(TestFiles.file(tempDir, fileName, "text")))
                .created()
                .getFirst());
    }
}
