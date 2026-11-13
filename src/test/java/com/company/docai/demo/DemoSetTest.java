package com.company.docai.demo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The last event of the history of a set, which the loader moves to the day before the load: no event of the set may
 * come after it, or that event would land in the future.
 */
class DemoSetTest {

    @Test
    void theLastEventOfTheSampleSetIsTheDeletionOfALease() throws IOException {
        // later than the uploads and the comments of the set and than the deletion of its folder of drafts
        assertThat(DemoSet.read("sample").lastEvent()).contains(LocalDateTime.of(2026, 10, 21, 9, 15));
    }

    @Test
    void theChangeOfACommentCountsAsAnEvent() {
        LocalDateTime uploaded = LocalDateTime.of(2026, 3, 2, 9, 0);
        LocalDateTime edited = uploaded.plusHours(3);
        DemoSet.Comment comment = new DemoSet.Comment("john", uploaded.plusHours(1), "Checked", edited);

        DemoSet.Document document = new DemoSet.Document("Note", null, null, null, null, null, null,
                List.of(new DemoSet.Version("note.txt", "alice", uploaded)), List.of(comment), null, null);

        assertThat(setOf(new DemoSet.Folder("Notes", null, null, List.of(document), null, null)).lastEvent())
                .contains(edited);
    }

    @Test
    void aSetWithoutDocumentsAndDeletionsHasNoHistory() {
        assertThat(setOf(new DemoSet.Folder("Empty", null, null, null, null, null)).lastEvent()).isEmpty();
    }

    private static DemoSet setOf(DemoSet.Folder folder) {
        return new DemoSet(null, null, List.of(folder));
    }
}
