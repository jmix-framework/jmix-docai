package com.company.dms.access;

import com.company.dms.app.service.OperationRejectedException;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.access.AccessEntry;
import com.company.dms.model.access.AccessLevel;
import com.company.dms.model.access.AccessSet;
import com.company.dms.model.document.Document;
import com.company.dms.model.folder.Folder;
import com.company.dms.model.user.User;
import com.company.dms.security.role.EmployeeRole;
import com.company.dms.test_support.AuthenticatedAsAdmin;
import com.company.dms.test_support.TestAccess;
import com.company.dms.test_support.TestAccess.Grant;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
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
 * The access rules that hold on every save path, also when code saves through {@code DataManager} directly.
 */
@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class AccessInvariantsTest {

    @Autowired
    private DataManager dataManager;

    @Autowired
    private FolderService folderService;

    @Autowired
    private Messages messages;

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        sandbox = folder(folderService.getRoot(), "access-invariants-test-" + UUID.randomUUID());
    }

    @AfterEach
    void deleteSandbox() {
        folderService.delete(sandbox);
    }

    @Test
    void aFolderSavedDirectlyGetsTheSetOfItsParent() {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(sandbox);
        folder.setName("Direct");

        Folder saved = dataManager.save(folder);

        assertThat(setOf(saved)).isEqualTo(setOf(sandbox));
    }

    @Test
    void aDocumentSavedDirectlyGetsTheSetOfItsFolder() {
        Document document = dataManager.create(Document.class);
        document.setFolder(sandbox);
        document.setName("direct.txt");

        Document saved = dataManager.save(document);

        assertThat(TestAccess.setOf(dataManager, saved)).isEqualTo(setOf(sandbox));
    }

    @Test
    void aFolderCannotTakeTheSetOfAnotherBranch() {
        Folder hr = TestAccess.giveOwnAccess(dataManager, folder(sandbox, "HR"),
                Grant.role(EmployeeRole.CODE, AccessLevel.MANAGE));
        Folder other = dataManager.load(Id.of(folder(sandbox, "Other"))).one();
        other.setAccessSet(setOf(hr));

        assertThatThrownBy(() -> dataManager.save(other)).isInstanceOf(OperationRejectedException.class);
    }

    @Test
    void aFolderLeavesItsOwnSetOnlyTogetherWithTheSet() {
        Folder hr = TestAccess.giveOwnAccess(dataManager, folder(sandbox, "HR"),
                Grant.role(EmployeeRole.CODE, AccessLevel.MANAGE));
        Folder stored = dataManager.load(Id.of(hr)).one();
        stored.setAccessSet(setOf(sandbox));

        assertThatThrownBy(() -> dataManager.save(stored)).isInstanceOf(OperationRejectedException.class);
    }

    @Test
    void aDocumentLeavesItsOwnSetOnlyTogetherWithTheSet() {
        Document memo = dataManager.create(Document.class);
        memo.setFolder(sandbox);
        memo.setName("memo.txt");
        Document own = TestAccess.giveOwnAccess(dataManager, dataManager.save(memo),
                Grant.role(EmployeeRole.CODE, AccessLevel.MANAGE));
        Document stored = dataManager.load(Id.of(own)).one();
        stored.setAccessSet(setOf(sandbox));

        assertThatThrownBy(() -> dataManager.save(stored)).isInstanceOf(OperationRejectedException.class);
    }

    @Test
    void anEntryIsForExactlyOneUserOrRole() {
        AccessSet accessSet = setOf(TestAccess.giveOwnAccess(dataManager, folder(sandbox, "Own"),
                Grant.role(EmployeeRole.CODE, AccessLevel.MANAGE)));

        assertThatThrownBy(() -> dataManager.save(entry(accessSet, null, null)))
                .isInstanceOf(OperationRejectedException.class);
        assertThatThrownBy(() -> dataManager.save(entry(accessSet, "peter", "auditor")))
                .isInstanceOf(OperationRejectedException.class);
    }

    @Test
    void aDuplicateEntryIsStoppedByTheConstraintWithAMessage() {
        AccessSet accessSet = setOf(TestAccess.giveOwnAccess(dataManager, folder(sandbox, "Shared"),
                Grant.user("peter", AccessLevel.VIEW)));

        assertThatThrownBy(() -> dataManager.save(entry(accessSet, "peter", null)))
                .isInstanceOfSatisfying(UniqueConstraintViolationException.class, e -> {
                    assertThat(e.getConstraintName()).isEqualTo("UK_ACCESS_ENTRY_USER");
                    assertThat(messages.findMessage("databaseUniqueConstraintViolation." + e.getConstraintName(),
                            null)).isNotNull();
                });
    }

    @Test
    void theEntriesOfADeletedUserGoAwayWithTheUser() {
        User user = dataManager.create(User.class);
        user.setUsername("access-invariants-test-" + UUID.randomUUID());
        dataManager.save(user);
        TestAccess.giveOwnAccess(dataManager, folder(sandbox, "Shared"), Grant.user(user.getUsername(), AccessLevel.VIEW));

        dataManager.remove(dataManager.load(Id.of(user)).one());

        assertThat(dataManager.load(AccessEntry.class)
                .query("e.username = ?1", user.getUsername())
                .list()).isEmpty();
    }

    private AccessEntry entry(AccessSet accessSet, String username, String roleCode) {
        AccessEntry entry = dataManager.create(AccessEntry.class);
        entry.setAccessSet(accessSet);
        entry.setUsername(username);
        entry.setRoleCode(roleCode);
        entry.setAccessLevel(AccessLevel.VIEW);
        return entry;
    }

    private AccessSet setOf(Folder folder) {
        return TestAccess.setOf(dataManager, folder);
    }

    private Folder folder(Folder parent, String name) {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(parent);
        folder.setName(name);
        return folderService.save(folder);
    }
}
