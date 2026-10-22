package com.company.dms.view.document;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class PreviewTypeTest {

    @ParameterizedTest
    @CsvSource({
            "image/png, IMAGE",
            "image/jpeg, IMAGE",
            "image/gif, IMAGE",
            "image/webp, IMAGE",
            "application/pdf, PDF",
            "text/plain, TEXT",
            "text/csv, TEXT",
            "'text/plain; charset=UTF-8', TEXT",
            "text/html, TEXT",
            "application/json, TEXT",
            "application/xml, TEXT",
            "image/svg+xml, NONE",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document, NONE",
            "application/octet-stream, NONE"
    })
    void thePreviewFollowsTheAllowList(String contentType, PreviewType expected) {
        assertThat(PreviewType.of(contentType)).isEqualTo(expected);
    }
}
