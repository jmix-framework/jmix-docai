package com.company.dms.ai.assistant;

import com.company.dms.DMSApplication;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.access.AccessLevel;
import com.company.dms.model.document.Document;
import com.company.dms.model.folder.Folder;
import com.company.dms.test_support.AuthenticatedAsAdmin;
import com.company.dms.test_support.StubChatModelConfiguration;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The search of documents by the fields of their types, called the way the model calls it in the chat of the menu:
 * as the person who asks, with the status feed of the add-on in the tool context.
 */
@SpringBootTest(classes = {DMSApplication.class, StubChatModelConfiguration.class})
@ExtendWith(AuthenticatedAsAdmin.class)
class AssistantChatToolsTest {

    private static final String EMPLOYEE = "peter";

    @Autowired
    private AssistantChatTools tools;

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
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(folderService.getRoot());
        folder.setName("assistant-chat-tools-test-" + UUID.randomUUID());
        sandbox = folderService.save(folder);

        supplier = "Harbour Logistics " + UUID.randomUUID();
        amount = new BigDecimal(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000) + ".50");
        invoice = typed(upload("invoice.txt"), "Invoice", Map.of("+supplier", supplier, "+invoiceAmount", amount));
        contract = typed(upload("contract.txt"), "Contract",
                Map.of("+counterparty", supplier, "+contractAmount", amount));
        hidden = TestAccess.giveOwnAccess(dataManager, typed(upload("hidden.txt"), "Invoice",
                Map.of("+supplier", supplier, "+invoiceAmount", amount)), Grant.user("maria", AccessLevel.VIEW));
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
    void theStatusOfTheModelShowsInTheChat() {
        find("supplier", supplier);

        assertThat(statuses).containsExactly("Looking for the invoices");
    }

    @Test
    void theChatOfTheMenuHasTheToolsOfTheDocuments() {
        assertThat(aiToolRegistry.getAllCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .contains("dms_findDocumentsByTypeField", "dms_readDocumentText", "dms_proposeTypeAndFields",
                        "aitls_executeQuery");
    }

    private String find(@Nullable String field, String value) {
        Consumer<AiToolStatusUpdate> feed = update -> statuses.add(update.getMessage());
        ToolContext context = new ToolContext(Map.of(AiToolStatusPublisher.STATUS_UPDATE_CALLBACK, feed));

        return systemAuthenticator.withUser(EMPLOYEE, () -> tools.findDocumentsByTypeField("Looking for the invoices",
                field, value, context));
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
