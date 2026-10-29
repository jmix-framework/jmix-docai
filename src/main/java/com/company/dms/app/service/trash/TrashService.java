package com.company.dms.app.service.trash;

import com.company.dms.app.service.OperationRejectedException;
import com.company.dms.app.service.access.AccessChecker;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.access.AccessSet;
import com.company.dms.model.document.Document;
import com.company.dms.model.document.DocumentRevision;
import com.company.dms.model.folder.Folder;
import com.company.dms.model.trash.TrashItem;
import com.company.dms.model.user.User;
import io.jmix.core.AccessManager;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.FileRef;
import io.jmix.core.FileStorageLocator;
import io.jmix.core.FluentValuesLoader;
import io.jmix.core.Messages;
import io.jmix.core.Metadata;
import io.jmix.core.MetadataTools;
import io.jmix.core.SaveContext;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.accesscontext.SpecificOperationAccessContext;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.data.PersistenceHints;
import io.jmix.datatools.EntityRestore;
import io.jmix.dynattr.model.CategoryAttributeValue;
import jakarta.persistence.LockModeType;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The trash: the folders and documents deleted softly. It lists what was deleted on its own, shows what comes back
 * together with a deleted folder, puts items back where they were and deletes them forever. What comes back with
 * an item is what the restore of Jmix takes: the rows deleted from 100 ms before to 1 s after their parent, at any
 * depth; the rest of a deleted folder lies in the trash on its own. Reading and restoring follow the access to
 * the items, deleting forever needs {@link #PURGE_POLICY}. Paths are read past the access rules, as the folder tree
 * reads them.
 */
@Service
public class TrashService {

    /**
     * The specific policy that lets a user delete items of the trash forever and empty it.
     */
    public static final String PURGE_POLICY = "dms.trash.purge";

    private static final Logger log = LoggerFactory.getLogger(TrashService.class);

    // the bounds within which EntityRestore takes a deleted row back together with its parent
    private static final Duration DELETED_BEFORE_PARENT = Duration.ofMillis(100);
    private static final Duration DELETED_AFTER_PARENT = Duration.ofSeconds(1);

    private static final String PATH_SEPARATOR = " / ";

    private final DataManager dataManager;
    private final UnconstrainedDataManager unconstrainedDataManager;
    private final FolderService folderService;
    private final AccessChecker accessChecker;
    private final AccessManager accessManager;
    private final EntityRestore entityRestore;
    private final SystemAuthenticator systemAuthenticator;
    private final FileStorageLocator fileStorageLocator;
    private final Metadata metadata;
    private final MetadataTools metadataTools;
    private final Messages messages;
    private final TransactionTemplate transaction;

    public TrashService(DataManager dataManager, UnconstrainedDataManager unconstrainedDataManager,
                        FolderService folderService, AccessChecker accessChecker, AccessManager accessManager,
                        EntityRestore entityRestore, SystemAuthenticator systemAuthenticator,
                        FileStorageLocator fileStorageLocator, Metadata metadata, MetadataTools metadataTools,
                        Messages messages, PlatformTransactionManager transactionManager) {
        this.dataManager = dataManager;
        this.unconstrainedDataManager = unconstrainedDataManager;
        this.folderService = folderService;
        this.accessChecker = accessChecker;
        this.accessManager = accessManager;
        this.entityRestore = entityRestore;
        this.systemAuthenticator = systemAuthenticator;
        this.fileStorageLocator = fileStorageLocator;
        this.metadata = metadata;
        this.metadataTools = metadataTools;
        this.messages = messages;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Returns the items of the trash the current user may read, the last deleted first: the folders and documents
     * deleted on their own. What was deleted together with a folder lies in its contents.
     */
    public List<TrashItem> loadItems() {
        Map<UUID, Folder> tree = loadTree();
        Set<UUID> readableFolders = readable("select f.id from Folder f where f.deletedDate is not null");
        Set<UUID> readableDocuments = readable("select d.id from Document d where d.deletedDate is not null");

        List<Folder> folders = tree.values().stream()
                .filter(folder -> folder.getDeletedDate() != null && readableFolders.contains(folder.getId()))
                .filter(folder -> !deletedTogether(folder.getDeletedDate(), parentOf(folder, tree)))
                .toList();

        List<Document> documents = loadDeletedDocuments("select d from Document d where d.deletedDate is not null",
                Map.of()).stream()
                .filter(document -> readableDocuments.contains(document.getId()))
                .filter(document -> !deletedTogether(document.getDeletedDate(), folderOf(document, tree)))
                .toList();

        Set<String> deleters = new HashSet<>();
        folders.forEach(folder -> deleters.add(folder.getDeletedBy()));
        documents.forEach(document -> deleters.add(document.getDeletedBy()));
        Map<String, String> userNames = userNames(deleters);

        List<TrashItem> items = new ArrayList<>();
        folders.forEach(folder -> items.add(item(folder, pathOf(parentOf(folder, tree), null, tree),
                userNames)));
        documents.forEach(document -> items.add(item(document, pathOf(folderOf(document, tree), null, tree),
                userNames)));

        items.sort(Comparator.comparing(TrashItem::getDeletedDate).reversed());

        return items;
    }

    /**
     * Returns what comes back together with the deleted folder and the current user may read: the folders and
     * documents deleted with it, folders first, each with the folders it lies in below the deleted one. Empty for
     * a folder that is not in the trash or that the user may not read.
     */
    public List<TrashItem> loadContents(Folder folder) {
        Map<UUID, Folder> tree = loadTree();
        Folder deleted = tree.get(folder.getId());
        Set<UUID> readableFolders = readable("select f.id from Folder f where f.deletedDate is not null");

        if (deleted == null || deleted.getDeletedDate() == null || !readableFolders.contains(deleted.getId())) {
            return List.of();
        }

        List<Folder> together = new ArrayList<>();
        List<Folder> level = List.of(deleted);

        while (!level.isEmpty()) {
            List<Folder> parents = level;
            level = tree.values().stream()
                    .filter(child -> child.getDeletedDate() != null)
                    .filter(child -> parents.contains(parentOf(child, tree)))
                    .filter(child -> deletedTogether(child.getDeletedDate(), parentOf(child, tree)))
                    .toList();
            together.addAll(level);
        }

        List<UUID> containers = new ArrayList<>(List.of(deleted.getId()));
        together.forEach(child -> containers.add(child.getId()));
        Set<UUID> readableDocuments = readable("select d.id from Document d where d.deletedDate is not null");

        List<TrashItem> folders = together.stream()
                .filter(child -> readableFolders.contains(child.getId()))
                .map(child -> item(child, pathOf(parentOf(child, tree), deleted, tree), Map.of()))
                .sorted(Comparator.comparing(TrashItem::getLocation).thenComparing(TrashItem::getName))
                .toList();

        List<TrashItem> documents = loadDeletedDocuments(
                "select d from Document d where d.folder.id in :folders and d.deletedDate is not null",
                Map.of("folders", containers)).stream()
                .filter(document -> readableDocuments.contains(document.getId()))
                .filter(document -> deletedTogether(document.getDeletedDate(), folderOf(document, tree)))
                .map(document -> item(document, pathOf(folderOf(document, tree), deleted, tree), Map.of()))
                .sorted(Comparator.comparing(TrashItem::getLocation).thenComparing(TrashItem::getName))
                .toList();

        List<TrashItem> contents = new ArrayList<>(folders);
        contents.addAll(documents);

        return contents;
    }

    /**
     * Puts the items back where they were deleted from, each with what was deleted together with it: the folders
     * top down first, then the documents, each checked right before it comes back. A document whose type was
     * deleted meanwhile comes back without a type and without the values of its fields. The items come back all
     * or none: the first one that cannot rejects the whole restore. An item no longer in the trash is skipped.
     *
     * @return how many of the items came back
     * @throws OperationRejectedException if the user may not change an item, the folder of an item lies in the
     *                                    trash and does not come back first, or holds a live item with its name
     */
    @Transactional
    public int restore(Collection<TrashItem> items) {
        List<Folder> folders = lockDeleted(Folder.class, idsOf(items, TrashItem::getFolder));
        List<Document> documents = lockDeleted(Document.class, idsOf(items, TrashItem::getDocument));
        Map<UUID, Folder> tree = loadTree();
        Set<UUID> restored = new HashSet<>();

        folders.sort(Comparator.comparingInt(folder -> depthOf(tree.get(folder.getId()), tree)));

        for (Folder folder : folders) {
            if (!accessChecker.canEdit(folder)) {
                throw new OperationRejectedException(messages.formatMessage(FolderService.class, "folderReadOnly",
                        folder.getName()));
            }

            // the root is never deleted, so a deleted folder has a parent
            Folder parent = liveFolder(Objects.requireNonNull(parentOf(tree.get(folder.getId()), tree)));
            List<UUID> namesakes = ids(unconstrainedDataManager.loadValues(
                            "select f.id from Folder f where f.parent = :parent and f.nameKey = :name")
                    .parameter("parent", parent)
                    .parameter("name", folder.getName()));
            checkNoNamesake(namesakes, restored, "folderNameTaken", parent, folder.getName());

            dropDeletedTypes(documentsBelow(folder, tree));
            restoreRow(folder);
            restored.add(folder.getId());
        }

        for (Document document : documents) {
            if (!accessChecker.canEdit(document)) {
                throw new OperationRejectedException(messages.formatMessage(DocumentService.class,
                        "documentReadOnly", document.getName()));
            }

            Folder parent = liveFolder(folderOf(withFolder(document), tree));
            List<UUID> namesakes = ids(unconstrainedDataManager.loadValues(
                            "select d.id from Document d where d.folder = :folder and d.nameKey = :name")
                    .parameter("folder", parent)
                    .parameter("name", document.getName()));
            checkNoNamesake(namesakes, restored, "documentNameTaken", parent, document.getName());

            dropDeletedTypes(List.of(document.getId()));
            restoreRow(document);
            restored.add(document.getId());
        }

        return restored.size();
    }

    /**
     * Tells whether the current user may delete items of the trash forever and empty it.
     */
    public boolean canPurge() {
        SpecificOperationAccessContext context = new SpecificOperationAccessContext(PURGE_POLICY);
        accessManager.applyRegisteredConstraints(context);

        return context.isPermitted();
    }

    /**
     * Deletes the items forever with everything under them, the items deleted on their own below a folder too:
     * their versions, the values of their fields and their own access sets. The files of the deleted versions leave
     * the storage after the commit unless a remaining version, live or in the trash, refers to them; a file that
     * cannot be removed is logged. An item no longer in the trash is left alone.
     *
     * @throws OperationRejectedException if the user has no {@link #PURGE_POLICY}
     */
    public void purge(Collection<TrashItem> items) {
        checkPurge();

        removeFiles(Objects.requireNonNull(transaction.execute(status ->
                purgeRows(idsOf(items, TrashItem::getFolder), idsOf(items, TrashItem::getDocument)))));
    }

    /**
     * Deletes everything in the trash forever, as {@link #purge(Collection)} does.
     *
     * @throws OperationRejectedException if the user has no {@link #PURGE_POLICY}
     */
    public void empty() {
        checkPurge();

        removeFiles(Objects.requireNonNull(transaction.execute(status -> purgeRows(
                ids(unconstrainedDataManager.loadValues("select f.id from Folder f where f.deletedDate is not null")),
                ids(unconstrainedDataManager.loadValues(
                        "select d.id from Document d where d.deletedDate is not null"))))));
    }

    private List<FileRef> purgeRows(Collection<UUID> folderIds, Collection<UUID> documentIds) {
        // the rows stay locked, so a restore of the same items waits, and are checked again: what came back stays
        List<Folder> roots = lockDeleted(Folder.class, folderIds);
        Map<UUID, Folder> tree = loadTree();

        // a deleted folder goes as a whole: what lies below it was deleted with it or before it
        List<List<UUID>> levels = new ArrayList<>();
        List<UUID> level = roots.stream().map(Folder::getId).toList();

        while (!level.isEmpty()) {
            levels.add(level);
            List<UUID> parents = level;
            level = tree.values().stream()
                    .filter(child -> child.getDeletedDate() != null && child.getParent() != null
                            && parents.contains(child.getParent().getId()))
                    .map(Folder::getId)
                    .toList();
        }

        List<UUID> folders = levels.stream().flatMap(List::stream).toList();
        checkNothingLiveBelow(roots, folders, tree);
        Set<UUID> documents = new LinkedHashSet<>(documentsIn(folders));
        lockDeleted(Document.class, documentIds).forEach(document -> documents.add(document.getId()));

        List<DocumentRevision> revisions = loadWithDeleted(DocumentRevision.class, "e.document.id in :ids", documents);
        List<FileRef> files = revisions.stream().map(DocumentRevision::getFile).distinct().toList();
        List<AccessSet> ownSets = loadWithDeleted(AccessSet.class,
                "e.ownerFolder.id in :ids or e.ownerDocument.id in :ids", union(folders, documents));

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
        deleteForever(DocumentRevision.class, "e.document.id in :ids", documents);
        deleteForever(Document.class, "e.id in :ids and e.deletedDate is not null", documents);

        for (List<UUID> folderLevel : levels.reversed()) {
            deleteForever(Folder.class, "e.id in :ids and e.deletedDate is not null", folderLevel);
        }

        // the database deletes the entries with the set
        deleteForever(AccessSet.class, "e.id in :ids", ownSets.stream().map(AccessSet::getId).toList());

        return files;
    }

    // something live below a deleted folder, left by a race of a delete or by a folder restored alone in Data Tools,
    // keeps the folder: deleting it forever would take the live item along or fail on it
    private void checkNothingLiveBelow(List<Folder> roots, List<UUID> folders, Map<UUID, Folder> tree) {
        Set<UUID> purged = new HashSet<>(folders);
        List<UUID> holders = new ArrayList<>(tree.values().stream()
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

        Set<UUID> rootIds = roots.stream().map(Folder::getId).collect(Collectors.toSet());
        Folder root = tree.get(holders.getFirst());

        while (!rootIds.contains(root.getId())) {
            root = Objects.requireNonNull(parentOf(root, tree));
        }

        throw reject("purgeLiveContent", metadataTools.getInstanceName(root));
    }

    // file storage is not transactional: a file goes after its rows, and only when no version refers to it any more
    private void removeFiles(List<FileRef> files) {
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
                fileStorageLocator.getByName(file.getStorageName()).removeFile(file);
            } catch (RuntimeException e) {
                log.warn("Cannot remove the file {} of a version deleted forever", file, e);
            }
        }
    }

    private void checkPurge() {
        if (!canPurge()) {
            throw reject("purgeNotPermitted");
        }
    }

    // the folder an item goes back into is live, and its row stays locked, as for anything placed into a folder
    private Folder liveFolder(Folder folder) {
        if (folder.getDeletedDate() != null) {
            Folder stored = unconstrainedDataManager.load(Folder.class)
                    .id(folder.getId())
                    .hint(PersistenceHints.SOFT_DELETION, false)
                    .one();

            if (stored.getDeletedDate() != null) {
                throw reject("restoreFolderFirst", metadataTools.getInstanceName(stored));
            }
        }

        return folderService.lockLive(folder);
    }

    private void checkNoNamesake(List<UUID> namesakes, Set<UUID> restored, String takenKey, Folder folder,
                                 String name) {
        if (namesakes.isEmpty()) {
            return;
        }

        String folderName = metadataTools.getInstanceName(folder);

        throw namesakes.stream().anyMatch(restored::contains)
                ? reject("namesakesSelected", name, folderName)
                : reject(takenKey, folderName, name);
    }

    // a type deleted while the document lay in the trash is gone, and the document comes back without it
    private void dropDeletedTypes(Collection<UUID> documentIds) {
        if (documentIds.isEmpty()) {
            return;
        }

        List<Document> typeless = unconstrainedDataManager.load(Document.class)
                .query("select d from Document d where d.id in :ids and d.category.deleteTs is not null")
                .parameter("ids", documentIds)
                .hint(PersistenceHints.SOFT_DELETION, false)
                .list();

        if (typeless.isEmpty()) {
            return;
        }

        typeless.forEach(document -> document.setCategory(null));
        unconstrainedDataManager.save(new SaveContext()
                .saving(typeless)
                .setHint(PersistenceHints.SOFT_DELETION, false)
                .setDiscardSaved(true));

        // the values go as the add-on removes the values of a type a document leaves
        unconstrainedDataManager.remove(unconstrainedDataManager.load(CategoryAttributeValue.class)
                .query("e.entity.entityId in :ids")
                .parameter("ids", typeless.stream().map(Document::getId).toList())
                .list());
    }

    // the restore of Jmix saves through the secure data manager, and an employee may not change versions, so
    // the rights are checked above and the rows come back as the system
    private void restoreRow(Object row) {
        systemAuthenticator.runWithSystem(() -> entityRestore.restoreEntities(List.of(row)));
    }

    private List<UUID> documentsBelow(Folder folder, Map<UUID, Folder> tree) {
        List<UUID> folders = new ArrayList<>(List.of(folder.getId()));
        List<UUID> level = folders;

        while (!level.isEmpty()) {
            List<UUID> parents = level;
            level = tree.values().stream()
                    .filter(child -> child.getParent() != null && parents.contains(child.getParent().getId()))
                    .map(Folder::getId)
                    .toList();
            folders.addAll(level);
        }

        return documentsIn(folders);
    }

    private List<UUID> documentsIn(Collection<UUID> folders) {
        if (folders.isEmpty()) {
            return List.of();
        }

        return ids(unconstrainedDataManager.loadValues(
                        "select d.id from Document d where d.folder.id in :folders and d.deletedDate is not null")
                .parameter("folders", folders));
    }

    private <E> List<E> lockDeleted(Class<E> type, Collection<UUID> ids) {
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

    // the whole tree, the deleted folders too: a path names the folders on the way also to those who may not open them
    private Map<UUID, Folder> loadTree() {
        return unconstrainedDataManager.load(Folder.class)
                .query("select f from Folder f")
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("parent", FetchPlan.INSTANCE_NAME))
                .hint(PersistenceHints.SOFT_DELETION, false)
                .list()
                .stream()
                .collect(Collectors.toMap(Folder::getId, Function.identity()));
    }

    private List<Document> loadDeletedDocuments(String query, Map<String, Object> parameters) {
        return unconstrainedDataManager.load(Document.class)
                .query(query)
                .parameters(parameters)
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("folder", FetchPlan.INSTANCE_NAME)
                        .add("currentRevision", FetchPlan.BASE))
                .hint(PersistenceHints.SOFT_DELETION, false)
                .list();
    }

    private Document withFolder(Document document) {
        return unconstrainedDataManager.load(Document.class)
                .id(document.getId())
                .fetchPlan(fetchPlan -> fetchPlan.add("folder", FetchPlan.INSTANCE_NAME))
                .hint(PersistenceHints.SOFT_DELETION, false)
                .one();
    }

    // the ids the access rules leave to the current user
    private Set<UUID> readable(String query) {
        return new HashSet<>(ids(dataManager.loadValues(query)));
    }

    private static List<UUID> ids(FluentValuesLoader loader) {
        return loader.properties("id")
                .hint(PersistenceHints.SOFT_DELETION, false)
                .list()
                .stream()
                .map(value -> value.<UUID>getValue("id"))
                .toList();
    }

    private TrashItem item(Folder folder, String location, Map<String, String> userNames) {
        TrashItem item = item(folder.getId(), metadataTools.getInstanceName(folder), location,
                folder.getDeletedBy(), folder.getDeletedDate(), userNames);
        item.setFolder(folder);

        return item;
    }

    private TrashItem item(Document document, String location, Map<String, String> userNames) {
        TrashItem item = item(document.getId(), document.getName(), location, document.getDeletedBy(),
                document.getDeletedDate(), userNames);
        item.setDocument(document);

        return item;
    }

    private TrashItem item(UUID id, String name, String location, @Nullable String deletedBy,
                           OffsetDateTime deletedDate, Map<String, String> userNames) {
        TrashItem item = metadata.create(TrashItem.class);
        item.setId(id);
        item.setName(name);
        item.setLocation(location);
        item.setDeletedBy(deletedBy == null ? null : userNames.getOrDefault(deletedBy, deletedBy));
        item.setDeletedDate(deletedDate);

        return item;
    }

    // the names of the users as the application shows them; a user deleted since keeps the username
    private Map<String, String> userNames(Set<String> usernames) {
        usernames.remove(null);

        if (usernames.isEmpty()) {
            return Map.of();
        }

        return dataManager.load(User.class)
                .query("select u from User u where u.username in :usernames")
                .parameter("usernames", usernames)
                .fetchPlan(FetchPlan.INSTANCE_NAME)
                .list()
                .stream()
                .collect(Collectors.toMap(User::getUsername, metadataTools::getInstanceName));
    }

    // the folders from the top, exclusive, or from the root down to the folder, inclusive
    private String pathOf(@Nullable Folder folder, @Nullable Folder top, Map<UUID, Folder> tree) {
        LinkedList<String> names = new LinkedList<>();

        for (Folder current = folder; current != null && !current.equals(top); current = parentOf(current, tree)) {
            names.addFirst(metadataTools.getInstanceName(current));
        }

        return String.join(PATH_SEPARATOR, names);
    }

    private static int depthOf(Folder folder, Map<UUID, Folder> tree) {
        int depth = 0;

        for (Folder current = parentOf(folder, tree); current != null; current = parentOf(current, tree)) {
            depth++;
        }

        return depth;
    }

    private static @Nullable Folder parentOf(Folder folder, Map<UUID, Folder> tree) {
        return folder.getParent() == null ? null : tree.get(folder.getParent().getId());
    }

    private static Folder folderOf(Document document, Map<UUID, Folder> tree) {
        return tree.get(document.getFolder().getId());
    }

    // EntityRestore takes a row back with its parent when the row was deleted within these bounds of the parent
    private static boolean deletedTogether(OffsetDateTime deleted, @Nullable Folder parent) {
        if (parent == null || parent.getDeletedDate() == null) {
            return false;
        }

        return !deleted.isBefore(parent.getDeletedDate().minus(DELETED_BEFORE_PARENT))
                && !deleted.isAfter(parent.getDeletedDate().plus(DELETED_AFTER_PARENT));
    }

    private static List<UUID> idsOf(Collection<TrashItem> items, Function<TrashItem, @Nullable Object> kind) {
        return items.stream()
                .filter(item -> kind.apply(item) != null)
                .map(TrashItem::getId)
                .toList();
    }

    private static List<UUID> union(Collection<UUID> first, Collection<UUID> second) {
        List<UUID> union = new ArrayList<>(first);
        union.addAll(second);

        return union;
    }

    private OperationRejectedException reject(String key, Object... args) {
        return new OperationRejectedException(messages.formatMessage(getClass(), key, args));
    }
}
