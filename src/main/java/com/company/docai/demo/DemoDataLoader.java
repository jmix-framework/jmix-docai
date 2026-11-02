package com.company.docai.demo;

import com.company.docai.ai.classification.ClassificationProposal;
import com.company.docai.ai.classification.ClassificationResult;
import com.company.docai.ai.classification.DocumentClassifier;
import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.app.service.access.AccessService;
import com.company.docai.app.service.document.DocumentCommentService;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.document.UploadedFile;
import com.company.docai.app.service.favorite.FavoriteService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.app.service.tag.TagService;
import com.company.docai.model.access.AccessDraft;
import com.company.docai.model.access.AccessEntryDraft;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.document.Document;
import com.company.docai.model.document.DocumentComment;
import com.company.docai.model.document.DocumentRevision;
import com.company.docai.model.document.DocumentStatus;
import com.company.docai.model.folder.Folder;
import com.company.docai.model.tag.Tag;
import com.company.docai.model.user.User;
import io.jmix.core.DataManager;
import io.jmix.core.Metadata;
import io.jmix.core.SaveContext;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.data.PersistenceHints;
import io.jmix.security.role.ResourceRoleRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Loads a demo set into an empty database when the application starts: the folders under the root with their own
 * access, then the documents with their versions, types, fields, tags, access and statuses, and last what lies in
 * the trash.
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

    private static final String LOCATION = "com/company/docai/demo/%s/";
    private static final String DESCRIPTION = "demo-data.json";

    // a misspelt property of the description is a problem, not a value silently left out
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    // folders, access and statuses are created by the administrator, who manages every folder
    private static final String ADMIN = "admin";

    private final String chosenSet;
    private final DataManager dataManager;
    private final UnconstrainedDataManager unconstrainedDataManager;
    private final FolderService folderService;
    private final DocumentService documentService;
    private final DocumentCommentService commentService;
    private final AccessService accessService;
    private final DocumentClassifier documentClassifier;
    private final TagService tagService;
    private final FavoriteService favoriteService;
    private final ResourceRoleRepository roleRepository;
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
                          DocumentCommentService commentService,
                          AccessService accessService,
                          DocumentClassifier documentClassifier,
                          TagService tagService,
                          FavoriteService favoriteService,
                          ResourceRoleRepository roleRepository,
                          SystemAuthenticator systemAuthenticator,
                          Metadata metadata,
                          PlatformTransactionManager transactionManager) {
        this.chosenSet = chosenSet;
        this.dataManager = dataManager;
        this.unconstrainedDataManager = unconstrainedDataManager;
        this.folderService = folderService;
        this.documentService = documentService;
        this.commentService = commentService;
        this.accessService = accessService;
        this.documentClassifier = documentClassifier;
        this.tagService = tagService;
        this.favoriteService = favoriteService;
        this.roleRepository = roleRepository;
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

        if (!new ClassPathResource(LOCATION.formatted(set) + DESCRIPTION).exists()) {
            log.error("There is no demo set '{}': {} is missing", set, LOCATION.formatted(set) + DESCRIPTION);
            return Outcome.INVALID;
        }

        if (!isEmpty()) {
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
        return systemAuthenticator.withSystem(() -> problemsOf(set));
    }

    /**
     * Loads the set into the database in one transaction, without looking at what the database holds. The set must
     * pass {@link #check}.
     */
    public void load(String set) {
        DemoSet description;

        try {
            description = read(set);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        log.info("The demo set '{}' is loading", set);
        long started = System.nanoTime();
        Loaded loaded = new Loaded();

        transaction.executeWithoutResult(status -> systemAuthenticator.runWithUser(ADMIN, () -> {
            nameUsers(description.users());

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

    private List<String> problemsOf(String set) {
        DemoSet description;

        try {
            description = read(set);
        } catch (IOException | JacksonException e) {
            return List.of("The description of the demo set '%s' cannot be read: %s".formatted(set, e.getMessage()));
        }

        Set<String> users = new HashSet<>(unconstrainedDataManager.loadValues("select u.username from User u")
                .properties("username")
                .list()
                .stream()
                .map(row -> (String) row.getValue("username"))
                .toList());

        List<String> problems = new ArrayList<>();
        checkUsers(description.users(), users, problems);
        checkFolders(set, description.folders(), "", users, problems);
        checkTags(description.folders(), "", new HashMap<>(), problems);
        checkFavorites(description.folders(), "", false, users, problems);

        return problems;
    }

    private DemoSet read(String set) throws IOException {
        try (InputStream content = new ClassPathResource(LOCATION.formatted(set) + DESCRIPTION).getInputStream()) {
            return JSON.readValue(content, DemoSet.class);
        }
    }

    private boolean isEmpty() {
        long folders = unconstrainedDataManager.loadValue("select count(f) from Folder f", Long.class)
                .hint(PersistenceHints.SOFT_DELETION, false)
                .one();

        long documents = unconstrainedDataManager.loadValue("select count(d) from Document d", Long.class)
                .hint(PersistenceHints.SOFT_DELETION, false)
                .one();

        // the root folder comes with the changelogs
        return folders == 1 && documents == 0;
    }

    private static void checkUsers(List<DemoSet.User> described, Set<String> users, List<String> problems) {
        for (DemoSet.User names : described) {
            if (!users.contains(names.login())) {
                problems.add("users: there is no user '" + names.login() + "'");
            }
        }
    }

    private void checkFolders(String set, List<DemoSet.Folder> folders, String path, Set<String> users,
                              List<String> problems) {
        Set<String> names = new HashSet<>();

        for (DemoSet.Folder folder : folders) {
            String where = path + "/" + folder.name();

            if (isBlank(folder.name())) {
                problems.add(path + "/: a folder has no name");
            } else if (!names.add(folder.name())) {
                problems.add(where + ": two folders have this name");
            }

            checkAccess(folder.access(), where, users, problems);
            checkDeletion(folder.deleted(), where, users, problems);
            checkFolders(set, folder.folders(), where, users, problems);
            checkDocuments(set, folder.documents(), where, users, problems);
        }
    }

    private void checkDocuments(String set, List<DemoSet.Document> documents, String path, Set<String> users,
                                List<String> problems) {
        Set<String> names = new HashSet<>();

        for (DemoSet.Document document : documents) {
            String where = path + "/" + document.name();

            if (isBlank(document.name())) {
                problems.add(path + ": a document has no name");
            } else if (!names.add(document.name())) {
                problems.add(where + ": two documents have this name");
            }

            if (document.versions().isEmpty()) {
                problems.add(where + ": the document has no versions");
            }

            for (DemoSet.Version version : document.versions()) {
                if (isBlank(version.file()) || !file(set, version.file()).exists()) {
                    problems.add(where + ": there is no file '" + version.file() + "'");
                }

                if (!users.contains(version.author())) {
                    problems.add(where + ": there is no user '" + version.author() + "'");
                }

                if (version.uploaded() == null) {
                    problems.add(where + ": a version has no upload time");
                }
            }

            if (document.status() != null && parse(DocumentStatus.class, document.status()) == null) {
                problems.add(where + ": there is no status '" + document.status() + "'");
            }

            checkType(document, where, problems);
            checkAccess(document.access(), where, users, problems);
            checkDeletion(document.deleted(), where, users, problems);
            checkComments(document.comments(), where, users, problems);
        }
    }

    // the vocabulary keeps one tag for a name whatever its case, so a set writes a name one way
    private static void checkTags(List<DemoSet.Folder> folders, String path, Map<String, String> spellings,
                                  List<String> problems) {
        for (DemoSet.Folder folder : folders) {
            String folderPath = path + "/" + folder.name();

            for (DemoSet.Document document : folder.documents()) {
                String where = folderPath + "/" + document.name();

                for (String tag : document.tags()) {
                    String spelling = isBlank(tag) ? null : spellings.putIfAbsent(tag.toLowerCase(Locale.ROOT), tag);

                    if (isBlank(tag)) {
                        problems.add(where + ": a tag has no name");
                    } else if (tag.length() > Tag.MAX_NAME_LENGTH) {
                        problems.add(where + ": the tag '" + tag + "' is longer than " + Tag.MAX_NAME_LENGTH
                                + " characters");
                    } else if (spelling != null && !spelling.equals(tag)) {
                        problems.add(where + ": the tag '" + tag + "' is written '" + spelling + "' elsewhere");
                    }
                }
            }

            checkTags(folder.folders(), folderPath, spellings, problems);
        }
    }

    // a mark of a folder or a document that the loader puts into the trash would never show
    private static void checkFavorites(List<DemoSet.Folder> folders, String path, boolean deleted, Set<String> users,
                                       List<String> problems) {
        for (DemoSet.Folder folder : folders) {
            String folderPath = path + "/" + folder.name();
            boolean folderDeleted = deleted || folder.deleted() != null;
            checkMarks(folder.favoriteOf(), folderPath, folderDeleted, users, problems);

            for (DemoSet.Document document : folder.documents()) {
                checkMarks(document.favoriteOf(), folderPath + "/" + document.name(),
                        folderDeleted || document.deleted() != null, users, problems);
            }

            checkFavorites(folder.folders(), folderPath, folderDeleted, users, problems);
        }
    }

    private static void checkMarks(List<String> logins, String where, boolean deleted, Set<String> users,
                                   List<String> problems) {
        if (deleted && !logins.isEmpty()) {
            problems.add(where + ": an item in the trash cannot be a favorite");
        }

        for (String login : logins) {
            if (!users.contains(login)) {
                problems.add(where + ": there is no user '" + login + "'");
            }
        }
    }

    private static void checkDeletion(DemoSet.@Nullable Deletion deletion, String where, Set<String> users,
                                      List<String> problems) {
        if (deletion == null) {
            return;
        }

        if (!users.contains(deletion.by())) {
            problems.add(where + ": there is no user '" + deletion.by() + "'");
        }

        if (deletion.at() == null) {
            problems.add(where + ": the deletion has no time");
        }
    }

    private static void checkComments(List<DemoSet.Comment> comments, String where, Set<String> users,
                                      List<String> problems) {
        for (DemoSet.Comment comment : comments) {
            if (!users.contains(comment.author())) {
                problems.add(where + ": there is no user '" + comment.author() + "'");
            }

            if (isBlank(comment.text())) {
                problems.add(where + ": a comment has no text");
            } else if (comment.text().strip().length() > DocumentComment.MAX_TEXT_LENGTH) {
                problems.add(where + ": a comment is longer than " + DocumentComment.MAX_TEXT_LENGTH + " characters");
            }

            if (comment.at() == null) {
                problems.add(where + ": a comment has no time");
            } else if (comment.edited() != null && comment.edited().isBefore(comment.at())) {
                problems.add(where + ": a comment is edited before it is written");
            }
        }
    }

    private void checkType(DemoSet.Document document, String where, List<String> problems) {
        if (document.type() == null) {
            if (!document.fields().isEmpty()) {
                problems.add(where + ": the fields need a type");
            }

            return;
        }

        // the same rules the AI follows when it fills the type of a document
        ClassificationResult result = documentClassifier.apply(proposalOf(document),
                dataManager.create(Document.class));

        if (result.typeName() == null) {
            problems.add(where + ": there is no type '" + document.type() + "'");
        }

        for (String skipped : result.skipped()) {
            problems.add(where + ": the type does not accept " + skipped);
        }
    }

    private void checkAccess(@Nullable List<DemoSet.Access> entries, String where, Set<String> users,
                             List<String> problems) {
        if (entries == null) {
            return;
        }

        for (DemoSet.Access entry : entries) {
            if ((entry.user() == null) == (entry.role() == null)) {
                problems.add(where + ": an access entry must name a user or a role");
            } else if (entry.user() != null && !users.contains(entry.user())) {
                problems.add(where + ": there is no user '" + entry.user() + "'");
            } else if (entry.role() != null && roleRepository.findRoleByCode(entry.role()) == null) {
                problems.add(where + ": there is no role '" + entry.role() + "'");
            }

            if (entry.level() == null || parse(AccessLevel.class, entry.level()) == null) {
                problems.add(where + ": there is no access level '" + entry.level() + "'");
            }
        }
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
            unconstrainedDataManager.save(user);
        }
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
        DocumentStatus status = description.status() == null
                ? DocumentStatus.DRAFT
                : parse(DocumentStatus.class, description.status());

        Document draft = dataManager.create(Document.class);
        draft.setFolder(folder);
        draft.setName(description.name());
        draft.setDocumentDate(description.date());
        // the document service saves a new tag with the document and finds the one an earlier document brought
        draft.setTags(new ArrayList<>(description.tags().stream()
                .map(tagService::findOrCreate)
                .toList()));

        if (description.type() != null) {
            documentClassifier.apply(proposalOf(description), draft);
        }

        List<DemoSet.Version> versions = description.versions();
        DemoSet.Version first = versions.getFirst();
        DocumentStatus created = status == DocumentStatus.DRAFT ? null : DocumentStatus.ACTIVE;

        // every version is uploaded by its author, with the rights the author has
        Document document = systemAuthenticator.withUser(first.author(), () -> upload(set, first, description,
                file -> documentService.create(draft, file, created)));

        for (DemoSet.Version version : versions.subList(1, versions.size())) {
            Document current = document;
            document = systemAuthenticator.withUser(version.author(), () -> upload(set, version, description,
                    file -> documentService.uploadRevision(current, file)));
        }

        if (description.access() != null) {
            giveOwnAccess(accessService.describe(document), description.access());
        }

        // an archived document takes no other change, so its status comes last
        if (status == DocumentStatus.ARCHIVED) {
            documentService.changeStatus(List.of(document), DocumentStatus.ARCHIVED);
        }

        backdate(document, versions);
        writeComments(document, description.comments());
        loaded.documents++;
        loaded.marked.add(new Marked(document, description.favoriteOf()));
        loaded.versions += versions.size();

        if (description.deleted() != null) {
            loaded.deleted.add(new Deleted(document, description.deleted()));
        }
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
                try (InputStream source = file(set, version.file()).getInputStream()) {
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
                    entryDraft.setAccessLevel(parse(AccessLevel.class, entry.level()));
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
                    .setHint(PersistenceHints.SOFT_DELETION, false));
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

    private static ClassificationProposal proposalOf(DemoSet.Document document) {
        List<ClassificationProposal.Field> fields = document.fields()
                .entrySet()
                .stream()
                .map((Map.Entry<String, String> field) -> new ClassificationProposal.Field(field.getKey(),
                        field.getValue()))
                .toList();

        return new ClassificationProposal(document.type(), fields, null, List.of());
    }

    private static ClassPathResource file(String set, String file) {
        return new ClassPathResource(LOCATION.formatted(set) + "files/" + file);
    }

    private static OffsetDateTime at(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private static <E extends Enum<E>> @Nullable E parse(Class<E> type, String name) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isBlank(@Nullable String text) {
        return text == null || text.isBlank();
    }

    private static final class Loaded {

        private final List<Deleted> deleted = new ArrayList<>();
        private final List<Marked> marked = new ArrayList<>();
        private int folders;
        private int documents;
        private int versions;
    }

    private record Deleted(Object item, DemoSet.Deletion description) {
    }

    private record Marked(Object item, List<String> logins) {
    }
}
