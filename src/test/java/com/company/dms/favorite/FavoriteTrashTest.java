package com.company.dms.favorite;

import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.favorite.FavoriteService;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.app.service.trash.TrashService;
import com.company.dms.model.document.Document;
import com.company.dms.model.favorite.Favorite;
import com.company.dms.model.favorite.FavoriteItem;
import com.company.dms.model.folder.Folder;
import com.company.dms.model.trash.TrashItem;
import com.company.dms.test_support.AuthenticatedAsAdmin;
import com.company.dms.test_support.TestFiles;
import com.company.dms.test_support.TestTrash;
import io.jmix.core.DataManager;
import io.jmix.core.SaveContext;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class FavoriteTrashTest {

    @Autowired
    private FavoriteService favoriteService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private TrashService trashService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        sandbox = folder(folderService.getRoot(), "favorite-trash-test-" + UUID.randomUUID());
    }

    // no other test keeps favorites, so the marks of every user go
    @AfterEach
    void deleteSandbox() {
        unconstrainedDataManager.save(new SaveContext()
                .removing(unconstrainedDataManager.load(Favorite.class).all().list()));
        folderService.delete(sandbox);
    }

    @Test
    void aMarkOfADocumentInTheTrashHidesAndComesBackWithTheRestore() {
        Document lease = upload(sandbox, "lease.txt");
        favoriteService.mark(List.of(lease));

        documentService.delete(List.of(lease));
        assertThat(itemIds()).isEmpty();

        trashService.restore(List.of(TestTrash.itemOf(trashService.loadItems(), lease.getId())));
        assertThat(itemIds()).containsExactly(lease.getId());
    }

    // the block was shown before the document went to the trash, and the star of its row still removes the mark
    @Test
    void theMarkOfADocumentThatWentToTheTrashIsStillRemoved() {
        Document lease = upload(sandbox, "lease.txt");
        favoriteService.mark(List.of(lease));
        documentService.delete(List.of(lease));

        favoriteService.unmark(List.of(lease));

        assertThat(unconstrainedDataManager.load(Favorite.class).all().list()).isEmpty();
    }

    @Test
    void theMarksInsideADeletedFolderHideAndComeBackWithIt() {
        Folder contracts = folder(sandbox, "Contracts");
        Folder archive = folder(contracts, "Archive");
        Document lease = upload(archive, "lease.txt");
        favoriteService.mark(List.of(archive, lease));

        folderService.delete(contracts);
        assertThat(itemIds()).isEmpty();

        trashService.restore(List.of(TestTrash.itemOf(trashService.loadItems(), contracts.getId())));
        assertThat(itemIds()).containsExactly(archive.getId(), lease.getId());
    }

    @Test
    void deletingForeverRemovesTheMarksOfEveryoneOnWhatGoes() {
        Folder contracts = folder(sandbox, "Contracts");
        Folder archive = folder(contracts, "Archive");
        Document lease = upload(archive, "lease.txt");
        Document act = upload(sandbox, "act.txt");
        favoriteService.mark(List.of(contracts, archive, lease, act));
        systemAuthenticator.runWithUser("peter", () -> favoriteService.mark(List.of(archive, lease, act)));

        folderService.delete(contracts);
        documentService.delete(List.of(act));
        List<TrashItem> items = trashService.loadItems();
        trashService.purge(List.of(TestTrash.itemOf(items, contracts.getId()), TestTrash.itemOf(items, act.getId())));

        assertThat(unconstrainedDataManager.load(Favorite.class).all().list()).isEmpty();
    }

    private List<UUID> itemIds() {
        return favoriteService.loadItems().stream()
                .map(FavoriteItem::getId)
                .toList();
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
