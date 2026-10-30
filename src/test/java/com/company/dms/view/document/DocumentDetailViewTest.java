package com.company.dms.view.document;

import com.company.dms.DMSApplication;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.document.Document;
import com.company.dms.model.document.DocumentRevision;
import com.company.dms.model.document.DocumentStatus;
import com.company.dms.model.folder.Folder;
import com.company.dms.model.tag.Tag;
import com.company.dms.model.user.User;
import com.company.dms.test_support.EmployeeUiTestAuthenticator;
import com.company.dms.test_support.TestFiles;
import com.company.dms.test_support.TestGrids;
import com.company.dms.test_support.TestTags;
import com.company.dms.test_support.TestUploads;
import com.company.dms.view.main.MainView;
import com.company.dms.view.search.DocumentSearchView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBoxBase;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.tabs.Tab;
import io.jmix.core.DataManager;
import io.jmix.core.EntityStates;
import io.jmix.core.FetchPlan;
import io.jmix.core.Id;
import io.jmix.core.Messages;
import io.jmix.core.MetadataTools;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.metamodel.datatype.DatatypeFormatter;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.multiselectcombobox.JmixMultiSelectComboBox;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.component.upload.JmixUpload;
import io.jmix.flowui.data.grid.DataGridItems;
import io.jmix.flowui.fragment.FragmentUtils;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.testassist.dialog.DialogInfo;
import io.jmix.flowui.testassist.notification.NotificationInfo;
import io.jmix.flowui.upload.TemporaryStorage;
import io.jmix.flowui.view.DialogWindow;
import io.jmix.flowui.view.View;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DMSApplication.class, FlowuiTestAssistConfiguration.class})
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

    @TempDir
    private Path tempDir;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(folderService.getRoot());
        folder.setName("document-detail-view-test-" + UUID.randomUUID());
        sandbox = folderService.save(folder);
    }

    @AfterEach
    void deleteSandbox() {
        folderService.delete(sandbox);
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
    void withoutTheKeyOfTheModelTheCardHasNoAiButton() {
        DocumentDetailView card = openCard(upload("a.txt"));

        assertThat(this.<JmixButton>component(card, "fillWithAiButton").isVisible()).isFalse();
    }

    @Test
    void withoutTheKeyOfTheModelTheCardHasNoAssistant() {
        DocumentDetailView card = openCard(upload("a.txt"));

        assertThat(this.<Tab>component(card, "assistantTab").isVisible()).isFalse();
    }

    @Test
    void theTitleFollowsTheName() {
        DocumentDetailView card = openCard(upload("a.txt"));

        this.<TypedTextField<String>>component(card, "nameField").setValue("contract.txt");

        assertThat(this.<H3>component(card, "documentTitle").getText()).isEqualTo("contract.txt");
    }

    @Test
    void theFolderFieldShowsThePathAsTheBreadcrumbsDo() {
        DocumentDetailView card = openCard(upload("a.txt"));

        assertThat(this.<TypedTextField<String>>component(card, "folderField").getValue())
                .isEqualTo(metadataTools.getInstanceName(folderService.getRoot()) + " / " + sandbox.getName());
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
    void aStagedStatusChangeIsSavedOnOk() {
        Document document = upload("a.txt");
        DocumentDetailView card = openCard(document);

        this.<JmixButton>component(card, "activateButton").click();
        assertThat(card.hasUnsavedChanges()).isTrue();
        assertThat(this.<Span>component(card, "pendingChanges").getText())
                .isEqualTo("→ " + messages.getMessage(DocumentStatus.ACTIVE));

        assertThat(this.<JmixButton>component(card, "activateButton").getThemeNames())
                .contains(ButtonVariant.PRIMARY.getVariantName());

        this.<JmixButton>component(card, "saveButton").click();

        assertThat(reload(document).getStatus()).isEqualTo(DocumentStatus.ACTIVE);
    }

    @Test
    void anArchivedDocumentIsReadOnly() {
        Document document = upload("a.txt");
        documentService.changeStatus(List.of(document), DocumentStatus.ACTIVE);
        documentService.changeStatus(List.of(document), DocumentStatus.ARCHIVED);

        DocumentDetailView card = openCard(reload(document));

        assertThat(this.<TypedTextField<String>>component(card, "nameField").isReadOnly()).isTrue();
        assertThat(this.<JmixUpload<?>>component(card, "newVersionUpload").isVisible()).isFalse();
        assertThat(this.<JmixButton>component(card, "unarchiveButton").isVisible()).isTrue();
        assertThat(this.<JmixButton>component(card, "archiveButton").isVisible()).isFalse();
    }

    @Test
    void theStatusShowsAsABadgeInItsColor() {
        Document document = upload("a.txt");
        documentService.changeStatus(List.of(document), DocumentStatus.ACTIVE);

        DocumentDetailView card = openCard(reload(document));

        Badge badge = component(card, "statusBadge");
        assertThat(badge.getText()).isEqualTo(messages.getMessage(DocumentStatus.ACTIVE));
        assertThat(badge.getClassNames()).containsExactly("v-success");
    }

    @Test
    void theStatusLeadsTheActionsAndTheIconsCloseThem() {
        DocumentDetailView card = openCard(upload("a.txt"));

        assertThat(this.<Component>component(card, "headerActions").getChildren()
                .map(action -> action.getId().orElseThrow()))
                .containsExactly("activateButton", "archiveButton", "unarchiveButton", "fillWithAiButton",
                        "newVersionUpload", "downloadButton", "accessButton", "favoriteButton");
    }

    @Test
    void theIconsOfDownloadAndAccessKeepTheirNamesInTooltips() {
        DocumentDetailView card = openCard(upload("a.txt"));

        assertThat(List.of("downloadButton", "accessButton")).allSatisfy(id -> {
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
        revisionsGrid.getAction("restoreRevisionAction").actionPerform(revisionsGrid);
        this.<JmixButton>component(card, "saveButton").click();

        DocumentRevision current = reload(document).getCurrentRevision();
        assertThat(current.getNumber()).isEqualTo(3);
        assertThat(current.getFile()).isEqualTo(first.getFile());
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
        revisionsGrid.getAction("restoreRevisionAction").actionPerform(revisionsGrid);

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
        revisionsGrid.getAction("showRevisionAction").actionPerform(revisionsGrid);

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

        assertThat(newCardUpload.getText()).isEqualTo(messages.getMessage("com.company.dms.view.document/uploadFile"));
        assertThat(existingCardUpload.getText())
                .isEqualTo(messages.getMessage("com.company.dms.view.document/newVersionUpload.uploadText"));
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

        // the person works in Kiritimati, where noon in UTC is already the next day; the user of the test's login
        // is loaded for this test alone
        ((User) currentAuthentication.getUser()).setTimeZoneId("Pacific/Kiritimati");
        assertThat(currentAuthentication.getTimeZone().getID()).isEqualTo("Pacific/Kiritimati");

        DocumentDetailView card = openCard(reload(document));

        assertThat(this.<TypedTextField<String>>component(card, "modifiedField").getValue())
                .isEqualTo(nameOf("maria") + ", "
                        + datatypeFormatter.formatLocalDateTime(LocalDateTime.of(2020, 1, 16, 2, 0)));
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
        documentService.changeStatus(List.of(document), DocumentStatus.ACTIVE);
        documentService.changeStatus(List.of(document), DocumentStatus.ARCHIVED);

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

    // what the field fires when a name is typed and Enter pressed
    private static void type(JmixMultiSelectComboBox<Tag> field, String text) {
        ComponentUtil.fireEvent(field, new ComboBoxBase.CustomValueSetEvent<>(field, true, text));
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
