package com.company.docai.app.service.document;

import org.apache.tika.Tika;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MimeType;
import org.apache.tika.mime.MimeTypeException;
import org.apache.tika.mime.MimeTypes;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Detects the content type of a file from its bytes, with the file name as a hint for what the bytes leave open.
 */
@Component
public class ContentTypeDetector {

    private final Tika tika = new Tika();

    /**
     * Returns the content type of the file, such as {@code application/pdf}.
     */
    public String detect(Path file, String fileName) throws IOException {
        try (InputStream content = Files.newInputStream(file)) {
            return tika.detect(content, fileName);
        }
    }

    /**
     * Returns the content type the bytes show by themselves, without the hint of a name: the bytes of no format, those
     * of an empty file among them, are {@code application/octet-stream}. The type can carry parameters, such as
     * {@code application/x-msdownload; format=pe32}.
     */
    public String detectByContent(Path file) throws IOException {
        // on a path Tika opens a zip as a file and tells an Office document from a plain zip by its parts
        try (InputStream content = TikaInputStream.get(file)) {
            return tika.detect(content);
        }
    }

    /**
     * Returns the short name of the format of the content type, such as PDF or DOCX: the acronym Tika knows, else
     * the extension of its files in capitals, else the type itself.
     */
    public String nameOf(String contentType) {
        return registered(contentType)
                .map(known -> known.getAcronym().isEmpty()
                        ? known.getExtension()
                                .replaceFirst("^\\.", "")
                                .toUpperCase(Locale.ROOT)
                        : known.getAcronym())
                .filter(name -> !name.isEmpty())
                .orElse(contentType);
    }

    private static Optional<MimeType> registered(String contentType) {
        MediaType type = MediaType.parse(contentType);

        if (type == null) {
            return Optional.empty();
        }

        try {
            return Optional.ofNullable(MimeTypes.getDefaultMimeTypes()
                    .getRegisteredMimeType(type.getBaseType().toString()));
        } catch (MimeTypeException e) {
            return Optional.empty();
        }
    }
}
