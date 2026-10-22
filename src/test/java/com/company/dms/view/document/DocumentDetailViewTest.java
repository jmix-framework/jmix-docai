package com.company.dms.view.document;

import com.company.dms.DMSApplication;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.document.Document;
import com.company.dms.model.document.DocumentRevision;
import com.company.dms.model.document.DocumentStatus;
import com.company.dms.model.folder.Folder;
import com.company.dms.test_support.EmployeeUiTestAuthenticator;
import com.company.dms.test_support.TestFiles;
import com.company.dms.test_support.TestUploads;
import com.company.dms.view.main.MainView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.html.Span;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Messages;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.component.upload.JmixUpload;
import io.jmix.flowui.fragment.FragmentUtils;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.upload.TemporaryStorage;
import io.jmix.flowui.view.DialogWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
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
    private TemporaryStorage temporaryStorage;

    @Autowired
    private Messages messages;

    @TempDir
    private Path tempDir;

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
    void theTitleFollowsTheName() {
        DocumentDetailView card = openCard(upload("a.txt"));

        this.<TypedTextField<String>>component(card, "nameField").setValue("contract.txt");

        assertThat(this.<H3>component(card, "documentTitle").getText()).isEqualTo("contract.txt");
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
