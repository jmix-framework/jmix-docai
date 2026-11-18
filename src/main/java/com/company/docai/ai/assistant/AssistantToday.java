package com.company.docai.ai.assistant;

import io.jmix.core.TimeSource;
import io.jmix.core.security.CurrentAuthentication;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Tells the assistants what day it is for the person who asks.
 */
@Component
public class AssistantToday {

    private final TimeSource timeSource;
    private final CurrentAuthentication currentAuthentication;

    public AssistantToday(TimeSource timeSource, CurrentAuthentication currentAuthentication) {
        this.timeSource = timeSource;
        this.currentAuthentication = currentAuthentication;
    }

    /**
     * Returns today's date in the person's time zone as sentences for a system prompt, without curly braces.
     */
    public String sentences() {
        ZonedDateTime now = timeSource.now().withZoneSameInstant(currentAuthentication.getTimeZone().toZoneId());

        String weekday = now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);

        return "Today is " + now.toLocalDate() + " (" + weekday + "), time zone " + now.getZone().getId() + ". "
                + "Count relative dates, such as this month, soon or next week, from this date.";
    }
}
