package com.company.dms.view.document;

import io.jmix.core.Messages;
import io.jmix.core.security.CurrentAuthentication;
import org.springframework.stereotype.Component;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;

/**
 * Formats a file size for people: "980 B", "310 KB", "1.2 MB", in the user's language.
 */
@Component
public class FileSizeFormatter {

    private static final double KILOBYTE = 1024;

    private final Messages messages;
    private final CurrentAuthentication currentAuthentication;

    public FileSizeFormatter(Messages messages, CurrentAuthentication currentAuthentication) {
        this.messages = messages;
        this.currentAuthentication = currentAuthentication;
    }

    /**
     * Uses the largest unit, up to megabytes, that keeps the number at least 1, with one decimal at most.
     */
    public String format(long bytes) {
        if (bytes < KILOBYTE) {
            return messages.formatMessage(getClass(), "bytes", bytes);
        }

        double kilobytes = bytes / KILOBYTE;
        if (kilobytes < KILOBYTE) {
            return messages.formatMessage(getClass(), "kilobytes", round(kilobytes));
        }

        return messages.formatMessage(getClass(), "megabytes", round(kilobytes / KILOBYTE));
    }

    private String round(double value) {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(currentAuthentication.getLocale());
        return new DecimalFormat("#,##0.#", symbols).format(value);
    }
}
