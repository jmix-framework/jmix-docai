package com.company.docai.demo;

import io.jmix.core.TimeSource;
import io.jmix.core.security.CurrentAuthentication;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;

/**
 * The invoice the tour offers to upload: a text of the demo set rendered into a PDF with its dates counted from
 * today.
 */
@Component
public class TourSample {

    private static final String DESCRIPTION = "tour-sample.json";
    private static final String RUSSIAN = "ru";
    private static final String ENGLISH = "en";

    private final DemoPdf pdf;
    private final TimeSource timeSource;
    private final CurrentAuthentication currentAuthentication;

    public TourSample(DemoPdf pdf, TimeSource timeSource, CurrentAuthentication currentAuthentication) {
        this.pdf = pdf;
        this.timeSource = timeSource;
        this.currentAuthentication = currentAuthentication;
    }

    /**
     * Returns the name of the file of the sample in the language of the interface.
     *
     * @throws UncheckedIOException when the description of the sample cannot be read
     */
    public String fileName() {
        return description(set()).fileName();
    }

    /**
     * Renders the sample for today in the user's time zone as a one-page PDF.
     *
     * @throws UncheckedIOException when the sample cannot be read
     */
    public byte[] render() {
        String set = set();
        Description description = description(set);

        LocalDate today = timeSource.now()
                .withZoneSameInstant(currentAuthentication.getTimeZone().toZoneId())
                .toLocalDate();

        try {
            String template = DemoSetTemplate.text(set, description.text())
                    .getContentAsString(StandardCharsets.UTF_8);

            String text = DemoSetTemplate.read(set)
                    .calendar(today)
                    .datesOf(description.dates())
                    .text(template);

            return pdf.render(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String set() {
        return RUSSIAN.equals(currentAuthentication.getLocale().getLanguage()) ? RUSSIAN : ENGLISH;
    }

    private static Description description(String set) {
        try (InputStream content = new ClassPathResource(DemoSet.LOCATION.formatted(set) + DESCRIPTION)
                .getInputStream()) {
            return DemoSetTemplate.JSON.readValue(content, Description.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The sample as its file describes it; the dates are {@link DemoCalendar} expressions.
     */
    private record Description(String fileName, String text, Map<String, String> dates) {
    }
}
