package com.company.docai.security;

import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.document.Document;
import com.company.docai.model.document.DocumentRevision;
import com.company.docai.model.folder.Folder;
import com.company.docai.test_support.AuthenticatedAsAdmin;
import com.company.docai.test_support.TestFiles;
import com.company.docai.test_support.TestStatuses;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Id;
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

@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class EmployeeRepositoryTest {

    private static final String EMPLOYEE = "peter";

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

    @BeforeEach
    void createSandbox() {
        sandbox = folder(folderService.getRoot(), "employee-repository-test-" + UUID.randomUUID());
    }

    @AfterEach
    void deleteSandbox() {
        folderService.delete(sandbox);
    }

    @Test
    void anEmployeeWorksWithFoldersAndDocuments() {
        systemAuthenticator.runWithUser(EMPLOYEE, () -> {
            Folder contracts = folder(sandbox, "Contracts");
            Folder archive = folder(sandbox, "Archive");
            Document document = documentService.upload(contracts,
                    List.of(TestFiles.file(tempDir, "a.txt", "text"))).created().getFirst();

            documentService.uploadRevision(document, TestFiles.file(tempDir, "a-v2.txt", "text 2"));
            documentService.changeStatus(List.of(document), TestStatuses.active(dataManager));
            documentService.move(List.of(document), archive);

            Document moved = dataManager.load(Document.class)
                    .id(document.getId())
                    .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE).add("folder", FetchPlan.BASE))
                    .one();

            assertThat(moved.getFolder()).isEqualTo(archive);

            documentService.delete(List.of(moved));
            folderService.delete(contracts);
        });
    }

    @Test
    void anEmployeeCannotChangeASavedRevision() {
        Document document = documentService.upload(sandbox,
                List.of(TestFiles.file(tempDir, "a.txt", "text"))).created().getFirst();

        assertThatThrownBy(() -> systemAuthenticator.runWithUser(EMPLOYEE, () -> {
            DocumentRevision revision = dataManager.load(Document.class)
                    .id(document.getId())
                    .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                            .add("currentRevision", FetchPlan.BASE))
                    .one()
                    .getCurrentRevision();

            revision.setFileSize(1L);
            dataManager.save(revision);
        })).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anEmployeeCannotDeleteARevisionDirectly() {
        Document document = documentService.upload(sandbox,
                List.of(TestFiles.file(tempDir, "a.txt", "text"))).created().getFirst();

        assertThatThrownBy(() -> systemAuthenticator.runWithUser(EMPLOYEE, () -> dataManager.remove(
                Id.of(dataManager.load(Document.class)
                        .id(document.getId())
                        .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                                .add("currentRevision", FetchPlan.BASE))
                        .one()
                        .getCurrentRevision()))))
                .isInstanceOf(AccessDeniedException.class);
    }

    private Folder folder(Folder parent, String name) {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(parent);
        folder.setName(name);
        return folderService.save(folder);
    }
}
