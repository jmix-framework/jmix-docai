package com.company.docai.ai.assistant;

import io.jmix.core.TimeSource;
import io.jmix.core.security.CurrentAuthentication;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Tells the assistants what day it is for the person who asks, so that a question such as what expires this month or
 * soon can be answered.
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
     * Returns the sentences for a system prompt: today's date in the time zone of the person signed in now, with the
     * weekday and the zone, and the rule to count relative dates from it. The text has no curly braces, so it fits a
     * prompt template.
     */
    public String sentences() {
        ZonedDateTime now = timeSource.now().withZoneSameInstant(currentAuthentication.getTimeZone().toZoneId());

        String weekday = now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);

        return "Today is " + now.toLocalDate() + " (" + weekday + "), time zone " + now.getZone().getId() + ". "
                + "Count relative dates, such as this month, soon or next week, from this date.";
    }
}
