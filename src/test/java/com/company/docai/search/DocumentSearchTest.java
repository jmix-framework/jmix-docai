package com.company.docai.search;

import com.company.docai.app.service.document.DocumentService;
import com.company.docai.app.service.folder.FolderService;
import com.company.docai.app.service.trash.TrashService;
import com.company.docai.model.access.AccessLevel;
import com.company.docai.model.document.Document;
import com.company.docai.model.folder.Folder;
import com.company.docai.model.tag.Tag;
import com.company.docai.test_support.AuthenticatedAsAdmin;
import com.company.docai.test_support.TestAccess;
import com.company.docai.test_support.TestAccess.Grant;
import com.company.docai.test_support.TestFiles;
import com.company.docai.test_support.TestStatuses;
import com.company.docai.test_support.TestTrash;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Id;
import io.jmix.core.IdSerialization;
import io.jmix.core.MessageTools;
import io.jmix.core.Messages;
import io.jmix.core.Metadata;
import io.jmix.core.MetadataTools;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.dynattr.DynAttrQueryHints;
import io.jmix.dynattr.model.Category;
import io.jmix.search.index.mapping.IndexConfigurationManager;
import io.jmix.search.index.queue.IndexingQueueManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.Refresh;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-text search against OpenSearch from {@code compose.yaml}: what is found, by whom, and what leaves
 * the index. Runs only with {@code ./gradlew searchTest}.
 */
@org.junit.jupiter.api.Tag("search")
@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
class DocumentSearchTest {

    private static final String EMPLOYEE = "peter";
    private static final String COLLEAGUE = "maria";

    @Autowired
    private DocumentSearch documentSearch;

    @Autowired
    private SearchIndexInitializer searchIndexInitializer;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private TrashService trashService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private IndexingQueueManager indexingQueueManager;

    @Autowired
    private IndexConfigurationManager indexConfigurationManager;

    @Autowired
    private OpenSearchClient openSearchClient;

    @Autowired
    private IdSerialization idSerialization;

    @Autowired
    private Messages messages;

    @Autowired
    private MetadataTools metadataTools;

    @Autowired
    private MessageTools messageTools;

    @Autowired
    private Metadata metadata;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void createSandbox() {
        sandbox = folder(folderService.getRoot(), "search-test-" + UUID.randomUUID());
    }

    @AfterEach
    void deleteSandbox() {
        dataManager.load(Id.of(sandbox))
                .optional()
                .ifPresent(folderService::delete);

        index();
    }

    @Test
    void aWordInsideThePdfFindsTheDocument() {
        Document lease = upload(sandbox, "lease.pdf", pdf());
        index();

        DocumentSearchHit hit = hitOf(documentSearch.search("Chamomile"), lease);

        assertThat(hit.field()).isEqualTo(messages.getMessage(DocumentSearch.class, "fileText"));
        assertThat(hit.snippet()).contains("<b>Chamomile</b>");
        assertThat(hit.folderPath()).containsExactly(metadataTools.getInstanceName(folderService.getRoot()),
                sandbox.getName());
    }

    // the samples are English, DocAiIndexSettingsTest checks the Russian stemmer
    @Test
    void otherFormsOfAWordMatch() {
        Document document = upload(sandbox, "The tenants signed the lease contracts on Monday.");
        index();

        assertThat(ids(documentSearch.search("contract"))).contains(document.getId());
        assertThat(ids(documentSearch.search("tenant"))).contains(document.getId());
    }

    @Test
    void aTextFieldOfTheTypeIsFoundAndAnAmountIsNot() {
        String counterparty = token();
        Document contract = upload(sandbox, "Nothing about the parties here.");
        Document edited = reloadWithFields(contract);
        edited.setCategory(contractType());
        EntityValues.setValue(edited, "+counterparty", counterparty);
        EntityValues.setValue(edited, "+contractAmount", new BigDecimal("7340001"));
        documentService.save(edited, null, null);
        index();

        DocumentSearchHit hit = hitOf(documentSearch.search(counterparty), contract);

        assertThat(hit.field()).isEqualTo("Counterparty");
        assertThat(ids(documentSearch.search("7340001"))).doesNotContain(contract.getId());
    }

    @Test
    void theStatusAndTheFolderAreNotSearched() {
        Folder folder = folder(sandbox, token());
        Document document = upload(folder, "Nothing about statuses or folders here.");
        documentService.changeStatus(List.of(document), TestStatuses.active(dataManager));
        index();

        assertThat(ids(documentSearch.search(folder.getName()))).doesNotContain(document.getId());
        assertThat(ids(documentSearch.search("Active"))).doesNotContain(document.getId());
    }

    @Test
    void aWordOfTheNameIsShownWithTheNameCaption() {
        String word = token();
        Document document = upload(sandbox, word + ".txt", "Nothing else here.".getBytes(StandardCharsets.UTF_8));
        index();

        DocumentSearchHit hit = hitOf(documentSearch.search(word), document);

        assertThat(hit.field()).isEqualTo(messageTools.getPropertyCaption(metadata.getClass(Document.class), "name"));
    }

    @Test
    void aFileWithoutTextIsFoundByItsName() {
        String word = token();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 0};
        Document document = upload(sandbox, word + ".png", png);
        index();

        assertThat(ids(documentSearch.search(word))).containsExactly(document.getId());
    }

    @Test
    void aRenamedDocumentIsFoundByItsNewNameAndItsFileByTheOldOne() {
        String oldName = token();
        String newName = token();
        Document document = upload(sandbox, oldName + ".txt", "Nothing else here.".getBytes(StandardCharsets.UTF_8));
        index();

        Document edited = reloadWithFields(document);
        edited.setName(newName + ".txt");
        documentService.save(edited, null, null);
        index();

        assertThat(hitOf(documentSearch.search(newName), document).field())
                .isEqualTo(messageTools.getPropertyCaption(metadata.getClass(Document.class), "name"));
        // the file of the version keeps its name
        assertThat(hitOf(documentSearch.search(oldName), document).field())
                .isEqualTo(messages.getMessage(DocumentSearch.class, "fileName"));
    }

    @Test
    void aDocumentIsFoundByTheNameOfItsTag() {
        String word = token();
        Document document = upload(sandbox, "Nothing about the tag here.");
        index();

        tagged(document, tag(word));
        index();

        DocumentSearchHit hit = hitOf(documentSearch.search(word), document);
        assertThat(hit.field()).isEqualTo(messageTools.getPropertyCaption(metadata.getClass(Document.class), "tags"));
        assertThat(hit.snippet()).contains("<b>" + word + "</b>");
    }

    @Test
    void aDocumentIsFoundByTheNewNameOfItsRenamedTagAndNotByTheOldOne() {
        String oldWord = token();
        String newWord = token();
        Tag tag = tag(oldWord);
        Document document = upload(sandbox, "Nothing about the tag here.");
        tagged(document, tag);
        index();

        Tag renamed = dataManager.load(Id.of(tag)).one();
        renamed.setName(newWord);
        dataManager.save(renamed);
        index();

        assertThat(ids(documentSearch.search(newWord))).containsExactly(document.getId());
        assertThat(ids(documentSearch.search(oldWord))).doesNotContain(document.getId());
    }

    @Test
    void aDocumentIsNotFoundByATagTakenOffIt() {
        String word = token();
        Document document = upload(sandbox, "Nothing about the tag here.");
        tagged(document, tag(word));
        index();

        tagged(document);
        index();

        assertThat(ids(documentSearch.search(word))).doesNotContain(document.getId());
    }

    @Test
    void wordsWithQuerySyntaxAreSearchedAsWords() {
        Document lease = upload(sandbox, "lease.pdf", pdf());
        index();

        assertThat(ids(documentSearch.search("warehouse: \"( [* tenant"))).contains(lease.getId());
    }

    @Test
    void anEmployeeGetsOnlyTheDocumentsTheyMaySee() {
        String word = token();
        Folder secret = secretFolder();
        Document open = upload(sandbox, word);
        Document hidden = upload(secret, word);
        index();

        assertThat(ids(documentSearch.search(word))).containsExactlyInAnyOrder(open.getId(), hidden.getId());
        assertThat(ids(asEmployee(word))).containsExactly(open.getId());
    }

    @Test
    void moreIsOfferedOnlyWhenTheNextPageHasADocumentTheUserMaySee() {
        String word = token();
        uploadMany(sandbox, word, DocumentSearch.PAGE_SIZE);
        uploadMany(secretFolder(), word, 5);
        index();

        DocumentSearchPage employeePage = asEmployee(word);
        DocumentSearchPage adminPage = documentSearch.search(word);

        assertThat(employeePage.hits()).hasSize(DocumentSearch.PAGE_SIZE);
        assertThat(employeePage.hasMore()).isFalse();
        assertThat(adminPage.hits()).hasSize(DocumentSearch.PAGE_SIZE);
        assertThat(adminPage.hasMore()).isTrue();
        assertThat(documentSearch.next(adminPage).hits()).hasSize(5);
    }

    @Test
    void pagesFollowEachOtherWithoutGapsOrRepeats() {
        String word = token();
        List<Document> documents = uploadMany(sandbox, word, 2 * DocumentSearch.PAGE_SIZE + 1);
        index();

        DocumentSearchPage first = documentSearch.search(word);
        DocumentSearchPage second = documentSearch.next(first);
        DocumentSearchPage third = documentSearch.next(second);

        assertThat(first.hits()).hasSize(DocumentSearch.PAGE_SIZE);
        assertThat(second.hits()).hasSize(DocumentSearch.PAGE_SIZE);
        assertThat(third.hits()).hasSize(1);
        assertThat(third.hasMore()).isFalse();
        assertThat(Stream.of(first, second, third).flatMap(page -> ids(page).stream()))
                .containsExactlyInAnyOrderElementsOf(documents.stream().map(Document::getId).toList());
    }

    @Test
    void aNewVersionReplacesTheTextOfTheDocument() {
        String oldWord = token();
        String newWord = token();
        Document document = upload(sandbox, oldWord);
        index();

        documentService.uploadRevision(document, TestFiles.file(tempDir, "v2.txt", newWord));
        index();

        assertThat(ids(documentSearch.search(oldWord))).doesNotContain(document.getId());
        assertThat(ids(documentSearch.search(newWord))).containsExactly(document.getId());
    }

    @Test
    void aDeletedDocumentLeavesTheIndexAtOnce() {
        String word = token();
        Document document = upload(sandbox, word);
        index();

        documentService.delete(List.of(document));

        assertThat(inIndex(document)).isFalse();
        index();
        assertThat(ids(documentSearch.search(word))).isEmpty();
    }

    @Test
    void theDocumentsOfADeletedFolderLeaveTheIndexAtOnce() {
        String word = token();
        Folder folder = folder(sandbox, "to-delete");
        Document first = upload(folder, word);
        Document second = upload(folder, word);
        index();
        documentService.uploadRevision(second, TestFiles.file(tempDir, "v2.txt", word + " again"));
        index();

        folderService.delete(folder);

        assertThat(inIndex(first)).isFalse();
        assertThat(inIndex(second)).isFalse();
        // the queue holds the removal and, through the folder and the version, an indexing that is later
        index();
        assertThat(inIndex(first)).isFalse();
        assertThat(inIndex(second)).isFalse();
    }

    @Test
    void theDocumentsOfARestoredFolderAreFoundAgain() {
        String word = token();
        Folder folder = folder(sandbox, "to-restore");
        Document document = upload(folder, word);
        index();
        folderService.delete(folder);
        index();
        assertThat(ids(documentSearch.search(word))).isEmpty();

        trashService.restore(List.of(TestTrash.itemOf(trashService.loadItems(), folder.getId())));
        index();

        assertThat(ids(documentSearch.search(word))).containsExactly(document.getId());
    }

    @Test
    void anEmptyIndexIsFilledWithTheLiveDocuments() {
        String word = token();
        Document document = upload(sandbox, word);
        index();
        emptyIndex();

        assertThat(searchIndexInitializer.fillEmptyIndex()).isTrue();
        indexAll();

        assertThat(ids(documentSearch.search(word))).containsExactly(document.getId());
        assertThat(searchIndexInitializer.fillEmptyIndex()).isFalse();
    }

    @Test
    void aDocumentUploadedByAnEmployeeIsIndexed() {
        String word = token();
        Document document = systemAuthenticator.withUser(EMPLOYEE, () -> upload(sandbox, word));
        index();

        assertThat(ids(documentSearch.search(word))).containsExactly(document.getId());
    }

    private DocumentSearchPage asEmployee(String word) {
        return systemAuthenticator.withUser(EMPLOYEE, () -> documentSearch.search(word));
    }

    private static DocumentSearchHit hitOf(DocumentSearchPage page, Document document) {
        return hitOf(page.hits(), document);
    }

    private static DocumentSearchHit hitOf(List<DocumentSearchHit> hits, Document document) {
        return hits.stream()
                .filter(hit -> hit.document().equals(document))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Not found: " + document.getName()));
    }

    private static List<UUID> ids(DocumentSearchPage page) {
        return ids(page.hits());
    }

    private static List<UUID> ids(List<DocumentSearchHit> hits) {
        return hits.stream()
                .map(hit -> hit.document().getId())
                .toList();
    }

    private static List<UUID> idsOf(List<Document> documents) {
        return documents.stream()
                .map(Document::getId)
                .toList();
    }

    private void index() {
        indexingQueueManager.processEntireQueue();
    }

    private void indexAll() {
        while (indexingQueueManager.processNextEnqueueingSession() > 0) {
            index();
        }

        index();
    }

    private boolean inIndex(Document document) {
        try {
            return openSearchClient.exists(request -> request.index(indexName())
                            .id(idSerialization.idToString(Id.of(document))))
                    .value();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void emptyIndex() {
        try {
            openSearchClient.deleteByQuery(request -> request.index(indexName())
                    .query(query -> query.matchAll(all -> all))
                    .refresh(Refresh.True));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String indexName() {
        return indexConfigurationManager.getIndexConfigurationByEntityName("Document").getIndexName();
    }

    private Folder secretFolder() {
        Folder secret = folder(sandbox, "secret");

        return TestAccess.giveOwnAccess(dataManager, secret, Grant.user(COLLEAGUE, AccessLevel.EDIT));
    }

    private Folder folder(Folder parent, String name) {
        Folder folder = dataManager.create(Folder.class);
        folder.setParent(parent);
        folder.setName(name);
        return folderService.save(folder);
    }

    private Document upload(Folder folder, String text) {
        return upload(folder, "doc-" + UUID.randomUUID() + ".txt", text.getBytes(StandardCharsets.UTF_8));
    }

    private Document upload(Folder folder, String fileName, byte[] content) {
        return documentService.upload(folder, List.of(TestFiles.file(tempDir, fileName, content)))
                .created()
                .getFirst();
    }

    private List<Document> uploadMany(Folder folder, String text, int count) {
        return documentService.upload(folder, IntStream.range(0, count)
                        .mapToObj(number -> TestFiles.file(tempDir, "doc-" + number + ".txt", text))
                        .toList())
                .created();
    }

    private Document reloadWithFields(Document document) {
        return dataManager.load(Document.class)
                .id(document.getId())
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("folder", FetchPlan.BASE)
                        .add("category", FetchPlan.INSTANCE_NAME))
                .hint(DynAttrQueryHints.LOAD_DYN_ATTR, true)
                .one();
    }

    private Tag tag(String name) {
        Tag tag = dataManager.create(Tag.class);
        tag.setName(name);
        return dataManager.save(tag);
    }

    // the tags as the card saves them
    private void tagged(Document document, Tag... tags) {
        Document edited = dataManager.load(Document.class)
                .id(document.getId())
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("folder", FetchPlan.BASE)
                        .add("tags", FetchPlan.BASE))
                .one();
        edited.setTags(new ArrayList<>(List.of(tags)));
        documentService.save(edited, null, null);
    }

    private Category contractType() {
        return dataManager.load(Category.class)
                .query("select c from dynat_Category c where c.name = 'Contract' and c.entityType = 'Document'")
                .one();
    }

    private static byte[] pdf() {
        try (InputStream stream = DocumentSearchTest.class.getResourceAsStream("lease.pdf")) {
            return Objects.requireNonNull(stream).readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Returns a unique word. It ends with a letter, as names do before their extension: «contract.txt».
     */
    private static String token() {
        return "w" + UUID.randomUUID().toString().replace("-", "") + "z";
    }

    @Test
    void theFirstDocumentsAreFoundByTheBeginningsOfTheirWords() {
        String word = token();
        Document document = upload(sandbox, word + " lease.txt", "Nothing else here.".getBytes(StandardCharsets.UTF_8));
        index();

        DocumentSearchHit hit = hitOf(documentSearch.first(word.substring(0, 6), 5), document);

        // the add-on marks the whole word that begins so, in the name or in its beginnings
        assertThat(hit.snippet()).contains("<b>" + word + "</b>");
        assertThat(DocumentSearchHit.partsOf(hit.snippet())
                .stream()
                .map(DocumentSearchHit.Part::text)
                .collect(Collectors.joining())).isEqualTo(document.getName());
        // a beginning of two letters is shorter than the index keeps
        assertThat(ids(documentSearch.first(word.substring(0, 2), 5))).doesNotContain(document.getId());
    }

    @Test
    void aWordOverEightLettersIsFoundByItsBeginningInAnyForm() {
        Document document = upload(sandbox, "Subcontractors " + token() + ".txt",
                "Nothing else here.".getBytes(StandardCharsets.UTF_8));

        index();

        assertThat(ids(documentSearch.first("subcontract", 5))).contains(document.getId());
        assertThat(ids(documentSearch.first("subcontractor", 5))).contains(document.getId());
        assertThat(ids(documentSearch.first("SUBCONTRACTORS", 5))).contains(document.getId());
    }

    @Test
    void thePageFindsTheBeginningsOfTheWordsOfTheNameAsThePaletteDoes() {
        String word = token();
        Document document = upload(sandbox, word + ".txt", "Nothing else here.".getBytes(StandardCharsets.UTF_8));
        index();

        DocumentSearchHit hit = hitOf(documentSearch.search(word.substring(0, 6)), document);

        assertThat(ids(documentSearch.first(word.substring(0, 6), 5))).contains(document.getId());
        assertThat(hit.field()).isEqualTo(messageTools.getPropertyCaption(metadata.getClass(Document.class), "name"));
        assertThat(hit.snippet()).isEqualTo("<b>" + word + "</b>.txt");
    }

    @Test
    void wordsJoinedByADotAreFoundAndMarkedOneByOne() {
        String word = token();
        String next = token();

        Document document = upload(sandbox, word + "." + next + ".txt",
                "Nothing else here.".getBytes(StandardCharsets.UTF_8));

        index();

        // a dot between letters ends a word in the beginnings as well, so the extension stays unmarked
        assertThat(hitOf(documentSearch.first(word.substring(0, 6), 5), document).snippet())
                .isEqualTo("<b>" + word + "</b>." + next + ".txt");
        assertThat(hitOf(documentSearch.first(next.substring(0, 6), 5), document).snippet())
                .isEqualTo(word + ".<b>" + next + "</b>.txt");
    }

    @Test
    void wordsJoinedByAnUnderscoreStayOneWord() {
        String word = token();
        String next = token();

        Document document = upload(sandbox, word + "_" + next + ".txt",
                "Nothing else here.".getBytes(StandardCharsets.UTF_8));

        index();

        // the split of the beginnings keeps an underscore inside the word, as the standard tokenizer does
        assertThat(hitOf(documentSearch.first(word.substring(0, 6), 5), document).snippet())
                .isEqualTo("<b>" + word + "_" + next + "</b>.txt");
        assertThat(ids(documentSearch.first(next.substring(0, 6), 5))).doesNotContain(document.getId());
    }

    @Test
    void aBeginningOfThreeLettersFindsAWordOfTheText() {
        Document document = upload(sandbox, "The supplier confirmed the delivery on Monday.");
        index();

        assertThat(ids(documentSearch.search("del"))).contains(document.getId());
        assertThat(ids(documentSearch.search("de"))).doesNotContain(document.getId());
    }

    @Test
    void aFormOfAWordLongerThanTheKeptBeginningsStillFindsTheWord() {
        Document document = upload(sandbox, "The agreement was signed on Monday.");
        index();

        assertThat(ids(documentSearch.search("agreements"))).contains(document.getId());
    }

    @Test
    void aWholeWordComesBeforeABeginningThatRelevanceAlonePutsFirst() {
        String word = token();
        String beginning = word.substring(0, 6);
        uploadMany(folder(sandbox, "others"), "Nothing of interest here.", 10);
        Document byBeginning = upload(sandbox, (word + " ").repeat(20));
        Document byWord = upload(sandbox, "Other matters. ".repeat(20) + beginning);
        index();

        // the palette lists them by relevance alone: the beginning, often in a short text, outweighs the word, once
        // in a long one
        assertThat(ids(documentSearch.first(beginning, 5))).containsSubsequence(byBeginning.getId(), byWord.getId());
        assertThat(ids(documentSearch.search(beginning))).containsSubsequence(byWord.getId(), byBeginning.getId());
    }

    @Test
    void aBeginningIsMarkedInTheTextAsAWordIs() {
        Document document = upload(sandbox, "The supplier confirmed the delivery on Monday.");
        index();

        DocumentSearchHit hit = hitOf(documentSearch.search("deliv"), document);

        assertThat(hit.field()).isEqualTo(messages.getMessage(DocumentSearch.class, "fileText"));
        assertThat(hit.snippet()).contains("<b>delivery</b>");
    }

    @Test
    void aBeginningOfATagIsShownWithTheTagsCaption() {
        String word = token();
        Document document = upload(sandbox, "Nothing about the tag here.");
        tagged(document, tag(word));
        index();

        DocumentSearchHit hit = hitOf(documentSearch.search(word.substring(0, 6)), document);

        assertThat(hit.field()).isEqualTo(messageTools.getPropertyCaption(metadata.getClass(Document.class), "tags"));
        assertThat(hit.snippet()).contains("<b>" + word + "</b>");
    }

    @Test
    void thePagesOfABeginningGiveTheWholeWordsFirstWithoutGapsOrRepeats() {
        String word = token();
        String beginning = word.substring(0, 6);
        List<Document> byWord = uploadMany(sandbox, beginning, DocumentSearch.PAGE_SIZE + 1);
        List<Document> byBeginning = uploadMany(folder(sandbox, "beginnings"), word, DocumentSearch.PAGE_SIZE);
        index();

        DocumentSearchPage first = documentSearch.search(beginning);
        DocumentSearchPage second = documentSearch.next(first);
        DocumentSearchPage third = documentSearch.next(second);

        List<UUID> found = Stream.of(first, second, third)
                .flatMap(page -> ids(page).stream())
                .toList();

        assertThat(first.hits()).hasSize(DocumentSearch.PAGE_SIZE);
        assertThat(second.hits()).hasSize(DocumentSearch.PAGE_SIZE);
        assertThat(third.hits()).hasSize(1);
        assertThat(third.hasMore()).isFalse();
        assertThat(found.subList(0, byWord.size())).containsExactlyInAnyOrderElementsOf(idsOf(byWord));
        assertThat(found.subList(byWord.size(), found.size())).containsExactlyInAnyOrderElementsOf(idsOf(byBeginning));
    }

    @Test
    void aQueryOfThirtyWordsIsSearchedByWholeWordsOnly() {
        List<String> words = distinctWords(30);
        Document byWords = upload(sandbox, String.join(" ", words));
        Document byBeginning = upload(sandbox, words.getFirst() + "x");
        index();

        assertThat(ids(documentSearch.search(String.join(" ", words))))
                .contains(byWords.getId())
                .doesNotContain(byBeginning.getId());
    }

    // a pasted text keeps to whole words: forty of them take 560 of the 1024 clauses that OpenSearch allows, one for
    // each of the fourteen fields, and with the beginnings they would take about three times as many
    @Test
    void aQueryOfFortyWordsIsStillSearched() {
        List<String> words = distinctWords(40);
        Document document = upload(sandbox, String.join(" ", words));
        index();

        assertThat(ids(documentSearch.search(String.join(" ", words)))).contains(document.getId());
    }

    @Test
    void aShortWordBesideAWordOverEightLettersIsNoBeginning() {
        Document document = upload(sandbox, "The equipment arrives on Monday; only the onset of work is later.");
        Document byShortWord = upload(sandbox, "Onions and olives.");
        index();

        DocumentSearchPage page = documentSearch.search("on equipment");

        assertThat(hitOf(page, document).snippet())
                .contains("<b>equipment</b>", "<b>on</b>")
                .doesNotContain("<b>only</b>", "<b>onset</b>");
        assertThat(ids(page)).doesNotContain(byShortWord.getId());
        assertThat(ids(documentSearch.first("on equipment", 5))).doesNotContain(byShortWord.getId());
    }

    // the add-on makes only the last piece of a joined word a beginning, so the short piece comes last here
    @Test
    void aShortPieceOfAJoinedWordBesideAWordOverEightLettersIsNoBeginning() {
        Document document = upload(sandbox, "The equipment of organic origin arrives in order.");
        Document byShortPiece = upload(sandbox, "Organic oranges.");
        index();

        DocumentSearchPage page = documentSearch.search("and/or equipment");

        assertThat(hitOf(page, document).snippet())
                .contains("<b>equipment</b>")
                .doesNotContain("<b>organic</b>", "<b>origin</b>", "<b>order</b>");
        assertThat(ids(page)).doesNotContain(byShortPiece.getId());
        assertThat(ids(documentSearch.first("and/or equipment", 5))).doesNotContain(byShortPiece.getId());
    }

    @Test
    void aBeginningOfATextFieldOfTheTypeIsShownWithItsCaption() {
        String counterparty = token();
        Document contract = upload(sandbox, "Nothing about the parties here.");
        Document edited = reloadWithFields(contract);
        edited.setCategory(contractType());
        EntityValues.setValue(edited, "+counterparty", counterparty);
        documentService.save(edited, null, null);
        index();

        DocumentSearchHit hit = hitOf(documentSearch.search(counterparty.substring(0, 6)), contract);

        assertThat(hit.field()).isEqualTo("Counterparty");
        assertThat(hit.snippet()).contains("<b>" + counterparty + "</b>");
    }

    /**
     * Returns distinct words of six letters and digits that no other test uses, so that none begins another.
     */
    private static List<String> distinctWords(int count) {
        String stem = token().substring(0, 4);

        return IntStream.range(0, count)
                .mapToObj(number -> stem + (char) ('a' + number / 26) + (char) ('a' + number % 26))
                .toList();
    }
}
