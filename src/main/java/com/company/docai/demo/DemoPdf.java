package com.company.docai.demo;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a text of a demo set into an A4 PDF with a text layer, in PT Serif and PT Sans by ParaType, which lie with
 * their license, the SIL Open Font License 1.1, in {@code com/company/docai/demo/fonts/}. A line of the text is:
 * <ul>
 *     <li>{@code # text}, a title, centered, in PT Sans Bold;</li>
 *     <li>{@code ## text}, a heading in PT Serif Bold, centered right under the title;</li>
 *     <li>{@code left<TAB>right}, a part at each margin;</li>
 *     <li>empty, a gap;</li>
 *     <li>anything else, a paragraph wrapped by the measured width of its words, which keeps a space between digits,
 *     after {@code №} and after a single letter with a period.</li>
 * </ul>
 * The same text gives the same bytes: the information of the file holds only the title, and the identifier of the
 * file is the hash of the text.
 */
@Component
public class DemoPdf {

    private static final String FONTS = "com/company/docai/demo/fonts/";
    private static final float BODY_SIZE = 11f;
    private static final float TITLE_SIZE = 14f;
    private static final float LINE_SPACING = 1.35f;
    // two centimeters
    private static final float MARGIN = 2f / 2.54f * 72f;
    private static final PDRectangle PAGE = PDRectangle.A4;
    private static final float TEXT_WIDTH = PAGE.getWidth() - 2 * MARGIN;

    private final byte[] serif = font("PTSerif-Regular.ttf");
    private final byte[] serifBold = font("PTSerif-Bold.ttf");
    private final byte[] sansBold = font("PTSans-Bold.ttf");

    /**
     * Renders the text into a PDF.
     *
     * @throws IllegalStateException when a word, or a line with a part at each margin, is wider than the page
     */
    public byte[] render(String text) {
        try (PDDocument document = new PDDocument()) {
            String title = new Pages(document)
                    .write(text.lines()
                            .toList());

            document.getDocumentInformation().setTitle(title);
            // without an identifier of its own PDFBox makes one from the clock
            COSString id = new COSString(hash(text));
            COSArray ids = new COSArray();
            ids.add(id);
            ids.add(id);
            document.getDocument().getTrailer().setItem(COSName.ID, ids);

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            document.save(bytes);

            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] font(String file) {
        try (InputStream content = new ClassPathResource(FONTS + file).getInputStream()) {
            return content.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("There is no font " + FONTS + file, e);
        }
    }

    private static byte[] hash(String text) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static float width(PDFont font, float size, String text) throws IOException {
        return font.getStringWidth(text) * size / 1000f;
    }

    // splits a paragraph at the spaces where a line may break; the other spaces stay inside a unit
    private static List<String> units(String text) {
        List<String> units = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c == ' ' && !isGlued(text, i)) {
                units.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }

        units.add(current.toString());

        return units;
    }

    private static boolean isGlued(String text, int space) {
        if (space == 0 || space == text.length() - 1) {
            return false;
        }

        char before = text.charAt(space - 1);
        char after = text.charAt(space + 1);

        return isDigit(before) && isDigit(after) || before == '№' || isAfterSingleLetter(text, space);
    }

    // an initial or an abbreviation of one letter, such as a street or a year in Russian
    private static boolean isAfterSingleLetter(String text, int space) {
        if (space < 2 || text.charAt(space - 1) != '.' || !Character.isLetter(text.charAt(space - 2))) {
            return false;
        }

        return space == 2 || text.charAt(space - 3) == ' ';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    // the pages of one rendering, written from the top down
    private final class Pages {

        private final PDDocument document;
        private final PDFont body;
        private final PDFont bold;
        private final PDFont heading;
        private PDPageContentStream stream;
        private float cursor;
        private boolean pageIsEmpty;

        private Pages(PDDocument document) throws IOException {
            this.document = document;
            body = PDType0Font.load(document, new RandomAccessReadBuffer(serif), true, false);
            bold = PDType0Font.load(document, new RandomAccessReadBuffer(serifBold), true, false);
            heading = PDType0Font.load(document, new RandomAccessReadBuffer(sansBold), true, false);
            stream = openPage();
        }

        // returns the first title of the text
        private @Nullable String write(List<String> lines) throws IOException {
            String title = null;
            boolean afterTitle = false;

            for (String line : lines) {
                if (line.isBlank()) {
                    gap();
                    afterTitle = false;
                } else if (line.startsWith("# ")) {
                    String text = line.substring(2).strip();

                    if (title == null) {
                        title = text;
                    }

                    drawWrapped(text, heading, TITLE_SIZE, true);
                    afterTitle = true;
                } else if (line.startsWith("## ")) {
                    drawWrapped(line.substring(3).strip(), bold, BODY_SIZE, afterTitle);
                    afterTitle = false;
                } else if (line.indexOf('\t') >= 0) {
                    int tab = line.indexOf('\t');
                    drawSplit(line.substring(0, tab).strip(), line.substring(tab + 1).strip());
                    afterTitle = false;
                } else {
                    drawWrapped(line.strip(), body, BODY_SIZE, false);
                    afterTitle = false;
                }
            }

            stream.close();

            return title;
        }

        private PDPageContentStream openPage() throws IOException {
            PDPage page = new PDPage(PAGE);
            document.addPage(page);
            cursor = PAGE.getHeight() - MARGIN;
            pageIsEmpty = true;

            return new PDPageContentStream(document, page);
        }

        private void ensureRoom(float height) throws IOException {
            if (cursor - height < MARGIN) {
                stream.close();
                stream = openPage();
            }
        }

        private void gap() {
            if (!pageIsEmpty) {
                cursor -= BODY_SIZE * LINE_SPACING;
            }
        }

        private void drawWrapped(String text, PDFont font, float size, boolean centered) throws IOException {
            float lineHeight = size * LINE_SPACING;

            for (String line : wrap(text, font, size)) {
                ensureRoom(lineHeight);
                float x = centered ? (PAGE.getWidth() - width(font, size, line)) / 2 : MARGIN;
                drawAt(font, size, x, cursor - size, line);
                cursor -= lineHeight;
                pageIsEmpty = false;
            }
        }

        private void drawSplit(String left, String right) throws IOException {
            ensureRoom(BODY_SIZE * LINE_SPACING);
            float rightWidth = width(body, BODY_SIZE, right);

            if (width(body, BODY_SIZE, left) + rightWidth + 2 * BODY_SIZE > TEXT_WIDTH) {
                throw new IllegalStateException("The line does not fit the page: " + left + " | " + right);
            }

            float baseline = cursor - BODY_SIZE;
            drawAt(body, BODY_SIZE, MARGIN, baseline, left);
            drawAt(body, BODY_SIZE, PAGE.getWidth() - MARGIN - rightWidth, baseline, right);
            cursor -= BODY_SIZE * LINE_SPACING;
            pageIsEmpty = false;
        }

        private void drawAt(PDFont font, float size, float x, float baseline, String text) throws IOException {
            stream.beginText();
            stream.setFont(font, size);
            stream.newLineAtOffset(x, baseline);
            stream.showText(text);
            stream.endText();
        }

        private List<String> wrap(String text, PDFont font, float size) throws IOException {
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();

            for (String unit : units(text)) {
                if (width(font, size, unit) > TEXT_WIDTH) {
                    throw new IllegalStateException("The word does not fit the page: " + unit);
                }

                if (line.isEmpty()) {
                    line.append(unit);
                    continue;
                }

                String candidate = line + " " + unit;

                if (width(font, size, candidate) > TEXT_WIDTH) {
                    lines.add(line.toString());
                    line = new StringBuilder(unit);
                } else {
                    line = new StringBuilder(candidate);
                }
            }

            lines.add(line.toString());

            return lines;
        }
    }
}
