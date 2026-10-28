package com.company.dms.demo;

import com.company.dms.ai.classification.ClassificationProposal;
import com.company.dms.ai.classification.ClassificationResult;
import com.company.dms.ai.classification.DocumentClassifier;
import com.company.dms.app.service.access.AccessService;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.document.UploadedFile;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.access.AccessDraft;
import com.company.dms.model.access.AccessEntryDraft;
import com.company.dms.model.access.AccessLevel;
import com.company.dms.model.document.Document;
import com.company.dms.model.document.DocumentRevision;
import com.company.dms.model.document.DocumentStatus;
import com.company.dms.model.folder.Folder;
import io.jmix.core.DataManager;
import io.jmix.core.Metadata;
import io.jmix.core.UnconstrainedDataManager;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Loads a demo set into an empty database when the application starts: the folders under the root with their own
 * access, then the documents with their versions, types, fields, access and statuses. The set is chosen by
 * {@code dms.demo-data} ({@code en}, {@code ru} or {@code none}); its description is
 * {@code com/company/dms/demo/<set>/demo-data.json}, the files of its versions lie in {@code files} next to it. A set
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
     * The value of {@code dms.demo-data} that loads no set.
     */
    public static final String NONE = "none";

    private static final Logger log = LoggerFactory.getLogger(DemoDataLoader.class);

    private static final String LOCATION = "com/company/dms/demo/%s/";
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
    private final AccessService accessService;
    private final DocumentClassifier documentClassifier;
    private final ResourceRoleRepository roleRepository;
    private final SystemAuthenticator systemAuthenticator;
    private final Metadata metadata;
    private final TransactionTemplate transaction;

    // the dates of the history are written past the entity listeners, which stamp the current time
    @PersistenceContext
    private EntityManager entityManager;

    public DemoDataLoader(@Value("${dms.demo-data:none}") String chosenSet,
                          DataManager dataManager,
                          UnconstrainedDataManager unconstrainedDataManager,
                          FolderService folderService,
                          DocumentService documentService,
                          AccessService accessService,
                          DocumentClassifier documentClassifier,
                          ResourceRoleRepository roleRepository,
                          SystemAuthenticator systemAuthenticator,
                          Metadata metadata,
                          PlatformTransactionManager transactionManager) {
        this.chosenSet = chosenSet;
        this.dataManager = dataManager;
        this.unconstrainedDataManager = unconstrainedDataManager;
        this.folderService = folderService;
        this.documentService = documentService;
        this.accessService = accessService;
        this.documentClassifier = documentClassifier;
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
     * level, status, type or field, and a field value its field does not accept.
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
            Folder root = folderService.getRoot();

            for (DemoSet.Folder folder : description.folders()) {
                createFolder(set, folder, root, loaded);
            }
        }));

        log.info("The demo set '{}' is loaded: {} folders, {} documents, {} versions in {} ms", set, loaded.folders,
                loaded.documents, loaded.versions, (System.nanoTime() - started) / 1_000_000);
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
        checkFolders(set, description.folders(), "", users, problems);

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

    private void createFolder(String set, DemoSet.Folder description, Folder parent, Loaded loaded) {
        Folder draft = dataManager.create(Folder.class);
        draft.setName(description.name());
        draft.setParent(parent);
        Folder folder = folderService.save(draft);
        loaded.folders++;

        if (description.access() != null) {
            giveOwnAccess(accessService.describe(folder), description.access());
        }

        for (DemoSet.Folder subfolder : description.folders()) {
            createFolder(set, subfolder, folder, loaded);
        }

        for (DemoSet.Document document : description.documents()) {
            createDocument(set, document, folder, loaded);
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
        loaded.documents++;
        loaded.versions += versions.size();
    }

    private Document upload(String set, DemoSet.Version version, DemoSet.Document description,
                            Function<UploadedFile, Document> save) {
        String extension = version.file().substring(version.file().lastIndexOf('.'));
        // the name goes into the address of the preview and the download, and the server rejects a slash there
        String name = description.name().replace('/', '_');
        String fileName = name.endsWith(extension) ? name : name + extension;

        try {
            Path content = Files.createTempFile("dms-demo-", extension);

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

    private static ClassificationProposal proposalOf(DemoSet.Document document) {
        List<ClassificationProposal.Field> fields = document.fields()
                .entrySet()
                .stream()
                .map((Map.Entry<String, String> field) -> new ClassificationProposal.Field(field.getKey(),
                        field.getValue()))
                .toList();

        return new ClassificationProposal(document.type(), fields, null);
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

        private int folders;
        private int documents;
        private int versions;
    }
}
