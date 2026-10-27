package com.company.dms.ai.assistant;

import com.company.dms.DMSApplication;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.access.AccessLevel;
import com.company.dms.model.document.Document;
import com.company.dms.model.folder.Folder;
import com.company.dms.test_support.EmployeeUiTestAuthenticator;
import com.company.dms.test_support.StubChatModel;
import com.company.dms.test_support.StubChatModelConfiguration;
import com.company.dms.test_support.TestAccess;
import com.company.dms.test_support.TestAccess.Grant;
import com.company.dms.test_support.TestFiles;
import com.company.dms.view.main.MainView;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The chat of the assistant in the menu on the stub model, asked by an employee: what is saved of the answers and
 * what the model is told. A UI test, since the links to the cards come from the routes of the views.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DMSApplication.class, FlowuiTestAssistConfiguration.class,
        StubChatModelConfiguration.class})
@TestPropertySource(properties = "spring.ai.openai.api-key=test-key")
class AssistantChatServiceTest {

    private static final String ADMIN = "admin";
    private static final String QUESTION = "Which invoices are there?";

    @Autowired
    private AiChatService chatService;

    @Autowired
    private AiConversationService conversationService;

    @Autowired
    private AiChatMessageService messageService;

    @Autowired
    private StubChatModel model;

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

    @BeforeEach
    void setUp() {
        model.reset();

        sandbox = systemAuthenticator.withUser(ADMIN, () -> {
            Folder folder = dataManager.create(Folder.class);
            folder.setParent(folderService.getRoot());
            folder.setName("assistant-chat-service-test-" + UUID.randomUUID());

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
    void aDocumentTheAnswerNamesLinksToItsCardWhenThePersonMayOpenIt() {
        Document invoice = upload("invoice.txt");
        Document hidden = systemAuthenticator.withUser(ADMIN, () -> TestAccess.giveOwnAccess(dataManager,
                upload("hidden.txt"), Grant.user("maria", AccessLevel.VIEW)));
        model.willReply("[Invoice](%s), [Other](/dms/documents/%s), [Made up](%s), [Jmix](https://www.jmix.io), "
                .formatted(invoice.getId(), hidden.getId(), UUID.randomUUID()) + "![Scan](" + invoice.getId() + ")");
        AiConversation conversation = conversationService.create();

        chatService.processMessage(ask(conversation));

        AiChatMessage answer = messageService.loadLatestMessage(conversation, AiChatMessageType.ASSISTANT);
        assertThat(answer).isNotNull();
        assertThat(answer.getContent())
                .isEqualTo("[Invoice](documents/%s), Other, Made up, [Jmix](https://www.jmix.io), "
                        .formatted(invoice.getId()) + "![Scan](" + invoice.getId() + ")");
    }

    @Test
    void aLinkToNeitherADocumentNorASiteBecomesItsText() {
        String site = "https://example.org/files/" + UUID.randomUUID();
        model.willReply("[Notes](null), [Draft](documents/), [Files](%s)".formatted(site));
        AiConversation conversation = conversationService.create();

        chatService.processMessage(ask(conversation));

        AiChatMessage answer = messageService.loadLatestMessage(conversation, AiChatMessageType.ASSISTANT);
        assertThat(answer).isNotNull();
        assertThat(answer.getContent()).isEqualTo("Notes, Draft, [Files](%s)".formatted(site));
    }

    @Test
    void theModelAnswersInTheLanguageOfTheQuestion() {
        model.willReply("There are no invoices.");

        chatService.processMessage(ask(conversationService.create()));

        assertThat(model.prompts().getFirst().getSystemMessage().getText())
                .contains("Respond in this language: the language of the latest message of the user");
    }

    @Test
    void anEmptyAnswerFailsAndLeavesNoAnswerInTheConversation() {
        model.willReply(" ");
        AiConversation conversation = conversationService.create();
        AiChatMessage question = ask(conversation);

        assertThatThrownBy(() -> chatService.processMessage(question)).isInstanceOf(IllegalStateException.class);
        assertThat(messageService.loadMessages(conversation)).extracting(AiChatMessage::getType)
                .containsExactly(AiChatMessageType.USER);
    }

    @Test
    void theModelIsToldThePromptOfTheAddOnAndTheRulesOfTheDocuments() {
        model.willReply("There are no invoices.");

        chatService.processMessage(ask(conversationService.create()));

        assertThat(model.prompts().getFirst().getSystemMessage().getText())
                .contains("You are an assistant for a Jmix application", "dms_findDocumentsByTypeField",
                        "dms_readDocumentText", "[name](id)");
    }

    private AiChatMessage ask(AiConversation conversation) {
        return messageService.createMessage(conversation, AiChatMessageType.USER, QUESTION);
    }

    private Document upload(String fileName) {
        return systemAuthenticator.withUser(ADMIN, () -> documentService.upload(sandbox,
                        List.of(TestFiles.file(tempDir, fileName,
                                "A document of the assistant test".getBytes(StandardCharsets.UTF_8))))
                .created()
                .getFirst());
    }
}
