package com.company.dms.ai.assistant;

import com.company.dms.DMSApplication;
import com.company.dms.ai.AiSettings;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.document.Document;
import com.company.dms.model.folder.Folder;
import com.company.dms.test_support.EmployeeUiTestAuthenticator;
import com.company.dms.test_support.TestFiles;
import com.company.dms.view.main.MainView;
import io.jmix.aitoolsflowui.model.AiChatMessage;
import io.jmix.aitoolsflowui.model.AiChatMessageType;
import io.jmix.aitoolsflowui.model.AiConversation;
import io.jmix.aitoolsflowui.service.AiChatMessageService;
import io.jmix.aitoolsflowui.service.AiChatService;
import io.jmix.aitoolsflowui.service.AiConversationService;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Id;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.dynattr.DynAttrQueryHints;
import io.jmix.dynattr.model.Category;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenAI model in the chat of the assistant in the menu, asked by an employee: a question by a field of the
 * document types, a count and a question about the content of a document each reach the tool that answers them.
 * Runs only with {@code ./gradlew aiTest} and the key in {@code OPENAI_API_KEY}.
 */
@Tag("ai")
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DMSApplication.class, FlowuiTestAssistConfiguration.class})
class LiveAssistantChatTest {

    private static final String ADMIN = "admin";

    // a name that no other document of the test database has, and that does not look like an id
    private static final String SUPPLIER = "Brindlewood Haulage LLC";

    @Autowired
    private AiChatService chatService;

    @Autowired
    private AiConversationService conversationService;

    @Autowired
    private AiChatMessageService messageService;

    @Autowired
    private AiSettings aiSettings;

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
    private Document invoice;
    private Document contract;

    @BeforeEach
    void setUp() {
        assertThat(aiSettings.isAvailable()).isTrue();

        String token = letters();

        systemAuthenticator.runWithUser(ADMIN, () -> {
            Folder folder = dataManager.create(Folder.class);
            folder.setParent(folderService.getRoot());
            folder.setName("Assistant chat test " + token);
            sandbox = folderService.save(folder);

            invoice = typed(upload("invoice.txt", "Invoice 1187 of 1 November 2026 from " + SUPPLIER
                    + ". Delivery address: 12 Quay Street, Portsmouth. Total: 1,250.00, due by 1 December 2026."),
                    "Invoice", Map.of("+supplier", SUPPLIER, "+invoiceAmount", new BigDecimal("1250.00"),
                            "+dueDate", LocalDate.of(2026, 12, 1)));
            contract = typed(upload("contract.txt", "Supply contract 45 with Northwind " + token
                    + " for office paper."), "Contract", Map.of("+counterparty", "Northwind " + token));
            upload("notes.txt", "Notes of the meeting about the paper supply.");
        });
    }

    @AfterEach
    void deleteSandbox() {
        systemAuthenticator.runWithUser(ADMIN, () -> dataManager.load(Id.of(sandbox))
                .optional()
                .ifPresent(folderService::delete));
    }

    @RepeatedTest(3)
    void theInvoicesOfASupplierAreFoundAndLinkedToTheirCards() {
        String answer = ask("Which invoices are from " + SUPPLIER + "?");

        assertThat(answer).contains("documents/" + invoice.getId())
                .doesNotContain("documents/" + contract.getId());
    }

    @RepeatedTest(3)
    void theDocumentsOfAFolderAreCounted() {
        String answer = ask("How many documents are there in the folder \"" + sandbox.getName() + "\"?");

        assertThat(answer).containsAnyOf("3", "three");
    }

    @RepeatedTest(3)
    void aDocumentIsReadToAnswerAboutItsContent() {
        String answer = ask("What is the delivery address of the invoice from " + SUPPLIER + "?");

        assertThat(answer).contains("12 Quay Street");
    }

    private String ask(String question) {
        AiConversation conversation = conversationService.create();

        chatService.processMessage(messageService.createMessage(conversation, AiChatMessageType.USER, question));

        AiChatMessage answer = messageService.loadLatestMessage(conversation, AiChatMessageType.ASSISTANT);
        assertThat(answer).isNotNull();

        return answer.getContent();
    }

    // a neutral file name, so that the model reads the fields or the text and not the name
    private Document upload(String fileName, String text) {
        return documentService.upload(sandbox, List.of(TestFiles.file(tempDir, "scan " + letters() + " " + fileName,
                        text.getBytes(StandardCharsets.UTF_8))))
                .created()
                .getFirst();
    }

    // letters only, so that a count in an answer cannot come from a name
    private static String letters() {
        return UUID.randomUUID().toString().replaceAll("[^a-f]", "");
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
