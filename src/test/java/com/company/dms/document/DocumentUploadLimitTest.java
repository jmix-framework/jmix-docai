package com.company.dms.document;

import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.document.UploadReport;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.folder.Folder;
import com.company.dms.test_support.AuthenticatedAsAdmin;
import com.company.dms.test_support.TestFiles;
import io.jmix.core.DataManager;
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

@SpringBootTest(properties = "dms.files.max-file-size=10B")
@ExtendWith(AuthenticatedAsAdmin.class)
class DocumentUploadLimitTest {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DataManager dataManager;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(folderService.getRoot());
        folder.setName("upload-limit-test-" + UUID.randomUUID());
        sandbox = folderService.save(folder);
    }

    @AfterEach
    void deleteSandbox() {
        folderService.delete(sandbox);
    }

    @Test
    void aFileOverTheLimitIsSkipped() {
        UploadReport report = documentService.upload(sandbox, List.of(
                TestFiles.file(tempDir, "small.txt", "tiny"),
                TestFiles.file(tempDir, "large.txt", "more than ten bytes")));

        assertThat(report.created()).singleElement().extracting("name").isEqualTo("small.txt");
        assertThat(report.skipped()).singleElement().satisfies(skipped ->
                assertThat(skipped.fileName()).isEqualTo("large.txt"));
    }

    @Test
    void aFileNameLongerThanTheColumnIsSkipped() {
        String longName = "x".repeat(256) + ".txt";

        UploadReport report = documentService.upload(sandbox, List.of(TestFiles.file(tempDir, longName, "tiny")));

        assertThat(report.created()).isEmpty();
        assertThat(report.skipped()).singleElement().satisfies(skipped ->
                assertThat(skipped.fileName()).isEqualTo(longName));
    }
}
