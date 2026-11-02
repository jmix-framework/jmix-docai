package com.company.docai.access;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.access.AccessEntryDraft;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.access.AccessSet;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.security.role.AccessRowLevelRole;
import com.company.docai.security.role.EmployeeRole;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.TestAccess;
import com.company.docai.test_support.TestAccess.Grant;
import com.company.docai.test_support.TestFiles;
import com.company.docai.test_support.TestGrids;
import com.company.docai.view.access.AccessDetailView;
import com.company.docai.view.document.DocumentDetailView;
import com.company.docai.view.folder.FolderLookupView;
import com.company.docai.view.main.MainView;
import com.company.docai.view.repository.FolderTreeFragment;
import com.company.docai.view.repository.RepositoryView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.NativeLabel;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.upload.UploadDropZone;
import com.vaadin.flow.router.QueryParameters;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.Messages;
import io.jmix.core.security.AccessDeniedException;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.component.checkbox.JmixCheckbox;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.grid.TreeDataGrid;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.component.upload.JmixUpload;
import io.jmix.flowui.fragment.FragmentUtils;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.kit.component.dropdownbutton.DropdownButton;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.testassist.dialog.DialogInfo;
import io.jmix.flowui.view.DialogWindow;
import io.jmix.flowui.view.ViewControllerUtils;
import io.jmix.security.role.RoleGrantedAuthorityUtils;
import io.jmix.securityflowui.exception.AccessDeniedExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.GrantedAuthority;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The repository as peter, an employee, sees it. The tree: "Drafts" inherits from the root; "Published" is
 * viewed by all employees; "Managed" is changed by all employees and managed by peter; "Closed" is managed by
 * helen alone and holds "Public", viewed by all employees, "Secret", which inherits from "Closed", and
 * "memo.txt", viewed by peter.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class AccessUiTest {

    private static final String EMPLOYEE = EmployeeUiTestAuthenticator.USERNAME;
    private static final String HR_EMPLOYEE = "helen";
    private static final String ADMIN = "admin";
    // a demo employee who is given no entries, so manages nothing
    private static final String NON_MANAGER = "john";
    private static final List<String> FOLDER_CHANGES =
            List.of("createFolderAction", "renameFolderAction", "moveFolderAction", "deleteFolderAction");

    @Autowired
    private DataManager dataManager;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private CurrentAuthentication currentAuthentication;

    @Autowired
    private RoleGrantedAuthorityUtils roleGrantedAuthorityUtils;

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private DialogWindows dialogWindows;

    @Autowired
    private AccessDeniedExceptionHandler accessDeniedExceptionHandler;

    @Autowired
    private Messages messages;

    @TempDir
    private Path tempDir;

    private Folder sandbox;
    private Folder drafts;
    private Folder published;
    private Folder managed;
    private Folder closed;
    private Folder publicFolder;
    private Folder secret;
    private Document offer;
    private Document priceList;
    private Document plan;
    private Document memo;

    @BeforeEach
    void createTree() {
        systemAuthenticator.runWithUser(ADMIN, () -> {
            sandbox = folder(folderService.getRoot(), "access-ui-test-" + UUID.randomUUID());
            drafts = folder(sandbox, "Drafts");
            published = TestAccess.giveOwnAccess(dataManager, folder(sandbox, "Published"),
                    Grant.role(EmployeeRole.CODE, AccessLevel.VIEW));
            managed = TestAccess.giveOwnAccess(dataManager, folder(sandbox, "Managed"),
                    Grant.role(EmployeeRole.CODE, AccessLevel.EDIT), Grant.user(EMPLOYEE, AccessLevel.MANAGE));
            closed = TestAccess.giveOwnAccess(dataManager, folder(sandbox, "Closed"),
                    Grant.user(HR_EMPLOYEE, AccessLevel.MANAGE));
            publicFolder = TestAccess.giveOwnAccess(dataManager, folder(closed, "Public"),
                    Grant.role(EmployeeRole.CODE, AccessLevel.VIEW));
            secret = folder(closed, "Secret");

            offer = upload(sandbox, "offer.txt");
            priceList = upload(published, "price-list.txt");
            plan = upload(managed, "plan.txt");
            memo = TestAccess.giveOwnAccess(dataManager, upload(closed, "memo.txt"),
                    Grant.user(EMPLOYEE, AccessLevel.VIEW));
        });
    }

    @AfterEach
    void deleteTree() {
        systemAuthenticator.runWithUser(ADMIN, () -> folderService.delete(sandbox));
    }

    @Test
    void theEmployeeOfTheUiTestsHasTheAccessRole() {
        assertThat(currentAuthentication.getUser().getUsername()).isEqualTo(EMPLOYEE);
        assertThat(currentAuthentication.getAuthentication().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains(roleGrantedAuthorityUtils.createRowLevelRoleGrantedAuthority(AccessRowLevelRole.CODE)
                        .getAuthority());
    }

    @Test
    void theTreeGreysTheWayAndMarksWhatTheEmployeeManages() {
        TreeDataGrid<Folder> tree = foldersTree(open(sandbox));

        assertThat(tree.getItems().getItems()).contains(closed, publicFolder).doesNotContain(secret);
        assertThat(label(tree, closed).hasClassName("folder-greyed")).isTrue();
        assertThat(label(tree, published).hasClassName("folder-greyed")).isFalse();
        assertThat(marksOwnAccess(label(tree, managed))).isTrue();
        // published has its own access, but peter does not manage it
        assertThat(marksOwnAccess(label(tree, published))).isFalse();
        assertThat(marksOwnAccess(label(tree, drafts))).isFalse();
    }

    @Test
    void theDocumentListMarksTheDocumentsTheEmployeeManagesWithTheirOwnAccess() {
        Document notes = systemAuthenticator.withUser(ADMIN, () -> TestAccess.giveOwnAccess(dataManager,
                upload(drafts, "notes.txt"), Grant.user(EMPLOYEE, AccessLevel.MANAGE)));
        Document report = systemAuthenticator.withUser(ADMIN, () -> TestAccess.giveOwnAccess(dataManager,
                upload(drafts, "report.txt"), Grant.user(EMPLOYEE, AccessLevel.VIEW)));
        Document draft = systemAuthenticator.withUser(ADMIN, () -> upload(drafts, "draft.txt"));
        DataGrid<Document> grid = documentsGrid(open(drafts));

        assertThat(marksOwnAccess(TestGrids.render(grid, "name", notes))).isTrue();
        // report has its own access, but peter only views it
        assertThat(marksOwnAccess(TestGrids.render(grid, "name", report))).isFalse();
        assertThat(marksOwnAccess(TestGrids.render(grid, "name", draft))).isFalse();
    }

    @Test
    void theDocumentActionsAreIconsThatKeepTheirNames() {
        RepositoryView view = open(managed);

        // a screen reader reads the aria-label, so it must hold the action's name
        assertThat(List.of("openButton", "moveButton", "accessButton", "deleteButton")).allSatisfy(id -> {
            JmixButton button = fragmentComponent(view, "documentList", id);
            assertThat(button.getText()).as(id).isEmpty();
            assertThat(button.getElement().getAttribute("aria-label")).as(id).isNotBlank();
        });

        Button versionButton = (Button) this.<JmixUpload<?>>fragmentComponent(view, "documentList", "versionUpload")
                .getUploadButton();
        assertThat(versionButton.getText()).isEmpty();
        assertThat(versionButton.getElement().getAttribute("aria-label")).isNotBlank();

        DropdownButton statusButton = fragmentComponent(view, "documentList", "statusButton");
        assertThat(statusButton.getText()).isEmpty();
        assertThat(statusButton.getElement().getAttribute("aria-label")).isNotBlank();
    }

    @Test
    void theFolderActionsFollowTheLevel() {
        TreeDataGrid<Folder> tree = foldersTree(open(sandbox));

        TestGrids.clickRendered(tree, "name", published);
        assertThat(FOLDER_CHANGES).allSatisfy(id -> assertThat(tree.getAction(id).isEnabled()).as(id).isFalse());
        assertThat(tree.getAction("accessFolderAction").isVisible()).isFalse();

        TestGrids.clickRendered(tree, "name", closed);
        assertThat(FOLDER_CHANGES).allSatisfy(id -> assertThat(tree.getAction(id).isEnabled()).as(id).isFalse());

        TestGrids.clickRendered(tree, "name", managed);
        assertThat(FOLDER_CHANGES).allSatisfy(id -> assertThat(tree.getAction(id).isEnabled()).as(id).isTrue());
        assertThat(tree.getAction("accessFolderAction").isVisible()).isTrue();
        assertThat(tree.getAction("accessFolderAction").isEnabled()).isTrue();
    }

    @Test
    void aViewedFolderTakesNoNewDocumentsAndItsDocumentsStayAsTheyAre() {
        RepositoryView view = open(published);
        DataGrid<Document> grid = documentsGrid(view);

        TestGrids.clickRow(grid, priceList, false);

        assertThat(this.<JmixUpload<?>>fragmentComponent(view, "documentList", "documentsUpload").isEnabled()).isFalse();
        assertThat(documentsGrid(view).getParent()).get()
                .isInstanceOfSatisfying(UploadDropZone.class, zone -> assertThat(zone.isEnabled()).isFalse());
        assertThat(this.<JmixButton>fragmentComponent(view, "documentList", "createDocumentButton").isEnabled())
                .isFalse();
        assertThat(this.<JmixUpload<?>>fragmentComponent(view, "documentList", "versionUpload").isEnabled()).isFalse();
        assertThat(List.of("moveAction", "activateAction", "accessAction", "deleteAction"))
                .allSatisfy(id -> assertThat(grid.getAction(id).isEnabled()).as(id).isFalse());

        folderTree(view).select(sandbox);
        TestGrids.clickRow(grid, offer, false);

        assertThat(this.<JmixUpload<?>>fragmentComponent(view, "documentList", "documentsUpload").isEnabled()).isTrue();
        assertThat(documentsGrid(view).getParent()).get()
                .isInstanceOfSatisfying(UploadDropZone.class, zone -> assertThat(zone.isEnabled()).isTrue());
        assertThat(List.of("moveAction", "activateAction", "deleteAction"))
                .allSatisfy(id -> assertThat(grid.getAction(id).isEnabled()).as(id).isTrue());
    }

    @Test
    void aDocumentTheEmployeeOnlyViewsOpensReadOnly() {
        DocumentDetailView viewed = openCard(priceList);
        DocumentDetailView changed = openCard(plan);

        assertThat(viewed.isReadOnly()).isTrue();
        assertThat(UiTestUtils.<TypedTextField<String>>getComponent(viewed, "nameField").isReadOnly()).isTrue();
        assertThat(List.of("newVersionUpload", "activateButton", "accessButton"))
                .allSatisfy(id -> assertThat(UiTestUtils.<Component>getComponent(viewed, id).isVisible()).as(id)
                        .isFalse());
        assertThat(changed.isReadOnly()).isFalse();
        assertThat(List.of("newVersionUpload", "activateButton", "accessButton"))
                .allSatisfy(id -> assertThat(UiTestUtils.<Component>getComponent(changed, id).isVisible()).as(id)
                        .isTrue());
    }

    @Test
    void aGreyedFolderShowsTheDocumentsTheEmployeeSeesWithTheirWholePath() {
        RepositoryView view = open(closed);

        assertThat(documentsGrid(view).getItems().getItems()).containsExactly(memo);
        assertThat(UiTestUtils.<TypedTextField<String>>getComponent(openCard(memo), "folderField").getValue())
                .endsWith(sandbox.getName() + " / Closed");
    }

    @Test
    void theBreadcrumbsNameAGreyedFolderWithoutALink() {
        HorizontalLayout breadcrumbs = UiTestUtils.getComponent(open(publicFolder), "breadcrumbs");
        Map<String, Component> steps = breadcrumbs.getChildren()
                .filter(step -> !step.getElement().getTextRecursively().equals("/"))
                .collect(Collectors.toMap(step -> step.getElement().getTextRecursively(), Function.identity()));

        assertThat(steps.get(sandbox.getName())).isInstanceOf(JmixButton.class);
        assertThat(steps.get("Closed")).isNotInstanceOf(JmixButton.class);
    }

    @Test
    void aFolderTheEmployeeCannotSeeOpensTheRootInstead() {
        RepositoryView view = open(secret);

        assertThat(folderTree(view).getSelectedFolder()).isEqualTo(folderService.getRoot());
        assertThat(UiTestUtils.getLastOpenedNotification().getText())
                .isEqualTo(messages.getMessage("com.company.docai.view.repository/folderUnavailable"));
        assertThat(UiTestUtils.getLastOpenedNotification().getType()).isEqualTo(Notifications.Type.WARNING);
    }

    @Test
    void theMoveDialogOffersOnlyFoldersTheEmployeeChanges() {
        TreeDataGrid<Folder> tree = foldersTree(open(drafts));
        tree.getAction("moveFolderAction").actionPerform(tree);
        FolderLookupView lookup = UiTestUtils.getLastOpenedViewDialog();
        TreeDataGrid<Folder> lookupTree = UiTestUtils.getComponent(lookup, "foldersTree");
        JmixButton selectButton = UiTestUtils.getComponent(lookup, "selectButton");

        TestGrids.clickRendered(lookupTree, "name", published);
        assertThat(label(lookupTree, published).hasClassName("folder-greyed")).isTrue();
        assertThat(selectButton.isEnabled()).isFalse();

        TestGrids.clickRendered(lookupTree, "name", managed);
        assertThat(label(lookupTree, managed).hasClassName("folder-greyed")).isFalse();
        assertThat(selectButton.isEnabled()).isTrue();
    }

    @Test
    void aMoveThatChangesTheAccessAsksFirst() {
        TreeDataGrid<Folder> tree = foldersTree(open(drafts));
        tree.getAction("moveFolderAction").actionPerform(tree);
        FolderLookupView lookup = UiTestUtils.getLastOpenedViewDialog();
        TestGrids.clickRendered(UiTestUtils.getComponent(lookup, "foldersTree"), "name", managed);
        UiTestUtils.<JmixButton>getComponent(lookup, "selectButton").click();

        assertThat(setOf(drafts)).isEqualTo(setOf(sandbox));

        dialogButton(DialogAction.Type.YES).click();

        assertThat(setOf(drafts)).isEqualTo(setOf(managed));
    }

    @Test
    void aFolderWithContentTheEmployeeCannotChangeIsRefusedBeforeTheDialog() {
        TreeDataGrid<Folder> tree = foldersTree(open(sandbox));

        assertThatThrownBy(() -> tree.getAction("deleteFolderAction").actionPerform(tree))
                .isInstanceOf(OperationRejectedException.class);
        assertThat(UiTestUtils.getOpenedDialogs()).isEmpty();
    }

    @Test
    void givingUpTheAccessMovesTheTreeToTheNearestFolder() {
        RepositoryView view = open(managed);
        TreeDataGrid<Folder> tree = foldersTree(view);
        tree.getAction("accessFolderAction").actionPerform(tree);
        AccessDetailView dialog = UiTestUtils.getLastOpenedViewDialog();
        DataGrid<AccessEntryDraft> entriesGrid = UiTestUtils.getComponent(dialog, "entriesGrid");
        CollectionContainer<AccessEntryDraft> entriesDc = ViewControllerUtils.getViewData(dialog)
                .getContainer("entriesDc");

        // peter removes the employees and himself, so the folder is left to the administrator
        List.copyOf(entriesDc.getItems())
                .forEach(entry -> ((JmixButton) TestGrids.render(entriesGrid, "remove", entry)).click());
        UiTestUtils.<JmixButton>getComponent(dialog, "saveButton").click();
        dialogButton(DialogAction.Type.YES).click();

        assertThat(folderTree(view).getSelectedFolder()).isEqualTo(sandbox);
        assertThat(foldersTree(view).getItems().getItems()).doesNotContain(managed);
        assertThat(UiTestUtils.getLastOpenedNotification().getText())
                .isEqualTo(messages.getMessage("com.company.docai.view.repository/folderUnavailable"));
    }

    @Test
    void theAccessDialogOpensForADocumentTheEmployeeManages() {
        DataGrid<Document> grid = documentsGrid(open(managed));

        TestGrids.clickRow(grid, plan, false);
        assertThat(grid.getAction("accessAction").isVisible()).isTrue();
        grid.getAction("accessAction").actionPerform(grid);

        AccessDetailView dialog = UiTestUtils.getLastOpenedViewDialog();
        assertThat(dialog.getEditedEntity().getDocument()).isEqualTo(plan);
    }

    @Test
    void theDocumentToolbarKeepsTheKeyInPlaceForAnEmployeeWhoManagesSomething() {
        RepositoryView publishedView = open(published);
        JmixButton accessButton = fragmentComponent(publishedView, "documentList", "accessButton");

        // peter manages "Managed", so the key stays, switched off, where he manages nothing
        assertThat(accessButton.isVisible()).isTrue();
        TestGrids.clickRow(documentsGrid(publishedView), priceList, false);
        assertThat(accessButton.isEnabled()).isFalse();

        RepositoryView managedView = open(managed);
        JmixButton managedAccessButton = fragmentComponent(managedView, "documentList", "accessButton");
        TestGrids.clickRow(documentsGrid(managedView), plan, false);
        assertThat(managedAccessButton.isEnabled()).isTrue();
        managedAccessButton.click();

        assertThat(UiTestUtils.<AccessDetailView>getLastOpenedViewDialog().getEditedEntity().getDocument())
                .isEqualTo(plan);
    }

    @Test
    void theDocumentToolbarHasNoKeyForAnEmployeeWhoManagesNothing() {
        systemAuthenticator.runWithUser(NON_MANAGER, () -> assertThat(
                this.<JmixButton>fragmentComponent(open(drafts), "documentList", "accessButton").isVisible())
                .isFalse());
    }

    @Test
    void theCardTakesItsDocumentAgainAfterItsAccessDialog() {
        DocumentDetailView card = openCard(plan);
        AccessDetailView dialog = openAccess(card);
        UiTestUtils.<JmixCheckbox>getComponent(dialog, "inheritField").setValue(false);
        UiTestUtils.<JmixButton>getComponent(dialog, "saveButton").click();

        UiTestUtils.<TypedTextField<String>>getComponent(card, "nameField").setValue("plan-2026.txt");
        UiTestUtils.<JmixButton>getComponent(card, "saveButton").click();

        assertThat(systemAuthenticator.withUser(ADMIN, () -> dataManager.load(Id.of(plan)).one().getName()))
                .isEqualTo("plan-2026.txt");
    }

    @Test
    void theCardAsksToSaveItsChangesBeforeTheAccessDialog() {
        DocumentDetailView card = openCard(plan);
        UiTestUtils.<TypedTextField<String>>getComponent(card, "nameField").setValue("plan-2026.txt");

        UiTestUtils.<JmixButton>getComponent(card, "accessButton").click();

        assertThat(UiTestUtils.getOpenedViewDialogs()).noneMatch(AccessDetailView.class::isInstance);
        assertThat(UiTestUtils.getLastOpenedNotification().getText())
                .isEqualTo(messages.getMessage("com.company.docai.view.document/saveBeforeAccess"));
    }

    @Test
    void theCardTurnsReadOnlyWhenTheEmployeeKeepsOnlyTheView() {
        DocumentDetailView card = openCard(plan);
        AccessDetailView dialog = openAccess(card);
        UiTestUtils.<JmixCheckbox>getComponent(dialog, "inheritField").setValue(false);
        entries(dialog).getItems().forEach(entry -> entry.setAccessLevel(AccessLevel.VIEW));
        UiTestUtils.<JmixButton>getComponent(dialog, "saveButton").click();
        dialogButton(DialogAction.Type.YES).click();

        assertThat(card.isReadOnly()).isTrue();
        assertThat(UiTestUtils.<Component>getComponent(card, "accessButton").isVisible()).isFalse();
    }

    @Test
    void theCardClosesWhenTheEmployeeGivesUpTheDocument() {
        DocumentDetailView card = openCard(plan);
        AccessDetailView dialog = openAccess(card);
        DataGrid<AccessEntryDraft> entriesGrid = UiTestUtils.getComponent(dialog, "entriesGrid");

        // peter keeps no entry, so the document is left to the administrator
        UiTestUtils.<JmixCheckbox>getComponent(dialog, "inheritField").setValue(false);
        List.copyOf(entries(dialog).getItems())
                .forEach(entry -> ((JmixButton) TestGrids.render(entriesGrid, "remove", entry)).click());
        UiTestUtils.<JmixButton>getComponent(dialog, "saveButton").click();
        dialogButton(DialogAction.Type.YES).click();

        assertThat(UiTestUtils.getOpenedViewDialogs()).doesNotContain(card);
        assertThat(UiTestUtils.getLastOpenedNotification().getText())
                .isEqualTo(messages.getMessage("com.company.docai.view.document/documentUnavailable"));
    }

    @Test
    void aSaveThePredicatesDenyIsShownAsAnError() {
        boolean handled = accessDeniedExceptionHandler.handle(new IllegalStateException(
                new AccessDeniedException("entity", "Folder", "update")));

        assertThat(handled).isTrue();
        assertThat(UiTestUtils.getLastOpenedNotification().getText())
                .isEqualTo(messages.getMessage(AccessDeniedExceptionHandler.class, "accessDeniedError.title"));
        assertThat(UiTestUtils.getLastOpenedNotification().getType()).isEqualTo(Notifications.Type.ERROR);
    }

    private RepositoryView open(Folder folder) {
        viewNavigators.view(UiTestUtils.getCurrentView(), RepositoryView.class)
                .withQueryParameters(QueryParameters.of("folder", folder.getId().toString()))
                .navigate();

        return UiTestUtils.getCurrentView();
    }

    private DocumentDetailView openCard(Document document) {
        DialogWindow<DocumentDetailView> window = dialogWindows.detail(UiTestUtils.getCurrentView(), Document.class)
                .withViewClass(DocumentDetailView.class)
                .editEntity(document)
                .build();

        window.open();
        return window.getView();
    }

    private AccessDetailView openAccess(DocumentDetailView card) {
        UiTestUtils.<JmixButton>getComponent(card, "accessButton").click();
        return UiTestUtils.getLastOpenedViewDialog();
    }

    private CollectionContainer<AccessEntryDraft> entries(AccessDetailView dialog) {
        return ViewControllerUtils.getViewData(dialog).getContainer("entriesDc");
    }

    private NativeLabel label(TreeDataGrid<Folder> tree, Folder folder) {
        return (NativeLabel) TestGrids.render(tree, "name", folder).getChildren()
                .findFirst()
                .orElseThrow();
    }

    private boolean marksOwnAccess(Component label) {
        return label.getChildren()
                .anyMatch(child -> child.getElement().getClassList().contains("own-access"));
    }

    private Button dialogButton(DialogAction.Type type) {
        DialogInfo dialog = UiTestUtils.getLastOpenedDialog();
        assertThat(dialog).isNotNull();
        return dialog.getButtons().stream()
                .filter(button -> button.getId().equals(Optional.of(type.getId())))
                .findFirst()
                .orElseThrow();
    }

    private AccessSet setOf(Folder folder) {
        return systemAuthenticator.withUser(ADMIN, () -> TestAccess.setOf(dataManager, folder));
    }

    private FolderTreeFragment folderTree(RepositoryView view) {
        return UiTestUtils.getComponent(view, "folderTree");
    }

    private TreeDataGrid<Folder> foldersTree(RepositoryView view) {
        return fragmentComponent(view, "folderTree", "foldersTree");
    }

    private DataGrid<Document> documentsGrid(RepositoryView view) {
        return fragmentComponent(view, "documentList", "documentsGrid");
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
        return documentService.upload(folder, List.of(TestFiles.file(tempDir, fileName, "text")))
                .created().getFirst();
    }
}
