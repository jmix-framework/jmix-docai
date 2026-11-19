package com.company.docai.view.document;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.document.DocumentStatusService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.document.Document;
import com.company.docai.model.document.DocumentRevision;
import com.company.docai.model.document.DocumentStatus;
import com.company.docai.model.folder.Folder;
import com.company.docai.model.tag.Tag;
import com.company.docai.model.user.User;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.TestAccess;
import com.company.docai.test_support.TestAccess.Grant;
import com.company.docai.test_support.TestFiles;
import com.company.docai.test_support.TestGrids;
import com.company.docai.test_support.TestPalettes;
import com.company.docai.test_support.TestSamples;
import com.company.docai.test_support.TestStatuses;
import com.company.docai.test_support.TestTags;
import com.company.docai.test_support.TestUploads;
import com.company.docai.view.main.MainView;
import com.company.docai.view.repository.RepositoryView;
import com.company.docai.view.search.DocumentSearchView;
import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBoxBase;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.internal.PendingJavaScriptInvocation;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.shared.Tooltip;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.data.provider.SortDirection;
import io.jmix.core.DataManager;
import io.jmix.core.EntityStates;
import io.jmix.core.FetchPlan;
import io.jmix.core.Id;
import io.jmix.core.Messages;
import io.jmix.core.MetadataTools;
import io.jmix.core.SaveContext;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.metamodel.datatype.DatatypeFormatter;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.data.PersistenceHints;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.multiselectcombobox.JmixMultiSelectComboBox;
import io.jmix.flowui.component.select.JmixSelect;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.component.upload.JmixUpload;
import io.jmix.flowui.data.grid.DataGridItems;
import io.jmix.flowui.fragment.FragmentUtils;
import io.jmix.flowui.kit.action.Action;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.testassist.dialog.DialogInfo;
import io.jmix.flowui.testassist.notification.NotificationInfo;
import io.jmix.flowui.testassist.notification.OpenedNotifications;
import io.jmix.flowui.upload.TemporaryStorage;
import io.jmix.flowui.view.DialogWindow;
import io.jmix.flowui.view.View;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.node.JsonNodeFactory;

import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class DocumentDetailViewTest {

    @Autowired
    private DialogWindows dialogWindows;

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @Autowired
    private TemporaryStorage temporaryStorage;

    @Autowired
    private Messages messages;

    @Autowired
    private MetadataTools metadataTools;

    @Autowired
    private EntityStates entityStates;

    @Autowired
    private DatatypeFormatter datatypeFormatter;

    @Autowired
    private CurrentAuthentication currentAuthentication;

    @Autowired
    private FileSizeFormatter fileSizeFormatter;

    @Autowired
    private DocumentRenderers documentRenderers;

    @Autowired
    private DocumentStatusService statusService;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private OpenedNotifications openedNotifications;

    @TempDir
    private Path tempDir;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private final List<DocumentStatus> statuses = new ArrayList<>();

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(folderService.getRoot());
        folder.setName("document-detail-view-test-" + UUID.randomUUID());
        sandbox = folderService.save(folder);
    }

    // as the administrator: a test may leave the employee only the view of a document in the sandbox
    @AfterEach
    void deleteSandbox() {
        // the statuses go even when the folder stays: a seed status keeps a target to a leftover one in the shared
        // database, and every later test would see it
        try {
            systemAuthenticator.runWithUser("admin", () -> folderService.delete(sandbox));
        } finally {
            statuses.forEach(this::deleteStatus);
        }
    }

    @Test
    void aNewDocumentNeedsAFile() {
        DocumentDetailView card = openNewCard();
        this.<TypedTextField<String>>component(card, "nameField").setValue("Contract");

        assertThat(UiTestUtils.validateView(card).isEmpty()).isFalse();
    }

    @Test
    void aNewDocumentOpenedByItsAddressGoesIntoTheRoot() {
        viewNavigators.detailView(UiTestUtils.getCurrentView(), Document.class).newEntity().navigate();

        DocumentDetailView card = UiTestUtils.getCurrentView();
        assertThat(card.getEditedEntity().getFolder()).isEqualTo(folderService.getRoot());
    }

    @Test
    void aNewDocumentTakesTheNameOfItsFile() {
        DocumentDetailView card = openNewCard();

        TestUploads.succeed(component(card, "newVersionUpload"), temporaryStorage, "scan.txt", "text");
        assertThat(this.<H3>component(card, "documentTitle").getText()).isEqualTo("scan.txt");
        this.<JmixButton>component(card, "saveButton").click();

        Document created = dataManager.load(Document.class)
                .query("select d from Document d where d.folder = :folder")
                .parameter("folder", sandbox)
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE).add("currentRevision", FetchPlan.BASE))
                .one();

        assertThat(created.getName()).isEqualTo("scan.txt");
        assertThat(created.getCurrentRevision().getNumber()).isEqualTo(1);
    }

    @Test
    void aStagedVersionBecomesARevisionOnOk() {
        Document document = upload("a.txt");
        DocumentDetailView card = openCard(document);

        TestUploads.succeed(component(card, "newVersionUpload"), temporaryStorage, "a-v2.txt", "second");
        assertThat(card.hasUnsavedChanges()).isTrue();
        this.<JmixButton>component(card, "saveButton").click();

        assertThat(reload(document).getCurrentRevision().getNumber()).isEqualTo(2);
    }

    @Test
    void closingWithoutSavingDiscardsTheStagedVersion() {
        Document document = upload("a.txt");
        DocumentDetailView card = openCard(document);
        TemporaryStorage.FileInfo staged = TestUploads.succeed(component(card, "newVersionUpload"),
                temporaryStorage, "a-v2.txt", "second");

        card.closeWithDiscard();

        assertThat(temporaryStorage.getFile(staged.getId())).isNull();
        assertThat(reload(document).getCurrentRevision().getNumber()).isEqualTo(1);
    }

    @Test
    void withoutTheKeyOfTheModelTheCardHasNoAiButtonAndNoAssistant() {
        DocumentDetailView card = openCard(upload("a.txt"));

        assertThat(this.<JmixButton>component(card, "fillWithAiButton").isVisible()).isFalse();
        assertThat(this.<Tab>component(card, "assistantTab").isVisible()).isFalse();
    }

    @Test
    void theTitleFollowsTheName() {
        DocumentDetailView card = openCard(upload("a.txt"));

        this.<TypedTextField<String>>component(card, "nameField").setValue("contract.txt");

        assertThat(this.<H3>component(card, "documentTitle").getText()).isEqualTo("contract.txt");
    }

    @Test
    void theFolderShowsThePathAsTheBreadcrumbsDoAsPlainText() {
        DocumentDetailView card = openCard(upload("a.txt"));

        TypedTextField<String> folder = component(card, "folderField");

        assertThat(folder.getValue())
                .isEqualTo(metadataTools.getInstanceName(folderService.getRoot()) + " / " + sandbox.getName());
        assertThat(folder.isReadOnly()).isTrue();
    }

    @Test
    void copyLinkPutsTheAddressOfTheCardIntoTheClipboardAndSaysSo() {
        Document document = upload("a.txt");
        DocumentDetailView card = openCard(document);
        TestPalettes.sentScripts();
        openedNotifications.closeOpenedNotifications();

        this.<JmixButton>component(card, "copyLinkButton").click();
        PendingJavaScriptInvocation copy = clipboardScript();
        String address = copiedText(copy);

        assertThat(URI.create(address).isAbsolute()).isTrue();
        assertThat(address).endsWith("/documents/" + document.getId());

        copy.complete(JsonNodeFactory.instance.nullNode());

        NotificationInfo notice = UiTestUtils.getLastOpenedNotification();
        assertThat(notice).isNotNull();
        assertThat(notice.getText()).isEqualTo(messages.getMessage(DocumentDetailView.class, "linkCopied"));
        assertThat(notice.getType()).isEqualTo(Notifications.Type.SUCCESS);
        assertThat(notice.getNotification().getPosition()).isEqualTo(Notification.Position.TOP_END);
    }

    // a browser may refuse the clipboard to a page; the notice then gives the address to copy by hand
    @Test
    void aLinkTheBrowserDidNotCopyIsShownToCopyByHand() {
        Document document = upload("a.txt");
        DocumentDetailView card = openCard(document);
        TestPalettes.sentScripts();

        this.<JmixButton>component(card, "copyLinkButton").click();
        PendingJavaScriptInvocation copy = clipboardScript();
        String address = copiedText(copy);

        copy.completeExceptionally(JsonNodeFactory.instance.stringNode("NotAllowedError"));

        NotificationInfo notice = UiTestUtils.getLastOpenedNotification();
        assertThat(notice).isNotNull();
        assertThat(notice.getText())
                .isEqualTo(messages.formatMessage(DocumentDetailView.class, "linkNotCopied", address));
        assertThat(notice.getType()).isEqualTo(Notifications.Type.WARNING);
    }

    @Test
    void aNewDocumentHasNoLinkToCopyYet() {
        assertThat(this.<JmixButton>component(openNewCard(), "copyLinkButton").isVisible()).isFalse();
    }

    @Test
    void aLinkToADocumentInTheTrashLeadsToTheRepositoryWithANotice() {
        Document document = upload("a.txt");
        documentService.delete(List.of(document));

        navigate("documents/" + document.getId());

        assertLedToTheRepositoryWithANotice();
    }

    @Test
    void aLinkThatNamesNoDocumentLeadsToTheRepositoryWithTheSameNotice() {
        navigate("documents/" + UUID.randomUUID());

        assertLedToTheRepositoryWithANotice();

        navigate("documents/no-document");

        assertLedToTheRepositoryWithANotice();
    }

    @Test
    void aLinkToAnAvailableDocumentOpensItsCard() {
        Document document = upload("a.txt");

        navigate("documents/" + document.getId());

        DocumentDetailView card = UiTestUtils.getCurrentView();
        assertThat(card.getEditedEntity()).isEqualTo(document);
    }

    @Test
    void aLockedStatusShowsALockInItsBadgeWithATooltip() {
        Document document = upload("a.txt");
        documentService.changeStatus(List.of(document), TestStatuses.active(dataManager));
        documentService.changeStatus(List.of(document), TestStatuses.archived(dataManager));

        DocumentDetailView card = openCard(reload(document));
        Badge badge = component(card, "statusBadge");

        assertThat(badge.getIcon()).isNotNull();
        assertThat(Tooltip.forComponent(badge).getText()).isEqualTo(lockedTooltip());
        assertThat(this.<JmixButton>component(card, "fillWithAiButton").getTooltip().getText())
                .isEqualTo(lockedTooltip());
    }

    // the flag locks a status, the name Archived does not
    @Test
    void anyLockedStatusShowsTheLockNotOnlyArchived() {
        DocumentStatus signed = TestStatuses.create(unconstrainedDataManager, "Signed", true, List.of());
        statuses.add(signed);
        // Active leads to the new status until the status is deleted, which takes it out of the targets
        DocumentStatus active = TestStatuses.active(dataManager);
        active.getTargets().add(signed);
        unconstrainedDataManager.save(active);
        Document document = upload("a.txt");
        documentService.changeStatus(List.of(document), active);
        documentService.changeStatus(List.of(document), signed);

        Badge badge = component(openCard(reload(document)), "statusBadge");

        assertThat(badge.getIcon()).isNotNull();
        assertThat(Tooltip.forComponent(badge).getText()).isEqualTo(lockedTooltip());
    }

    @Test
    void aStatusThatMayChangeShowsNoLock() {
        DocumentDetailView card = openCard(upload("a.txt"));
        Badge badge = component(card, "statusBadge");

        assertThat(badge.getIcon()).isNull();
        assertThat(Tooltip.forComponent(badge).getText()).isNullOrEmpty();
        assertThat(this.<JmixButton>component(card, "fillWithAiButton").getTooltip().getText()).isNullOrEmpty();
    }

    // the lock explains, it does not advise, so the person who may not change the status sees it too
    @Test
    void aViewerOfALockedDocumentSeesTheLock() {
        Document document = upload("a.txt");
        documentService.changeStatus(List.of(document), TestStatuses.active(dataManager));
        documentService.changeStatus(List.of(document), TestStatuses.archived(dataManager));

        systemAuthenticator.runWithUser("admin", () -> TestAccess.giveOwnAccess(dataManager, document,
                Grant.user(EmployeeUiTestAuthenticator.USERNAME, AccessLevel.VIEW)));

        DocumentDetailView card = openCard(reload(document));
        Badge badge = component(card, "statusBadge");

        assertThat(card.isReadOnly()).isTrue();
        assertThat(badge.getIcon()).isNotNull();
        assertThat(Tooltip.forComponent(badge).getText()).isEqualTo(lockedTooltip());
    }

    @Test
    void theLockTooltipIsInBothLanguages() {
        String english = messages.getMessage(DocumentDetailView.class, "lockedStatus.tooltip", Locale.ENGLISH);
        String russian = messages.getMessage(DocumentDetailView.class, "lockedStatus.tooltip", Locale.of("ru"));

        assertThat(english).isEqualTo("Documents in this status can't be changed");
        assertThat(russian).isNotBlank().doesNotContainPattern("[A-Za-z]");
    }

    @Test
    void okDeletesTheTemporaryFile() {
        DocumentDetailView card = openCard(upload("a.txt"));
        TemporaryStorage.FileInfo staged = TestUploads.succeed(component(card, "newVersionUpload"),
                temporaryStorage, "a-v2.txt", "second");

        this.<JmixButton>component(card, "saveButton").click();

        assertThat(temporaryStorage.getFile(staged.getId())).isNull();
    }

    @Test
    void aCardLeavingTheUiWithoutClosingDeletesTheTemporaryFile() {
        DocumentDetailView card = openCard(upload("a.txt"));
        TemporaryStorage.FileInfo staged = TestUploads.succeed(component(card, "newVersionUpload"),
                temporaryStorage, "a-v2.txt", "second");

        // a session timeout or a page reload detaches the card without closing it
        card.getElement().removeFromParent();

        assertThat(temporaryStorage.getFile(staged.getId())).isNull();
    }

    @Test
    void aBrokenUploadIsReported() {
        DocumentDetailView card = openCard(upload("a.txt"));

        TestUploads.fail(component(card, "newVersionUpload"), "a-v2.txt");

        assertThat(UiTestUtils.getOpenedNotifications())
                .extracting(NotificationInfo::getText)
                .anyMatch(text -> text.contains("a-v2.txt"));
    }

    @Test
    void aStatusChosenInTheFieldIsSavedOnOk() {
        Document document = upload("a.txt");
        DocumentDetailView card = openCard(document);
        JmixSelect<DocumentStatus> statusField = component(card, "statusField");
        DocumentStatus active = TestStatuses.active(dataManager);

        choose(statusField, active, TestStatuses.draft(dataManager));
        assertThat(card.hasUnsavedChanges()).isTrue();
        assertThat(this.<Span>component(card, "pendingChanges").getText()).isEqualTo("→ " + active.getName());

        this.<JmixButton>component(card, "saveButton").click();

        assertThat(reload(document).getStatus()).isEqualTo(active);
    }

    // a status chosen back to the current one is no change either
    @Test
    void aCardWithNothingStagedShowsNoPendingChanges() {
        DocumentDetailView card = openCard(upload("a.txt"));
        JmixSelect<DocumentStatus> statusField = component(card, "statusField");
        Span pendingChanges = component(card, "pendingChanges");
        DocumentStatus draft = TestStatuses.draft(dataManager);
        DocumentStatus active = TestStatuses.active(dataManager);

        assertThat(pendingChanges.getText()).isEmpty();

        choose(statusField, active, draft);
        choose(statusField, draft, active);

        assertThat(pendingChanges.getText()).isEmpty();
    }

    @Test
    void thePendingChangesNameTheUploadedFile() {
        DocumentDetailView card = openCard(upload("a.txt"));

        TestUploads.succeed(component(card, "newVersionUpload"), temporaryStorage, "a-v2.txt", "second");

        assertThat(this.<Span>component(card, "pendingChanges").getText())
                .isEqualTo(messages.formatMessage(DocumentCardChanges.class, "pendingNewVersion", "a-v2.txt"));
    }

    @Test
    void thePendingChangesNameTheNumberOfTheRestoredVersion() {
        Document document = upload("a.txt");
        DocumentRevision first = reload(document).getCurrentRevision();
        documentService.uploadRevision(document, TestFiles.file(tempDir, "a-v2.txt", "second"));
        DocumentDetailView card = openCard(reload(document));
        DataGrid<DocumentRevision> revisionsGrid = component(card, "revisionsGrid");

        revisionsGrid.select(first);
        perform(revisionsGrid, "restoreRevisionAction");

        assertThat(this.<Span>component(card, "pendingChanges").getText())
                .isEqualTo(messages.formatMessage(DocumentCardChanges.class, "pendingRestoredVersion", 1));
    }

    // the new version comes first, then the status
    @Test
    void thePendingChangesJoinTheVersionAndTheStatusUntilTheStatusIsChosenBack() {
        DocumentDetailView card = openCard(upload("a.txt"));
        JmixSelect<DocumentStatus> statusField = component(card, "statusField");
        Span pendingChanges = component(card, "pendingChanges");
        DocumentStatus draft = TestStatuses.draft(dataManager);
        DocumentStatus active = TestStatuses.active(dataManager);
        String newVersion = messages.formatMessage(DocumentCardChanges.class, "pendingNewVersion", "a-v2.txt");

        TestUploads.succeed(component(card, "newVersionUpload"), temporaryStorage, "a-v2.txt", "second");
        choose(statusField, active, draft);
        assertThat(pendingChanges.getText()).isEqualTo(newVersion + " · → " + active.getName());

        choose(statusField, draft, active);
        assertThat(pendingChanges.getText()).isEqualTo(newVersion);
    }

    @Test
    void theStatusFieldOffersTheCurrentStatusAndItsTargets() {
        Document document = upload("a.txt");
        documentService.changeStatus(List.of(document), TestStatuses.active(dataManager));
        DocumentDetailView card = openCard(reload(document));
        JmixSelect<DocumentStatus> statusField = component(card, "statusField");

        assertThat(statusField.getValue()).isEqualTo(TestStatuses.active(dataManager));
        assertThat(statusField.getListDataView().getItems())
                .containsExactly(TestStatuses.active(dataManager), TestStatuses.archived(dataManager));
    }

    // the current status keeps its own place among the others, not the first one
    @Test
    void theStatusFieldListsTheStatusesInTheirOrder() {
        Document document = upload("a.txt");
        documentService.changeStatus(List.of(document), TestStatuses.active(dataManager));
        documentService.changeStatus(List.of(document), TestStatuses.archived(dataManager));
        DocumentDetailView card = openCard(reload(document));
        JmixSelect<DocumentStatus> statusField = component(card, "statusField");

        assertThat(statusField.getValue()).isEqualTo(TestStatuses.archived(dataManager));
        assertThat(statusField.getListDataView().getItems())
                .containsExactly(TestStatuses.active(dataManager), TestStatuses.archived(dataManager));
    }

    @Test
    void theStatusFieldNamesTheStatusesAndDrawsTheirDots() {
        DocumentDetailView card = openCard(reload(upload("a.txt")));
        JmixSelect<DocumentStatus> statusField = component(card, "statusField");
        DocumentStatus draft = TestStatuses.draft(dataManager);

        assertThat(statusField.getItemLabelGenerator().apply(draft)).isEqualTo(draft.getName());
        assertThat(statusField.getItemRenderer().createComponent(draft).getElement().getClassList())
                .contains("status-option");
    }

    @Test
    void aNewCardShowsTheInitialStatus() {
        DocumentDetailView card = openNewCard();
        DocumentStatus draft = TestStatuses.draft(dataManager);

        assertThat(this.<Badge>component(card, "statusBadge").getText()).isEqualTo(draft.getName());
        assertThat(this.<JmixSelect<DocumentStatus>>component(card, "statusField").getValue()).isEqualTo(draft);
    }

    @Test
    void aDocumentInALockedStatusIsReadOnlyButItsStatusIsNot() {
        Document document = upload("a.txt");
        documentService.changeStatus(List.of(document), TestStatuses.active(dataManager));
        documentService.changeStatus(List.of(document), TestStatuses.archived(dataManager));

        DocumentDetailView card = openCard(reload(document));
        JmixSelect<DocumentStatus> statusField = component(card, "statusField");

        assertThat(this.<TypedTextField<String>>component(card, "nameField").isReadOnly()).isTrue();
        assertThat(this.<JmixUpload<?>>component(card, "newVersionUpload").isVisible()).isFalse();
        assertThat(statusField.isReadOnly()).isFalse();
        assertThat(statusField.getListDataView().getItems()).contains(TestStatuses.active(dataManager));
    }

    @Test
    void theStatusShowsAsABadgeInItsColor() {
        Document document = upload("a.txt");
        documentService.changeStatus(List.of(document), TestStatuses.active(dataManager));

        DocumentDetailView card = openCard(reload(document));

        Badge badge = component(card, "statusBadge");
        DocumentStatus active = TestStatuses.active(dataManager);
        assertThat(badge.getText()).isEqualTo(active.getName());
        assertThat(badge.getClassNames()).isEmpty();
        assertThat(badge.getStyle().get("--aura-accent-color-light")).isEqualTo(active.getColor());
        assertThat(badge.getStyle().get("--vaadin-badge-border-color")).isEqualTo(active.getColor());
    }

    @Test
    void theAiActionLeadsAndTheIconsCloseTheActions() {
        DocumentDetailView card = openCard(upload("a.txt"));

        assertThat(this.<Component>component(card, "headerActions").getChildren()
                .map(action -> action.getId().orElseThrow()))
                .containsExactly("fillWithAiButton", "newVersionUpload", "downloadButton", "copyLinkButton",
                        "accessButton", "favoriteButton");
    }

    @Test
    void theIconsOfDownloadCopyLinkAndAccessKeepTheirNamesInTooltips() {
        DocumentDetailView card = openCard(upload("a.txt"));

        assertThat(List.of("downloadButton", "copyLinkButton", "accessButton")).allSatisfy(id -> {
            JmixButton button = component(card, id);
            String name = messages.getMessage(DocumentDetailView.class, id + ".text");

            assertThat(button.getText()).isEmpty();
            assertThat(button.getTooltip().getText()).isEqualTo(name);
            assertThat(button.getAriaLabel()).hasValue(name);
        });
    }

    @Test
    void theCardOpensLargeEnoughForThePreviewAndCanBeMaximized() {
        DialogWindow<DocumentDetailView> window = dialogWindows.detail(UiTestUtils.getCurrentView(), Document.class)
                .withViewClass(DocumentDetailView.class)
                .editEntity(upload("a.txt"))
                .build();

        assertThat(window.isMaximizable()).isTrue();
        assertThat(window.getWidth()).isEqualTo("88em");
        assertThat(window.getHeight()).isEqualTo("58em");
    }

    @Test
    void fillingWithAiWearsThePurpleOfTheAiSearch() {
        DocumentDetailView card = openCard(upload("a.txt"));

        assertThat(this.<JmixButton>component(card, "fillWithAiButton").getClassNames())
                .contains("aura-accent-purple", "ai-button");
    }

    @Test
    void restoringAnOldVersionAddsARevisionOnOk() {
        Document document = upload("a.txt");
        DocumentRevision first = reload(document).getCurrentRevision();
        documentService.uploadRevision(document, TestFiles.file(tempDir, "a-v2.txt", "second"));
        DocumentDetailView card = openCard(reload(document));
        DataGrid<DocumentRevision> revisionsGrid = component(card, "revisionsGrid");

        revisionsGrid.select(first);
        perform(revisionsGrid, "restoreRevisionAction");
        this.<JmixButton>component(card, "saveButton").click();

        DocumentRevision current = reload(document).getCurrentRevision();
        assertThat(current.getNumber()).isEqualTo(3);
        assertThat(current.getFile()).isEqualTo(first.getFile());
    }

    @Test
    void theVersionsNameTheTypesOfTheirFilesAsPeopleKnowThem() {
        Document document = upload("a.txt");
        documentService.uploadRevision(document, TestFiles.file(tempDir, "b.pdf", TestSamples.pdf("second")));
        DataGrid<DocumentRevision> revisionsGrid = component(openCard(reload(document)), "revisionsGrid");

        assertThat(versions(revisionsGrid).stream()
                .collect(Collectors.toMap(DocumentRevision::getNumber,
                        revision -> TestGrids.text(revisionsGrid, "contentType", revision))))
                .containsExactlyInAnyOrderEntriesOf(Map.of(1, "TXT", 2, "PDF"));
    }

    // the column shows PDF and DOCX, while the media types order them the other way round
    @Test
    void theTypeColumnSortsByTheNameItShows() {
        DocumentDetailView card = openCard(upload("a.txt"));
        DataGrid<DocumentRevision> revisionsGrid = component(card, "revisionsGrid");
        DocumentRevision pdf = revisionOf("application/pdf");
        DocumentRevision docx = revisionOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        Comparator<DocumentRevision> byType = Objects.requireNonNull(revisionsGrid.getColumnByKey("contentType"))
                .getComparator(SortDirection.ASCENDING);

        assertThat(byType.compare(docx, pdf)).isNegative();
        assertThat(byType.compare(pdf, docx)).isPositive();
    }

    @Test
    void onlyTheCurrentVersionIsMarkedAsCurrent() {
        Document document = upload("a.txt");
        documentService.uploadRevision(document, TestFiles.file(tempDir, "a-v2.txt", "second"));
        DataGrid<DocumentRevision> revisionsGrid = component(openCard(reload(document)), "revisionsGrid");
        String current = messages.getMessage(DocumentDetailView.class, "currentRevision.text");

        Map<Integer, Component> cells = versions(revisionsGrid).stream()
                .collect(Collectors.toMap(DocumentRevision::getNumber,
                        revision -> TestGrids.render(revisionsGrid, "fileName", revision)));

        assertThat(badges(cells.get(1))).isEmpty();
        assertThat(badges(cells.get(2))).containsExactly(current);
        assertThat(cells.get(2).getChildren()
                .filter(Badge.class::isInstance)
                .allMatch(badge -> badge.getElement().getThemeList().contains(BadgeVariant.SUCCESS.getVariantName())))
                .isTrue();
        assertThat(cells.get(2).getChildren()
                .noneMatch(child -> child.getElement().getClassList().contains("v-success"))).isTrue();
        assertThat(cells.get(1).getElement().getTextRecursively()).isEqualTo("a.txt");
        assertThat(cells.get(2).getElement().getTextRecursively()).startsWith("a-v2.txt");
    }

    @Test
    void everyVersionColumnCanBeSorted() {
        DocumentDetailView card = openCard(upload("a.txt"));
        DataGrid<DocumentRevision> revisionsGrid = component(card, "revisionsGrid");

        assertThat(revisionsGrid.getColumns())
                .allSatisfy(column -> assertThat(column.isSortable()).as(column.getKey()).isTrue());
    }

    @Test
    void anUploadedFileShowsInThePreviewBeforeOk() {
        DocumentDetailView card = openCard(upload("a.txt", "first"));

        TestUploads.succeed(component(card, "newVersionUpload"), temporaryStorage, "a-v2.txt", "second");

        assertThat(previewText(component(card, "preview"))).isEqualTo("second");
    }

    @Test
    void aRestoredVersionShowsInThePreviewBeforeOk() {
        Document document = upload("a.txt", "first");
        DocumentRevision first = reload(document).getCurrentRevision();
        documentService.uploadRevision(document, TestFiles.file(tempDir, "a-v2.txt", "second"));
        DocumentDetailView card = openCard(reload(document));
        DataGrid<DocumentRevision> revisionsGrid = component(card, "revisionsGrid");

        revisionsGrid.select(first);
        perform(revisionsGrid, "restoreRevisionAction");

        assertThat(previewText(component(card, "preview"))).isEqualTo("first");
    }

    @Test
    void showingAVersionOpensItInADialogAndKeepsTheCardPreview() {
        Document document = upload("a.txt", "first");
        DocumentRevision first = reload(document).getCurrentRevision();
        documentService.uploadRevision(document, TestFiles.file(tempDir, "a-v2.txt", "second"));
        DocumentDetailView card = openCard(reload(document));
        DataGrid<DocumentRevision> revisionsGrid = component(card, "revisionsGrid");

        revisionsGrid.select(first);
        perform(revisionsGrid, "showRevisionAction");

        assertThat(previewText(lastDialogPreview())).isEqualTo("first");
        assertThat(previewText(component(card, "preview"))).isEqualTo("second");
    }

    @Test
    void theDialogOfAVersionIsNamedAfterIt() {
        Document document = reload(upload("a.txt"));
        DocumentDetailView card = openCard(document);

        TestGrids.doubleClickRow(component(card, "revisionsGrid"), document.getCurrentRevision());

        RevisionReadView dialog = UiTestUtils.getLastOpenedViewDialog();
        assertThat(dialog).isNotNull();
        assertThat(dialog.getPageTitle())
                .isEqualTo(messages.formatMessage(RevisionReadView.class, "revisionReadView.title", 1, "a.txt"));
    }

    @Test
    void theDialogOfAVersionOpensLargeEnoughAndCanBeMaximized() {
        DialogWindow<RevisionReadView> window = dialogWindows.view(UiTestUtils.getCurrentView(), RevisionReadView.class)
                .build();

        assertThat(window.isMaximizable()).isTrue();
        assertThat(window.isResizable()).isTrue();
        assertThat(window.getWidth()).isEqualTo("56em");
        assertThat(window.getHeight()).isEqualTo("40em");
    }

    @Test
    void aDoubleClickOnAVersionOpensItInADialog() {
        Document document = upload("a.txt", "first");
        DocumentRevision first = reload(document).getCurrentRevision();
        documentService.uploadRevision(document, TestFiles.file(tempDir, "a-v2.txt", "second"));
        DocumentDetailView card = openCard(reload(document));

        TestGrids.doubleClickRow(component(card, "revisionsGrid"), first);

        assertThat(previewText(lastDialogPreview())).isEqualTo("first");
    }

    @Test
    void aNewDocumentAsksForAFileAndAnExistingOneForANewVersion() {
        Button newCardUpload = uploadButton(openNewCard());
        Button existingCardUpload = uploadButton(openCard(upload("a.txt")));

        assertThat(newCardUpload.getText())
                .isEqualTo(messages.getMessage("com.company.docai.view.document/uploadFile"));
        assertThat(existingCardUpload.getText())
                .isEqualTo(messages.getMessage("com.company.docai.view.document/newVersionUpload.uploadText"));
    }

    @Test
    void aLongTextShowsItsBeginningWithANote() {
        DocumentDetailView card = openCard(upload("long.txt", "a".repeat(300 * 1024)));

        DocumentPreviewFragment preview = component(card, "preview");
        Pre text = (Pre) FragmentUtils.getComponent(preview, "previewText");
        Span note = (Span) FragmentUtils.getComponent(preview, "truncatedNote");
        Component emptyPreview = FragmentUtils.getComponent(preview, "previewEmpty");
        assertThat(text.getText()).hasSize(256 * 1024);
        assertThat(note.isVisible()).isTrue();
        assertThat(emptyPreview.isVisible()).isFalse();
    }

    @Test
    void theCardShowsWhoChangedTheDocumentItselfAndWhenInThePersonsTimeZone() {
        Document document = unconstrainedDataManager.load(Id.of(upload("a.txt"))).one();
        document.setChangedBy("maria");
        document.setChangedDate(OffsetDateTime.of(2020, 1, 15, 12, 0, 0, 0, ZoneOffset.UTC));
        unconstrainedDataManager.save(document);

        // in Kiritimati noon in UTC is already the next day; the user of the test's login is loaded for this test alone
        ((User) currentAuthentication.getUser()).setTimeZoneId("Pacific/Kiritimati");
        assertThat(currentAuthentication.getTimeZone().getID()).isEqualTo("Pacific/Kiritimati");

        DocumentDetailView card = openCard(reload(document));

        assertThat(this.<TypedTextField<String>>component(card, "modifiedField").getValue())
                .isEqualTo(nameOf("maria") + ", "
                        + datatypeFormatter.formatLocalDateTime(LocalDateTime.of(2020, 1, 16, 2, 0)));
    }

    @Test
    void theVersionFieldShowsTheNumberTheFileAndTheSizeOfTheCurrentVersion() {
        Document document = upload("a.txt");
        DocumentDetailView card = openCard(document);

        assertThat(this.<TypedTextField<String>>component(card, "currentVersionField").getValue())
                .isEqualTo("1 · a.txt · "
                        + fileSizeFormatter.format(reload(document).getCurrentRevision().getFileSize()));
    }

    @Test
    void theVersionsNameWhoUploadedThem() {
        DocumentDetailView card = openCard(upload("a.txt"));
        DataGrid<DocumentRevision> revisionsGrid = component(card, "revisionsGrid");
        DataGridItems<DocumentRevision> versions = revisionsGrid.getItems();

        assertThat(versions).isNotNull();
        assertThat(TestGrids.text(revisionsGrid, "createdBy", versions.getItems().iterator().next()))
                .isEqualTo(nameOf(EmployeeUiTestAuthenticator.USERNAME));
    }

    @Test
    void theVersionsShowWhenTheyWereUploadedInThePersonsTimeZone() {
        // Kiritimati is far from the zone of the server; the user of the test's login is loaded for this test alone
        ((User) currentAuthentication.getUser()).setTimeZoneId("Pacific/Kiritimati");
        assertThat(currentAuthentication.getTimeZone().getID()).isEqualTo("Pacific/Kiritimati");

        DocumentDetailView card = openCard(upload("a.txt"));
        DataGrid<DocumentRevision> revisionsGrid = component(card, "revisionsGrid");
        DataGridItems<DocumentRevision> versions = revisionsGrid.getItems();

        assertThat(versions).isNotNull();
        DocumentRevision revision = versions.getItems().iterator().next();

        assertThat(revision.getCreatedDate()).isNotNull();

        assertThat(TestGrids.text(revisionsGrid, "createdDate", revision))
                .isEqualTo(documentRenderers.userDateTime(revision.getCreatedDate()));
    }

    @Test
    void theTagsShowInTheFieldAndNextToTheStatus() {
        Tag lease = TestTags.tag(unconstrainedDataManager, "Lease " + suffix);
        Tag office = TestTags.tag(unconstrainedDataManager, "Office " + suffix);

        DocumentDetailView card = openCard(TestTags.tagged(unconstrainedDataManager, upload("a.txt"), office, lease));

        assertThat(entityStates.isLoaded(card.getEditedEntity(), "tags")).isTrue();
        assertThat(this.<JmixMultiSelectComboBox<Tag>>component(card, "tagsField").getValue())
                .containsExactlyInAnyOrder(lease, office);
        assertThat(TestTags.names(component(card, "documentTags")))
                .containsExactly("Lease " + suffix, "Office " + suffix);
    }

    @Test
    void aTypedNameTakesItsTagWhateverTheCaseOrMakesANewOneThatOkSaves() {
        String lease = "Lease " + suffix;
        String office = "Office " + suffix;
        TestTags.tag(unconstrainedDataManager, lease);
        Document document = upload("a.txt");
        DocumentDetailView card = openCard(document);
        JmixMultiSelectComboBox<Tag> tagsField = component(card, "tagsField");

        type(tagsField, " " + lease.toLowerCase() + " ");
        type(tagsField, office);
        type(tagsField, office.toUpperCase());

        assertThat(tagsField.getValue()).extracting(Tag::getName).containsExactlyInAnyOrder(lease, office);
        assertThat(TestTags.names(component(card, "documentTags"))).containsExactly(lease, office);
        assertThat(card.hasUnsavedChanges()).isTrue();

        this.<JmixButton>component(card, "saveButton").click();

        assertThat(unconstrainedDataManager.load(Document.class)
                .id(document.getId())
                .fetchPlan(fetchPlan -> fetchPlan.add("tags", FetchPlan.BASE))
                .one()
                .getTags()).extracting(Tag::getName).containsExactly(lease, office);
    }

    @Test
    void theBadgesComeInAlphabeticalOrderWhateverTheCase() {
        DocumentDetailView card = openCard(upload("a.txt"));
        JmixMultiSelectComboBox<Tag> tagsField = component(card, "tagsField");

        type(tagsField, "Office " + suffix);
        type(tagsField, "lease " + suffix);

        assertThat(TestTags.names(component(card, "documentTags")))
                .containsExactly("lease " + suffix, "Office " + suffix);
    }

    @Test
    void aTypedNameOfSpacesAddsNothing() {
        DocumentDetailView card = openCard(upload("a.txt"));
        JmixMultiSelectComboBox<Tag> tagsField = component(card, "tagsField");

        type(tagsField, "   ");

        assertThat(tagsField.getValue()).isEmpty();
        assertThat(card.hasUnsavedChanges()).isFalse();
    }

    @Test
    void theTagsOfAnArchivedDocumentStayOpenForChange() {
        Document document = upload("a.txt");
        documentService.changeStatus(List.of(document), TestStatuses.active(dataManager));
        documentService.changeStatus(List.of(document), TestStatuses.archived(dataManager));

        DocumentDetailView card = openCard(reload(document));

        assertThat(this.<TypedTextField<String>>component(card, "nameField").isReadOnly()).isTrue();
        assertThat(this.<JmixMultiSelectComboBox<Tag>>component(card, "tagsField").isReadOnly()).isFalse();
    }

    @Test
    void aClickOnATagClosesTheCardAndOpensThePageOfTheTag() {
        String lease = "Lease " + suffix;
        Document document = TestTags.tagged(unconstrainedDataManager, upload("a.txt"),
                TestTags.tag(unconstrainedDataManager, lease));
        DocumentDetailView card = openCard(document);

        TestTags.click(component(card, "documentTags"), lease);

        assertThat((View<?>) UiTestUtils.getCurrentView()).isInstanceOf(DocumentSearchView.class);
        assertThat(TestTags.tagInAddress()).hasValue(lease);
    }

    // a card opened by its address is a page of its own, and the page of the tag takes its place
    @Test
    void aClickOnATagInACardOpenedByItsAddressOpensThePageOfTheTag() {
        String lease = "Lease " + suffix;
        Document document = TestTags.tagged(unconstrainedDataManager, upload("a.txt"),
                TestTags.tag(unconstrainedDataManager, lease));
        viewNavigators.detailView(UiTestUtils.getCurrentView(), Document.class)
                .editEntity(document)
                .navigate();
        DocumentDetailView card = UiTestUtils.getCurrentView();

        TestTags.click(component(card, "documentTags"), lease);

        assertThat((View<?>) UiTestUtils.getCurrentView()).isInstanceOf(DocumentSearchView.class);
        assertThat(TestTags.tagInAddress()).hasValue(lease);
    }

    @Test
    void aClickOnATagAsksAboutTheUnsavedChangesFirst() {
        String lease = "Lease " + suffix;
        Document document = TestTags.tagged(unconstrainedDataManager, upload("a.txt"),
                TestTags.tag(unconstrainedDataManager, lease));
        DocumentDetailView card = openCard(document);
        this.<TypedTextField<String>>component(card, "nameField").setValue("b.txt");

        TestTags.click(component(card, "documentTags"), lease);

        DialogInfo dialog = UiTestUtils.getLastOpenedDialog();
        assertThat(dialog).isNotNull();
        assertThat(dialog.getDialog().getHeaderTitle()).isEqualTo(messages.getMessage("dialogs.closeUnsaved.title"));
        assertThat((View<?>) UiTestUtils.getCurrentView()).isNotInstanceOf(DocumentSearchView.class);
    }

    // the browser sends the server an Esc only for a dialog that closes on Esc, and the card then closes as Cancel
    // closes it
    @Test
    void anEscOnACardWithAStagedChangeAsksAboutTheUnsavedChanges() {
        DocumentDetailView card = openCard(upload("a.txt"));
        choose(component(card, "statusField"), TestStatuses.active(dataManager), TestStatuses.draft(dataManager));
        Dialog dialog = card.findAncestor(Dialog.class);

        ComponentUtil.fireEvent(dialog, new Dialog.DialogCloseActionEvent(dialog, true));

        DialogInfo question = UiTestUtils.getLastOpenedDialog();
        assertThat(dialog.isOpened()).isTrue();
        assertThat(question).isNotNull();
        assertThat(question.getDialog().getHeaderTitle()).isEqualTo(messages.getMessage("dialogs.closeUnsaved.title"));
    }

    private static List<DocumentRevision> versions(DataGrid<DocumentRevision> revisionsGrid) {
        DataGridItems<DocumentRevision> items = revisionsGrid.getItems();
        assertThat(items).isNotNull();

        return List.copyOf(items.getItems());
    }

    private static List<String> badges(Component cell) {
        return cell.getChildren()
                .filter(Badge.class::isInstance)
                .map(badge -> ((Badge) badge).getText())
                .toList();
    }

    private void navigate(String address) {
        openedNotifications.closeOpenedNotifications();
        UI.getCurrent().navigate(address);
    }

    private void assertLedToTheRepositoryWithANotice() {
        NotificationInfo notice = UiTestUtils.getLastOpenedNotification();

        assertThat(UiTestUtils.<View<?>>getCurrentView()).isInstanceOf(RepositoryView.class);
        assertThat(UI.getCurrent().getInternals().getActiveViewLocation().getPath()).isEqualTo("repository");
        assertThat(notice).isNotNull();
        assertThat(notice.getText()).isEqualTo(messages.getMessage(DocumentDetailView.class, "documentUnavailable"));
        assertThat(notice.getType()).isEqualTo(Notifications.Type.WARNING);
    }

    // the script of UiComponentUtils.copyToClipboard, which the browser gets with the next response
    private static PendingJavaScriptInvocation clipboardScript() {
        return TestPalettes.sentScripts()
                .stream()
                .filter(script -> script.getInvocation()
                        .getExpression()
                        .contains("navigator.clipboard.writeText"))
                .findFirst()
                .orElseThrow();
    }

    private static String copiedText(PendingJavaScriptInvocation copy) {
        return copy.getInvocation()
                .getParameters()
                .stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private String lockedTooltip() {
        return messages.getMessage(DocumentDetailView.class, "lockedStatus.tooltip");
    }

    private DocumentRevision revisionOf(String contentType) {
        DocumentRevision revision = dataManager.create(DocumentRevision.class);
        revision.setContentType(contentType);
        return revision;
    }

    // the documents of a status made by a test, those in the trash included, go back to Draft so that it can go
    private void deleteStatus(DocumentStatus status) {
        systemAuthenticator.runWithUser("admin", () -> {
            DocumentStatus draft = TestStatuses.draft(dataManager);
            List<Document> documents = unconstrainedDataManager.load(Document.class)
                    .query("select d from Document d where d.status = :status")
                    .parameter("status", status)
                    .hint(PersistenceHints.SOFT_DELETION, false)
                    .list();

            documents.forEach(document -> document.setStatus(draft));

            unconstrainedDataManager.save(new SaveContext()
                    .saving(documents)
                    .setHint(PersistenceHints.SOFT_DELETION, false));

            statusService.delete(status);
        });
    }

    private static void type(JmixMultiSelectComboBox<Tag> field, String text) {
        ComponentUtil.fireEvent(field, new ComboBoxBase.CustomValueSetEvent<>(field, true, text));
    }

    private static void perform(DataGrid<DocumentRevision> revisionsGrid, String actionId) {
        Action action = revisionsGrid.getAction(actionId);
        assertThat(action).as(actionId).isNotNull();
        action.actionPerform(revisionsGrid);
    }

    private static void choose(JmixSelect<DocumentStatus> field, DocumentStatus status, DocumentStatus previous) {
        field.setValue(status);
        ComponentUtil.fireEvent(field, new AbstractField.ComponentValueChangeEvent<>(field, field, previous, true));
    }

    private DocumentDetailView openNewCard() {
        DialogWindow<DocumentDetailView> window = dialogWindows.detail(UiTestUtils.getCurrentView(), Document.class)
                .withViewClass(DocumentDetailView.class)
                .newEntity()
                .withInitializer(document -> document.setFolder(sandbox))
                .build();

        window.open();
        return window.getView();
    }

    private DocumentDetailView openCard(Document document) {
        DialogWindow<DocumentDetailView> window = dialogWindows.detail(UiTestUtils.getCurrentView(), Document.class)
                .withViewClass(DocumentDetailView.class)
                .editEntity(document)
                .build();

        window.open();
        return window.getView();
    }

    private <T> T component(DocumentDetailView card, String id) {
        return UiTestUtils.getComponent(card, id);
    }

    private Button uploadButton(DocumentDetailView card) {
        return (Button) this.<JmixUpload<?>>component(card, "newVersionUpload").getUploadButton();
    }

    private String previewText(DocumentPreviewFragment preview) {
        return ((Pre) FragmentUtils.getComponent(preview, "previewText")).getText();
    }

    private DocumentPreviewFragment lastDialogPreview() {
        RevisionReadView dialog = UiTestUtils.getLastOpenedViewDialog();

        assertThat(dialog).isNotNull();
        return UiTestUtils.getComponent(dialog, "preview");
    }

    private String nameOf(String username) {
        return metadataTools.getInstanceName(dataManager.load(User.class)
                .query("e.username = :username")
                .parameter("username", username)
                .one());
    }

    private Document upload(String fileName) {
        return upload(fileName, "text");
    }

    private Document upload(String fileName, String content) {
        return documentService.upload(sandbox, List.of(TestFiles.file(tempDir, fileName, content)))
                .created().getFirst();
    }

    private Document reload(Document document) {
        return dataManager.load(Document.class)
                .id(document.getId())
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE).add("currentRevision", FetchPlan.BASE))
                .one();
    }
}
