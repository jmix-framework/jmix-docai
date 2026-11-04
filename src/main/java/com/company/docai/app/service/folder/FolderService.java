package com.company.docai.app.service.folder;

import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.app.service.access.AccessChecker;
import com.company.docai.app.service.access.AccessLevels;
import com.company.docai.app.service.access.AccessService;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.access.AccessSet;
import com.company.docai.model.folder.Folder;
import io.jmix.core.DataManager;
import io.jmix.core.EntityStates;
import io.jmix.core.FetchPlan;
import io.jmix.core.Id;
import io.jmix.core.Messages;
import io.jmix.core.MetadataTools;
import io.jmix.core.SaveContext;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.entity.KeyValueEntity;
import jakarta.persistence.LockModeType;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Changes the folder tree: creates, renames, moves and deletes folders. The root stays as
 * the changelog created it, and names are unique among the live folders of one parent.
 * A change needs {@link AccessLevel#EDIT} on the folders it touches. The structure of the tree
 * is read past the access rules: locks, paths and taken names are the same for everyone.
 */
@Service
public class FolderService {

    private final DataManager dataManager;
    private final UnconstrainedDataManager unconstrainedDataManager;
    private final AccessChecker accessChecker;
    private final AccessService accessService;
    private final EntityStates entityStates;
    private final Messages messages;
    private final MetadataTools metadataTools;

    public FolderService(DataManager dataManager, UnconstrainedDataManager unconstrainedDataManager,
                         AccessChecker accessChecker, AccessService accessService, EntityStates entityStates,
                         Messages messages, MetadataTools metadataTools) {
        this.dataManager = dataManager;
        this.unconstrainedDataManager = unconstrainedDataManager;
        this.accessChecker = accessChecker;
        this.accessService = accessService;
        this.entityStates = entityStates;
        this.messages = messages;
        this.metadataTools = metadataTools;
    }

    /**
     * Returns the root folder, which the changelog creates with {@link Folder#ROOT_ID}. Every user gets it,
     * also one without access to it.
     */
    public Folder getRoot() {
        return unconstrainedDataManager.load(Folder.class)
                .id(Folder.ROOT_ID)
                .one();
    }

    /**
     * Creates a folder or renames one, as the folder dialog does. The parent of an existing
     * folder changes only through {@link #move(Folder, Folder)}. The parent row stays locked
     * until the transaction ends, so a delete of the parent waits for this save.
     *
     * @throws OperationRejectedException if the folder is the root, the parent of a new folder or the renamed
     *                                    folder is unavailable or only viewed by the user, or the name is taken
     */
    @Transactional
    public Folder save(Folder folder) {
        String name = folder.getName().strip();

        if (entityStates.isNew(folder)) {
            Folder parent = lockLive(folder.getParent());
            checkEditable(parent);
            checkNameFree(parent, name, null);
            folder.setParent(parent);
        } else {
            if (folder.isRoot()) {
                throw reject("rootFolderLocked");
            }

            checkEditable(folder);
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
     * Moves a folder with its subtree into another folder. A folder that inherits its access takes the access
     * of the target, together with the folders and documents below it that inherit from it.
     *
     * @throws OperationRejectedException if the folder is the root, the folder or the target is unavailable or only
     *                                    viewed by the user, the target is the folder itself or one of its
     *                                    subfolders, or the target already has a folder with the same name
     */
    @Transactional
    public Folder move(Folder folder, Folder target) {
        // moves wait for each other, otherwise two crossing moves pass the cycle check and build a loop
        unconstrainedDataManager.load(Folder.class)
                .id(Folder.ROOT_ID)
                .lockMode(LockModeType.PESSIMISTIC_WRITE)
                .one();

        if (folder.isRoot()) {
            throw reject("rootFolderLocked");
        }

        checkEditable(folder);
        Folder moved = loadWithParent(folder.getId());
        Folder destination = lockLive(target);
        checkEditable(destination);
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

        SaveContext saveContext = new SaveContext().saving(moved);
        accessService.followMove(moved, destination, saveContext);

        return dataManager.save(saveContext).get(moved);
    }

    /**
     * Soft-deletes the folder with all its subfolders, documents and their revisions. The folder
     * row is locked first, so a document or a folder being placed into it at the same time is
     * either deleted with it or rejected. The same holds for its subfolders only while the database
     * locks whole tables, as HSQLDB does by default. A folder deleted already is left as it is.
     *
     * @throws OperationRejectedException if the folder is the root, is unavailable or only viewed by the user,
     *                                    or holds something the user may not change
     */
    @Transactional
    public void delete(Folder folder) {
        if (folder.isRoot()) {
            throw reject("rootFolderLocked");
        }

        Optional<Folder> locked = unconstrainedDataManager.load(Folder.class)
                .id(folder.getId())
                .lockMode(LockModeType.PESSIMISTIC_WRITE)
                .optional();

        if (locked.isPresent()) {
            checkEditable(locked.get());
            checkContentEditable(locked.get(), subtree(locked.get()));
            dataManager.remove(locked.get());
        }
    }

    /**
     * Counts what deleting the folder takes with it, for the confirmation dialog.
     *
     * @throws OperationRejectedException if the folder is unavailable or only viewed by the user, or holds
     *                                    something the user may not change: the delete would be rejected
     */
    public FolderContents countContents(Folder folder) {
        checkEditable(folder);
        List<UUID> subtree = subtree(folder);
        checkContentEditable(folder, subtree);

        long documents = unconstrainedDataManager.loadValue(
                        "select count(d) from Document d where d.folder.id in :folders", Long.class)
                .parameter("folders", subtree)
                .one();

        return new FolderContents(subtree.size() - 1, documents);
    }

    /**
     * Returns the folders the tree shows to the current user: the folders they have access to and, greyed,
     * the folders on the way to them and to the documents they have access to. The names on the way are shown
     * to everyone who has access below them; that is how the user finds what was shared with them.
     */
    public FolderTree loadTree() {
        AccessLevels levels = accessService.levels();
        List<Folder> folders = unconstrainedDataManager.load(Folder.class)
                .query("select f from Folder f order by f.name")
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("parent", FetchPlan.INSTANCE_NAME)
                        .add("accessSet", FetchPlan.INSTANCE_NAME))
                .list();

        if (levels.full()) {
            return new FolderTree(folders, Set.of(), levels);
        }

        Set<UUID> visible = folders.stream()
                .filter(folder -> levels.of(folder.getAccessSet()) != null)
                .map(Folder::getId)
                .collect(Collectors.toSet());

        // a document with its own access may be the only thing the user sees in its folder
        List<UUID> reached = new ArrayList<>(visible);
        reached.add(Folder.ROOT_ID);
        dataManager.loadValues("select distinct d.folder.id from Document d")
                .properties("folderId")
                .list()
                .forEach(value -> reached.add(value.getValue("folderId")));

        FolderIndex index = FolderIndex.of(folders);

        Set<UUID> shown = new HashSet<>();
        for (UUID id : reached) {
            Folder current = index.get(id);
            while (current != null && shown.add(current.getId())) {
                current = index.parentOf(current);
            }
        }

        return new FolderTree(
                folders.stream()
                        .filter(folder -> shown.contains(folder.getId()))
                        .toList(),
                shown.stream()
                        .filter(id -> !visible.contains(id))
                        .collect(Collectors.toSet()),
                levels);
    }

    /**
     * Counts the documents of each folder together with its subfolders, for the folder tree. A document adds to
     * its folder and to the ancestors among the given folders only, so a folder the user does not see reveals
     * nothing through its parent. A folder without documents gets 0.
     */
    public Map<UUID, Long> countDocuments(Collection<Folder> folders) {
        FolderIndex index = FolderIndex.of(folders);

        Map<UUID, Long> counts = new HashMap<>();
        index.folders()
                .forEach(folder -> counts.put(folder.getId(), 0L));

        List<KeyValueEntity> ownCounts = dataManager.loadValues(
                        "select d.folder.id, count(d) from Document d group by d.folder.id")
                .properties("folderId", "documents")
                .list();

        for (KeyValueEntity ownCount : ownCounts) {
            long documents = ownCount.<Long>getValue("documents");
            for (Folder current = index.get(ownCount.getValue("folderId")); current != null;
                 current = index.parentOf(current)) {
                counts.merge(current.getId(), documents, Long::sum);
            }
        }

        return counts;
    }

    /**
     * Returns the folders from the root down to the given one, for breadcrumbs and paths. The folders on the way
     * are named also when the user has no access to them.
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
     * Checks that the current user may change the folder and place items into it.
     *
     * @throws OperationRejectedException if the folder is deleted or hidden from the user, or the user only views it
     */
    public void checkEditable(Folder folder) {
        Folder visible = dataManager.load(Id.of(folder))
                .optional()
                .orElseThrow(() -> reject("folderUnavailable", metadataTools.getInstanceName(folder)));

        if (!accessChecker.canEdit(visible)) {
            throw reject("folderReadOnly", metadataTools.getInstanceName(visible));
        }
    }

    /**
     * Locks the folder row until the transaction ends and returns the live folder, whether the user has access
     * to it or not. Anything placed into a folder takes this lock, so it waits for a delete of the same branch.
     * Call it inside a transaction: outside one, the lock ends together with the load.
     *
     * @throws OperationRejectedException if the folder is deleted
     */
    public Folder lockLive(Folder folder) {
        return unconstrainedDataManager.load(Folder.class)
                .id(folder.getId())
                .lockMode(LockModeType.PESSIMISTIC_WRITE)
                .optional()
                .orElseThrow(() -> reject("folderUnavailable", metadataTools.getInstanceName(folder)));
    }

    /**
     * Checks that the user may change everything a delete of the folder takes with it. The message names no item:
     * the user may not see them.
     */
    private void checkContentEditable(Folder folder, List<UUID> subtree) {
        AccessLevels levels = accessService.levels();
        boolean restricted = unconstrainedDataManager.load(AccessSet.class)
                .query("select s from AccessSet s"
                        + " where exists (select f from Folder f where f.accessSet = s and f.id in :folders)"
                        + " or exists (select d from Document d where d.accessSet = s and d.folder.id in :folders)")
                .parameter("folders", subtree)
                .list()
                .stream()
                .anyMatch(accessSet -> !levels.allows(accessSet, AccessLevel.EDIT));

        if (restricted) {
            throw reject("folderContentNotDeletable", metadataTools.getInstanceName(folder));
        }
    }

    /**
     * Returns the ids of the folder and of its live subfolders at any depth.
     */
    private List<UUID> subtree(Folder folder) {
        List<UUID> subtree = new ArrayList<>(List.of(folder.getId()));
        List<UUID> level = List.of(folder.getId());

        while (!level.isEmpty()) {
            level = unconstrainedDataManager.loadValues("select f.id from Folder f where f.parent.id in :parents")
                    .properties("id")
                    .parameter("parents", level)
                    .list().stream()
                    .map(value -> value.<UUID>getValue("id"))
                    .toList();
            subtree.addAll(level);
        }

        return subtree;
    }

    private Folder loadWithParent(UUID id) {
        return unconstrainedDataManager.load(Folder.class)
                .id(id)
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("parent", FetchPlan.BASE))
                .one();
    }

    private void checkNameFree(Folder parent, String name, @Nullable UUID ownId) {
        List<Folder> namesakes = unconstrainedDataManager.load(Folder.class)
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
