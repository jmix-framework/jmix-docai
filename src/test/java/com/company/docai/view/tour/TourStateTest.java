package com.company.docai.view.tour;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static com.company.docai.view.tour.TourChapter.AI_ASSISTANT;
import static com.company.docai.view.tour.TourChapter.DOCUMENT_ASSISTANT;
import static com.company.docai.view.tour.TourChapter.FILL_WITH_AI;
import static com.company.docai.view.tour.TourChapter.SEARCH_BY_MEANING;
import static com.company.docai.view.tour.TourChapter.UPLOAD;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The progress of the tour and its cookie, without Spring.
 */
class TourStateTest {

    @Test
    void theChaptersGoFromTheUploadToTheAssistantsNumberedFromOne() {
        assertThat(TourChapter.values())
                .containsExactly(UPLOAD, FILL_WITH_AI, SEARCH_BY_MEANING, DOCUMENT_ASSISTANT, AI_ASSISTANT);
        assertThat(Arrays.stream(TourChapter.values()).map(TourChapter::getNumber)).containsExactly(1, 2, 3, 4, 5);
        assertThat(Arrays.stream(TourChapter.values()).filter(chapter -> !chapter.isAiNeeded()))
                .containsExactly(UPLOAD);
    }

    @Test
    void theCookieNamesTheUploadAndTheFillingAsOneAndTwo() {
        TourState state = TourState.fromCookie("v1.a.12");

        assertThat(state.isDone(UPLOAD)).isTrue();
        assertThat(state.isDone(FILL_WITH_AI)).isTrue();
        assertThat(state.getDoneCount()).isEqualTo(2);
    }

    @Test
    void theUploadedDocumentIsRememberedOutsideTheCookieUntilTheRestart() {
        UUID document = UUID.randomUUID();
        TourState state = TourState.fromCookie("v1.a.1");

        assertThat(state.getUploadedDocumentId()).isEmpty();

        state.rememberUploaded(document);

        assertThat(state.getUploadedDocumentId()).contains(document);
        assertThat(state.cookieValue()).isEqualTo("v1.a.1");

        state.end();

        assertThat(state.getUploadedDocumentId()).contains(document);

        state.restart();

        assertThat(state.getUploadedDocumentId()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1.n.0", "v1.a.25", "v1.f.12345", "v1.e.3"})
    void aCanonicalCookieIsReadAndWrittenBackAsItWas(String value) {
        assertThat(TourState.fromCookie(value).cookieValue()).isEqualTo(value);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"garbage", "v2.a.12", "v1.x.12", "v1.a.16", "v1.a.", "v1.a.0.1", "V1.a.12", " v1.a.12",
            "v1.a.12\n"})
    void anUnknownCookieIsATourNotBegun(String value) {
        TourState state = TourState.fromCookie(value);

        assertThat(state.getStatus()).isEqualTo(TourState.Status.NOT_STARTED);
        assertThat(state.getDoneCount()).isZero();
        assertThat(state.cookieValue()).isEqualTo("v1.n.0");
    }

    @Test
    void repeatedAndUnorderedChaptersAreWrittenOnceInOrder() {
        TourState state = TourState.fromCookie("v1.a.5225");

        assertThat(state.cookieValue()).isEqualTo("v1.a.25");
    }

    @Test
    void eachStatusIsWrittenWithItsLetter() {
        TourState state = new TourState();
        state.markDone(UPLOAD);
        state.markDone(FILL_WITH_AI);

        assertThat(state.cookieValue()).isEqualTo("v1.n.12");

        state.start();
        assertThat(state.cookieValue()).isEqualTo("v1.a.12");

        state.finish();
        assertThat(state.cookieValue()).isEqualTo("v1.f.12");

        state.end();
        assertThat(state.cookieValue()).isEqualTo("v1.e.12");
    }

    @Test
    void docGreetsByItselfOnlyBeforeTheTourWithTheSwitchAndTheAiOn() {
        TourState fresh = new TourState();

        assertThat(fresh.greetsByItself(true, true)).isTrue();
        assertThat(fresh.greetsByItself(false, true)).isFalse();
        assertThat(fresh.greetsByItself(true, false)).isFalse();

        for (String value : new String[]{"v1.a.12", "v1.f.12345", "v1.e.0"}) {
            assertThat(TourState.fromCookie(value).greetsByItself(true, true)).as(value).isFalse();
        }
    }

    @Test
    void aRunningTourStaysRunningWhateverTheSwitchAndTheAi() {
        TourState running = TourState.fromCookie("v1.a.12");

        assertThat(running.getStatus()).isEqualTo(TourState.Status.RUNNING);
        assertThat(running.greetsByItself(false, false)).isFalse();
    }

    @Test
    void startBeginsOnlyATourNotBegun() {
        TourState state = new TourState();
        state.start();

        assertThat(state.getStatus()).isEqualTo(TourState.Status.RUNNING);

        TourState ended = TourState.fromCookie("v1.e.0");
        ended.start();

        assertThat(ended.getStatus()).isEqualTo(TourState.Status.ENDED);
    }

    @Test
    void chaptersDoneBeforeTheStartCount() {
        TourState state = new TourState();

        assertThat(state.markDone(SEARCH_BY_MEANING)).isTrue();

        state.start();

        assertThat(state.isDone(SEARCH_BY_MEANING)).isTrue();
        assertThat(state.getDoneCount()).isEqualTo(1);
    }

    @Test
    void aTourOverMarksNothing() {
        TourState finished = TourState.fromCookie("v1.f.1");
        TourState ended = TourState.fromCookie("v1.e.1");

        assertThat(finished.markDone(FILL_WITH_AI)).isFalse();
        assertThat(ended.markDone(FILL_WITH_AI)).isFalse();
        assertThat(finished.cookieValue()).isEqualTo("v1.f.1");
        assertThat(ended.cookieValue()).isEqualTo("v1.e.1");
    }

    @Test
    void aSecondMarkOfTheSameChapterChangesNothing() {
        TourState state = TourState.fromCookie("v1.a.1");

        assertThat(state.markDone(UPLOAD)).isFalse();
        assertThat(state.cookieValue()).isEqualTo("v1.a.1");
    }

    @Test
    void onlyTheRestartTakesTheMarksAway() {
        TourState state = TourState.fromCookie("v1.a.12");
        state.skip(DOCUMENT_ASSISTANT);
        state.markExplained(FILL_WITH_AI);
        state.end();

        assertThat(state.cookieValue()).isEqualTo("v1.e.12");

        state.restart();

        assertThat(state.cookieValue()).isEqualTo("v1.n.0");
        assertThat(state.markExplained(FILL_WITH_AI)).isTrue();
        assertThat(state.next(chapter -> true)).contains(UPLOAD);
    }

    @Test
    void theNextChapterPassesOverDoneSkippedAndUnavailableOnes() {
        TourState state = TourState.fromCookie("v1.a.1");
        state.skip(FILL_WITH_AI);
        Set<TourChapter> unavailable = EnumSet.of(SEARCH_BY_MEANING);

        assertThat(state.next(chapter -> !unavailable.contains(chapter))).contains(DOCUMENT_ASSISTANT);
    }

    @Test
    void onlySkippedOrUnavailableChaptersLeftMeanTheFinale() {
        TourState state = TourState.fromCookie("v1.a.12");
        state.skip(SEARCH_BY_MEANING);

        assertThat(state.next(chapter -> chapter != DOCUMENT_ASSISTANT && chapter != AI_ASSISTANT)).isEmpty();
    }

    @Test
    void anExplanationIsShownOnceAPass() {
        TourState state = new TourState();

        assertThat(state.markExplained(UPLOAD)).isTrue();
        assertThat(state.markExplained(UPLOAD)).isFalse();
    }

    @Test
    void theFirstMainViewOfASessionTakesTheCookieAndTheNextOnesTheSession() {
        TourState first = TourState.of(null, "v1.a.2");

        assertThat(first.cookieValue()).isEqualTo("v1.a.2");

        first.markDone(UPLOAD);
        TourState next = TourState.of(first, "v1.a.2");

        assertThat(next).isSameAs(first);
        assertThat(next.differsFrom("v1.a.2")).isTrue();
        assertThat(next.differsFrom("v1.a.12")).isFalse();
        assertThat(next.differsFrom(null)).isTrue();
    }
}
