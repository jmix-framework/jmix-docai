package com.company.docai.view.search;

import com.company.docai.DocAiApplication;
import com.company.docai.model.folder.Folder;
import com.company.docai.search.semantic.DocumentVectorIndexer;
import com.company.docai.search.semantic.SemanticSearch;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.StubAiConfiguration;
import com.company.docai.test_support.TestMenus;
import com.company.docai.test_support.TestPalettes;
import com.company.docai.view.main.MainView;
import com.company.docai.view.repository.RepositoryView;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.internal.PendingJavaScriptInvocation;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.server.VaadinSession;
import io.jmix.aitoolsflowui.model.AiChatMessage;
import io.jmix.aitoolsflowui.model.AiChatMessageType;
import io.jmix.aitoolsflowui.model.AiConversation;
import io.jmix.aitoolsflowui.service.AiChatMessageService;
import io.jmix.aitoolsflowui.service.AiConversationService;
import io.jmix.aitoolsflowui.view.chat.AiChatFragment;
import io.jmix.aitoolsflowui.view.chat.AiChatView;
import io.jmix.core.Messages;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.kit.icon.JmixFontIcon;
import io.jmix.flowui.settings.UserSettingsCache;
import io.jmix.flowui.settings.UserSettingsService;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.testassist.dialog.DialogInfo;
import io.jmix.flowui.view.View;
import io.jmix.flowui.view.navigation.RouteSupport;
import io.jmix.search.searching.EntitySearcher;
import io.jmix.search.searching.SearchResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.node.StringNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The search palette as an employee meets it with the AI on: the wand of the line, the questions for the assistant
 * and the rows that open the search by meaning and the chat. The search by meaning, the indexer and the index are
 * replaced; the context is the one of AiSearchTest.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class,
        StubAiConfiguration.class})
@TestPropertySource(properties = "spring.ai.openai.api-key=test-key")
class SearchPaletteAiTest {

    private static final String QUERY = "tractor";

    @MockitoBean
    private SemanticSearch semanticSearch;

    @MockitoBean
    private DocumentVectorIndexer vectorIndexer;

    @MockitoBean
    private EntitySearcher entitySearcher;

    @Autowired
    private UserSettingsService userSettingsService;

    @Autowired
    private Messages messages;

    @Autowired
    private RecentQueries recentQueries;

    @Autowired
    private AiConversationService conversationService;

    @Autowired
    private AiChatMessageService messageService;

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private RouteSupport routeSupport;

    @Autowired
    private UserSettingsCache userSettingsCache;

    @BeforeEach
    void setUp() {
        SearchResult result = mock(SearchResult.class);
        when(result.getAllEntries()).thenReturn(List.of());
        when(entitySearcher.search(any(), anyString())).thenReturn(result);
        when(semanticSearch.search(anyString())).thenReturn(List.of());
        userSettingsService.delete(RecentQueries.RECENT_QUERIES_SETTING);
    }

    @AfterEach
    void tearDown() {
        // the answer lands before its conversation goes, as in the other tests of the chat
        if (UiTestUtils.getCurrentView() instanceof AiChatView chat) {
            awaitAnswer(chat);
        }

        conversationService.loadConversations().forEach(conversationService::remove);
        userSettingsService.delete(MainView.SEARCH_MODE_SETTING);
        userSettingsService.delete(RecentQueries.RECENT_QUERIES_SETTING);
        // a view saves the settings of its fragments for the user as it detaches, the main view at the sign-out
        userSettingsCache.delete("MainView.folderTree");
        userSettingsCache.delete("RepositoryView.documentList");
    }

    @Test
    void enterInAiModeOpensTheSearchByMeaning() {
        headerWand().click();
        SearchPaletteView palette = TestPalettes.open();

        assertThat(pressed(wand(palette))).isTrue();
        assertThat(TestPalettes.queryField(palette).getPlaceholder())
                .isEqualTo(messages.getMessage(MainView.class, "searchField.aiPlaceholder"));

        TestPalettes.enter(palette, QUERY);

        assertThat(UiTestUtils.<View<?>>getCurrentView()).isInstanceOf(DocumentSearchView.class);
        assertThat(DocumentSearchView.isAiMode(TestPalettes.currentQueryParameters())).isTrue();
        verify(semanticSearch, timeout(5_000)).search(QUERY);
    }

    @Test
    void theWandOfThePaletteSwitchesTheModeOfTheHeaderAndSavesIt() {
        SearchPaletteView palette = TestPalettes.open();
        JmixButton wand = wand(palette);

        assertThat(wand.isVisible()).isTrue();
        assertThat(pressed(wand)).isFalse();

        wand.click();

        assertThat(pressed(wand)).isTrue();
        assertThat(pressed(headerWand())).isTrue();
        assertThat(userSettingsService.load(MainView.SEARCH_MODE_SETTING)).contains(DocumentSearchView.AI_MODE);
        assertThat(TestPalettes.isOpen()).isTrue();
    }

    @Test
    void beforeTypingThePaletteShowsTheWandAndTwoQuestionsForTheAssistant() {
        SearchPaletteView palette = TestPalettes.open();
        List<Div> rows = TestPalettes.rows(palette);

        assertThat(wand(palette).isVisible()).isTrue();
        assertThat(TestPalettes.headings(palette)).last()
                .isEqualTo(messages.getMessage(SearchPaletteView.class, "askAiSection.title"));
        assertThat(TestPalettes.names(palette)).endsWith(question(1), question(2));
        assertThat(rows.subList(rows.size() - 2, rows.size())).allSatisfy(row -> {
            assertThat(TestPalettes.iconClass(row)).isEqualTo(TestPalettes.iconClass(JmixFontIcon.COMMENTS));
            assertThat(TestPalettes.wearsAiAccent(row)).isTrue();
        });
    }

    @Test
    void aQuestionOpensTheAssistantWithItAsTheFirstMessage() {
        Set<UUID> before = conversationIds();

        TestPalettes.click(TestPalettes.open(), question(1));

        assertThat(UiTestUtils.<View<?>>getCurrentView()).isInstanceOf(AiChatView.class);
        assertThat(firstQuestions(before)).containsExactly(question(1));
    }

    @Test
    void twoClicksOnAQuestionGiveOneNewConversation() {
        Set<UUID> before = conversationIds();
        SearchPaletteView palette = TestPalettes.open();

        // over a slow network the browser sends the second click after the response that closed the palette
        TestPalettes.click(palette, question(1));
        TestPalettes.click(palette, question(1));

        assertThat(firstQuestions(before)).containsExactly(question(1));
    }

    @Test
    void aQuestionWhileAChatIsOpenGoesToANewConversationOfItsOwn() {
        Set<UUID> before = conversationIds();
        TestPalettes.click(TestPalettes.open(), question(1));
        View<?> firstChat = UiTestUtils.getCurrentView();
        // a chat that is still answering asks the person to stay before it lets them go
        awaitAnswer(firstChat);

        TestPalettes.click(TestPalettes.open(), question(2));

        assertThat(UiTestUtils.<View<?>>getCurrentView()).isInstanceOf(AiChatView.class).isNotSameAs(firstChat);
        assertThat(firstQuestions(before)).containsExactlyInAnyOrder(question(1), question(2));
    }

    @Test
    void leavingAChatThatIsStillAnsweringSendsTheQuestionOnceToTheNewChat() {
        Set<UUID> before = conversationIds();
        TestPalettes.click(TestPalettes.open(), question(1));
        View<?> firstChat = UiTestUtils.getCurrentView();

        // the stub has not answered yet, so the chat asks whether to leave
        TestPalettes.click(TestPalettes.open(), question(2));
        answerLeaveDialog("leave");

        assertThat(UiTestUtils.<View<?>>getCurrentView()).isInstanceOf(AiChatView.class).isNotSameAs(firstChat);
        assertThat(firstQuestions(before)).containsExactlyInAnyOrder(question(1), question(2));
        assertThat(allQuestions(before)).hasSize(2);
    }

    @Test
    void stayingInAChatThatIsStillAnsweringSendsTheQuestionNowhere() {
        Set<UUID> before = conversationIds();
        // a chat opened from the history, with a message of its own and no question from the palette
        AiChatView chat = openChat(conversationService.create());
        UiTestUtils.<AiChatFragment>getComponent(chat, "chatFragment").sendMessage(QUERY);

        TestPalettes.click(TestPalettes.open(), question(2));
        answerLeaveDialog("stay");

        assertThat(UiTestUtils.<View<?>>getCurrentView()).isSameAs(chat);

        // the way out the person called off leaves its handler on the chat, which runs with the chat itself when the
        // chat goes while it is shown: at the sign-out, when the session lets the page go
        awaitAnswer(chat);
        VaadinSession.getCurrent().removeUI(UI.getCurrent());

        // the conversation begun for the question stays empty, and the chat keeps only its own message
        assertThat(allQuestions(before)).containsExactly(QUERY);
    }

    @Test
    void aRecentAiQueryOpensTheSearchByMeaning() {
        recentQueries.add(QUERY, true);
        SearchPaletteView palette = TestPalettes.open();
        Div row = TestPalettes.row(palette, QUERY);

        assertThat(TestPalettes.iconClass(row)).isEqualTo(TestPalettes.iconClass(JmixFontIcon.MAGIC));
        assertThat(TestPalettes.wearsAiAccent(row)).isTrue();

        TestPalettes.click(palette, QUERY);

        assertThat(DocumentSearchView.isAiMode(TestPalettes.currentQueryParameters())).isTrue();
        verify(semanticSearch, timeout(5_000)).search(QUERY);
    }

    @Test
    void questionsToTheAssistantAreNotRemembered() {
        TestPalettes.click(TestPalettes.open(), question(1));

        assertThat(recentQueries.load()).isEmpty();
    }

    @Test
    void theAskAiRowOpensTheAssistantWithTheTextAsTheFirstMessage() {
        Set<UUID> before = conversationIds();
        SearchPaletteView palette = TestPalettes.open();
        TestPalettes.type(palette, QUERY);
        String askAi = messages.formatMessage(SearchPaletteView.class, "askAiRow.text", QUERY);

        assertThat(TestPalettes.names(palette)).last().isEqualTo(askAi);

        TestPalettes.click(palette, askAi);

        assertThat(UiTestUtils.<View<?>>getCurrentView()).isInstanceOf(AiChatView.class);
        assertThat(firstQuestions(before)).containsExactly(QUERY);
    }

    @Test
    void theAllResultsRowFollowsTheModeOfTheWand() {
        SearchPaletteView palette = TestPalettes.open();
        TestPalettes.type(palette, QUERY);
        String allResults = messages.formatMessage(SearchPaletteView.class, "allResultsRow.text", QUERY);

        assertThat(TestPalettes.iconClass(TestPalettes.row(palette, allResults)))
                .isEqualTo(TestPalettes.iconClass(JmixFontIcon.SEARCH));

        wand(palette).click();
        Div row = TestPalettes.row(palette, allResults);

        assertThat(TestPalettes.iconClass(row)).isEqualTo(TestPalettes.iconClass(JmixFontIcon.MAGIC));
        assertThat(TestPalettes.wearsAiAccent(row)).isTrue();

        TestPalettes.click(palette, allResults);

        assertThat(DocumentSearchView.isAiMode(TestPalettes.currentQueryParameters())).isTrue();
        verify(semanticSearch, timeout(5_000)).search(QUERY);
    }

    @Test
    void typingNeitherSearchesByMeaningNorStartsAConversation() {
        Set<UUID> before = conversationIds();
        // even in the mode of the search by meaning
        headerWand().click();
        SearchPaletteView palette = TestPalettes.open();

        TestPalettes.type(palette, "tra");
        TestPalettes.type(palette, QUERY);

        verify(semanticSearch, never()).search(anyString());
        assertThat(conversationIds()).isEqualTo(before);
    }

    @Test
    void aClickOnAChipOrOnTheWandGivesTheLineTheFocusBackWithTheCaretAfterTheText() {
        SearchPaletteView palette = TestPalettes.open();
        TestPalettes.type(palette, QUERY);
        TestPalettes.sentScripts();

        UiTestUtils.<JmixButton>getComponent(palette, "documentsChip").click();

        assertThat(TestPalettes.focusesTheLineAtTheEnd(palette)).isTrue();

        wand(palette).click();

        assertThat(TestPalettes.focusesTheLineAtTheEnd(palette)).isTrue();
    }

    @Test
    void aChatOpenedFromThePaletteKeepsNoQueryOfThePageItWasOpenedOn() {
        // the main view alone, which a link with a query of its own may open
        assertThat(lastAddressAfterAQuestion("?ref=mail"))
                .startsWith("aitls/chat/")
                .doesNotContain("?");

        // a chat that is still answering asks the person to stay before it lets them go
        awaitAnswer(UiTestUtils.getCurrentView());

        UI.getCurrent().navigate(DocumentSearchView.class,
                QueryParameters.of(DocumentSearchView.QUERY_PARAMETER, QUERY));

        assertThat(lastAddressAfterAQuestion("search?q=" + QUERY))
                .startsWith("aitls/chat/")
                .doesNotContain("?");

        awaitAnswer(UiTestUtils.getCurrentView());

        viewNavigators.view(UiTestUtils.getCurrentView(), RepositoryView.class)
                .withQueryParameters(QueryParameters.of(RepositoryView.FOLDER_PARAMETER, Folder.ROOT_ID.toString()))
                .navigate();

        assertThat(lastAddressAfterAQuestion("repository?folder=" + Folder.ROOT_ID))
                .startsWith("aitls/chat/")
                .doesNotContain("?");
    }

    private static JmixButton wand(SearchPaletteView palette) {
        return UiTestUtils.getComponent(palette, "searchModeButton");
    }

    private static JmixButton headerWand() {
        return UiTestUtils.getComponent(TestMenus.mainView(), "searchModeButton");
    }

    private static boolean pressed(JmixButton button) {
        return "true".equals(button.getElement().getAttribute("aria-pressed"));
    }

    // the address the browser shows last after a question from the palette on the page with the given address. While
    // the chat opens the browser still shows that page, and it moves on to the chat only after the response, so it
    // answers every request for its address with the address of the page.
    private String lastAddressAfterAQuestion(String pageAddress) {
        SearchPaletteView palette = TestPalettes.open();
        // the palette opens with the words of the search page, and the questions come back once they are deleted
        TestPalettes.type(palette, "");
        TestPalettes.sentScripts();

        TestPalettes.click(palette, question(1));
        List<PendingJavaScriptInvocation> scripts = new ArrayList<>(TestPalettes.sentScripts());

        scripts.stream()
                .filter(script -> script.getInvocation().getExpression().contains("window.location.href"))
                .forEach(script -> script.complete(StringNode.valueOf("http://localhost/docai/" + pageAddress)));

        scripts.addAll(TestPalettes.sentScripts());

        // the router of the page takes each new address from an event, or the history of the browser takes it
        return scripts.stream()
                .map(PendingJavaScriptInvocation::getInvocation)
                .filter(invocation -> invocation.getExpression().contains("new CustomEvent('vaadin-navigate'")
                        || invocation.getExpression().contains("history.pushState(")
                        || invocation.getExpression().contains("history.replaceState("))
                .map(invocation -> String.valueOf(invocation.getParameters().get(1)))
                .reduce((first, last) -> last)
                .orElseThrow();
    }

    private String question(int number) {
        return messages.getMessage(SearchPaletteView.class, "aiQuestion" + number + ".text");
    }

    private Set<UUID> conversationIds() {
        return conversationService.loadConversations()
                .stream()
                .map(AiConversation::getId)
                .collect(Collectors.toSet());
    }

    // the first message the person sent in each conversation that began after the given ones; null where none was
    private List<String> firstQuestions(Set<UUID> before) {
        return conversationService.loadConversations()
                .stream()
                .filter(conversation -> !before.contains(conversation.getId()))
                .map(conversation -> messageService.loadLatestMessage(conversation, AiChatMessageType.USER))
                .map(message -> message == null ? null : message.getContent())
                .toList();
    }

    // every message the person sent in the conversations that began after the given ones
    private List<String> allQuestions(Set<UUID> before) {
        return conversationService.loadConversations()
                .stream()
                .filter(conversation -> !before.contains(conversation.getId()))
                .flatMap(conversation -> messageService.loadMessages(conversation).stream())
                .filter(message -> message.getType() == AiChatMessageType.USER)
                .map(AiChatMessage::getContent)
                .toList();
    }

    // as from the history of the hub: the chat has no question of its own to send
    private AiChatView openChat(AiConversation conversation) {
        viewNavigators.view(UiTestUtils.getCurrentView(), AiChatView.class)
                .withRouteParameters(routeSupport.createRouteParameters(AiChatView.ROUTE_PARAM_ID,
                        conversation.getId()))
                .navigate();

        return UiTestUtils.getCurrentView();
    }

    // a chat that is still answering asks before it lets the person go
    private void answerLeaveDialog(String answer) {
        String text = messages.getMessage("io.jmix.aitoolsflowui.view.chat/aiChatView.leaveWhileResponding." + answer);
        DialogInfo dialog = UiTestUtils.getLastOpenedDialog();
        assertThat(dialog).isNotNull();

        dialog.getButtons()
                .stream()
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow()
                .click();
    }

    // the answer of the stub comes back through UI.access, which a UI test runs only when asked
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
