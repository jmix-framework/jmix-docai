package com.company.docai.test_support;

import com.company.docai.demo.DemoPdf;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Small files of the formats an upload may bring, built in code: the ones the stand takes and some it refuses. None
 * of them does harm: the program and the document of old Office are only their first bytes, and the scripts are
 * never run. A text given to an Office file is plain words without markup.
 */
public final class TestSamples {

    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
    private static final String OFFICE_DOCUMENT =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument";

    private TestSamples() {
    }

    public static byte[] pdf(String text) {
        return new DemoPdf().render(text);
    }

    /**
     * A Word document of one paragraph, made of the least parts Word and Tika need.
     */
    public static byte[] docx(String text) {
        Map<String, String> parts = new LinkedHashMap<>();

        parts.put("[Content_Types].xml", contentTypes("/word/document.xml",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml", ""));

        parts.put("_rels/.rels", relationships(OFFICE_DOCUMENT, "word/document.xml"));

        parts.put("word/document.xml", XML
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                + "<w:p><w:r><w:t>" + text + "</w:t></w:r></w:p></w:body></w:document>");

        return zip(parts);
    }

    /**
     * An Excel workbook of one sheet with the text in its first cell.
     */
    public static byte[] xlsx(String text) {
        Map<String, String> parts = new LinkedHashMap<>();

        parts.put("[Content_Types].xml", contentTypes("/xl/workbook.xml",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml",
                "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\""
                        + "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"));

        parts.put("_rels/.rels", relationships(OFFICE_DOCUMENT, "xl/workbook.xml"));

        parts.put("xl/workbook.xml", XML
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");

        parts.put("xl/_rels/workbook.xml.rels", relationships(
                "http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet",
                "worksheets/sheet1.xml"));

        parts.put("xl/worksheets/sheet1.xml", XML
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
                + "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>" + text + "</t></is></c></row>"
                + "</sheetData></worksheet>");

        return zip(parts);
    }

    public static byte[] png() {
        return image("png");
    }

    public static byte[] jpeg() {
        return image("jpg");
    }

    public static byte[] svg() {
        return utf8("<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\">"
                + "<script>alert(1)</script></svg>");
    }

    public static byte[] html() {
        return utf8("<!DOCTYPE html><html><body><script>alert(1)</script></body></html>");
    }

    /**
     * The headers of a Windows program, MZ and PE, and no code.
     */
    public static byte[] exe() {
        byte[] bytes = new byte[512];
        bytes[0] = 'M';
        bytes[1] = 'Z';
        // where the PE header starts
        bytes[0x3C] = (byte) 0x80;
        bytes[0x80] = 'P';
        bytes[0x81] = 'E';
        // an Intel 386 machine
        bytes[0x84] = 0x4C;
        bytes[0x85] = 0x01;

        return bytes;
    }

    /**
     * The signature of a compound file of old Office, the container of DOC and XLS, followed by nothing.
     */
    public static byte[] oldOffice() {
        byte[] bytes = new byte[1536];
        byte[] signature = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        System.arraycopy(signature, 0, bytes, 0, signature.length);

        return bytes;
    }

    /**
     * A CSV as Excel on a Russian Windows saves it: words of the Russian alphabet in Windows-1251 without a byte order
     * mark, a semicolon between the columns, a tab in a cell and Windows line ends. The words are made of the code
     * points of the alphabet, so the source holds none of its letters.
     */
    public static byte[] windows1251Csv() {
        // the letters of the Russian alphabet but its yo: 32 upper case, then 32 lower case
        String letters = IntStream.rangeClosed(0x0410, 0x044F)
                .mapToObj(Character::toString)
                .collect(Collectors.joining());

        String csv = letters.substring(0, 6) + ";" + letters.substring(32, 40) + "\r\n"
                + letters.substring(40, 48) + ";1200\r\n"
                + letters.substring(48, 56) + ";\t950\r\n";

        return csv.getBytes(Charset.forName("windows-1251"));
    }

    public static byte[] zip(Map<String, String> entries) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(utf8(entry.getValue()));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return bytes.toByteArray();
    }

    private static String contentTypes(String mainPart, String mainType, String otherParts) {
        return XML
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" "
                + "ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"" + mainPart + "\" ContentType=\"" + mainType + "\"/>"
                + otherParts
                + "</Types>";
    }

    private static String relationships(String type, String target) {
        return XML
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"" + type + "\" Target=\"" + target + "\"/>"
                + "</Relationships>";
    }

    private static byte[] image(String format) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            ImageIO.write(new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB), format, bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
