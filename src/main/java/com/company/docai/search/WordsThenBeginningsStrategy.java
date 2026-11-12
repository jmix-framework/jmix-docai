package com.company.docai.search;

import io.jmix.search.SearchProperties;
import io.jmix.search.searching.SearchContext;
import io.jmix.search.searching.SearchRequestContext;
import io.jmix.searchopensearch.searching.strategy.OpenSearchSearchStrategy;
import io.jmix.searchopensearch.searching.strategy.impl.AnyTermAnyFieldOpenSearchSearchStrategy;
import io.jmix.searchopensearch.searching.strategy.impl.StartsWithOpenSearchSearchStrategy;
import org.jspecify.annotations.NullMarked;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The search of the results page: first the documents with a word of the text or one of its forms, then the documents
 * with a word that only begins with a word of the text of three letters or more, each part the most relevant first.
 * The words are what the add-on strategy {@code anyTermAnyField} finds and the beginnings what {@code startsWith}
 * finds for the search palette: each of them builds its query with the indexes and the fields the user may read, and
 * this strategy joins the two queries in one request, which the add-on pages and marks as its own. A text of more than
 * eight words of three letters or more, or of none, is searched by whole words only.
 */
@NullMarked
@Component
public class WordsThenBeginningsStrategy implements OpenSearchSearchStrategy {

    /**
     * The name that a search gives to use the strategy.
     */
    public static final String NAME = "wordsThenBeginnings";

    // a person types a few words, and a longer text, such as a pasted sentence, keeps to whole words as before: with
    // the beginnings a word costs about three clauses for each field, and OpenSearch refuses over 1024 clauses
    private static final int MAX_BEGINNINGS = 8;

    // Lucene cannot order the hits by the clause that matched, so the whole words get a constant above any score of
    // the beginnings alone: BM25 gives a word at most its idf, under 20 even in millions of documents
    private static final float WORDS_FIRST = 1000;

    // the analyzer of the index splits "and/or" or "contract.pdf" into two words, and a short one of them must not
    // become a beginning, nor slip past the count of words
    private static final Pattern WORD_BREAK = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final AnyTermAnyFieldOpenSearchSearchStrategy anyTermAnyField;
    private final StartsWithOpenSearchSearchStrategy startsWith;
    private final SearchProperties searchProperties;

    public WordsThenBeginningsStrategy(AnyTermAnyFieldOpenSearchSearchStrategy anyTermAnyField,
                                       StartsWithOpenSearchSearchStrategy startsWith,
                                       SearchProperties searchProperties) {
        this.anyTermAnyField = anyTermAnyField;
        this.startsWith = startsWith;
        this.searchProperties = searchProperties;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void configureRequest(SearchRequestContext<SearchRequest.Builder> requestContext) {
        SearchContext searchContext = requestContext.getSearchContext();
        List<String> beginnings = beginningsOf(searchContext.getSearchText());

        if (beginnings.isEmpty() || beginnings.size() > MAX_BEGINNINGS) {
            anyTermAnyField.configureRequest(requestContext);
            return;
        }

        SearchContext beginningsContext = new SearchContext(String.join(" ", beginnings))
                .setEntities(searchContext.getEntities());

        SearchRequestContext<SearchRequest.Builder> wordsRequest = requestOf(anyTermAnyField, searchContext);
        SearchRequestContext<SearchRequest.Builder> beginningsRequest = requestOf(startsWith, beginningsContext);

        if (!wordsRequest.isRequestPossible() || !beginningsRequest.isRequestPossible()) {
            requestContext.setEmptyResult();
            return;
        }

        Query wordsFirst = Query.of(query -> query.bool(bool -> bool
                .must(queryOf(wordsRequest))
                .should(lift -> lift.matchAll(all -> all.boost(WORDS_FIRST)))));

        Query beginningsQuery = queryOf(beginningsRequest);

        // the better of the two: the words over the constant, or the beginnings for a document without a word
        requestContext.getRequestBuilder()
                .query(query -> query.disMax(best -> best
                        .queries(wordsFirst, beginningsQuery)
                        .tieBreaker(0f)));

        requestContext.setPositiveResult(wordsRequest.getIndexSearchRequestScopes());
    }

    /**
     * Returns the words of the text that a search by beginnings takes. A word ends at every character that is neither
     * a letter nor a digit, so "and/or" and "contract.pdf" are two words each, and the words kept have at least as
     * many letters and digits as the shortest beginning that the index keeps, three by default. A shorter word would
     * match, and mark, every word it begins. The search palette leaves out the same words.
     */
    public List<String> beginningsOf(String text) {
        return WORD_BREAK.splitAsStream(text)
                .filter(word -> word.codePointCount(0, word.length()) >= searchProperties.getMinPrefixLength())
                .toList();
    }

    // an add-on strategy writes its query and its scope into the request it gets
    private static SearchRequestContext<SearchRequest.Builder> requestOf(OpenSearchSearchStrategy strategy,
                                                                        SearchContext searchContext) {
        SearchRequestContext<SearchRequest.Builder> request =
                new SearchRequestContext<>(new SearchRequest.Builder(), searchContext);

        strategy.configureRequest(request);

        return request;
    }

    // only a possible request is read, and an add-on strategy writes a query into every request it finds possible
    private static Query queryOf(SearchRequestContext<SearchRequest.Builder> request) {
        Query query = request.getRequestBuilder()
                .build()
                .query();

        return Objects.requireNonNull(query, "the add-on strategy wrote no query");
    }
}
