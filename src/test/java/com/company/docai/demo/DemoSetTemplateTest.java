package com.company.docai.demo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The set of a day that a template gives, and the problems of its history at the moment of a load.
 */
class DemoSetTemplateTest {

    // a Thursday in the second quarter
    private static final LocalDate DAY = LocalDate.of(2027, 5, 20);

    @Test
    void aDocumentWithNamedDatesTakesThemFromTheDayAndItsTextWritesThem() throws IOException {
        DemoSet.Document invoice = documentOf(DemoSetTemplate.read("sample")
                .generate("sample", DAY), "Invoice No. 77");

        assertThat(invoice.date()).isEqualTo(LocalDate.of(2027, 5, 17));
        assertThat(invoice.fields())
                .containsEntry("invoiceNumber", "77")
                .containsEntry("dueDate", "2027-05-31");
        assertThat(invoice.versions())
                .extracting(DemoSet.Version::file, DemoSet.Version::uploaded)
                .containsExactly(tuple("invoice-77.pdf", LocalDateTime.of(2027, 5, 17, 10, 30)));
        assertThat(invoice.versions().getFirst().text()).contains("## dated May 17, 2027", "Pay by May 31, 2027.");
    }

    // the text of the invoice makes its PDF and this plain file: the extension of the file decides
    @Test
    void aTextOfAPlainFileBecomesThatFileWithTheDatesOfTheDay() throws IOException {
        DemoSet.Document copy = documentOf(DemoSetTemplate.read("sample")
                .generate("sample", DAY), "Invoice No. 77.txt");

        assertThat(copy.versions())
                .extracting(DemoSet.Version::file, DemoSet.Version::uploaded)
                .containsExactly(tuple("invoice-77.txt", LocalDateTime.of(2027, 5, 17, 10, 45)));
        assertThat(copy.versions().getFirst().text())
                .startsWith("Northwind Paper Inc.")
                .contains("## dated May 17, 2027", "Pay by May 31, 2027.");
    }

    @Test
    void aDocumentWithDatesOfIso8601KeepsThemOnAnyDay() throws IOException {
        DemoSet.Document lease = documentOf(DemoSetTemplate.read("sample")
                .generate("sample", DAY), "Lease Agreement No. 7");

        assertThat(lease.date()).isEqualTo(LocalDate.of(2026, 2, 27));
        assertThat(lease.fields()).containsEntry("validUntil", "2027-02-28");
        assertThat(lease.versions())
                .extracting(DemoSet.Version::file, DemoSet.Version::text, DemoSet.Version::uploaded)
                .containsExactly(tuple("lease-draft.txt", null, LocalDateTime.of(2026, 2, 10, 10, 15)),
                        tuple("lease-signed.txt", null, LocalDateTime.of(2026, 2, 27, 16, 40)));
    }

    @Test
    void aNameAFieldAndACommentWriteYearsAndNamedDates() {
        DemoSetTemplate.Document contract = new DemoSetTemplate.Document("Contract No. 9/{year:signed}",
                Map.of("signed", "year-start-1+50d", "end", "year-end-1"), "signed", "Contract",
                Map.of("contractNumber", "9/{year:signed}", "validUntil", "end"), null, null, null,
                List.of(new DemoSetTemplate.Version("note.txt", null, "alice", "signed 13:15")),
                List.of(new DemoSetTemplate.Comment("john", "signed+1d 09:00", "Valid until {end:long}.", null)),
                null, null);

        DemoSetTemplate template = new DemoSetTemplate("en", Map.of("long", "MMMM d, yyyy"), null, null,
                List.of(new DemoSetTemplate.Folder("Archive {year:-1}", null, null, List.of(contract), null, null)));

        DemoSet.Folder archive = template.generate("none", DAY)
                .folders()
                .getFirst();

        DemoSet.Document generated = archive.documents().getFirst();

        assertThat(archive.name()).isEqualTo("Archive 2026");
        assertThat(generated.name()).isEqualTo("Contract No. 9/2026");
        assertThat(generated.date()).isEqualTo(LocalDate.of(2026, 2, 20));
        assertThat(generated.fields())
                .containsEntry("contractNumber", "9/2026")
                .containsEntry("validUntil", "2026-12-31");
        assertThat(generated.versions().getFirst().uploaded()).isEqualTo(LocalDateTime.of(2026, 2, 20, 13, 15));
        assertThat(generated.comments())
                .extracting(DemoSet.Comment::at, DemoSet.Comment::text)
                .containsExactly(tuple(LocalDateTime.of(2026, 2, 21, 9, 0), "Valid until December 31, 2026."));
    }

    @Test
    void theHistoryHasNoEventAfterTheLoadAndTheLastVersionIsNoOlderThanItsDocument() {
        LocalDateTime now = DAY.atTime(10, 0);

        DemoSet.Document invoice = new DemoSet.Document("Invoice", DAY.minusDays(1), null, null, null, null, null,
                List.of(new DemoSet.Version("draft.pdf", null, "maria", now.minusDays(3)),
                        new DemoSet.Version("invoice.pdf", null, "maria", now.minusDays(2))),
                List.of(new DemoSet.Comment("john", now.plusHours(1), "Paid", now.minusHours(1))),
                new DemoSet.Deletion("john", now.plusDays(1)), null);

        DemoSet.Document scan = new DemoSet.Document("Scan", null, null, null, null, null, null,
                List.of(new DemoSet.Version("scan.pdf", null, "alice", now.plusMinutes(1))), null, null, null);

        DemoSet set = new DemoSet(null, null, List.of(new DemoSet.Folder("Inbox", null, null,
                List.of(invoice, scan), new DemoSet.Deletion("alice", now.minusDays(1)), null)));

        assertThat(set.historyProblems(now)).containsExactlyInAnyOrder(
                "/Inbox/Invoice: a comment is written in the future, 2027-05-20T11:00",
                "/Inbox/Invoice: a comment is edited before it is written",
                "/Inbox/Invoice: the document is deleted in the future, 2027-05-21T10:00",
                "/Inbox/Invoice: the last version is uploaded before the date of the document",
                "/Inbox/Scan: a version is uploaded in the future, 2027-05-20T10:01");
        assertThat(set.historyProblems(now.plusDays(2))).containsExactlyInAnyOrder(
                "/Inbox/Invoice: a comment is edited before it is written",
                "/Inbox/Invoice: the last version is uploaded before the date of the document");
    }

    private static DemoSet.Document documentOf(DemoSet set, String name) {
        return set.folders()
                .stream()
                .flatMap(folder -> folder.documents().stream())
                .filter(document -> document.name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
