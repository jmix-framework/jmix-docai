package com.company.docai.view.document;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.StubAiConfiguration;
import com.company.docai.test_support.StubChatModel;
import com.company.docai.test_support.TestAccess;
import com.company.docai.test_support.TestAccess.Grant;
import com.company.docai.test_support.TestFiles;
import com.company.docai.test_support.TestStatuses;
import com.company.docai.test_support.TestUploads;
import com.company.docai.view.main.MainView;
import com.google.common.util.concurrent.Uninterruptibles;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.server.VaadinSession;
import io.jmix.aichat.component.chat.AiChat;
import io.jmix.aichat.component.messagelist.GenerationState;
import io.jmix.aichat.component.messagelist.ThinkingStatusItem;
import io.jmix.aichat.data.SimpleAiChatMessage;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.Messages;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.component.tabsheet.JmixTabSheet;
import io.jmix.flowui.fragment.FragmentUtils;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.testassist.notification.NotificationInfo;
import io.jmix.flowui.testassist.notification.OpenedNotifications;
import io.jmix.flowui.upload.TemporaryStorage;
import io.jmix.flowui.view.DialogWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

/**
 * The assistant tab of the document card, used by an employee, with the model replaced by a stub.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class,
        StubAiConfiguration.class})
@TestPropertySource(properties = "spring.ai.openai.api-key=test-key")
class DocumentAssistantFragmentTest {

    private static final String ADMIN = "admin";
    private static final String EMPLOYEE = "peter";
    private static final String ANSWER = "The lease runs until 31 December 2027 at 185,000.00 a month.";

    @Autowired
    private DialogWindows dialogWindows;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private OpenedNotifications openedNotifications;

    @Autowired
    private Messages messages;

    @Autowired
    private StubChatModel model;

    @Autowired
    private TemporaryStorage temporaryStorage;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void setUp() {
        model.reset();
        sandbox = systemAuthenticator.withUser(ADMIN, () -> {
            Folder folder = dataManager.create(Folder.class);
            folder.setParent(folderService.getRoot());
            folder.setName("document-assistant-test-" + UUID.randomUUID());
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
    void aSavedDocumentHasTheAssistant() {
        DocumentDetailView card = openCard(upload());

        assertThat(this.<Tab>component(card, "assistantTab").isVisible()).isTrue();
    }

    @Test
    void aPersonWhoOnlyViewsTheDocumentHasTheAssistant() {
        Document document = upload();
        systemAuthenticator.runWithUser(ADMIN, () ->
                TestAccess.giveOwnAccess(dataManager, document, Grant.user(EMPLOYEE, AccessLevel.VIEW)));

        DocumentDetailView card = openCard(document);

        assertThat(card.isReadOnly()).isTrue();
        assertThat(this.<Tab>component(card, "assistantTab").isVisible()).isTrue();
    }

    @Test
    void anArchivedDocumentHasTheAssistant() {
        Document document = upload();
        systemAuthenticator.runWithUser(ADMIN, () -> {
            documentService.changeStatus(List.of(document), TestStatuses.active(dataManager));
            documentService.changeStatus(List.of(document), TestStatuses.archived(dataManager));
        });

        DocumentDetailView card = openCard(stored(document));

        assertThat(this.<Tab>component(card, "assistantTab").isVisible()).isTrue();
    }

    @Test
    void aNewDocumentHasNoAssistantBeforeItIsSaved() {
        DocumentDetailView card = openNewCard();

        assertThat(this.<Tab>component(card, "assistantTab").isVisible()).isFalse();
    }

    @Test
    void aSuggestionAsksItsQuestionAndTheAnswerAppears() {
        DocumentDetailView card = openCard(upload());
        model.willReply(ANSWER);

        ask(card, "datesAndAmountsButton");

        assertThat(chat(card).getItems().getItems())
                .extracting(SimpleAiChatMessage::getContent)
                .containsExactly(messages.getMessage(DocumentAssistantFragment.class, "datesAndAmountsButton.prompt"),
                        ANSWER);
        assertThat(model.prompts().getFirst().getSystemMessage().getText())
                .contains(card.getEditedEntity().getId().toString());
    }

    @Test
    void theAnswerComesFromTheTextAndTheStatusOfTheModelShowsMeanwhile() {
        DocumentDetailView card = openCard(upload());
        AiChat<SimpleAiChatMessage> chat = chat(card);
        List<ThinkingStatusItem> statuses = new CopyOnWriteArrayList<>();
        model.willCallTool("docai_readDocumentText", readCall(card));
        model.willReply(() -> {
            statuses.addAll(chat.getThinkingStatusPublisher().getItems());
            return ANSWER;
        });

        ask(card, "datesAndAmountsButton");

        assertThat(statuses)
                .extracting(ThinkingStatusItem::getText, ThinkingStatusItem::isCompleted)
                .containsExactly(tuple("Reading the lease", true));
        assertThat(toolResults(model.prompts().get(1)))
                .singleElement()
                .asString()
                .contains("Lease agreement 112");
        assertThat(chat.getItems().getItems())
                .extracting(SimpleAiChatMessage::getContent)
                .last()
                .isEqualTo(ANSWER);
    }

    @Test
    void theAssistantReadsTheSavedVersionWhileANewOneWaitsForOk() {
        DocumentDetailView card = openCard(upload());
        TestUploads.succeed(component(card, "newVersionUpload"), temporaryStorage, "lease-113.txt",
                "Lease agreement 113");
        model.willCallTool("docai_readDocumentText", readCall(card));
        model.willReply(ANSWER);

        ask(card, "summaryButton");

        assertThat(toolResults(model.prompts().get(1)))
                .singleElement()
                .asString()
                .contains("Lease agreement 112")
                .doesNotContain("Lease agreement 113");
        assertThat(previewText(card, "assistantPreview")).isEqualTo("Lease agreement 112");
        assertThat(previewText(card, "preview")).isEqualTo("Lease agreement 113");
    }

    @Test
    void aCardClosedWhileTheModelAnswersDropsTheAnswerQuietly() throws InterruptedException {
        DocumentDetailView card = openCard(upload());
        AiChat<SimpleAiChatMessage> chat = chat(card);
        CountDownLatch answering = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        model.willReply(() -> {
            answering.countDown();
            assertThat(Uninterruptibles.awaitUninterruptibly(closed, 20, TimeUnit.SECONDS)).isTrue();
            return ANSWER;
        });
        openedNotifications.closeOpenedNotifications();
        this.<JmixTabSheet>component(card, "cardTabs").setSelectedTab(component(card, "assistantTab"));

        this.<JmixButton>assistantComponent(card, "summaryButton").click();
        assertThat(answering.await(20, TimeUnit.SECONDS)).isTrue();
        card.removeFromParent();
        closed.countDown();

        assertThat(chat.getState()).isEqualTo(GenerationState.IDLE);
        assertThat(chat.getItems().getItems())
                .extracting(SimpleAiChatMessage::getContent)
                .containsExactly(messages.getMessage(DocumentAssistantFragment.class, "summaryButton.prompt"));
        assertThat(UiTestUtils.getOpenedNotifications()).isEmpty();
    }

    @Test
    void anEmptyAnswerIsReportedAsAFailure() {
        DocumentDetailView card = openCard(upload());
        model.willReply("");

        ask(card, "summaryButton");

        assertThat(lastNotification().getType()).isEqualTo(Notifications.Type.ERROR);
        assertThat(lastNotification().getText())
                .isEqualTo(messages.getMessage(DocumentAssistantFragment.class, "assistantFailed"));
        assertThat(chat(card).getState()).isEqualTo(GenerationState.ERROR);
    }

    @Test
    void aModelThatFailsIsReported() {
        DocumentDetailView card = openCard(upload());

        ask(card, "summaryButton");

        assertThat(lastNotification().getType()).isEqualTo(Notifications.Type.ERROR);
    }

    // the answer comes back through UI.access, which a UI test runs only when asked
    private void ask(DocumentDetailView card, String suggestionId) {
        VaadinSession session = VaadinSession.getCurrent();
        AiChat<SimpleAiChatMessage> chat = chat(card);
        openedNotifications.closeOpenedNotifications();

        this.<JmixTabSheet>component(card, "cardTabs").setSelectedTab(component(card, "assistantTab"));
        this.<JmixButton>assistantComponent(card, suggestionId).click();

        await().pollInSameThread()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> {
                    session.getService().runPendingAccessTasks(session);
                    return chat.getState() != GenerationState.GENERATING;
                });
    }

    private static String readCall(DocumentDetailView card) {
        return "{\"status\": \"Reading the lease\", \"documentId\": \"" + card.getEditedEntity().getId() + "\"}";
    }

    private static List<String> toolResults(Prompt prompt) {
        return prompt.getInstructions()
                .stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(message -> ((ToolResponseMessage) message).getResponses().getFirst().responseData())
                .toList();
    }

    private String previewText(DocumentDetailView card, String previewId) {
        DocumentPreviewFragment preview = component(card, previewId);

        return ((Pre) FragmentUtils.getComponent(preview, "previewText")).getText();
    }

    private NotificationInfo lastNotification() {
        return openedNotifications.getLastNotification();
    }

    private AiChat<SimpleAiChatMessage> chat(DocumentDetailView card) {
        return assistantComponent(card, "chat");
    }

    @SuppressWarnings("unchecked")
    private <T> T assistantComponent(DocumentDetailView card, String id) {
        return (T) FragmentUtils.getComponent(component(card, "assistant"), id);
    }

    private Document upload() {
        return systemAuthenticator.withUser(ADMIN, () -> documentService.upload(sandbox,
                        List.of(TestFiles.file(tempDir, "lease-112.txt", "Lease agreement 112")))
                .created()
                .getFirst());
    }

    private Document stored(Document document) {
        return systemAuthenticator.withUser(ADMIN, () -> dataManager.load(Document.class)
                .id(document.getId())
                .one());
    }

    private DocumentDetailView openCard(Document document) {
        DialogWindow<DocumentDetailView> window = dialogWindows.detail(UiTestUtils.getCurrentView(), Document.class)
                .withViewClass(DocumentDetailView.class)
                .editEntity(document)
                .build();

        window.open();
        return window.getView();
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

    private <T> T component(DocumentDetailView card, String id) {
        return UiTestUtils.getComponent(card, id);
    }
}
