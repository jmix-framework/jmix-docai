package com.company.docai.search;

/**
 * The search engine does not answer.
 */
public class SearchUnavailableException extends RuntimeException {

    public SearchUnavailableException(Throwable cause) {
        super(cause);
    }
}
