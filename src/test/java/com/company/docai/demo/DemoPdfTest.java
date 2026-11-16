package com.company.docai.demo;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The PDF of a text of a demo set: the same text gives the same bytes, and the text reads back from the pages.
 */
class DemoPdfTest {

    private static final String TEXT = """
            Northwind Paper Inc.
            Address: 157 Northfield Drive, Springfield, IL 62702

            # INVOICE No. 77
            ## dated May 20, 2027
            Supplier: Northwind Paper Inc.

            1. Copy paper, Letter size, 120 reams at $5.30\t$636.00
            Total\t$636.00
            Pay by May 31, 2027.
            """;

    // a pair starts each line after the first: it does not fit the line before, which takes its first part without the
    // rule that keeps the pair together
    private static final List<String> WRAPPED = List.of(
            "The supplier delivers the copy paper to the warehouse of the buyer and bills it under Invoice",
            "№ 2045 for the paper delivered on May 20, which the buyer pays by May 31; the order totals",
            "100 000 sheets of copy paper, as the delivery note says; the invoice is signed for the supplier by",
            "J. Smith, its chief accountant.");

    private final DemoPdf pdf = new DemoPdf();

    @Test
    void twoRendersOfOneTextGiveTheSameBytes() {
        assertThat(pdf.render(TEXT)).isEqualTo(pdf.render(TEXT));
    }

    @Test
    void theFileIsIdentifiedByTheHashOfItsTextAndTellsOnlyItsTitle() throws IOException, NoSuchAlgorithmException {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(TEXT.getBytes(StandardCharsets.UTF_8));

        try (PDDocument document = Loader.loadPDF(pdf.render(TEXT))) {
            COSArray ids = document.getDocument().getTrailer().getCOSArray(COSName.ID);

            assertThat(ids.size()).isEqualTo(2);
            assertThat(((COSString) ids.getObject(0)).getBytes()).isEqualTo(hash);
            assertThat(((COSString) ids.getObject(1)).getBytes()).isEqualTo(hash);
            assertThat(document.getDocumentInformation().getMetadataKeys()).containsExactly("Title");
            assertThat(document.getDocumentInformation().getTitle()).isEqualTo("INVOICE No. 77");
        }

        assertThat(pdf.render(TEXT.replace("May 31", "May 30"))).isNotEqualTo(pdf.render(TEXT));
    }

    @Test
    void theTextReadsBackFromThePage() throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf.render(TEXT))) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            assertThat(new PDFTextStripper().getText(document))
                    .contains("INVOICE No. 77", "dated May 20, 2027", "Copy paper, Letter size", "$636.00",
                            "Pay by May 31, 2027.");
        }
    }

    @Test
    void aParagraphWrapsByTheWidthOfItsWordsButNeverInsideANumberOrAfterAnInitial() throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf.render(String.join(" ", WRAPPED)))) {
            List<String> lines = new PDFTextStripper().getText(document)
                    .lines()
                    .toList();

            assertThat(lines).containsExactlyElementsOf(WRAPPED);
        }
    }

    @Test
    void aLongTextRunsOntoMorePages() throws IOException {
        String text = "# MEMO\n" + "The supplier delivers the paper to the warehouse every Monday.\n\n".repeat(60);

        try (PDDocument document = Loader.loadPDF(pdf.render(text))) {
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
        }
    }

    @Test
    void aWordOrALineOfTwoPartsWiderThanThePageIsAMistake() {
        assertThatThrownBy(() -> pdf.render("Springfield\t" + "May 31, 2027 ".repeat(12)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("The line does not fit the page");
        assertThatThrownBy(() -> pdf.render("x".repeat(200)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("The word does not fit the page");
    }
}
