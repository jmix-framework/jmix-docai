package com.company.docai.demo;

import com.company.docai.ai.classification.ClassificationProposal;
import com.company.docai.ai.classification.DocumentClassifier;
import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.app.service.access.AccessService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.app.service.trash.TrashService;
import com.company.docai.model.access.AccessDraft;
import com.company.docai.model.access.AccessEntryDraft;
import com.company.docai.model.document.Document;
import com.company.docai.model.document.DocumentComment;
import com.company.docai.model.document.DocumentRevision;
import com.company.docai.model.document.DocumentStatus;
import com.company.docai.model.favorite.Favorite;
import com.company.docai.model.folder.Folder;
import com.company.docai.model.tag.Tag;
import com.company.docai.model.user.User;
import com.company.docai.security.role.EmployeeRole;
import com.company.docai.test_support.AuthenticatedAsAdmin;
import com.company.docai.test_support.TestStatuses;
import com.company.docai.test_support.TestTrash;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.TimeSource;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.data.PersistenceHints;
import io.jmix.dynattr.DynAttrQueryHints;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Loads the sets into a database of their own and compares the database with the descriptions, so the test names
 * no folder or document of a set. The history of a set lands on the days before the load, so its times compare with
 * those of the description moved by the same whole days.
 */
@SpringBootTest(properties = "main.datasource.url=jdbc:hsqldb:mem:demo")
@ExtendWith(AuthenticatedAsAdmin.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DemoDataLoaderTest {

    // the access of the root comes with the changelog
    private static final List<String> ROOT_ACCESS = List.of("role:" + EmployeeRole.CODE + "=EDIT");

    @Autowired
    private DemoDataLoader loader;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @Autowired
    private FolderService folderService;

    @Autowired
    private AccessService accessService;

    @Autowired
    private TrashService trashService;

    @Autowired
    private DocumentClassifier documentClassifier;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private TimeSource timeSource;

    // the names of the statuses that the keys of the set being checked stand for
    private Map<String, String> statusNames = Map.of();
    // the whole days the loader moved the history of the set being checked by
    private long shift;

    @Test
    @Order(1)
    void aSetThatFailsHalfwayLeavesTheDatabaseEmpty() {
        // peter files a note in an open folder, then in a folder he may not open
        assertThatThrownBy(() -> loader.load("forbidden"))
                .isInstanceOf(OperationRejectedException.class);

        assertThat(unconstrainedDataManager.loadValue("select count(f) from Folder f", Long.class)
                .hint(PersistenceHints.SOFT_DELETION, false)
                .one()).isEqualTo(1L);
        assertThat(unconstrainedDataManager.loadValue("select count(d) from Document d", Long.class)
                .hint(PersistenceHints.SOFT_DELETION, false)
                .one()).isZero();
    }

    @Test
    @Order(2)
    void anEmptyDatabaseGetsTheSetAndAFilledOneDoesNot() {
        LocalDate loadDay = today();

        assertThat(loader.loadIfEmpty("sample")).isEqualTo(DemoDataLoader.Outcome.LOADED);
        assertThat(loader.loadIfEmpty("sample")).isEqualTo(DemoDataLoader.Outcome.NOT_EMPTY);

        shift = shiftOf("sample", loadDay);
        assertLoadedAsDescribed("sample");
    }

    @Test
    @Order(3)
    void theRussianSetLoadsAsItsDescriptionSays() {
        assertThat(loader.check("ru")).isEmpty();
        LocalDate loadDay = today();

        loader.load("ru");

        shift = shiftOf("ru", loadDay);
        assertLoadedAsDescribed("ru");
        assertFavoritesAsDescribed("ru");
        assertUsersAsDescribed("ru", Character.UnicodeScript.CYRILLIC);
        assertStatusesAsDescribed("ru", Character.UnicodeScript.CYRILLIC);
    }

    @Test
    @Order(4)
    void theEnglishSetLoadsAsItsDescriptionSays() {
        assertThat(loader.check("en")).isEmpty();
        LocalDate loadDay = today();

        loader.load("en");

        shift = shiftOf("en", loadDay);
        assertLoadedAsDescribed("en");
        assertFavoritesAsDescribed("en");
        assertUsersAsDescribed("en", Character.UnicodeScript.LATIN);
        assertStatusesAsDescribed("en", Character.UnicodeScript.LATIN);
    }

    private void assertLoadedAsDescribed(String set) {
        statusNames = statusNamesOf(read(set));
        List<Visibility> visibility = new ArrayList<>();
        Trash trash = new Trash(new ArrayList<>(), new ArrayList<>());

        for (DemoSet.Folder folder : read(set).folders()) {
            assertFolder(folder, folderService.getRoot(), ROOT_ACCESS, null, visibility, trash);
        }

        // an item deleted on its own lies in the trash, what was deleted with a folder lies inside the folder
        List<UUID> items = TestTrash.ids(trashService.loadItems());
        assertThat(items).containsAll(trash.items()).doesNotContainAnyElementsOf(trash.contents());

        List<UUID> ids = visibility.stream()
                .map(Visibility::id)
                .toList();

        List<UUID> seenByPeter = systemAuthenticator.withUser("peter", () -> dataManager.load(Document.class)
                .query("select d from Document d where d.id in :ids")
                .parameter("ids", ids)
                .list()
                .stream()
                .map(Document::getId)
                .toList());

        assertThat(seenByPeter).containsExactlyInAnyOrderElementsOf(visibility.stream()
                .filter(Visibility::byPeter)
                .map(Visibility::id)
                .toList());
    }

    // every demo user takes the name and the address of the set, written in its script; the English set loads after
    // the Russian one, so it gives the users their English names back
    private void assertUsersAsDescribed(String set, Character.UnicodeScript script) {
        List<DemoSet.User> described = read(set).users();
        assertThat(described)
                .extracting(DemoSet.User::login)
                .containsExactlyInAnyOrder("peter", "alice", "helen", "maria", "john");

        for (DemoSet.User names : described) {
            User user = unconstrainedDataManager.load(User.class)
                    .query("e.username = :login")
                    .parameter("login", names.login())
                    .one();

            assertThat(user)
                    .extracting(User::getFirstName, User::getLastName, User::getEmail)
                    .containsExactly(names.firstName(), names.lastName(), names.email());

            assertThat(names.firstName() + names.lastName())
                    .matches(name -> isWrittenIn(name, script), "written in " + script);
        }
    }

    // every status of the set has the name, the color, the place, the flags and the targets the set gives it, and
    // its name is written in the script of the set; the English set loads after the Russian one, so it gives the
    // default statuses their English names back and adds its own In review beside the Russian one
    private void assertStatusesAsDescribed(String set, Character.UnicodeScript script) {
        List<DemoSet.Status> described = read(set).statuses();
        assertThat(described)
                .extracting(DemoSet.Status::key)
                .containsExactly("DRAFT", "IN_REVIEW", "ACTIVE", "ARCHIVED");

        Map<String, DocumentStatus> statuses = described.stream()
                .collect(Collectors.toMap(DemoSet.Status::key, this::statusOf));

        for (DemoSet.Status entry : described) {
            DocumentStatus status = statuses.get(entry.key());

            assertThat(status.getName()).isEqualTo(entry.name());
            assertThat(status.getName().replace(" ", ""))
                    .matches(name -> isWrittenIn(name, script), "written in " + script);

            if (entry.color() != null) {
                assertThat(status.getColor()).isEqualTo(entry.color());
            }

            if (entry.position() != null) {
                assertThat(status.getPosition()).isEqualTo(entry.position());
            }

            if (entry.initial() != null) {
                assertThat(status.getInitial()).isEqualTo(entry.initial());
            }

            if (entry.locked() != null) {
                assertThat(status.getLocked()).isEqualTo(entry.locked());
            }

            if (entry.anyTarget() != null) {
                assertThat(status.getAnyTarget()).isEqualTo(entry.anyTarget());
            }

            if (entry.targets() != null) {
                assertThat(status.getTargets())
                        .containsExactlyInAnyOrderElementsOf(entry.targets().stream()
                                .map(statuses::get)
                                .toList());
            }
        }
    }

    // a default status by its id, another one by its name
    private DocumentStatus statusOf(DemoSet.Status entry) {
        return switch (entry.key()) {
            case "DRAFT" -> TestStatuses.draft(dataManager);
            case "ACTIVE" -> TestStatuses.active(dataManager);
            case "ARCHIVED" -> TestStatuses.archived(dataManager);
            default -> dataManager.load(DocumentStatus.class)
                    .query("e.name = :name")
                    .parameter("name", entry.name())
                    .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                            .add("targets", FetchPlan.BASE))
                    .one();
        };
    }

    private static boolean isWrittenIn(String text, Character.UnicodeScript script) {
        return text.codePoints()
                .allMatch(codePoint -> Character.UnicodeScript.of(codePoint) == script);
    }

    // every login of a favoriteOf has its mark on the item; the folders of the other sets have marks of their own
    private void assertFavoritesAsDescribed(String set) {
        List<String> expected = new ArrayList<>();
        collectMarks(read(set).folders(), "", expected);

        List<String> folders = read(set).folders().stream()
                .map(folder -> "/" + folder.name() + "/")
                .toList();

        List<String> marks = unconstrainedDataManager.load(Favorite.class)
                .all()
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("user", user -> user.add("username"))
                        .add("folder", FetchPlan.BASE)
                        .add("document", document -> document.addFetchPlan(FetchPlan.BASE)
                                .add("folder", FetchPlan.BASE)))
                .list()
                .stream()
                .map(favorite -> favorite.getUser().getUsername() + ":" + (favorite.getFolder() != null
                        ? pathOf(favorite.getFolder())
                        : pathOf(favorite.getDocument().getFolder()) + "/" + favorite.getDocument().getName()))
                .filter(mark -> folders.stream().anyMatch(folder -> (mark.substring(mark.indexOf(':') + 1) + "/")
                        .startsWith(folder)))
                .toList();

        assertThat(expected).isNotEmpty();
        assertThat(marks).containsExactlyInAnyOrderElementsOf(expected);
    }

    private static void collectMarks(List<DemoSet.Folder> folders, String path, List<String> marks) {
        for (DemoSet.Folder folder : folders) {
            String folderPath = path + "/" + folder.name();
            folder.favoriteOf().forEach(login -> marks.add(login + ":" + folderPath));
            folder.documents().forEach(document -> document.favoriteOf()
                    .forEach(login -> marks.add(login + ":" + folderPath + "/" + document.name())));
            collectMarks(folder.folders(), folderPath, marks);
        }
    }

    // the names from the folder under the root down to the folder
    private String pathOf(Folder folder) {
        return folderService.getPath(folder).stream()
                .skip(1)
                .map(step -> "/" + step.getName())
                .collect(Collectors.joining());
    }

    // a folder or a document deleted together with a folder takes the deletion of the folder
    private void assertFolder(DemoSet.Folder description, Folder parent, List<String> parentAccess,
                              DemoSet.@Nullable Deletion deletedWith, List<Visibility> visibility, Trash trash) {
        DemoSet.Deletion deletion = description.deleted() != null ? description.deleted() : deletedWith;
        Folder folder = dataManager.load(Folder.class)
                .query("e.parent = :parent and e.name = :name")
                .parameter("parent", parent)
                .parameter("name", description.name())
                .hint(PersistenceHints.SOFT_DELETION, false)
                .one();

        assertDeletion(folder.getDeletedBy(), folder.getDeletedDate(), deletion);
        trash.add(folder.getId(), description.deleted(), deletedWith);

        // the access of what lies in the trash is not shown anywhere
        List<String> access = deletion == null
                ? assertAccess(accessService.describe(folder), description.access(), parentAccess)
                : parentAccess;

        for (DemoSet.Folder subfolder : description.folders()) {
            assertFolder(subfolder, folder, access, deletion, visibility, trash);
        }

        for (DemoSet.Document document : description.documents()) {
            DemoSet.Deletion documentDeletion = document.deleted() != null ? document.deleted() : deletion;
            Document stored = assertDocument(document, folder, documentDeletion);
            trash.add(stored.getId(), document.deleted(), deletion);

            if (documentDeletion == null) {
                List<String> documentAccess = assertAccess(accessService.describe(stored), document.access(),
                        access);

                visibility.add(new Visibility(stored.getId(), documentAccess.stream()
                        .anyMatch(entry -> entry.startsWith("user:peter=")
                                || entry.startsWith("role:" + EmployeeRole.CODE + "="))));
            }
        }
    }

    private void assertDeletion(@Nullable String deletedBy, @Nullable OffsetDateTime deletedDate,
                                DemoSet.@Nullable Deletion deletion) {
        if (deletion == null) {
            assertThat(deletedDate).isNull();
            return;
        }

        assertThat(deletedBy).isEqualTo(deletion.by());
        assertThat(deletedDate).isNotNull();
        assertThat(deletedDate.toInstant()).isEqualTo(instantOf(deletion.at()));
    }

    // the access the item has: its own one as described, or the one of its parent
    private static List<String> assertAccess(AccessDraft draft, List<DemoSet.Access> described,
                                             List<String> parentAccess) {
        if (described == null) {
            assertThat(draft.getInherit()).isTrue();
            return parentAccess;
        }

        List<String> expected = described.stream()
                .map(entry -> entry.user() != null
                        ? "user:" + entry.user() + "=" + entry.level()
                        : "role:" + entry.role() + "=" + entry.level())
                .toList();

        assertThat(draft.getInherit()).isFalse();
        assertThat(subjects(draft.getEntries())).containsExactlyInAnyOrderElementsOf(expected);

        return expected;
    }

    private Document assertDocument(DemoSet.Document description, Folder folder,
                                    DemoSet.@Nullable Deletion deletion) {
        Document document = dataManager.load(Document.class)
                .query("e.folder = :folder and e.name = :name")
                .parameter("folder", folder)
                .parameter("name", description.name())
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("category", FetchPlan.BASE)
                        .add("status", FetchPlan.BASE))
                .hint(DynAttrQueryHints.LOAD_DYN_ATTR, true)
                .hint(PersistenceHints.SOFT_DELETION, false)
                .one();

        assertDeletion(document.getDeletedBy(), document.getDeletedDate(), deletion);

        assertThat(document.getDocumentDate()).isEqualTo(description.date());
        assertThat(document.getStatus().getName())
                .isEqualTo(statusNames.get(description.status() == null ? "DRAFT" : description.status()));
        assertThat(document.getCategory() == null ? null : document.getCategory().getName())
                .isEqualTo(description.type());
        assertFields(description, document);
        assertThat(dataManager.load(Document.class)
                .id(document.getId())
                .fetchPlan(fetchPlan -> fetchPlan.add("tags", FetchPlan.BASE))
                .hint(PersistenceHints.SOFT_DELETION, false)
                .one()
                .getTags()).extracting(Tag::getName).containsExactlyInAnyOrderElementsOf(description.tags());

        List<DemoSet.Version> versions = description.versions();
        List<DocumentRevision> revisions = dataManager.load(DocumentRevision.class)
                .query("e.document = :document order by e.number")
                .parameter("document", document)
                .hint(PersistenceHints.SOFT_DELETION, false)
                .list();

        assertThat(revisions).hasSameSizeAs(versions);

        for (int number = 0; number < versions.size(); number++) {
            String file = versions.get(number).file();

            assertThat(revisions.get(number).getCreatedBy()).isEqualTo(versions.get(number).author());
            assertDeletion(revisions.get(number).getDeletedBy(), revisions.get(number).getDeletedDate(), deletion);
            assertThat(revisions.get(number).getCreatedDate().toInstant())
                    .isEqualTo(instantOf(versions.get(number).uploaded()));

            // a file is named after its document, without the slash that breaks the address of its preview
            assertThat(revisions.get(number).getFile().getFileName())
                    .startsWith(description.name().replace('/', '_'))
                    .endsWith(file.substring(file.lastIndexOf('.')));
        }

        assertThat(document.getCreatedDate().toInstant()).isEqualTo(instantOf(versions.getFirst().uploaded()));
        assertThat(document.getChangedDate().toInstant()).isEqualTo(instantOf(versions.getLast().uploaded()));
        assertThat(document.getChangedBy()).isEqualTo(versions.getLast().author());
        assertComments(description.comments(), document);

        return document;
    }

    // a comment has its author, text and times as described, also on a document in the trash
    private void assertComments(List<DemoSet.Comment> described, Document document) {
        List<DocumentComment> comments = dataManager.load(DocumentComment.class)
                .query("select c from DocumentComment c where c.document = :document order by c.createdDate")
                .parameter("document", document)
                .hint(PersistenceHints.SOFT_DELETION, false)
                .list();

        assertThat(comments).hasSameSizeAs(described);

        for (int number = 0; number < described.size(); number++) {
            DemoSet.Comment expected = described.get(number);
            DocumentComment comment = comments.get(number);

            assertThat(comment.getCreatedBy()).isEqualTo(expected.author());
            assertThat(comment.getText()).isEqualTo(expected.text().strip());
            assertThat(comment.getCreatedDate().toInstant()).isEqualTo(instantOf(expected.at()));
            assertThat(comment.getEditedDate() == null ? null : comment.getEditedDate().toInstant())
                    .isEqualTo(expected.edited() == null ? null : instantOf(expected.edited()));
        }
    }

    // a field holds the value the rules of the AI make of the text of the description
    private void assertFields(DemoSet.Document description, Document document) {
        if (description.type() == null) {
            return;
        }

        Document expected = dataManager.create(Document.class);
        documentClassifier.apply(new ClassificationProposal(description.type(), description.fields()
                .entrySet()
                .stream()
                .map((Map.Entry<String, String> field) -> new ClassificationProposal.Field(field.getKey(),
                        field.getValue()))
                .toList(), null, List.of()), expected);

        for (String code : description.fields().keySet()) {
            Object value = EntityValues.getValue(document, "+" + code);
            Object wanted = EntityValues.getValue(expected, "+" + code);

            if (wanted instanceof BigDecimal amount) {
                assertThat((BigDecimal) value).isEqualByComparingTo(amount);
            } else {
                assertThat(value).isEqualTo(wanted);
            }
        }
    }

    private static List<String> subjects(List<AccessEntryDraft> entries) {
        return entries.stream()
                .map(entry -> entry.getUsername() != null
                        ? "user:" + entry.getUsername() + "=" + entry.getAccessLevel()
                        : "role:" + entry.getRoleCode() + "=" + entry.getAccessLevel())
                .toList();
    }

    // a time of the history as the loader writes it: on its day after the shift of the set, in the time zone of the
    // server
    private Instant instantOf(LocalDateTime time) {
        return time.plusDays(shift)
                .atZone(ZoneId.systemDefault())
                .toInstant();
    }

    // the loader moves the history of a set by whole days, so that its last event falls on the day before the load;
    // a load that runs over midnight cannot tell which of the two days that is
    private long shiftOf(String set, LocalDate loadDay) {
        assumeTrue(loadDay.equals(today()), "the load ran over midnight");

        LocalDate lastEvent = read(set).lastEvent()
                .orElseThrow()
                .toLocalDate();

        return ChronoUnit.DAYS.between(lastEvent, loadDay.minusDays(1));
    }

    private LocalDate today() {
        return timeSource.now().toLocalDate();
    }

    // a key of the set names its status by the name the set gives it, a default status by the name it has
    private Map<String, String> statusNamesOf(DemoSet set) {
        Map<String, String> names = new HashMap<>(Map.of(
                "DRAFT", TestStatuses.draft(dataManager).getName(),
                "ACTIVE", TestStatuses.active(dataManager).getName(),
                "ARCHIVED", TestStatuses.archived(dataManager).getName()));
        set.statuses().stream()
                .filter(status -> status.name() != null)
                .forEach(status -> names.put(status.key(), status.name()));

        return names;
    }

    private static DemoSet read(String set) {
        try (InputStream content = new ClassPathResource("com/company/docai/demo/" + set + "/demo-data.json")
                .getInputStream()) {
            return JsonMapper.shared().readValue(content, DemoSet.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Visibility(UUID id, boolean byPeter) {
    }

    // the items of the trash: deleted on their own, and deleted together with a folder
    private record Trash(List<UUID> items, List<UUID> contents) {

        void add(UUID id, DemoSet.@Nullable Deletion own, DemoSet.@Nullable Deletion ofParent) {
            if (own != null) {
                items.add(id);
            } else if (ofParent != null) {
                contents.add(id);
            }
        }
    }
}
