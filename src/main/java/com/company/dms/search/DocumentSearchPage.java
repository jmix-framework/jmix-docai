package com.company.dms.search;

import io.jmix.search.searching.SearchResult;

import java.util.List;

/**
 * A page of found documents.
 *
 * @param hits   the documents of the page, the most relevant first
 * @param rest   the documents found beyond the page; {@link DocumentSearch#next} starts with them
 * @param result the last answer of the index, which {@link DocumentSearch#next} continues
 */
public record DocumentSearchPage(List<DocumentSearchHit> hits, List<DocumentSearchHit> rest, SearchResult result) {

    public DocumentSearchPage {
        hits = List.copyOf(hits);
        rest = List.copyOf(rest);
    }

    /**
     * Tells whether the next page has at least one document.
     */
    public boolean hasMore() {
        return !rest.isEmpty();
    }
}
