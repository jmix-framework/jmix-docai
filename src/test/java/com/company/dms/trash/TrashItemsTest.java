package com.company.dms.trash;

import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.app.service.trash.TrashService;
import com.company.dms.model.document.Document;
import com.company.dms.model.folder.Folder;
import com.company.dms.model.trash.TrashItem;
import com.company.dms.model.user.User;
import com.company.dms.test_support.AuthenticatedAsAdmin;
import com.company.dms.test_support.TestAccess;
import com.company.dms.test_support.TestFiles;
import io.jmix.core.DataManager;
import io.jmix.core.MetadataTools;
import io.jmix.core.security.SystemAuthenticator;
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

import static com.company.dms.test_support.TestTrash.deletedEarlier;
import static com.company.dms.test_support.TestTrash.ids;
import static com.company.dms.test_support.TestTrash.itemOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the trash lists and what it shows inside a deleted folder, for the administrator and for an employee.
 */
@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class TrashItemsTest {

    @Autowired
    private TrashService trashService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private MetadataTools metadataTools;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        sandbox = folder(folderService.getRoot(), "trash-items-test-" + UUID.randomUUID());
    }

    @AfterEach
    void deleteSandbox() {
        folderService.delete(sandbox);
    }

    @Test
    void aFolderDeletedWithItsContentsIsOneItem() {
        Folder contracts = folder(sandbox, "Contracts");
        Folder leases = folder(contracts, "Leases");
        Document supply = upload(contracts, "supply.txt");
        Document lease = upload(leases, "lease.txt");

        folderService.delete(contracts);

        List<TrashItem> items = trashService.loadItems();
        TrashItem item = itemOf(items, contracts.getId());
        assertThat(item.getFolder()).isEqualTo(contracts);
        assertThat(item.getDocument()).isNull();
        assertThat(item.getName()).isEqualTo("Contracts");
        assertThat(item.getLocation()).isEqualTo(pathOf(sandbox));
        assertThat(item.getDeletedBy()).isEqualTo(nameOf("admin"));
        assertThat(item.getDeletedDate()).isNotNull();
        assertThat(ids(items)).doesNotContain(leases.getId(), supply.getId(), lease.getId());
    }

    @Test
    void aDocumentDeletedBeforeItsFolderIsAnItemOfItsOwnAndTheLastDeletedComesFirst() {
        Folder contracts = folder(sandbox, "Contracts");
        Document draft = upload(contracts, "draft.txt");
        documentService.delete(List.of(draft));
        deletedEarlier(dataManager, draft, Duration.ofHours(1));

        folderService.delete(contracts);

        List<TrashItem> items = trashService.loadItems();
        assertThat(ids(items)).containsSubsequence(contracts.getId(), draft.getId());
        assertThat(itemOf(items, draft.getId()).getDocument()).isEqualTo(draft);
        assertThat(itemOf(items, draft.getId()).getLocation()).isEqualTo(pathOf(sandbox) + " / Contracts");
    }

    @Test
    void theItemNamesTheUserWhoDeletedIt() {
        Document note = upload(sandbox, "note.txt");

        systemAuthenticator.runWithUser("peter", () -> documentService.delete(List.of(note)));

        assertThat(itemOf(trashService.loadItems(), note.getId()).getDeletedBy()).isEqualTo(nameOf("peter"));
    }

    @Test
    void anEmployeeSeesOnlyWhatTheyMayRead() {
        Folder shared = folder(sandbox, "Shared");
        // an own access without entries leaves the folder to the administrator
        Folder secret = TestAccess.giveOwnAccess(dataManager, folder(sandbox, "Secret"));
        Document note = upload(sandbox, "note.txt");
        folderService.delete(shared);
        folderService.delete(secret);
        documentService.delete(List.of(note));

        List<UUID> seenByPeter = systemAuthenticator.withUser("peter", () -> ids(trashService.loadItems()));

        assertThat(seenByPeter).contains(shared.getId(), note.getId()).doesNotContain(secret.getId());
        assertThat(ids(trashService.loadItems())).contains(secret.getId());
        assertThat(systemAuthenticator.withUser("peter", () -> trashService.loadContents(secret))).isEmpty();
    }

    @Test
    void theContentsOfAFolderAreWhatComesBackWithIt() {
        Folder contracts = folder(sandbox, "Contracts");
        Folder leases = folder(contracts, "Leases");
        upload(contracts, "supply.txt");
        upload(leases, "lease.txt");
        Document draft = upload(contracts, "draft.txt");
        documentService.delete(List.of(draft));
        deletedEarlier(dataManager, draft, Duration.ofHours(1));

        folderService.delete(contracts);

        List<TrashItem> contents = trashService.loadContents(contracts);
        assertThat(contents).extracting(TrashItem::getName).containsExactly("Leases", "supply.txt", "lease.txt");
        assertThat(contents).extracting(TrashItem::getLocation).containsExactly("", "", "Leases");
        assertThat(contents.getFirst().getFolder()).isEqualTo(leases);
    }

    @Test
    void theContentsShowOnlyWhatTheUserMayRead() {
        Folder contracts = folder(sandbox, "Contracts");
        upload(contracts, "supply.txt");
        TestAccess.giveOwnAccess(dataManager, upload(contracts, "salaries.txt"));
        folderService.delete(contracts);

        assertThat(systemAuthenticator.withUser("peter", () -> trashService.loadContents(contracts)))
                .extracting(TrashItem::getName)
                .containsExactly("supply.txt");
        assertThat(trashService.loadContents(contracts))
                .extracting(TrashItem::getName)
                .containsExactly("salaries.txt", "supply.txt");
    }

    @Test
    void aLiveFolderHasNoContentsInTheTrash() {
        upload(sandbox, "note.txt");

        assertThat(trashService.loadContents(sandbox)).isEmpty();
    }

    private String pathOf(Folder folder) {
        return metadataTools.getInstanceName(folderService.getRoot()) + " / " + folder.getName();
    }

    private String nameOf(String username) {
        return metadataTools.getInstanceName(dataManager.load(User.class)
                .query("select u from User u where u.username = :username")
                .parameter("username", username)
                .one());
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
