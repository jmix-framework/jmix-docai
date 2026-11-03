package com.company.docai.trash;

import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.app.service.trash.TrashService;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.access.AccessSet;
import com.company.docai.model.document.Document;
import com.company.docai.model.document.DocumentRevision;
import com.company.docai.model.folder.Folder;
import com.company.docai.model.trash.TrashItem;
import com.company.docai.security.role.EmployeeRole;
import com.company.docai.test_support.AuthenticatedAsAdmin;
import com.company.docai.test_support.TestAccess;
import com.company.docai.test_support.TestAccess.Grant;
import com.company.docai.test_support.TestFiles;
import com.company.docai.test_support.TestStatuses;
import io.jmix.core.DataManager;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.FetchPlan;
import io.jmix.core.Id;
import io.jmix.core.Messages;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.dynattr.AttributeType;
import io.jmix.dynattr.DynAttrQueryHints;
import io.jmix.dynattr.model.Category;
import io.jmix.dynattr.model.CategoryAttribute;
import io.jmix.dynattr.model.CategoryAttributeValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.company.docai.test_support.TestTrash.deletedEarlier;
import static com.company.docai.test_support.TestTrash.ids;
import static com.company.docai.test_support.TestTrash.isDeleted;
import static com.company.docai.test_support.TestTrash.itemOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Restoring items of the trash: what comes back together, who may restore, and when an item has to wait.
 */
@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class TrashRestoreTest {

    private static final String FIELD = "trashTestField";

    @Autowired
    private TrashService trashService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private Messages messages;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        sandbox = folder(folderService.getRoot(), "trash-restore-test-" + UUID.randomUUID());
    }

    @AfterEach
    void deleteSandbox() {
        folderService.delete(sandbox);
    }

    @Test
    void aFolderComesBackWithWhatWasDeletedWithIt() {
        Folder contracts = TestAccess.giveOwnAccess(dataManager, folder(sandbox, "Contracts"),
                Grant.role(EmployeeRole.CODE, AccessLevel.EDIT));
        Folder leases = folder(contracts, "Leases");
        Document supply = upload(contracts, "supply.txt");
        Document lease = upload(leases, "lease.txt");
        Document draft = upload(contracts, "draft.txt");
        documentService.delete(List.of(draft));
        deletedEarlier(dataManager, draft, Duration.ofHours(1));
        AccessSet ownSet = TestAccess.setOf(dataManager, contracts);
        folderService.delete(contracts);

        int restored = trashService.restore(List.of(itemOf(trashService.loadItems(), contracts.getId())));

        assertThat(restored).isEqualTo(1);
        assertThat(isDeleted(dataManager, Folder.class, contracts.getId())).isFalse();
        assertThat(isDeleted(dataManager, Folder.class, leases.getId())).isFalse();
        assertThat(isDeleted(dataManager, Document.class, supply.getId())).isFalse();
        assertThat(isDeleted(dataManager, Document.class, lease.getId())).isFalse();
        assertThat(liveRevisions(lease)).hasSize(1);
        assertThat(TestAccess.setOf(dataManager, leases)).isEqualTo(ownSet);
        // a document deleted on its own before the folder stays in the trash
        assertThat(isDeleted(dataManager, Document.class, draft.getId())).isTrue();
        assertThat(ids(trashService.loadItems())).contains(draft.getId()).doesNotContain(contracts.getId());
    }

    @Test
    void anEmployeeRestoresWhatTheyMayChangeAndOnlyThat() {
        Folder viewed = TestAccess.giveOwnAccess(dataManager, folder(sandbox, "Viewed"),
                Grant.role(EmployeeRole.CODE, AccessLevel.VIEW));
        Document note = upload(sandbox, "note.txt");
        folderService.delete(viewed);
        documentService.delete(List.of(note));
        List<TrashItem> items = trashService.loadItems();

        assertThatThrownBy(() -> systemAuthenticator.runWithUser("peter",
                () -> trashService.restore(List.of(itemOf(items, viewed.getId())))))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessageContaining("Viewed");
        assertThat(isDeleted(dataManager, Folder.class, viewed.getId())).isTrue();

        // the versions come back too, though an employee may not change a version
        int restored = systemAuthenticator.withUser("peter",
                () -> trashService.restore(List.of(itemOf(items, note.getId()))));

        assertThat(restored).isEqualTo(1);
        assertThat(liveRevisions(note)).hasSize(1);
    }

    @Test
    void aFolderComesBackWithWhatTheEmployeeMayNotSee() {
        Folder contracts = folder(sandbox, "Contracts");
        // an own access without entries leaves the document to the administrator
        Document salaries = TestAccess.giveOwnAccess(dataManager, upload(contracts, "salaries.txt"));
        folderService.delete(contracts);
        TrashItem item = itemOf(trashService.loadItems(), contracts.getId());

        int restored = systemAuthenticator.withUser("peter", () -> trashService.restore(List.of(item)));

        assertThat(restored).isEqualTo(1);
        assertThat(isDeleted(dataManager, Document.class, salaries.getId())).isFalse();
        assertThat(systemAuthenticator.withUser("peter", () -> dataManager.load(Document.class)
                .id(salaries.getId())
                .optional())).isEmpty();
    }

    @Test
    void anItemWaitsForItsFolderToComeBackFirst() {
        Folder contracts = folder(sandbox, "Contracts");
        Document draft = upload(contracts, "draft.txt");
        documentService.delete(List.of(draft));
        deletedEarlier(dataManager, draft, Duration.ofHours(1));
        folderService.delete(contracts);
        List<TrashItem> items = trashService.loadItems();

        assertThatThrownBy(() -> trashService.restore(List.of(itemOf(items, draft.getId()))))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessage(messages.formatMessage(TrashService.class, "restoreFolderFirst", "Contracts"));

        int restored = trashService.restore(List.of(itemOf(items, draft.getId()), itemOf(items, contracts.getId())));

        assertThat(restored).isEqualTo(2);
        assertThat(isDeleted(dataManager, Document.class, draft.getId())).isFalse();
    }

    @Test
    void aLiveNamesakeKeepsTheItemInTheTrash() {
        Document oldReport = upload(sandbox, "report.txt");
        Folder oldArchive = folder(sandbox, "Archive");
        documentService.delete(List.of(oldReport));
        folderService.delete(oldArchive);
        upload(sandbox, "report.txt");
        folder(sandbox, "Archive");
        List<TrashItem> items = trashService.loadItems();

        assertThatThrownBy(() -> trashService.restore(List.of(itemOf(items, oldReport.getId()))))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessage(messages.formatMessage(TrashService.class, "documentNameTaken", sandbox.getName(),
                        "report.txt"));
        assertThatThrownBy(() -> trashService.restore(List.of(itemOf(items, oldArchive.getId()))))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessage(messages.formatMessage(TrashService.class, "folderNameTaken", sandbox.getName(),
                        "Archive"));
        assertThat(isDeleted(dataManager, Document.class, oldReport.getId())).isTrue();
        assertThat(isDeleted(dataManager, Folder.class, oldArchive.getId())).isTrue();
    }

    @Test
    void twoNamesakesFromOneFolderComeBackOneAtATimeAndARejectedRestoreBringsNothingBack() {
        Document first = upload(sandbox, "report.txt");
        documentService.delete(List.of(first));
        deletedEarlier(dataManager, first, Duration.ofHours(2));
        Document second = upload(sandbox, "report.txt");
        documentService.delete(List.of(second));
        Folder clients = folder(sandbox, "Clients");
        folderService.delete(clients);
        List<TrashItem> items = trashService.loadItems();

        assertThatThrownBy(() -> trashService.restore(List.of(itemOf(items, clients.getId()),
                itemOf(items, first.getId()), itemOf(items, second.getId()))))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessage(messages.formatMessage(TrashService.class, "namesakesSelected", "report.txt",
                        sandbox.getName()));

        assertThat(isDeleted(dataManager, Folder.class, clients.getId())).isTrue();
        assertThat(isDeleted(dataManager, Document.class, first.getId())).isTrue();
        assertThat(isDeleted(dataManager, Document.class, second.getId())).isTrue();
    }

    @Test
    void anItemNoLongerInTheTrashIsSkipped() {
        Document note = upload(sandbox, "note.txt");
        documentService.delete(List.of(note));
        TrashItem item = itemOf(trashService.loadItems(), note.getId());
        trashService.restore(List.of(item));

        assertThat(trashService.restore(List.of(item))).isZero();
    }

    @Test
    void anArchivedDocumentWhoseTypeWasDeletedComesBackWithoutIt() {
        Category type = newType();
        Document document = typed(upload(sandbox, "typed.txt"), type);
        documentService.changeStatus(List.of(document), TestStatuses.active(dataManager));
        documentService.changeStatus(List.of(document), TestStatuses.archived(dataManager));
        documentService.delete(List.of(document));
        // a type that only documents in the trash are marked with can be deleted
        dataManager.remove(dataManager.load(Id.of(type)).one());

        trashService.restore(List.of(itemOf(trashService.loadItems(), document.getId())));

        Document restored = dataManager.load(Document.class)
                .id(document.getId())
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("category", FetchPlan.INSTANCE_NAME))
                .one();
        assertThat(restored.getCategory()).isNull();
        assertThat(restored.getStatus()).isEqualTo(TestStatuses.archived(dataManager));
        assertThat(dataManager.load(CategoryAttributeValue.class)
                .query("e.entity.entityId = :id")
                .parameter("id", document.getId())
                .list()).isEmpty();
    }

    // a type of its own with one field, so that the test may delete it
    private Category newType() {
        Category type = dataManager.create(Category.class);
        type.setName("Trash test type " + UUID.randomUUID());
        type.setEntityType("Document");
        Category saved = dataManager.save(type);

        CategoryAttribute field = dataManager.create(CategoryAttribute.class);
        field.setCategory(saved);
        field.setCategoryEntityType("Document");
        field.setName("Trash test field");
        field.setCode(FIELD);
        field.setDataType(AttributeType.STRING);
        field.setOrderNo(10);
        dataManager.save(field);

        return saved;
    }

    private Document typed(Document document, Category type) {
        Document edited = dataManager.load(Document.class)
                .id(document.getId())
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("folder", FetchPlan.BASE)
                        .add("category", FetchPlan.INSTANCE_NAME))
                .hint(DynAttrQueryHints.LOAD_DYN_ATTR, true)
                .one();
        edited.setCategory(type);
        EntityValues.setValue(edited, "+" + FIELD, "value");

        return documentService.save(edited, null, null);
    }

    private List<DocumentRevision> liveRevisions(Document document) {
        return dataManager.load(DocumentRevision.class)
                .query("e.document.id = :id")
                .parameter("id", document.getId())
                .list();
    }

    private Folder folder(Folder parent, String name) {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(parent);
        folder.setName(name);
        return folderService.save(folder);
    }

    private Document upload(Folder folder, String fileName) {
        return documentService.upload(folder, List.of(TestFiles.file(tempDir, fileName, "text")))
                .created()
                .getFirst();
    }
}
