package com.company.docai.app.service.document;

import org.apache.tika.Tika;
import org.apache.tika.io.TikaInputStream;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Detects the content type of a file from its bytes: the type a version is stored with, where the file name refines
 * what the bytes leave open, such as a CSV among plain texts, and the type the bytes show by themselves, which the
 * stand checks uploads against.
 */
@Component
public class ContentTypeDetector {

    private final Tika tika = new Tika();

    /**
     * Returns the content type of the file, such as {@code application/pdf}.
     *
     * @param fileName the name the file was uploaded with, a hint for what the bytes leave open
     */
    public String detect(Path file, String fileName) throws IOException {
        try (InputStream content = Files.newInputStream(file)) {
            return tika.detect(content, fileName);
        }
    }

    /**
     * Returns the content type the bytes of the file show by themselves, without the hint of a name: the bytes of no
     * format, those of an empty file among them, are {@code application/octet-stream}, whatever the name says. The
     * type can carry parameters, such as {@code application/x-msdownload; format=pe32}.
     */
    public String detectByContent(Path file) throws IOException {
        // on a path Tika opens a zip as a file and tells an Office document from a plain zip by its parts
        try (InputStream content = TikaInputStream.get(file)) {
            return tika.detect(content);
        }
    }
}
