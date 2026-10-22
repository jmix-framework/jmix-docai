package com.company.dms.app.service.folder;

import com.company.dms.app.service.OperationRejectedException;
import com.company.dms.model.folder.Folder;
import io.jmix.core.DataManager;
import io.jmix.core.EntityStates;
import io.jmix.core.FetchPlan;
import io.jmix.core.Messages;
import io.jmix.core.MetadataTools;
import jakarta.persistence.LockModeType;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Changes the folder tree: creates, renames, moves and deletes folders. The root stays as
 * the changelog created it, and names are unique among the live folders of one parent.
 */
@Service
public class FolderService {

    private final DataManager dataManager;
    private final EntityStates entityStates;
    private final Messages messages;
    private final MetadataTools metadataTools;

    public FolderService(DataManager dataManager, EntityStates entityStates, Messages messages,
                         MetadataTools metadataTools) {
        this.dataManager = dataManager;
        this.entityStates = entityStates;
        this.messages = messages;
        this.metadataTools = metadataTools;
    }

    /**
     * Returns the root folder, which the changelog creates with {@link Folder#ROOT_ID}.
     */
    public Folder getRoot() {
        return dataManager.load(Folder.class).id(Folder.ROOT_ID).one();
    }

    /**
     * Creates a folder or renames one, as the folder dialog does. The parent of an existing
     * folder changes only through {@link #move(Folder, Folder)}. The parent row stays locked
     * until the transaction ends, so a delete of the parent waits for this save.
     *
     * @throws OperationRejectedException if the folder is the root, the parent is deleted
     *                                    or the name is taken
     */
    @Transactional
    public Folder save(Folder folder) {
        String name = folder.getName().strip();

        if (entityStates.isNew(folder)) {
            Folder parent = lockLive(folder.getParent());
            checkNameFree(parent, name, null);
            folder.setParent(parent);
        } else {
            if (folder.isRoot()) {
                throw reject("rootFolderLocked");
            }

            Folder stored = loadWithParent(folder.getId());
            if (!Objects.equals(stored.getParent(), folder.getParent())) {
                throw reject("folderParentChangedBySave");
            }

            checkNameFree(lockLive(stored.getParent()), name, folder.getId());
        }

        folder.setName(name);
        return dataManager.save(folder);
    }

    /**
     * Moves a folder with its subtree into another folder.
     *
     * @throws OperationRejectedException if the folder is the root, the target is deleted, the target
     *                                    is the folder itself or one of its subfolders, or the target
     *                                    already has a folder with the same name
     */
    @Transactional
    public Folder move(Folder folder, Folder target) {
        // moves wait for each other, otherwise two crossing moves pass the cycle check and build a loop
        dataManager.load(Folder.class)
                .id(Folder.ROOT_ID)
                .lockMode(LockModeType.PESSIMISTIC_WRITE)
                .one();

        if (folder.isRoot()) {
            throw reject("rootFolderLocked");
        }

        Folder moved = loadWithParent(folder.getId());
        Folder destination = lockLive(target);
        if (destination.equals(moved.getParent())) {
            return moved;
        }

        for (Folder current = destination; current != null; current = loadWithParent(current.getId()).getParent()) {
            if (current.equals(moved)) {
                throw reject("folderMovedIntoItself", moved.getName());
            }
        }

        checkNameFree(destination, moved.getName(), moved.getId());
        moved.setParent(destination);
        return dataManager.save(moved);
    }

    /**
     * Soft-deletes the folder with all its subfolders, documents and their revisions. The folder
     * row is locked first, so a document or a folder being placed into it at the same time is
     * either deleted with it or rejected.
     *
     * @throws OperationRejectedException if the folder is the root
     */
    @Transactional
    public void delete(Folder folder) {
        if (folder.isRoot()) {
            throw reject("rootFolderLocked");
        }

        dataManager.load(Folder.class)
                .id(folder.getId())
                .lockMode(LockModeType.PESSIMISTIC_WRITE)
                .optional()
                .ifPresent(dataManager::remove);
    }

    /**
     * Counts what deleting the folder takes with it, for the confirmation dialog.
     */
    public FolderContents countContents(Folder folder) {
        List<UUID> subtree = new ArrayList<>(List.of(folder.getId()));
        List<UUID> level = List.of(folder.getId());

        while (!level.isEmpty()) {
            level = dataManager.loadValues("select f.id from Folder f where f.parent.id in :parents")
                    .properties("id")
                    .parameter("parents", level)
                    .list().stream()
                    .map(value -> value.<UUID>getValue("id"))
                    .toList();
            subtree.addAll(level);
        }

        long documents = dataManager.loadValue("select count(d) from Document d where d.folder.id in :folders",
                        Long.class)
                .parameter("folders", subtree)
                .one();

        return new FolderContents(subtree.size() - 1, documents);
    }

    /**
     * Returns the folders from the root down to the given one, for breadcrumbs and paths.
     */
    public List<Folder> getPath(Folder folder) {
        LinkedList<Folder> path = new LinkedList<>();
        for (Folder current = loadWithParent(folder.getId()); current != null;
             current = current.getParent() == null ? null : loadWithParent(current.getParent().getId())) {
            path.addFirst(current);
        }

        return path;
    }

    /**
     * Locks the folder row until the transaction ends and returns the live folder. Anything placed
     * into a folder takes this lock, so it waits for a delete of the same branch. Call it inside
     * a transaction: outside one, the lock ends together with the load.
     *
     * @throws OperationRejectedException if the folder is deleted
     */
    public Folder lockLive(Folder folder) {
        return dataManager.load(Folder.class)
                .id(folder.getId())
                .lockMode(LockModeType.PESSIMISTIC_WRITE)
                .optional()
                .orElseThrow(() -> reject("folderDeleted", metadataTools.getInstanceName(folder)));
    }

    private Folder loadWithParent(UUID id) {
        return dataManager.load(Folder.class)
                .id(id)
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("parent", FetchPlan.BASE))
                .one();
    }

    private void checkNameFree(Folder parent, String name, @Nullable UUID ownId) {
        List<Folder> namesakes = dataManager.load(Folder.class)
                .query("select f from Folder f where f.parent = :parent and f.nameKey = :name")
                .parameter("parent", parent)
                .parameter("name", name)
                .list();

        if (namesakes.stream().anyMatch(namesake -> !namesake.getId().equals(ownId))) {
            throw reject("folderNameTaken", metadataTools.getInstanceName(parent), name);
        }
    }

    private OperationRejectedException reject(String key, Object... args) {
        return new OperationRejectedException(messages.formatMessage(getClass(), key, args));
    }
}
