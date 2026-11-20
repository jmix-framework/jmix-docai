package com.company.docai.view.tour;

import com.company.docai.DocAiApplication;
import com.company.docai.app.ui.component.tour.TourPanel;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.TestTours;
import com.company.docai.view.main.MainView;
import com.company.docai.view.repository.RepositoryView;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import io.jmix.core.Messages;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.usermenu.UserMenu;
import io.jmix.flowui.kit.component.usermenu.ActionUserMenuItem;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.view.View;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static com.company.docai.test_support.TestTours.message;
import static com.company.docai.test_support.TestTours.startTour;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * The tour of an employee with the AI off; the tests open the tour themselves.
 */
@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class TourUiTest {

    @Autowired
    private ViewNavigators viewNavigators;

    @Autowired
    private Tour tour;

    @Autowired
    private Messages messages;

    @Test
    void withTheSwitchOffDocDoesNotGreetByHimself() {
        assertThat(TestTours.panel().isShown()).isFalse();
        assertThat(TestTours.state().getStatus()).isEqualTo(TourState.Status.NOT_STARTED);
    }

    @Test
    void docGreetsWithStartAndSkipAndTheScreenReaderHearsHim() {
        TestTours.guide().open(true);
        TourPanel panel = TestTours.panel();

        assertThat(panel.isShown()).isTrue();
        assertThat(panel.isExpanded()).isFalse();
        assertThat(TestTours.text(panel, "tour-bubble-text")).isEqualTo(message(messages, "greeting.text"));
        assertThat(TestTours.buttonTexts(panel, "tour-bubble-actions"))
                .containsExactly(message(messages, "startButton"), message(messages, "skipButton"));
        assertThat(TestTours.text(panel, "tour-live")).isEqualTo(message(messages, "greeting.text"));
        assertThat(TestTours.withClass(panel, "tour-live").getFirst().getElement().getAttribute("role"))
                .isEqualTo("status");
    }

    @Test
    void aRunningTourInTheCookieShowsDocFoldedWithItsProgress() {
        TestTours.browserCookie("v1.a.12");

        TourPanel panel = newMainView();

        assertThat(panel.isShown()).isTrue();
        assertThat(panel.isExpanded()).isFalse();
        assertThat(TestTours.text(panel, "tour-doc-badge")).isEqualTo("2/5");
        assertThat(TestTours.withClass(panel, "tour-doc").getFirst().getElement().getAttribute("aria-label"))
                .isEqualTo(messages.formatMessage(TourPanel.class, "doc.ariaLabel", 2));
        assertThat(TestTours.withClass(panel, "tour-bubble").getFirst().isVisible()).isFalse();
    }

    @Test
    void docRollsInWhenARunningTourOpensInANewPage() {
        TestTours.browserCookie("v1.a.12");
        TestTours.playedMotions();

        newMainView();

        assertThat(TestTours.playedMotions()).containsExactly("roll-in");
    }

    @Test
    void aFinishedTourInTheCookieShowsNothing() {
        TestTours.browserCookie("v1.f.12345");

        TourPanel panel = newMainView();

        assertThat(panel.isShown()).isFalse();
        assertThat(TestTours.state().getStatus()).isEqualTo(TourState.Status.FINISHED);
    }

    @Test
    void skipEndsTheTourWithAGoodbyeAndANewMainViewShowsNoTour() {
        TestTours.guide().open(true);
        TestTours.writtenStates();

        TestTours.button(TestTours.panel(), message(messages, "skipButton")).click();

        assertThat(TestTours.state().getStatus()).isEqualTo(TourState.Status.ENDED);
        assertThat(TestTours.text(TestTours.panel(), "tour-bubble-text")).isEqualTo(message(messages, "goodbye.text"));
        assertThat(TestTours.writtenStates()).containsExactly("v1.e.0");
        assertThat(newMainView().isShown()).isFalse();
    }

    @Test
    void startGoesHomeExplainsItAndOpensThePanelWhereOnlyTheUploadIsLeftWithoutAi() {
        viewNavigators.view(UiTestUtils.getCurrentView(), RepositoryView.class)
                .navigate();

        TestTours.guide().open(true);
        TourPanel panel = TestTours.panel();

        TestTours.button(panel, message(messages, "startButton")).click();

        assertThat(UiTestUtils.<View<?>>getCurrentView()).isInstanceOf(MainView.class);
        assertThat(TestTours.state().getStatus()).isEqualTo(TourState.Status.RUNNING);
        assertThat(TestTours.text(panel, "tour-bubble-text")).isEqualTo(message(messages, "home.text"));

        TestTours.button(panel, message(messages, "nextButton")).click();

        assertThat(panel.isExpanded()).isTrue();
        assertThat(TestTours.text(panel, "tour-line")).isEqualTo(message(messages, "aiOff.text"));
        assertThat(TestTours.rowStates(panel))
                .containsExactly("next", "unavailable", "unavailable", "unavailable", "unavailable");
        assertThat(TestTours.text(TestTours.withClass(panel, "tour-row").get(1), "tour-row-reason"))
                .isEqualTo(message(messages, "reason.ai"));
        assertThat(TestTours.showButton(panel, 1).isEnabled()).isTrue();
        assertThat(TestTours.showButton(panel, 2).isEnabled()).isFalse();
        assertThat(TestTours.showButton(panel, 5).isEnabled()).isFalse();
    }

    @Test
    void endTourInThePanelEndsItAtOnce() {
        TourPanel panel = startTour(messages);
        TestTours.writtenStates();

        TestTours.button(panel, messages.getMessage(TourPanel.class, "panel.endButton")).click();

        assertThat(TestTours.state().getStatus()).isEqualTo(TourState.Status.ENDED);
        assertThat(UiTestUtils.getLastOpenedDialog()).isNull();
        assertThat(panel.isExpanded()).isFalse();
        assertThat(TestTours.text(panel, "tour-bubble-text")).isEqualTo(message(messages, "goodbye.text"));
        assertThat(TestTours.writtenStates()).containsExactly("v1.e.0");
    }

    @Test
    void theOnlyChapterLeftDoneLeadsToTheFinaleWithoutTheDiscussionWhenTheAiIsOff() {
        startTour(messages);
        TestTours.writtenStates();

        tour.done(TourChapter.UPLOAD);

        assertThat(TestTours.state().getStatus()).isEqualTo(TourState.Status.FINISHED);
        assertThat(TestTours.text(TestTours.panel(), "tour-bubble-text"))
                .isEqualTo(message(messages, "finale.noAiText"));
        assertThat(TestTours.writtenStates()).last().isEqualTo("v1.f.1");
    }

    @Test
    void finishTakesTheBubbleAwayAsDocLeaves() {
        startTour(messages);
        tour.done(TourChapter.UPLOAD);

        TestTours.button(TestTours.panel(), message(messages, "finishButton")).click();

        assertThat(TestTours.withClass(TestTours.panel(), "tour-bubble").getFirst().isVisible()).isFalse();
    }

    @Test
    void aChapterDoneUnderAWindowWaitsForTheWindowToClose() {
        startTour(messages);

        ((ActionUserMenuItem) userMenu().getItem("profileMenuItem")).getAction()
                .actionPerform(userMenu());

        View<?> profile = UiTestUtils.getLastOpenedViewDialog();
        assertThat(profile).isNotNull();

        tour.done(TourChapter.UPLOAD);

        assertThat(TestTours.state().getStatus()).isEqualTo(TourState.Status.RUNNING);
        assertThat(TestTours.text(TestTours.panel(), "tour-doc-badge")).isEqualTo("1/5");

        profile.closeWithDefaultAction();

        assertThat(TestTours.state().getStatus()).isEqualTo(TourState.Status.FINISHED);
        assertThat(TestTours.text(TestTours.panel(), "tour-bubble-text"))
                .isEqualTo(message(messages, "finale.noAiText"));
    }

    @Test
    void aChapterDoneInTheRequestThatOpensAWindowWaitsForTheWindowToClose() {
        startTour(messages);

        // a window opened in the request that reports a chapter joins the page only before the response
        TestTours.inOneRequest(() -> {
            ((ActionUserMenuItem) userMenu().getItem("profileMenuItem")).getAction()
                    .actionPerform(userMenu());

            tour.done(TourChapter.UPLOAD);
        });

        View<?> profile = UiTestUtils.getLastOpenedViewDialog();
        assertThat(profile).isNotNull();

        assertThat(TestTours.state().getStatus()).isEqualTo(TourState.Status.RUNNING);
        assertThat(TestTours.text(TestTours.panel(), "tour-doc-badge")).isEqualTo("1/5");

        profile.closeWithDefaultAction();

        assertThat(TestTours.state().getStatus()).isEqualTo(TourState.Status.FINISHED);
        assertThat(TestTours.text(TestTours.panel(), "tour-bubble-text"))
                .isEqualTo(message(messages, "finale.noAiText"));
    }

    @Test
    void aFailureOfTheGuideStaysInTheTourAndTheMarkStays() {
        TourGuide broken = mock(TourGuide.class);
        doThrow(new IllegalStateException("A broken guide")).when(broken).chapterDone(TourChapter.UPLOAD);
        ComponentUtil.setData(UI.getCurrent(), TourGuide.class, broken);

        assertThatCode(() -> tour.done(TourChapter.UPLOAD)).doesNotThrowAnyException();

        assertThat(TestTours.state().isDone(TourChapter.UPLOAD)).isTrue();
    }

    @Test
    void aGuideTakenOffThePageInTheRequestOfAResultAnswersNothing() {
        startTour(messages);
        TourGuide guide = TestTours.guide();

        TestTours.inOneRequest(() -> {
            tour.done(TourChapter.UPLOAD);
            guide.detach();
        });

        assertThat(TestTours.state().isDone(TourChapter.UPLOAD)).isTrue();
        assertThat(TestTours.state().getStatus()).isEqualTo(TourState.Status.RUNNING);
    }

    @Test
    void aChapterDoneBeforeTheStartCountsQuietly() {
        TestTours.writtenStates();

        tour.done(TourChapter.UPLOAD);

        assertThat(TestTours.state().isDone(TourChapter.UPLOAD)).isTrue();
        assertThat(TestTours.state().getStatus()).isEqualTo(TourState.Status.NOT_STARTED);
        assertThat(TestTours.panel().isShown()).isFalse();
        assertThat(TestTours.text(TestTours.panel(), "tour-doc-badge")).isEqualTo("1/5");
        assertThat(TestTours.writtenStates()).containsExactly("v1.n.1");
    }

    @Test
    void anUploadThatCreatedNothingLeavesTheFirstChapterOpen() {
        tour.uploaded(List.of());

        assertThat(TestTours.state().isDone(TourChapter.UPLOAD)).isFalse();
        assertThat(TestTours.state().getUploadedDocumentId()).isEmpty();
    }

    @Test
    void afterTheTourEndsNoChapterIsMarked() {
        TestTours.guide().open(true);
        TestTours.button(TestTours.panel(), message(messages, "skipButton")).click();

        tour.done(TourChapter.UPLOAD);

        assertThat(TestTours.state().isDone(TourChapter.UPLOAD)).isFalse();
    }

    @Test
    void takeTheTourAgainFromTheUserMenuStartsOverWhateverTheSwitchAndTheAi() {
        tour.done(TourChapter.UPLOAD);
        TestTours.guide().open(true);
        TestTours.button(TestTours.panel(), message(messages, "skipButton")).click();
        TestTours.writtenStates();

        ((ActionUserMenuItem) userMenu().getItem("tourMenuItem")).getAction()
                .actionPerform(userMenu());

        assertThat(TestTours.state().getStatus()).isEqualTo(TourState.Status.NOT_STARTED);
        assertThat(TestTours.state().getDoneCount()).isZero();
        assertThat(TestTours.panel().isShown()).isTrue();
        assertThat(TestTours.text(TestTours.panel(), "tour-bubble-text")).isEqualTo(message(messages, "greeting.text"));
        assertThat(TestTours.writtenStates()).contains("v1.n.0");
    }

    @Test
    void theWindowOfADiscussionMovesDocToTheLeftUntilItCloses() {
        TestTours.guide().open(true);

        tour.discussionOpened(true);

        assertThat(TestTours.panel().getElement().getAttribute("dock")).isEqualTo("left");

        tour.discussionOpened(false);

        assertThat(TestTours.panel().getElement().getAttribute("dock")).isEqualTo("right");
    }

    private TourPanel newMainView() {
        // a navigation from the main view to itself builds a new one, as a reload of the page does
        viewNavigators.view(UiTestUtils.getCurrentView(), MainView.class)
                .navigate();

        return TestTours.panel();
    }

    private UserMenu userMenu() {
        return UiTestUtils.getComponent(UiTestUtils.getCurrentView(), "userMenu");
    }
}
