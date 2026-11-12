package com.company.docai.view.main;

import com.company.docai.DocAiApplication;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.TestMenus;
import com.company.docai.test_support.TestPalettes;
import com.company.docai.view.repository.RepositoryView;
import com.company.docai.view.search.DocumentSearchView;
import com.company.docai.view.search.RecentQueries;
import com.company.docai.view.search.SearchPaletteView;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.KeyPressEvent;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.Views;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The search field in the header: it opens the search palette, which searches.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class MainViewSearchTest {

    @MockitoBean
    private EntitySearcher entitySearcher;

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private Views views;

    @Autowired
    private UserSettingsService userSettingsService;

    @Autowired
    private RecentQueries recentQueries;

    @BeforeEach
    void answerNothing() {
        SearchResult result = mock(SearchResult.class);
        when(result.getAllEntries()).thenReturn(List.of());
        when(entitySearcher.search(any(), anyString())).thenReturn(result);
    }

    @AfterEach
    void forgetTheModeAndTheSearches() {
        userSettingsService.delete(MainView.SEARCH_MODE_SETTING);
        userSettingsService.delete(RecentQueries.RECENT_QUERIES_SETTING);
    }

    @Test
    void enterInTheFieldSearchesNothingByItself() {
        TypedTextField<String> field = searchField();
        field.setValue("tractor");

        ComponentUtil.fireEvent(field, new KeyPressEvent(field, "Enter"));

        // in the browser the opener takes the Enter and asks for the palette
        assertThat(UiTestUtils.<View<?>>getCurrentView()).isNotInstanceOf(DocumentSearchView.class);
        assertThat(recentQueries.load()).isEmpty();
        assertThat(TestPalettes.isOpen()).isFalse();
    }

    @Test
    void theOpenerOpensThePaletteWithTheTextTheBrowserShows() {
        // the field sends its text only when the person leaves it, so the server may have an older one
        searchField().setValue("lease");

        SearchPaletteView palette = TestPalettes.open("tractor");

        assertThat(palette).isNotNull();
        assertThat(TestPalettes.queryField(palette).getValue()).isEqualTo("tractor");
        // the field in the browser keeps its own text, which a value from the server would overwrite
        assertThat(searchField().getValue()).isEqualTo("lease");
    }

    @Test
    void anEscBeforeThePaletteAppearsClosesItAndTheFieldKeepsItsText() {
        searchField().setValue("lease");
        TestPalettes.open("lease");

        TestPalettes.cancel();

        assertThat(TestPalettes.isOpen()).isFalse();
        assertThat(searchField().getValue()).isEqualTo("lease");
        assertThat(UiTestUtils.<View<?>>getCurrentView()).isNotInstanceOf(DocumentSearchView.class);

        // the Esc of a palette that never came finds nothing to close
        TestPalettes.cancel();

        assertThat(TestPalettes.isOpen()).isFalse();
        assertThat(recentQueries.load()).isEmpty();
    }

    @Test
    void aSearchFromThePaletteShowsItsTrimmedWordsInTheField() {
        TestPalettes.enter(TestPalettes.open(), "  tractor  ");

        assertThat(UiTestUtils.<View<?>>getCurrentView()).isInstanceOf(DocumentSearchView.class);
        assertThat(DocumentSearchView.queryOf(TestPalettes.currentQueryParameters())).isEqualTo("tractor");
        assertThat(searchField().getValue()).isEqualTo("tractor");
    }

    @Test
    void aSearchFromThePaletteIsRememberedWithItsMode() {
        TestPalettes.enter(TestPalettes.open(), "  tractor  ");

        // without the key the search goes by words
        assertThat(recentQueries.load()).containsExactly(new RecentQueries.RecentQuery("tractor", false));
    }

    @Test
    void theFieldIsEmptiedOutsideTheSearch() {
        TestPalettes.enter(TestPalettes.open(), "tractor");

        viewNavigators.view(UiTestUtils.getCurrentView(), RepositoryView.class).navigate();

        assertThat(searchField().getValue()).isEmpty();
    }

    @Test
    void theOpenerNamesTheHeaderField() {
        SearchPaletteOpener opener = UiTestUtils.getComponent(TestMenus.mainView(), "searchPaletteOpener");

        // the browser looks the field up by its id
        assertThat(opener.getElement().getAttribute("for")).isEqualTo(searchField().getId().orElseThrow());
    }

    @Test
    void withoutTheKeyTheSearchIsByWordsEvenForWhoChoseTheAiSearch() {
        userSettingsService.save(MainView.SEARCH_MODE_SETTING, DocumentSearchView.AI_MODE);

        // what the person sees on the next visit
        JmixButton mode = UiTestUtils.getComponent(views.create(MainView.class), "searchModeButton");

        assertThat(mode.isVisible()).isFalse();
        assertThat(mode.getElement().getAttribute("aria-pressed")).isEqualTo("false");
    }

    private TypedTextField<String> searchField() {
        return UiTestUtils.getComponent(TestMenus.mainView(), "searchField");
    }
}
