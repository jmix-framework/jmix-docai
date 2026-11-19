package com.company.docai.document;

import com.company.docai.app.service.document.ContentTypeDetector;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The short names of content types, as people know the formats of their files.
 */
class ContentTypeDetectorTest {

    private final ContentTypeDetector detector = new ContentTypeDetector();

    @Test
    void theTypesOfTheStandHaveTheirShortNames() {
        assertThat(detector.nameOf("application/pdf")).isEqualTo("PDF");
        assertThat(detector.nameOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .isEqualTo("DOCX");
        assertThat(detector.nameOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .isEqualTo("XLSX");
        assertThat(detector.nameOf("text/plain")).isEqualTo("TXT");
        assertThat(detector.nameOf("text/csv")).isEqualTo("CSV");
        assertThat(detector.nameOf("image/png")).isEqualTo("PNG");
        assertThat(detector.nameOf("image/jpeg")).isEqualTo("JPEG");
    }

    @Test
    void otherKnownTypesAreNamedByTheirFormatOrExtension() {
        assertThat(detector.nameOf("application/msword")).isEqualTo("DOC");
        assertThat(detector.nameOf("application/vnd.openxmlformats-officedocument.presentationml.presentation"))
                .isEqualTo("PPTX");
        assertThat(detector.nameOf("image/gif")).isEqualTo("GIF");
    }

    @Test
    void theParametersOfATypeChangeNothing() {
        assertThat(detector.nameOf("text/plain; charset=UTF-8")).isEqualTo("TXT");
    }

    @Test
    void aTypeWithoutANameStaysAsItIs() {
        assertThat(detector.nameOf("application/x-docai-unknown")).isEqualTo("application/x-docai-unknown");
        assertThat(detector.nameOf("not a type")).isEqualTo("not a type");
    }
}
