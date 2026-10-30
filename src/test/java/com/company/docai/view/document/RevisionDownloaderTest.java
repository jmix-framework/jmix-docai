package com.company.docai.view.document;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.document.Document;
import com.company.docai.model.document.DocumentRevision;
import com.company.docai.model.folder.Folder;
import com.company.docai.test_support.EmployeeUiTestAuthenticator;
import com.company.docai.test_support.TestFiles;
import com.company.docai.view.main.MainView;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Anchor;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.FileStorageLocator;
import io.jmix.core.Messages;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@UiTest(authenticator = EmployeeUiTestAuthenticator.class, initialView = MainView.class)
@SpringBootTest(classes = {DocAiApplication.class, FlowuiTestAssistConfiguration.class})
class RevisionDownloaderTest {

    @Autowired
    private RevisionDownloader revisionDownloader;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private FileStorageLocator fileStorageLocator;

    @Autowired
    private Messages messages;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(folderService.getRoot());
        folder.setName("revision-downloader-test-" + UUID.randomUUID());
        sandbox = folderService.save(folder);
    }

    @AfterEach
    void deleteSandbox() {
        folderService.delete(sandbox);
    }

    @Test
    void aStoredFileIsDownloadedAsAnAttachment() {
        DocumentRevision revision = uploadAndGetRevision("a.txt");

        revisionDownloader.download(revision);

        assertThat(downloadLinks()).singleElement()
                .satisfies(link -> assertThat(link.getElement().hasAttribute("download")).isTrue());
    }

    @Test
    void aFileMissingFromTheStorageIsReportedInsteadOfDownloaded() {
        DocumentRevision revision = uploadAndGetRevision("a.txt");
        fileStorageLocator.getByName(revision.getFile().getStorageName()).removeFile(revision.getFile());

        revisionDownloader.download(revision);

        assertThat(downloadLinks()).isEmpty();
        assertThat(UiTestUtils.getLastOpenedNotification().getText())
                .isEqualTo(messages.formatMessage(RevisionDownloader.class, "fileMissing", 1));
        assertThat(UiTestUtils.getLastOpenedNotification().getType()).isEqualTo(Notifications.Type.WARNING);
    }

    /**
     * The downloader adds a link to the page and clicks it.
     */
    private List<Anchor> downloadLinks() {
        return UI.getCurrent().getChildren()
                .filter(Anchor.class::isInstance)
                .map(Anchor.class::cast)
                .toList();
    }

    private DocumentRevision uploadAndGetRevision(String fileName) {
        Document document = documentService.upload(sandbox, List.of(TestFiles.file(tempDir, fileName, "text")))
                .created().getFirst();

        return dataManager.load(Document.class)
                .id(document.getId())
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE).add("currentRevision", FetchPlan.BASE))
                .one()
                .getCurrentRevision();
    }
}
