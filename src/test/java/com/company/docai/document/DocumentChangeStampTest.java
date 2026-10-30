package com.company.docai.document;

import com.company.docai.app.service.access.AccessService;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.access.AccessDraft;
import com.company.docai.model.access.AccessSet;
import com.company.docai.model.document.Document;
import com.company.docai.model.document.DocumentStatus;
import com.company.docai.model.folder.Folder;
import com.company.docai.test_support.AuthenticatedAsAdmin;
import com.company.docai.test_support.TestAccess;
import com.company.docai.test_support.TestFiles;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Id;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who changed the document itself and when: its file, name, date or status. Jmix stamps every save of the row,
 * an access change and a move included; the document's own stamp stays as it is then.
 */
@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class DocumentChangeStampTest {

    private static final String EMPLOYEE = "peter";
    private static final String EARLIER_EDITOR = "maria";
    private static final OffsetDateTime LONG_AGO = OffsetDateTime.of(2020, 1, 15, 9, 30, 0, 0, ZoneOffset.UTC);

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private AccessService accessService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        sandbox = folder(folderService.getRoot(), "document-change-stamp-test-" + UUID.randomUUID());
    }

    @AfterEach
    void deleteSandbox() {
        folderService.delete(sandbox);
    }

    @Test
    void aNewDocumentIsChangedByWhoUploadedIt() {
        Document document = systemAuthenticator.withUser(EMPLOYEE, () -> upload("a.txt"));

        Document stored = reload(document);
        assertThat(stored.getChangedBy()).isEqualTo(EMPLOYEE);
        assertThat(stored.getChangedDate()).isNotNull();
    }

    @Test
    void aNewNameStampsTheDocument() {
        Document document = stampedLongAgo(upload("a.txt"));
        Document renamed = reload(document);
        renamed.setName("b.txt");

        systemAuthenticator.runWithUser(EMPLOYEE, () -> documentService.save(renamed, null, null));

        assertStampedBy(EMPLOYEE, document);
    }

    @Test
    void aNewVersionStampsTheDocument() {
        Document document = stampedLongAgo(upload("a.txt"));

        systemAuthenticator.runWithUser(EMPLOYEE, () ->
                documentService.uploadRevision(document, TestFiles.file(tempDir, "a-v2.txt", "second")));

        assertStampedBy(EMPLOYEE, document);
    }

    @Test
    void aStatusChangeStampsTheDocument() {
        Document document = stampedLongAgo(upload("a.txt"));

        systemAuthenticator.runWithUser(EMPLOYEE, () ->
                documentService.changeStatus(List.of(document), DocumentStatus.ACTIVE));

        assertStampedBy(EMPLOYEE, document);
    }

    @Test
    void aCardSavedWithoutChangesKeepsTheStamp() {
        Document document = stampedLongAgo(upload("a.txt"));

        systemAuthenticator.runWithUser(EMPLOYEE, () -> documentService.save(reload(document), null, null));

        assertStampKept(document);
    }

    @Test
    void aMoveKeepsTheStamp() {
        Document document = stampedLongAgo(upload("a.txt"));
        Folder target = folder(sandbox, "Target");

        systemAuthenticator.runWithUser(EMPLOYEE, () -> documentService.move(List.of(document), target));

        assertThat(reload(document).getFolder()).isEqualTo(target);
        assertStampKept(document);
    }

    @Test
    void anAccessChangeOfTheFolderKeepsTheStamp() {
        Document document = stampedLongAgo(upload("a.txt"));
        AccessSet inherited = TestAccess.setOf(dataManager, document);

        AccessDraft draft = accessService.describe(sandbox);
        draft.setInherit(false);
        accessService.save(draft);

        assertThat(TestAccess.setOf(dataManager, document)).isNotEqualTo(inherited);
        assertStampKept(document);
    }

    private Document stampedLongAgo(Document document) {
        Document stored = dataManager.load(Id.of(document)).one();
        stored.setChangedBy(EARLIER_EDITOR);
        stored.setChangedDate(LONG_AGO);
        return dataManager.save(stored);
    }

    private void assertStampedBy(String username, Document document) {
        Document stored = reload(document);
        assertThat(stored.getChangedBy()).isEqualTo(username);
        assertThat(stored.getChangedDate()).isAfter(LONG_AGO);
    }

    private void assertStampKept(Document document) {
        Document stored = reload(document);
        assertThat(stored.getChangedBy()).isEqualTo(EARLIER_EDITOR);
        assertThat(stored.getChangedDate()).isAtSameInstantAs(LONG_AGO);
    }

    private Document upload(String fileName) {
        return documentService.upload(sandbox, List.of(TestFiles.file(tempDir, fileName, "text")))
                .created().getFirst();
    }

    private Folder folder(Folder parent, String name) {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(parent);
        folder.setName(name);
        return folderService.save(folder);
    }

    private Document reload(Document document) {
        return dataManager.load(Document.class)
                .id(document.getId())
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("folder", FetchPlan.BASE))
                .one();
    }
}
