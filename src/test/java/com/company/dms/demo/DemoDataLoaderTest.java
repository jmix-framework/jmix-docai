package com.company.dms.demo;

import com.company.dms.ai.classification.ClassificationProposal;
import com.company.dms.ai.classification.DocumentClassifier;
import com.company.dms.app.service.OperationRejectedException;
import com.company.dms.app.service.access.AccessService;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.app.service.trash.TrashService;
import com.company.dms.model.access.AccessDraft;
import com.company.dms.model.access.AccessEntryDraft;
import com.company.dms.model.document.Document;
import com.company.dms.model.document.DocumentRevision;
import com.company.dms.model.document.DocumentStatus;
import com.company.dms.model.folder.Folder;
import com.company.dms.model.tag.Tag;
import com.company.dms.security.role.EmployeeRole;
import com.company.dms.test_support.AuthenticatedAsAdmin;
import com.company.dms.test_support.TestTrash;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
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
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Loads the sets into a database of their own and compares the database with the descriptions, so the test names
 * no folder or document of a set.
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
        assertThat(loader.loadIfEmpty("sample")).isEqualTo(DemoDataLoader.Outcome.LOADED);
        assertThat(loader.loadIfEmpty("sample")).isEqualTo(DemoDataLoader.Outcome.NOT_EMPTY);

        assertLoadedAsDescribed("sample");
    }

    @Test
    @Order(3)
    void theRussianSetLoadsAsItsDescriptionSays() {
        assertThat(loader.check("ru")).isEmpty();

        loader.load("ru");

        assertLoadedAsDescribed("ru");
    }

    @Test
    @Order(4)
    void theEnglishSetLoadsAsItsDescriptionSays() {
        assertThat(loader.check("en")).isEmpty();

        loader.load("en");

        assertLoadedAsDescribed("en");
    }

    private void assertLoadedAsDescribed(String set) {
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

    private static void assertDeletion(@Nullable String deletedBy, @Nullable OffsetDateTime deletedDate,
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
                        .add("category", FetchPlan.BASE))
                .hint(DynAttrQueryHints.LOAD_DYN_ATTR, true)
                .hint(PersistenceHints.SOFT_DELETION, false)
                .one();

        assertDeletion(document.getDeletedBy(), document.getDeletedDate(), deletion);

        assertThat(document.getDocumentDate()).isEqualTo(description.date());
        assertThat(document.getStatus())
                .isEqualTo(description.status() == null
                        ? DocumentStatus.DRAFT
                        : DocumentStatus.valueOf(description.status()));
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

        return document;
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

    private static Instant instantOf(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant();
    }

    private static DemoSet read(String set) {
        try (InputStream content = new ClassPathResource("com/company/dms/demo/" + set + "/demo-data.json")
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
