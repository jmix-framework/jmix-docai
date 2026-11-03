package com.company.docai.view.status;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.document.DocumentStatusService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.app.service.trash.TrashService;
import com.company.docai.model.document.Document;
import com.company.docai.model.document.DocumentStatus;
import com.company.docai.model.folder.Folder;
import com.company.docai.test_support.TestFiles;
import com.company.docai.test_support.TestGrids;
import com.company.docai.test_support.TestStatuses;
import com.company.docai.test_support.TestTrash;
import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.dnd.GridDropLocation;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Input;
import com.vaadin.flow.component.html.NativeButton;
import io.jmix.core.DataManager;
import io.jmix.core.Messages;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.component.checkbox.JmixCheckbox;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.multiselectcombobox.JmixMultiSelectComboBox;
import io.jmix.flowui.component.textfield.JmixIntegerField;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.icon.Icons;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.kit.icon.JmixFontIcon;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.testassist.dialog.DialogInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The dictionary of statuses as the administrator keeps it: a new status moves to any status until told otherwise,
 * the palette and the custom color set the color, and the list shows the statuses in their order and colors.
 */
@UiTest
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class DocumentStatusViewsTest {

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private Icons icons;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private DocumentStatusService statusService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private TrashService trashService;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private Messages messages;

    @TempDir
    private Path tempDir;

    @Test
    void aNewStatusMovesToAnyStatusAndShowsTheListWhenItDoesNot() {
        DocumentStatusDetailView dialog = openNew();
        JmixCheckbox anyTarget = UiTestUtils.getComponent(dialog, "anyTargetField");
        JmixMultiSelectComboBox<DocumentStatus> targets = UiTestUtils.getComponent(dialog, "targetsField");

        assertThat(anyTarget.getValue()).isTrue();
        assertThat(targets.isVisible()).isFalse();

        anyTarget.setValue(false);

        assertThat(targets.isVisible()).isTrue();
        assertThat(targets.getListDataView().getItems()).contains(TestStatuses.active(dataManager));
    }

    @Test
    void aSavedStatusOpensWithTheStatusesItMovesTo() {
        DocumentStatusListView list = openList();
        DataGrid<DocumentStatus> grid = UiTestUtils.getComponent(list, "statusesDataGrid");
        grid.select(TestStatuses.draft(dataManager));
        UiTestUtils.<JmixButton>getComponent(list, "editButton").click();
        DocumentStatusDetailView dialog = UiTestUtils.getLastOpenedViewDialog();
        JmixMultiSelectComboBox<DocumentStatus> targets = UiTestUtils.getComponent(dialog, "targetsField");

        assertThat(targets.isVisible()).isTrue();
        assertThat(targets.getValue()).containsExactly(TestStatuses.active(dataManager));
        assertThat(targets.getListDataView().getItems()).doesNotContain(TestStatuses.draft(dataManager));
    }

    @Test
    void aNewStatusWithItsFlagsOffIsSavedFromTheDialog() {
        int position = statusService.nextPosition();
        DocumentStatusDetailView dialog = openNew();
        String name = "Saved " + UUID.randomUUID().toString().substring(0, 8);
        UiTestUtils.<TypedTextField<String>>getComponent(dialog, "nameField").setValue(name);

        UiTestUtils.<JmixButton>getComponent(dialog, "saveAndCloseButton").click();

        DocumentStatus saved = statusService.find(name).orElseThrow();
        statusService.delete(saved);

        assertThat(saved.getPosition()).isEqualTo(position);
        assertThat(saved.getInitial()).isFalse();
        assertThat(saved.getLocked()).isFalse();
        assertThat(saved.getAnyTarget()).isTrue();
    }

    // the flag moves to the saved status from the one that had it, which the list shows too
    @Test
    void theListShowsOneStatusForNewDocumentsAfterAnotherTakesTheFlag() {
        DocumentStatusListView list = openList();
        DataGrid<DocumentStatus> grid = UiTestUtils.getComponent(list, "statusesDataGrid");
        UiTestUtils.<JmixButton>getComponent(list, "createButton").click();
        DocumentStatusDetailView dialog = UiTestUtils.getLastOpenedViewDialog();
        String name = "Initial " + UUID.randomUUID().toString().substring(0, 8);
        UiTestUtils.<TypedTextField<String>>getComponent(dialog, "nameField").setValue(name);
        UiTestUtils.<JmixCheckbox>getComponent(dialog, "initialField").setValue(true);

        UiTestUtils.<JmixButton>getComponent(dialog, "saveAndCloseButton").click();

        try {
            assertThat(grid.getItems()).isNotNull();
            assertThat(grid.getItems().getItems())
                    .filteredOn(DocumentStatus::getInitial)
                    .extracting(DocumentStatus::getName)
                    .containsExactly(name);
        } finally {
            DocumentStatus draft = TestStatuses.draft(dataManager);
            draft.setInitial(true);
            statusService.save(draft);
            statusService.delete(statusService.find(name).orElseThrow());
        }
    }

    // a status dropped on the upper half of another row takes the place of that status
    @Test
    void aStatusDraggedAboveAnotherTakesItsPlaceInTheList() {
        DocumentStatus first = TestStatuses.create(unconstrainedDataManager, "First", false, List.of());
        DocumentStatus second = TestStatuses.create(unconstrainedDataManager, "Second", false, List.of());

        try {
            DocumentStatusListView list = openList();
            DataGrid<DocumentStatus> grid = UiTestUtils.getComponent(list, "statusesDataGrid");

            TestGrids.startDrag(grid, List.of(second));
            TestGrids.dropOnRow(grid, first, GridDropLocation.ABOVE);
            TestGrids.endDrag(grid);

            assertThat(grid.getItems()).isNotNull();
            List<DocumentStatus> rows = List.copyOf(grid.getItems().getItems());
            assertThat(rows.indexOf(second)).isEqualTo(rows.indexOf(first) - 1);
        } finally {
            statusService.delete(second);
            statusService.delete(first);
        }
    }

    @Test
    void theButtonsMoveTheSelectedStatusUpAndDownAndKeepItSelected() {
        DocumentStatus first = TestStatuses.create(unconstrainedDataManager, "First", false, List.of());
        DocumentStatus second = TestStatuses.create(unconstrainedDataManager, "Second", false, List.of());

        try {
            DocumentStatusListView list = openList();
            DataGrid<DocumentStatus> grid = UiTestUtils.getComponent(list, "statusesDataGrid");
            grid.select(second);

            UiTestUtils.<JmixButton>getComponent(list, "moveUpButton").click();

            assertThat(grid.getItems()).isNotNull();
            List<DocumentStatus> rows = List.copyOf(grid.getItems().getItems());
            assertThat(rows.indexOf(second)).isEqualTo(rows.indexOf(first) - 1);
            assertThat(grid.getSelectedItems()).containsExactly(second);

            UiTestUtils.<JmixButton>getComponent(list, "moveDownButton").click();

            rows = List.copyOf(grid.getItems().getItems());
            assertThat(rows.indexOf(second)).isEqualTo(rows.indexOf(first) + 1);
        } finally {
            statusService.delete(second);
            statusService.delete(first);
        }
    }

    @Test
    void theFirstStatusDoesNotMoveUpAndTheLastDoesNotMoveDown() {
        DocumentStatusListView list = openList();
        DataGrid<DocumentStatus> grid = UiTestUtils.getComponent(list, "statusesDataGrid");
        JmixButton up = UiTestUtils.getComponent(list, "moveUpButton");
        JmixButton down = UiTestUtils.getComponent(list, "moveDownButton");
        assertThat(grid.getItems()).isNotNull();
        List<DocumentStatus> rows = List.copyOf(grid.getItems().getItems());

        grid.select(rows.getFirst());
        assertThat(up.isEnabled()).isFalse();
        assertThat(down.isEnabled()).isTrue();

        grid.select(rows.getLast());
        assertThat(up.isEnabled()).isTrue();
        assertThat(down.isEnabled()).isFalse();
    }

    // the arrows speak for themselves; the title names them for a hover and for a screen reader
    @Test
    void theMoveButtonsShowOnlyTheirArrows() {
        DocumentStatusListView list = openList();

        for (String id : List.of("moveUpButton", "moveDownButton")) {
            JmixButton button = UiTestUtils.getComponent(list, id);

            assertThat(button.getText()).as(id).isEmpty();
            assertThat(button.getIcon()).as(id).isNotNull();
            assertThat(button.getTitle()).as(id).isNotBlank();
            assertThat(button.getAriaLabel()).as(id).hasValue(button.getTitle());
        }
    }

    // the place changes only in the list, by a drag or the buttons, and the dialog shows where the status stands
    @Test
    void theDialogShowsThePlaceOfTheStatusWithoutChangingIt() {
        int position = statusService.nextPosition();
        DocumentStatusDetailView dialog = openNew();
        JmixIntegerField positionField = UiTestUtils.getComponent(dialog, "positionField");

        assertThat(positionField.isReadOnly()).isTrue();
        assertThat(positionField.isRequiredIndicatorVisible()).isFalse();
        assertThat(positionField.getValue()).isEqualTo(position);
    }

    @Test
    void aSwatchAndTheCustomColorSetTheColor() {
        DocumentStatusDetailView dialog = openNew();
        Div swatches = UiTestUtils.getComponent(dialog, "swatches");
        NativeButton green = (NativeButton) swatches.getChildren().toList().get(1);

        ComponentUtil.fireEvent(green, new ClickEvent<>(green));

        assertThat(dialog.getEditedEntity().getColor()).isEqualTo("#00a045");
        assertThat(green.getElement().getAttribute("aria-pressed")).isEqualTo("true");

        Input custom = UiTestUtils.getComponent(dialog, "customColorInput");
        String old = custom.getValue();
        custom.setValue("#AB12CD");
        ComponentUtil.fireEvent(custom, new AbstractField.ComponentValueChangeEvent<>(custom, custom, old, true));

        assertThat(dialog.getEditedEntity().getColor()).isEqualTo("#ab12cd");
        assertThat(custom.getElement().getAttribute("aria-pressed")).isEqualTo("true");
        assertThat(green.getElement().getAttribute("aria-pressed")).isEqualTo("false");
    }

    @Test
    void theListShowsTheNamesAsBadgesInTheirColorsInTheOrderOfTheStatuses() {
        DocumentStatusListView list = openList();
        DataGrid<DocumentStatus> grid = UiTestUtils.getComponent(list, "statusesDataGrid");
        DocumentStatus active = TestStatuses.active(dataManager);

        assertThat(grid.getItems()).isNotNull();
        assertThat(grid.getItems().getItems()).containsSubsequence(TestStatuses.draft(dataManager), active,
                TestStatuses.archived(dataManager));

        Badge badge = (Badge) TestGrids.render(grid, "name", active);

        assertThat(badge.getText()).isEqualTo(active.getName());
        assertThat(badge.getStyle().get("--aura-accent-color-light")).isEqualTo(active.getColor());
        // Aura tints a badge by hue and chroma, so black, white and grays keep apart only by the hairline
        assertThat(badge.getStyle().get("--vaadin-badge-border-color")).isEqualTo(active.getColor());
    }

    @Test
    void theListShowsASetFlagAsATickedBoxAndAnUnsetOneAsAnEmptyBox() {
        DocumentStatusListView list = openList();
        DataGrid<DocumentStatus> grid = UiTestUtils.getComponent(list, "statusesDataGrid");
        DocumentStatus draft = TestStatuses.draft(dataManager);
        DocumentStatus archived = TestStatuses.archived(dataManager);

        assertThat(classesOf(TestGrids.render(grid, "initial", draft)))
                .containsAll(classesOf(icons.get(JmixFontIcon.CHECK_SQUARE)));
        assertThat(classesOf(TestGrids.render(grid, "locked", draft)))
                .containsAll(classesOf(icons.get(JmixFontIcon.SQUARE)));
        assertThat(classesOf(TestGrids.render(grid, "locked", archived)))
                .containsAll(classesOf(icons.get(JmixFontIcon.CHECK_SQUARE)));
        assertThat(classesOf(TestGrids.render(grid, "anyTarget", draft)))
                .containsAll(classesOf(icons.get(JmixFontIcon.SQUARE)));
    }

    // the list deletes a status through the service, which keeps the one that new documents get
    @Test
    void theListDoesNotDeleteTheStatusForNewDocuments() {
        DocumentStatusListView list = openList();
        DataGrid<DocumentStatus> grid = UiTestUtils.getComponent(list, "statusesDataGrid");
        DocumentStatus draft = TestStatuses.draft(dataManager);

        grid.select(draft);
        UiTestUtils.<JmixButton>getComponent(list, "removeButton").click();

        assertThatThrownBy(() -> confirmButton().click())
                .isInstanceOf(OperationRejectedException.class)
                .hasMessage(messages.formatMessage(DocumentStatusService.class, "statusInitialNotDeleted",
                        draft.getName()));
        assertThat(statusService.findById(draft.getId())).isPresent();
    }

    // a document in the trash can come back with its status, so the refusal says where the documents are
    @Test
    void theListDoesNotDeleteAStatusThatADocumentHasEvenInTheTrash() {
        Folder sandbox = asAdmin(() -> {
            Folder folder = unconstrainedDataManager.create(Folder.class);
            folder.setParent(folderService.getRoot());
            folder.setName("document-status-views-test-" + UUID.randomUUID());
            return folderService.save(folder);
        });
        DocumentStatus review = TestStatuses.create(unconstrainedDataManager, "Review", false, List.of());
        Document document = asAdmin(() -> {
            DocumentStatus draft = TestStatuses.draft(dataManager);
            draft.getTargets().add(review);
            statusService.save(draft);

            Document uploaded = documentService.upload(sandbox, List.of(TestFiles.file(tempDir, "a.txt", "text")))
                    .created()
                    .getFirst();
            documentService.changeStatus(List.of(uploaded), review);
            return uploaded;
        });

        try {
            DocumentStatusListView list = openList();
            DataGrid<DocumentStatus> grid = UiTestUtils.getComponent(list, "statusesDataGrid");

            grid.select(review);
            UiTestUtils.<JmixButton>getComponent(list, "removeButton").click();

            assertThatThrownBy(() -> confirmButton().click())
                    .isInstanceOf(OperationRejectedException.class)
                    .hasMessage(messages.formatMessage(DocumentStatusService.class, "statusInUse",
                            review.getName()));

            asAdmin(() -> {
                documentService.delete(List.of(document));
                return document;
            });
            grid.select(review);
            UiTestUtils.<JmixButton>getComponent(list, "removeButton").click();

            assertThatThrownBy(() -> confirmButton().click())
                    .isInstanceOf(OperationRejectedException.class)
                    .hasMessage(messages.formatMessage(DocumentStatusService.class, "statusInTrash",
                            review.getName()));

            // a document deleted on its own is an item of the trash of its own, outside the sandbox
            asAdmin(() -> {
                trashService.purge(List.of(TestTrash.itemOf(trashService.loadItems(), document.getId())));
                return document;
            });
        } finally {
            asAdmin(() -> {
                DocumentStatus draft = TestStatuses.draft(dataManager);
                draft.getTargets().remove(review);
                statusService.save(draft);
                folderService.delete(sandbox);
                trashService.purge(List.of(TestTrash.itemOf(trashService.loadItems(), sandbox.getId())));
                statusService.delete(review);
                return review;
            });
        }
    }

    private <T> T asAdmin(Supplier<T> work) {
        return systemAuthenticator.withUser("admin", work::get);
    }

    private static Button confirmButton() {
        DialogInfo dialog = UiTestUtils.getLastOpenedDialog();
        assertThat(dialog).isNotNull();

        return dialog.getButtons()
                .stream()
                .filter(button -> button.getId().equals(Optional.of(DialogAction.Type.YES.getId())))
                .findFirst()
                .orElseThrow();
    }

    private Set<String> classesOf(Component component) {
        return Set.copyOf(component.getElement().getClassList());
    }

    private DocumentStatusListView openList() {
        viewNavigators.view(UiTestUtils.getCurrentView(), DocumentStatusListView.class).navigate();
        return UiTestUtils.getCurrentView();
    }

    private DocumentStatusDetailView openNew() {
        DocumentStatusListView list = openList();
        UiTestUtils.<JmixButton>getComponent(list, "createButton").click();
        return UiTestUtils.getLastOpenedViewDialog();
    }
}
