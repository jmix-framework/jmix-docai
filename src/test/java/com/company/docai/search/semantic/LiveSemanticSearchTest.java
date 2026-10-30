package com.company.docai.search.semantic;

import com.company.docai.DocAiApplication;
import com.company.docai.ai.AiSettings;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.search.DocumentSearchHit;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.TestAccess;
import com.company.docai.test_support.TestAccess.Grant;
import com.company.docai.test_support.TestFiles;
import com.company.docai.view.main.MainView;
import io.jmix.aitoolsflowui.model.AiChatMessage;
import io.jmix.aitoolsflowui.model.AiChatMessageType;
import io.jmix.aitoolsflowui.model.AiConversation;
import io.jmix.aitoolsflowui.service.AiChatMessageService;
import io.jmix.aitoolsflowui.service.AiChatService;
import io.jmix.aitoolsflowui.service.AiConversationService;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The search by meaning with the OpenAI models and OpenSearch, on English samples: the page finds the agricultural
 * machinery and nothing else, the assistant in the menu quotes the penalties of both supply contracts, and a document
 * the employee may not open is found by neither. Runs only with {@code ./gradlew aiTest}, the key in
 * {@code OPENAI_API_KEY} and OpenSearch from {@code compose.yaml}.
 */
@Tag("ai")
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveSemanticSearchTest {

    private static final String ADMIN = "admin";
    private static final String EMPLOYEE = "peter";
    private static final String TOPIC = "agricultural machinery";

    @Autowired
    private SemanticSearch semanticSearch;

    @Autowired
    private DocumentVectorIndexer indexer;

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

    // a static one is there before the samples are uploaded once for the class
    @TempDir
    private static Path tempDir;

    private Folder sandbox;
    private Document tractor;
    private Document combine;
    private Document seedDrill;
    private Document closedTractor;
    private Document paperContract;
    private Document cartridgeContract;
    private List<Document> otherThings;

    @BeforeAll
    void uploadTheSamples() {
        assertThat(aiSettings.isAvailable()).isTrue();

        systemAuthenticator.runWithUser(ADMIN, () -> {
            Folder folder = dataManager.create(Folder.class);
            folder.setParent(folderService.getRoot());
            folder.setName("Semantic search test " + UUID.randomUUID());
            sandbox = folderService.save(folder);

            tractor = upload("Lease agreement 17 of 3 March 2026. The lessor hands over to the lessee a wheeled "
                    + "tractor MTZ-82.1 with a front loader for the spring field work on the farm. The rent is 45,000 "
                    + "per month, paid in advance.");
            combine = upload("Invoice 2231 of 12 July 2026. Grain combine harvester Acros 595 Plus with a 7 m "
                    + "header, delivered to the farm of Green Valley Agro. Total: 9,800,000, due by 1 August 2026.");
            seedDrill = upload("Delivery note 88. A pneumatic seed drill with a 6 m working width and a fertilizer "
                    + "hopper is delivered to the field station. Received by the chief agronomist.");
            closedTractor = TestAccess.giveOwnAccess(dataManager, upload("Lease agreement 18. The lessor hands over "
                    + "a crawler tractor with a plough and a harrow for the field work on the farm."),
                    Grant.user("maria", AccessLevel.VIEW));
            paperContract = upload("Supply contract 51 with Northwind Paper for office paper. For each day of delay "
                    + "the supplier pays a penalty of 0.1% of the value of the goods not delivered in time.");
            cartridgeContract = upload("Supply contract 64 with Ridgeway Office for printer cartridges. A late "
                    + "delivery costs the supplier a fine of 500 for each week of delay.");
            otherThings = List.of(
                    upload("Rental contract 5. A crawler excavator with a 1.2 m3 bucket is rented for digging the "
                            + "foundation pit of the new warehouse. Rent per machine hour: 3,500."),
                    upload("Purchase order 140. A dump truck with a 20 t payload for the construction site of the "
                            + "logistics centre. Delivery within 30 days."),
                    upload("Service contract 12 for the daily cleaning of the office premises: floors, windows "
                            + "and the kitchen. The contractor brings its own detergents."),
                    upload("Order 77 for office furniture: twelve desks, twelve chairs and four cabinets for the new "
                            + "sales office."));
        });

        await().atMost(Duration.ofMinutes(2))
                .until(() -> !indexer.isBusy());
    }

    @AfterAll
    void deleteSandbox() {
        systemAuthenticator.runWithUser(ADMIN, () -> dataManager.load(Id.of(sandbox))
                .optional()
                .ifPresent(folderService::delete));
    }

    @RepeatedTest(3)
    void thePageFindsTheAgriculturalMachineryAndNothingElse() {
        List<Document> found = systemAuthenticator.withUser(EMPLOYEE, () -> documents(semanticSearch.search(TOPIC)));

        assertThat(found).contains(tractor, combine, seedDrill)
                .doesNotContain(closedTractor, paperContract, cartridgeContract)
                .doesNotContainAnyElementsOf(otherThings);
    }

    // the closed document fits the topic, so only the rights keep it from the employee
    @RepeatedTest(3)
    void aDocumentTheEmployeeMayNotOpenIsFoundOnlyByWhoMay() {
        List<Document> found = systemAuthenticator.withUser(ADMIN, () -> documents(semanticSearch.search(TOPIC)));

        assertThat(found).contains(closedTractor);
    }

    @RepeatedTest(3)
    void theAssistantQuotesThePenaltiesOfBothSupplyContracts() {
        String answer = ask("What penalties for a late delivery do the supply contracts set?");

        assertThat(answer).contains("documents/" + paperContract.getId(), "documents/" + cartridgeContract.getId(),
                "0.1", "500");
    }

    @RepeatedTest(3)
    void theAssistantCollectsTheAgriculturalMachineryTheEmployeeMayOpen() {
        String answer = ask("Collect the documents about agricultural machinery and tell what is in them.");

        assertThat(answer).contains("documents/" + tractor.getId(), "documents/" + combine.getId(),
                        "documents/" + seedDrill.getId())
                .doesNotContain("documents/" + closedTractor.getId());
    }

    private String ask(String question) {
        AiConversation conversation = conversationService.create();

        chatService.processMessage(messageService.createMessage(conversation, AiChatMessageType.USER, question));

        AiChatMessage answer = messageService.loadLatestMessage(conversation, AiChatMessageType.ASSISTANT);
        assertThat(answer).isNotNull();

        return answer.getContent();
    }

    // a neutral file name, so that the meaning comes from the text
    private Document upload(String text) {
        String fileName = "scan " + UUID.randomUUID() + ".txt";

        return documentService.upload(sandbox, List.of(TestFiles.file(tempDir, fileName,
                        text.getBytes(StandardCharsets.UTF_8))))
                .created()
                .getFirst();
    }

    private static List<Document> documents(List<DocumentSearchHit> hits) {
        return hits.stream()
                .map(DocumentSearchHit::document)
                .toList();
    }
}
