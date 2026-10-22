package com.company.dms.app.service.document;

import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Detects the content type from the bytes of a file. The file name only refines
 * what the bytes leave open, such as the kind of an Office zip container.
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
}
