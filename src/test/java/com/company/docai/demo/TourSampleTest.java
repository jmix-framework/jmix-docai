package com.company.docai.demo;

import io.jmix.core.TimeSource;
import io.jmix.core.security.CurrentAuthentication;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The sample invoice of the tour, without Spring: its language, its dates and its text read back from the PDF.
 */
class TourSampleTest {

    private static final ZonedDateTime NOON = ZonedDateTime.of(2027, 5, 20, 12, 0, 0, 0, ZoneOffset.UTC);

    private final TimeSource timeSource = mock(TimeSource.class);
    private final CurrentAuthentication currentAuthentication = mock(CurrentAuthentication.class);
    private final TourSample sample = new TourSample(new DemoPdf(), timeSource, currentAuthentication);

    @BeforeEach
    void setUp() {
        when(timeSource.now()).thenReturn(NOON);
        when(currentAuthentication.getTimeZone()).thenReturn(TimeZone.getTimeZone(ZoneOffset.UTC));
        when(currentAuthentication.getLocale()).thenReturn(Locale.ENGLISH);
    }

    @Test
    void theEnglishSampleIsAnInvoiceOfOnePageWithItsNumberAndTotal() throws IOException {
        assertThat(sample.fileName()).isEqualTo("Invoice 2231.pdf");

        try (PDDocument document = Loader.loadPDF(sample.render())) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            assertThat(document.getDocumentInformation().getTitle()).isEqualTo("INVOICE No. 2231");
            assertThat(textOf(document))
                    .contains("Mid-South Equipment Sales LLC", "INVOICE No. 2231", "Total $172,000.00");
        }
    }

    @Test
    void everyDateOfTheSampleCountsFromToday() throws IOException {
        assertThat(text()).contains("dated May 17, 2027", "Supply Agreement No. 52/2027 dated May 3, 2027",
                "Pay by May 24, 2027.");
    }

    @Test
    void aLaterDayMovesTheDatesWithIt() throws IOException {
        when(timeSource.now()).thenReturn(NOON.plusDays(300));

        assertThat(text())
                .contains("dated March 12, 2028", "No. 52/2028 dated February 27, 2028", "Pay by March 19, 2028.")
                .doesNotContain("2027");
    }

    @Test
    void todayIsTheDayInTheTimeZoneOfTheUser() throws IOException {
        when(timeSource.now()).thenReturn(NOON.withHour(23));
        when(currentAuthentication.getTimeZone()).thenReturn(TimeZone.getTimeZone("Europe/Moscow"));

        assertThat(text()).contains("dated May 18, 2027");
    }

    @Test
    void aRussianInterfaceGetsTheRussianSampleWithItsOwnNameAndFormats() throws IOException {
        when(currentAuthentication.getLocale()).thenReturn(Locale.forLanguageTag("ru"));

        assertThat(sample.fileName()).matches("\\P{ASCII}.* 2231\\.pdf");

        try (PDDocument document = Loader.loadPDF(sample.render())) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            assertThat(textOf(document))
                    .contains("6168123956", " 2231", "17.05.2027", " 52/2027", "15 480 000,00")
                    .doesNotContain("INVOICE");
        }
    }

    @Test
    void anyOtherInterfaceGetsTheEnglishSample() throws IOException {
        when(currentAuthentication.getLocale()).thenReturn(Locale.GERMANY);

        assertThat(sample.fileName()).isEqualTo("Invoice 2231.pdf");
        assertThat(text()).contains("INVOICE No. 2231");
    }

    private String text() throws IOException {
        try (PDDocument document = Loader.loadPDF(sample.render())) {
            return textOf(document);
        }
    }

    private static String textOf(PDDocument document) throws IOException {
        // a paragraph wraps where the page ends
        return new PDFTextStripper().getText(document)
                .replaceAll("\\s+", " ");
    }
}
