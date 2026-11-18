package com.company.docai.stand;

/**
 * The template of the language a visitor logs in with is not ready yet, as while it is built or checked.
 */
public class TemplateNotReadyException extends RuntimeException {

    public TemplateNotReadyException(String language) {
        super("The template of " + language + " is not ready yet");
    }
}
