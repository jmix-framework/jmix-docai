package com.company.dms.view.trash;

import com.company.dms.DMSApplication;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.document.Document;
import com.company.dms.model.folder.Folder;
import com.company.dms.model.trash.TrashItem;
import com.company.dms.test_support.EmployeeUiTestAuthenticator;
import com.company.dms.test_support.TestFiles;
import com.company.dms.test_support.TestGrids;
import com.company.dms.view.document.DocumentPreviewFragment;
import com.company.dms.view.main.MainView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.html.Span;
import io.jmix.core.DataManager;
import io.jmix.core.Messages;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.sidepanellayout.SidePanelLayout;
import io.jmix.flowui.fragment.Fragment;
import io.jmix.flowui.fragment.FragmentUtils;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.settings.UserSettingsCache;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.testassist.dialog.DialogInfo;
import io.jmix.flowui.view.ViewControllerUtils;
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

import static com.company.dms.test_support.TestTrash.exists;
import static com.company.dms.test_support.TestTrash.ids;
import static com.company.dms.test_support.TestTrash.isDeleted;
import static com.company.dms.test_support.TestTrash.itemOf;
import static org.assertj.core.api.Assertions.assertThat;

@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DMSApplication.class, FlowuiTestAssistConfiguration.class})
class TrashViewTest {

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
    private UserSettingsCache userSettingsCache;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        sandbox = folder(folderService.getRoot(), "trash-view-test-" + UUID.randomUUID());
    }

    @AfterEach
    void deleteSandbox() {
        folderService.delete(sandbox);
        // leaving the view saves its column settings for the user
        userSettingsCache.delete("TrashView");
        systemAuthenticator.runWithUser("admin", () -> userSettingsCache.delete("TrashView"));
    }

    @Test
    void theTrashListsTheDeletedFolderAndDocumentWithTheirIcons() {
        Folder contracts = folder(sandbox, "Contracts");
        upload(contracts, "supply.txt");
        Document note = upload(sandbox, "note.txt");
        folderService.delete(contracts);
        documentService.delete(List.of(note));

        TrashView view = open();

        DataGrid<TrashItem> grid = grid(view);
        assertThat(ids(items(grid))).contains(contracts.getId(), note.getId());
        assertThat(nameCell(grid, itemOf(items(grid), contracts.getId()))).isEqualTo("Contracts");
        assertThat(nameCell(grid, itemOf(items(grid), note.getId()))).isEqualTo("note.txt");
        assertThat(grid.getEmptyStateText())
                .isEqualTo(messages.getMessage(TrashView.class, "trashItemsGrid.emptyStateText"));
    }

    @Test
    void anEmployeeRestoresAndHasNoWayToDeleteForever() {
        Document note = upload(sandbox, "note.txt");
        documentService.delete(List.of(note));
        TrashView view = open();
        DataGrid<TrashItem> grid = grid(view);
        JmixButton restoreButton = UiTestUtils.getComponent(view, "restoreButton");

        assertThat(UiTestUtils.<JmixButton>getComponent(view, "purgeButton").isVisible()).isFalse();
        assertThat(UiTestUtils.<JmixButton>getComponent(view, "emptyButton").isVisible()).isFalse();
        assertThat(restoreButton.isEnabled()).isFalse();

        grid.select(itemOf(items(grid), note.getId()));
        restoreButton.click();

        assertThat(UiTestUtils.getLastOpenedNotification().getText())
                .isEqualTo(messages.formatMessage(TrashView.class, "restoredNotification", 1));
        assertThat(ids(items(grid))).doesNotContain(note.getId());
        assertThat(isDeleted(dataManager, Document.class, note.getId())).isFalse();
    }

    @Test
    void theAdministratorDeletesForeverAfterAQuestion() {
        Document note = upload(sandbox, "note.txt");
        documentService.delete(List.of(note));

        systemAuthenticator.runWithUser("admin", () -> {
            TrashView view = open();
            DataGrid<TrashItem> grid = grid(view);
            JmixButton purgeButton = UiTestUtils.getComponent(view, "purgeButton");
            assertThat(purgeButton.isVisible()).isTrue();

            grid.select(itemOf(items(grid), note.getId()));
            purgeButton.click();

            assertThat(dialogText()).isEqualTo(messages.formatMessage(TrashView.class, "purgeDialog.names",
                    messages.formatMessage(TrashView.class, "purgeDialog.name", "note.txt")));
            dialogButton(DialogAction.Type.YES).click();

            assertThat(exists(dataManager, Document.class, note.getId())).isFalse();
            assertThat(ids(items(grid))).doesNotContain(note.getId());
        });
    }

    @Test
    void emptyingTheTrashAsksWithTheNumberOfItems() {
        Document note = upload(sandbox, "note.txt");
        documentService.delete(List.of(note));

        systemAuthenticator.runWithUser("admin", () -> {
            TrashView view = open();
            JmixButton emptyButton = UiTestUtils.getComponent(view, "emptyButton");
            assertThat(emptyButton.isVisible()).isTrue();
            assertThat(emptyButton.isEnabled()).isTrue();

            emptyButton.click();

            assertThat(dialogText())
                    .isEqualTo(messages.formatMessage(TrashView.class, "emptyDialog.text", items(grid(view)).size()));
            dialogButton(DialogAction.Type.NO).click();

            assertThat(isDeleted(dataManager, Document.class, note.getId())).isTrue();
        });
    }

    @Test
    void emptyingTheTrashDeletesForeverOnlyTheItemsItShows() {
        Document shown = upload(sandbox, "shown.txt");
        Document later = upload(sandbox, "later.txt");
        documentService.delete(List.of(shown));

        systemAuthenticator.runWithUser("admin", () -> {
            TrashView view = open();
            // the list keeps only this test's item, so that emptying it leaves what other tests put into the trash
            CollectionContainer<TrashItem> listed = ViewControllerUtils.getViewData(view).getContainer("trashItemsDc");
            listed.setItems(List.of(itemOf(listed.getItems(), shown.getId())));
            // a colleague deletes another document while the list is open
            documentService.delete(List.of(later));
            JmixButton emptyButton = UiTestUtils.getComponent(view, "emptyButton");

            emptyButton.click();
            dialogButton(DialogAction.Type.YES).click();

            assertThat(exists(dataManager, Document.class, shown.getId())).isFalse();
            assertThat(isDeleted(dataManager, Document.class, later.getId())).isTrue();
        });
    }

    @Test
    void theEdgeOfThePreviewIsAHairline() {
        TrashView view = open();
        SidePanelLayout previewLayout = UiTestUtils.getComponent(view, "previewLayout");

        // a thin line, with the grip shown on hover only, as in the repository
        assertThat(previewLayout.getThemeNames()).contains("resizer-small");
    }

    @Test
    void theQuestionToDeleteForeverQuotesEveryName() {
        Document report = upload(sandbox, "Report, total.txt");
        Document note = upload(sandbox, "note.txt");
        documentService.delete(List.of(report, note));

        systemAuthenticator.runWithUser("admin", () -> {
            TrashView view = open();
            DataGrid<TrashItem> grid = grid(view);
            JmixButton purgeButton = UiTestUtils.getComponent(view, "purgeButton");
            grid.select(itemOf(items(grid), report.getId()));
            grid.select(itemOf(items(grid), note.getId()));

            purgeButton.click();

            // without the quotes a comma inside a name reads as two names
            assertThat(dialogText()).contains(
                    messages.formatMessage(TrashView.class, "purgeDialog.name", "Report, total.txt"),
                    messages.formatMessage(TrashView.class, "purgeDialog.name", "note.txt"));
            dialogButton(DialogAction.Type.NO).click();
        });
    }

    @Test
    void thePreviewShowsADocumentAndWhatComesBackWithAFolder() {
        Folder contracts = folder(sandbox, "Contracts");
        Document supply = upload(contracts, "supply.txt");
        Document note = upload(sandbox, "note.txt");
        folderService.delete(contracts);
        documentService.delete(List.of(note));
        TrashView view = open();
        DataGrid<TrashItem> grid = grid(view);
        SidePanelLayout previewLayout = UiTestUtils.getComponent(view, "previewLayout");
        TrashPreviewFragment preview = UiTestUtils.getComponent(view, "preview");

        UiTestUtils.<JmixButton>getComponent(view, "previewButton").click();
        assertThat(previewLayout.isSidePanelOpened()).isTrue();
        assertThat(this.<Span>fragmentComponent(preview, "placeholder").isVisible()).isTrue();

        grid.select(itemOf(items(grid), note.getId()));
        assertThat(previewText(preview)).isEqualTo("text");
        assertThat(this.<JmixButton>fragmentComponent(preview, "backButton").isVisible()).isFalse();

        grid.deselectAll();
        grid.select(itemOf(items(grid), contracts.getId()));
        DataGrid<TrashItem> contents = fragmentComponent(preview, "contentsGrid");
        assertThat(items(contents)).extracting(TrashItem::getName).containsExactly("supply.txt");
        assertThat(contents.getColumnByKey("location").isVisible()).isFalse();
        assertThat(this.<Span>fragmentComponent(preview, "contentsSummary").getText())
                .isEqualTo(messages.formatMessage(TrashPreviewFragment.class, "contentsSummary", 0, 1));

        TestGrids.clickRow(contents, itemOf(items(contents), supply.getId()), false);
        assertThat(this.<Component>fragmentComponent(preview, "contentsPanel").isVisible()).isFalse();
        assertThat(previewText(preview)).isEqualTo("text");

        this.<JmixButton>fragmentComponent(preview, "backButton").click();
        assertThat(this.<Component>fragmentComponent(preview, "contentsPanel").isVisible()).isTrue();
        assertThat(this.<Component>fragmentComponent(preview, "documentPanel").isVisible()).isFalse();

        grid.deselectAll();
        assertThat(this.<Span>fragmentComponent(preview, "placeholder").isVisible()).isTrue();
    }

    @Test
    void aClickSelectsItsRowAndThePreviewShowsTheItemSelectedLast() {
        Document first = upload(sandbox, "first.txt", "first text");
        Document second = upload(sandbox, "second.txt", "second text");
        documentService.delete(List.of(first, second));
        TrashView view = open();
        DataGrid<TrashItem> grid = grid(view);
        TrashPreviewFragment preview = UiTestUtils.getComponent(view, "preview");
        UiTestUtils.<JmixButton>getComponent(view, "previewButton").click();

        TestGrids.clickRow(grid, itemOf(items(grid), second.getId()), true);
        TestGrids.clickRow(grid, itemOf(items(grid), first.getId()), true);

        assertThat(grid.getSelectedItems()).hasSize(2);
        assertThat(previewText(preview)).isEqualTo("first text");

        TestGrids.clickRow(grid, itemOf(items(grid), second.getId()), false);

        assertThat(grid.getSelectedItems()).extracting(TrashItem::getId).containsExactly(second.getId());
        assertThat(previewText(preview)).isEqualTo("second text");
    }

    private TrashView open() {
        viewNavigators.view(UiTestUtils.getCurrentView(), TrashView.class).navigate();

        return UiTestUtils.getCurrentView();
    }

    private static DataGrid<TrashItem> grid(TrashView view) {
        return UiTestUtils.getComponent(view, "trashItemsGrid");
    }

    private static List<TrashItem> items(DataGrid<TrashItem> grid) {
        return List.copyOf(grid.getItems().getItems());
    }

    private static String nameCell(DataGrid<TrashItem> grid, TrashItem item) {
        return TestGrids.render(grid, "name", item).getElement().getTextRecursively();
    }

    private String previewText(TrashPreviewFragment preview) {
        DocumentPreviewFragment documentPreview = fragmentComponent(preview, "documentPreview");

        return ((Pre) FragmentUtils.getComponent(documentPreview, "previewText")).getText();
    }

    @SuppressWarnings("unchecked")
    private <T> T fragmentComponent(Fragment<?> fragment, String componentId) {
        return (T) FragmentUtils.getComponent(fragment, componentId);
    }

    private static String dialogText() {
        return UiTestUtils.getLastOpenedDialog().getContent().getElement().getTextRecursively();
    }

    private static Button dialogButton(DialogAction.Type type) {
        DialogInfo dialog = UiTestUtils.getLastOpenedDialog();

        return dialog.getButtons().stream()
                .filter(button -> button.getId().equals(Optional.of(type.getId())))
                .findFirst()
                .orElseThrow();
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

    private Document upload(Folder folder, String fileName, String text) {
        return documentService.upload(folder, List.of(TestFiles.file(tempDir, fileName, text)))
                .created()
                .getFirst();
    }
}
