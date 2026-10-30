package com.company.docai.ai.assistant;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.test_support.AuthenticatedAsAdmin;
import com.company.docai.test_support.StubAiConfiguration;
import com.company.docai.test_support.StubChatModel;
import com.company.docai.test_support.TestAccess;
import com.company.docai.test_support.TestAccess.Grant;
import com.company.docai.test_support.TestFiles;
import com.google.common.util.concurrent.Uninterruptibles;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.security.SystemAuthenticator;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = {DocAiApplication.class, StubAiConfiguration.class})
@ExtendWith(AuthenticatedAsAdmin.class)
class DocumentAssistantTest {

    private static final String EMPLOYEE = "peter";
    private static final ToolStatusFeed NO_STATUSES = status -> () -> {
    };

    @Autowired
    private DocumentAssistant documentAssistant;

    @Autowired
    private StubChatModel model;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void setUp() {
        model.reset();

        Folder folder = dataManager.create(Folder.class);
        folder.setParent(folderService.getRoot());
        folder.setName("ai-assistant-test-" + UUID.randomUUID());
        sandbox = folderService.save(folder);
    }

    @AfterEach
    void deleteSandbox() {
        dataManager.load(Id.of(sandbox))
                .optional()
                .ifPresent(folderService::delete);
    }

    @Test
    void theModelKnowsTheDocumentAndGetsItsTools() {
        Document lease = uploadLease();
        model.willReply("The lease is valid until 31 December 2027.");

        String answer = documentAssistant.chatClient(lease, NO_STATUSES)
                .prompt()
                .user("Until when is the lease valid?")
                .call()
                .content();

        Prompt prompt = model.prompts().getFirst();
        assertThat(answer).isEqualTo("The lease is valid until 31 December 2027.");
        assertThat(prompt.getSystemMessage().getText()).contains("lease-112.txt", lease.getId().toString(),
                "docai_readDocumentComments");
        assertThat(prompt.getOptions()).isInstanceOfSatisfying(ToolCallingChatOptions.class,
                options -> assertThat(options.getToolCallbacks())
                        .extracting(ToolCallback::getToolDefinition)
                        .extracting(ToolDefinition::name)
                        .containsExactlyInAnyOrder("docai_readDocumentText", "docai_proposeTypeAndFields",
                                "docai_readDocumentComments"));
    }

    @Test
    void theConversationRemembersWhatWasSaid() {
        ChatClient chatClient = documentAssistant.chatClient(uploadLease(), NO_STATUSES);
        model.willReply("Until 31 December 2027.");
        model.willReply("185,000 a month.");

        chatClient.prompt()
                .user("Until when is the lease valid?")
                .call()
                .content();

        chatClient.prompt()
                .user("And the rent?")
                .call()
                .content();

        assertThat(model.prompts().get(1).getInstructions())
                .extracting(Message::getText)
                .contains("Until when is the lease valid?", "Until 31 December 2027.", "And the rent?");
    }

    @Test
    void whatAToolReadStaysInTheConversation() {
        Document lease = uploadLease();
        ChatClient chatClient = documentAssistant.chatClient(lease, NO_STATUSES);
        model.willCallTool("docai_readDocumentText", readCall(lease));
        model.willReply("The number of the lease is 112.");
        model.willReply("It is a lease agreement.");

        chatClient.prompt()
                .user("What is the number of the lease?")
                .call()
                .content();

        chatClient.prompt()
                .user("What kind of document is it?")
                .call()
                .content();

        assertThat(model.prompts().get(2).getInstructions())
                .filteredOn(ToolResponseMessage.class::isInstance)
                .map(message -> ((ToolResponseMessage) message).getResponses().getFirst().responseData())
                .anySatisfy(data -> assertThat(data).contains("Lease agreement 112"));
    }

    @Test
    void aToolCallSendsEachMessageOnce() {
        Document lease = uploadLease();
        model.willCallTool("docai_readDocumentText", readCall(lease));
        model.willReply("The number of the lease is 112.");

        documentAssistant.chatClient(lease, NO_STATUSES)
                .prompt()
                .user("What is the number of the lease?")
                .call()
                .content();

        assertThat(model.prompts().get(1).getInstructions())
                .extracting(Message::getMessageType)
                .containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL);
    }

    @Test
    void twoQuestionsThatReadTheTextSendEachMessageOnce() {
        Document lease = uploadLease();
        ChatClient chatClient = documentAssistant.chatClient(lease, NO_STATUSES);
        model.willCallTool("docai_readDocumentText", readCall(lease));
        model.willReply("The number of the lease is 112.");
        model.willCallTool("docai_readDocumentText", readCall(lease));
        model.willReply("It is a lease agreement.");

        ask(chatClient, "What is the number of the lease?");
        ask(chatClient, "What kind of document is it?");

        assertThat(model.prompts().get(3).getInstructions())
                .extracting(Message::getMessageType)
                .containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL,
                        MessageType.ASSISTANT, MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL);
    }

    @Test
    void aQuestionStoppedWhileAToolReadsLeavesTheConversationUsable() throws InterruptedException {
        Document lease = uploadLease();
        AtomicReference<Disposable> firstAnswer = new AtomicReference<>();
        CountDownLatch asked = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        // the person presses Stop while the tool reads, as the chat shows its status
        ChatClient chatClient = documentAssistant.chatClient(lease, status -> {
            assertThat(Uninterruptibles.awaitUninterruptibly(asked, 20, TimeUnit.SECONDS)).isTrue();
            firstAnswer.get().dispose();
            stopped.countDown();
            return () -> {
            };
        });
        model.willCallTool("docai_readDocumentText", readCall(lease));
        model.willReply("It is a lease agreement.");

        firstAnswer.set(chatClient.prompt()
                .user("What is the number of the lease?")
                .stream()
                .content()
                .subscribe());

        asked.countDown();
        assertThat(stopped.await(20, TimeUnit.SECONDS)).isTrue();

        String answer = ask(chatClient, "What kind of document is it?");

        assertThat(answer).isEqualTo("It is a lease agreement.");
        assertThat(model.prompts().getLast().getInstructions())
                .extracting(Message::getMessageType)
                .containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.USER);
    }

    @Test
    void aToolTheModelMadeUpLeavesTheConversationUsable() {
        ChatClient chatClient = documentAssistant.chatClient(uploadLease(), NO_STATUSES);
        model.willCallTool("docai_readTheMind", "{}");
        model.willReply("It is a lease agreement.");

        assertThatThrownBy(() -> ask(chatClient, "What is the number of the lease?"))
                .hasStackTraceContaining("docai_readTheMind");

        String answer = ask(chatClient, "What kind of document is it?");

        assertThat(answer).isEqualTo("It is a lease agreement.");
        assertThat(model.prompts().getLast().getInstructions())
                .extracting(Message::getMessageType)
                .containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.USER);
    }

    @Test
    void theToolsReadAsThePersonWhoStartedTheConversation() {
        Document lease = TestAccess.giveOwnAccess(dataManager, uploadLease(),
                Grant.user("maria", AccessLevel.VIEW));
        ChatClient chatClient = systemAuthenticator.withUser(EMPLOYEE,
                () -> documentAssistant.chatClient(lease, NO_STATUSES));
        model.willCallTool("docai_readDocumentText", readCall(lease));
        model.willReply("The document is not available to you.");

        chatClient.prompt()
                .user("What is the number of the lease?")
                .call()
                .content();

        assertThat(model.prompts().get(1).getInstructions())
                .filteredOn(ToolResponseMessage.class::isInstance)
                .map(message -> ((ToolResponseMessage) message).getResponses().getFirst().responseData())
                .singleElement()
                .satisfies(data -> assertThat(data).contains("not available").doesNotContain("Lease agreement 112"));
    }

    @Test
    void eachConversationIsOfItsOwn() {
        Document lease = uploadLease();
        model.willReply("Until 31 December 2027.");
        model.willReply("A warehouse.");

        documentAssistant.chatClient(lease, NO_STATUSES)
                .prompt()
                .user("Until when is the lease valid?")
                .call()
                .content();

        documentAssistant.chatClient(lease, NO_STATUSES)
                .prompt()
                .user("What is leased?")
                .call()
                .content();

        assertThat(model.prompts().get(1).getInstructions())
                .extracting(Message::getText)
                .doesNotContain("Until when is the lease valid?", "Until 31 December 2027.");
    }

    // the chat of the card streams the answer
    private static @Nullable String ask(ChatClient chatClient, String question) {
        return chatClient.prompt()
                .user(question)
                .stream()
                .content()
                .collect(Collectors.joining())
                .block();
    }

    private static String readCall(Document document) {
        return "{\"status\": \"Reading the lease\", \"documentId\": \"" + document.getId() + "\"}";
    }

    private Document uploadLease() {
        return documentService.upload(sandbox, List.of(TestFiles.file(tempDir, "lease-112.txt", "Lease agreement 112")))
                .created()
                .getFirst();
    }
}
