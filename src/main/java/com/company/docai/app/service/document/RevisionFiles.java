package com.company.docai.app.service.document;

import com.company.docai.app.config.FilesProperties;
import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.model.document.Document;
import io.jmix.core.FileRef;
import io.jmix.core.FileStorage;
import io.jmix.core.FileStorageLocator;
import io.jmix.core.Messages;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.data.PersistenceHints;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The files of the document versions in the file storage, which is not transactional: the file of a new version is
 * stored before the data and removed again if the data cannot be saved, and the files of the versions deleted forever
 * go after the commit.
 */
@Component
public class RevisionFiles {

    private static final Logger log = LoggerFactory.getLogger(RevisionFiles.class);

    private final UnconstrainedDataManager unconstrainedDataManager;
    private final FileStorageLocator fileStorageLocator;
    private final ContentTypeDetector contentTypeDetector;
    private final FilesProperties filesProperties;
    private final Messages messages;
    private final TransactionTemplate transaction;

    public RevisionFiles(UnconstrainedDataManager unconstrainedDataManager, FileStorageLocator fileStorageLocator,
                         ContentTypeDetector contentTypeDetector, FilesProperties filesProperties, Messages messages,
                         PlatformTransactionManager transactionManager) {
        this.unconstrainedDataManager = unconstrainedDataManager;
        this.fileStorageLocator = fileStorageLocator;
        this.contentTypeDetector = contentTypeDetector;
        this.filesProperties = filesProperties;
        this.messages = messages;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * A file stored for a new version, with its size and content type.
     */
    public record StoredFile(FileRef fileRef, long size, String contentType) {
    }

    /**
     * Stores the uploaded file in the default storage for a new version and returns it with its size and content
     * type.
     *
     * @throws OperationRejectedException if the file is larger than the maximum file size
     */
    public StoredFile store(UploadedFile file) {
        try {
            long size = Files.size(file.content());

            if (size > filesProperties.maxFileSize().toBytes()) {
                throw new OperationRejectedException(messages.formatMessage(getClass(), "fileTooLarge", file.fileName(),
                        filesProperties.maxFileSize().toMegabytes()));
            }

            String contentType = contentTypeDetector.detect(file.content(), file.fileName());

            try (InputStream content = Files.newInputStream(file.content())) {
                FileRef fileRef = fileStorageLocator.getDefault()
                        .saveStream(file.fileName(), content);
                return new StoredFile(fileRef, size, contentType);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Runs the work in a transaction and removes the just stored file if the transaction fails:
     * file storage is not transactional.
     */
    public Document inTransaction(@Nullable StoredFile stored, Supplier<Document> work) {
        try {
            return Objects.requireNonNull(transaction.execute(status -> work.get()));
        } catch (RuntimeException e) {
            if (stored != null) {
                FileStorage storage = fileStorageLocator.getByName(stored.fileRef().getStorageName());
                storage.removeFile(stored.fileRef());
            }

            throw e;
        }
    }

    /**
     * Removes the files of the versions deleted forever. File storage is not transactional: a file goes after its
     * rows, and only when no version refers to it any more. A file that cannot be removed is logged.
     */
    public void removeFiles(List<FileRef> files) {
        for (FileRef file : files) {
            long references = unconstrainedDataManager.loadValue(
                            "select count(r) from DocumentRevision r where r.file = :file", Long.class)
                    .parameter("file", file)
                    .hint(PersistenceHints.SOFT_DELETION, false)
                    .one();

            if (references > 0) {
                continue;
            }

            try {
                fileStorageLocator.getByName(file.getStorageName())
                        .removeFile(file);
            } catch (RuntimeException e) {
                log.warn("Cannot remove the file {} of a version deleted forever", file, e);
            }
        }
    }
}
