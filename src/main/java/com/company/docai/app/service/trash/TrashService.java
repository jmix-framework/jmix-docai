package com.company.docai.app.service.trash;

import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.app.service.access.AccessChecker;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderIndex;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.app.service.user.UserService;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.model.trash.TrashItem;
import io.jmix.core.AccessManager;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
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
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

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
    public static final String PURGE_POLICY = "docai.trash.purge";

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
    private final TrashRows trashRows;
    private final TrashPurge trashPurge;
    private final Metadata metadata;
    private final MetadataTools metadataTools;
    private final Messages messages;
    private final UserService userService;

    public TrashService(DataManager dataManager, UnconstrainedDataManager unconstrainedDataManager,
                        FolderService folderService, AccessChecker accessChecker, AccessManager accessManager,
                        EntityRestore entityRestore, SystemAuthenticator systemAuthenticator,
                        TrashRows trashRows, TrashPurge trashPurge, Metadata metadata, MetadataTools metadataTools,
                        Messages messages, UserService userService) {
        this.dataManager = dataManager;
        this.unconstrainedDataManager = unconstrainedDataManager;
        this.folderService = folderService;
        this.accessChecker = accessChecker;
        this.accessManager = accessManager;
        this.entityRestore = entityRestore;
        this.systemAuthenticator = systemAuthenticator;
        this.trashRows = trashRows;
        this.trashPurge = trashPurge;
        this.metadata = metadata;
        this.metadataTools = metadataTools;
        this.messages = messages;
        this.userService = userService;
    }

    /**
     * Returns the items of the trash the current user may read, the last deleted first: the folders and documents
     * deleted on their own. What was deleted together with a folder lies in its contents.
     */
    public List<TrashItem> loadItems() {
        FolderIndex tree = trashRows.loadTree();
        Set<UUID> readableFolders = readable("select f.id from Folder f where f.deletedDate is not null");
        Set<UUID> readableDocuments = readable("select d.id from Document d where d.deletedDate is not null");

        List<Folder> folders = tree.folders()
                .stream()
                .filter(folder -> folder.getDeletedDate() != null && readableFolders.contains(folder.getId()))
                .filter(folder -> !deletedTogether(folder.getDeletedDate(), tree.parentOf(folder)))
                .toList();

        List<Document> documents = loadDeletedDocuments("select d from Document d where d.deletedDate is not null",
                Map.of()).stream()
                .filter(document -> readableDocuments.contains(document.getId()))
                .filter(document -> !deletedTogether(document.getDeletedDate(), folderOf(document, tree)))
                .toList();

        Map<String, String> userNames = userService.namesOf(Stream.concat(
                        folders.stream().map(Folder::getDeletedBy),
                        documents.stream().map(Document::getDeletedBy))
                .toList());

        List<TrashItem> items = new ArrayList<>();

        folders.forEach(folder -> items.add(item(folder, pathOf(tree.parentOf(folder), null, tree),
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
        FolderIndex tree = trashRows.loadTree();
        Folder deleted = tree.get(folder.getId());
        Set<UUID> readableFolders = readable("select f.id from Folder f where f.deletedDate is not null");

        if (deleted == null || deleted.getDeletedDate() == null || !readableFolders.contains(deleted.getId())) {
            return List.of();
        }

        List<Folder> together = new ArrayList<>();
        List<Folder> level = List.of(deleted);

        while (!level.isEmpty()) {
            List<Folder> parents = level;

            level = tree.folders()
                    .stream()
                    .filter(child -> child.getDeletedDate() != null)
                    .filter(child -> parents.contains(tree.parentOf(child)))
                    .filter(child -> deletedTogether(child.getDeletedDate(), tree.parentOf(child)))
                    .toList();

            together.addAll(level);
        }

        List<UUID> containers = new ArrayList<>(List.of(deleted.getId()));
        together.forEach(child -> containers.add(child.getId()));
        Set<UUID> readableDocuments = readable("select d.id from Document d where d.deletedDate is not null");

        List<TrashItem> folders = together.stream()
                .filter(child -> readableFolders.contains(child.getId()))
                .map(child -> item(child, pathOf(tree.parentOf(child), deleted, tree), Map.of()))
                .sorted(Comparator.comparing(TrashItem::getLocation)
                        .thenComparing(TrashItem::getName))
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
        List<Folder> folders = trashRows.lockDeleted(Folder.class, idsOf(items, TrashItem::getFolder));
        List<Document> documents = trashRows.lockDeleted(Document.class, idsOf(items, TrashItem::getDocument));
        FolderIndex tree = trashRows.loadTree();
        Set<UUID> restored = new HashSet<>();

        folders.sort(Comparator.comparingInt(tree::depthOf));

        for (Folder folder : folders) {
            if (!accessChecker.canEdit(folder)) {
                throw new OperationRejectedException(messages.formatMessage(FolderService.class, "folderReadOnly",
                        folder.getName()));
            }

            // the root is never deleted, so a deleted folder has a parent
            Folder parent = liveFolder(Objects.requireNonNull(tree.parentOf(folder)));

            List<UUID> namesakes = TrashRows.ids(unconstrainedDataManager.loadValues(
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

            List<UUID> namesakes = TrashRows.ids(unconstrainedDataManager.loadValues(
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

        trashPurge.purge(idsOf(items, TrashItem::getFolder), idsOf(items, TrashItem::getDocument));
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

    private List<UUID> documentsBelow(Folder folder, FolderIndex tree) {
        List<UUID> folders = new ArrayList<>(List.of(folder.getId()));
        List<UUID> level = folders;

        while (!level.isEmpty()) {
            List<UUID> parents = level;

            level = tree.folders()
                    .stream()
                    .filter(child -> child.getParent() != null && parents.contains(child.getParent().getId()))
                    .map(Folder::getId)
                    .toList();

            folders.addAll(level);
        }

        return trashRows.documentsIn(folders);
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
        return new HashSet<>(TrashRows.ids(dataManager.loadValues(query)));
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

    // the folders from the top, exclusive, or from the root down to the folder, inclusive
    private String pathOf(@Nullable Folder folder, @Nullable Folder top, FolderIndex tree) {
        LinkedList<String> names = new LinkedList<>();

        for (Folder current = folder; current != null && !current.equals(top); current = tree.parentOf(current)) {
            names.addFirst(metadataTools.getInstanceName(current));
        }

        return String.join(PATH_SEPARATOR, names);
    }

    private static Folder folderOf(Document document, FolderIndex tree) {
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

    private OperationRejectedException reject(String key, Object... args) {
        return new OperationRejectedException(messages.formatMessage(getClass(), key, args));
    }
}
