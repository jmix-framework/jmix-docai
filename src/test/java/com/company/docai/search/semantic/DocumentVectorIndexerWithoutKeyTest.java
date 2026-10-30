package com.company.docai.search.semantic;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.test_support.AuthenticatedAsAdmin;
import com.company.docai.test_support.InMemoryVectorIndex;
import com.company.docai.test_support.StubAiConfiguration;
import com.company.docai.test_support.StubEmbeddingModel;
import com.company.docai.test_support.TestFiles;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
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
 * The indexer without the key of the model provider: no document goes into the queue, and the index is not checked
 * at the start.
 */
@SpringBootTest(classes = {DocAiApplication.class, StubAiConfiguration.class})
@ExtendWith(AuthenticatedAsAdmin.class)
class DocumentVectorIndexerWithoutKeyTest {

    @Autowired
    private DocumentVectorIndexer indexer;

    @Autowired
    private InMemoryVectorIndex index;

    @Autowired
    private StubEmbeddingModel embeddingModel;

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
    void setUp() {
        index.reset();
        embeddingModel.reset();

        Folder folder = dataManager.create(Folder.class);
        folder.setParent(folderService.getRoot());
        folder.setName("vector-indexer-without-key-test-" + UUID.randomUUID());
        sandbox = folderService.save(folder);
    }

    @AfterEach
    void deleteSandbox() {
        dataManager.load(Id.of(sandbox))
                .optional()
                .ifPresent(folderService::delete);
    }

    @Test
    void anUploadAndTheStartLeaveTheIndexAlone() {
        Document document = documentService.upload(sandbox, List.of(TestFiles.file(tempDir, "contract.txt",
                        "Supply contract 45 with Northwind for office paper.")))
                .created()
                .getFirst();

        indexer.enqueue(document.getId());
        indexer.onApplicationReady();

        // a task in the queue keeps the indexer busy at once, so an idle one has queued nothing
        assertThat(indexer.isBusy()).isFalse();
        assertThat(index.fragmentsOf(document.getId())).isEmpty();
        assertThat(embeddingModel.texts()).isEmpty();
    }
}
