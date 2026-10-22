package com.company.dms.app.service.document;

import com.company.dms.app.config.FilesProperties;
import com.company.dms.app.service.OperationRejectedException;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.document.Document;
import com.company.dms.model.document.DocumentRevision;
import com.company.dms.model.document.DocumentStatus;
import com.company.dms.model.folder.Folder;
import io.jmix.core.DataManager;
import io.jmix.core.EntitySet;
import io.jmix.core.FetchPlan;
import io.jmix.core.FetchPlanBuilder;
import io.jmix.core.FileRef;
import io.jmix.core.FileStorage;
import io.jmix.core.FileStorageLocator;
import io.jmix.core.Id;
import io.jmix.core.Messages;
import io.jmix.core.MetadataTools;
import io.jmix.core.SaveContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Creates, versions, moves and deletes documents. A document gets its files as revisions:
 * a revision never changes, and the document points to its current one. The file of a new
 * revision is stored before the data and removed again if the data cannot be saved.
 */
@Service
public class DocumentService {

    private static final int MAX_NAME_LENGTH = 255;

    private final DataManager dataManager;
    private final FolderService folderService;
    private final FileStorageLocator fileStorageLocator;
    private final ContentTypeDetector contentTypeDetector;
    private final FilesProperties filesProperties;
    private final Messages messages;
    private final MetadataTools metadataTools;
    private final TransactionTemplate transaction;

    public DocumentService(DataManager dataManager, FolderService folderService,
                           FileStorageLocator fileStorageLocator, ContentTypeDetector contentTypeDetector,
                           FilesProperties filesProperties, Messages messages, MetadataTools metadataTools,
                           PlatformTransactionManager transactionManager) {
        this.dataManager = dataManager;
        this.folderService = folderService;
        this.fileStorageLocator = fileStorageLocator;
        this.contentTypeDetector = contentTypeDetector;
        this.filesProperties = filesProperties;
        this.messages = messages;
        this.metadataTools = metadataTools;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Creates a document from every file, each in its own transaction. A file that breaks
     * a rule is skipped and reported; the others are created.
     */
    public UploadReport upload(Folder folder, List<UploadedFile> files) {
        List<Document> created = new ArrayList<>();
        List<UploadReport.SkippedFile> skipped = new ArrayList<>();

        for (UploadedFile file : files) {
            Document draft = dataManager.create(Document.class);
            draft.setFolder(folder);
            draft.setName(file.fileName());

            try {
                created.add(create(draft, file, null));
            } catch (OperationRejectedException e) {
                skipped.add(new UploadReport.SkippedFile(file.fileName(), e.getMessage()));
            }
        }

        return new UploadReport(created, skipped);
    }

    /**
     * Creates a document with its first revision. The document starts as a draft and then
     * moves to the target status, if one is given.
     *
     * @throws OperationRejectedException if the folder is deleted, the name is taken or too long,
     *                                    the file is too large, or the status cannot be reached
     */
    public Document create(Document draft, UploadedFile file, @Nullable DocumentStatus targetStatus) {
        String name = checkName(draft.getName());
        StoredFile stored = store(file);

        return inTransaction(stored, () -> {
            Folder folder = folderService.lockLive(draft.getFolder());
            checkNameFree(folder, name, null);
            draft.setFolder(folder);
            draft.setName(name);
            draft.setStatus(DocumentStatus.DRAFT);

            // the document and its revision refer to each other, so the current revision is set in a second save
            DocumentRevision revision = newRevision(draft, 1, stored.fileRef(), stored.size(), stored.contentType());
            EntitySet saved = dataManager.save(new SaveContext().saving(draft, revision));

            Document document = saved.get(draft);
            document.setCurrentRevision(saved.get(revision));

            if (targetStatus != null && targetStatus != DocumentStatus.DRAFT) {
                document.setStatus(checkTransition(document, DocumentStatus.DRAFT, targetStatus));
            }

            return dataManager.save(document);
        });
    }

    /**
     * Saves the changes of the document card: the edited fields, a new version, and the status
     * transition last, because an archived document accepts no other change.
     *
     * @throws OperationRejectedException if the document is archived and its content changes,
     *                                    the name is taken or too long, the file is too large,
     *                                    or the status cannot be reached
     */
    public Document save(Document document, @Nullable NewVersion newVersion, @Nullable DocumentStatus targetStatus) {
        String name = checkName(document.getName());
        StoredFile stored = newVersion instanceof NewVersion.FromFile(UploadedFile file)
                ? store(file)
                : null;

        return inTransaction(stored, () -> {
            Document persisted = dataManager.load(Document.class)
                    .id(document.getId())
                    .fetchPlan(DocumentService::withFolder)
                    .one();

            boolean contentChanged = !name.equals(persisted.getName())
                    || !Objects.equals(document.getDocumentDate(), persisted.getDocumentDate())
                    || newVersion != null;

            if (contentChanged && persisted.getStatus() == DocumentStatus.ARCHIVED) {
                throw reject("documentArchived", persisted.getName());
            }

            if (!name.equals(persisted.getName())) {
                checkNameFree(folderService.lockLive(persisted.getFolder()), name, document.getId());
            }

            document.setName(name);
            SaveContext saveContext = new SaveContext().saving(document);
            if (newVersion != null) {
                DocumentRevision revision = switch (newVersion) {
                    case NewVersion.FromFile ignored -> newRevision(document, nextNumber(document),
                            stored.fileRef(), stored.size(), stored.contentType());
                    case NewVersion.FromRevision(DocumentRevision source) -> copyOf(document, source);
                };

                document.setCurrentRevision(revision);
                saveContext.saving(revision);
            }

            if (targetStatus != null && targetStatus != persisted.getStatus()) {
                document.setStatus(checkTransition(persisted, persisted.getStatus(), targetStatus));
            }

            return dataManager.save(saveContext).get(document);
        });
    }

    /**
     * Adds a new version from the repository, outside the card.
     *
     * @throws OperationRejectedException if the document is archived or the file is too large
     */
    public Document uploadRevision(Document document, UploadedFile file) {
        Document persisted = dataManager.load(Id.of(document)).one();
        return save(persisted, new NewVersion.FromFile(file), null);
    }

    /**
     * Moves the documents to the target status, all of them or none.
     *
     * @throws OperationRejectedException if the transition is not allowed for one of the documents
     */
    public void changeStatus(Collection<Document> documents, DocumentStatus target) {
        List<Document> changed = new ArrayList<>();
        for (Document document : documents) {
            Document persisted = dataManager.load(Id.of(document)).one();
            if (persisted.getStatus() != target) {
                persisted.setStatus(checkTransition(persisted, persisted.getStatus(), target));
                changed.add(persisted);
            }
        }

        dataManager.save(new SaveContext().saving(changed));
    }

    /**
     * Moves the documents into the target folder, all of them or none. The folder row stays locked
     * until the transaction ends, so the taken names cannot change before the save.
     *
     * @throws OperationRejectedException if the target is deleted or already has documents
     *                                    with these names; the message lists all of them
     */
    @Transactional
    public void move(Collection<Document> documents, Folder target) {
        Folder destination = folderService.lockLive(target);
        Set<String> takenNames = new HashSet<>(dataManager.loadValues(
                        "select d.nameKey from Document d where d.folder = :folder")
                .properties("name")
                .parameter("folder", destination)
                .list().stream()
                .map(value -> value.<String>getValue("name"))
                .toList());

        List<Document> moved = new ArrayList<>();
        List<String> clashes = new ArrayList<>();
        for (Document document : documents) {
            Document persisted = dataManager.load(Document.class)
                    .id(document.getId())
                    .fetchPlan(DocumentService::withFolder)
                    .one();

            if (persisted.getFolder().equals(destination)) {
                continue;
            }

            if (!takenNames.add(persisted.getName())) {
                clashes.add(persisted.getName());
            }

            persisted.setFolder(destination);
            moved.add(persisted);
        }

        if (!clashes.isEmpty()) {
            throw reject("documentNamesTaken", metadataTools.getInstanceName(destination),
                    String.join(", ", clashes));
        }

        dataManager.save(new SaveContext().saving(moved));
    }

    /**
     * Soft-deletes the documents together with their revisions.
     */
    public void delete(Collection<Document> documents) {
        SaveContext saveContext = new SaveContext();
        documents.stream()
                .map(document -> dataManager.load(Id.of(document)).optional())
                .flatMap(Optional::stream)
                .forEach(saveContext::removing);

        dataManager.save(saveContext);
    }

    private StoredFile store(UploadedFile file) {
        try {
            long size = Files.size(file.content());
            if (size > filesProperties.maxFileSize().toBytes()) {
                throw reject("fileTooLarge", file.fileName(), filesProperties.maxFileSize().toMegabytes());
            }

            String contentType = contentTypeDetector.detect(file.content(), file.fileName());
            try (InputStream content = Files.newInputStream(file.content())) {
                FileRef fileRef = fileStorageLocator.getDefault().saveStream(file.fileName(), content);
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
    private Document inTransaction(@Nullable StoredFile stored, Supplier<Document> work) {
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

    private DocumentRevision newRevision(Document document, int number, FileRef file, long size, String contentType) {
        DocumentRevision revision = dataManager.create(DocumentRevision.class);
        revision.setDocument(document);
        revision.setNumber(number);
        revision.setFile(file);
        revision.setFileSize(size);
        revision.setContentType(contentType);
        return revision;
    }

    private DocumentRevision copyOf(Document document, DocumentRevision source) {
        DocumentRevision original = dataManager.load(DocumentRevision.class)
                .id(source.getId())
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE).add("document", FetchPlan.BASE))
                .one();

        if (!original.getDocument().equals(document)) {
            throw reject("revisionOfAnotherDocument");
        }

        return newRevision(document, nextNumber(document), original.getFile(), original.getFileSize(),
                original.getContentType());
    }

    private int nextNumber(Document document) {
        Integer last = dataManager.loadValue(
                        "select max(r.number) from DocumentRevision r where r.document.id = :document", Integer.class)
                .parameter("document", document.getId())
                .one();

        return last == null ? 1 : last + 1;
    }

    private String checkName(String name) {
        String stripped = name.strip();
        if (stripped.length() > MAX_NAME_LENGTH) {
            throw reject("nameTooLong", stripped.substring(0, 40), MAX_NAME_LENGTH);
        }

        return stripped;
    }

    private void checkNameFree(Folder folder, String name, @Nullable UUID ownId) {
        List<Document> namesakes = dataManager.load(Document.class)
                .query("select d from Document d where d.folder = :folder and d.nameKey = :name")
                .parameter("folder", folder)
                .parameter("name", name)
                .list();

        if (namesakes.stream().anyMatch(namesake -> !namesake.getId().equals(ownId))) {
            throw reject("documentNameTaken", metadataTools.getInstanceName(folder), name);
        }
    }

    private DocumentStatus checkTransition(Document document, DocumentStatus from, DocumentStatus to) {
        if (!from.canTransitionTo(to)) {
            throw reject("statusTransitionNotAllowed", document.getName(),
                    messages.getMessage(from), messages.getMessage(to));
        }

        return to;
    }

    private OperationRejectedException reject(String key, Object... args) {
        return new OperationRejectedException(messages.formatMessage(getClass(), key, args));
    }

    private static void withFolder(FetchPlanBuilder fetchPlan) {
        fetchPlan.addFetchPlan(FetchPlan.BASE)
                .add("folder", FetchPlan.BASE);
    }

    private record StoredFile(FileRef fileRef, long size, String contentType) {
    }
}
