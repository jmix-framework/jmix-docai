package com.company.docai.app.ui.component.tour;

import com.company.docai.DocAiApplication;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.TestTours;
import com.company.docai.view.main.MainView;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.progressbar.ProgressBar;
import io.jmix.core.Messages;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Doc as a component: the progress, the chapters, the bubble and the cookie.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class TourPanelTest {

    @Autowired
    private UiComponents uiComponents;

    @Autowired
    private Messages messages;

    private TourPanel panel;

    @BeforeEach
    void setUp() {
        panel = uiComponents.create(TourPanel.class);
        UI.getCurrent().add(panel);
    }

    @Test
    void theProgressShowsOnDocInHisNameInThePanelAndOnItsBar() {
        panel.setProgress(3);

        assertThat(TestTours.text(panel, "tour-doc-badge")).isEqualTo("3/5");
        assertThat(TestTours.withClass(panel, "tour-doc").getFirst().getElement().getAttribute("aria-label"))
                .isEqualTo(messages.formatMessage(TourPanel.class, "doc.ariaLabel", 3));
        assertThat(TestTours.text(panel, "tour-panel-progress"))
                .isEqualTo(messages.formatMessage(TourPanel.class, "panel.progress", 3));
        assertThat(((ProgressBar) TestTours.withClass(panel, "tour-progress").getFirst()).getValue()).isEqualTo(3);
    }

    @Test
    void theRowsKeepTheirPlacesAndShowGivesTheNumberOfItsChapter() {
        List<Integer> shown = new ArrayList<>();
        panel.setShowHandler(shown::add);

        panel.setRows(List.of(
                new TourPanel.Row(1, "One", TourPanel.RowState.DONE, null),
                new TourPanel.Row(2, "Two", TourPanel.RowState.NEXT, null),
                new TourPanel.Row(3, "Three", TourPanel.RowState.UNAVAILABLE, "Away"),
                new TourPanel.Row(4, "Four", TourPanel.RowState.OPEN, null),
                new TourPanel.Row(5, "Five", TourPanel.RowState.OPEN, null)));

        assertThat(TestTours.rowStates(panel)).containsExactly("done", "next", "unavailable", "open", "open");
        assertThat(TestTours.text(TestTours.withClass(panel, "tour-row").getFirst(), "tour-row-done"))
                .isEqualTo(messages.getMessage(TourPanel.class, "chapter.doneLabel"));
        assertThat(TestTours.text(TestTours.withClass(panel, "tour-row").get(2), "tour-row-reason"))
                .isEqualTo("Away");
        assertThat(TestTours.showButton(panel, 3).isEnabled()).isFalse();
        assertThat(TestTours.showButton(panel, 2).getElement().getAttribute("aria-label"))
                .isEqualTo(messages.formatMessage(TourPanel.class, "showButton.ariaLabel", "Two"));

        TestTours.showButton(panel, 2).click();
        TestTours.showButton(panel, 1).click();

        assertThat(shown).containsExactly(2, 1);
    }

    @Test
    void theBubbleRunsTheActionsOfItsButtonsAndOffersToCloseOnlyWhenAsked() {
        List<String> clicked = new ArrayList<>();

        panel.showBubble("Hello", List.of(new TourPanel.Choice("Go", true, () -> clicked.add("go")),
                new TourPanel.Choice("Stay", false, () -> clicked.add("stay"))), false);

        Button close = (Button) TestTours.withClass(panel, "tour-bubble-close").getFirst();

        assertThat(TestTours.text(panel, "tour-bubble-text")).isEqualTo("Hello");
        assertThat(close.isVisible()).isFalse();

        TestTours.button(panel, "Stay").click();
        TestTours.button(panel, "Go").click();

        assertThat(clicked).containsExactly("stay", "go");

        panel.showBubble("Bye", List.of(), true);

        assertThat(close.isVisible()).isTrue();
    }

    @Test
    void anOpenPanelHidesTheBubbleAndDocTellsWhetherItIsOpen() {
        panel.showBubble("Hello", List.of(), false);

        panel.setExpanded(true);

        assertThat(panel.getElement().hasAttribute("expanded")).isTrue();
        assertThat(TestTours.withClass(panel, "tour-doc").getFirst().getElement().getAttribute("aria-expanded"))
                .isEqualTo("true");
        assertThat(TestTours.withClass(panel, "tour-bubble").getFirst().isVisible()).isFalse();

        panel.setExpanded(false);

        assertThat(panel.isExpanded()).isFalse();
        assertThat(TestTours.withClass(panel, "tour-doc").getFirst().getElement().getAttribute("aria-expanded"))
                .isEqualTo("false");
    }

    @Test
    void tabGoesFromDocIntoHisBubbleAndHisPanel() {
        List<String> order = panel.getElement()
                .getChildren()
                .map(child -> child.getAttribute("class"))
                .toList();

        assertThat(order).containsExactly("tour-live", "tour-doc", "tour-bubble", "tour-panel");
    }

    @Test
    void theBrowserGetsThePlaceOfTheCookieAndEveryValueToWrite() {
        TestTours.writtenStates();

        panel.setCookieTarget("/docai", true);
        panel.writeState("v1.a.12");

        assertThat(panel.getElement().getAttribute("cookie-path")).isEqualTo("/docai");
        assertThat(panel.getElement().hasAttribute("cookie-secure")).isTrue();
        assertThat(TestTours.writtenStates()).containsExactly("v1.a.12");

        panel.setCookieTarget("", false);

        assertThat(panel.getElement().getAttribute("cookie-path")).isEqualTo("/");
        assertThat(panel.getElement().hasAttribute("cookie-secure")).isFalse();
    }

    @Test
    void docIsTheSquareOfTheLogoWithEyesThatTheThemeCanMove() {
        String picture = TourPanel.createDoc().getElement().getProperty("innerHTML");

        assertThat(picture).contains("class=\"doc-eyes\"", "class=\"doc-pupil\"", "class=\"doc-eyes-glad\"");
    }
}
