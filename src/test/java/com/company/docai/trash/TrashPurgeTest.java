package com.company.docai.trash;

import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.document.NewVersion;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.app.service.trash.TrashService;
import com.company.docai.model.access.AccessEntry;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.access.AccessSet;
import com.company.docai.model.document.Document;
import com.company.docai.model.document.DocumentRevision;
import com.company.docai.model.document.DocumentStatus;
import com.company.docai.model.folder.Folder;
import com.company.docai.model.trash.TrashItem;
import com.company.docai.test_support.AuthenticatedAsAdmin;
import com.company.docai.test_support.TestAccess;
import com.company.docai.test_support.TestAccess.Grant;
import com.company.docai.test_support.TestFiles;
import io.jmix.core.DataManager;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.FetchPlan;
import io.jmix.core.FileRef;
import io.jmix.core.FileStorageLocator;
import io.jmix.core.Messages;
import io.jmix.core.SaveContext;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.data.PersistenceHints;
import io.jmix.dynattr.DynAttrQueryHints;
import io.jmix.dynattr.model.Category;
import io.jmix.dynattr.model.CategoryAttributeValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.company.docai.test_support.TestTrash.deletedEarlier;
import static com.company.docai.test_support.TestTrash.exists;
import static com.company.docai.test_support.TestTrash.isDeleted;
import static com.company.docai.test_support.TestTrash.itemOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deleting items of the trash forever and emptying it: who may, what goes and what stays.
 */
@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class TrashPurgeTest {

    @Autowired
    private TrashService trashService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private FileStorageLocator fileStorageLocator;

    @Autowired
    private Messages messages;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Value("${jmix.localfs.storage-dir}")
    private String storageDir;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        sandbox = folder(folderService.getRoot(), "trash-purge-test-" + UUID.randomUUID());
    }

    @AfterEach
    void deleteSandbox() {
        folderService.delete(sandbox);
    }

    @Test
    void onlyTheAdministratorDeletesForever() {
        Document note = upload(sandbox, "note.txt");
        documentService.delete(List.of(note));
        TrashItem item = itemOf(trashService.loadItems(), note.getId());

        assertThat(trashService.canPurge()).isTrue();
        assertThat(systemAuthenticator.withUser("peter", trashService::canPurge)).isFalse();
        assertThatThrownBy(() -> systemAuthenticator.runWithUser("peter", () -> trashService.purge(List.of(item))))
                .isInstanceOf(OperationRejectedException.class);
        assertThat(exists(dataManager, Document.class, note.getId())).isTrue();
    }

    @Test
    void aFolderDeletedForeverLeavesNeitherRowsNorFiles() {
        Folder contracts = TestAccess.giveOwnAccess(dataManager, folder(sandbox, "Contracts"),
                Grant.user("peter", AccessLevel.EDIT));
        Folder leases = folder(contracts, "Leases");
        Document supply = upload(contracts, "supply.txt");
        documentService.uploadRevision(supply, TestFiles.file(tempDir, "supply-v2.txt", "second"));
        Document invoice = asInvoice(upload(contracts, "invoice.txt"));
        documentService.changeStatus(List.of(invoice), DocumentStatus.ACTIVE);
        documentService.changeStatus(List.of(invoice), DocumentStatus.ARCHIVED);
        Document lease = TestAccess.giveOwnAccess(dataManager, upload(leases, "lease.txt"),
                Grant.user("alice", AccessLevel.VIEW));
        Document draft = upload(contracts, "draft.txt");
        documentService.delete(List.of(draft));
        deletedEarlier(dataManager, draft, Duration.ofHours(1));

        List<Document> documents = List.of(supply, invoice, lease, draft);
        List<FileRef> files = filesOf(documents);
        List<AccessSet> ownSets = List.of(TestAccess.setOf(dataManager, contracts),
                TestAccess.setOf(dataManager, lease));
        folderService.delete(contracts);

        trashService.purge(List.of(itemOf(trashService.loadItems(), contracts.getId())));

        assertThat(exists(dataManager, Folder.class, contracts.getId())).isFalse();
        assertThat(exists(dataManager, Folder.class, leases.getId())).isFalse();
        documents.forEach(document -> assertThat(exists(dataManager, Document.class, document.getId())).isFalse());
        assertThat(rows(DocumentRevision.class, "e.document.id in :ids", ids(documents))).isZero();
        assertThat(rows(CategoryAttributeValue.class, "e.entity.entityId in :ids", ids(documents))).isZero();
        assertThat(rows(AccessSet.class, "e.id in :ids", ownSets.stream().map(AccessSet::getId).toList())).isZero();
        assertThat(rows(AccessEntry.class, "e.accessSet.id in :ids", ownSets.stream().map(AccessSet::getId).toList()))
                .isZero();
        assertThat(files).isNotEmpty()
                .noneMatch(file -> fileStorageLocator.getByName(file.getStorageName()).fileExists(file));
    }

    @Test
    void aFileThatAnotherVersionRefersToStays() {
        Document original = upload(sandbox, "original.txt");
        Document other = upload(sandbox, "other.txt");
        FileRef file = filesOf(List.of(original)).getFirst();
        // a version of another document with the same file, as a version brought back from an older one has it
        DocumentRevision sharing = dataManager.create(DocumentRevision.class);
        sharing.setDocument(other);
        sharing.setNumber(2);
        sharing.setFile(file);
        sharing.setFileSize(4L);
        sharing.setContentType("text/plain");
        dataManager.save(sharing);
        documentService.delete(List.of(original));

        trashService.purge(List.of(itemOf(trashService.loadItems(), original.getId())));

        assertThat(exists(dataManager, Document.class, original.getId())).isFalse();
        assertThat(fileStorageLocator.getByName(file.getStorageName()).fileExists(file)).isTrue();
    }

    @Test
    void aFileThatTwoVersionsOfTheDocumentShareGoesOnce() {
        Document lease = upload(sandbox, "lease.txt");
        DocumentRevision first = currentRevision(lease);
        documentService.uploadRevision(lease, TestFiles.file(tempDir, "lease-v2.txt", "second"));
        // a version brought back from the first one has its file
        documentService.save(withCurrentRevision(lease), new NewVersion.FromRevision(first), null);
        documentService.delete(List.of(lease));

        trashService.purge(List.of(itemOf(trashService.loadItems(), lease.getId())));

        assertThat(exists(dataManager, Document.class, lease.getId())).isFalse();
        assertThat(fileStorageLocator.getByName(first.getFile().getStorageName()).fileExists(first.getFile()))
                .isFalse();
    }

    @Test
    void aFileThatCannotBeRemovedLeavesTheDeletionDone() throws IOException {
        Document note = upload(sandbox, "note.txt");
        Path file = Path.of(storageDir).resolve(filesOf(List.of(note)).getFirst().getPath());
        // a directory with something inside cannot be removed as a file
        Files.delete(file);
        Path blocker = Files.createDirectories(file.resolve("blocker"));
        documentService.delete(List.of(note));

        try {
            trashService.purge(List.of(itemOf(trashService.loadItems(), note.getId())));

            assertThat(exists(dataManager, Document.class, note.getId())).isFalse();
        } finally {
            Files.delete(blocker);
            Files.delete(file);
        }
    }

    @Test
    void aFolderWithALiveFolderBelowItStaysInTheTrash() {
        Folder contracts = folder(sandbox, "Contracts");
        Folder leases = folder(contracts, "Leases");
        folderService.delete(contracts);
        bringBack(Folder.class, leases.getId());

        try {
            assertThatThrownBy(() -> trashService.purge(List.of(itemOf(trashService.loadItems(),
                    contracts.getId()))))
                    .isInstanceOf(OperationRejectedException.class)
                    .hasMessage(messages.formatMessage(TrashService.class, "purgeLiveContent", "Contracts"));
            assertThat(isDeleted(dataManager, Folder.class, contracts.getId())).isTrue();
            assertThat(isDeleted(dataManager, Folder.class, leases.getId())).isFalse();
        } finally {
            // the trash of the shared database stays one that can be emptied
            folderService.delete(leases);
        }
    }

    @Test
    void aFolderWithALiveDocumentBelowItStaysInTheTrash() {
        Folder contracts = folder(sandbox, "Contracts");
        Folder leases = folder(contracts, "Leases");
        Document lease = upload(leases, "lease.txt");
        folderService.delete(contracts);
        bringBack(Document.class, lease.getId());

        try {
            assertThatThrownBy(() -> trashService.purge(List.of(itemOf(trashService.loadItems(),
                    contracts.getId()))))
                    .isInstanceOf(OperationRejectedException.class)
                    .hasMessage(messages.formatMessage(TrashService.class, "purgeLiveContent", "Contracts"));
            assertThat(exists(dataManager, Folder.class, leases.getId())).isTrue();
            assertThat(isDeleted(dataManager, Document.class, lease.getId())).isFalse();
        } finally {
            // the trash of the shared database stays one that can be emptied
            documentService.delete(List.of(lease));
        }
    }

    @Test
    void anItemRestoredMeanwhileStays() {
        Document note = upload(sandbox, "note.txt");
        documentService.delete(List.of(note));
        TrashItem item = itemOf(trashService.loadItems(), note.getId());
        trashService.restore(List.of(item));

        trashService.purge(List.of(item));

        assertThat(isDeleted(dataManager, Document.class, note.getId())).isFalse();
        assertThat(exists(dataManager, Document.class, note.getId())).isTrue();
    }

    // an invoice with its number, so that the document has values of fields
    private Document asInvoice(Document document) {
        Document edited = dataManager.load(Document.class)
                .id(document.getId())
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("folder", FetchPlan.BASE)
                        .add("category", FetchPlan.INSTANCE_NAME))
                .hint(DynAttrQueryHints.LOAD_DYN_ATTR, true)
                .one();
        edited.setCategory(dataManager.load(Category.class)
                .query("select c from dynat_Category c where c.name = 'Invoice' and c.entityType = 'Document'")
                .one());
        EntityValues.setValue(edited, "+invoiceNumber", "2045");

        Document saved = documentService.save(edited, null, null);
        assertThat(rows(CategoryAttributeValue.class, "e.entity.entityId in :ids", List.of(saved.getId())))
                .isPositive();

        return saved;
    }

    // a row out of the trash past the services, as a race of a delete or the entity inspector of Data Tools leaves it
    private void bringBack(Class<?> type, UUID id) {
        Object row = dataManager.load(type)
                .id(id)
                .hint(PersistenceHints.SOFT_DELETION, false)
                .one();
        EntityValues.setDeletedDate(row, null);
        EntityValues.setDeletedBy(row, null);

        dataManager.save(new SaveContext()
                .saving(row)
                .setHint(PersistenceHints.SOFT_DELETION, false));
    }

    private DocumentRevision currentRevision(Document document) {
        return withCurrentRevision(document).getCurrentRevision();
    }

    private Document withCurrentRevision(Document document) {
        return dataManager.load(Document.class)
                .id(document.getId())
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("folder", FetchPlan.BASE)
                        .add("currentRevision", FetchPlan.BASE))
                .one();
    }

    private List<FileRef> filesOf(List<Document> documents) {
        return dataManager.load(DocumentRevision.class)
                .query("e.document.id in :ids")
                .parameter("ids", ids(documents))
                .hint(PersistenceHints.SOFT_DELETION, false)
                .list()
                .stream()
                .map(DocumentRevision::getFile)
                .toList();
    }

    private long rows(Class<?> type, String condition, List<UUID> ids) {
        return dataManager.load(type)
                .query(condition)
                .parameter("ids", ids)
                .hint(PersistenceHints.SOFT_DELETION, false)
                .list()
                .size();
    }

    private static List<UUID> ids(List<Document> documents) {
        List<UUID> ids = new ArrayList<>();
        documents.forEach(document -> ids.add(document.getId()));

        return ids;
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
