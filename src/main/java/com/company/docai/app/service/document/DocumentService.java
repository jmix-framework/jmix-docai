package com.company.docai.app.service.document;

import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.app.service.access.AccessChecker;
import com.company.docai.app.service.access.AccessService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.app.service.tag.TagService;
import com.company.docai.model.document.Document;
import com.company.docai.model.document.DocumentRevision;
import com.company.docai.model.document.DocumentStatus;
import com.company.docai.model.folder.Folder;
import io.jmix.core.DataManager;
import io.jmix.core.EntitySet;
import io.jmix.core.FetchPlan;
import io.jmix.core.FileRef;
import io.jmix.core.Messages;
import io.jmix.core.MetadataTools;
import io.jmix.core.SaveContext;
import io.jmix.core.TimeSource;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.dynattr.DynAttrQueryHints;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Creates, versions, moves and deletes documents. A document gets its files as revisions:
 * a revision never changes, and the document points to its current one. The file of a new
 * revision is stored before the data and removed again if the data cannot be saved.
 * A change needs edit access to the document, a new document to its folder. A change of the
 * document itself, its file, name, date, type, fields or status, stamps who made it and when; a move
 * does not. A document keeps only the fields of its type: the values of other types go on save. Its tags change
 * with it, also in a locked status, and are no change of the document itself; a new tag joins the vocabulary
 * with the document.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private static final int MAX_NAME_LENGTH = 255;

    private final DataManager dataManager;
    private final UnconstrainedDataManager unconstrainedDataManager;
    private final FolderService folderService;
    private final DocumentFields documentFields;
    private final TagService tagService;
    private final DocumentStatusService statusService;
    private final AccessChecker accessChecker;
    private final AccessService accessService;
    private final RevisionFiles revisionFiles;
    private final Messages messages;
    private final MetadataTools metadataTools;
    private final CurrentAuthentication currentAuthentication;
    private final TimeSource timeSource;

    public DocumentService(DataManager dataManager, UnconstrainedDataManager unconstrainedDataManager,
                           FolderService folderService, DocumentFields documentFields, TagService tagService,
                           DocumentStatusService statusService, AccessChecker accessChecker,
                           AccessService accessService, RevisionFiles revisionFiles,
                           Messages messages, MetadataTools metadataTools,
                           CurrentAuthentication currentAuthentication, TimeSource timeSource) {
        this.dataManager = dataManager;
        this.unconstrainedDataManager = unconstrainedDataManager;
        this.folderService = folderService;
        this.documentFields = documentFields;
        this.tagService = tagService;
        this.statusService = statusService;
        this.accessChecker = accessChecker;
        this.accessService = accessService;
        this.revisionFiles = revisionFiles;
        this.messages = messages;
        this.metadataTools = metadataTools;
        this.currentAuthentication = currentAuthentication;
        this.timeSource = timeSource;
    }

    /**
     * Creates a document from every file, each in its own transaction. A file that breaks
     * a rule or fails to save is skipped and reported; the others are created.
     *
     * @throws OperationRejectedException if the folder is unavailable or only viewed by the user
     */
    public UploadReport upload(Folder folder, List<UploadedFile> files) {
        // one refusal for the whole upload instead of one for every file
        folderService.checkEditable(folder);

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
            } catch (RuntimeException e) {
                log.error("Failed to upload {}", file.fileName(), e);
                skipped.add(new UploadReport.SkippedFile(file.fileName(),
                        messages.formatMessage(getClass(), "uploadFailed", file.fileName())));
            }
        }

        return new UploadReport(created, skipped);
    }

    /**
     * Creates a document with its first revision. The document starts in the initial status and then
     * moves to the target status, if one is given.
     *
     * @throws OperationRejectedException if the folder is unavailable or only viewed by the user, the name
     *                                    is taken or too long, the file is too large, or the status cannot
     *                                    be reached
     */
    public Document create(Document draft, UploadedFile file, @Nullable DocumentStatus targetStatus) {
        String name = checkName(draft.getName());
        RevisionFiles.StoredFile stored = revisionFiles.store(file);

        return revisionFiles.inTransaction(stored, () -> {
            Folder folder = folderService.lockLive(draft.getFolder());
            folderService.checkEditable(folder);
            checkNameFree(folder, name, null);

            draft.setFolder(folder);
            draft.setName(name);
            DocumentStatus initial = statusService.getInitial();
            draft.setStatus(initial);

            documentFields.keepOwnType(draft);
            stamp(draft);

            // the document and its revision refer to each other, so the current revision is set in a second save
            DocumentRevision revision = newRevision(draft, 1, stored.fileRef(), stored.size(), stored.contentType());

            EntitySet saved = dataManager.save(new SaveContext()
                    .saving(draft, revision)
                    .saving(tagService.keepVocabulary(draft)));

            Document document = saved.get(draft);
            document.setCurrentRevision(saved.get(revision));

            // the saved document refers to its status by id only, so the move starts from the initial one
            if (targetStatus != null && !targetStatus.equals(initial)) {
                document.setStatus(checkTransition(document, initial, targetStatus));
            }

            return dataManager.save(document);
        });
    }

    /**
     * Saves the changes of the document card: the edited fields, the type and its fields, a new version,
     * and the status transition last, because a document in a locked status accepts no other change.
     *
     * @throws OperationRejectedException if the document is unavailable or only viewed by the user, it is in
     *                                    a locked status and its content changes, the name is taken or too long,
     *                                    the file is too large, or the status cannot be reached
     */
    public Document save(Document document, @Nullable NewVersion newVersion, @Nullable DocumentStatus targetStatus) {
        String name = checkName(document.getName());

        RevisionFiles.StoredFile stored = newVersion instanceof NewVersion.FromFile(UploadedFile file)
                ? revisionFiles.store(file)
                : null;

        return revisionFiles.inTransaction(stored, () -> {
            Document persisted = loadEditable(document);

            boolean contentChanged = !name.equals(persisted.getName())
                    || !Objects.equals(document.getDocumentDate(), persisted.getDocumentDate())
                    || newVersion != null
                    || documentFields.differ(document, persisted);
            boolean statusChanged = targetStatus != null && !targetStatus.equals(persisted.getStatus());
            DocumentStatus current = statusService.reload(persisted.getStatus());

            if (contentChanged && Boolean.TRUE.equals(current.getLocked())) {
                throw reject("documentLocked", persisted.getName(), current.getName());
            }

            if (!name.equals(persisted.getName())) {
                checkNameFree(folderService.lockLive(persisted.getFolder()), name, document.getId());
            }

            document.setName(name);
            if (contentChanged || statusChanged) {
                stamp(document);
            }

            SaveContext saveContext = new SaveContext()
                    .saving(document)
                    .saving(tagService.keepVocabulary(document));

            if (newVersion != null) {
                DocumentRevision revision = switch (newVersion) {
                    case NewVersion.FromFile ignored -> newRevision(document, nextNumber(document),
                            stored.fileRef(), stored.size(), stored.contentType());
                    case NewVersion.FromRevision(DocumentRevision source) -> copyOf(document, source);
                };

                document.setCurrentRevision(revision);
                saveContext.saving(revision);
            }

            if (statusChanged) {
                document.setStatus(checkTransition(persisted, persisted.getStatus(), targetStatus));
            }

            // last: a refused change leaves the card with the values of the former type
            documentFields.keepOwnType(document);
            return dataManager.save(saveContext).get(document);
        });
    }

    /**
     * Adds a new version from the repository, outside the card.
     *
     * @throws OperationRejectedException if the document is unavailable or only viewed by the user, it is in
     *                                    a locked status, or the file is too large
     */
    public Document uploadRevision(Document document, UploadedFile file) {
        Document persisted = loadEditable(document);
        return save(persisted, new NewVersion.FromFile(file), null);
    }

    /**
     * Moves the documents to the target status, all of them or none.
     *
     * @throws OperationRejectedException if one of the documents is unavailable or only viewed by the user,
     *                                    or the transition is not allowed for it
     */
    public void changeStatus(Collection<Document> documents, DocumentStatus target) {
        List<Document> changed = new ArrayList<>();
        for (Document document : documents) {
            Document persisted = loadEditable(document);
            if (!target.equals(persisted.getStatus())) {
                persisted.setStatus(checkTransition(persisted, persisted.getStatus(), target));
                stamp(persisted);
                changed.add(persisted);
            }
        }

        dataManager.save(new SaveContext()
                .saving(changed)
                .setDiscardSaved(true));
    }

    /**
     * Moves the documents into the target folder, all of them or none. The folder row stays locked
     * until the transaction ends, so the taken names cannot change before the save. A document that
     * inherits its access takes the access of the target.
     *
     * @throws OperationRejectedException if the target or one of the documents is unavailable or only viewed
     *                                    by the user, or the target already has documents with these names;
     *                                    the message lists all of them
     */
    @Transactional
    public void move(Collection<Document> documents, Folder target) {
        Folder destination = folderService.lockLive(target);
        folderService.checkEditable(destination);
        Set<String> takenNames = new HashSet<>(unconstrainedDataManager.loadValues(
                        "select d.nameKey from Document d where d.folder = :folder")
                .properties("name")
                .parameter("folder", destination)
                .list().stream()
                .map(value -> value.<String>getValue("name"))
                .toList());

        List<Document> moved = new ArrayList<>();
        List<String> clashes = new ArrayList<>();
        for (Document document : documents) {
            Document persisted = loadEditable(document);
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

        SaveContext saveContext = new SaveContext()
                .saving(moved)
                .setDiscardSaved(true);

        accessService.followMove(moved, destination, saveContext);
        dataManager.save(saveContext);
    }

    /**
     * Soft-deletes the documents together with their revisions.
     *
     * @throws OperationRejectedException if one of the documents is unavailable or only viewed by the user
     */
    public void delete(Collection<Document> documents) {
        SaveContext saveContext = new SaveContext()
                .setDiscardSaved(true);

        documents.stream()
                .map(this::loadEditable)
                .forEach(saveContext::removing);

        dataManager.save(saveContext);
    }

    /**
     * Loads the document for a change by the current user.
     *
     * @throws OperationRejectedException if the document is deleted or hidden from the user, or the user only
     *                                    views it
     */
    private Document loadEditable(Document document) {
        Document persisted = dataManager.load(Document.class)
                .id(document.getId())
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("folder", FetchPlan.BASE)
                        .add("category", FetchPlan.INSTANCE_NAME)
                        .add("status", status -> status.addFetchPlan(FetchPlan.BASE)
                                .add("targets", FetchPlan.BASE)))
                .hint(DynAttrQueryHints.LOAD_DYN_ATTR, true)
                .optional()
                .orElseThrow(() -> reject("documentUnavailable", metadataTools.getInstanceName(document)));

        if (!accessChecker.canEdit(persisted)) {
            throw reject("documentReadOnly", metadataTools.getInstanceName(persisted));
        }

        return persisted;
    }

    private void stamp(Document document) {
        document.setChangedBy(currentAuthentication.getUser().getUsername());
        document.setChangedDate(timeSource.now().toOffsetDateTime());
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
        List<Document> namesakes = unconstrainedDataManager.load(Document.class)
                .query("select d from Document d where d.folder = :folder and d.nameKey = :name")
                .parameter("folder", folder)
                .parameter("name", name)
                .list();

        if (namesakes.stream().anyMatch(namesake -> !namesake.getId().equals(ownId))) {
            throw reject("documentNameTaken", metadataTools.getInstanceName(folder), name);
        }
    }

    private DocumentStatus checkTransition(Document document, DocumentStatus from, DocumentStatus to) {
        DocumentStatus source = statusService.reload(from);
        // a card or the table may still offer a status that the administrator has deleted meanwhile
        DocumentStatus target = statusService.findById(to.getId())
                .orElseThrow(() -> reject("statusGone", to.getName()));

        if (!source.canMoveTo(target)) {
            throw reject("statusTransitionNotAllowed", document.getName(), source.getName(), target.getName());
        }

        return target;
    }

    private OperationRejectedException reject(String key, Object... args) {
        return new OperationRejectedException(messages.formatMessage(getClass(), key, args));
    }
}
