package com.company.dms.ai.classification;

import com.company.dms.DMSApplication;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.document.Document;
import com.company.dms.model.folder.Folder;
import com.company.dms.test_support.AuthenticatedAsAdmin;
import com.company.dms.test_support.StubAiConfiguration;
import com.company.dms.test_support.StubChatModel;
import com.company.dms.test_support.TestFiles;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Id;
import io.jmix.core.entity.EntityValues;
import io.jmix.dynattr.AttributeType;
import io.jmix.dynattr.DynAttrQueryHints;
import io.jmix.dynattr.model.Category;
import io.jmix.dynattr.model.CategoryAttribute;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {DMSApplication.class, StubAiConfiguration.class})
@ExtendWith(AuthenticatedAsAdmin.class)
class DocumentClassifierTest {

    @Autowired
    private DocumentClassifier documentClassifier;

    @Autowired
    private StubChatModel model;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private OpenAiChatProperties openAiChatProperties;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    private final List<Object> typesToRemove = new ArrayList<>();

    @BeforeEach
    void setUp() {
        model.reset();

        Folder folder = dataManager.create(Folder.class);
        folder.setParent(folderService.getRoot());
        folder.setName("ai-classifier-test-" + UUID.randomUUID());
        sandbox = folderService.save(folder);
    }

    @AfterEach
    void deleteSandbox() {
        dataManager.load(Id.of(sandbox))
                .optional()
                .ifPresent(folderService::delete);
        typesToRemove.forEach(type -> dataManager.load(Id.of(type)).optional().ifPresent(dataManager::remove));
    }

    @Test
    void theModelGetsTheTypesAndTheFieldsOfTheCatalog() {
        model.willReply("{\"type\": \"UNKNOWN\", \"fields\": [], \"documentDate\": null}");

        documentClassifier.classify("scan-0001.pdf", "The text of the scan");

        Prompt prompt = model.prompts().getFirst();
        assertThat(prompt.getSystemMessage().getText())
                .contains("\"Contract\"", "\"Invoice\"", "\"Completion certificate\"", "\"HR document\"")
                .contains("contractNumber", "invoiceAmount", "dueDate", "workCompletedOn", "hireDate")
                .contains("one of: supply,lease,work,services,leasing");
        assertThat(prompt.getUserMessage().getText()).contains("scan-0001.pdf", "The text of the scan");
    }

    @Test
    void theModelIsHeldToTheSchemaOfTheProposal() {
        model.willReply("{\"type\": \"UNKNOWN\", \"fields\": [], \"documentDate\": null}");

        documentClassifier.classify("scan-0001.pdf", "The text of the scan");

        assertThat(model.prompts().getFirst().getOptions()).isInstanceOfSatisfying(StructuredOutputChatOptions.class,
                options -> {
                    JsonNode schema = JsonMapper.shared().readTree(options.getOutputSchema());

                    // OpenAI takes a schema only when it requires every property; a missing date is null
                    assertThat(schema.at("/required").valueStream().map(JsonNode::asString))
                            .containsExactlyInAnyOrder("type", "fields", "documentDate");
                    assertThat(schema.at("/properties/documentDate/type").valueStream().map(JsonNode::asString))
                            .contains("null");
                });
    }

    @Test
    void theAnswerOfTheModelBecomesAProposal() {
        model.willReply("""
                {"type": "Invoice",
                 "fields": [{"code": "supplier", "value": "Harbour Logistics LLC"}],
                 "documentDate": "2026-11-01"}""");

        ClassificationProposal proposal = documentClassifier.classify("invoice.pdf", "Invoice 1187");

        assertThat(proposal.type()).isEqualTo("Invoice");
        assertThat(proposal.fields()).containsExactly(field("supplier", "Harbour Logistics LLC"));
        assertThat(proposal.documentDate()).isEqualTo("2026-11-01");
    }

    @Test
    void anAnswerWithoutFieldsFillsTheTypeOnly() {
        Document document = editable(upload());
        model.willReply("{\"type\": \"Invoice\"}");

        ClassificationResult result = documentClassifier.apply(
                documentClassifier.classify("invoice.pdf", "Invoice 1187"), document);

        assertThat(result.typeName()).isEqualTo("Invoice");
        assertThat(result.filled()).isEmpty();
        assertThat(document.getCategory()).isEqualTo(invoiceType());
    }

    @Test
    void aProposalFillsTheTypeTheFieldsAndTheDateButSavesNothing() {
        Document document = editable(upload());

        ClassificationResult result = documentClassifier.apply(new ClassificationProposal("Invoice", List.of(
                field("supplier", "Harbour Logistics LLC"),
                field("invoiceAmount", "1 240 000,50"),
                field("dueDate", "2026-11-15"),
                field("invoiceNumber", "1187")), "2026-11-01"), document);

        assertThat(result.typeName()).isEqualTo("Invoice");
        assertThat(result.filled()).hasSize(5);
        assertThat(result.skipped()).isEmpty();
        assertThat(document.getCategory()).isEqualTo(invoiceType());
        assertThat(value(document, "supplier")).isEqualTo("Harbour Logistics LLC");
        assertThat(value(document, "invoiceAmount")).isEqualTo(new BigDecimal("1240000.50"));
        assertThat(value(document, "dueDate")).isEqualTo(LocalDate.of(2026, 11, 15));
        assertThat(value(document, "invoiceNumber")).isEqualTo("1187");
        assertThat(document.getDocumentDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(editable(document).getCategory()).isNull();
    }

    @Test
    void anEnumerationTakesOnlyItsOwnValues() {
        Document lease = editable(upload());
        Document rent = editable(upload());

        documentClassifier.apply(new ClassificationProposal("Contract",
                List.of(field("contractKind", "Lease")), null), lease);
        ClassificationResult result = documentClassifier.apply(new ClassificationProposal("Contract",
                List.of(field("contractKind", "rent")), null), rent);

        assertThat(value(lease, "contractKind")).isEqualTo("lease");
        assertThat(value(rent, "contractKind")).isNull();
        assertThat(result.skipped()).singleElement().asString().endsWith(": rent");
    }

    @Test
    void theTemperatureComesFromTheConfiguration() {
        model.willReply("{\"type\": \"UNKNOWN\", \"fields\": [], \"documentDate\": null}");

        documentClassifier.classify("scan-0001.pdf", "The text of the scan");

        ChatOptions options = model.prompts().getFirst().getOptions();
        assertThat(openAiChatProperties.getTemperature()).isZero();
        assertThat(options).isNotNull();
        assertThat(options.getTemperature()).isNull();
    }

    @Test
    void anAmountWhoseCommaMaySeparateThousandsIsLeftOut() {
        Document document = editable(upload());

        ClassificationResult result = documentClassifier.apply(new ClassificationProposal("Invoice", List.of(
                field("invoiceAmount", "185,000")), null), document);

        assertThat(result.skipped()).hasSize(1);
        assertThat(value(document, "invoiceAmount")).isNull();
    }

    @Test
    void aListFieldAndAReferenceAreLeftToThePerson() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Category type = newType("Shipment " + suffix);
        newField(type, "carrier" + suffix, AttributeType.STRING);
        newField(type, "ports" + suffix, AttributeType.STRING, field -> field.setIsCollection(true));
        newField(type, "folder" + suffix, AttributeType.ENTITY, field -> field.setEntityClass(Folder.class.getName()));
        Document document = editable(upload());
        model.willReply("{\"type\": \"UNKNOWN\", \"fields\": [], \"documentDate\": null}");

        documentClassifier.classify("scan-0001.pdf", "The text of the scan");
        ClassificationResult result = documentClassifier.apply(new ClassificationProposal(type.getName(), List.of(
                field("carrier" + suffix, "Harbour Logistics LLC"),
                field("ports" + suffix, "Riga")), null), document);

        assertThat(model.prompts().getFirst().getSystemMessage().getText())
                .contains("carrier" + suffix)
                .doesNotContain("ports" + suffix, "folder" + suffix);
        assertThat(result.filled()).hasSize(1);
        assertThat(result.skipped()).hasSize(1);
        assertThat(value(document, "ports" + suffix)).isNull();
    }

    @Test
    void numbersFlagsAndDatesAreParsedByTheTypesOfTheirFields() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Category type = newType("Delivery note " + suffix);
        newField(type, "pallets" + suffix, AttributeType.INTEGER);
        newField(type, "weight" + suffix, AttributeType.DOUBLE);
        newField(type, "insured" + suffix, AttributeType.BOOLEAN);
        newField(type, "shippedAt" + suffix, AttributeType.DATE);
        newField(type, "fragile" + suffix, AttributeType.BOOLEAN);
        Document document = editable(upload());

        ClassificationResult result = documentClassifier.apply(new ClassificationProposal(type.getName(), List.of(
                field("pallets" + suffix, "12"),
                field("weight" + suffix, "1 250,5"),
                field("insured" + suffix, "TRUE"),
                field("shippedAt" + suffix, "2026-11-03"),
                field("fragile" + suffix, "yes")), null), document);

        assertThat(result.filled()).hasSize(4);
        assertThat(result.skipped()).hasSize(1);
        assertThat(value(document, "pallets" + suffix)).isEqualTo(12);
        assertThat(value(document, "weight" + suffix)).isEqualTo(1250.5);
        assertThat(value(document, "insured" + suffix)).isEqualTo(true);
        assertThat(value(document, "shippedAt" + suffix)).isEqualTo(Date.from(LocalDate.of(2026, 11, 3)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()));
        assertThat(value(document, "fragile" + suffix)).isNull();
    }

    @Test
    void valuesThatDoNotFitTheirFieldsAreSkippedAndTheRestIsFilled() {
        Document document = editable(upload());

        ClassificationResult result = documentClassifier.apply(new ClassificationProposal("Invoice", List.of(
                field("supplier", "Harbour Logistics LLC"),
                field("invoiceAmount", "about a million"),
                field("dueDate", "the middle of November"),
                field("counterparty", "Chamomile Trading LLC")), "yesterday"), document);

        assertThat(result.filled()).hasSize(1);
        assertThat(result.skipped()).hasSize(4);
        assertThat(value(document, "supplier")).isEqualTo("Harbour Logistics LLC");
        assertThat(value(document, "invoiceAmount")).isNull();
        assertThat(document.getDocumentDate()).isNull();
    }

    @Test
    void aProposalWithoutATypeOfTheCatalogLeavesTheDocumentAsItWas() {
        Document document = editable(upload());

        ClassificationResult unknown = documentClassifier.apply(new ClassificationProposal(
                ClassificationProposal.UNKNOWN, List.of(field("supplier", "Harbour Logistics LLC")), "2026-11-01"),
                document);
        ClassificationResult strange = documentClassifier.apply(new ClassificationProposal(
                "Purchase order", List.of(), "2026-11-01"), document);

        assertThat(unknown.typeName()).isNull();
        assertThat(strange.typeName()).isNull();
        assertThat(document.getCategory()).isNull();
        assertThat(document.getDocumentDate()).isNull();
    }

    @Test
    void theTypeIsFoundWhateverItsCase() {
        Document document = editable(upload());

        assertThat(documentClassifier.apply(new ClassificationProposal("invoice", List.of(), null), document)
                .typeName()).isEqualTo("Invoice");
        assertThat(document.getCategory()).isEqualTo(invoiceType());
    }

    @Test
    void theTypeIsFoundByItsLocalizedName() throws IOException {
        Document document = editable(upload());
        Properties names = new Properties();
        names.load(new StringReader(invoiceType().getLocaleNames()));
        String russianName = names.getProperty("ru");

        assertThat(russianName).isNotBlank().isNotEqualTo("Invoice");
        assertThat(documentClassifier.apply(new ClassificationProposal(russianName, List.of(), null), document)
                .typeName()).isEqualTo("Invoice");
        assertThat(document.getCategory()).isEqualTo(invoiceType());
    }

    private Document upload() {
        return documentService.upload(sandbox, List.of(TestFiles.file(tempDir, "scan-" + UUID.randomUUID() + ".txt",
                        "text")))
                .created()
                .getFirst();
    }

    private Document editable(Document document) {
        return dataManager.load(Document.class)
                .id(document.getId())
                .fetchPlan(plan -> plan.addFetchPlan(FetchPlan.BASE)
                        .add("category", FetchPlan.INSTANCE_NAME))
                .hint(DynAttrQueryHints.LOAD_DYN_ATTR, true)
                .one();
    }

    private Category newType(String name) {
        Category type = dataManager.create(Category.class);
        type.setName(name);
        type.setEntityType("Document");
        Category saved = dataManager.save(type);
        typesToRemove.add(saved);
        return saved;
    }

    private void newField(Category type, String code, AttributeType dataType) {
        newField(type, code, dataType, field -> {
        });
    }

    private void newField(Category type, String code, AttributeType dataType, Consumer<CategoryAttribute> setUp) {
        CategoryAttribute field = dataManager.create(CategoryAttribute.class);
        field.setCategory(type);
        field.setCategoryEntityType("Document");
        field.setName(code);
        field.setCode(code);
        field.setDataType(dataType);
        field.setOrderNo(10);
        setUp.accept(field);
        typesToRemove.addFirst(dataManager.save(field));
    }

    private Category invoiceType() {
        return dataManager.load(Category.class)
                .query("select c from dynat_Category c where c.name = :name and c.entityType = 'Document'")
                .parameter("name", "Invoice")
                .one();
    }

    private static Object value(Document document, String code) {
        return EntityValues.getValue(document, "+" + code);
    }

    private static ClassificationProposal.Field field(String code, String value) {
        return new ClassificationProposal.Field(code, value);
    }
}
