package com.company.dms.test_support;

import com.company.dms.model.document.Document;
import com.company.dms.model.document.DocumentRevision;
import com.company.dms.model.trash.TrashItem;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.SaveContext;
import io.jmix.data.PersistenceHints;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Prepares and reads the trash in tests. Run it as the administrator.
 */
public final class TestTrash {

    private TestTrash() {
    }

    /**
     * Moves the deletion of the document and of its versions back by the given time, as if the document had been
     * deleted on its own long before its folder: the restore of Jmix takes back together only what was deleted
     * within a second of its parent.
     */
    public static void deletedEarlier(DataManager dataManager, Document document, Duration earlier) {
        Document stored = dataManager.load(Id.of(document))
                .hint(PersistenceHints.SOFT_DELETION, false)
                .one();
        List<DocumentRevision> revisions = dataManager.load(DocumentRevision.class)
                .query("e.document = :document")
                .parameter("document", stored)
                .hint(PersistenceHints.SOFT_DELETION, false)
                .list();

        stored.setDeletedDate(stored.getDeletedDate().minus(earlier));
        revisions.forEach(revision -> revision.setDeletedDate(revision.getDeletedDate().minus(earlier)));

        dataManager.save(new SaveContext()
                .saving(stored)
                .saving(revisions)
                .setHint(PersistenceHints.SOFT_DELETION, false));
    }

    /**
     * Returns the item with the id among the items.
     */
    public static TrashItem itemOf(Collection<TrashItem> items, UUID id) {
        return items.stream()
                .filter(item -> item.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No item " + id + " in the trash"));
    }

    /**
     * Returns the ids of the items in their order.
     */
    public static List<UUID> ids(Collection<TrashItem> items) {
        return items.stream()
                .map(TrashItem::getId)
                .toList();
    }

    /**
     * Tells whether a row with the id is still in the database, deleted softly or not.
     */
    public static boolean exists(DataManager dataManager, Class<?> type, UUID id) {
        return dataManager.load(type)
                .id(id)
                .hint(PersistenceHints.SOFT_DELETION, false)
                .optional()
                .isPresent();
    }

    /**
     * Tells whether the row with the id lies in the trash.
     */
    public static boolean isDeleted(DataManager dataManager, Class<?> type, UUID id) {
        return dataManager.load(type)
                .id(id)
                .optional()
                .isEmpty() && exists(dataManager, type, id);
    }
}
