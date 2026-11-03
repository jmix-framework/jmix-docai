package com.company.docai.access;

import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.document.UploadReport;
import com.company.docai.app.service.document.UploadedFile;
import com.company.docai.app.service.folder.FolderContents;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.document.Document;
import com.company.docai.model.document.DocumentRevision;
import com.company.docai.model.folder.Folder;
import com.company.docai.security.role.EmployeeRole;
import com.company.docai.test_support.AuthenticatedAsAdmin;
import com.company.docai.test_support.TestAccess;
import com.company.docai.test_support.TestAccess.Grant;
import com.company.docai.test_support.TestFiles;
import com.company.docai.test_support.TestStatuses;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Id;
import io.jmix.core.Messages;
import io.jmix.core.security.AccessDeniedException;
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
 * Who may change what. A user who only views a folder or a document is told so, and an item hidden from the user
 * is unavailable to them, as if it were deleted. A change that bypasses the services is stopped by the predicates
 * of the access row-level role. The tree: "Published" is viewed by all employees, "Closed" is managed by helen
 * alone; peter is an employee.
 */
@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class AccessChangeTest {

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

    @Autowired
    private Messages messages;

    @TempDir
    private Path tempDir;

    private Folder sandbox;
    private Folder archive;
    private Folder published;
    private Folder closed;
    private Document memo;
    private Document priceList;
    private Document budget;

    @BeforeEach
    void createTree() {
        sandbox = folder(folderService.getRoot(), "access-change-test-" + UUID.randomUUID());
        archive = folder(sandbox, "Archive");
        published = TestAccess.giveOwnAccess(dataManager, folder(sandbox, "Published"),
                Grant.role(EmployeeRole.CODE, AccessLevel.VIEW));
        closed = TestAccess.giveOwnAccess(dataManager, folder(sandbox, "Closed"),
                Grant.user(HR_EMPLOYEE, AccessLevel.MANAGE));

        memo = upload(sandbox, "memo.txt");
        priceList = upload(published, "price-list.txt");
        budget = upload(closed, "budget.txt");
    }

    @AfterEach
    void deleteTree() {
        folderService.delete(sandbox);
    }

    @Test
    void aViewedFolderIsNeitherChangedNorFilled() {
        String readOnly = messages.formatMessage(FolderService.class, "folderReadOnly", "Published");

        assertRejected(() -> folderService.save(renamed(published, "Renamed")), readOnly);
        assertRejected(() -> folder(published, "Drafts"), readOnly);
        assertRejected(() -> documentService.upload(published, List.of(file("offer.txt"))), readOnly);
        assertRejected(() -> documentService.create(draft(published, "offer.txt"), file("offer.txt"), null), readOnly);
        assertRejected(() -> folderService.move(published, archive), readOnly);
        assertRejected(() -> folderService.move(archive, published), readOnly);
        assertRejected(() -> documentService.move(List.of(memo), published), readOnly);
        assertRejected(() -> folderService.countContents(published), readOnly);
        assertRejected(() -> folderService.delete(published), readOnly);
    }

    @Test
    void aViewedDocumentIsNotChanged() {
        String readOnly = messages.formatMessage(DocumentService.class, "documentReadOnly", "price-list.txt");

        assertRejected(() -> documentService.save(renamed(priceList, "prices.txt"), null, null), readOnly);
        assertRejected(() -> documentService.uploadRevision(priceList, file("price-list-v2.txt")), readOnly);
        assertRejected(() -> documentService.changeStatus(List.of(priceList), TestStatuses.active(dataManager)),
                readOnly);
        assertRejected(() -> documentService.move(List.of(priceList), sandbox), readOnly);
        assertRejected(() -> documentService.delete(List.of(priceList)), readOnly);
    }

    @Test
    void aHiddenFolderIsUnavailable() {
        String unavailable = messages.formatMessage(FolderService.class, "folderUnavailable", "Closed");

        assertRejected(() -> folderService.save(closed), unavailable);
        assertRejected(() -> folder(closed, "Drafts"), unavailable);
        assertRejected(() -> documentService.upload(closed, List.of(file("offer.txt"))), unavailable);
        assertRejected(() -> folderService.move(closed, archive), unavailable);
        assertRejected(() -> folderService.move(archive, closed), unavailable);
        assertRejected(() -> documentService.move(List.of(memo), closed), unavailable);
        assertRejected(() -> folderService.countContents(closed), unavailable);
        assertRejected(() -> folderService.delete(closed), unavailable);
    }

    @Test
    void aHiddenDocumentIsUnavailable() {
        String unavailable = messages.formatMessage(DocumentService.class, "documentUnavailable", "budget.txt");

        assertRejected(() -> documentService.save(budget, null, null), unavailable);
        assertRejected(() -> documentService.uploadRevision(budget, file("budget-v2.txt")), unavailable);
        assertRejected(() -> documentService.changeStatus(List.of(budget), TestStatuses.active(dataManager)),
                unavailable);
        assertRejected(() -> documentService.move(List.of(budget), sandbox), unavailable);
        assertRejected(() -> documentService.delete(List.of(budget)), unavailable);
    }

    @Test
    void aHiddenNamesakeTakesTheName() {
        TestAccess.giveOwnAccess(dataManager, memo, Grant.user(HR_EMPLOYEE, AccessLevel.EDIT));

        UploadReport report = systemAuthenticator.withUser(EMPLOYEE,
                () -> documentService.upload(sandbox, List.of(file("memo.txt"))));

        assertThat(report.created()).isEmpty();
        assertThat(report.skipped()).singleElement()
                .satisfies(skipped -> assertThat(skipped.reason()).isEqualTo(messages.formatMessage(
                        DocumentService.class, "documentNameTaken", sandbox.getName(), "memo.txt")));
        assertRejected(() -> folder(sandbox, "Closed"),
                messages.formatMessage(FolderService.class, "folderNameTaken", sandbox.getName(), "Closed"));
    }

    @Test
    void aFolderWithContentTheUserCannotChangeIsNotDeleted() {
        String kept = messages.formatMessage(FolderService.class, "folderContentNotDeletable", sandbox.getName());

        assertRejected(() -> folderService.countContents(sandbox), kept);
        assertRejected(() -> folderService.delete(sandbox), kept);
        assertThat(dataManager.load(Id.of(sandbox)).optional()).isPresent();
    }

    @Test
    void anEmployeeChangesAFolderWhereTheyEdit() {
        Folder drafts = systemAuthenticator.withUser(EMPLOYEE, () -> folder(sandbox, "Drafts"));
        systemAuthenticator.runWithUser(EMPLOYEE, () -> upload(drafts, "draft.txt"));

        FolderContents contents = systemAuthenticator.withUser(EMPLOYEE, () -> folderService.countContents(drafts));
        systemAuthenticator.runWithUser(EMPLOYEE, () -> folderService.delete(drafts));

        assertThat(TestAccess.setOf(dataManager, drafts)).isEqualTo(TestAccess.setOf(dataManager, sandbox));
        assertThat(contents).isEqualTo(new FolderContents(0, 1));
        assertThat(dataManager.load(Id.of(drafts)).optional()).isEmpty();
    }

    @Test
    void aRenameThroughDataManagerIsDenied() {
        assertThatThrownBy(() -> systemAuthenticator.runWithUser(EMPLOYEE, () -> {
            Folder folder = dataManager.load(Id.of(published)).one();
            folder.setName("Renamed");
            dataManager.save(folder);
        }))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageMatching("resource: .*Folder.*, type: entity, action: update");
    }

    @Test
    void aNewItemThroughDataManagerIsDenied() {
        assertThatThrownBy(() -> systemAuthenticator.runWithUser(EMPLOYEE, () -> {
            Folder folder = dataManager.create(Folder.class);
            folder.setParent(published);
            folder.setName("Drafts");
            dataManager.save(folder);
        }))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageMatching("resource: .*Folder.*, type: entity, action: create");
        assertThatThrownBy(() -> systemAuthenticator.runWithUser(EMPLOYEE,
                () -> dataManager.save(draft(published, "offer.txt"))))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageMatching("resource: .*Document.*, type: entity, action: create");
    }

    @Test
    void aVersionSavedDirectlyIsDenied() {
        DocumentRevision current = dataManager.load(Document.class)
                .id(priceList.getId())
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("currentRevision", FetchPlan.BASE))
                .one()
                .getCurrentRevision();

        assertThatThrownBy(() -> systemAuthenticator.runWithUser(EMPLOYEE, () -> {
            DocumentRevision revision = dataManager.create(DocumentRevision.class);
            revision.setDocument(dataManager.load(Id.of(priceList)).one());
            revision.setNumber(2);
            revision.setFile(current.getFile());
            revision.setFileSize(current.getFileSize());
            revision.setContentType(current.getContentType());
            dataManager.save(revision);
        }))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageMatching("resource: .*DocumentRevision.*, type: entity, action: create");
    }

    @Test
    void aDeleteThroughDataManagerIsDenied() {
        assertThatThrownBy(() -> systemAuthenticator.runWithUser(EMPLOYEE,
                () -> dataManager.remove(dataManager.load(Id.of(priceList)).one())))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageMatching("resource: .*Document.*, type: entity, action: delete");
        assertThatThrownBy(() -> systemAuthenticator.runWithUser(EMPLOYEE,
                () -> dataManager.remove(dataManager.load(Id.of(published)).one())))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageMatching("resource: .*Folder.*, type: entity, action: delete");
    }

    private void assertRejected(Runnable change, String message) {
        assertThatThrownBy(() -> systemAuthenticator.runWithUser(EMPLOYEE, change))
                .isInstanceOf(OperationRejectedException.class)
                .hasMessage(message);
    }

    private Folder renamed(Folder folder, String name) {
        Folder loaded = dataManager.load(Id.of(folder)).one();
        loaded.setName(name);
        return loaded;
    }

    private Document renamed(Document document, String name) {
        Document loaded = dataManager.load(Id.of(document)).one();
        loaded.setName(name);
        return loaded;
    }

    private Document draft(Folder folder, String name) {
        Document draft = dataManager.create(Document.class);
        draft.setFolder(folder);
        draft.setName(name);
        return draft;
    }

    private UploadedFile file(String fileName) {
        return TestFiles.file(tempDir, fileName, "text");
    }

    private Folder folder(Folder parent, String name) {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(parent);
        folder.setName(name);
        return folderService.save(folder);
    }

    private Document upload(Folder folder, String fileName) {
        return documentService.upload(folder, List.of(file(fileName))).created().getFirst();
    }
}
