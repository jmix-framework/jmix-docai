package com.company.dms.app.service.access;

import com.company.dms.app.service.OperationRejectedException;
import com.company.dms.model.access.AccessDraft;
import com.company.dms.model.access.AccessEntry;
import com.company.dms.model.access.AccessEntryDraft;
import com.company.dms.model.access.AccessLevel;
import com.company.dms.model.access.AccessSet;
import com.company.dms.model.document.Document;
import com.company.dms.model.folder.Folder;
import com.company.dms.model.user.User;
import com.company.dms.security.role.AccessRowLevelRole;
import com.company.dms.security.role.FullAccessRole;
import com.company.dms.security.role.UiMinimalRole;
import io.jmix.core.DataManager;
import io.jmix.core.EntityStates;
import io.jmix.core.FetchPlan;
import io.jmix.core.FetchPlanBuilder;
import io.jmix.core.Messages;
import io.jmix.core.Metadata;
import io.jmix.core.MetadataTools;
import io.jmix.core.SaveContext;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.data.PersistenceHints;
import io.jmix.security.model.ResourceRole;
import io.jmix.security.role.ResourceRoleRepository;
import io.jmix.security.role.RoleLocalizationSupport;
import jakarta.persistence.LockModeType;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Changes who may see, change and manage folders and documents. The access sets and their entries are written
 * only here: every change needs {@link AccessLevel#MANAGE} in the set in effect for the item, and the folders and
 * documents that inherit from the item follow the change, including the ones in the trash.
 */
@Service
public class AccessService {

    // these roles are technical, so access entries are not given to them
    private static final Set<String> TECHNICAL_ROLES = Set.of(FullAccessRole.CODE, UiMinimalRole.CODE);

    // the lookup matches the name the list shows for a user, "First Last [username]"
    private static final String USER_NAME = "lower(concat(coalesce(u.firstName, ''), ' ', coalesce(u.lastName, ''),"
            + " ' [', u.username, ']'))";

    private final DataManager dataManager;
    private final UnconstrainedDataManager unconstrainedDataManager;
    private final AccessChecker accessChecker;
    private final Metadata metadata;
    private final MetadataTools metadataTools;
    private final EntityStates entityStates;
    private final ResourceRoleRepository roleRepository;
    private final RoleLocalizationSupport roleLocalizationSupport;
    private final Messages messages;

    public AccessService(DataManager dataManager, UnconstrainedDataManager unconstrainedDataManager,
                         AccessChecker accessChecker, Metadata metadata, MetadataTools metadataTools,
                         EntityStates entityStates, ResourceRoleRepository roleRepository,
                         RoleLocalizationSupport roleLocalizationSupport, Messages messages) {
        this.dataManager = dataManager;
        this.unconstrainedDataManager = unconstrainedDataManager;
        this.accessChecker = accessChecker;
        this.metadata = metadata;
        this.metadataTools = metadataTools;
        this.entityStates = entityStates;
        this.roleRepository = roleRepository;
        this.roleLocalizationSupport = roleLocalizationSupport;
        this.messages = messages;
    }

    /**
     * Describes the access of the folder for the access dialog.
     *
     * @throws OperationRejectedException if the folder is deleted or hidden from the user, or the user does not
     *                                    manage its access
     */
    public AccessDraft describe(Folder folder) {
        Folder stored = loadFolder(folder);

        AccessDraft draft = draftOf(stored, stored.getAccessSet(), parentSet(stored));
        draft.setFolder(stored);
        return draft;
    }

    /**
     * Describes the access of the document for the access dialog.
     *
     * @throws OperationRejectedException if the document is deleted or hidden from the user, or the user does not
     *                                    manage its access
     */
    public AccessDraft describe(Document document) {
        Document stored = loadDocument(document);

        AccessDraft draft = draftOf(stored, stored.getAccessSet(), stored.getFolder().getAccessSet());
        draft.setDocument(stored);
        return draft;
    }

    /**
     * Applies the access dialog in one transaction: breaks the inheritance or returns to it, then writes the
     * difference of the entries. Access changes queue with folder moves on the root row, so a branch does not
     * move while its access changes.
     *
     * @throws OperationRejectedException if the item is deleted or hidden from the user, the user does not manage
     *                                    its access, the access changed since the dialog opened, a user or a role
     *                                    is listed twice, an entry has no level, or a technical role gets access
     */
    @Transactional
    public void save(AccessDraft draft) {
        unconstrainedDataManager.load(Folder.class)
                .id(Folder.ROOT_ID)
                .lockMode(LockModeType.PESSIMISTIC_WRITE)
                .one();

        SaveContext saveContext = new SaveContext().setHint(PersistenceHints.SOFT_DELETION, false);

        if (draft.getFolder() != null) {
            Folder folder = loadFolder(draft.getFolder());
            apply(draft, folder, folder.getAccessSet(), parentSet(folder), saveContext,
                    (from, to) -> repointBranch(folder, from, to, saveContext));
        } else {
            Document document = loadDocument(draft.getDocument());
            apply(draft, document, document.getAccessSet(), document.getFolder().getAccessSet(), saveContext,
                    (from, to) -> {
                        document.setAccessSet(to);
                        saveContext.saving(document);
                    });
        }

        unconstrainedDataManager.save(saveContext);
    }

    /**
     * Tells whether the current user still manages the item after the draft is applied, so the dialog can warn
     * before the user takes management from themselves.
     */
    public boolean keepsManagement(AccessDraft draft) {
        if (accessChecker.hasFullAccess()) {
            return true;
        }

        List<AccessEntryDraft> entries = Boolean.TRUE.equals(draft.getInherit())
                ? draft.getInheritedEntries()
                : draft.getEntries();

        return entries.stream()
                .filter(entry -> accessChecker.isMine(entry.getUsername(), entry.getRoleCode()))
                .anyMatch(entry -> entry.getAccessLevel() == AccessLevel.MANAGE);
    }

    /**
     * Returns the levels of the current user in the access sets where they have entries, in one query.
     */
    public AccessLevels levels() {
        if (accessChecker.hasFullAccess()) {
            return new AccessLevels(Map.of(), true);
        }

        Map<UUID, AccessLevel> bySet = new HashMap<>();
        unconstrainedDataManager.loadValues("select ae.accessSet.id, max(ae.accessLevel) from AccessEntry ae"
                        + " where " + AccessRowLevelRole.MY_ENTRY + " group by ae.accessSet.id")
                .properties("accessSet", "level")
                .list()
                .forEach(row -> bySet.put(row.getValue("accessSet"), AccessLevel.fromId(row.getValue("level"))));

        return new AccessLevels(bySet, false);
    }

    /**
     * Tells whether the current user manages the access of any folder or document that is not deleted, so that
     * access is offered only to those who can use it.
     */
    public boolean managesAnything() {
        return accessChecker.hasFullAccess() || managesAny("Folder") || managesAny("Document");
    }

    /**
     * Finds the resource roles and the users that can get access and whose names contain the text, roles first,
     * each by name, one page at a time: the access dialog looks them up as the user types.
     */
    public List<AccessSubject> findSubjects(String text, int offset, int limit) {
        String part = text.strip().toLowerCase(Locale.ROOT);
        List<AccessSubject> roles = roleRepository.getAllRoles().stream()
                .filter(role -> !TECHNICAL_ROLES.contains(role.getCode()))
                .map(role -> new AccessSubject(null, role.getCode(), roleLocalizationSupport.getLocalizedName(role)))
                .filter(role -> role.name().toLowerCase(Locale.ROOT).contains(part))
                .sorted(Comparator.comparing(AccessSubject::name))
                .toList();

        // the roles are few and live in code as well as in the database, so only the users are read by the page
        int rolesEnd = (int) Math.min((long) offset + limit, roles.size());
        List<AccessSubject> page = new ArrayList<>(roles.subList(Math.min(offset, rolesEnd), rolesEnd));
        if (page.size() < limit) {
            dataManager.load(User.class)
                    .query("select u from User u where " + USER_NAME + " like :part escape '\\'"
                            + " order by u.firstName, u.lastName, u.username")
                    .parameter("part", "%" + escapeForLike(part) + "%")
                    .firstResult(Math.max(0, offset - roles.size()))
                    .maxResults(limit - page.size())
                    .list()
                    .forEach(user -> page.add(new AccessSubject(user.getUsername(), null,
                            metadataTools.getInstanceName(user))));
        }

        return page;
    }

    /**
     * Tells whether moving the folder changes its access: it inherits, and the target passes down another set.
     */
    public boolean changesOnMove(Folder folder, Folder target) {
        Folder stored = unconstrainedDataManager.load(Folder.class)
                .id(folder.getId())
                .fetchPlan(fetchPlan -> fetchPlan.add("accessSet", AccessService::withOwners))
                .one();

        return !stored.equals(stored.getAccessSet().getOwnerFolder()) && !stored.getAccessSet().equals(setOf(target));
    }

    /**
     * Tells whether moving the documents changes the access of any of them: it inherits, and the target passes
     * down another set.
     */
    public boolean changesOnMove(Collection<Document> documents, Folder target) {
        AccessSet targetSet = setOf(target);
        return unconstrainedDataManager.load(Document.class)
                .ids(documents.stream().map(Document::getId).toList())
                .fetchPlan(fetchPlan -> fetchPlan.add("accessSet", AccessService::withOwners))
                .list()
                .stream()
                .anyMatch(document -> !document.equals(document.getAccessSet().getOwnerDocument())
                        && !document.getAccessSet().equals(targetSet));
    }

    /**
     * Makes the access of a moved folder follow the move, in the save context of the move: a folder that inherits
     * takes the set of the target, together with the folders and documents below it that inherit from it, deleted
     * ones included; a folder with its own access keeps it. The save context then saves deleted items too.
     */
    public void followMove(Folder folder, Folder target, SaveContext saveContext) {
        Folder stored = unconstrainedDataManager.load(Folder.class)
                .id(folder.getId())
                .fetchPlan(fetchPlan -> fetchPlan.add("accessSet", AccessService::withOwners))
                .one();

        AccessSet current = stored.getAccessSet();
        AccessSet targetSet = setOf(target);

        if (!stored.equals(current.getOwnerFolder()) && !current.equals(targetSet)) {
            saveContext.setHint(PersistenceHints.SOFT_DELETION, false);
            repointBranch(folder, current, targetSet, saveContext);
        }
    }

    /**
     * Makes the access of moved documents follow the move, in the save context of the move: a document that
     * inherits takes the set of the target; a document with its own access keeps it.
     */
    public void followMove(Collection<Document> documents, Folder target, SaveContext saveContext) {
        if (documents.isEmpty()) {
            return;
        }

        AccessSet targetSet = setOf(target);
        Set<Document> inheriting = unconstrainedDataManager.load(Document.class)
                .ids(documents.stream().map(Document::getId).toList())
                .fetchPlan(fetchPlan -> fetchPlan.add("accessSet", AccessService::withOwners))
                .list()
                .stream()
                .filter(document -> !document.equals(document.getAccessSet().getOwnerDocument()))
                .collect(Collectors.toSet());

        documents.stream()
                .filter(inheriting::contains)
                .forEach(document -> {
                    document.setAccessSet(targetSet);
                    saveContext.saving(document);
                });
    }

    private AccessDraft draftOf(Object item, AccessSet current, @Nullable AccessSet parentSet) {
        String name = metadataTools.getInstanceName(item);
        AccessSet set = loadSet(current);
        requireManage(set, name);

        AccessDraft draft = metadata.create(AccessDraft.class);
        draft.setInherit(!item.equals(ownerOf(set)));
        draft.setFingerprint(fingerprint(set));
        draft.setEntries(drafts(set));

        if (parentSet == null) {
            draft.setInheritedEntries(new ArrayList<>());
        } else {
            AccessSet inherited = loadSet(parentSet);
            draft.setInheritedFrom(metadataTools.getInstanceName(ownerOf(inherited)));
            draft.setInheritedEntries(drafts(inherited));
        }

        // the draft stands for stored state: a data context that receives it then reports only what the user changes
        entityStates.makeDetached(draft);
        Stream.concat(draft.getEntries().stream(), draft.getInheritedEntries().stream())
                .forEach(entityStates::makeDetached);

        return draft;
    }

    private void apply(AccessDraft draft, Object item, AccessSet current, @Nullable AccessSet parentSet,
                       SaveContext saveContext, BiConsumer<AccessSet, AccessSet> repointing) {
        String name = metadataTools.getInstanceName(item);
        AccessSet set = loadSet(current);
        if (!fingerprint(set).equals(draft.getFingerprint())) {
            throw reject("accessChanged", name);
        }

        requireManage(set, name);
        boolean own = item.equals(ownerOf(set));

        if (Boolean.TRUE.equals(draft.getInherit())) {
            if (own && parentSet != null) {
                repointing.accept(set, parentSet);
                // the database deletes the entries with the set
                saveContext.removing(set);
            }
        } else if (own) {
            writeEntries(set, draft.getEntries(), saveContext);
        } else {
            AccessSet created = unconstrainedDataManager.create(AccessSet.class);
            if (item instanceof Folder folder) {
                created.setOwnerFolder(folder);
            } else {
                created.setOwnerDocument((Document) item);
            }

            saveContext.saving(created);
            writeEntries(created, draft.getEntries(), saveContext);
            repointing.accept(set, created);
        }
    }

    private void writeEntries(AccessSet set, List<AccessEntryDraft> drafts, SaveContext saveContext) {
        Map<String, AccessEntry> existing = set.getEntries() == null
                ? Map.of()
                : set.getEntries().stream().collect(Collectors.toMap(AccessService::subjectKey, Function.identity()));

        Set<String> wanted = new HashSet<>();
        for (AccessEntryDraft draft : drafts) {
            // the dialog never sends these; a draft that comes another way gets a reason, not a database error
            if (draft.getAccessLevel() == null) {
                throw reject("accessLevelMissing", draft.getSubjectName());
            }

            if (draft.getRoleCode() != null && TECHNICAL_ROLES.contains(draft.getRoleCode())) {
                throw reject("accessTechnicalRole", draft.getRoleCode());
            }

            String key = subjectKey(draft.getUsername(), draft.getRoleCode());
            if (!wanted.add(key)) {
                throw reject("accessSubjectTwice", draft.getSubjectName());
            }

            AccessEntry entry = existing.get(key);
            if (entry == null) {
                entry = unconstrainedDataManager.create(AccessEntry.class);
                entry.setAccessSet(set);
                entry.setUsername(draft.getUsername());
                entry.setRoleCode(draft.getRoleCode());
                entry.setAccessLevel(draft.getAccessLevel());

                saveContext.saving(entry);
            } else if (entry.getAccessLevel() != draft.getAccessLevel()) {
                entry.setAccessLevel(draft.getAccessLevel());
                saveContext.saving(entry);
            }
        }

        existing.forEach((key, entry) -> {
            if (!wanted.contains(key)) {
                saveContext.removing(entry);
            }
        });
    }

    /**
     * Moves the folder and the folders and documents below it that use the same set to another set. A folder
     * with its own set stops the walk: the items below it use that set.
     */
    private void repointBranch(Folder folder, AccessSet from, AccessSet to, SaveContext saveContext) {
        List<Folder> branch = new ArrayList<>();
        List<Folder> level = List.of(folder);
        while (!level.isEmpty()) {
            level.forEach(each -> each.setAccessSet(to));
            branch.addAll(level);

            level = unconstrainedDataManager.load(Folder.class)
                    .query("select f from Folder f where f.parent in :parents and f.accessSet = :accessSet")
                    .parameter("parents", level)
                    .parameter("accessSet", from)
                    .hint(PersistenceHints.SOFT_DELETION, false)
                    .list();
        }

        List<Document> documents = unconstrainedDataManager.load(Document.class)
                .query("select d from Document d where d.folder in :folders and d.accessSet = :accessSet")
                .parameter("folders", branch)
                .parameter("accessSet", from)
                .hint(PersistenceHints.SOFT_DELETION, false)
                .list();

        documents.forEach(document -> document.setAccessSet(to));
        saveContext.saving(branch, documents);
    }

    private List<AccessEntryDraft> drafts(AccessSet set) {
        List<String> usernames = set.getEntries().stream()
                .map(AccessEntry::getUsername)
                .filter(Objects::nonNull)
                .toList();

        Map<String, String> userNames = usernames.isEmpty()
                ? Map.of()
                : unconstrainedDataManager.load(User.class)
                        .query("select u from User u where u.username in :usernames")
                        .parameter("usernames", usernames)
                        .list()
                        .stream()
                        .collect(Collectors.toMap(User::getUsername, metadataTools::getInstanceName));

        return set.getEntries().stream()
                .map(entry -> {
                    AccessEntryDraft draft = metadata.create(AccessEntryDraft.class);
                    draft.setUsername(entry.getUsername());
                    draft.setRoleCode(entry.getRoleCode());
                    draft.setAccessLevel(entry.getAccessLevel());
                    draft.setSubjectName(entry.getUsername() != null
                            ? userNames.getOrDefault(entry.getUsername(), entry.getUsername())
                            : roleName(entry.getRoleCode()));
                    return draft;
                })
                .sorted(Comparator.comparing((AccessEntryDraft draft) -> draft.getUsername() != null)
                        .thenComparing(AccessEntryDraft::getSubjectName))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private String roleName(String code) {
        ResourceRole role = roleRepository.findRoleByCode(code);
        return role == null
                ? messages.formatMessage(getClass(), "roleNotFound", code)
                : roleLocalizationSupport.getLocalizedName(role);
    }

    private void requireManage(AccessSet set, String name) {
        AccessLevel level = accessChecker.getLevel(set);
        if (level == null || !level.includes(AccessLevel.MANAGE)) {
            throw reject("accessManageRequired", name);
        }
    }

    private Folder loadFolder(Folder folder) {
        return dataManager.load(Folder.class)
                .id(folder.getId())
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.INSTANCE_NAME)
                        .add("accessSet", FetchPlan.INSTANCE_NAME)
                        .add("parent", parent -> parent.add("accessSet", FetchPlan.INSTANCE_NAME)))
                .optional()
                .orElseThrow(() -> reject("folderUnavailable", metadataTools.getInstanceName(folder)));
    }

    private Document loadDocument(Document document) {
        return dataManager.load(Document.class)
                .id(document.getId())
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.INSTANCE_NAME)
                        .add("accessSet", FetchPlan.INSTANCE_NAME)
                        .add("folder", folder -> folder.add("accessSet", FetchPlan.INSTANCE_NAME)))
                .optional()
                .orElseThrow(() -> reject("documentUnavailable", metadataTools.getInstanceName(document)));
    }

    private AccessSet loadSet(AccessSet set) {
        return unconstrainedDataManager.load(AccessSet.class)
                .id(set.getId())
                .fetchPlan(fetchPlan -> withOwners(fetchPlan.add("entries", FetchPlan.BASE)))
                .one();
    }

    private AccessSet setOf(Folder folder) {
        return unconstrainedDataManager.load(Folder.class)
                .id(folder.getId())
                .fetchPlan(fetchPlan -> fetchPlan.add("accessSet", FetchPlan.INSTANCE_NAME))
                .one()
                .getAccessSet();
    }

    @Nullable
    private static AccessSet parentSet(Folder folder) {
        return folder.getParent() == null ? null : folder.getParent().getAccessSet();
    }

    private static Object ownerOf(AccessSet set) {
        return set.getOwnerFolder() != null ? set.getOwnerFolder() : set.getOwnerDocument();
    }

    private static void withOwners(FetchPlanBuilder fetchPlan) {
        fetchPlan.add("ownerFolder", FetchPlan.INSTANCE_NAME)
                .add("ownerDocument", FetchPlan.INSTANCE_NAME);
    }

    /**
     * The set and its entries as text: a dialog opened on another state of the set must not overwrite it.
     */
    private static String fingerprint(AccessSet set) {
        return set.getId() + set.getEntries().stream()
                .map(entry -> subjectKey(entry) + "=" + entry.getAccessLevel().getId())
                .sorted()
                .collect(Collectors.joining(";", "[", "]"));
    }

    private static String subjectKey(AccessEntry entry) {
        return subjectKey(entry.getUsername(), entry.getRoleCode());
    }

    private static String subjectKey(@Nullable String username, @Nullable String roleCode) {
        return username != null ? "user:" + username : "role:" + roleCode;
    }

    // the query skips deleted items, so a set left behind in the trash does not count
    private boolean managesAny(String entityName) {
        return unconstrainedDataManager.loadValue("select e.id from " + entityName + " e"
                        + " where exists (select ae from AccessEntry ae where ae.accessSet = e.accessSet"
                        + " and ae.accessLevel >= @enum(com.company.dms.model.access.AccessLevel.MANAGE)"
                        + " and " + AccessRowLevelRole.MY_ENTRY + ")", UUID.class)
                .maxResults(1)
                .optional()
                .isPresent();
    }

    // what the user types is matched as it is: a percent sign or an underscore is not a wildcard
    private static String escapeForLike(String text) {
        return text.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private OperationRejectedException reject(String key, Object... args) {
        return new OperationRejectedException(messages.formatMessage(getClass(), key, args));
    }
}
