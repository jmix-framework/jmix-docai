package com.company.dms.folder;

import com.company.dms.app.service.OperationRejectedException;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.folder.Folder;
import com.company.dms.test_support.AuthenticatedAsAdmin;
import com.company.dms.test_support.TestAccess;
import io.jmix.core.DataManager;
import io.jmix.core.Messages;
import io.jmix.data.exception.UniqueConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The folder rules hold when code saves through DataManager directly, bypassing the service.
 */
@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class FolderInvariantsTest {

    @Autowired
    private DataManager dataManager;

    @Autowired
    private FolderService folderService;

    @Autowired
    private Messages messages;

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(folderService.getRoot());
        folder.setName("folder-invariants-test-" + UUID.randomUUID());
        sandbox = folderService.save(folder);
    }

    @AfterEach
    void deleteSandbox() {
        folderService.delete(sandbox);
    }

    @Test
    void aDuplicateNameIsStoppedByTheConstraintWithAMessage() {
        Folder first = dataManager.create(Folder.class);
        first.setParent(sandbox);
        first.setName("Twin");
        dataManager.save(first);
        Folder second = dataManager.create(Folder.class);
        second.setParent(sandbox);
        second.setName("Twin");

        assertThatThrownBy(() -> dataManager.save(second))
                .isInstanceOfSatisfying(UniqueConstraintViolationException.class, e -> assertThat(
                        messages.findMessage("databaseUniqueConstraintViolation." + e.getConstraintName(), null))
                        .isNotNull());
    }

    @Test
    void theRootCannotBeRenamedDirectly() {
        Folder root = folderService.getRoot();
        root.setName("Renamed");

        assertThatThrownBy(() -> dataManager.save(root)).isInstanceOf(OperationRejectedException.class);
    }

    @Test
    void theRootCannotBeDeleted() {
        assertThatThrownBy(() -> dataManager.remove(folderService.getRoot()))
                .isInstanceOf(OperationRejectedException.class);
    }

    @Test
    void onlyTheRootHasNoParent() {
        Folder orphan = dataManager.create(Folder.class);
        // without a parent there is no set to inherit, and the folder would fail on the column first
        orphan.setAccessSet(TestAccess.setOf(dataManager, sandbox));
        orphan.setName("orphan-" + UUID.randomUUID());

        assertThatThrownBy(() -> dataManager.save(orphan)).isInstanceOf(OperationRejectedException.class);
    }
}
