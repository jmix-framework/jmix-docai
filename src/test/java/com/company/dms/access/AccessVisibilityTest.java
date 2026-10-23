package com.company.dms.access;

import com.company.dms.app.service.access.AccessChecker;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.app.service.folder.FolderTree;
import com.company.dms.model.access.AccessEntry;
import com.company.dms.model.access.AccessLevel;
import com.company.dms.model.access.AccessSet;
import com.company.dms.model.document.Document;
import com.company.dms.model.document.DocumentRevision;
import com.company.dms.model.folder.Folder;
import com.company.dms.model.user.User;
import com.company.dms.security.role.EmployeeRole;
import com.company.dms.test_support.AuthenticatedAsAdmin;
import com.company.dms.test_support.TestAccess;
import com.company.dms.test_support.TestAccess.DepartmentRole;
import com.company.dms.test_support.TestAccess.Grant;
import com.company.dms.test_support.TestFiles;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Id;
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
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who sees what, on the tree of the design: the root is open; "Contracts" inherits from it; "HR" has its own
 * access, managed by the HR department, and "HR/Payroll" and "HR/Archive" inherit it; "HR/Vacations" has its own
 * access, viewed by all employees; "Contracts/secret.txt" is for helen alone; "HR/policy.txt" is viewed by all
 * employees inside the closed "HR"; "HR/Payroll/payslip.txt" is viewed by peter alone. peter is an employee,
 * helen is an employee in the HR department, admin has full access.
 */
@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class AccessVisibilityTest {

    private static final String EMPLOYEE = "peter";
    private static final String HR_EMPLOYEE = "helen";
    private static final String ADMIN = "admin";

    @Autowired
    private DataManager dataManager;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private AccessChecker accessChecker;

    @Autowired
    private MetadataTools metadataTools;

    @TempDir
    private Path tempDir;

    private DepartmentRole hrDepartment;
    private Folder sandbox;
    private Folder contracts;
    private Folder hr;
    private Folder vacations;
    private Folder payroll;
    private Folder archive;
    private Document readme;
    private Document contract;
    private Document secret;
    private Document salaries;
    private Document policy;
    private Document schedule;

    @BeforeEach
    void createTree() {
        hrDepartment = DepartmentRole.assign(dataManager, HR_EMPLOYEE);

        sandbox = folder(folderService.getRoot(), "access-visibility-test-" + UUID.randomUUID());
        contracts = folder(sandbox, "Contracts");
        hr = TestAccess.giveOwnAccess(dataManager, folder(sandbox, "HR"),
                Grant.role(hrDepartment.code(), AccessLevel.MANAGE));
        vacations = TestAccess.giveOwnAccess(dataManager, folder(hr, "Vacations"),
                Grant.role(EmployeeRole.CODE, AccessLevel.VIEW));
        payroll = folder(hr, "Payroll");
        archive = folder(hr, "Archive");

        readme = upload(sandbox, "readme.txt");
        contract = upload(contracts, "contract.txt");
        secret = TestAccess.giveOwnAccess(dataManager, upload(contracts, "secret.txt"),
                Grant.user(HR_EMPLOYEE, AccessLevel.EDIT));
        salaries = upload(hr, "salaries.txt");
        policy = TestAccess.giveOwnAccess(dataManager, upload(hr, "policy.txt"),
                Grant.role(EmployeeRole.CODE, AccessLevel.VIEW));
        schedule = upload(vacations, "schedule.txt");
        TestAccess.giveOwnAccess(dataManager, upload(payroll, "payslip.txt"), Grant.user(EMPLOYEE, AccessLevel.VIEW));
    }

    @AfterEach
    void deleteTree() {
        folderService.delete(sandbox);
        hrDepartment.remove(dataManager);
    }

    @Test
    void eachUserLoadsTheFoldersTheyHaveAnEntryFor() {
        assertThat(foldersSeenBy(EMPLOYEE)).containsExactlyInAnyOrder(sandbox, contracts, vacations);
        assertThat(foldersSeenBy(HR_EMPLOYEE)).containsExactlyInAnyOrder(sandbox, contracts, hr, vacations);
        assertThat(foldersSeenBy(ADMIN)).containsExactlyInAnyOrder(sandbox, contracts, hr, vacations);
    }

    @Test
    void eachUserLoadsTheDocumentsTheyHaveAnEntryFor() {
        assertThat(documentsSeenBy(EMPLOYEE)).containsExactlyInAnyOrder(readme, contract, policy, schedule);
        assertThat(documentsSeenBy(HR_EMPLOYEE))
                .containsExactlyInAnyOrder(readme, contract, secret, salaries, policy, schedule);
        assertThat(documentsSeenBy(ADMIN))
                .containsExactlyInAnyOrder(readme, contract, secret, salaries, policy, schedule);
    }

    @Test
    void eachUserLoadsTheVersionsOfTheDocumentsTheySee() {
        assertThat(documentsOfVersionsSeenBy(EMPLOYEE)).containsExactlyInAnyOrder(readme, contract, policy, schedule);
        assertThat(documentsOfVersionsSeenBy(HR_EMPLOYEE))
                .containsExactlyInAnyOrder(readme, contract, secret, salaries, policy, schedule);
    }

    @Test
    void theDocumentCountsIncludeOnlyTheDocumentsTheUserSees() {
        assertThat(as(EMPLOYEE, () -> folderService.countDocuments(List.of(contracts, hr))))
                .containsEntry(contracts.getId(), 1L)
                .containsEntry(hr.getId(), 1L);
        assertThat(as(HR_EMPLOYEE, () -> folderService.countDocuments(List.of(contracts, hr))))
                .containsEntry(contracts.getId(), 2L)
                .containsEntry(hr.getId(), 2L);
    }

    @Test
    void theTreeShowsTheWayToWhatTheUserSees() {
        List<Folder> ours = List.of(sandbox, contracts, hr, vacations, payroll, archive);
        List<UUID> ourIds = ours.stream().map(Folder::getId).toList();

        FolderTree peterTree = as(EMPLOYEE, folderService::loadTree);
        FolderTree helenTree = as(HR_EMPLOYEE, folderService::loadTree);
        FolderTree adminTree = as(ADMIN, folderService::loadTree);

        assertThat(peterTree.folders()).filteredOn(ours::contains)
                .containsExactlyInAnyOrder(sandbox, contracts, hr, vacations, payroll);
        assertThat(peterTree.greyed()).filteredOn(ourIds::contains)
                .containsExactlyInAnyOrder(hr.getId(), payroll.getId());
        assertThat(helenTree.folders()).filteredOn(ours::contains).containsExactlyInAnyOrderElementsOf(ours);
        assertThat(helenTree.greyed()).doesNotContainAnyElementsOf(ourIds);
        assertThat(adminTree.folders()).filteredOn(ours::contains).containsExactlyInAnyOrderElementsOf(ours);
        assertThat(adminTree.greyed()).isEmpty();
    }

    @Test
    void aUserWhoSeesNothingGetsTheRootGreyed() {
        User user = dataManager.create(User.class);
        user.setUsername("access-visibility-test-" + UUID.randomUUID());
        dataManager.save(user);

        try {
            FolderTree tree = as(user.getUsername(), folderService::loadTree);

            assertThat(tree.folders()).containsExactly(folderService.getRoot());
            assertThat(tree.greyed()).containsExactly(Folder.ROOT_ID);
        } finally {
            dataManager.remove(dataManager.load(Id.of(user)).one());
        }
    }

    @Test
    void aPathNamesTheHiddenFoldersOnTheWay() {
        List<String> names = as(EMPLOYEE, () -> folderService.getPath(vacations)).stream()
                .map(metadataTools::getInstanceName)
                .toList();

        assertThat(names).endsWith(sandbox.getName(), "HR", "Vacations");
    }

    @Test
    void aLoadedItemKeepsTheReferenceToAHiddenFolder() {
        Document loadedPolicy = as(EMPLOYEE, () -> dataManager.load(Document.class)
                .id(policy.getId())
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("folder", FetchPlan.INSTANCE_NAME))
                .one());

        Folder loadedVacations = as(EMPLOYEE, () -> dataManager.load(Folder.class)
                .id(vacations.getId())
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("parent", FetchPlan.INSTANCE_NAME))
                .one());

        assertThat(as(EMPLOYEE, () -> dataManager.load(Id.of(hr)).optional())).isEmpty();
        assertThat(loadedPolicy.getFolder()).isEqualTo(hr);
        assertThat(metadataTools.getInstanceName(loadedPolicy.getFolder())).isEqualTo("HR");
        assertThat(loadedVacations.getParent()).isEqualTo(hr);
    }

    @Test
    void eachUserHasTheHighestLevelOfTheirEntries() {
        assertThat(levelsOf(EMPLOYEE)).containsExactly(AccessLevel.EDIT, AccessLevel.EDIT, null, AccessLevel.VIEW);
        assertThat(levelsOf(HR_EMPLOYEE))
                .containsExactly(AccessLevel.EDIT, AccessLevel.EDIT, AccessLevel.MANAGE, AccessLevel.VIEW);
        assertThat(levelsOf(ADMIN))
                .containsExactly(AccessLevel.MANAGE, AccessLevel.MANAGE, AccessLevel.MANAGE, AccessLevel.MANAGE);
    }

    @Test
    void theJavaRuleAgreesWithTheJpqlPolicy() {
        Map<Folder, AccessSet> accessSets = Stream.of(sandbox, contracts, hr, vacations)
                .collect(Collectors.toMap(Function.identity(), folder -> TestAccess.setOf(dataManager, folder)));

        for (String username : List.of(EMPLOYEE, HR_EMPLOYEE, ADMIN)) {
            List<Folder> withLevel = as(username, () -> accessSets.entrySet().stream()
                    .filter(entry -> accessChecker.getLevel(entry.getValue()) != null)
                    .map(Map.Entry::getKey)
                    .toList());

            assertThat(withLevel).as(username).containsExactlyInAnyOrderElementsOf(foldersSeenBy(username));
        }
    }

    @Test
    void onlyTheManagersOfASetLoadItAndItsEntries() {
        List<UUID> setIds = Stream.of(sandbox, hr, vacations)
                .map(folder -> TestAccess.setOf(dataManager, folder).getId())
                .toList();
        UUID hrSetId = TestAccess.setOf(dataManager, hr).getId();

        assertThat(as(HR_EMPLOYEE, () -> loadSets(setIds))).extracting(AccessSet::getId).containsExactly(hrSetId);
        assertThat(as(HR_EMPLOYEE, () -> loadEntries(setIds)))
                .extracting(AccessEntry::getRoleCode)
                .containsExactly(hrDepartment.code());
        assertThat(as(EMPLOYEE, () -> loadSets(setIds))).isEmpty();
        assertThat(as(EMPLOYEE, () -> loadEntries(setIds))).isEmpty();
        assertThat(as(ADMIN, () -> loadSets(setIds))).hasSize(3);
    }

    private List<Folder> foldersSeenBy(String username) {
        List<UUID> ids = List.of(sandbox.getId(), contracts.getId(), hr.getId(), vacations.getId());

        return as(username, () -> dataManager.load(Folder.class)
                .query("e.id in :ids")
                .parameter("ids", ids)
                .list());
    }

    private List<Document> documentsSeenBy(String username) {
        return as(username, () -> dataManager.load(Document.class)
                .query("e.id in :ids")
                .parameter("ids", allDocumentIds())
                .list());
    }

    private List<Document> documentsOfVersionsSeenBy(String username) {
        return as(username, () -> dataManager.load(DocumentRevision.class)
                .query("e.document.id in :ids")
                .parameter("ids", allDocumentIds())
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("document", FetchPlan.INSTANCE_NAME))
                .list().stream()
                .map(DocumentRevision::getDocument)
                .toList());
    }

    private List<AccessLevel> levelsOf(String username) {
        List<AccessSet> accessSets = Stream.of(sandbox, contracts, hr, vacations)
                .map(folder -> TestAccess.setOf(dataManager, folder))
                .toList();

        // Stream.toList keeps the nulls of the sets without a level
        return as(username, () -> accessSets.stream()
                .map(accessChecker::getLevel)
                .toList());
    }

    private List<AccessSet> loadSets(List<UUID> ids) {
        return dataManager.load(AccessSet.class)
                .query("e.id in :ids")
                .parameter("ids", ids)
                .list();
    }

    private List<AccessEntry> loadEntries(List<UUID> setIds) {
        return dataManager.load(AccessEntry.class)
                .query("e.accessSet.id in :ids")
                .parameter("ids", setIds)
                .list();
    }

    private List<UUID> allDocumentIds() {
        return List.of(readme.getId(), contract.getId(), secret.getId(), salaries.getId(), policy.getId(),
                schedule.getId());
    }

    private <T> T as(String username, Supplier<T> operation) {
        return systemAuthenticator.withUser(username, operation::get);
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
