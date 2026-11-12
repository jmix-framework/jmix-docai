package com.company.docai.view.search;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.favorite.FavoriteService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.document.Document;
import com.company.docai.model.favorite.FavoriteItem;
import com.company.docai.model.folder.Folder;
import com.company.docai.model.tag.Tag;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.TestAccess;
import com.company.docai.test_support.TestAccess.Grant;
import com.company.docai.test_support.TestFiles;
import com.company.docai.test_support.TestMenus;
import com.company.docai.test_support.TestPalettes;
import com.company.docai.test_support.TestTags;
import com.company.docai.view.document.DocumentCards;
import com.company.docai.view.document.DocumentDetailView;
import com.company.docai.view.main.MainView;
import com.company.docai.view.repository.RepositoryView;
import com.google.common.base.Strings;
import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.router.QueryParameters;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.IdSerialization;
import io.jmix.core.Messages;
import io.jmix.core.MetadataTools;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.UiComponentUtils;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.data.grid.DataGridItems;
import io.jmix.flowui.fragment.FragmentUtils;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.kit.icon.JmixFontIcon;
import io.jmix.flowui.settings.UserSettingsCache;
import io.jmix.flowui.settings.UserSettingsService;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.testassist.notification.NotificationInfo;
import io.jmix.flowui.view.View;
import io.jmix.search.searching.EntitySearcher;
import io.jmix.search.searching.FieldHit;
import io.jmix.search.searching.SearchResult;
import io.jmix.search.searching.SearchResultEntry;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The search palette as an employee meets it with the AI off. The index is replaced: it answers with the documents
 * of the test, and the rest of the way, from the reload through DataManager to the rows, is real. The context is the
 * one of the other UI tests that replace the index.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class SearchPaletteViewTest {

    private static final String ADMIN = "admin";
    private static final String COLLEAGUE = "maria";
    private static final String FILE_TEXT = "currentRevision.file._content";
    private static final Locale RUSSIAN = Locale.forLanguageTag("ru");

    @MockitoBean
    private EntitySearcher entitySearcher;

    @Autowired
    private Messages messages;

    @Autowired
    private UserSettingsService userSettingsService;

    @Autowired
    private UserSettingsCache userSettingsCache;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private FavoriteService favoriteService;

    @Autowired
    private RecentQueries recentQueries;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @Autowired
    private IdSerialization idSerialization;

    @Autowired
    private MetadataTools metadataTools;

    @Autowired
    private CurrentAuthentication currentAuthentication;

    @TempDir
    private Path tempDir;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final List<Object> marked = new ArrayList<>();
    private Folder sandbox;

    @BeforeEach
    void setUp() {
        answerNothing();
        userSettingsService.delete(RecentQueries.RECENT_QUERIES_SETTING);
        sandbox = folder(null, "search-palette-test-" + UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        favoriteService.unmark(marked);
        userSettingsService.delete(RecentQueries.RECENT_QUERIES_SETTING);
        systemAuthenticator.runWithUser(ADMIN, () -> folderService.delete(sandbox));
        // leaving the view saves the settings of its fragments for the user
        userSettingsCache.delete("MainView.folderTree");
        userSettingsCache.delete("RepositoryView.documentList");
    }

    @Test
    void theHeaderOpensThePaletteWithItsTextAndMode() {
        TestPalettes.searchField().setValue("tractor");

        SearchPaletteView palette = TestPalettes.open();

        assertThat(TestPalettes.queryField(palette).getValue()).isEqualTo("tractor");
        // the line flies out of the header field and back, so it keeps the field's hint and no text changes in flight
        assertThat(TestPalettes.queryField(palette).getPlaceholder())
                .isEqualTo(TestPalettes.searchField().getPlaceholder())
                .isEqualTo(messages.getMessage(MainView.class, "searchField.placeholder"));
        assertThat(palette.findAncestor(Dialog.class).getClassNames()).contains("search-palette");
        assertThat(TestPalettes.pressedChips(palette)).containsExactly("allChip");
    }

    @Test
    void aSpaceTheBrowserSendsAfterAWordStaysInTheLine() {
        SearchPaletteView palette = TestPalettes.open();
        TypedTextField<String> line = TestPalettes.queryField(palette);
        line.setValue("farm ");

        // the line sends its text after a pause, which may come between a word and the next one
        ComponentUtil.fireEvent(line, new AbstractField.ComponentValueChangeEvent<>(line, line, "", true));

        assertThat(line.getValue()).isEqualTo("farm ");
    }

    @Test
    void thePaletteAsksTheBrowserToFlyItOutOfTheHeaderField() {
        TestPalettes.sentScripts();

        SearchPaletteView palette = TestPalettes.open();

        Element motion = UiTestUtils.<SearchPaletteMotion>getComponent(palette, "motion").getElement();
        Element line = TestPalettes.queryField(palette).getElement();
        Element panel = UiTestUtils.<Div>getComponent(palette, "panel").getElement();

        // the motion gets the header field, the line, which also takes over what the person typed into the header
        // field before the palette opened, and the panel, in this order
        assertThat(TestPalettes.sentScripts())
                .anySatisfy(invocation -> {
                    assertThat(invocation.getInvocation().getExpression()).contains("$0.open(");
                    assertThat(invocation.getInvocation().getParameters())
                            .containsExactly(motion, TestPalettes.searchField().getElement(), line, panel);
                });
    }

    @Test
    void theLineNamesItsFieldAndItsListForTheBrowser() {
        SearchPaletteView palette = TestPalettes.open();
        Element line = TestPalettes.line(palette).getElement();

        assertThat(line.getAttribute("for")).isEqualTo(TestPalettes.queryField(palette).getId().orElseThrow());
        assertThat(line.getAttribute("list"))
                .isEqualTo(UiTestUtils.<Div>getComponent(palette, "resultsBox").getId().orElseThrow());
    }

    @Test
    void aSecondRequestWhileThePaletteIsOpenOpensNoOther() {
        SearchPaletteView palette = TestPalettes.open();

        // a second click or shortcut that was on its way when the palette opened
        TestPalettes.open();

        assertThat(UiTestUtils.getOpenedViewDialogs())
                .filteredOn(SearchPaletteView.class::isInstance)
                .containsExactly(palette);

        // a closed palette lets the next click open a new one
        palette.closeWithDefaultAction();

        assertThat(TestPalettes.open()).isNotSameAs(palette);
    }

    @Test
    void aNavigationThePaletteDidNotStartClosesIt() {
        SearchPaletteView palette = TestPalettes.open();

        // the Back button of the browser reaches the server as a navigation of the router
        UI.getCurrent().navigate(DocumentSearchView.class,
                QueryParameters.of(DocumentSearchView.QUERY_PARAMETER, "lease"));

        assertThat(palette.findAncestor(Dialog.class).isOpened()).isFalse();
    }

    @Test
    void tabAndShiftTabSwitchTheChipsInACircle() {
        SearchPaletteView palette = TestPalettes.open();

        TestPalettes.press(palette, "Tab", false);

        assertThat(TestPalettes.pressedChips(palette)).containsExactly("documentsChip");

        TestPalettes.press(palette, "Tab", false);
        TestPalettes.press(palette, "Tab", false);
        TestPalettes.press(palette, "Tab", false);

        assertThat(TestPalettes.pressedChips(palette)).containsExactly("allChip");

        TestPalettes.press(palette, "Tab", true);

        assertThat(TestPalettes.pressedChips(palette)).containsExactly("tagsChip");
        assertThat(UiTestUtils.<Span>getComponent(palette, "chipStatus").getText())
                .isEqualTo(messages.getMessage(SearchPaletteView.class, "tagsGroup.title"));
        assertThat(TestPalettes.isOpen()).isTrue();
    }

    @Test
    void enterTakesTheTextOfTheKeyEventNotTheStaleField() {
        SearchPaletteView palette = TestPalettes.open();
        TestPalettes.type(palette, "tract");

        // the browser had more text than the pause sent
        TestPalettes.enter(palette, "  tractor  ");

        assertThat(UiTestUtils.<View<?>>getCurrentView()).isInstanceOf(DocumentSearchView.class);
        assertThat(DocumentSearchView.queryOf(TestPalettes.currentQueryParameters())).isEqualTo("tractor");
        assertThat(DocumentSearchView.isAiMode(TestPalettes.currentQueryParameters())).isFalse();
        assertThat(TestPalettes.isOpen()).isFalse();
    }

    @Test
    void anArrowUsesTheNewQueryBeforeItsLazyValueChangeArrives() {
        recentQueries.add("old query", false);
        Folder found = folder(sandbox, "new-query-" + suffix);
        SearchPaletteView palette = TestPalettes.open();

        // the browser shows the new text, which the pause has not sent yet
        TestPalettes.press(palette, "ArrowDown", false, "new-query-" + suffix);

        assertThat(TestPalettes.selectedRows(palette))
                .extracting(TestPalettes::name)
                .containsExactly(found.getName());

        Div row = TestPalettes.row(palette, found.getName());
        assertThat(TestPalettes.activeRow(palette)).isEqualTo(row.getId().orElseThrow());

        // the value that arrives after the pause describes the same list, which keeps its selected row
        TestPalettes.type(palette, "new-query-" + suffix);

        assertThat(TestPalettes.selectedRows(palette)).containsExactly(row);
        assertThat(TestPalettes.activeRow(palette)).isEqualTo(row.getId().orElseThrow());
    }

    @Test
    void anUpArrowAfterClearingUsesTheRecentQueriesAgain() {
        recentQueries.add("old query", false);
        SearchPaletteView palette = TestPalettes.open();
        TestPalettes.type(palette, "no-match-" + suffix);

        // the browser shows the line empty, and the pause has not sent it yet
        TestPalettes.press(palette, "ArrowUp", false, "");

        // up from none selects the last row of the list before typing, whatever the person has marked
        assertThat(TestPalettes.names(palette)).first().isEqualTo("old query");
        assertThat(TestPalettes.selectedRows(palette)).containsExactly(TestPalettes.rows(palette).getLast());
    }

    @Test
    void tabUsesTheNewQueryBeforeItsLazyValueChangeArrives() {
        recentQueries.add("old query", false);
        Document document = upload("new-query-" + suffix + ".txt");
        answer(entry(document, "name", document.getName()));
        SearchPaletteView palette = TestPalettes.open();

        TestPalettes.press(palette, "Tab", false, "new-query-" + suffix);

        assertThat(TestPalettes.pressedChips(palette)).containsExactly("documentsChip");
        assertThat(TestPalettes.groupNames(palette, message("documentsGroup.title")))
                .containsExactly(document.getName());
        assertThat(TestPalettes.names(palette)).doesNotContain("old query");
    }

    @Test
    void beforeTypingThePaletteShowsTheRecentQueriesNewestFirstAndFiveFavorites() {
        recentQueries.add("lease", false);
        recentQueries.add("tractor", true);

        mark(folder(sandbox, "alpha-" + suffix), folder(sandbox, "beta-" + suffix), upload("a-" + suffix + ".txt"),
                upload("b-" + suffix + ".txt"), upload("c-" + suffix + ".txt"), upload("d-" + suffix + ".txt"));

        // the first five rows of the favorites block of the menu, whatever else the person has marked
        List<FavoriteItem> favorites = favoriteService.loadItems()
                .stream()
                .limit(5)
                .toList();

        List<String> expected = Stream.concat(Stream.of("tractor", "lease"), favorites.stream()
                        .map(FavoriteItem::getName))
                .toList();

        SearchPaletteView palette = TestPalettes.open();

        assertThat(TestPalettes.headings(palette))
                .containsExactly(message("recentSection.title"), message("favoritesSection.title"));
        assertThat(TestPalettes.names(palette)).containsExactlyElementsOf(expected);
        // without the key the search by meaning wears the icon of the others
        assertThat(TestPalettes.rows(palette).subList(0, 2)).allSatisfy(row -> assertThat(TestPalettes.iconClass(row))
                .isEqualTo(TestPalettes.iconClass(JmixFontIcon.TIME_BACKWARD)));
        assertThat(TestPalettes.meta(TestPalettes.row(palette, favorites.getFirst().getName())))
                .isEqualTo(Strings.nullToEmpty(favorites.getFirst().getLocation()));
    }

    @Test
    void aSingleLetterKeepsTheSectionsBeforeTyping() {
        recentQueries.add("lease", false);
        SearchPaletteView palette = TestPalettes.open();
        List<String> before = TestPalettes.names(palette);

        TestPalettes.type(palette, " l ");

        assertThat(TestPalettes.names(palette)).isEqualTo(before).contains("lease");

        // before typing a chip only remembers its group for the search to come
        TestPalettes.press(palette, "Tab", false);

        assertThat(TestPalettes.pressedChips(palette)).containsExactly("documentsChip");
        assertThat(TestPalettes.names(palette)).isEqualTo(before);
        verify(entitySearcher, never()).search(any(), anyString());
    }

    @Test
    void aFavoriteOpensItsFolderOrItsCard() {
        Folder folder = folder(sandbox, "alpha-" + suffix);
        Document document = upload("a-" + suffix + ".txt");
        mark(folder, document);

        SearchPaletteView palette = TestPalettes.open();
        TestPalettes.click(palette, document.getName());

        DocumentDetailView card = UiTestUtils.getLastOpenedViewDialog();
        assertThat(card).isNotNull();
        assertThat(card.getEditedEntity()).isEqualTo(document);
        assertThat(TestPalettes.isOpen()).isTrue();

        card.closeWithDefaultAction();
        TestPalettes.click(palette, folder.getName());

        assertThat(UiTestUtils.<View<?>>getCurrentView()).isInstanceOf(RepositoryView.class);
        assertThat(TestMenus.folderTree().getSelectedFolder()).isEqualTo(folder);
    }

    @Test
    void inTheRepositoryACardSavedFromThePaletteRefreshesTheTable() {
        Document document = upload("plan-" + suffix + ".txt");
        mark(document);
        RepositoryView repository = openRepository(sandbox);

        TestPalettes.click(TestPalettes.open(), document.getName());
        DocumentDetailView card = UiTestUtils.getLastOpenedViewDialog();
        UiTestUtils.<TypedTextField<String>>getComponent(card, "nameField").setValue("renamed-" + suffix + ".txt");
        UiTestUtils.<JmixButton>getComponent(card, "saveButton").click();

        assertThat(documentNames(repository)).contains("renamed-" + suffix + ".txt");
        assertThat(TestPalettes.isOpen()).isTrue();
    }

    @Test
    void aDocumentGoneSinceGivesTheNoticeOverThePaletteWhichListsWhatIsLeft() {
        Document document = upload("gone-" + suffix + ".txt");
        Document other = upload("kept-" + suffix + ".txt");
        mark(document, other);
        SearchPaletteView palette = TestPalettes.open();

        systemAuthenticator.runWithUser(ADMIN, () -> documentService.delete(List.of(document)));
        TestPalettes.click(palette, document.getName());
        NotificationInfo notice = UiTestUtils.getLastOpenedNotification();

        assertThat(notice).isNotNull();
        assertThat(notice.getText()).isEqualTo(messages.getMessage(DocumentCards.class, "documentUnavailable"));
        assertThat(UiTestUtils.getOpenedViewDialogs()).noneMatch(DocumentDetailView.class::isInstance);
        assertThat(TestPalettes.isOpen()).isTrue();
        assertThat(TestPalettes.names(palette)).doesNotContain(document.getName()).contains(other.getName());
    }

    @Test
    void closingTheCardLeavesThePaletteWithItsTextChipAndRowAndTheFocusInTheLine() {
        Document document = upload("lease-" + suffix + ".txt");
        answer(entry(document, FILE_TEXT, "a <b>lease</b>"));
        SearchPaletteView palette = TestPalettes.open();
        TestPalettes.type(palette, "lease");
        TestPalettes.press(palette, "Tab", false);
        TestPalettes.press(palette, "ArrowDown", false);
        Div row = TestPalettes.row(palette, document.getName());

        TestPalettes.enter(palette, "lease");
        DocumentDetailView card = UiTestUtils.getLastOpenedViewDialog();
        assertThat(card).isNotNull();
        TestPalettes.sentScripts();
        card.closeWithDefaultAction();

        assertThat(TestPalettes.isOpen()).isTrue();
        assertThat(TestPalettes.queryField(palette).getValue()).isEqualTo("lease");
        assertThat(TestPalettes.pressedChips(palette)).containsExactly("documentsChip");
        assertThat(TestPalettes.selectedRows(palette)).containsExactly(row);
        assertThat(TestPalettes.activeRow(palette)).isEqualTo(row.getId().orElseThrow());
        // the card may have taken the focus from wherever the click left it
        assertThat(TestPalettes.focusesTheLineAtTheEnd(palette)).isTrue();
    }

    @Test
    void aPageTheCardLeavesForClosesTheCardAndThePalette() {
        String tag = "lease-" + suffix;
        Document document = TestTags.tagged(unconstrainedDataManager, upload("a-" + suffix + ".txt"),
                TestTags.tag(unconstrainedDataManager, tag));
        mark(document);
        SearchPaletteView palette = TestPalettes.open();
        TestPalettes.click(palette, document.getName());
        DocumentDetailView card = UiTestUtils.getLastOpenedViewDialog();
        assertThat(card).isNotNull();
        assertThat(TestPalettes.isOpen()).isTrue();

        TestTags.click(UiTestUtils.getComponent(card, "documentTags"), tag);

        // the dialogs themselves: a closed dialog view stays on the page until the browser has played the closing, and
        // a navigation meanwhile lists it as open again
        assertThat(TestTags.tagInAddress()).hasValue(tag);
        assertThat(card.findAncestor(Dialog.class).isOpened()).isFalse();
        assertThat(palette.findAncestor(Dialog.class).isOpened()).isFalse();
    }

    @Test
    void aSecondChoiceWhileTheCardIsOpenOpensNoOtherAndTheNextChoiceWorksAfterIt() {
        Document document = upload("lease-" + suffix + ".txt");
        mark(document);
        SearchPaletteView palette = TestPalettes.open();

        // a double click; a double Enter goes the same way
        TestPalettes.click(palette, document.getName());
        TestPalettes.click(palette, document.getName());

        List<View<?>> cards = cards();
        assertThat(cards).hasSize(1);

        cards.getFirst().closeWithDefaultAction();
        TestPalettes.click(palette, document.getName());

        assertThat(cards()).hasSize(1).doesNotContainAnyElementsOf(cards);
    }

    @Test
    void aClickOnTheCrossRemovesThatRecentSearchAndKeepsTheOthers() {
        recentQueries.add("lease", false);
        recentQueries.add("tractor", true);
        SearchPaletteView palette = TestPalettes.open();
        String recent = message("recentSection.title");
        JmixButton cross = TestPalettes.removeButton(TestPalettes.row(palette, "lease")).orElseThrow();

        assertThat(cross.getElement().getAttribute("aria-label")).isEqualTo(message("removeRecentButton.ariaLabel"));
        assertThat(cross.getTooltip().getText()).isEqualTo(message("removeRecentButton.tooltip"));
        // the row under the cross would run the search
        assertThat(TestPalettes.keepsItsClicks(cross)).isTrue();
        TestPalettes.sentScripts();

        TestPalettes.remove(palette, "lease");

        assertThat(TestPalettes.groupNames(palette, recent)).containsExactly("tractor");
        assertThat(recentQueries.load()).containsExactly(new RecentQueries.RecentQuery("tractor", true));
        // the cross only removes: the palette stays, and the line has the focus with the caret after its text
        assertThat(TestPalettes.isOpen()).isTrue();
        assertThat(UiTestUtils.<View<?>>getCurrentView()).isNotInstanceOf(DocumentSearchView.class);
        assertThat(TestPalettes.focusesTheLineAtTheEnd(palette)).isTrue();
    }

    @Test
    void onlyTheRecentSearchesHaveACross() {
        recentQueries.add("lease", false);
        Folder folder = folder(sandbox, "alpha-" + suffix);
        mark(folder);

        SearchPaletteView palette = TestPalettes.open();

        assertThat(TestPalettes.removeButton(TestPalettes.row(palette, "lease"))).isPresent();
        assertThat(TestPalettes.removeButton(TestPalettes.row(palette, folder.getName()))).isEmpty();
    }

    @Test
    void aRecentSearchIsNamedByItsTextAloneAndNamesItsShortcut() {
        recentQueries.add("lease", false);
        Folder folder = folder(sandbox, "alpha-" + suffix);
        mark(folder);

        SearchPaletteView palette = TestPalettes.open();
        Element recent = TestPalettes.row(palette, "lease").getElement();

        // the name of the cross inside the row would join the name of the row
        assertThat(recent.getAttribute("aria-label")).isEqualTo("lease");
        assertThat(recent.getAttribute("aria-keyshortcuts")).isEqualTo("Shift+Delete");
        assertThat(TestPalettes.row(palette, folder.getName()).getElement().getAttribute("aria-keyshortcuts"))
                .isNull();
    }

    @Test
    void theSecondClickOfADoubleClickOnACrossRemovesNothingAndRunsNothing() {
        for (String text : List.of("alpha", "beta", "gamma")) {
            recentQueries.add(text, false);
        }

        SearchPaletteView palette = TestPalettes.open();
        String recent = message("recentSection.title");

        // the newest first: gamma, beta, alpha
        TestPalettes.remove(palette, "gamma");

        // the next search moves up under the pointer, with its cross or without it
        Div next = TestPalettes.row(palette, "beta");
        TestPalettes.secondClick(TestPalettes.removeButton(next).orElseThrow());
        TestPalettes.secondClick(next);

        assertThat(TestPalettes.groupNames(palette, recent)).containsExactly("beta", "alpha");
        assertThat(TestPalettes.isOpen()).isTrue();
        assertThat(UiTestUtils.<View<?>>getCurrentView()).isNotInstanceOf(DocumentSearchView.class);
    }

    @Test
    void shiftDeleteRemovesTheSelectedRecentSearchAndSelectsTheOneInItsPlace() {
        for (String text : List.of("alpha", "beta", "gamma")) {
            recentQueries.add(text, false);
        }

        SearchPaletteView palette = TestPalettes.open();
        String recent = message("recentSection.title");

        // the newest first: gamma, beta, alpha
        TestPalettes.press(palette, "ArrowDown", false);
        TestPalettes.press(palette, "Delete", true);

        assertThat(TestPalettes.groupNames(palette, recent)).containsExactly("beta", "alpha");
        assertThat(TestPalettes.selectedRows(palette)).extracting(TestPalettes::name).containsExactly("beta");

        // the last one gives its place to the one before it
        TestPalettes.press(palette, "ArrowDown", false);
        TestPalettes.press(palette, "Delete", true);

        assertThat(TestPalettes.groupNames(palette, recent)).containsExactly("beta");
        assertThat(TestPalettes.selectedRows(palette)).extracting(TestPalettes::name).containsExactly("beta");

        // Delete alone edits the line
        TestPalettes.press(palette, "Delete", false);

        assertThat(recentQueries.load()).containsExactly(new RecentQueries.RecentQuery("beta", false));

        TestPalettes.press(palette, "Delete", true);

        assertThat(TestPalettes.headings(palette)).doesNotContain(recent);
        assertThat(TestPalettes.selectedRows(palette)).isEmpty();
        assertThat(TestPalettes.activeRow(palette)).isEmpty();
        assertThat(recentQueries.load()).isEmpty();
    }

    @Test
    void shiftDeleteOnAnotherRowRemovesNothing() {
        recentQueries.add("lease", false);
        Folder folder = folder(sandbox, "alpha-" + suffix);
        mark(folder);
        SearchPaletteView palette = TestPalettes.open();

        // the recent search, then the first of the favorites
        TestPalettes.press(palette, "ArrowDown", false);
        TestPalettes.press(palette, "ArrowDown", false);
        TestPalettes.press(palette, "Delete", true);

        assertThat(recentQueries.load()).containsExactly(new RecentQueries.RecentQuery("lease", false));
        assertThat(TestPalettes.names(palette)).contains("lease", folder.getName());
        assertThat(TestPalettes.selectedRows(palette)).containsExactly(TestPalettes.rows(palette).get(1));
    }

    @Test
    void aRemovedSearchIsSaidToAScreenReader() {
        recentQueries.add("lease", false);
        recentQueries.add("tractor", false);
        SearchPaletteView palette = TestPalettes.open();
        Span status = UiTestUtils.getComponent(palette, "messageText");

        TestPalettes.remove(palette, "lease");

        assertThat(status.getText()).isEqualTo(removed("lease"));
        // the list shows the removal itself, so only a screen reader hears it
        assertThat(status.hasClassName("search-palette-live")).isTrue();

        TestPalettes.press(palette, "ArrowDown", false);
        TestPalettes.press(palette, "Delete", true);

        assertThat(status.getText()).isEqualTo(removed("tractor"));
    }

    @Test
    void withoutTheKeyARecentAiQueryOpensTheSearchByWords() {
        recentQueries.add("tractor", true);
        SearchPaletteView palette = TestPalettes.open();

        assertThat(TestPalettes.iconClass(TestPalettes.row(palette, "tractor")))
                .isEqualTo(TestPalettes.iconClass(JmixFontIcon.TIME_BACKWARD));

        TestPalettes.click(palette, "tractor");

        assertThat(DocumentSearchView.queryOf(TestPalettes.currentQueryParameters())).isEqualTo("tractor");
        assertThat(DocumentSearchView.isAiMode(TestPalettes.currentQueryParameters())).isFalse();
    }

    @Test
    void theArrowsGoThroughAllTheRowsAndBackToNone() {
        recentQueries.add("lease", false);
        recentQueries.add("tractor", false);
        SearchPaletteView palette = TestPalettes.open();
        List<Div> rows = TestPalettes.rows(palette);

        assertThat(TestPalettes.activeRow(palette)).isEmpty();

        TestPalettes.press(palette, "ArrowDown", false);

        assertThat(TestPalettes.selectedRows(palette)).containsExactly(rows.getFirst());
        assertThat(rows.getFirst().hasClassName("search-palette-row-active")).isTrue();
        assertThat(TestPalettes.activeRow(palette)).isEqualTo(rows.getFirst().getId().orElseThrow());

        for (int i = 1; i < rows.size(); i++) {
            TestPalettes.press(palette, "ArrowDown", false);
        }

        assertThat(TestPalettes.selectedRows(palette)).containsExactly(rows.getLast());
        assertThat(TestPalettes.activeRow(palette)).isEqualTo(rows.getLast().getId().orElseThrow());

        TestPalettes.press(palette, "ArrowDown", false);

        assertThat(TestPalettes.selectedRows(palette)).isEmpty();
        assertThat(rows).noneMatch(row -> row.hasClassName("search-palette-row-active"));
        assertThat(TestPalettes.activeRow(palette)).isEmpty();

        TestPalettes.press(palette, "ArrowUp", false);

        assertThat(TestPalettes.selectedRows(palette)).containsExactly(rows.getLast());
        assertThat(TestPalettes.activeRow(palette)).isEqualTo(rows.getLast().getId().orElseThrow());

        for (int i = 0; i < rows.size(); i++) {
            TestPalettes.press(palette, "ArrowUp", false);
        }

        assertThat(TestPalettes.selectedRows(palette)).isEmpty();
        assertThat(TestPalettes.activeRow(palette)).isEmpty();
        assertThat(TestPalettes.isOpen()).isTrue();

        // a new list starts with no row chosen, so the line names no row of the old one
        TestPalettes.press(palette, "ArrowDown", false);
        TestPalettes.type(palette, "lease");

        assertThat(TestPalettes.activeRow(palette)).isEmpty();
    }

    @Test
    void theKeysAndTheChipsSendNoScriptToTheFieldOfTheLine() {
        SearchPaletteView palette = TestPalettes.open();
        // a new value updates the invalid state of the field, which the framework sends with a script of its own
        TestPalettes.type(palette, "lease");
        Element field = TestPalettes.queryField(palette).getElement();
        TestPalettes.sentScripts();

        TestPalettes.press(palette, "ArrowDown", false);
        TestPalettes.press(palette, "ArrowUp", false);
        TestPalettes.press(palette, "Tab", false);
        TestPalettes.press(palette, "Tab", true);
        UiTestUtils.<JmixButton>getComponent(palette, "foldersChip").click();

        // whatever the browser does for the line goes through its companion
        assertThat(TestPalettes.sentScripts())
                .filteredOn(script -> script.getInvocation().getParameters().contains(field))
                .extracting(script -> script.getInvocation().getExpression())
                .isEmpty();
    }

    @Test
    void enterWithoutASelectedRowSearchesByWordsAndOnARowOpensIt() {
        recentQueries.add("lease", false);
        SearchPaletteView palette = TestPalettes.open();
        TestPalettes.type(palette, "tractor");

        TestPalettes.enter(palette, "tractor");

        assertThat(DocumentSearchView.queryOf(TestPalettes.currentQueryParameters())).isEqualTo("tractor");

        // the header shows the search of the page; an empty line brings the recent searches back
        SearchPaletteView again = TestPalettes.open();
        TestPalettes.type(again, "");
        TestPalettes.press(again, "ArrowDown", false);
        TestPalettes.press(again, "ArrowDown", false);
        TestPalettes.enter(again, "");

        assertThat(DocumentSearchView.queryOf(TestPalettes.currentQueryParameters())).isEqualTo("lease");
        assertThat(DocumentSearchView.isAiMode(TestPalettes.currentQueryParameters())).isFalse();
    }

    @Test
    void aSearchIsRememberedFiveAtMostNewestFirstAndOnceEach() {
        for (String text : List.of("alpha", "beta", "gamma", "delta", "epsilon", "zeta")) {
            TestPalettes.enter(TestPalettes.open(), text);
        }

        TestPalettes.enter(TestPalettes.open(), "  BETA  ");

        SearchPaletteView palette = TestPalettes.open();
        TestPalettes.type(palette, "");

        assertThat(TestPalettes.names(palette)).startsWith("BETA", "zeta", "epsilon", "delta", "gamma");
        assertThat(recentQueries.load()).hasSize(RecentQueries.MAX_QUERIES);
    }

    @Test
    void aBrokenSettingGivesNoRecentQueries() {
        userSettingsService.save(RecentQueries.RECENT_QUERIES_SETTING, "[{\"text\":");

        SearchPaletteView palette = TestPalettes.open();

        assertThat(TestPalettes.headings(palette)).doesNotContain(message("recentSection.title"));
    }

    @Test
    void withoutTheKeyThereIsNoWandNoAssistantSectionAndNoAskAiRow() {
        SearchPaletteView palette = TestPalettes.open();

        assertThat(UiTestUtils.<JmixButton>getComponent(palette, "searchModeButton").isVisible()).isFalse();
        assertThat(TestPalettes.headings(palette)).doesNotContain(message("askAiSection.title"));

        TestPalettes.type(palette, "tractor");

        assertThat(TestPalettes.names(palette)).last().isEqualTo(allResults("tractor"));
        assertThat(TestPalettes.rows(palette)).noneMatch(row -> TestPalettes.iconClass(row)
                .equals(TestPalettes.iconClass(JmixFontIcon.COMMENTS)));
    }

    // documents are found by the beginnings of their words from three letters
    @Test
    void twoLettersShowTheMatchingFoldersAndTagsThenTheAllResultsRow() {
        String folders = message("foldersGroup.title");
        String tags = message("tagsGroup.title");
        Folder folder = folder(sandbox, "Qz plans " + suffix);
        tagged(upload("tagged-" + suffix + ".txt"), "qz-" + suffix);

        SearchPaletteView palette = TestPalettes.open();
        TestPalettes.type(palette, "qz");

        verify(entitySearcher, never()).search(any(), anyString());
        assertThat(TestPalettes.headings(palette)).containsExactly(folders, tags);
        assertThat(TestPalettes.groupNames(palette, folders)).contains(folder.getName());
        assertThat(TestPalettes.groupNames(palette, tags)).contains("qz-" + suffix);
        assertThat(TestPalettes.names(palette)).last().isEqualTo(allResults("qz"));
    }

    @Test
    void theLiveDocumentsUseThePrefixStrategyAndThePageKeepsItsOwn() {
        SearchPaletteView palette = TestPalettes.open();

        TestPalettes.type(palette, "lease");

        verify(entitySearcher).search(any(), eq("startsWith"));

        TestPalettes.enter(palette, "lease");

        verify(entitySearcher).search(any(), eq("wordsThenBeginnings"));
    }

    @Test
    void allShowsFiveRowsOfEachGroupAndAChipTenRowsOfItsGroupOnly() {
        String documents = message("documentsGroup.title");
        String folders = message("foldersGroup.title");
        String tags = message("tagsGroup.title");

        List<Document> found = IntStream.range(0, 11)
                .mapToObj(number -> upload("doc-" + number + "-" + suffix + ".txt"))
                .toList();

        IntStream.range(0, 11)
                .forEach(number -> folder(sandbox, "Folder " + number + " " + suffix));

        IntStream.range(0, 11)
                .forEach(number -> tagged(found.get(number), "tag-" + number + "-" + suffix));

        answer(found.stream()
                .map(document -> entry(document, FILE_TEXT, "the <b>text</b>"))
                .toArray(SearchResultEntry[]::new));

        SearchPaletteView palette = TestPalettes.open();
        TestPalettes.type(palette, suffix);

        assertThat(TestPalettes.groupNames(palette, documents)).hasSize(5);
        assertThat(TestPalettes.groupNames(palette, folders)).hasSize(5);
        assertThat(TestPalettes.groupNames(palette, tags)).hasSize(5);

        TestPalettes.press(palette, "Tab", false);

        assertThat(TestPalettes.headings(palette)).containsExactly(documents);
        assertThat(TestPalettes.groupNames(palette, documents)).hasSize(10);
        assertThat(TestPalettes.names(palette)).last().isEqualTo(allResults(suffix));

        TestPalettes.press(palette, "Tab", false);

        assertThat(TestPalettes.headings(palette)).containsExactly(folders);
        assertThat(TestPalettes.groupNames(palette, folders)).hasSize(10);

        TestPalettes.press(palette, "Tab", false);

        assertThat(TestPalettes.headings(palette)).containsExactly(tags);
        assertThat(TestPalettes.groupNames(palette, tags)).hasSize(10);
    }

    @Test
    void theMatchInTheNameComesFromTheNameOrItsPrefixFieldAndIsShownAsText() {
        Document byBeginning = upload("Lease <draft> " + suffix + ".txt");
        Document byName = upload("Plan " + suffix + ".txt");
        Document cut = upload("Lease plan " + suffix + ".txt");

        answer(entry(byBeginning, "name.prefix", "<b>Lease</b> <draft> " + suffix + ".txt"),
                entry(byName, "name", "<b>Plan</b> " + suffix + ".txt"),
                entry(cut, "name", "<b>Lease</b> plan"));

        SearchPaletteView palette = TestPalettes.open();
        TestPalettes.type(palette, "lease");
        Div marked = TestPalettes.row(palette, byBeginning.getName());

        assertThat(matches(marked)).containsExactly("Lease");
        // the angle brackets of the name stay text, so the name reads in full
        assertThat(TestPalettes.name(marked)).isEqualTo(byBeginning.getName());
        assertThat(matches(TestPalettes.row(palette, byName.getName()))).containsExactly("Plan");
        // a mark shorter than the name gives the plain name
        assertThat(matches(TestPalettes.row(palette, cut.getName()))).isEmpty();
    }

    @Test
    void aFolderRowSaysWhereTheFolderLiesAndOpensItInTheRepository() {
        Folder folder = folder(sandbox, "Leases " + suffix);
        SearchPaletteView palette = TestPalettes.open();
        TestPalettes.type(palette, suffix);

        assertThat(TestPalettes.meta(TestPalettes.row(palette, folder.getName())))
                .isEqualTo(metadataTools.getInstanceName(folderService.getRoot()) + " / " + sandbox.getName());

        TestPalettes.click(palette, folder.getName());

        assertThat(UiTestUtils.<View<?>>getCurrentView()).isInstanceOf(RepositoryView.class);
        assertThat(TestMenus.folderTree().getSelectedFolder()).isEqualTo(folder);
    }

    @Test
    void theFoldersComeAsTheTreeShowsThemEachRightAfterItsParent() {
        Folder parent = folder(sandbox, "b " + suffix);
        Folder child = folder(parent, "a " + suffix);
        Folder sibling = folder(sandbox, "c " + suffix);

        SearchPaletteView palette = TestPalettes.open();
        TestPalettes.type(palette, suffix);

        // by name alone the child would come first
        assertThat(TestPalettes.groupNames(palette, message("foldersGroup.title")))
                .containsExactly(parent.getName(), child.getName(), sibling.getName());
    }

    @Test
    void aFolderTheEmployeeOnlyPassesThroughIsGreyedAsInTheTree() {
        Folder passage = systemAuthenticator.withUser(ADMIN, () -> TestAccess.giveOwnAccess(dataManager,
                folder(sandbox, "Passage " + suffix),
                Grant.user(COLLEAGUE, AccessLevel.EDIT)));

        Folder shared = systemAuthenticator.withUser(ADMIN, () -> TestAccess.giveOwnAccess(dataManager,
                folder(passage, "Shared " + suffix),
                Grant.user(EmployeeUiTestAuthenticator.USERNAME, AccessLevel.VIEW)));

        SearchPaletteView palette = TestPalettes.open();
        TestPalettes.type(palette, suffix);

        assertThat(TestPalettes.isGreyed(TestPalettes.row(palette, passage.getName()))).isTrue();
        assertThat(TestPalettes.isGreyed(TestPalettes.row(palette, shared.getName()))).isFalse();
    }

    @Test
    void aTagRowCountsOnlyTheDocumentsTheEmployeeMaySeeAndOpensTheTagPage() {
        String name = "lease-" + suffix;
        Tag tag = TestTags.tag(unconstrainedDataManager, name);
        TestTags.tagged(unconstrainedDataManager, upload("a-" + suffix + ".txt"), tag);
        TestTags.tagged(unconstrainedDataManager, upload("b-" + suffix + ".txt"), tag);
        TestTags.tagged(unconstrainedDataManager, upload(secretFolder(), "c-" + suffix + ".txt"), tag);

        SearchPaletteView palette = TestPalettes.open();
        TestPalettes.type(palette, name);
        String key = "tagDocuments." + SearchPaletteView.pluralForm(2, currentAuthentication.getLocale());

        assertThat(TestPalettes.meta(TestPalettes.row(palette, name)))
                .isEqualTo(messages.formatMessage(SearchPaletteView.class, key, 2));

        TestPalettes.click(palette, name);

        assertThat(TestTags.tagInAddress()).contains(name);
    }

    @Test
    void theNumberOfTheDocumentsOfATagHasItsThreeFormsInBothLanguages() {
        List<String> keys = Stream.of("one", "few", "many")
                .map(form -> "tagDocuments." + form)
                .toList();

        // a message missing from a bundle reads as its key
        for (Locale locale : List.of(Locale.ENGLISH, RUSSIAN)) {
            assertThat(keys).allSatisfy(key -> assertThat(messages.getMessage(SearchPaletteView.class, key, locale))
                    .isNotEqualTo(key));
        }

        assertThat(tagDocuments(1)).isEqualTo("1 document");
        assertThat(tagDocuments(2)).isEqualTo("2 documents");
        // the three Russian forms differ from each other, and each carries the number
        assertThat(keys.stream()
                .map(key -> messages.getMessage(SearchPaletteView.class, key, RUSSIAN)))
                .doesNotHaveDuplicates()
                .allSatisfy(text -> assertThat(text).contains("%s"));
    }

    @Test
    void outsideTheRepositoryADocumentRowOpensItsCardOverThePalette() {
        Document document = upload("lease-" + suffix + ".txt");
        answer(entry(document, FILE_TEXT, "a <b>lease</b>"));

        SearchPaletteView palette = TestPalettes.open();
        TestPalettes.type(palette, "lease");
        TestPalettes.click(palette, document.getName());

        DocumentDetailView card = UiTestUtils.getLastOpenedViewDialog();
        assertThat(card).isNotNull();
        assertThat(card.getEditedEntity()).isEqualTo(document);
        assertThat(TestPalettes.isOpen()).isTrue();
    }

    @Test
    void withoutTheKeyTheAllResultsRowOpensTheSearchByWords() {
        SearchPaletteView palette = TestPalettes.open();
        TestPalettes.type(palette, "tractor");

        assertThat(TestPalettes.iconClass(TestPalettes.row(palette, allResults("tractor"))))
                .isEqualTo(TestPalettes.iconClass(JmixFontIcon.SEARCH));

        TestPalettes.click(palette, allResults("tractor"));

        assertThat(DocumentSearchView.queryOf(TestPalettes.currentQueryParameters())).isEqualTo("tractor");
        assertThat(DocumentSearchView.isAiMode(TestPalettes.currentQueryParameters())).isFalse();
    }

    @Test
    void anUnavailableIndexIsSaidInTheDocumentsGroupWhileFoldersAndTagsStay() {
        String documents = message("documentsGroup.title");
        String folders = message("foldersGroup.title");
        when(entitySearcher.search(any(), anyString())).thenThrow(new IllegalStateException("No index"));
        Folder folder = folder(sandbox, "Leases " + suffix);

        SearchPaletteView palette = TestPalettes.open();
        TestPalettes.type(palette, suffix);
        Span status = UiTestUtils.getComponent(palette, "messageText");

        assertThat(TestPalettes.headings(palette)).containsExactly(documents, folders);
        assertThat(TestPalettes.groupMessages(palette, documents)).containsExactly(message("searchUnavailable"));
        assertThat(TestPalettes.groupNames(palette, folders)).containsExactly(folder.getName());
        // the status line says it to a screen reader only, since the group shows it
        assertThat(status.getText()).isEqualTo(message("searchUnavailable"));
        assertThat(status.hasClassName("search-palette-live")).isTrue();
        assertThat(TestPalettes.names(palette)).last().isEqualTo(allResults(suffix));
    }

    @Test
    void anUnavailableIndexIsNotTakenForNothingFound() {
        String documents = message("documentsGroup.title");
        when(entitySearcher.search(any(), anyString())).thenThrow(new IllegalStateException("No index"));

        SearchPaletteView palette = TestPalettes.open();
        // no folder and no tag carries the text, and the palette cannot know what the index would find
        TestPalettes.type(palette, "zq" + suffix);

        assertThat(TestPalettes.headings(palette)).containsExactly(documents);
        assertThat(TestPalettes.groupMessages(palette, documents)).containsExactly(message("searchUnavailable"));
        assertThat(UiTestUtils.<Span>getComponent(palette, "messageText").getText())
                .isEqualTo(message("searchUnavailable"));
        assertThat(TestPalettes.names(palette)).containsExactly(allResults("zq" + suffix));
        // a screen reader hears the status line only, not the notice of the group as well
        assertThat(UiComponentUtils.getComponents(UiTestUtils.<Div>getComponent(palette, "resultsBox"))
                .stream()
                .filter(component -> component.hasClassName("search-palette-message"))
                .map(component -> component.getElement().getAttribute("aria-hidden")))
                .containsExactly("true");
    }

    @Test
    void aTextNothingMatchesSaysSoAboveTheAllResultsRow() {
        SearchPaletteView palette = TestPalettes.open();
        Span message = UiTestUtils.getComponent(palette, "messageText");

        // a screen reader hears a status only from a region that was on the page before the text
        assertThat(message.isVisible()).isTrue();
        assertThat(message.getText()).isEmpty();

        TestPalettes.type(palette, "zq" + suffix);

        assertThat(message.hasClassName("search-palette-live")).isFalse();
        assertThat(message.getText()).isEqualTo(message("nothingFound"));
        assertThat(TestPalettes.headings(palette)).isEmpty();
        assertThat(TestPalettes.names(palette)).containsExactly(allResults("zq" + suffix));
    }

    private void answerNothing() {
        SearchResult result = mock(SearchResult.class);
        when(result.getAllEntries()).thenReturn(List.of());
        when(entitySearcher.search(any(), anyString())).thenReturn(result);
    }

    private void answer(SearchResultEntry... entries) {
        SearchResult result = mock(SearchResult.class);
        when(result.getAllEntries()).thenReturn(List.of(entries));
        when(entitySearcher.search(any(), anyString())).thenReturn(result);
    }

    private SearchResultEntry entry(Document document, String field, String highlights) {
        return new SearchResultEntry(idSerialization.idToString(Id.of(document)), document.getName(), "Document",
                List.of(new FieldHit(field, highlights)));
    }

    private String message(String key) {
        return messages.getMessage(SearchPaletteView.class, key);
    }

    private String allResults(String text) {
        return messages.formatMessage(SearchPaletteView.class, "allResultsRow.text", text);
    }

    private String removed(String text) {
        return messages.formatMessage(SearchPaletteView.class, "recentRemoved", text);
    }

    private String tagDocuments(long count) {
        String key = "tagDocuments." + SearchPaletteView.pluralForm(count, Locale.ENGLISH);

        return messages.formatMessage(SearchPaletteView.class, key, Locale.ENGLISH, count);
    }

    private Folder folder(@Nullable Folder parent, String name) {
        return systemAuthenticator.withUser(ADMIN, () -> {
            Folder folder = dataManager.create(Folder.class);
            folder.setParent(parent == null ? folderService.getRoot() : parent);
            folder.setName(name);

            return folderService.save(folder);
        });
    }

    private Document upload(String fileName) {
        return upload(sandbox, fileName);
    }

    private Document upload(Folder folder, String fileName) {
        return systemAuthenticator.withUser(ADMIN, () ->
                documentService.upload(folder, List.of(TestFiles.file(tempDir, fileName, "text")))
                        .created()
                        .getFirst());
    }

    private void tagged(Document document, String name) {
        TestTags.tagged(unconstrainedDataManager, document, TestTags.tag(unconstrainedDataManager, name));
    }

    private Folder secretFolder() {
        return systemAuthenticator.withUser(ADMIN, () -> TestAccess.giveOwnAccess(dataManager,
                folder(sandbox, "secret"),
                Grant.user(COLLEAGUE, AccessLevel.EDIT)));
    }

    // the person marks them, and the marks go with the test
    private void mark(Object... targets) {
        favoriteService.mark(List.of(targets));
        marked.addAll(List.of(targets));
    }

    private RepositoryView openRepository(Folder folder) {
        viewNavigators.view(UiTestUtils.getCurrentView(), RepositoryView.class)
                .withQueryParameters(QueryParameters.of(RepositoryView.FOLDER_PARAMETER, folder.getId().toString()))
                .navigate();

        return UiTestUtils.getCurrentView();
    }

    private static List<View<?>> cards() {
        return UiTestUtils.getOpenedViewDialogs()
                .stream()
                .filter(DocumentDetailView.class::isInstance)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> documentNames(RepositoryView repository) {
        DataGrid<Document> grid = (DataGrid<Document>) FragmentUtils.getComponent(
                UiTestUtils.getComponent(repository, "documentList"), "documentsGrid");

        DataGridItems<Document> items = grid.getItems();
        assertThat(items).isNotNull();

        return items.getItems()
                .stream()
                .map(Document::getName)
                .toList();
    }

    private static List<String> matches(Div row) {
        return UiComponentUtils.getComponents(row)
                .stream()
                .filter(component -> component.hasClassName("document-search-match"))
                .map(component -> component.getElement().getText())
                .toList();
    }
}
