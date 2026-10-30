package com.company.docai.view.access;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.access.AccessService;
import com.company.docai.app.service.access.AccessSubject;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.access.AccessDraft;
import com.company.docai.model.access.AccessEntry;
import com.company.docai.model.access.AccessEntryDraft;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.access.AccessSet;
import com.company.docai.model.folder.Folder;
import com.company.docai.security.role.EmployeeRole;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.TestAccess;
import com.company.docai.test_support.TestAccess.Grant;
import com.company.docai.test_support.TestGrids;
import com.company.docai.view.main.MainView;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.data.provider.DataProvider;
import com.vaadin.flow.data.provider.Query;
import io.jmix.core.DataManager;
import io.jmix.core.MessageTools;
import io.jmix.core.Metadata;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.component.checkbox.JmixCheckbox;
import io.jmix.flowui.component.combobox.JmixComboBox;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.select.JmixSelect;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.testassist.dialog.DialogInfo;
import io.jmix.flowui.view.DialogWindow;
import io.jmix.flowui.view.ViewControllerUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The access dialog as peter uses it on "Shared", whose own access he manages, and on "Shared/Team", which
 * inherits it: the box breaks and restores the inheritance, the entries change only on OK, and taking
 * the management from oneself asks first.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class AccessDetailViewTest {

    private static final String EMPLOYEE = EmployeeUiTestAuthenticator.USERNAME;
    private static final String ADMIN = "admin";

    @Autowired
    private DataManager dataManager;

    @Autowired
    private FolderService folderService;

    @Autowired
    private AccessService accessService;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private DialogWindows dialogWindows;

    @Autowired
    private MessageTools messageTools;

    @Autowired
    private Metadata metadata;

    private Folder sandbox;
    private Folder shared;
    private Folder team;

    @BeforeEach
    void createTree() {
        systemAuthenticator.runWithUser(ADMIN, () -> {
            sandbox = folder(folderService.getRoot(), "access-dialog-test-" + UUID.randomUUID());
            shared = TestAccess.giveOwnAccess(dataManager, folder(sandbox, "Shared"),
                    Grant.role(EmployeeRole.CODE, AccessLevel.EDIT), Grant.user(EMPLOYEE, AccessLevel.MANAGE));
            team = folder(shared, "Team");
        });
    }

    @AfterEach
    void deleteTree() {
        systemAuthenticator.runWithUser(ADMIN, () -> folderService.delete(sandbox));
    }

    @Test
    void theSubjectListFindsRolesAndUsersByAPartOfTheName() {
        JmixComboBox<AccessSubject> subjectField = component(open(shared), "subjectField");

        assertThat(fetch(subjectField, "HEL")).extracting(AccessSubject::username)
                .contains("helen")
                .doesNotContain(EMPLOYEE);
        assertThat(fetch(subjectField, "").getFirst().isRole()).isTrue();
    }

    @Test
    void anInheritingFolderShowsTheInheritedEntriesReadOnly() {
        AccessDetailView dialog = open(team);

        assertThat(inheritField(dialog).getValue()).isTrue();
        assertThat(inheritField(dialog).getLabel()).contains("Shared");
        assertThat(this.<DataGrid<?>>component(dialog, "inheritedEntriesGrid").isVisible()).isTrue();
        assertThat(this.<DataGrid<?>>component(dialog, "entriesGrid").isVisible()).isFalse();
        assertThat(this.<HorizontalLayout>component(dialog, "addPanel").isVisible()).isFalse();
        assertThat(entries(dialog, "inheritedEntriesDc")).isEqualTo("@employee=EDIT, peter=MANAGE");
        assertThat(dialog.getPageTitle()).contains("Team");
        assertThat(dialog.hasUnsavedChanges()).isFalse();
    }

    @Test
    void clearingTheBoxBreaksTheInheritanceWithACopyOfTheEntries() {
        AccessDetailView dialog = open(team);

        inheritField(dialog).setValue(false);
        assertThat(this.<DataGrid<?>>component(dialog, "entriesGrid").isVisible()).isTrue();
        assertThat(entries(dialog, "entriesDc")).isEqualTo("@employee=EDIT, peter=MANAGE");
        ok(dialog);

        assertThat(setOf(team)).isNotEqualTo(setOf(shared));
        assertThat(storedEntries(team)).isEqualTo("@employee=EDIT, peter=MANAGE");
    }

    @Test
    void checkingTheBoxAgainBeforeOkChangesNothing() {
        AccessDetailView dialog = open(team);

        inheritField(dialog).setValue(false);
        add(dialog, "maria", AccessLevel.VIEW);
        inheritField(dialog).setValue(true);
        ok(dialog);

        assertThat(setOf(team)).isEqualTo(setOf(shared));
    }

    @Test
    void theOwnEntriesAreChangedAndAdded() {
        AccessDetailView dialog = open(shared);

        levelSelect(dialog, entry(dialog, EmployeeRole.CODE)).setValue(AccessLevel.VIEW);
        add(dialog, "maria", AccessLevel.EDIT);
        ok(dialog);

        assertThat(storedEntries(shared)).isEqualTo("@employee=VIEW, maria=EDIT, peter=MANAGE");
    }

    @Test
    void aRemovedEntryIsDeleted() {
        AccessDetailView dialog = open(shared);

        removeButton(dialog, entry(dialog, EmployeeRole.CODE)).click();
        ok(dialog);

        assertThat(storedEntries(shared)).isEqualTo("peter=MANAGE");
    }

    @Test
    void aRevertedEditIsNoChange() {
        AccessDetailView dialog = open(shared);

        levelSelect(dialog, entry(dialog, EmployeeRole.CODE)).setValue(AccessLevel.VIEW);
        levelSelect(dialog, entry(dialog, EmployeeRole.CODE)).setValue(AccessLevel.EDIT);
        add(dialog, "maria", AccessLevel.VIEW);
        removeButton(dialog, entry(dialog, "maria")).click();

        assertThat(dialog.hasUnsavedChanges()).isFalse();
    }

    @Test
    void takingTheManagementFromOneselfAsksFirst() {
        AccessDetailView dialog = open(shared);
        removeButton(dialog, entry(dialog, EMPLOYEE)).click();

        ok(dialog);
        dialogButton(DialogAction.Type.NO).click();

        assertThat(UiTestUtils.getOpenedViewDialogs()).contains(dialog);
        assertThat(storedEntries(shared)).isEqualTo("@employee=EDIT, peter=MANAGE");

        ok(dialog);
        dialogButton(DialogAction.Type.YES).click();

        assertThat(storedEntries(shared)).isEqualTo("@employee=EDIT");
    }

    @Test
    void checkingTheBoxOfAnOwnSetReturnsToTheInheritance() {
        AccessDetailView dialog = open(shared);

        inheritField(dialog).setValue(true);
        assertThat(entries(dialog, "inheritedEntriesDc")).isEqualTo("@employee=EDIT");
        ok(dialog);
        // the root gives employees no management, so peter confirms that he gives it up
        dialogButton(DialogAction.Type.YES).click();

        assertThat(setOf(shared)).isEqualTo(setOf(sandbox));
        assertThat(setOf(team)).isEqualTo(setOf(sandbox));
    }

    @Test
    void cancelChangesNothing() {
        AccessDetailView dialog = open(team);
        inheritField(dialog).setValue(false);

        dialog.closeWithDiscard();

        assertThat(setOf(team)).isEqualTo(setOf(shared));
    }

    @Test
    void theRootHasNoBoxToInherit() {
        systemAuthenticator.runWithUser(ADMIN, () -> {
            JmixCheckbox inheritField = inheritField(open(folderService.getRoot()));

            assertThat(inheritField.isVisible()).isFalse();
            assertThat(inheritField.getLabel()).isNull();
        });
    }

    @Test
    void theLevelListsOpenUnderTheirFields() {
        AccessDetailView dialog = open(shared);

        assertThat(this.<JmixSelect<AccessLevel>>component(dialog, "levelField").isNoVerticalOverlap()).isTrue();
        assertThat(levelSelect(dialog, entry(dialog, EmployeeRole.CODE)).isNoVerticalOverlap()).isTrue();
    }

    @Test
    void removingAnEntryLooksDangerous() {
        AccessDetailView dialog = open(shared);

        assertThat(removeButton(dialog, entry(dialog, EmployeeRole.CODE)).getThemeNames())
                .contains(ButtonVariant.TERTIARY.getVariantName(), ButtonVariant.ERROR.getVariantName());
    }

    @Test
    void aLevelHasTheSameNameInTheDialogAndInDataTools() {
        assertThat(messageTools.getPropertyCaption(metadata.getClass(AccessEntry.class), "accessLevel"))
                .isEqualTo(messageTools.getPropertyCaption(metadata.getClass(AccessEntryDraft.class), "accessLevel"));
    }

    private AccessDetailView open(Folder folder) {
        DialogWindow<AccessDetailView> window = dialogWindows.detail(UiTestUtils.getCurrentView(), AccessDraft.class)
                .withViewClass(AccessDetailView.class)
                .editEntity(accessService.describe(folder))
                .build();

        window.open();
        return window.getView();
    }

    @SuppressWarnings("unchecked")
    private List<AccessSubject> fetch(JmixComboBox<AccessSubject> subjectField, String filter) {
        DataProvider<AccessSubject, String> items = (DataProvider<AccessSubject, String>) subjectField.getDataProvider();
        return items.fetch(new Query<>(0, 50, List.of(), null, filter)).toList();
    }

    private void ok(AccessDetailView dialog) {
        this.<JmixButton>component(dialog, "saveButton").click();
    }

    private void add(AccessDetailView dialog, String username, AccessLevel level) {
        AccessSubject subject = accessService.findSubjects(username, 0, 50).stream()
                .filter(each -> username.equals(each.username()))
                .findFirst()
                .orElseThrow();

        this.<JmixComboBox<AccessSubject>>component(dialog, "subjectField").setValue(subject);
        this.<JmixSelect<AccessLevel>>component(dialog, "levelField").setValue(level);
        this.<JmixButton>component(dialog, "addButton").click();
    }

    private AccessEntryDraft entry(AccessDetailView dialog, String subject) {
        CollectionContainer<AccessEntryDraft> entriesDc = ViewControllerUtils.getViewData(dialog)
                .getContainer("entriesDc");

        return entriesDc.getItems().stream()
                .filter(entry -> subject.equals(entry.getUsername()) || subject.equals(entry.getRoleCode()))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private JmixSelect<AccessLevel> levelSelect(AccessDetailView dialog, AccessEntryDraft entry) {
        DataGrid<AccessEntryDraft> grid = component(dialog, "entriesGrid");
        return (JmixSelect<AccessLevel>) TestGrids.render(grid, "accessLevel", entry);
    }

    private JmixButton removeButton(AccessDetailView dialog, AccessEntryDraft entry) {
        DataGrid<AccessEntryDraft> grid = component(dialog, "entriesGrid");
        return (JmixButton) TestGrids.render(grid, "remove", entry);
    }

    private JmixCheckbox inheritField(AccessDetailView dialog) {
        return component(dialog, "inheritField");
    }

    private Button dialogButton(DialogAction.Type type) {
        DialogInfo dialog = UiTestUtils.getLastOpenedDialog();
        assertThat(dialog).isNotNull();
        return dialog.getButtons().stream()
                .filter(button -> button.getId().equals(Optional.of(type.getId())))
                .findFirst()
                .orElseThrow();
    }

    private String entries(AccessDetailView dialog, String containerId) {
        CollectionContainer<AccessEntryDraft> container = ViewControllerUtils.getViewData(dialog)
                .getContainer(containerId);

        return describe(container.getItems().stream()
                .map(entry -> subject(entry.getUsername(), entry.getRoleCode()) + "=" + entry.getAccessLevel())
                .toList());
    }

    private String storedEntries(Folder folder) {
        return systemAuthenticator.withUser(ADMIN, () -> describe(dataManager.load(AccessEntry.class)
                .query("e.accessSet = ?1", setOf(folder))
                .list().stream()
                .map(entry -> subject(entry.getUsername(), entry.getRoleCode()) + "=" + entry.getAccessLevel())
                .toList()));
    }

    private AccessSet setOf(Folder folder) {
        return systemAuthenticator.withUser(ADMIN, () -> TestAccess.setOf(dataManager, folder));
    }

    private <T> T component(AccessDetailView dialog, String id) {
        return UiTestUtils.getComponent(dialog, id);
    }

    private Folder folder(Folder parent, String name) {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(parent);
        folder.setName(name);
        return folderService.save(folder);
    }

    private static String subject(String username, String roleCode) {
        return username != null ? username : "@" + roleCode;
    }

    private static String describe(Collection<String> entries) {
        return entries.stream()
                .sorted()
                .collect(Collectors.joining(", "));
    }
}
