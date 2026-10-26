package com.company.dms.view.main;

import com.company.dms.DMSApplication;
import com.company.dms.test_support.EmployeeUiTestAuthenticator;
import com.company.dms.view.repository.RepositoryView;
import com.company.dms.view.search.DocumentSearchView;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.KeyPressEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.QueryParameters;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.view.View;
import io.jmix.search.searching.EntitySearcher;
import io.jmix.search.searching.SearchResult;
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
 * The search field in the header.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DMSApplication.class, FlowuiTestAssistConfiguration.class})
class MainViewSearchTest {

    @MockitoBean
    private EntitySearcher entitySearcher;

    @Autowired
    private ViewNavigators viewNavigators;

    @BeforeEach
    void answerNothing() {
        SearchResult result = mock(SearchResult.class);
        when(result.getAllEntries()).thenReturn(List.of());
        when(entitySearcher.search(any(), anyString())).thenReturn(result);
    }

    @Test
    void enterOpensTheSearchForTheTrimmedWords() {
        TypedTextField<String> field = searchField();
        field.setValue("  tractor  ");

        ComponentUtil.fireEvent(field, new KeyPressEvent(field, "Enter"));

        View<?> current = UiTestUtils.getCurrentView();
        assertThat(current).isInstanceOf(DocumentSearchView.class);
        assertThat(DocumentSearchView.queryOf(currentQueryParameters())).isEqualTo("tractor");
        assertThat(searchField().getValue()).isEqualTo("tractor");
    }

    @Test
    void enterWithoutWordsOpensNothing() {
        TypedTextField<String> field = searchField();
        field.setValue("   ");

        ComponentUtil.fireEvent(field, new KeyPressEvent(field, "Enter"));

        View<?> current = UiTestUtils.getCurrentView();
        assertThat(current).isNotInstanceOf(DocumentSearchView.class);
    }

    @Test
    void theFieldIsEmptiedOutsideTheSearch() {
        TypedTextField<String> field = searchField();
        field.setValue("tractor");
        ComponentUtil.fireEvent(field, new KeyPressEvent(field, "Enter"));

        viewNavigators.view(UiTestUtils.getCurrentView(), RepositoryView.class).navigate();

        assertThat(searchField().getValue()).isEmpty();
    }

    private TypedTextField<String> searchField() {
        MainView mainView = UI.getCurrent().getInternals().getActiveRouterTargetsChain().stream()
                .filter(MainView.class::isInstance)
                .map(MainView.class::cast)
                .findFirst()
                .orElseThrow();

        return UiTestUtils.getComponent(mainView, "searchField");
    }

    private static QueryParameters currentQueryParameters() {
        return UI.getCurrent().getInternals().getActiveViewLocation().getQueryParameters();
    }
}
