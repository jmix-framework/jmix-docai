package com.company.docai.access;

import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.app.service.access.AccessLevels;
import com.company.docai.app.service.access.AccessService;
import com.company.docai.app.service.access.AccessSubject;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.access.AccessDraft;
import com.company.docai.model.access.AccessEntryDraft;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.access.AccessSet;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.security.role.EmployeeRole;
import com.company.docai.security.role.FullAccessRole;
import com.company.docai.security.role.UiMinimalRole;
import com.company.docai.test_support.AuthenticatedAsAdmin;
import com.company.docai.test_support.TestAccess;
import com.company.docai.test_support.TestAccess.DepartmentRole;
import com.company.docai.test_support.TestAccess.Grant;
import com.company.docai.test_support.TestFiles;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Id;
import io.jmix.core.Messages;
import io.jmix.core.Metadata;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the access dialog shows and how its changes reach the folders and documents that inherit.
 */
@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class AccessServiceTest {

    private static final String EMPLOYEE = "peter";
    private static final String HR_EMPLOYEE = "helen";
    private static final String OTHER_EMPLOYEE = "john";

    @Autowired
    private DataManager dataManager;

    @Autowired
    private AccessService accessService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private MetadataTools metadataTools;

    @Autowired
    private Metadata metadata;

    @Autowired
    private Messages messages;

    @TempDir
    private Path tempDir;

    private DepartmentRole hrDepartment;
    private Folder sandbox;
    private Folder hr;
    private Folder payroll;
    private Document salaries;
    private Document oldSalaries;

    @BeforeEach
    void createTree() {
        hrDepartment = DepartmentRole.assign(dataManager, HR_EMPLOYEE);

        sandbox = folder(folderService.getRoot(), "access-service-test-" + UUID.randomUUID());
        hr = folder(sandbox, "HR");
        payroll = folder(hr, "Payroll");
        salaries = upload(payroll, "salaries.txt");
        oldSalaries = upload(payroll, "old-salaries.txt");
        documentService.delete(List.of(oldSalaries));
    }

    @AfterEach
    void deleteTree() {
        folderService.delete(sandbox);
        hrDepartment.remove(dataManager);
    }

    @Test
    void anInheritingFolderShowsTheInheritedEntriesAndItsParent() {
        AccessDraft draft = accessService.describe(hr);

        assertThat(draft.getInherit()).isTrue();
        assertThat(draft.getInheritedFrom()).isEqualTo(metadataTools.getInstanceName(folderService.getRoot()));
        assertThat(subjects(draft.getInheritedEntries())).containsExactly("role:" + EmployeeRole.CODE + "=EDIT");
        // a copy to start from when the inheritance is broken
        assertThat(subjects(draft.getEntries())).containsExactly("role:" + EmployeeRole.CODE + "=EDIT");
    }

    @Test
    void theRootHasNothingToInheritFrom() {
        AccessDraft draft = accessService.describe(folderService.getRoot());

        assertThat(draft.getInherit()).isFalse();
        assertThat(draft.getInheritedFrom()).isNull();
        assertThat(draft.getInheritedEntries()).isEmpty();
    }

    @Test
    void breakingTheInheritanceMovesTheBranchWithTheTrashToTheNewSet() {
        AccessDraft draft = accessService.describe(hr);
        draft.setInherit(false);
        draft.setEntries(List.of(entry(null, hrDepartment.code(), AccessLevel.MANAGE)));

        accessService.save(draft);

        AccessSet own = setOf(hr);
        assertThat(own).isNotEqualTo(setOf(sandbox));
        assertThat(setOf(payroll)).isEqualTo(own);
        assertThat(setOf(salaries)).isEqualTo(own);
        assertThat(setOf(oldSalaries)).isEqualTo(own);
        assertThat(subjects(accessService.describe(hr).getEntries()))
                .containsExactly("role:" + hrDepartment.code() + "=MANAGE");
    }

    @Test
    void returningToTheInheritanceMovesTheBranchBackAndDeletesTheOwnSet() {
        AccessSet own = ownAccess(hr, entry(null, hrDepartment.code(), AccessLevel.MANAGE));
        AccessDraft draft = accessService.describe(hr);
        draft.setInherit(true);

        accessService.save(draft);

        assertThat(setOf(hr)).isEqualTo(setOf(sandbox));
        assertThat(dataManager.load(Id.of(own)).optional()).isEmpty();
    }

    @Test
    void breakingTheInheritanceOfADocumentGivesItASetOfItsOwn() {
        AccessDraft folderDraft = accessService.describe(payroll);
        AccessDraft draft = accessService.describe(salaries);
        draft.setInherit(false);
        draft.setEntries(List.of(entry(HR_EMPLOYEE, null, AccessLevel.MANAGE)));

        accessService.save(draft);

        AccessSet own = setOf(salaries);
        assertThat(own).isNotEqualTo(setOf(payroll));
        assertThat(dataManager.load(Id.of(own))
                .fetchPlan(fetchPlan -> fetchPlan.add("ownerDocument", FetchPlan.INSTANCE_NAME))
                .one()
                .getOwnerDocument()).isEqualTo(salaries);
        assertThat(subjects(accessService.describe(salaries).getEntries()))
                .containsExactly("user:" + HR_EMPLOYEE + "=MANAGE");
        // the set of the folder stays as it was, so a dialog opened for the folder still saves
        assertThat(accessService.describe(payroll).getFingerprint()).isEqualTo(folderDraft.getFingerprint());
    }

    @Test
    void returningADocumentToTheInheritanceDeletesItsOwnSet() {
        AccessSet own = ownAccess(salaries, entry(HR_EMPLOYEE, null, AccessLevel.MANAGE));
        AccessDraft draft = accessService.describe(salaries);
        draft.setInherit(true);

        accessService.save(draft);

        assertThat(setOf(salaries)).isEqualTo(setOf(payroll));
        assertThat(dataManager.load(Id.of(own)).optional()).isEmpty();
    }

    @Test
    void anOwnSetGetsTheDifferenceOfTheEntries() {
        ownAccess(hr, entry(null, hrDepartment.code(), AccessLevel.MANAGE),
                entry(EMPLOYEE, null, AccessLevel.VIEW), entry(null, EmployeeRole.CODE, AccessLevel.VIEW));
        AccessDraft draft = accessService.describe(hr);
        draft.setEntries(List.of(
                entry(null, hrDepartment.code(), AccessLevel.MANAGE),
                entry(EMPLOYEE, null, AccessLevel.EDIT),
                entry("maria", null, AccessLevel.VIEW)));

        accessService.save(draft);

        assertThat(subjects(accessService.describe(hr).getEntries())).containsExactlyInAnyOrder(
                "role:" + hrDepartment.code() + "=MANAGE", "user:" + EMPLOYEE + "=EDIT", "user:maria=VIEW");
    }

    @Test
    void aDialogOpenedBeforeAnotherChangeCannotOverwriteIt() {
        ownAccess(hr, entry(null, hrDepartment.code(), AccessLevel.MANAGE));
        AccessDraft first = accessService.describe(hr);
        AccessDraft second = accessService.describe(hr);
        first.setEntries(List.of(entry(null, hrDepartment.code(), AccessLevel.MANAGE),
                entry(EMPLOYEE, null, AccessLevel.VIEW)));
        accessService.save(first);

        second.setEntries(List.of(entry(null, hrDepartment.code(), AccessLevel.MANAGE)));

        assertThatThrownBy(() -> accessService.save(second)).isInstanceOf(OperationRejectedException.class);
    }

    @Test
    void onlyAManagerOpensAndSavesTheAccess() {
        ownAccess(hr, entry(null, hrDepartment.code(), AccessLevel.MANAGE),
                entry(null, EmployeeRole.CODE, AccessLevel.EDIT));
        AccessDraft draft = accessService.describe(hr);

        assertThatThrownBy(() -> systemAuthenticator.runWithUser(EMPLOYEE, () -> accessService.describe(hr)))
                .isInstanceOf(OperationRejectedException.class);
        assertThatThrownBy(() -> systemAuthenticator.runWithUser(EMPLOYEE, () -> accessService.save(draft)))
                .isInstanceOf(OperationRejectedException.class);
        assertThat(systemAuthenticator.withUser(HR_EMPLOYEE, () -> accessService.describe(hr))).isNotNull();
    }

    @Test
    void aHiddenFolderIsUnavailable() {
        ownAccess(hr, entry(null, hrDepartment.code(), AccessLevel.MANAGE));

        assertThatThrownBy(() -> systemAuthenticator.runWithUser(EMPLOYEE, () -> accessService.describe(hr)))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessageContaining("HR");
    }

    @Test
    void anEntryOfADeletedRoleStaysAndIsNamedSo() {
        String code = "deleted-role-" + UUID.randomUUID();
        ownAccess(hr, entry(null, hrDepartment.code(), AccessLevel.MANAGE), entry(null, code, AccessLevel.VIEW));

        assertThat(accessService.describe(hr).getEntries())
                .extracting(AccessEntryDraft::getSubjectName)
                .contains(messages.formatMessage(AccessService.class, "roleNotFound", code));
    }

    @Test
    void aSubjectListedTwiceIsRejected() {
        AccessDraft draft = accessService.describe(hr);
        draft.setInherit(false);
        draft.setEntries(List.of(entry(EMPLOYEE, null, AccessLevel.VIEW), entry(EMPLOYEE, null, AccessLevel.EDIT)));

        assertThatThrownBy(() -> accessService.save(draft)).isInstanceOf(OperationRejectedException.class);
    }

    @Test
    void anEntryWithoutALevelIsRejectedWithItsSubject() {
        AccessDraft draft = accessService.describe(hr);
        draft.setInherit(false);
        draft.setEntries(List.of(entry(EMPLOYEE, null, null)));

        assertThatThrownBy(() -> accessService.save(draft))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessageContaining(EMPLOYEE);
    }

    @Test
    void aTechnicalRoleGetsNoAccess() {
        AccessDraft draft = accessService.describe(hr);
        draft.setInherit(false);
        draft.setEntries(List.of(entry(null, FullAccessRole.CODE, AccessLevel.VIEW)));

        assertThatThrownBy(() -> accessService.save(draft))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessageContaining(FullAccessRole.CODE);
        assertThat(accessService.describe(hr).getInherit()).isTrue();
    }

    @Test
    void aManagerIsWarnedBeforeTheyTakeTheManagementFromThemselves() {
        ownAccess(hr, entry(null, hrDepartment.code(), AccessLevel.MANAGE));

        systemAuthenticator.runWithUser(HR_EMPLOYEE, () -> {
            AccessDraft draft = accessService.describe(hr);
            assertThat(accessService.keepsManagement(draft)).isTrue();

            draft.setEntries(List.of(entry(null, hrDepartment.code(), AccessLevel.EDIT)));
            assertThat(accessService.keepsManagement(draft)).isFalse();

            draft.setInherit(true);
            assertThat(accessService.keepsManagement(draft)).isFalse();
        });

        assertThat(accessService.keepsManagement(accessService.describe(hr))).isTrue();
    }

    @Test
    void aManagerOfADocumentIsWarnedBeforeTheyTakeTheManagementFromThemselves() {
        ownAccess(salaries, entry(HR_EMPLOYEE, null, AccessLevel.MANAGE));

        systemAuthenticator.runWithUser(HR_EMPLOYEE, () -> {
            AccessDraft draft = accessService.describe(salaries);
            assertThat(accessService.keepsManagement(draft)).isTrue();

            draft.setEntries(List.of(entry(HR_EMPLOYEE, null, AccessLevel.VIEW)));
            assertThat(accessService.keepsManagement(draft)).isFalse();
        });
    }

    @Test
    void eachUserHasTheHighestLevelOfTheirEntriesBySet() {
        AccessSet own = ownAccess(hr, entry(null, hrDepartment.code(), AccessLevel.MANAGE),
                entry(HR_EMPLOYEE, null, AccessLevel.VIEW));
        AccessSet open = setOf(sandbox);

        AccessLevels helen = systemAuthenticator.withUser(HR_EMPLOYEE, () -> accessService.levels());
        AccessLevels peter = systemAuthenticator.withUser(EMPLOYEE, () -> accessService.levels());

        assertThat(helen.of(own)).isEqualTo(AccessLevel.MANAGE);
        assertThat(helen.of(open)).isEqualTo(AccessLevel.EDIT);
        assertThat(peter.of(own)).isNull();
        assertThat(peter.allows(open, AccessLevel.EDIT)).isTrue();
        assertThat(accessService.levels().of(own)).isEqualTo(AccessLevel.MANAGE);
    }

    @Test
    void managingOnlyADeletedFolderIsManagingNothing() {
        Folder shared = folder(sandbox, "Shared");
        ownAccess(shared, entry(OTHER_EMPLOYEE, null, AccessLevel.MANAGE));
        assertThat(systemAuthenticator.withUser(OTHER_EMPLOYEE, () -> accessService.managesAnything())).isTrue();

        folderService.delete(shared);

        assertThat(systemAuthenticator.withUser(OTHER_EMPLOYEE, () -> accessService.managesAnything())).isFalse();
        assertThat(accessService.managesAnything()).isTrue();
    }

    @Test
    void theSubjectsAreRolesAndUsersWithoutTheTechnicalRoles() {
        List<AccessSubject> subjects = accessService.findSubjects("", 0, 1000);

        assertThat(subjects).extracting(AccessSubject::roleCode)
                .contains(EmployeeRole.CODE, hrDepartment.code())
                .doesNotContain(FullAccessRole.CODE, UiMinimalRole.CODE);
        assertThat(subjects).extracting(AccessSubject::username).contains(EMPLOYEE, HR_EMPLOYEE);
        assertThat(subjects.getFirst().isRole()).isTrue();
    }

    @Test
    void theSubjectsAreFoundByAPartOfTheNameOnePageAtATime() {
        List<AccessSubject> all = accessService.findSubjects("", 0, 1000);
        int roles = (int) all.stream().filter(AccessSubject::isRole).count();

        assertThat(accessService.findSubjects("HEL", 0, 50)).extracting(AccessSubject::username)
                .contains(HR_EMPLOYEE)
                .doesNotContain(EMPLOYEE);
        assertThat(accessService.findSubjects("", 1, 2)).isEqualTo(all.subList(1, 3));
        // the last role and the first user share a page
        assertThat(accessService.findSubjects("", roles - 1, 2)).isEqualTo(all.subList(roles - 1, roles + 1));
        // a percent sign is a character to find, not a wildcard
        assertThat(accessService.findSubjects("%", 0, 50)).isEmpty();
    }

    @Test
    void aMoveChangesTheAccessOnlyOfWhatInheritsIntoAnotherSet() {
        Folder closed = TestAccess.giveOwnAccess(dataManager, folder(sandbox, "Closed"),
                Grant.role(hrDepartment.code(), AccessLevel.MANAGE));
        Folder ownAccess = TestAccess.giveOwnAccess(dataManager, folder(sandbox, "Own"),
                Grant.role(EmployeeRole.CODE, AccessLevel.EDIT));

        assertThat(accessService.changesOnMove(payroll, closed)).isTrue();
        assertThat(accessService.changesOnMove(payroll, sandbox)).isFalse();
        assertThat(accessService.changesOnMove(ownAccess, closed)).isFalse();
        assertThat(accessService.changesOnMove(List.of(salaries), closed)).isTrue();
    }

    @Test
    void aDocumentDeletedBeforeTheMoveIsLeftOutOfTheAccessCheck() {
        Folder closed = TestAccess.giveOwnAccess(dataManager, folder(sandbox, "Closed"),
                Grant.role(hrDepartment.code(), AccessLevel.MANAGE));

        assertThat(accessService.changesOnMove(List.of(oldSalaries), closed)).isFalse();
        assertThat(accessService.changesOnMove(List.of(oldSalaries, salaries), closed)).isTrue();
    }

    /**
     * Gives the folder its own access through the service, so that its branch follows it.
     */
    private AccessSet ownAccess(Folder folder, AccessEntryDraft... entries) {
        AccessDraft draft = accessService.describe(folder);
        draft.setInherit(false);
        draft.setEntries(List.of(entries));
        accessService.save(draft);
        return setOf(folder);
    }

    /**
     * Gives the document its own access through the service.
     */
    private AccessSet ownAccess(Document document, AccessEntryDraft... entries) {
        AccessDraft draft = accessService.describe(document);
        draft.setInherit(false);
        draft.setEntries(List.of(entries));
        accessService.save(draft);
        return setOf(document);
    }

    private AccessEntryDraft entry(String username, String roleCode, AccessLevel level) {
        AccessEntryDraft entry = metadata.create(AccessEntryDraft.class);
        entry.setUsername(username);
        entry.setRoleCode(roleCode);
        entry.setSubjectName(username != null ? username : roleCode);
        entry.setAccessLevel(level);
        return entry;
    }

    private static List<String> subjects(List<AccessEntryDraft> entries) {
        return entries.stream()
                .map(entry -> (entry.getUsername() != null ? "user:" + entry.getUsername() : "role:" + entry.getRoleCode())
                        + "=" + entry.getAccessLevel())
                .toList();
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
        return documentService.upload(folder, List.of(TestFiles.file(tempDir, fileName, "text"))).created().getFirst();
    }
}
