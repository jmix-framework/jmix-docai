package com.company.dms.view.document;

import com.company.dms.DMSApplication;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.access.AccessEntryDraft;
import com.company.dms.model.access.AccessLevel;
import com.company.dms.model.document.Document;
import com.company.dms.model.document.DocumentStatus;
import com.company.dms.model.folder.Folder;
import com.company.dms.test_support.EmployeeUiTestAuthenticator;
import com.company.dms.test_support.TestAccess.Grant;
import com.company.dms.test_support.TestAccess;
import com.company.dms.test_support.TestFiles;
import com.company.dms.test_support.TestUploads;
import com.company.dms.view.access.AccessDetailView;
import com.company.dms.view.main.MainView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.HasValueAndElement;
import com.vaadin.flow.component.button.Button;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.dynattr.DynAttrQueryHints;
import io.jmix.dynattr.model.Category;
import io.jmix.dynattrflowui.panel.DynamicAttributesPanel;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.component.UiComponentUtils;
import io.jmix.flowui.component.combobox.EntityComboBox;
import io.jmix.flowui.component.tabsheet.JmixTabSheet;
import io.jmix.flowui.data.EntityValueSource;
import io.jmix.flowui.data.SupportsValueSource;
import io.jmix.flowui.fragment.FragmentUtils;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.testassist.dialog.DialogInfo;
import io.jmix.flowui.upload.TemporaryStorage;
import io.jmix.flowui.view.DialogWindow;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "Properties" tab of the document card: the type and the fields of the type, edited by an employee.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DMSApplication.class, FlowuiTestAssistConfiguration.class})
class DocumentPropertiesTest {

    private static final String ADMIN = "admin";
    private static final String EMPLOYEE = "peter";

    @Autowired
    private DialogWindows dialogWindows;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private TemporaryStorage temporaryStorage;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        sandbox = systemAuthenticator.withUser(ADMIN, () -> {
            Folder folder = dataManager.create(Folder.class);
            folder.setParent(folderService.getRoot());
            folder.setName("document-properties-test-" + UUID.randomUUID());
            return folderService.save(folder);
        });
    }

    @AfterEach
    void deleteSandbox() {
        systemAuthenticator.runWithUser(ADMIN, () -> folderService.delete(sandbox));
    }

    @Test
    void thePropertiesTabSitsBetweenTheGeneralTabAndTheVersions() {
        DocumentDetailView card = openCard(upload());
        JmixTabSheet tabs = component(card, "cardTabs");

        assertThat(tabs.getIndexOf(component(card, "generalTab"))).isZero();
        assertThat(tabs.getIndexOf(component(card, "propertiesTab"))).isEqualTo(1);
        assertThat(tabs.getIndexOf(component(card, "versionsTab"))).isEqualTo(2);
    }

    @Test
    void theChosenTypeShowsItsFields() {
        DocumentDetailView card = openProperties(upload());
        assertThat(fieldPaths(card)).isEmpty();

        typeField(card).setValue(type("Contract"));
        assertThat(fieldPaths(card))
                .containsExactly("+contractNumber", "+contractKind", "+counterparty", "+contractAmount", "+validUntil");

        typeField(card).setValue(type("HR document"));
        assertThat(fieldPaths(card)).containsExactly("+employee", "+department", "+hireDate");

        typeField(card).clear();
        assertThat(fieldPaths(card)).isEmpty();
    }

    @Test
    void theTypeAndItsFieldsAreSavedOnOk() {
        Document document = upload();
        DocumentDetailView card = openProperties(document);

        typeField(card).setValue(type("Invoice"));
        field(card, "supplier").setValue("Globex");
        this.<JmixButton>component(card, "saveButton").click();

        Document stored = reloadWithFields(document);
        assertThat(stored.getCategory()).isEqualTo(type("Invoice"));
        assertThat(EntityValues.<Object>getValue(stored, "+supplier")).isEqualTo("Globex");
    }

    @Test
    void aNewDocumentIsSavedWithItsTypeAndFields() {
        DocumentDetailView card = openNewCard();
        TestUploads.succeed(component(card, "newVersionUpload"), temporaryStorage, "invoice.txt", "text");
        selectProperties(card);

        typeField(card).setValue(type("Invoice"));
        field(card, "invoiceNumber").setValue("INV-1610");
        this.<JmixButton>component(card, "saveButton").click();

        Document created = dataManager.load(Document.class)
                .query("select d from Document d where d.folder = :folder")
                .parameter("folder", sandbox)
                .one();

        Document stored = reloadWithFields(created);
        assertThat(stored.getCategory()).isEqualTo(type("Invoice"));
        assertThat(EntityValues.<Object>getValue(stored, "+invoiceNumber")).isEqualTo("INV-1610");
    }

    @Test
    void aViewerSeesTheTypeAndItsFieldsReadOnly() {
        Document document = systemAuthenticator.withUser(ADMIN, () ->
                TestAccess.giveOwnAccess(dataManager, contract(upload()),
                        Grant.user(EMPLOYEE, AccessLevel.VIEW)));

        DocumentDetailView card = openProperties(document);

        assertThat(typeField(card).isReadOnly()).isTrue();
        assertThat(fields(card)).isNotEmpty().allMatch(DocumentPropertiesTest::isReadOnly);
    }

    @Test
    void anArchivedDocumentShowsItsTypeAndFieldsReadOnly() {
        Document document = contract(upload());
        documentService.changeStatus(List.of(document), DocumentStatus.ACTIVE);
        documentService.changeStatus(List.of(document), DocumentStatus.ARCHIVED);

        DocumentDetailView card = openProperties(document);

        assertThat(typeField(card).isReadOnly()).isTrue();
        assertThat(fields(card)).isNotEmpty().allMatch(DocumentPropertiesTest::isReadOnly);
    }

    @Test
    void theTypeAndItsFieldsTurnReadOnlyWhenTheCardTakesTheDocumentAgain() {
        Document document = systemAuthenticator.withUser(ADMIN, () ->
                TestAccess.giveOwnAccess(dataManager, contract(upload()),
                        Grant.user(EMPLOYEE, AccessLevel.MANAGE)));

        DocumentDetailView card = openProperties(document);

        // the employee keeps only the view; the card takes the document again after the dialog
        this.<JmixButton>component(card, "accessButton").click();
        AccessDetailView dialog = UiTestUtils.getLastOpenedViewDialog();
        assertThat(dialog).isNotNull();

        CollectionContainer<AccessEntryDraft> entries =
                ViewControllerUtils.getViewData(dialog).getContainer("entriesDc");

        entries.getItems().forEach(entry -> entry.setAccessLevel(AccessLevel.VIEW));
        UiTestUtils.<JmixButton>getComponent(dialog, "saveButton").click();
        yesButton().click();

        assertThat(card.isReadOnly()).isTrue();
        assertThat(typeField(card).isReadOnly()).isTrue();
        assertThat(fields(card)).isNotEmpty().allMatch(DocumentPropertiesTest::isReadOnly);
    }

    @Test
    void anEditorChangesTheFields() {
        DocumentDetailView card = openProperties(contract(upload()));

        assertThat(typeField(card).isReadOnly()).isFalse();
        assertThat(fields(card)).isNotEmpty().noneMatch(DocumentPropertiesTest::isReadOnly);
    }

    private Button yesButton() {
        DialogInfo dialog = UiTestUtils.getLastOpenedDialog();
        assertThat(dialog).isNotNull();

        return dialog.getButtons().stream()
                .filter(button -> button.getId().equals(Optional.of(DialogAction.Type.YES.getId())))
                .findFirst()
                .orElseThrow();
    }

    private DocumentDetailView openProperties(Document document) {
        DocumentDetailView card = openCard(document);
        selectProperties(card);
        return card;
    }

    private void selectProperties(DocumentDetailView card) {
        JmixTabSheet tabs = component(card, "cardTabs");
        tabs.setSelectedTab(component(card, "propertiesTab"));
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

    @SuppressWarnings("unchecked")
    private EntityComboBox<Category> typeField(DocumentDetailView card) {
        return (EntityComboBox<Category>) FragmentUtils.getComponent(
                component(card, "propertiesFragment"), "typeField");
    }

    private List<Component> fields(DocumentDetailView card) {
        DynamicAttributesPanel panel = (DynamicAttributesPanel) FragmentUtils.getComponent(
                component(card, "propertiesFragment"), "fieldsPanel");

        return UiComponentUtils.getComponents(panel).stream()
                .filter(component -> pathOf(component).startsWith("+"))
                .toList();
    }

    private List<String> fieldPaths(DocumentDetailView card) {
        return fields(card).stream()
                .map(DocumentPropertiesTest::pathOf)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private HasValue<?, Object> field(DocumentDetailView card, String code) {
        return (HasValue<?, Object>) fields(card).stream()
                .filter(field -> pathOf(field).equals("+" + code))
                .findFirst()
                .orElseThrow();
    }

    private static boolean isReadOnly(Component field) {
        return ((HasValueAndElement<?, ?>) field).isReadOnly();
    }

    private static String pathOf(Component component) {
        return component instanceof SupportsValueSource<?> field
                && field.getValueSource() instanceof EntityValueSource<?, ?> source
                ? source.getMetaPropertyPath().toPathString()
                : "";
    }

    private <T> T component(DocumentDetailView card, String id) {
        return UiTestUtils.getComponent(card, id);
    }

    private Category type(String name) {
        return dataManager.load(Category.class)
                .query("select c from dynat_Category c where c.name = :name and c.entityType = 'Document'")
                .parameter("name", name)
                .one();
    }

    private Document contract(Document document) {
        Document edited = reloadWithFields(document);
        edited.setCategory(type("Contract"));
        EntityValues.setValue(edited, "+counterparty", "Acme Ltd");
        return documentService.save(edited, null, null);
    }

    private Document upload() {
        return systemAuthenticator.withUser(ADMIN, () ->
                documentService.upload(sandbox, List.of(TestFiles.file(tempDir, "a.txt", "text")))
                        .created()
                        .getFirst());
    }

    private Document reloadWithFields(Document document) {
        return dataManager.load(Document.class)
                .id(document.getId())
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("folder", FetchPlan.BASE)
                        .add("category", FetchPlan.INSTANCE_NAME))
                .hint(DynAttrQueryHints.LOAD_DYN_ATTR, true)
                .one();
    }
}
