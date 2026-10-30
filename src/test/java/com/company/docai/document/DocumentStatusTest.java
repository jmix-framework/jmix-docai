package com.company.docai.document;

import com.company.docai.model.document.DocumentStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentStatusTest {

    @ParameterizedTest
    @CsvSource({
            "DRAFT, ACTIVE, true",
            "DRAFT, ARCHIVED, false",
            "ACTIVE, ARCHIVED, true",
            "ACTIVE, DRAFT, false",
            "ARCHIVED, ACTIVE, true",
            "ARCHIVED, DRAFT, false",
            "DRAFT, DRAFT, false"
    })
    void theStatusMovesOnlyAlongItsTransitions(DocumentStatus from, DocumentStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }
}
