package com.company.docai.demo;

import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.app.service.access.AccessService;
import com.company.docai.app.service.document.DocumentCommentService;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.document.DocumentStatusService;
import com.company.docai.app.service.document.DocumentTypes;
import com.company.docai.app.service.document.UploadedFile;
import com.company.docai.app.service.favorite.FavoriteService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.app.service.tag.TagService;
import com.company.docai.model.access.AccessDraft;
import com.company.docai.model.access.AccessEntryDraft;
import com.company.docai.model.document.Document;
import com.company.docai.model.document.DocumentComment;
import com.company.docai.model.document.DocumentRevision;
import com.company.docai.model.document.DocumentStatus;
import com.company.docai.model.folder.Folder;
import com.company.docai.model.user.User;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Metadata;
import io.jmix.core.SaveContext;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.data.PersistenceHints;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Loads a demo set into an empty database when the application starts: the statuses of the set, the folders under
 * the root with their own access, then the documents with their versions, types, fields, tags, access and statuses,
 * and last what lies in the trash.
 * The set is chosen by {@code docai.demo-data} ({@code en}, {@code ru} or {@code none}); its description is
 * {@code com/company/docai/demo/<set>/demo-data.json}, the files of its versions lie in {@code files} next to it. A set
 * loads in one transaction, all of it or nothing.
 */
@Component
public class DemoDataLoader {

    /**
     * What the start of the application did with the chosen set.
     */
    public enum Outcome {

        /**
         * No set is chosen.
         */
        OFF,

        /**
         * The database holds folders or documents already, so nothing is written.
         */
        NOT_EMPTY,

        /**
         * The set does not exist or its description has problems, so nothing is written.
         */
        INVALID,

        /**
         * The set is loaded.
         */
        LOADED
    }

    /**
     * The value of {@code docai.demo-data} that loads no set.
     */
    public static final String NONE = "none";

    private static final Logger log = LoggerFactory.getLogger(DemoDataLoader.class);

    // folders, access and statuses are created by the administrator, who manages every folder
    private static final String ADMIN = "admin";

    private final String chosenSet;
    private final DataManager dataManager;
    private final UnconstrainedDataManager unconstrainedDataManager;
    private final FolderService folderService;
    private final DocumentService documentService;
    private final DocumentStatusService statusService;
    private final DocumentCommentService commentService;
    private final AccessService accessService;
    private final DocumentTypes documentTypes;
    private final TagService tagService;
    private final FavoriteService favoriteService;
    private final DemoSetChecker checker;
    private final SystemAuthenticator systemAuthenticator;
    private final Metadata metadata;
    private final TransactionTemplate transaction;

    // the dates of the history are written past the entity listeners, which stamp the current time
    @PersistenceContext
    private EntityManager entityManager;

    public DemoDataLoader(@Value("${docai.demo-data:none}") String chosenSet,
                          DataManager dataManager,
                          UnconstrainedDataManager unconstrainedDataManager,
                          FolderService folderService,
                          DocumentService documentService,
                          DocumentStatusService statusService,
                          DocumentCommentService commentService,
                          AccessService accessService,
                          DocumentTypes documentTypes,
                          TagService tagService,
                          FavoriteService favoriteService,
                          DemoSetChecker checker,
                          SystemAuthenticator systemAuthenticator,
                          Metadata metadata,
                          PlatformTransactionManager transactionManager) {
        this.chosenSet = chosenSet;
        this.dataManager = dataManager;
        this.unconstrainedDataManager = unconstrainedDataManager;
        this.folderService = folderService;
        this.documentService = documentService;
        this.statusService = statusService;
        this.commentService = commentService;
        this.accessService = accessService;
        this.documentTypes = documentTypes;
        this.tagService = tagService;
        this.favoriteService = favoriteService;
        this.checker = checker;
        this.systemAuthenticator = systemAuthenticator;
        this.metadata = metadata;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            systemAuthenticator.withSystem(() -> loadIfEmpty(chosenSet));
        } catch (RuntimeException e) {
            log.error("The demo set '{}' did not load; the database stays empty and the next start tries again",
                    chosenSet, e);
        }
    }

    /**
     * Loads the set when the database holds no folder but the root and no document, deleted ones included.
     */
    public Outcome loadIfEmpty(String set) {
        if (NONE.equals(set)) {
            return Outcome.OFF;
        }

        if (!new ClassPathResource(DemoSet.LOCATION.formatted(set) + DemoSet.DESCRIPTION).exists()) {
            log.error("There is no demo set '{}': {} is missing", set,
                    DemoSet.LOCATION.formatted(set) + DemoSet.DESCRIPTION);

            return Outcome.INVALID;
        }

        if (!checker.isEmpty()) {
            log.info("The demo set '{}' is not loaded: the database holds folders or documents", set);
            return Outcome.NOT_EMPTY;
        }

        List<String> problems = check(set);

        if (!problems.isEmpty()) {
            log.error("The demo set '{}' is not loaded, its description has problems:\n{}", set,
                    String.join("\n", problems));

            return Outcome.INVALID;
        }

        load(set);

        return Outcome.LOADED;
    }

    /**
     * Returns the problems of the description of the set without writing anything: a folder or a document without
     * a name or with the name of a neighbour, a document without versions, a missing file, an unknown user, role,
     * level, status, type or field, a field value its field does not accept, and a tag without a name, too long or
     * written in another case elsewhere, and a favorite of an unknown user or of an item in the trash.
     */
    public List<String> check(String set) {
        // the type rules read the names of types and fields in the language of the current user
        return systemAuthenticator.withSystem(() -> checker.problemsOf(set));
    }

    /**
     * Loads the set into the database in one transaction, without looking at what the database holds. The set must
     * pass {@link #check}.
     */
    public void load(String set) {
        DemoSet description;

        try {
            description = DemoSet.read(set);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        log.info("The demo set '{}' is loading", set);
        long started = System.nanoTime();
        Loaded loaded = new Loaded();

        transaction.executeWithoutResult(status -> systemAuthenticator.runWithUser(ADMIN, () -> {
            nameUsers(description.users());
            nameStatuses(description.statuses(), loaded);

            Folder root = folderService.getRoot();

            for (DemoSet.Folder folder : description.folders()) {
                createFolder(set, folder, root, loaded);
            }

            markAsDescribed(loaded.marked);
            deleteAsDescribed(loaded.deleted);
        }));

        log.info("The demo set '{}' is loaded: {} folders, {} documents, {} versions, {} items in the trash in {} ms",
                set, loaded.folders, loaded.documents, loaded.versions, loaded.deleted.size(),
                (System.nanoTime() - started) / 1_000_000);
    }

    // the changelogs create the users with English names, and the set names them in its language
    private void nameUsers(List<DemoSet.User> described) {
        for (DemoSet.User names : described) {
            User user = unconstrainedDataManager.load(User.class)
                    .query("e.username = :login")
                    .parameter("login", names.login())
                    .one();

            user.setFirstName(names.firstName());
            user.setLastName(names.lastName());
            user.setEmail(names.email());
            unconstrainedDataManager.saveWithoutReload(user);
        }
    }

    // the set names the default statuses in its language and adds its own before any document takes one; the targets
    // name keys, so they wait until every status of the set exists
    private void nameStatuses(List<DemoSet.Status> described, Loaded loaded) {
        for (DemoSet.Status entry : described) {
            DocumentStatus status = statusOf(entry);

            if (entry.name() != null) {
                status.setName(entry.name());
            }

            if (entry.color() != null) {
                status.setColor(entry.color());
            }

            if (entry.position() != null) {
                status.setPosition(entry.position());
            }

            if (entry.initial() != null) {
                status.setInitial(entry.initial());
            }

            if (entry.locked() != null) {
                status.setLocked(entry.locked());
            }

            if (entry.anyTarget() != null) {
                status.setAnyTarget(entry.anyTarget());
            }

            loaded.statuses.put(entry.key(), statusService.save(status));
        }

        for (DemoSet.Status entry : described) {
            if (entry.targets() != null) {
                DocumentStatus status = loadStatus(loaded.statuses.get(entry.key()).getId());
                status.getTargets().clear();
                entry.targets().forEach(key -> status.getTargets().add(statusFor(key, loaded)));
                loaded.statuses.put(entry.key(), statusService.save(status));
            }
        }
    }

    private DocumentStatus statusOf(DemoSet.Status entry) {
        UUID id = DemoSet.DEFAULT_STATUSES.get(entry.key());

        if (id != null) {
            return loadStatus(id);
        }

        return statusService.find(Objects.requireNonNull(entry.name()))
                .map(found -> loadStatus(found.getId()))
                .orElseGet(() -> {
                    DocumentStatus status = dataManager.create(DocumentStatus.class);
                    status.setPosition(statusService.nextPosition());
                    return status;
                });
    }

    private DocumentStatus statusFor(String key, Loaded loaded) {
        DocumentStatus status = loaded.statuses.get(key);

        return status != null ? status : loadStatus(DemoSet.DEFAULT_STATUSES.get(key));
    }

    private DocumentStatus loadStatus(UUID id) {
        return dataManager.load(DocumentStatus.class)
                .id(id)
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("targets", FetchPlan.BASE))
                .one();
    }

    private void createFolder(String set, DemoSet.Folder description, Folder parent, Loaded loaded) {
        Folder draft = dataManager.create(Folder.class);
        draft.setName(description.name());
        draft.setParent(parent);
        Folder folder = folderService.save(draft);
        loaded.folders++;
        loaded.marked.add(new Marked(folder, description.favoriteOf()));

        if (description.access() != null) {
            giveOwnAccess(accessService.describe(folder), description.access());
        }

        for (DemoSet.Folder subfolder : description.folders()) {
            createFolder(set, subfolder, folder, loaded);
        }

        for (DemoSet.Document document : description.documents()) {
            createDocument(set, document, folder, loaded);
        }

        if (description.deleted() != null) {
            loaded.deleted.add(new Deleted(folder, description.deleted()));
        }
    }

    private void createDocument(String set, DemoSet.Document description, Folder folder, Loaded loaded) {
        String statusKey = description.status() == null ? "DRAFT" : description.status();

        Document draft = dataManager.create(Document.class);
        draft.setFolder(folder);
        draft.setName(description.name());
        draft.setDocumentDate(description.date());
        // the document service saves a new tag with the document and finds the one an earlier document brought
        draft.setTags(new ArrayList<>(description.tags().stream()
                .map(tagService::findOrCreate)
                .toList()));

        if (description.type() != null) {
            documentTypes.find(description.type())
                    .ifPresent(type -> documentTypes.fill(draft, type, DemoSet.fieldValuesOf(description), null));
        }

        List<DemoSet.Version> versions = description.versions();
        DemoSet.Version first = versions.getFirst();

        // every version is uploaded by its author, with the rights the author has
        Document document = systemAuthenticator.withUser(first.author(), () -> upload(set, first, description,
                file -> documentService.create(draft, file, null)));

        for (DemoSet.Version version : versions.subList(1, versions.size())) {
            Document current = document;
            document = systemAuthenticator.withUser(version.author(), () -> upload(set, version, description,
                    file -> documentService.uploadRevision(current, file)));
        }

        if (description.access() != null) {
            giveOwnAccess(accessService.describe(document), description.access());
        }

        moveTo(document, statusFor(statusKey, loaded));

        backdate(document, versions);
        writeComments(document, description.comments());
        loaded.documents++;
        loaded.marked.add(new Marked(document, description.favoriteOf()));
        loaded.versions += versions.size();

        if (description.deleted() != null) {
            loaded.deleted.add(new Deleted(document, description.deleted()));
        }
    }

    // a document starts in the initial status and walks the shortest way of transitions to its own, after its
    // versions and access, because a locked status takes no other change
    private void moveTo(Document document, DocumentStatus target) {
        List<DocumentStatus> all = statusService.loadAll();
        DocumentStatus initial = all.stream()
                .filter(status -> Boolean.TRUE.equals(status.getInitial()))
                .findFirst()
                .orElseThrow();

        for (DocumentStatus step : shortestWay(all, initial, target)) {
            documentService.changeStatus(List.of(document), step);
        }
    }

    private static List<DocumentStatus> shortestWay(List<DocumentStatus> all, DocumentStatus from, DocumentStatus to) {
        Map<DocumentStatus, DocumentStatus> previous = new HashMap<>();
        previous.put(from, from);
        Deque<DocumentStatus> queue = new ArrayDeque<>(List.of(from));

        while (!queue.isEmpty() && !previous.containsKey(to)) {
            DocumentStatus current = queue.poll();

            for (DocumentStatus next : all) {
                if (!previous.containsKey(next) && current.canMoveTo(next)) {
                    previous.put(next, current);
                    queue.add(next);
                }
            }
        }

        LinkedList<DocumentStatus> steps = new LinkedList<>();

        for (DocumentStatus step = to; !step.equals(from); step = previous.get(step)) {
            steps.addFirst(step);
        }

        return steps;
    }

    private Document upload(String set, DemoSet.Version version, DemoSet.Document description,
                            Function<UploadedFile, Document> save) {
        String extension = version.file().substring(version.file().lastIndexOf('.'));
        // the name goes into the address of the preview and the download, and the server rejects a slash there
        String name = description.name().replace('/', '_');
        String fileName = name.endsWith(extension) ? name : name + extension;

        try {
            Path content = Files.createTempFile("docai-demo-", extension);

            try {
                try (InputStream source = DemoSet.file(set, version.file()).getInputStream()) {
                    Files.copy(source, content, StandardCopyOption.REPLACE_EXISTING);
                }

                return save.apply(new UploadedFile(fileName, content));
            } finally {
                Files.deleteIfExists(content);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void giveOwnAccess(AccessDraft draft, List<DemoSet.Access> entries) {
        draft.setInherit(false);

        draft.setEntries(entries.stream()
                .map(entry -> {
                    AccessEntryDraft entryDraft = metadata.create(AccessEntryDraft.class);
                    entryDraft.setUsername(entry.user());
                    entryDraft.setRoleCode(entry.role());
                    entryDraft.setSubjectName(entry.user() != null ? entry.user() : entry.role());
                    entryDraft.setAccessLevel(DemoSet.levelOf(entry.level()));
                    return entryDraft;
                })
                .toList());

        accessService.save(draft);
    }

    // the history of the set: when each version was uploaded, and who changed the document last
    private void backdate(Document document, List<DemoSet.Version> versions) {
        List<DocumentRevision> revisions = unconstrainedDataManager.load(DocumentRevision.class)
                .query("select r from DocumentRevision r where r.document = :document order by r.number")
                .parameter("document", document)
                .list();

        for (int number = 0; number < revisions.size(); number++) {
            entityManager.createQuery("update DocumentRevision r set r.createdDate = :uploaded where r.id = :id")
                    .setParameter("uploaded", at(versions.get(number).uploaded()))
                    .setParameter("id", revisions.get(number).getId())
                    .executeUpdate();
        }

        DemoSet.Version last = versions.getLast();

        entityManager.createQuery("update Document d set d.createdDate = :created, d.changedDate = :changed,"
                        + " d.changedBy = :author, d.lastModifiedDate = :changed, d.lastModifiedBy = :author"
                        + " where d.id = :id")
                .setParameter("created", at(versions.getFirst().uploaded()))
                .setParameter("changed", at(last.uploaded()))
                .setParameter("author", last.author())
                .setParameter("id", document.getId())
                .executeUpdate();
    }

    // every comment is written by its author, with the rights the author has, and dated as described
    private void writeComments(Document document, List<DemoSet.Comment> comments) {
        for (DemoSet.Comment description : comments) {
            DocumentComment comment = systemAuthenticator.withUser(description.author(),
                    () -> commentService.post(document, description.text()));

            entityManager.createQuery("update DocumentComment c set c.createdDate = :written where c.id = :id")
                    .setParameter("written", at(description.at()))
                    .setParameter("id", comment.getId())
                    .executeUpdate();

            if (description.edited() != null) {
                entityManager.createQuery("update DocumentComment c set c.editedDate = :edited where c.id = :id")
                        .setParameter("edited", at(description.edited()))
                        .setParameter("id", comment.getId())
                        .executeUpdate();
            }
        }
    }

    // each mark is set by its owner with the owner's rights, so a mark of an item the owner may not read fails the load
    private void markAsDescribed(List<Marked> marks) {
        for (Marked mark : marks) {
            for (String login : mark.logins()) {
                try {
                    systemAuthenticator.runWithUser(login, () -> favoriteService.mark(List.of(mark.item())));
                } catch (OperationRejectedException e) {
                    throw new IllegalStateException("The demo set marks an item that '%s' may not read: %s"
                            .formatted(login, e.getMessage()), e);
                }
            }
        }
    }

    // what lies in the trash is deleted after the whole set has loaded, in the order of its time, and dated as
    // described; what goes together with a folder gets the time of the folder, so that it comes back with the folder
    private void deleteAsDescribed(List<Deleted> deletions) {
        deletions.sort(Comparator.comparing(deleted -> deleted.description().at()));

        for (Deleted deleted : deletions) {
            List<UUID> folders = new ArrayList<>();
            List<UUID> documents = new ArrayList<>();

            if (deleted.item() instanceof Folder folder) {
                folders.add(folder.getId());

                for (List<UUID> level = folders; !level.isEmpty(); folders.addAll(level)) {
                    level = unconstrainedDataManager.loadValues("select f.id from Folder f where f.parent.id in :ids")
                            .properties("id")
                            .parameter("ids", level)
                            .list()
                            .stream()
                            .map(value -> value.<UUID>getValue("id"))
                            .toList();
                }

                unconstrainedDataManager.loadValues("select d.id from Document d where d.folder.id in :ids")
                        .properties("id")
                        .parameter("ids", folders)
                        .list()
                        .forEach(value -> documents.add(value.getValue("id")));
                folderService.delete(folder);
            } else if (deleted.item() instanceof Document document) {
                documents.add(document.getId());
                documentService.delete(List.of(document));
            }

            List<Object> rows = new ArrayList<>();
            rows.addAll(deletedRows(Folder.class, "e.id in :ids", folders));
            rows.addAll(deletedRows(Document.class, "e.id in :ids", documents));
            rows.addAll(deletedRows(DocumentRevision.class, "e.document.id in :ids", documents));

            rows.forEach(row -> {
                EntityValues.setDeletedDate(row, at(deleted.description().at()));
                EntityValues.setDeletedBy(row, deleted.description().by());
            });

            unconstrainedDataManager.save(new SaveContext()
                    .saving(rows)
                    .setHint(PersistenceHints.SOFT_DELETION, false)
                    .setDiscardSaved(true));
        }
    }

    private List<?> deletedRows(Class<?> type, String condition, List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }

        return unconstrainedDataManager.load(type)
                .query(condition)
                .parameter("ids", ids)
                .hint(PersistenceHints.SOFT_DELETION, false)
                .list();
    }

    private static OffsetDateTime at(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private static final class Loaded {

        private final List<Deleted> deleted = new ArrayList<>();
        private final List<Marked> marked = new ArrayList<>();
        private final Map<String, DocumentStatus> statuses = new HashMap<>();
        private int folders;
        private int documents;
        private int versions;
    }

    private record Deleted(Object item, DemoSet.Deletion description) {
    }

    private record Marked(Object item, List<String> logins) {
    }
}
