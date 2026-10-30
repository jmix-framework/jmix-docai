package com.company.docai.access;

import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.access.AccessSet;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.security.role.EmployeeRole;
import com.company.docai.test_support.AuthenticatedAsAdmin;
import com.company.docai.test_support.TestAccess;
import com.company.docai.test_support.TestAccess.Grant;
import com.company.docai.test_support.TestFiles;
import io.jmix.core.DataManager;
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

/**
 * A move carries access along: a folder or a document that inherits takes the access of its new place, together
 * with what inherits from it, deleted items included; one with its own access keeps it. The tree: "Projects/Alpha"
 * inherits from the root; "Projects/Beta" and "Closed" have their own access; "Alpha/budget.txt" has its own
 * access for helen alone.
 */
@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class AccessMoveTest {

    private static final String EMPLOYEE = "peter";
    private static final String HR_EMPLOYEE = "helen";

    @Autowired
    private DataManager dataManager;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @TempDir
    private Path tempDir;

    private Folder sandbox;
    private Folder projects;
    private Folder alpha;
    private Folder beta;
    private Folder closed;
    private Document plan;
    private Document oldPlan;
    private Document budget;

    @BeforeEach
    void createTree() {
        sandbox = folder(folderService.getRoot(), "access-move-test-" + UUID.randomUUID());
        projects = folder(sandbox, "Projects");
        alpha = folder(projects, "Alpha");
        beta = TestAccess.giveOwnAccess(dataManager, folder(projects, "Beta"),
                Grant.role(EmployeeRole.CODE, AccessLevel.EDIT));
        closed = TestAccess.giveOwnAccess(dataManager, folder(sandbox, "Closed"),
                Grant.role(EmployeeRole.CODE, AccessLevel.EDIT), Grant.user(HR_EMPLOYEE, AccessLevel.MANAGE));

        plan = upload(alpha, "plan.txt");
        oldPlan = upload(alpha, "old-plan.txt");
        documentService.delete(List.of(oldPlan));
        budget = TestAccess.giveOwnAccess(dataManager, upload(alpha, "budget.txt"),
                Grant.user(HR_EMPLOYEE, AccessLevel.EDIT));
    }

    @AfterEach
    void deleteTree() {
        folderService.delete(sandbox);
    }

    @Test
    void aMovedFolderTakesTheAccessOfItsNewParentWithWhatInheritsFromIt() {
        AccessSet betaSet = setOf(beta);
        AccessSet budgetSet = setOf(budget);

        folderService.move(projects, closed);

        assertThat(List.of(setOf(projects), setOf(alpha), setOf(plan), setOf(oldPlan))).containsOnly(setOf(closed));
        assertThat(setOf(beta)).isEqualTo(betaSet);
        assertThat(setOf(budget)).isEqualTo(budgetSet);
    }

    @Test
    void aMovedFolderWithItsOwnAccessKeepsIt() {
        AccessSet betaSet = setOf(beta);

        folderService.move(beta, closed);

        assertThat(setOf(beta)).isEqualTo(betaSet);
    }

    @Test
    void movedDocumentsTakeTheAccessOfTheirNewFolderUnlessTheyHaveTheirOwn() {
        AccessSet budgetSet = setOf(budget);

        documentService.move(List.of(plan, budget), closed);

        assertThat(setOf(plan)).isEqualTo(setOf(closed));
        assertThat(setOf(budget)).isEqualTo(budgetSet);
    }

    @Test
    void anEmployeeMovesABranchWithADocumentHiddenFromThem() {
        AccessSet budgetSet = setOf(budget);

        systemAuthenticator.runWithUser(EMPLOYEE, () -> folderService.move(projects, closed));

        assertThat(setOf(alpha)).isEqualTo(setOf(closed));
        assertThat(setOf(budget)).isEqualTo(budgetSet);
    }

    private AccessSet setOf(Folder folder) {
        return TestAccess.setOf(dataManager, folder);
    }

    private AccessSet setOf(Document document) {
        return TestAccess.setOf(dataManager, document);
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
}
