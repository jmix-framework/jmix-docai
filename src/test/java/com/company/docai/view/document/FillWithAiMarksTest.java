package com.company.docai.view.document;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.model.tag.Tag;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.StubAiConfiguration;
import com.company.docai.test_support.StubChatModel;
import com.company.docai.test_support.TestFiles;
import com.company.docai.test_support.TestTags;
import com.company.docai.view.main.MainView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasLabel;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.dom.Element;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.Messages;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.dynattr.model.Category;
import io.jmix.dynattrflowui.panel.DynamicAttributesPanel;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.component.UiComponentUtils;
import io.jmix.flowui.component.combobox.EntityComboBox;
import io.jmix.flowui.component.datepicker.TypedDatePicker;
import io.jmix.flowui.component.multiselectcombobox.JmixMultiSelectComboBox;
import io.jmix.flowui.data.EntityValueSource;
import io.jmix.flowui.data.SupportsValueSource;
import io.jmix.flowui.fragment.FragmentUtils;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.testassist.notification.OpenedNotifications;
import io.jmix.flowui.view.DialogWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.company.docai.test_support.TestUi.awaitAccess;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The marks of the fields that Fill with AI set in the document card, with the model replaced by a stub.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class,
        StubAiConfiguration.class})
@TestPropertySource(properties = "spring.ai.openai.api-key=test-key")
class FillWithAiMarksTest {

    private static final String ADMIN = "admin";
    private static final String INVOICE = "Invoice 1187 of 1 November 2026 from Harbour Logistics LLC";

    @Autowired
    private DialogWindows dialogWindows;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private OpenedNotifications openedNotifications;

    @Autowired
    private Messages messages;

    @Autowired
    private StubChatModel model;

    @TempDir
    private Path tempDir;

    private final String lease = "Lease " + UUID.randomUUID().toString().substring(0, 8);

    private Folder sandbox;

    @BeforeEach
    void setUp() {
        model.reset();
        TestTags.tag(unconstrainedDataManager, lease);

        sandbox = systemAuthenticator.withUser(ADMIN, () -> {
            Folder folder = dataManager.create(Folder.class);
            folder.setParent(folderService.getRoot());
            folder.setName("fill-with-ai-marks-test-" + UUID.randomUUID());
            return folderService.save(folder);
        });
    }

    @AfterEach
    void deleteSandbox() {
        systemAuthenticator.runWithUser(ADMIN, () -> dataManager.load(Id.of(sandbox))
                .optional()
                .ifPresent(folderService::delete));
    }

    @Test
    void theTypeTheFieldsTheDateAndTheTagsTheAiSetWearTheMark() {
        DocumentDetailView card = openCard(upload());

        fill(card);

        assertThat(marked(typeField(card))).isTrue();
        assertThat(marked(field(card, "supplier"))).isTrue();
        assertThat(marked(field(card, "invoiceNumber"))).isTrue();
        assertThat(marked(field(card, "invoiceAmount"))).isFalse();
        assertThat(marked(field(card, "dueDate"))).isFalse();
        assertThat(marked(component(card, "documentDateField"))).isTrue();
        assertThat(marked(component(card, "tagsField"))).isTrue();
        assertThat(marked(component(card, "nameField"))).isFalse();
        assertThat(dotted(component(card, "generalTab"))).isTrue();
        assertThat(dotted(component(card, "propertiesTab"))).isTrue();
    }

    // the mark joins the label of the field, so a screen reader reads the name of the field and then the mark
    @Test
    void theMarkKeepsTheLabelOfTheFieldAndNamesItselfToAScreenReader() {
        DocumentDetailView card = openCard(upload());

        fill(card);

        Component supplier = field(card, "supplier");
        Element label = label(supplier).orElseThrow();
        Element mark = mark(supplier).orElseThrow();

        assertThat(label.getText()).isEqualTo(((HasLabel) supplier).getLabel());
        assertThat(mark.getAttribute("role")).isEqualTo("img");
        assertThat(mark.getAttribute("aria-label"))
                .isEqualTo(messages.getMessage(DocumentDetailView.class, "aiFilledMark.ariaLabel"));
        assertThat(mark.getTextRecursively())
                .isEqualTo(messages.getMessage(DocumentDetailView.class, "aiFilledMark.text"));
        assertThat(mark.getClassList()).contains("aura-accent-purple");
    }

    @Test
    void anEditTakesTheMarkOffItsFieldAndTheLastMarkOfATabTakesItsDot() {
        DocumentDetailView card = openCard(upload());
        fill(card);

        editToGlobex(field(card, "supplier"));

        assertThat(marked(field(card, "supplier"))).isFalse();
        assertThat(marked(field(card, "invoiceNumber"))).isTrue();
        assertThat(dotted(component(card, "propertiesTab"))).isTrue();

        this.<TypedDatePicker<LocalDate>>component(card, "documentDateField").setValue(LocalDate.of(2026, 11, 2));

        assertThat(marked(component(card, "documentDateField"))).isFalse();
        assertThat(dotted(component(card, "generalTab"))).isTrue();

        this.<JmixMultiSelectComboBox<Tag>>component(card, "tagsField").setValue(Set.of());

        assertThat(marked(component(card, "tagsField"))).isFalse();
        assertThat(dotted(component(card, "generalTab"))).isFalse();
    }

    // a type chosen by hand is the person's: the fields of the type the AI chose come back with their values and marks
    @Test
    void aTypeChosenByHandTakesTheMarkOffTheTypeAndTheFormerTypeBringsItsFieldsBackMarked() {
        DocumentDetailView card = openCard(upload());
        fill(card);

        typeField(card).setValue(type("Contract"));

        assertThat(marked(typeField(card))).isFalse();
        assertThat(fields(card)).isNotEmpty().noneMatch(FillWithAiMarksTest::marked);
        assertThat(dotted(component(card, "propertiesTab"))).isFalse();

        typeField(card).setValue(type("Invoice"));

        assertThat(marked(typeField(card))).isFalse();
        assertThat(marked(field(card, "supplier"))).isTrue();
        assertThat(dotted(component(card, "propertiesTab"))).isTrue();
    }

    @Test
    void aSavedCardOpensWithoutMarks() {
        Document document = upload();
        DocumentDetailView card = openCard(document);
        fill(card);

        this.<JmixButton>component(card, "saveButton").click();
        DocumentDetailView reopened = openCard(document);

        assertThat(marked(typeField(reopened))).isFalse();
        assertThat(fields(reopened)).isNotEmpty().noneMatch(FillWithAiMarksTest::marked);
        assertThat(marked(component(reopened, "documentDateField"))).isFalse();
        assertThat(dotted(component(reopened, "generalTab"))).isFalse();
        assertThat(dotted(component(reopened, "propertiesTab"))).isFalse();
    }

    @Test
    void aFieldTheAiCouldNotFitStaysUnmarked() {
        DocumentDetailView card = openCard(upload());

        model.willReply("""
                {"type": "Invoice",
                 "fields": [{"code": "invoiceAmount", "value": "about a million"}],
                 "documentDate": null}""");

        openedNotifications.closeOpenedNotifications();
        this.<JmixButton>component(card, "fillWithAiButton").click();
        awaitAccess(() -> !openedNotifications.getNotifications().isEmpty());

        assertThat(marked(typeField(card))).isTrue();
        assertThat(marked(field(card, "invoiceAmount"))).isFalse();
        assertThat(marked(component(card, "documentDateField"))).isFalse();
        assertThat(dotted(component(card, "generalTab"))).isFalse();
        assertThat(dotted(component(card, "propertiesTab"))).isTrue();
    }

    private void fill(DocumentDetailView card) {
        model.willReply("""
                {"type": "Invoice",
                 "fields": [{"code": "supplier", "value": "Harbour Logistics LLC"},
                            {"code": "invoiceNumber", "value": "1187"}],
                 "documentDate": "2026-11-01",
                 "tags": ["%s"]}""".formatted(lease));

        openedNotifications.closeOpenedNotifications();
        this.<JmixButton>component(card, "fillWithAiButton").click();
        // the result comes back through UI.access, which a UI test runs only when asked
        awaitAccess(() -> !openedNotifications.getNotifications().isEmpty());
    }

    private static boolean marked(Component field) {
        return mark(field).isPresent();
    }

    // the label the field shows instead of its own while it is marked
    private static Optional<Element> label(Component field) {
        return field.getElement()
                .getChildren()
                .filter(child -> "label".equals(child.getAttribute("slot")))
                .findFirst();
    }

    private static Optional<Element> mark(Component field) {
        return label(field).flatMap(label -> label.getChildren()
                .filter(child -> "img".equals(child.getAttribute("role")))
                .findFirst());
    }

    private static boolean dotted(Tab tab) {
        return tab.getChildren()
                .anyMatch(child -> child instanceof Badge badge && badge.getThemeNames().contains("dot"));
    }

    @SuppressWarnings("unchecked")
    private EntityComboBox<Category> typeField(DocumentDetailView card) {
        return (EntityComboBox<Category>) FragmentUtils.getComponent(component(card, "propertiesFragment"),
                "typeField");
    }

    private List<Component> fields(DocumentDetailView card) {
        DynamicAttributesPanel panel = (DynamicAttributesPanel) FragmentUtils.getComponent(
                component(card, "propertiesFragment"), "fieldsPanel");

        return UiComponentUtils.getComponents(panel)
                .stream()
                .filter(component -> pathOf(component).startsWith("+"))
                .toList();
    }

    private Component field(DocumentDetailView card, String code) {
        return fields(card).stream()
                .filter(field -> pathOf(field).equals("+" + code))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static void editToGlobex(Component field) {
        ((HasValue<?, Object>) field).setValue("Globex");
    }

    private static String pathOf(Component component) {
        return component instanceof SupportsValueSource<?> field
                && field.getValueSource() instanceof EntityValueSource<?, ?> source
                ? source.getMetaPropertyPath().toPathString()
                : "";
    }

    private Category type(String name) {
        return systemAuthenticator.withUser(ADMIN, () -> dataManager.load(Category.class)
                .query("select c from dynat_Category c where c.name = :name and c.entityType = 'Document'")
                .parameter("name", name)
                .one());
    }

    private Document upload() {
        return systemAuthenticator.withUser(ADMIN, () ->
                documentService.upload(sandbox, List.of(TestFiles.file(tempDir, "invoice.txt", INVOICE)))
                        .created()
                        .getFirst());
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
}
