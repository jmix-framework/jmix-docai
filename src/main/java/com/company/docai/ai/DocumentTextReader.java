package com.company.docai.ai;

import com.company.docai.app.config.FilesProperties;
import com.company.docai.app.service.document.NewVersion;
import com.company.docai.app.service.document.UploadedFile;
import com.company.docai.model.document.DocumentRevision;
import io.jmix.core.FileRef;
import io.jmix.core.FileStorageLocator;
import jakarta.annotation.PreDestroy;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamSource;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Reads the texts of files with Tika, on the stand within limits of time and length, so a zip bomb or a broken PDF
 * leaves its document without text.
 */
@Component
public class DocumentTextReader {

    private static final Logger log = LoggerFactory.getLogger(DocumentTextReader.class);
    private static final String CUT = "\n\n[The text stops here: only its first %d characters are read.]";

    private final FileStorageLocator fileStorageLocator;
    private final AiSettings aiSettings;
    private final @Nullable Integer maxTextLength;
    private final @Nullable Duration timeout;
    private final AutoDetectParser parser = new AutoDetectParser();
    // a parse that does not stop keeps its thread, so the threads are few and a parse waits for one; none without the
    // time limit
    private final @Nullable ExecutorService parses;

    public DocumentTextReader(FileStorageLocator fileStorageLocator, AiSettings aiSettings,
                              FilesProperties filesProperties) {
        this.fileStorageLocator = fileStorageLocator;
        this.aiSettings = aiSettings;
        this.maxTextLength = filesProperties.maxTextLength();
        this.timeout = filesProperties.textTimeout();

        this.parses = timeout == null
                ? null
                : Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()),
                        Thread.ofPlatform()
                                .name("docai-text-reader-", 1)
                                .daemon()
                                .factory());
    }

    /**
     * Returns the text of the version, cut with a note after {@link AiSettings#getMaxTextLength()} characters.
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
     * Returns the first characters of the text of a saved version, without a note where the text is cut.
     */
    public Optional<String> readStart(DocumentRevision revision, int maxLength) {
        return read(revision.getFileName(), () -> open(revision.getFile()), maxLength, false);
    }

    /**
     * Returns the whole text of a file for the full-text search, or nothing when it exceeds the limits.
     */
    public Optional<String> readWhole(FileRef file, ParseContext context) {
        int limit = maxTextLength == null ? -1 : maxTextLength;
        Optional<Parsed> parsed = parse(file.getFileName(), () -> open(file), limit, context);

        // a zip bomb writes text until something stops it, so the beginning of an overlong text is not worth finding
        if (parsed.isPresent() && parsed.get().cut()) {
            log.warn("Cannot read the text of {}: it is longer than {} characters", file.getFileName(), maxTextLength);
            return Optional.empty();
        }

        return parsed.map(Parsed::text);
    }

    /**
     * Stops the parses in progress.
     */
    @PreDestroy
    public void shutdown() {
        if (parses != null) {
            parses.shutdownNow();
        }
    }

    private Optional<String> read(String fileName, InputStreamSource source, int maxLength, boolean noteTheCut) {
        int length = maxTextLength == null ? maxLength : Math.min(maxLength, maxTextLength);

        return parse(fileName, source, length, new ParseContext())
                .map(parsed -> {
                    String text = tidy(parsed.text());

                    return parsed.cut() && noteTheCut ? text + CUT.formatted(length) : text;
                });
    }

    private Optional<Parsed> parse(String fileName, InputStreamSource source, int maxLength, ParseContext context) {
        BodyContentHandler handler = new BodyContentHandler(maxLength);
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
        boolean cut = false;

        // the file is opened here, in the thread of the caller, which knows the files of its scope
        try (InputStream stream = source.getInputStream()) {
            if (!parseInTime(fileName, stream, handler, metadata, context)) {
                return Optional.empty();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException e) {
            if (!WriteLimitReachedException.isWriteLimitReached(e.getCause())) {
                return unreadable(fileName, e.getCause());
            }

            cut = true;
        } catch (SAXException e) {
            if (!WriteLimitReachedException.isWriteLimitReached(e)) {
                return unreadable(fileName, e);
            }

            cut = true;
        } catch (IOException | TikaException | RuntimeException e) {
            return unreadable(fileName, e);
        }

        String text = handler.toString();

        if (text.isBlank()) {
            log.info("No text to read in {} ({})", fileName, metadata.get(Metadata.CONTENT_TYPE));
            return Optional.empty();
        }

        return Optional.of(new Parsed(text, cut));
    }

    // with the time limit the parse runs in a thread of the reader, and closing the file at the limit stops it
    private boolean parseInTime(String fileName, InputStream stream, BodyContentHandler handler, Metadata metadata,
                                ParseContext context)
            throws IOException, SAXException, TikaException, ExecutionException, InterruptedException {
        if (parses == null || timeout == null) {
            parser.parse(stream, handler, metadata, context);
            return true;
        }

        Future<?> parse = parses.submit(() -> {
            parser.parse(stream, handler, metadata, context);
            return null;
        });

        try {
            parse.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            parse.cancel(true);
            log.warn("Cannot read the text of {}: its parse takes longer than {} ms", fileName, timeout.toMillis());
            return false;
        } catch (InterruptedException e) {
            parse.cancel(true);
            throw e;
        }
    }

    private InputStream open(FileRef file) {
        return fileStorageLocator.getByName(file.getStorageName()).openStream(file);
    }

    private static Optional<Parsed> unreadable(String fileName, Throwable e) {
        log.warn("Cannot read the text of {}: {}", fileName, e.toString());

        return Optional.empty();
    }

    private static String tidy(String text) {
        return text.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll(" ?\\n ?", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    private record Parsed(String text, boolean cut) {
    }
}
