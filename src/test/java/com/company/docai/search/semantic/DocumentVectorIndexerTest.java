package com.company.docai.search.semantic;

import com.company.docai.DocAiApplication;
import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.app.service.trash.TrashService;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.test_support.AuthenticatedAsAdmin;
import com.company.docai.test_support.InMemoryVectorIndex;
import com.company.docai.test_support.StubAiConfiguration;
import com.company.docai.test_support.StubEmbeddingModel;
import com.company.docai.test_support.TestAccess;
import com.company.docai.test_support.TestAccess.Grant;
import com.company.docai.test_support.TestFiles;
import com.company.docai.test_support.TestStatuses;
import com.company.docai.test_support.TestTrash;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Id;
import io.jmix.dynattr.model.Category;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The indexer keeps the index of fragments in line with the documents: the index lives in memory, the model of
 * vectors is a stub, and the uploads, versions, renames, moves and deletions are real.
 */
@SpringBootTest(classes = {DocAiApplication.class, StubAiConfiguration.class},
        properties = "spring.ai.openai.api-key=test-key")
@ExtendWith(AuthenticatedAsAdmin.class)
class DocumentVectorIndexerTest {

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
    private TrashService trashService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private OpenAiEmbeddingProperties embeddingProperties;

    @Value("${docai.semantic.dimensions}")
    private int dimensions;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void setUp() {
        index.reset();
        embeddingModel.reset();
        sandbox = folder("vector-indexer-test-" + UUID.randomUUID());
    }

    @AfterEach
    void deleteSandbox() {
        dataManager.load(Id.of(sandbox))
                .optional()
                .ifPresent(folderService::delete);
    }

    @Test
    void aNewDocumentIsIndexedWithItsNameInEveryVector() {
        Document document = upload("contract.txt", "Supply contract 45 with Northwind for office paper.");

        await().untilAsserted(() -> assertThat(index.fragmentsOf(document.getId())).isNotEmpty());

        assertThat(index.fragmentsOf(document.getId()))
                .allSatisfy(fragment -> assertThat(fragment.ofName()).isFalse())
                .anySatisfy(fragment -> assertThat(fragment.text()).contains("Northwind")
                        .doesNotContain("contract.txt"));
        assertThat(embeddingModel.texts()).filteredOn(text -> text.contains("Northwind"))
                .isNotEmpty()
                .allSatisfy(text -> assertThat(text).startsWith("contract.txt\n\n"));
    }

    @Test
    void aNewNameAndANewVersionIndexTheDocumentAgain() {
        Document document = upload("contract.txt", "Supply contract 45 with Northwind for office paper.");
        await().untilAsserted(() -> assertThat(index.fragmentsOf(document.getId())).isNotEmpty());

        Document renamed = editable(document);
        renamed.setName("supply-contract-45.txt");
        documentService.save(renamed, null, null);

        await().untilAsserted(() -> assertThat(index.sourceKeys().get(document.getId()))
                .endsWith(":supply-contract-45.txt"));

        documentService.uploadRevision(editable(document), TestFiles.file(tempDir, "contract-v2.txt",
                "The second version of the contract adds two tractors."));

        await().untilAsserted(() -> assertThat(index.fragmentsOf(document.getId()))
                .anySatisfy(fragment -> assertThat(fragment.text()).contains("tractors")));
    }

    @Test
    void aMoveAStatusATypeAndAccessLeaveTheIndexAlone() {
        Document document = upload("contract.txt", "Supply contract 45 with Northwind for office paper.");
        await().untilAsserted(() -> assertThat(index.replacements(document.getId())).isEqualTo(1));

        documentService.move(List.of(editable(document)), folder("vector-indexer-target-" + UUID.randomUUID()));
        documentService.changeStatus(List.of(editable(document)), TestStatuses.active(dataManager));
        Document typed = editable(document);
        typed.setCategory(dataManager.load(Category.class)
                .query("select c from dynat_Category c where c.name = 'Contract' and c.entityType = 'Document'")
                .one());
        documentService.save(typed, null, null);
        TestAccess.giveOwnAccess(dataManager, editable(document), Grant.user("maria", AccessLevel.VIEW));

        // the queue is one thread: a document indexed after the changes proves that they queued nothing before it
        Document later = upload("notes.txt", "Notes of the meeting.");
        await().untilAsserted(() -> assertThat(index.fragmentsOf(later.getId())).isNotEmpty());

        assertThat(index.replacements(document.getId())).isEqualTo(1);
    }

    @Test
    void aDeletedDocumentLeavesTheIndex() {
        Document document = upload("contract.txt", "Supply contract 45 with Northwind for office paper.");
        await().untilAsserted(() -> assertThat(index.fragmentsOf(document.getId())).isNotEmpty());

        documentService.delete(List.of(editable(document)));

        await().untilAsserted(() -> assertThat(index.sourceKeys()).doesNotContainKey(document.getId()));
    }

    @Test
    void aDocumentRestoredFromTheTrashComesBackIntoTheIndex() {
        Document document = upload("contract.txt", "Supply contract 45 with Northwind for office paper.");
        await().untilAsserted(() -> assertThat(index.fragmentsOf(document.getId())).isNotEmpty());
        documentService.delete(List.of(editable(document)));
        await().untilAsserted(() -> assertThat(index.sourceKeys()).doesNotContainKey(document.getId()));

        trashService.restore(List.of(TestTrash.itemOf(trashService.loadItems(), document.getId())));

        await().untilAsserted(() -> assertThat(index.fragmentsOf(document.getId())).isNotEmpty());
    }

    @Test
    void aDocumentWithoutTextIsIndexedByItsName() {
        Document document = upload("tractor-photo.txt", "   \n   ");

        await().untilAsserted(() -> assertThat(index.fragmentsOf(document.getId())).isNotEmpty());

        assertThat(index.fragmentsOf(document.getId())).singleElement()
                .satisfies(fragment -> {
                    assertThat(fragment.ofName()).isTrue();
                    assertThat(fragment.text()).isEqualTo("tractor-photo.txt");
                });
    }

    @Test
    void onlyTheStartOfALongTextIsIndexed() {
        String text = "The order begins here. " + "Some filler words of the order. ".repeat(1_500)
                + "The unicorn clause closes the order.";
        Document document = upload("long-order.txt", text);

        await().untilAsserted(() -> assertThat(index.fragmentsOf(document.getId())).isNotEmpty());

        assertThat(index.fragmentsOf(document.getId()))
                .anySatisfy(fragment -> assertThat(fragment.text()).contains("The order begins here"))
                .noneSatisfy(fragment -> assertThat(fragment.text()).containsAnyOf("unicorn", "The text stops here"));
    }

    @Test
    void theCheckQueuesTheChangedDocumentsAndRemovesTheGoneOnes() {
        Document document = upload("contract.txt", "Supply contract 45 with Northwind for office paper.");
        await().untilAsserted(() -> assertThat(index.fragmentsOf(document.getId())).isNotEmpty());
        UUID gone = UUID.randomUUID();
        index.put(gone, "gone", List.of(new DocumentVectorIndex.Fragment(0, "A gone document", false,
                StubEmbeddingModel.vectorOf("A gone document"))));
        index.put(document.getId(), "stale", index.fragmentsOf(document.getId()));

        String sourceKey = DocumentVectorIndexer.sourceKeyOf(withVersion(document));

        DocumentVectorIndexer.Reconciliation result = indexer.reconcile();

        assertThat(result.queued()).isPositive();
        assertThat(result.removed()).isPositive();
        assertThat(index.sourceKeys()).doesNotContainKey(gone);
        await().untilAsserted(() -> assertThat(index.sourceKeys().get(document.getId())).isEqualTo(sourceKey));
    }

    @Test
    void theModelOfVectorsIsAskedForVectorsOfTheSizeOfTheIndex() {
        assertThat(embeddingProperties.getDimensions()).isEqualTo(dimensions);
    }

    private Folder folder(String name) {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(folderService.getRoot());
        folder.setName(name);

        return folderService.save(folder);
    }

    private Document upload(String fileName, String text) {
        return documentService.upload(sandbox, List.of(TestFiles.file(tempDir, fileName, text)))
                .created()
                .getFirst();
    }

    private Document editable(Document document) {
        return dataManager.load(Document.class)
                .id(document.getId())
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("folder", FetchPlan.BASE)
                        .add("category", FetchPlan.INSTANCE_NAME))
                .one();
    }

    private Document withVersion(Document document) {
        return dataManager.load(Document.class)
                .id(document.getId())
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("currentRevision", FetchPlan.BASE))
                .one();
    }
}
