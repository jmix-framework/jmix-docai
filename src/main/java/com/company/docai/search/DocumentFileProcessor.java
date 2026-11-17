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
 * Takes the text of a file for the full-text search of the stand from {@link DocumentTextReader}, within its limits
 * of length and time, in place of the search add-on, which reads the whole text of a file with no limit: a zip bomb or
 * a broken PDF of a visitor leaves its document without text, found by its name. The add-on's choice by the extension
 * still decides which files have text; the reader parses them by their content, with the add-on's settings of the
 * parse, such as no headers and footers of Office documents. Elsewhere the add-on reads the files itself.
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
     * Returns the whole text of the file, or an empty text when the file has none to read, its text is longer than
     * {@code docai.files.max-text-length} characters or it takes longer than {@code docai.files.text-timeout} to read.
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
