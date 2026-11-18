package com.company.docai.app.service;

/**
 * Thrown when a business rule rejects an operation; its message is localized for the current user and shown as is.
 */
public class OperationRejectedException extends RuntimeException {

    public OperationRejectedException(String message) {
        super(message);
    }
}
