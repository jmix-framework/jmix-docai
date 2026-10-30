package com.company.docai.ai;

import com.company.docai.app.service.document.NewVersion;
import com.company.docai.app.service.document.UploadedFile;
import com.company.docai.model.document.DocumentRevision;
import io.jmix.core.FileRef;
import io.jmix.core.FileStorageLocator;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamSource;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Optional;

/**
 * Reads the text of a document version for the AI: the file of a saved version, or a file uploaded into
 * the card and not saved yet. Tika detects the format by the content, so the AI reads more formats than
 * the full-text search, which goes by the extension.
 */
@Component
public class DocumentTextReader {

    private static final Logger log = LoggerFactory.getLogger(DocumentTextReader.class);
    private static final String CUT = "\n\n[The text stops here: only its first %d characters are read.]";

    private final FileStorageLocator fileStorageLocator;
    private final AiSettings aiSettings;
    private final AutoDetectParser parser = new AutoDetectParser();

    public DocumentTextReader(FileStorageLocator fileStorageLocator, AiSettings aiSettings) {
        this.fileStorageLocator = fileStorageLocator;
        this.aiSettings = aiSettings;
    }

    /**
     * Returns the text of the version with its whitespace tidied, at most {@link AiSettings#getMaxTextLength()}
     * characters of it and a note where a longer text is cut, or nothing when the file has no text to read:
     * pictures, scanned pages, an unknown format, a broken file.
     */
    public Optional<String> read(NewVersion version) {
        return switch (version) {
            case NewVersion.FromFile(UploadedFile file) ->
                    read(file.fileName(), () -> Files.newInputStream(file.content()), aiSettings.getMaxTextLength(),
                            true);
            case NewVersion.FromRevision(DocumentRevision revision) -> read(revision);
        };
    }

    /**
     * Returns the text of a saved version by the same rules as {@link #read(NewVersion)}.
     */
    public Optional<String> read(DocumentRevision revision) {
        return read(revision.getFileName(), () -> open(revision.getFile()), aiSettings.getMaxTextLength(), true);
    }

    /**
     * Returns the first characters of the text of a saved version, without a note where the text is cut, or nothing
     * when the file has no text to read.
     *
     * @param maxLength how many characters of the text to read at most
     */
    public Optional<String> readStart(DocumentRevision revision, int maxLength) {
        return read(revision.getFileName(), () -> open(revision.getFile()), maxLength, false);
    }

    private Optional<String> read(String fileName, InputStreamSource source, int maxLength, boolean noteTheCut) {
        BodyContentHandler handler = new BodyContentHandler(maxLength);
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
        boolean cut = false;

        try (InputStream stream = source.getInputStream()) {
            parser.parse(stream, handler, metadata, new ParseContext());
        } catch (SAXException e) {
            // a long text stops at the limit, and its first part is what the model reads anyway
            if (!WriteLimitReachedException.isWriteLimitReached(e)) {
                return unreadable(fileName, e);
            }

            cut = true;
        } catch (IOException | TikaException | RuntimeException e) {
            return unreadable(fileName, e);
        }

        String text = tidy(handler.toString());

        if (text.isEmpty()) {
            log.info("No text to read in {} ({})", fileName, metadata.get(Metadata.CONTENT_TYPE));
            return Optional.empty();
        }

        // without the note the model takes the beginning for the whole document and says the rest is not there
        return Optional.of(cut && noteTheCut ? text + CUT.formatted(maxLength) : text);
    }

    private InputStream open(FileRef file) {
        return fileStorageLocator.getByName(file.getStorageName()).openStream(file);
    }

    private static Optional<String> unreadable(String fileName, Exception e) {
        log.warn("Cannot read the text of {}: {}", fileName, e.toString());

        return Optional.empty();
    }

    private static String tidy(String text) {
        return text.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll(" ?\\n ?", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }
}
