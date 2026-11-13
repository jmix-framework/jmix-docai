package com.company.docai.view.dashboard;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.discussion.DocumentDiscussionService;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.StubAiConfiguration;
import com.company.docai.test_support.TestAccess;
import com.company.docai.test_support.TestAccess.Grant;
import com.company.docai.test_support.TestDashboards;
import com.company.docai.test_support.TestFiles;
import com.company.docai.test_support.TestMenus;
import com.company.docai.test_support.TestPalettes;
import com.company.docai.test_support.TestStatuses;
import com.company.docai.view.main.MainView;
import com.company.docai.view.search.SearchPaletteView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import io.jmix.aitoolsflowui.model.AiChatMessageType;
import io.jmix.aitoolsflowui.model.AiConversation;
import io.jmix.aitoolsflowui.service.AiChatMessageService;
import io.jmix.aitoolsflowui.service.AiConversationService;
import io.jmix.aitoolsflowui.view.chat.AiChatView;
import io.jmix.aitoolsflowui.view.chathub.AiChatHubView;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.Messages;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.Fragments;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.kit.icon.JmixFontIcon;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.view.View;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parts of the AI on the dashboard of an employee while the AI is on: the question and the ready questions for
 * the assistant, the own recent chats and Fill with AI for the documents without a type. The models are stubs, and
 * nothing here asks them: the dashboard only reads the chats and hands the questions over.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class,
        StubAiConfiguration.class})
@TestPropertySource(properties = "spring.ai.openai.api-key=test-key")
class DashboardFragmentAiTest {

    private static final String EMPLOYEE = "peter";
    private static final String ADMIN = "admin";

    @Autowired
    private Fragments fragments;

    @Autowired
    private AiConversationService conversationService;

    @Autowired
    private AiChatMessageService messageService;

    @Autowired
    private DocumentDiscussionService discussionService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private Messages messages;

    @TempDir
    private Path tempDir;

    private Folder sandbox;
    private DashboardFragment dashboard;
    private final List<String> questions = new ArrayList<>();
    private final List<Document> filled = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(folderService.getRoot());
        folder.setName("dashboard-fragment-ai-test-" + UUID.randomUUID());
        sandbox = folderService.save(folder);

        // the chats of the employee that other tests left would take the places of those of the test
        conversationService.loadConversations().forEach(conversationService::remove);

        dashboard = fragments.create(TestMenus.mainView(), DashboardFragment.class);
        dashboard.setAssistantHandler(questions::add);
        dashboard.setFillWithAiHandler(filled::add);
    }

    @AfterEach
    void tearDown() {
        conversationService.loadConversations().forEach(conversationService::remove);

        // the employee may not delete a folder that holds a document he only views
        systemAuthenticator.runWithUser(ADMIN, () -> dataManager.load(Id.of(sandbox))
                .optional()
                .ifPresent(folderService::delete));
    }

    @Test
    void theQuestionGoesToTheAssistantWithoutTheSpacesAroundItAndAnEmptyOneGoesNowhere() {
        dashboard.refresh();
        TypedTextField<String> field = TestDashboards.component(dashboard, "questionField");
        JmixButton askButton = TestDashboards.component(dashboard, "askButton");

        assertThat(TestDashboards.<VerticalLayout>component(dashboard, "askBox").isVisible()).isTrue();

        field.setValue("   ");
        askButton.click();

        assertThat(questions).isEmpty();

        field.setValue("  Which contracts expire this year?  ");
        askButton.click();

        assertThat(questions).containsExactly("Which contracts expire this year?");
        assertThat(field.getValue()).isEmpty();
    }

    @Test
    void theReadyQuestionsAreTheTwoOfThePaletteAndOneAboutTheWeek() {
        dashboard.refresh();

        for (String id : List.of("question1Button", "question2Button", "question3Button")) {
            TestDashboards.<JmixButton>component(dashboard, id).click();
        }

        assertThat(questions).containsExactly(
                messages.getMessage(SearchPaletteView.class, "aiQuestion1.text"),
                messages.getMessage(SearchPaletteView.class, "aiQuestion2.text"),
                messages.getMessage(DashboardFragment.class, "question3Button.text"));
    }

    @Test
    void withoutChatsTheBlockAsksForAQuestion() {
        dashboard.refresh();

        assertThat(TestDashboards.rows(dashboard, "chatsBox")).isEmpty();

        assertThat(TestDashboards.text(TestDashboards.component(dashboard, "chatsBox")))
                .isEqualTo(messages.getMessage(DashboardFragment.class, "chatsEmpty.text"));
    }

    @Test
    void theOwnChatsWithMessagesShowAndADiscussionWearsTheIconOfADocument() {
        AiConversation plain = chat("Invoices of the month");
        AiConversation discussion = chat("The lease in short");
        discussionService.saveSelection(discussion.getId(), List.of(upload("lease.txt")));
        // a question given up on the way to its chat leaves a conversation without messages
        conversationService.create();

        dashboard.refresh();

        assertThat(TestDashboards.rows(dashboard, "chatsBox"))
                .extracting(DashboardFragmentAiTest::title)
                .containsExactlyInAnyOrder(plain.getTitle(), discussion.getTitle());

        assertThat(iconClass(TestDashboards.row(dashboard, "chatsBox", plain.getTitle())))
                .isEqualTo(TestPalettes.iconClass(JmixFontIcon.COMMENTS));

        assertThat(iconClass(TestDashboards.row(dashboard, "chatsBox", discussion.getTitle())))
                .isEqualTo(TestPalettes.iconClass(JmixFontIcon.FILE_TEXT));
    }

    @Test
    void aChatOpensWhereItStoppedAndAllChatsOpenTheAssistant() {
        AiConversation conversation = chat("Invoices of the month");

        dashboard.refresh();
        TestDashboards.click(TestDashboards.row(dashboard, "chatsBox", conversation.getTitle()));

        assertThat(UiTestUtils.<View<?>>getCurrentView()).isInstanceOf(AiChatView.class);

        assertThat(UI.getCurrent().getInternals().getActiveViewLocation().getPath())
                .isEqualTo("aitls/chat/" + conversation.getId());

        TestDashboards.<JmixButton>component(dashboard, "allChatsButton").click();

        assertThat(UiTestUtils.<View<?>>getCurrentView()).isInstanceOf(AiChatHubView.class);
    }

    @Test
    void fillWithAiIsOfferedWhereTheCardWouldOfferIt() {
        Document editable = upload("editable.txt");

        Document viewed = systemAuthenticator.withUser(ADMIN, () -> TestAccess.giveOwnAccess(dataManager,
                upload("viewed.txt"), Grant.user(EMPLOYEE, AccessLevel.VIEW)));

        Document archived = upload("archived.txt");

        systemAuthenticator.runWithUser(ADMIN, () -> {
            documentService.changeStatus(List.of(archived), TestStatuses.active(dataManager));
            documentService.changeStatus(List.of(archived), TestStatuses.archived(dataManager));
        });

        dashboard.refresh();

        List<JmixButton> buttons = TestDashboards.buttons(TestDashboards.row(dashboard, "untypedBox",
                editable.getName()));

        assertThat(buttons).singleElement()
                .satisfies(button -> assertThat(button.hasClassName("aura-accent-purple")).isTrue())
                .satisfies(button -> assertThat(button.getAriaLabel().orElseThrow()).contains(editable.getName()));

        assertThat(TestDashboards.buttons(TestDashboards.row(dashboard, "untypedBox", viewed.getName()))).isEmpty();
        assertThat(TestDashboards.buttons(TestDashboards.row(dashboard, "untypedBox", archived.getName()))).isEmpty();

        buttons.getFirst().click();

        assertThat(filled).containsExactly(editable);
    }

    // the chat of the employee with a title and one question
    private AiConversation chat(String title) {
        AiConversation conversation = conversationService.create();
        conversation.setTitle(title);
        AiConversation saved = conversationService.save(conversation);
        messageService.createMessage(saved, AiChatMessageType.USER, title + "?");

        return saved;
    }

    // a name of its own, so that a row of another test with the same file name is never taken for it
    private Document upload(String fileName) {
        return documentService.upload(sandbox, List.of(TestFiles.file(tempDir, UUID.randomUUID() + "-" + fileName,
                        "A document of the test")))
                .created()
                .getFirst();
    }

    // the title of a chat in its row, without the time after it
    private static String title(Div row) {
        Component title = TestDashboards.parts(row)
                .get(1);

        return TestDashboards.text(title);
    }

    // the icon class of the icon a row starts with
    private static String iconClass(Div row) {
        Component icon = TestDashboards.parts(row)
                .getFirst();

        return icon.getElement().getProperty("iconClass");
    }
}
