package com.company.docai.app.service.trash;

import com.company.docai.app.service.folder.FolderIndex;
import com.company.docai.model.folder.Folder;
import io.jmix.core.FetchPlan;
import io.jmix.core.FluentValuesLoader;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.data.PersistenceHints;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The rows that the restore and the purge of the trash share, read past the soft deletion and the access rules:
 * the items of the trash, locked for a change, the whole folder tree and the deleted documents of folders.
 */
@Component
public class TrashRows {

    private final UnconstrainedDataManager unconstrainedDataManager;

    public TrashRows(UnconstrainedDataManager unconstrainedDataManager) {
        this.unconstrainedDataManager = unconstrainedDataManager;
    }

    /**
     * Locks the rows with the ids that lie in the trash until the transaction ends and returns them; a row no longer
     * in the trash is left out. A restore and a purge of the same items take the same locks, so one waits for the
     * other.
     */
    public <E> List<E> lockDeleted(Class<E> type, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return new ArrayList<>();
        }

        return new ArrayList<>(unconstrainedDataManager.load(type)
                .query("e.id in :ids and e.deletedDate is not null")
                .parameter("ids", ids)
                .lockMode(LockModeType.PESSIMISTIC_WRITE)
                .hint(PersistenceHints.SOFT_DELETION, false)
                .list());
    }

    /**
     * Returns the whole folder tree, the deleted folders too: a path names the folders on the way also to those who
     * may not open them.
     */
    public FolderIndex loadTree() {
        return FolderIndex.of(unconstrainedDataManager.load(Folder.class)
                .query("select f from Folder f")
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("parent", FetchPlan.INSTANCE_NAME))
                .hint(PersistenceHints.SOFT_DELETION, false)
                .list());
    }

    /**
     * Returns the ids of the deleted documents in the folders.
     */
    public List<UUID> documentsIn(Collection<UUID> folders) {
        if (folders.isEmpty()) {
            return List.of();
        }

        return ids(unconstrainedDataManager.loadValues(
                        "select d.id from Document d where d.folder.id in :folders and d.deletedDate is not null")
                .parameter("folders", folders));
    }

    /**
     * Returns the ids that the query of the loader selects, the rows in the trash included.
     */
    public static List<UUID> ids(FluentValuesLoader loader) {
        return loader.properties("id")
                .hint(PersistenceHints.SOFT_DELETION, false)
                .list()
                .stream()
                .map(value -> value.<UUID>getValue("id"))
                .toList();
    }
}
