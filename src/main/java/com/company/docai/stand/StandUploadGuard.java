package com.company.docai.stand;

import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.app.service.document.ContentTypeDetector;
import com.company.docai.app.service.document.UploadedFile;
import io.jmix.core.Messages;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MimeType;
import org.apache.tika.mime.MimeTypeException;
import org.apache.tika.mime.MimeTypes;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Checks the files a visitor stores in their copy: their type by their bytes, the free space on the disk and the quota
 * of the copy. The file storage of the stand asks for every file a copy stores, whoever writes it; the build of a
 * template and a thread without a scope upload nothing of a visitor, so they are not checked.
 */
@Component
@Profile("stand")
public class StandUploadGuard {

    private static final String BYTES = MediaType.OCTET_STREAM.toString();
    private static final String TEXT = MediaType.TEXT_PLAIN.toString();

    private static final List<String> TEXT_NAMES = List.of(".txt", ".csv");

    private static final Map<String, List<String>> EXTENSIONS_BY_TYPE = Map.of(
            "application/pdf", List.of(".pdf"),
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", List.of(".docx"),
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", List.of(".xlsx"),
            "text/plain", List.of(".txt"),
            "text/csv", List.of(".csv"),
            "image/png", List.of(".png"),
            "image/jpeg", List.of(".jpg", ".jpeg"));

    private final StandProperties properties;
    private final CopyRegistry registry;
    private final ContentTypeDetector contentTypes;
    private final Messages messages;
    private final String formats;
    private final List<String> extensions;

    public StandUploadGuard(StandProperties properties,
                            CopyRegistry registry,
                            ContentTypeDetector contentTypes,
                            Messages messages) {
        this.properties = properties;
        this.registry = registry;
        this.contentTypes = contentTypes;
        this.messages = messages;
        this.formats = formatsOf(properties.uploadTypes());
        this.extensions = extensionsOf(properties.uploadTypes());
    }

    /**
     * The extensions of the files a visitor can upload, in lower case; a file is still checked by its bytes.
     */
    public List<String> fileExtensions() {
        return extensions;
    }

    /**
     * The notice of a file whose type a visitor cannot upload, which names the formats they can.
     */
    public String typeRefusal(String fileName) {
        return messages.formatMessage(getClass(), "uploadTypeNotAllowed", fileName, formats);
    }

    /**
     * Checks a file before it is stored in the copy of the thread: its type first, then the disk, then the quota.
     *
     * @param size the size of the file in bytes
     * @throws OperationRejectedException when the bytes of the file show a type a visitor cannot upload, the disk is
     *                                    low or the file does not fit into the quota of the copy
     * @throws IllegalStateException      when the copy is gone
     */
    public void check(UploadedFile file, long size) {
        StandScope scope = StandScopeHolder.resolve();

        if (scope == null || !scope.isCopy()) {
            return;
        }

        try {
            checkType(file.content(), file.fileName());
            checkFreeDisk(file.fileName());
            checkQuota(scope, file.fileName(), size);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Checks the disk before a copy stores a file, as the file storage of the stand does for every file of a copy.
     *
     * @throws OperationRejectedException when the disk is low
     */
    public void checkDisk(String fileName) {
        try {
            checkFreeDisk(fileName);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Checks a file the copy has just stored: its type, then the quota of the copy, the file counted in. The storage
     * removes a file this refuses.
     *
     * @throws OperationRejectedException when the bytes of the file show a type a visitor cannot upload or the copy is
     *                                    over its quota with it
     * @throws IllegalStateException      when the copy is gone
     */
    public void checkStored(StandScope copy, Path file, String fileName) {
        try {
            checkType(file, fileName);
            checkQuota(copy, fileName, 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // a name and the type a browser sends are the visitor's word; the bytes are what a preview and a parser get
    private void checkType(Path file, String fileName) throws IOException {
        String type = MediaType.parse(contentTypes.detectByContent(file))
                .getBaseType()
                .toString();

        // Tika knows a text by bytes of ASCII or UTF-8, and a CSV that Excel saves on a Russian Windows has neither
        if (BYTES.equals(type) && isText(file, fileName)) {
            type = TEXT;
        }

        boolean allowed = properties.uploadTypes()
                .contains(type);

        if (!allowed) {
            throw new OperationRejectedException(typeRefusal(fileName));
        }
    }

    private void checkFreeDisk(String fileName) throws IOException {
        long free = Files.getFileStore(properties.dataDir())
                .getUsableSpace();

        long minimum = properties.minFreeDisk()
                .toBytes();

        if (free < minimum) {
            throw reject("uploadsPaused", fileName);
        }
    }

    private void checkQuota(StandScope copy, String fileName, long size) throws IOException {
        DataSize quota = properties.copyUploadQuota();

        if (ownBytes(copy) + size > quota.toBytes()) {
            throw reject("uploadOverQuota", fileName, quota.toMegabytes());
        }
    }

    // the uploads of the visitor: the files of the copy that its template does not have at the same place
    private long ownBytes(StandScope copy) throws IOException {
        Copy registered = registry.find(copy.key())
                .orElseThrow(() -> new IllegalStateException("The copy " + copy.tag() + " is gone"));

        Path template = filesOf(StandScope.template(registered.language()));
        Path files = filesOf(copy);

        if (!Files.isDirectory(files)) {
            return 0;
        }

        AtomicLong bytes = new AtomicLong();

        Files.walkFileTree(files, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile() && Files.notExists(template.resolve(files.relativize(file)))) {
                    bytes.addAndGet(attributes.size());
                }

                return FileVisitResult.CONTINUE;
            }

            // a file deleted forever meanwhile counts no more
            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });

        return bytes.get();
    }

    private Path filesOf(StandScope scope) {
        return scope.folder(properties.dataDir())
                .resolve(StandFileStorage.FILES);
    }

    private OperationRejectedException reject(String key, Object... args) {
        return new OperationRejectedException(messages.formatMessage(getClass(), key, args));
    }

    private static boolean isText(Path file, String fileName) throws IOException {
        String name = fileName.toLowerCase(Locale.ROOT);

        boolean named = TEXT_NAMES.stream()
                .anyMatch(name::endsWith);

        if (!named || Files.size(file) == 0) {
            return false;
        }

        try (InputStream content = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];

            for (int read = content.read(buffer); read != -1; read = content.read(buffer)) {
                for (int i = 0; i < read; i++) {
                    if (isControl(buffer[i])) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    // the bytes of a legacy encoding above 0x7F are letters, as in Windows-1251
    private static boolean isControl(byte value) {
        return value >= 0 && value < 0x20 && value != '\t' && value != '\n' && value != '\r' || value == 0x7F;
    }

    private static String formatsOf(List<String> types) {
        List<String> formats = new ArrayList<>();

        for (String type : types) {
            MimeType known = registered(type);
            String acronym = known == null ? "" : known.getAcronym();
            String extension = known == null ? "" : known.getExtension();

            if (!acronym.isEmpty()) {
                formats.add(acronym);
            } else if (!extension.isEmpty()) {
                formats.add(extension.substring(1)
                        .toUpperCase(Locale.ROOT));
            } else {
                formats.add(type);
            }
        }

        return String.join(", ", formats);
    }

    private static List<String> extensionsOf(List<String> types) {
        Set<String> extensions = new LinkedHashSet<>();

        for (String type : types) {
            // Tika knows dozens of extensions of a text, .bsh and .classpath among them, and a picker would offer all
            List<String> named = EXTENSIONS_BY_TYPE.get(type);

            // a type without an extension could never be chosen in a file picker that offers only these
            if (named == null) {
                throw new IllegalStateException("docai.stand.upload-types holds a type without file extensions: "
                        + type);
            }

            extensions.addAll(named);
        }

        return List.copyOf(extensions);
    }

    private static @Nullable MimeType registered(String type) {
        try {
            return MimeTypes.getDefaultMimeTypes()
                    .getRegisteredMimeType(type);
        } catch (MimeTypeException e) {
            throw new IllegalStateException("docai.stand.upload-types holds an invalid type: " + type, e);
        }
    }
}
