package com.company.dms.app.service;

/**
 * Thrown when a business rule rejects an operation. The message is already localized
 * for the current user and is shown to them as is.
 */
public class OperationRejectedException extends RuntimeException {

    public OperationRejectedException(String message) {
        super(message);
    }
}
