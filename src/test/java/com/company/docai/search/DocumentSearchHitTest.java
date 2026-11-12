package com.company.docai.search;

import com.company.docai.search.DocumentSearchHit.Part;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The matched text of a found document, split into the plain text and the matched words.
 */
class DocumentSearchHitTest {

    @Test
    void theMarkedTextFallsIntoPlainAndMatchedPartsInItsOrder() {
        assertThat(DocumentSearchHit.partsOf("the <b>lease</b> of <b>2026</b>")).containsExactly(
                new Part("the ", false),
                new Part("lease", true),
                new Part(" of ", false),
                new Part("2026", true));
        assertThat(DocumentSearchHit.partsOf("<b>Lease</b> plan.txt")).containsExactly(
                new Part("Lease", true),
                new Part(" plan.txt", false));
        assertThat(DocumentSearchHit.partsOf("</b>the <b>lease</b>")).containsExactly(
                new Part("</b>the ", false),
                new Part("lease", true));
        assertThat(DocumentSearchHit.partsOf("<b>the <b>lease</b>")).containsExactly(new Part("the <b>lease", true));
    }

    @Test
    void anOpenMarkAndAnEmptyTextLeaveNothingMarked() {
        assertThat(DocumentSearchHit.partsOf("a <b>lease")).containsExactly(new Part("a <b>lease", false));
        assertThat(DocumentSearchHit.partsOf("<img src=x>")).containsExactly(new Part("<img src=x>", false));
        assertThat(DocumentSearchHit.partsOf("")).isEmpty();
        assertThat(DocumentSearchHit.partsOf("<b></b>")).isEmpty();
    }
}
