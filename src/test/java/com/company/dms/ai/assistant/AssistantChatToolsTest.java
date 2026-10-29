package com.company.dms.ai.assistant;

import com.company.dms.DMSApplication;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.access.AccessLevel;
import com.company.dms.model.document.Document;
import com.company.dms.model.folder.Folder;
import com.company.dms.search.semantic.DocumentVectorIndex;
import com.company.dms.search.semantic.DocumentVectorIndexer;
import com.company.dms.search.semantic.SemanticSearch;
import com.company.dms.test_support.AuthenticatedAsAdmin;
import com.company.dms.test_support.InMemoryVectorIndex;
import com.company.dms.test_support.StubAiConfiguration;
import com.company.dms.test_support.StubChatModel;
import com.company.dms.test_support.StubEmbeddingModel;
import com.company.dms.test_support.TestAccess;
import com.company.dms.test_support.TestAccess.Grant;
import com.company.dms.test_support.TestFiles;
import io.jmix.aitools.tool.AiToolRegistry;
import io.jmix.aitools.tool.AiToolStatusPublisher;
import io.jmix.aitools.tool.AiToolStatusUpdate;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Id;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.dynattr.DynAttrQueryHints;
import io.jmix.dynattr.model.Category;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The search of documents by the fields of their types, called the way the model calls it in the chat of the menu:
 * as the person who asks, with the status feed of the add-on in the tool context.
 */
@SpringBootTest(classes = {DMSApplication.class, StubAiConfiguration.class})
@ExtendWith(AuthenticatedAsAdmin.class)
class AssistantChatToolsTest {

    private static final String EMPLOYEE = "peter";
    private static final LocalDate DUE_DATE = LocalDate.of(2026, 12, 1);

    @Autowired
    private AssistantChatTools tools;

    @Autowired
    private InMemoryVectorIndex vectorIndex;

    @Autowired
    private StubChatModel model;

    @MockitoBean
    private DocumentVectorIndexer vectorIndexer;

    @Autowired
    private AiToolRegistry aiToolRegistry;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DataManager dataManager;

    @TempDir
    private Path tempDir;

    private Folder sandbox;
    private String supplier;
    private BigDecimal amount;
    private Document invoice;
    private Document contract;
    private Document hidden;

    private final List<String> statuses = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        vectorIndex.reset();
        model.reset();

        Folder folder = dataManager.create(Folder.class);
        folder.setParent(folderService.getRoot());
        folder.setName("assistant-chat-tools-test-" + UUID.randomUUID());
        sandbox = folderService.save(folder);

        supplier = "Harbour Logistics " + UUID.randomUUID();
        amount = new BigDecimal(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000) + ".50");
        invoice = typed(upload("invoice.txt"), "Invoice", Map.of("+supplier", supplier, "+invoiceAmount", amount,
                "+dueDate", DUE_DATE));
        contract = typed(upload("contract.txt"), "Contract",
                Map.of("+counterparty", supplier, "+contractAmount", amount));
        hidden = TestAccess.giveOwnAccess(dataManager, typed(upload("hidden.txt"), "Invoice",
                        Map.of("+supplier", supplier, "+invoiceAmount", amount, "+dueDate", DUE_DATE)),
                Grant.user("maria", AccessLevel.VIEW));
    }

    @AfterEach
    void deleteSandbox() {
        dataManager.load(Id.of(sandbox))
                .optional()
                .ifPresent(folderService::delete);
    }

    @Test
    void theInvoicesOfASupplierAreFoundByPartOfItsNameInAnyCase() {
        String found = find("supplier", supplier.substring(3).toUpperCase());

        assertThat(found).contains(invoice.getId().toString(), supplier)
                .doesNotContain(contract.getId().toString(), hidden.getId().toString());
    }

    @Test
    void aFieldNamedAlikeInSeveralTypesIsSearchedInAllOfThem() {
        String found = find("Amount", amount.toPlainString());

        assertThat(found).contains(invoice.getId().toString(), contract.getId().toString())
                .doesNotContain(hidden.getId().toString());
    }

    @Test
    void withoutAFieldEveryTextFieldIsSearched() {
        String found = find(null, supplier);

        assertThat(found).contains(invoice.getId().toString(), contract.getId().toString())
                .doesNotContain(hidden.getId().toString());
    }

    @Test
    void anUnknownFieldListsTheFieldsAndPointsToTheQueryTools() {
        assertThat(find("folder", supplier)).contains("No field is called \"folder\"", "query tools", "supplier (",
                "counterparty (");
    }

    @Test
    void aSearchThatFindsNothingPointsToTheQueryTools() {
        assertThat(find(null, "Nobody " + UUID.randomUUID())).contains("No document has a field holding",
                "query tools");
    }

    @Test
    void aValueThatFitsNoFieldSaysWhatFits() {
        assertThat(find("dueDate", "next week")).contains("\"next week\" is not a value of dueDate (");
    }

    @Test
    void aSearchWithoutAValueFindsNothingAndPointsToTheQueryTools() {
        assertThat(List.of(find(null, null), find("supplier", " "))).allSatisfy(found -> assertThat(found)
                .contains("Give a value", "query tools")
                .doesNotContain(invoice.getId().toString()));
    }

    @Test
    void aNumberOrADateIsFoundInARange() {
        String byAmount = findIn("Amount", amount.subtract(BigDecimal.ONE).toPlainString(),
                amount.add(BigDecimal.ONE).toPlainString());
        String byDueDate = findIn("dueDate", "2026-11-01", "2026-12-31");

        assertThat(byAmount).contains(invoice.getId().toString(), contract.getId().toString())
                .doesNotContain(hidden.getId().toString());
        assertThat(byDueDate).contains(invoice.getId().toString())
                .doesNotContain(contract.getId().toString(), hidden.getId().toString());
        assertThat(findIn("dueDate", "2026-12-02", null)).doesNotContain(invoice.getId().toString());
    }

    @Test
    void aRangeNeedsANumberOrADateField() {
        assertThat(findIn(null, "1", "2")).contains("Name the field", "invoiceAmount (");
        assertThat(findIn("supplier", "1", "2")).contains("is not a range of supplier (");
    }

    @Test
    void aDocumentFoundShowsAllItsFieldsItsFolderAndWhoUploadedIt() {
        String found = find("supplier", supplier);

        assertThat(found).contains(amount.toPlainString(), DUE_DATE.toString(), sandbox.getName(), "uploaded by admin")
                .doesNotContain("dated null");
    }

    @Test
    void theTypesAndTheirFieldsAreDescribedForTheModel() {
        assertThat(tools.describeFields())
                .contains("- Invoice: ", "supplier (Supplier, text)", "invoiceAmount (Amount, number)",
                        "dueDate (Due date, date)", "- HR document: ", "employee (Employee, text)",
                        "contractKind (Contract kind, one of: supply, lease, work, services, leasing)");
    }

    @Test
    void theStatusOfTheModelShowsInTheChat() {
        find("supplier", supplier);

        assertThat(statuses).containsExactly("Looking for the invoices");
    }

    @Test
    void theChatOfTheMenuHasTheToolsOfTheDocuments() {
        assertThat(aiToolRegistry.getAllCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .contains("dms_findDocumentsByTypeField", "dms_findDocumentsByMeaning", "dms_readDocumentText",
                        "dms_proposeTypeAndFields", "dms_readDocumentComments", "aitls_executeQuery");
    }

    @Test
    void theSearchByMeaningGivesThePassagesOfTheDocumentsThePersonMayOpen() {
        vectorIndex.put(invoice.getId(), "key", List.of(fragment("Delivery of office paper by Harbour trucks")));
        vectorIndex.put(hidden.getId(), "key", List.of(fragment("Delivery of office paper by Harbour trucks today")));
        model.willReply(allFit(1));

        String found = findByMeaning("office paper delivery");

        assertThat(found).contains(invoice.getId().toString(), supplier, "passage: Delivery of office paper")
                .doesNotContain(hidden.getId().toString());
        assertThat(statuses).containsExactly("Looking for the invoices");
    }

    @Test
    void aSearchByMeaningThatCannotRunSaysSo() {
        vectorIndex.setDown(true);

        assertThat(findByMeaning("office paper")).contains("not available now", "do not conclude");
    }

    // the search page has the same limit, so the model offers to narrow the topic instead of sending the person there
    @Test
    void aSearchByMeaningAtItsLimitSaysThatMoreDocumentsMayBeAboutTheTopic() {
        vectorIndex.put(invoice.getId(), "key", List.of(fragment("Delivery of office paper by Harbour trucks")));
        model.willReply(allFit(1));

        assertThat(findByMeaning("office paper delivery")).doesNotContain("more may be about");

        for (int i = 1; i <= SemanticSearch.PAGE_DOCUMENTS; i++) {
            vectorIndex.put(upload("paper-" + i + ".txt").getId(), "key",
                    List.of(fragment("Office paper delivery " + i)));
        }

        model.willReply(allFit(SemanticSearch.PAGE_DOCUMENTS));

        assertThat(findByMeaning("office paper delivery")).contains("more may be about", "narrow")
                .doesNotContain("search field");
    }

    // the nearest fragments the search reads may all be of a few long documents
    @Test
    void thePassagesOfAFewLongDocumentsSayThatMoreDocumentsMayBeAboutTheTopic() {
        for (Document document : List.of(invoice, upload("paper.txt"))) {
            vectorIndex.put(document.getId(), "key", IntStream.range(0, 30)
                    .mapToObj(number -> fragment("Office paper delivery " + number))
                    .toList());
        }

        model.willReply(allFit(2));

        assertThat(findByMeaning("office paper delivery")).contains("more may be about");
    }

    @Test
    void aSearchByMeaningThatFindsNothingSaysSo() {
        assertThat(findByMeaning("office paper")).contains("No document the person may open is about")
                .doesNotContain("still indexing");
    }

    @Test
    void aSearchByMeaningWhileTheIndexFillsSaysThatDocumentsMayBeMissing() {
        when(vectorIndexer.isBusy()).thenReturn(true);

        assertThat(findByMeaning("office paper")).contains("still indexing", "do not conclude");

        vectorIndex.put(invoice.getId(), "key", List.of(fragment("Delivery of office paper by Harbour trucks")));
        model.willReply(allFit(1));

        assertThat(findByMeaning("office paper delivery")).contains("passage: Delivery of office paper",
                "still indexing");
    }

    private String findByMeaning(String topic) {
        Consumer<AiToolStatusUpdate> feed = update -> statuses.add(update.getMessage());
        ToolContext context = new ToolContext(Map.of(AiToolStatusPublisher.STATUS_UPDATE_CALLBACK, feed));

        return systemAuthenticator.withUser(EMPLOYEE, () -> tools.findDocumentsByMeaning("Looking for the invoices",
                topic, context));
    }

    private static DocumentVectorIndex.Fragment fragment(String text) {
        return new DocumentVectorIndex.Fragment(0, text, false, StubEmbeddingModel.vectorOf(text));
    }

    // the decisions of the check that keeps every candidate
    private static String allFit(int candidates) {
        return IntStream.rangeClosed(1, candidates)
                .mapToObj("{\"number\": %d, \"quote\": \"\", \"fits\": true}"::formatted)
                .collect(Collectors.joining(", ", "{\"verdicts\": [", "]}"));
    }

    private String find(@Nullable String field, @Nullable String value) {
        return search(field, value, null, null);
    }

    private String findIn(@Nullable String field, @Nullable String from, @Nullable String to) {
        return search(field, null, from, to);
    }

    private String search(@Nullable String field, @Nullable String value, @Nullable String from, @Nullable String to) {
        Consumer<AiToolStatusUpdate> feed = update -> statuses.add(update.getMessage());
        ToolContext context = new ToolContext(Map.of(AiToolStatusPublisher.STATUS_UPDATE_CALLBACK, feed));

        return systemAuthenticator.withUser(EMPLOYEE, () -> tools.findDocumentsByTypeField("Looking for the invoices",
                field, value, from, to, context));
    }

    private Document upload(String fileName) {
        return documentService.upload(sandbox, List.of(TestFiles.file(tempDir, fileName,
                        "A document of the assistant test".getBytes(StandardCharsets.UTF_8))))
                .created()
                .getFirst();
    }

    private Document typed(Document document, String type, Map<String, Object> fields) {
        Document edited = dataManager.load(Document.class)
                .id(document.getId())
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("folder", FetchPlan.BASE)
                        .add("category", FetchPlan.INSTANCE_NAME))
                .hint(DynAttrQueryHints.LOAD_DYN_ATTR, true)
                .one();

        edited.setCategory(dataManager.load(Category.class)
                .query("select c from dynat_Category c where c.name = :name and c.entityType = 'Document'")
                .parameter("name", type)
                .one());

        fields.forEach((field, value) -> EntityValues.setValue(edited, field, value));

        return documentService.save(edited, null, null);
    }
}
