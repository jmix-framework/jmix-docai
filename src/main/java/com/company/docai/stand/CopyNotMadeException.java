package com.company.docai.stand;

/**
 * The copy of the demo data a login needs cannot be made, as when OpenSearch does not answer the clone or the script
 * of the template cannot be read: the demo is unavailable for now. The message names the language only, and the cause
 * tells the rest to the log.
 */
public class CopyNotMadeException extends RuntimeException {

    public CopyNotMadeException(String language, Throwable cause) {
        super("A copy of the template of " + language + " is not made", cause);
    }
}
