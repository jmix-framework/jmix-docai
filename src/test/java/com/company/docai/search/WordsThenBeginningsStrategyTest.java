package com.company.docai.search;

import io.jmix.search.SearchProperties;
import io.jmix.search.index.IndexConfiguration;
import io.jmix.search.searching.IndexSearchRequestScope;
import io.jmix.search.searching.SearchContext;
import io.jmix.search.searching.SearchRequestContext;
import io.jmix.search.searching.SearchRequestContext.ProcessingState;
import io.jmix.searchopensearch.searching.strategy.OpenSearchSearchStrategy;
import io.jmix.searchopensearch.searching.strategy.impl.AnyTermAnyFieldOpenSearchSearchStrategy;
import io.jmix.searchopensearch.searching.strategy.impl.StartsWithOpenSearchSearchStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch._types.query_dsl.DisMaxQuery;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The request of the results page, made of the two add-on strategies. They are replaced here: each one notes the text
 * it is asked for and answers with a query that names it.
 */
class WordsThenBeginningsStrategyTest {

    private final AnyTermAnyFieldOpenSearchSearchStrategy anyTermAnyField =
            mock(AnyTermAnyFieldOpenSearchSearchStrategy.class);
    private final StartsWithOpenSearchSearchStrategy startsWith = mock(StartsWithOpenSearchSearchStrategy.class);
    private final SearchProperties searchProperties = mock(SearchProperties.class);
    private final WordsThenBeginningsStrategy strategy =
            new WordsThenBeginningsStrategy(anyTermAnyField, startsWith, searchProperties);
    private final List<String> asked = new ArrayList<>();

    @BeforeEach
    void replaceTheAddOnStrategies() {
        when(searchProperties.getMinPrefixLength()).thenReturn(3);
        answerAs(anyTermAnyField, "words");
        answerAs(startsWith, "beginnings");
    }

    @Test
    void theWholeWordsAreAskedOnceAndLiftedAboveTheBeginnings() {
        Query query = queryOf("lease plan");
        DisMaxQuery best = query.disMax();

        assertThat(best.tieBreaker()).isZero();
        assertThat(best.queries()).hasSize(2);
        assertThat(best.queries().getFirst().bool().must().getFirst().toJsonString()).contains("words");
        assertThat(best.queries().getFirst().bool().should().getFirst().matchAll().boost()).isEqualTo(1000f);
        assertThat(best.queries().getLast().toJsonString()).contains("beginnings");
        assertThat(query.toJsonString()).containsOnlyOnce("words");
    }

    @Test
    void theBeginningsTakeTheWordsOfThreeLettersAndDigitsOrMoreAndTheWordsTakeTheWholeText() {
        queryOf("a lease on 45 plan");

        assertThat(asked).containsExactly("words: a lease on 45 plan", "beginnings: lease plan");
    }

    @Test
    void eightLongerWordsAreSearchedByTheirBeginningsAndNineOrShortWordsOnlyByWholeWords() {
        queryOf("one two three four five six seven eight");
        queryOf("one two three four five six seven eight nine");
        queryOf("a on 45");

        assertThat(asked).containsExactly(
                "words: one two three four five six seven eight",
                "beginnings: one two three four five six seven eight",
                "words: one two three four five six seven eight nine",
                "words: a on 45");
    }

    @Test
    void aWordEndsAtEveryCharacterThatIsNeitherALetterNorADigit() {
        assertThat(strategy.beginningsOf("and/or contract-offer lease.pdf №112 12.03.2026 a-b-c"))
                .containsExactly("and", "contract", "offer", "lease", "pdf", "112", "2026");
    }

    @Test
    void theWordsOfAJoinedWordCountOneByOne() {
        queryOf("one-two-three-four-five-six-seven-eight");
        queryOf("one-two-three-four-five-six-seven-eight-nine");
        queryOf("a-b-c-d-e-f-g-h-i");

        assertThat(asked).containsExactly(
                "words: one-two-three-four-five-six-seven-eight",
                "beginnings: one two three four five six seven eight",
                "words: one-two-three-four-five-six-seven-eight-nine",
                "words: a-b-c-d-e-f-g-h-i");
    }

    @Test
    void aUserWhoMaySearchNoEntityGetsNothing() {
        doAnswer(invocation -> {
            invocation.<SearchRequestContext<SearchRequest.Builder>>getArgument(0).setEmptyResult();
            return null;
        }).when(anyTermAnyField).configureRequest(any());

        doAnswer(invocation -> {
            invocation.<SearchRequestContext<SearchRequest.Builder>>getArgument(0).setEmptyResult();
            return null;
        }).when(startsWith).configureRequest(any());

        SearchRequestContext<SearchRequest.Builder> request = request("lease");
        strategy.configureRequest(request);

        assertThat(request.getProcessingResult()).isEqualTo(ProcessingState.NO_AVAILABLE_ENTITIES);
    }

    private Query queryOf(String text) {
        SearchRequestContext<SearchRequest.Builder> request = request(text);
        strategy.configureRequest(request);

        return request.getRequestBuilder()
                .build()
                .query();
    }

    private static SearchRequestContext<SearchRequest.Builder> request(String text) {
        return new SearchRequestContext<>(new SearchRequest.Builder(), new SearchContext(text).setEntities("Document"));
    }

    private void answerAs(OpenSearchSearchStrategy addOnStrategy, String name) {
        doAnswer(invocation -> {
            SearchRequestContext<SearchRequest.Builder> request = invocation.getArgument(0);
            asked.add(name + ": " + request.getSearchContext().getSearchText());

            request.getRequestBuilder()
                    .query(query -> query.term(term -> term.field("strategy")
                            .value(value -> value.stringValue(name))));

            request.setPositiveResult(List.of(new IndexSearchRequestScope(mock(IndexConfiguration.class), Set.of())));

            return null;
        }).when(addOnStrategy).configureRequest(any());
    }
}
