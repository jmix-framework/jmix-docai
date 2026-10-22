package com.company.dms.folder;

import com.company.dms.app.service.OperationRejectedException;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.folder.FolderContents;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.document.Document;
import com.company.dms.model.document.DocumentRevision;
import com.company.dms.model.folder.Folder;
import com.company.dms.test_support.AuthenticatedAsAdmin;
import com.company.dms.test_support.TestFiles;
import io.jmix.core.DataManager;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.data.PersistenceHints;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class FolderServiceTest {

    @Autowired
    private FolderService folderService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        sandbox = folder(folderService.getRoot(), "folder-service-test-" + UUID.randomUUID());
    }

    @AfterEach
    void deleteSandbox() {
        folderService.delete(sandbox);
    }

    @Test
    void theRootCannotBeRenamedMovedOrDeleted() {
        Folder root = folderService.getRoot();
        root.setName("Renamed");

        assertThatThrownBy(() -> folderService.save(root)).isInstanceOf(OperationRejectedException.class);
        assertThatThrownBy(() -> folderService.move(folderService.getRoot(), sandbox))
                .isInstanceOf(OperationRejectedException.class);
        assertThatThrownBy(() -> folderService.delete(folderService.getRoot()))
                .isInstanceOf(OperationRejectedException.class);
    }

    @Test
    void siblingNamesAreUniqueAndCaseSensitive() {
        folder(sandbox, "Contracts");
        folder(sandbox, "contracts");

        assertThatThrownBy(() -> folder(sandbox, " Contracts "))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessageContaining("Contracts");
    }

    @Test
    void aNameIsSavedWithoutSurroundingSpaces() {
        assertThat(folder(sandbox, "  Reports  ").getName()).isEqualTo("Reports");
    }

    @Test
    void aDeletedFolderFreesItsName() {
        folderService.delete(folder(sandbox, "Old"));

        Folder recreated = folder(sandbox, "Old");

        assertThat(recreated.getNameKey()).isEqualTo("Old");
    }

    @Test
    void aMoveChangesTheParent() {
        Folder target = folder(sandbox, "Target");
        Folder moved = folder(sandbox, "Moved");

        Folder result = folderService.move(moved, target);

        assertThat(result.getParent()).isEqualTo(target);
    }

    @Test
    void aFolderCannotMoveIntoItselfOrItsSubfolder() {
        Folder parent = folder(sandbox, "Parent");
        Folder child = folder(parent, "Child");

        assertThatThrownBy(() -> folderService.move(parent, parent)).isInstanceOf(OperationRejectedException.class);
        assertThatThrownBy(() -> folderService.move(parent, child)).isInstanceOf(OperationRejectedException.class);
    }

    @Test
    void aMoveIntoAFolderWithTheSameNameIsRejected() {
        Folder target = folder(sandbox, "Target");
        folder(target, "Reports");
        Folder reports = folder(sandbox, "Reports");

        assertThatThrownBy(() -> folderService.move(reports, target))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessageContaining("Reports");
    }

    @Test
    void aMoveIntoADeletedFolderIsRejected() {
        Folder target = folder(sandbox, "Gone");
        Folder moved = folder(sandbox, "Moved");
        folderService.delete(target);

        assertThatThrownBy(() -> folderService.move(moved, target))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessageContaining("Gone");
    }

    @Test
    void deletingAFolderTakesItsSubtree() {
        Folder parent = folder(sandbox, "Parent");
        Folder child = folder(parent, "Child");
        Document document = upload(child, "a.txt");
        UUID revisionId = document.getCurrentRevision().getId();

        folderService.delete(parent);

        assertThat(isDeleted(Folder.class, child.getId())).isTrue();
        assertThat(isDeleted(Document.class, document.getId())).isTrue();
        assertThat(isDeleted(DocumentRevision.class, revisionId)).isTrue();
    }

    @Test
    void theContentsCountCoversTheWholeSubtree() {
        Folder parent = folder(sandbox, "Parent");
        Folder child = folder(parent, "Child");
        folder(child, "Grandchild");
        upload(parent, "a.txt");
        upload(child, "b.txt");

        FolderContents contents = folderService.countContents(parent);

        assertThat(contents.subfolders()).isEqualTo(2);
        assertThat(contents.documents()).isEqualTo(2);
    }

    @Test
    void crossingMovesDoNotBuildALoop() throws Exception {
        Folder f = folder(sandbox, "F");
        Folder h = folder(f, "H");
        Folder g = folder(sandbox, "G");
        Folder t = folder(g, "T");
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // F into T and G into H: each move passes the check alone, together they would build a loop
        for (Folder[] move : List.of(new Folder[]{f, t}, new Folder[]{g, h})) {
            executor.submit(() -> {
                try {
                    start.await();
                    systemAuthenticator.runWithUser("admin", () -> folderService.move(move[0], move[1]));
                } catch (Throwable e) {
                    failures.add(e);
                }
            });
        }

        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(failures).hasSize(1);
        assertThat(failures.getFirst()).isInstanceOf(OperationRejectedException.class);
    }

    private Folder folder(Folder parent, String name) {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(parent);
        folder.setName(name);
        return folderService.save(folder);
    }

    private Document upload(Folder folder, String fileName) {
        return documentService.upload(folder, List.of(TestFiles.file(tempDir, fileName, "text")))
                .created().getFirst();
    }

    private boolean isDeleted(Class<?> entityClass, UUID id) {
        Object entity = dataManager.load(entityClass)
                .id(id)
                .hint(PersistenceHints.SOFT_DELETION, false)
                .one();

        return EntityValues.isSoftDeleted(entity);
    }
}
