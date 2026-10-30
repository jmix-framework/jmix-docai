package com.company.docai.view.search;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.search.DocumentSearch;
import com.company.docai.search.DocumentSearchHit;
import com.company.docai.search.SearchUnavailableException;
import com.company.docai.search.semantic.DocumentVectorIndexer;
import com.company.docai.search.semantic.SemanticSearch;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.StubAiConfiguration;
import com.company.docai.test_support.TestFiles;
import com.company.docai.view.main.MainView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.KeyPressEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.server.VaadinSession;
import io.jmix.core.DataManager;
import io.jmix.core.Messages;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.Views;
import io.jmix.flowui.component.UiComponentUtils;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.settings.UserSettingsService;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.view.View;
import io.jmix.search.searching.EntitySearcher;
import io.jmix.search.searching.SearchResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

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
 * The AI search as an employee meets it with the AI on: the mode in the header field and the search page in that
 * mode. The search by meaning and the indexer are replaced: the search answers with the documents of the test.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class,
        StubAiConfiguration.class})
@TestPropertySource(properties = "spring.ai.openai.api-key=test-key")
class AiSearchTest {

    private static final String ADMIN = "admin";
    private static final String QUERY = "tractor";

    @MockitoBean
    private SemanticSearch semanticSearch;

    @MockitoBean
    private DocumentVectorIndexer vectorIndexer;

    @MockitoBean
    private EntitySearcher entitySearcher;

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private Views views;

    @Autowired
    private UserSettingsService userSettingsService;

    @Autowired
    private Messages messages;

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
        SearchResult result = mock(SearchResult.class);
        when(result.getAllEntries()).thenReturn(List.of());
        when(entitySearcher.search(any(), anyString())).thenReturn(result);
        when(semanticSearch.search(anyString())).thenReturn(List.of());

        sandbox = systemAuthenticator.withUser(ADMIN, () -> {
            Folder folder = dataManager.create(Folder.class);
            folder.setParent(folderService.getRoot());
            folder.setName("ai-search-test-" + UUID.randomUUID());
            return folderService.save(folder);
        });
    }

    @AfterEach
    void tearDown() {
        userSettingsService.delete(MainView.SEARCH_MODE_SETTING);
        systemAuthenticator.runWithUser(ADMIN, () -> folderService.delete(sandbox));
    }

    @Test
    void theSearchIsByWordsAtFirstAndTheChosenModeIsRemembered() {
        JmixButton mode = modeButton(mainView());

        assertThat(mode.isVisible()).isTrue();
        assertThat(pressed(mode)).isFalse();
        assertThat(mode.getTooltip().getText())
                .isEqualTo(messages.getMessage(MainView.class, "searchModeButton.off"));

        mode.click();

        assertThat(pressed(mode)).isTrue();
        assertThat(mode.getTooltip().getText())
                .isEqualTo(messages.getMessage(MainView.class, "searchModeButton.on"));
        assertThat(userSettingsService.load(MainView.SEARCH_MODE_SETTING)).contains(DocumentSearchView.AI_MODE);
        // what the person sees on the next visit
        assertThat(pressed(modeButton(views.create(MainView.class)))).isTrue();
    }

    @Test
    void enterInTheAiModeOpensTheSearchByMeaning() {
        modeButton(mainView()).click();

        enter("  tractor  ");

        View<?> current = UiTestUtils.getCurrentView();
        assertThat(current).isInstanceOf(DocumentSearchView.class);
        assertThat(DocumentSearchView.queryOf(currentQueryParameters())).isEqualTo(QUERY);
        assertThat(DocumentSearchView.isAiMode(currentQueryParameters())).isTrue();
        verify(semanticSearch, timeout(5_000)).search(QUERY);
    }

    @Test
    void aClickOnTheSearchPageOnlySwitchesTheModeAndEnterSearchesInIt() {
        enter(QUERY);
        assertThat(DocumentSearchView.isAiMode(currentQueryParameters())).isFalse();

        modeButton(mainView()).click();

        assertThat(pressed(modeButton(mainView()))).isTrue();
        assertThat(DocumentSearchView.isAiMode(currentQueryParameters())).isFalse();
        verify(semanticSearch, never()).search(anyString());

        enter(QUERY);

        assertThat(DocumentSearchView.queryOf(currentQueryParameters())).isEqualTo(QUERY);
        assertThat(DocumentSearchView.isAiMode(currentQueryParameters())).isTrue();
        verify(semanticSearch, timeout(5_000)).search(QUERY);
    }

    @Test
    void aLinkToTheAiSearchSwitchesTheField() {
        openAiSearch();

        assertThat(pressed(modeButton(mainView()))).isTrue();
        assertThat(userSettingsService.load(MainView.SEARCH_MODE_SETTING)).contains(DocumentSearchView.AI_MODE);
    }

    @Test
    void theFoundDocumentsAreCountedAndTheirQuoteIsMarked() {
        Document document = upload();
        when(semanticSearch.search(QUERY)).thenReturn(List.of(hit(document, "the <b>MTZ-82 tractor</b> for")));

        DocumentSearchView view = openAiSearch();
        waitUntil(() -> found(view).isVisible());

        assertThat(found(view).getText())
                .isEqualTo(messages.formatMessage(DocumentSearchView.class, "foundCount", 1));
        assertThat(rows(view)).hasSize(1);
        assertThat(match(rows(view).getFirst()).getText())
                .isEqualTo("MTZ-82 tractor");
        assertThat(UiTestUtils.<JmixButton>getComponent(view, "showMoreButton").isVisible()).isFalse();
        assertThat(message(view).isVisible()).isFalse();
    }

    @Test
    void nothingFoundOffersTheSearchByWords() {
        DocumentSearchView view = openAiSearch();
        waitUntil(() -> searchByWords(view).isVisible());

        assertThat(message(view).getText())
                .isEqualTo(messages.getMessage(DocumentSearchView.class, "nothingFoundByMeaning"));
        assertThat(found(view).isVisible()).isFalse();

        searchByWords(view).click();

        assertThat(DocumentSearchView.queryOf(currentQueryParameters())).isEqualTo(QUERY);
        assertThat(DocumentSearchView.isAiMode(currentQueryParameters())).isFalse();
        assertThat(pressed(modeButton(mainView()))).isFalse();
        verify(entitySearcher).search(any(), anyString());
    }

    @Test
    void aSearchByMeaningThatFailsIsSaidAndTheWordsAreOffered() {
        when(semanticSearch.search(QUERY)).thenThrow(new IllegalStateException("The model did not answer"));

        DocumentSearchView view = openAiSearch();
        waitUntil(() -> searchByWords(view).isVisible());

        assertThat(message(view).getText()).isEqualTo(messages.getMessage(DocumentSearchView.class, "aiSearchFailed"));
    }

    @Test
    void anIndexThatDoesNotAnswerIsSaidAsForTheSearchByWords() {
        when(semanticSearch.search(QUERY))
                .thenThrow(new SearchUnavailableException(new IllegalStateException("No index")));

        DocumentSearchView view = openAiSearch();
        waitUntil(() -> message(view).getText()
                .equals(messages.getMessage(DocumentSearchView.class, "searchUnavailable")));

        assertThat(searchByWords(view).isVisible()).isFalse();
    }

    @Test
    void anIndexStillFillingIsNoted() {
        when(vectorIndexer.isBusy()).thenReturn(true);

        DocumentSearchView view = openAiSearch();
        waitUntil(() -> searchByWords(view).isVisible());

        assertThat(UiTestUtils.<Span>getComponent(view, "noteText").getText())
                .isEqualTo(messages.getMessage(DocumentSearchView.class, "indexFilling"));
    }

    @Test
    void theAnswerToAQueryReplacedFromTheHeaderIsDropped() {
        Document document = upload();
        CountDownLatch slow = new CountDownLatch(1);
        when(semanticSearch.search("combine")).thenAnswer(invocation -> {
            // the answer to the first query comes after the answer to the second one
            assertThat(slow.await(20, TimeUnit.SECONDS)).isTrue();
            return List.of(hit(document, "a <b>combine</b>"));
        });
        when(semanticSearch.search(QUERY)).thenReturn(List.of(hit(document, "a <b>tractor</b>")));
        modeButton(mainView()).click();

        enter("combine");
        DocumentSearchView view = UiTestUtils.getCurrentView();
        enter(QUERY);
        waitUntil(() -> found(view).isVisible());

        slow.countDown();
        VaadinSession session = VaadinSession.getCurrent();
        await().pollInSameThread()
                .atMost(Duration.ofSeconds(20))
                .until(() -> !session.getPendingAccessQueue().isEmpty());
        runPendingAccessTasks();

        // the header keeps the open page and gives it the new query
        assertThat(UiTestUtils.<View<?>>getCurrentView()).isSameAs(view);
        assertThat(rows(view)).singleElement()
                .satisfies(row -> assertThat(match(row).getText())
                        .isEqualTo("tractor"));
    }

    private DocumentSearchView openAiSearch() {
        viewNavigators.view(UiTestUtils.getCurrentView(), DocumentSearchView.class)
                .withQueryParameters(QueryParameters.simple(Map.of(DocumentSearchView.QUERY_PARAMETER, QUERY,
                        DocumentSearchView.MODE_PARAMETER, DocumentSearchView.AI_MODE)))
                .navigate();

        return UiTestUtils.getCurrentView();
    }

    private void enter(String query) {
        TypedTextField<String> field = UiTestUtils.getComponent(mainView(), "searchField");
        field.setValue(query);

        ComponentUtil.fireEvent(field, new KeyPressEvent(field, "Enter"));
    }

    // the answer comes back through UI.access, which a UI test runs only when asked
    private static void waitUntil(BooleanSupplier shown) {
        await().pollInSameThread()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> {
                    runPendingAccessTasks();
                    return shown.getAsBoolean();
                });
    }

    private static void runPendingAccessTasks() {
        VaadinSession session = VaadinSession.getCurrent();
        session.getService().runPendingAccessTasks(session);
    }

    private DocumentSearchHit hit(Document document, String snippet) {
        return new DocumentSearchHit(document, List.of(sandbox.getName()),
                messages.getMessage(DocumentSearch.class, "fileText"), snippet);
    }

    private Document upload() {
        return systemAuthenticator.withUser(ADMIN, () ->
                documentService.upload(sandbox, List.of(TestFiles.file(tempDir, "lease.txt", "text")))
                        .created()
                        .getFirst());
    }

    private static MainView mainView() {
        return UI.getCurrent().getInternals().getActiveRouterTargetsChain().stream()
                .filter(MainView.class::isInstance)
                .map(MainView.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static JmixButton modeButton(MainView mainView) {
        return UiTestUtils.getComponent(mainView, "searchModeButton");
    }

    private static boolean pressed(JmixButton mode) {
        return "true".equals(mode.getElement().getAttribute("aria-pressed"));
    }

    private static Span message(DocumentSearchView view) {
        return UiTestUtils.getComponent(view, "messageText");
    }

    private static Span found(DocumentSearchView view) {
        return UiTestUtils.getComponent(view, "foundText");
    }

    private static JmixButton searchByWords(DocumentSearchView view) {
        return UiTestUtils.getComponent(view, "searchByWordsButton");
    }

    private static List<Component> rows(DocumentSearchView view) {
        return UiTestUtils.<VerticalLayout>getComponent(view, "hitsBox").getChildren().toList();
    }

    private static QueryParameters currentQueryParameters() {
        return UI.getCurrent().getInternals().getActiveViewLocation().getQueryParameters();
    }

    private static Span match(Component row) {
        return UiComponentUtils.getComponents(row).stream()
                .filter(component -> component.getClassNames().contains("document-search-match"))
                .map(Span.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No marked quote"));
    }
}
