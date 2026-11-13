package com.company.docai.view.main;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.StubAiConfiguration;
import com.company.docai.test_support.StubChatModel;
import com.company.docai.test_support.TestDashboards;
import com.company.docai.test_support.TestFiles;
import com.company.docai.test_support.TestMenus;
import com.company.docai.test_support.TestStatuses;
import com.company.docai.view.dashboard.DashboardFragment;
import com.company.docai.view.document.DocumentDetailView;
import com.vaadin.flow.server.VaadinSession;
import io.jmix.aitoolsflowui.model.AiChatMessage;
import io.jmix.aitoolsflowui.model.AiChatMessageType;
import io.jmix.aitoolsflowui.model.AiConversation;
import io.jmix.aitoolsflowui.service.AiChatMessageService;
import io.jmix.aitoolsflowui.service.AiConversationService;
import io.jmix.aitoolsflowui.view.chat.AiChatFragment;
import io.jmix.aitoolsflowui.view.chat.AiChatView;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.dynattr.model.Category;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.testassist.notification.NotificationInfo;
import io.jmix.flowui.testassist.notification.OpenedNotifications;
import io.jmix.flowui.view.View;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * What the main view does with the dashboard of an employee while the AI is on: a question opens the chat of the
 * assistant with it, and Fill with AI opens the card of the document and fills it, with the model replaced by a stub.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class,
        StubAiConfiguration.class})
@TestPropertySource(properties = "spring.ai.openai.api-key=test-key")
class MainViewDashboardAiTest {

    private static final String ADMIN = "admin";
    private static final String QUESTION = "Which invoices are due this week?";
    private static final String INVOICE = "Invoice 1187 of 1 November 2026 from Harbour Logistics LLC";
    private static final String INVOICE_PROPOSAL = """
            {"type": "Invoice",
             "fields": [{"code": "supplier", "value": "Harbour Logistics LLC"},
                        {"code": "invoiceNumber", "value": "1187"}],
             "documentDate": "2026-11-01"}""";

    @Autowired
    private AiConversationService conversationService;

    @Autowired
    private AiChatMessageService messageService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private OpenedNotifications openedNotifications;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private StubChatModel model;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void setUp() {
        model.reset();

        Folder folder = dataManager.create(Folder.class);
        folder.setParent(folderService.getRoot());
        folder.setName("main-view-dashboard-ai-test-" + UUID.randomUUID());
        sandbox = folderService.save(folder);
    }

    @AfterEach
    void tearDown() {
        // the answer lands before its conversation goes, as in the other tests of the chat
        if (UiTestUtils.getCurrentView() instanceof AiChatView chat) {
            awaitAnswer(chat);
        }

        conversationService.loadConversations().forEach(conversationService::remove);

        dataManager.load(Id.of(sandbox))
                .optional()
                .ifPresent(folderService::delete);
    }

    @Test
    void aQuestionOpensTheAssistantWithItAsTheFirstMessage() {
        Set<UUID> before = conversationIds();
        DashboardFragment dashboard = dashboard();
        model.willReply("Two invoices are due this week.");

        TestDashboards.<TypedTextField<String>>component(dashboard, "questionField").setValue(QUESTION);
        TestDashboards.<JmixButton>component(dashboard, "askButton").click();

        assertThat(UiTestUtils.<View<?>>getCurrentView()).isInstanceOf(AiChatView.class);
        assertThat(firstQuestions(before)).containsExactly(QUESTION);
    }

    @Test
    void fillWithAiOpensTheCardOfTheDocumentAndFillsItFromItsFile() {
        Document invoice = upload();
        DashboardFragment dashboard = dashboard();
        dashboard.refresh();
        model.willReply(INVOICE_PROPOSAL);
        openedNotifications.closeOpenedNotifications();

        TestDashboards.buttons(TestDashboards.row(dashboard, "untypedBox", invoice.getName()))
                .getFirst()
                .click();

        waitForNotification();
        DocumentDetailView card = UiTestUtils.getLastOpenedViewDialog();
        assertThat(card).isNotNull();

        NotificationInfo notice = openedNotifications.getLastNotification();
        Category type = card.getEditedEntity().getCategory();

        assertThat(card.getEditedEntity()).isEqualTo(invoice);
        assertThat(notice).isNotNull();
        assertThat(notice.getType()).isEqualTo(Notifications.Type.SUCCESS);
        assertThat(type).isNotNull();
        assertThat(type.getName()).isEqualTo("Invoice");
        assertThat(model.prompts().getFirst().getUserMessage().getText()).contains(INVOICE);
    }

    @Test
    void fillWithAiOfADocumentArchivedSinceTheDashboardShowedOpensTheCardWithoutFilling() {
        Document invoice = upload();
        DashboardFragment dashboard = dashboard();
        dashboard.refresh();

        // another person archives the document, which locks it, while the dashboard still offers to fill it
        systemAuthenticator.runWithUser(ADMIN, () -> {
            documentService.changeStatus(List.of(invoice), TestStatuses.active(dataManager));
            documentService.changeStatus(List.of(invoice), TestStatuses.archived(dataManager));
        });

        openedNotifications.closeOpenedNotifications();

        TestDashboards.buttons(TestDashboards.row(dashboard, "untypedBox", invoice.getName()))
                .getFirst()
                .click();

        DocumentDetailView card = UiTestUtils.getLastOpenedViewDialog();

        assertThat(card).isNotNull();
        assertThat(card.getEditedEntity()).isEqualTo(invoice);
        assertThat(UiTestUtils.getOpenedDialogs()).isEmpty();
        assertThat(openedNotifications.getNotifications()).isEmpty();
        assertThat(model.prompts()).isEmpty();
    }

    private static DashboardFragment dashboard() {
        return UiTestUtils.getComponent(TestMenus.mainView(), "dashboard");
    }

    // the invoice under a name of its own, so that a row of another test with the same file name is never taken for it
    private Document upload() {
        return documentService.upload(sandbox, List.of(TestFiles.file(tempDir, UUID.randomUUID() + "-invoice.txt",
                        INVOICE.getBytes(StandardCharsets.UTF_8))))
                .created()
                .getFirst();
    }

    private Set<UUID> conversationIds() {
        return conversationService.loadConversations()
                .stream()
                .map(AiConversation::getId)
                .collect(Collectors.toSet());
    }

    // the first message the person sent in each conversation that began after the given ones
    private List<String> firstQuestions(Set<UUID> before) {
        return conversationService.loadConversations()
                .stream()
                .filter(conversation -> !before.contains(conversation.getId()))
                .map(conversation -> messageService.loadLatestMessage(conversation, AiChatMessageType.USER))
                .filter(Objects::nonNull)
                .map(AiChatMessage::getContent)
                .toList();
    }

    // the result of Fill with AI comes back through UI.access, which a UI test runs only when asked
    private void waitForNotification() {
        VaadinSession session = VaadinSession.getCurrent();

        await().pollInSameThread()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> {
                    session.getService().runPendingAccessTasks(session);

                    return !openedNotifications.getNotifications().isEmpty();
                });
    }

    // the answer of the stub comes back through UI.access as well
    private static void awaitAnswer(View<?> chat) {
        AiChatFragment fragment = UiTestUtils.getComponent(chat, "chatFragment");
        VaadinSession session = VaadinSession.getCurrent();

        await().pollInSameThread()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> {
                    session.getService().runPendingAccessTasks(session);

                    return !fragment.isAwaitingResponse();
                });
    }
}
