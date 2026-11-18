package com.company.docai.search;

import com.company.docai.ai.DocumentTextReader;
import io.jmix.core.FileRef;
import io.jmix.core.FileStorageLocator;
import io.jmix.search.exception.UnsupportedFileFormatException;
import io.jmix.search.utils.FileProcessor;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Takes a file's text for the stand's full-text search from {@link DocumentTextReader}, within limits the add-on lacks.
 */
@Primary
@Component
@Profile("stand")
public class DocumentFileProcessor extends FileProcessor {

    private final DocumentTextReader textReader;

    public DocumentFileProcessor(FileStorageLocator fileStorageLocator, DocumentTextReader textReader) {
        super(fileStorageLocator);
        this.textReader = textReader;
    }

    /**
     * Returns the whole text of the file, or an empty text when the file has none to read or exceeds the limits.
     *
     * @throws UnsupportedFileFormatException when the add-on has no parser for the extension of the file
     */
    @Override
    public String extractFileContent(FileRef fileRef) throws UnsupportedFileFormatException {
        if (getParserOpt(fileRef).isEmpty()) {
            throw new UnsupportedFileFormatException(fileRef.getFileName());
        }

        return textReader.readWhole(fileRef, createParseContext())
                .orElse("");
    }
}
