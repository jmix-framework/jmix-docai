package com.company.docai.app.service.trash;

import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.app.service.document.RevisionFiles;
import com.company.docai.app.service.folder.FolderIndex;
import com.company.docai.model.access.AccessSet;
import com.company.docai.model.document.Document;
import com.company.docai.model.document.DocumentComment;
import com.company.docai.model.document.DocumentRevision;
import com.company.docai.model.favorite.Favorite;
import com.company.docai.model.folder.Folder;
import io.jmix.core.FileRef;
import io.jmix.core.Messages;
import io.jmix.core.MetadataTools;
import io.jmix.core.SaveContext;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.data.PersistenceHints;
import io.jmix.dynattr.model.CategoryAttributeValue;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Deletes items of the trash forever for {@link TrashService}. The rows go in one transaction, and a deleted folder
 * goes as a whole; the files of the deleted versions leave the storage after the commit.
 */
@Component
public class TrashPurge {

    private final UnconstrainedDataManager unconstrainedDataManager;
    private final TrashRows trashRows;
    private final RevisionFiles revisionFiles;
    private final MetadataTools metadataTools;
    private final Messages messages;
    private final TransactionTemplate transaction;

    public TrashPurge(UnconstrainedDataManager unconstrainedDataManager, TrashRows trashRows,
                      RevisionFiles revisionFiles, MetadataTools metadataTools, Messages messages,
                      PlatformTransactionManager transactionManager) {
        this.unconstrainedDataManager = unconstrainedDataManager;
        this.trashRows = trashRows;
        this.revisionFiles = revisionFiles;
        this.metadataTools = metadataTools;
        this.messages = messages;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Deletes the folders and the documents with the ids forever, as {@link TrashService#purge} describes it. An id
     * no longer in the trash is left alone.
     *
     * @throws OperationRejectedException if a folder holds something that is not in the trash
     */
    public void purge(Collection<UUID> folderIds, Collection<UUID> documentIds) {
        revisionFiles.removeFiles(Objects.requireNonNull(transaction.execute(status ->
                purgeRows(folderIds, documentIds))));
    }

    private List<FileRef> purgeRows(Collection<UUID> folderIds, Collection<UUID> documentIds) {
        // the rows stay locked, so a restore of the same items waits, and are checked again: what came back stays
        List<Folder> roots = trashRows.lockDeleted(Folder.class, folderIds);
        FolderIndex tree = trashRows.loadTree();

        // a deleted folder goes as a whole: what lies below it was deleted with it or before it
        List<List<UUID>> levels = new ArrayList<>();
        List<UUID> level = roots.stream()
                .map(Folder::getId)
                .toList();

        while (!level.isEmpty()) {
            levels.add(level);
            List<UUID> parents = level;

            level = tree.folders()
                    .stream()
                    .filter(child -> child.getDeletedDate() != null && child.getParent() != null
                            && parents.contains(child.getParent().getId()))
                    .map(Folder::getId)
                    .toList();
        }

        List<UUID> folders = levels.stream()
                .flatMap(List::stream)
                .toList();
        checkNothingLiveBelow(roots, folders, tree);

        Set<UUID> documents = new LinkedHashSet<>(trashRows.documentsIn(folders));
        trashRows.lockDeleted(Document.class, documentIds)
                .forEach(document -> documents.add(document.getId()));

        List<DocumentRevision> revisions = loadWithDeleted(DocumentRevision.class, "e.document.id in :ids", documents);
        List<FileRef> files = revisions.stream()
                .map(DocumentRevision::getFile)
                .distinct()
                .toList();

        List<AccessSet> ownSets = loadWithDeleted(AccessSet.class,
                "e.ownerFolder.id in :ids or e.ownerDocument.id in :ids",
                union(folders, documents));

        // a document and its current version refer to each other, as an item and its own access set do
        List<Document> stored = loadWithDeleted(Document.class, "e.id in :ids", documents);
        stored.forEach(document -> document.setCurrentRevision(null));

        ownSets.forEach(accessSet -> {
            accessSet.setOwnerFolder(null);
            accessSet.setOwnerDocument(null);
        });

        unconstrainedDataManager.save(new SaveContext()
                .saving(stored)
                .saving(ownSets)
                .setHint(PersistenceHints.SOFT_DELETION, false)
                .setDiscardSaved(true));

        // the add-on keeps the values of the fields by the id of the document and leaves them when it goes;
        // a value of a list field refers to the value it belongs to, so it goes first
        deleteForever(CategoryAttributeValue.class, "e.entity.entityId in :ids and e.parent is not null", documents);
        deleteForever(CategoryAttributeValue.class, "e.entity.entityId in :ids", documents);
        // a comment refers to its document, and the document does not know its comments
        deleteForever(DocumentComment.class, "e.document.id in :ids", documents);
        deleteForever(DocumentRevision.class, "e.document.id in :ids", documents);
        // the marks of the users keep their targets in the trash and go with them
        deleteForever(Favorite.class, "e.document.id in :ids", documents);
        deleteForever(Favorite.class, "e.folder.id in :ids", folders);
        deleteForever(Document.class, "e.id in :ids and e.deletedDate is not null", documents);

        for (List<UUID> folderLevel : levels.reversed()) {
            deleteForever(Folder.class, "e.id in :ids and e.deletedDate is not null", folderLevel);
        }

        // the database deletes the entries with the set
        deleteForever(AccessSet.class, "e.id in :ids", ownSets.stream()
                .map(AccessSet::getId)
                .toList());

        return files;
    }

    // something live below a deleted folder, left by a race of a delete or by a folder restored alone in Data Tools,
    // keeps the folder: deleting it forever would take the live item along or fail on it
    private void checkNothingLiveBelow(List<Folder> roots, List<UUID> folders, FolderIndex tree) {
        Set<UUID> purged = new HashSet<>(folders);

        List<UUID> holders = new ArrayList<>(tree.folders()
                .stream()
                .filter(folder -> folder.getDeletedDate() == null && folder.getParent() != null
                        && purged.contains(folder.getParent().getId()))
                .map(folder -> folder.getParent().getId())
                .toList());

        // the soft deletion would hide a live document together with its deleted folder, and with the soft deletion
        // off the query ignores "deletedDate is null", so the date is checked here
        if (!folders.isEmpty()) {
            unconstrainedDataManager.loadValues("select d.folder.id, d.deletedDate from Document d"
                            + " where d.folder.id in :folders")
                    .properties("folderId", "deletedDate")
                    .parameter("folders", folders)
                    .hint(PersistenceHints.SOFT_DELETION, false)
                    .list()
                    .stream()
                    .filter(value -> value.getValue("deletedDate") == null)
                    .forEach(value -> holders.add(value.getValue("folderId")));
        }

        if (holders.isEmpty()) {
            return;
        }

        Set<UUID> rootIds = roots.stream()
                .map(Folder::getId)
                .collect(Collectors.toSet());
        Folder root = tree.get(holders.getFirst());

        while (!rootIds.contains(root.getId())) {
            root = Objects.requireNonNull(tree.parentOf(root));
        }

        throw new OperationRejectedException(
                messages.formatMessage(getClass(), "purgeLiveContent", metadataTools.getInstanceName(root)));
    }

    private <E> List<E> loadWithDeleted(Class<E> type, String condition, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }

        return unconstrainedDataManager.load(type)
                .query(condition)
                .parameter("ids", ids)
                .hint(PersistenceHints.SOFT_DELETION, false)
                .list();
    }

    private <E> void deleteForever(Class<E> type, String condition, Collection<UUID> ids) {
        List<E> rows = loadWithDeleted(type, condition, ids);

        if (!rows.isEmpty()) {
            unconstrainedDataManager.save(new SaveContext()
                    .removing(rows)
                    .setHint(PersistenceHints.SOFT_DELETION, false));
        }
    }

    private static List<UUID> union(Collection<UUID> first, Collection<UUID> second) {
        List<UUID> union = new ArrayList<>(first);
        union.addAll(second);

        return union;
    }
}
