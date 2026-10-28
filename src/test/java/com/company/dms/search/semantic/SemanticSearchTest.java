package com.company.dms.search.semantic;

import com.company.dms.DMSApplication;
import com.company.dms.app.service.document.DocumentService;
import com.company.dms.app.service.folder.FolderService;
import com.company.dms.model.access.AccessLevel;
import com.company.dms.model.document.Document;
import com.company.dms.model.folder.Folder;
import com.company.dms.search.DocumentSearchHit;
import com.company.dms.search.SearchUnavailableException;
import com.company.dms.test_support.AuthenticatedAsAdmin;
import com.company.dms.test_support.InMemoryVectorIndex;
import com.company.dms.test_support.StubAiConfiguration;
import com.company.dms.test_support.StubChatModel;
import com.company.dms.test_support.StubEmbeddingModel;
import com.company.dms.test_support.TestAccess;
import com.company.dms.test_support.TestAccess.Grant;
import com.company.dms.test_support.TestFiles;
import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The search by meaning on the index in memory, with the stub models of vectors and of the check: the rights apply
 * before the index looks for the nearest fragments, and the model decides which candidates the page shows and the chat
 * gets with their nearest fragments.
 */
@SpringBootTest(classes = {DMSApplication.class, StubAiConfiguration.class})
@ExtendWith(AuthenticatedAsAdmin.class)
class SemanticSearchTest {

    private static final String EMPLOYEE = "peter";

    @Autowired
    private SemanticSearch semanticSearch;

    @Autowired
    private InMemoryVectorIndex index;

    @Autowired
    private StubChatModel model;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private FolderService folderService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @TempDir
    private Path tempDir;

    private Folder sandbox;

    @BeforeEach
    void setUp() {
        index.reset();
        model.reset();

        Folder folder = dataManager.create(Folder.class);
        folder.setParent(folderService.getRoot());
        folder.setName("semantic-search-test-" + UUID.randomUUID());
        sandbox = folderService.save(folder);
    }

    @AfterEach
    void deleteSandbox() {
        dataManager.load(Id.of(sandbox))
                .optional()
                .ifPresent(folderService::delete);
    }

    @Test
    void theSearchLooksOnlyAmongTheDocumentsThePersonMayOpen() {
        Document lease = indexed("lease.txt", "The lessee takes a wheeled tractor for the field work.");
        Document hidden = TestAccess.giveOwnAccess(dataManager, indexed("hidden.txt",
                "A wheeled tractor and a combine for the field work."), Grant.user("maria", AccessLevel.VIEW));
        model.willReply("{\"verdicts\": [{\"number\": 1, \"fits\": true, \"quote\": \"wheeled tractor\"}]}");

        List<DocumentSearchHit> hits = asEmployee(() -> semanticSearch.search("wheeled tractor field work"));

        assertThat(hits).extracting(hit -> hit.document().getId()).containsExactly(lease.getId());
        assertThat(index.searchedAmong().getLast()).contains(lease.getId()).doesNotContain(hidden.getId());
        assertThat(model.prompts().getFirst().getUserMessage().getText()).doesNotContain("hidden.txt");
    }

    @Test
    void theModelKeepsTheDocumentsThatFitAndTheirQuoteIsMarked() {
        Document lease = indexed("lease.txt", "The lessee takes the MTZ-82 tractor for the field work in spring.");
        indexed("excavator.txt", "The lessee takes a crawler excavator for the field work on the site.");
        model.willReply("""
                {"verdicts": [{"number": 1, "fits": true, "quote": "MTZ-82 tractor"},
                              {"number": 2, "fits": false, "quote": ""}]}""");

        List<DocumentSearchHit> hits = semanticSearch.search("MTZ-82 tractor spring");

        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.document().getId()).isEqualTo(lease.getId());
            assertThat(hit.field()).isEqualTo("File text");
            assertThat(hit.snippet()).contains("the <b>MTZ-82 tractor</b> for");
            assertThat(hit.folderPath()).endsWith(sandbox.getName());
        });
    }

    @Test
    void aQuoteTheModelDidNotCopyIsNotMarked() {
        indexed("lease.txt", "The lessee takes the MTZ-82 tractor for the field work.");
        model.willReply("{\"verdicts\": [{\"number\": 1, \"fits\": true, \"quote\": \"a tractor MTZ\"}]}");

        assertThat(semanticSearch.search("tractor")).singleElement()
                .satisfies(hit -> assertThat(hit.snippet()).doesNotContain("<b>")
                        .startsWith("The lessee takes the MTZ-82 tractor"));
    }

    @Test
    void aQuoteLongerThanTheSnippetIsMarkedWhole() {
        String quote = "The lessee takes the MTZ-82 tractor" + " with the front loader and the trailer".repeat(10);
        indexed("lease.txt", "The lease of the farm machinery. ".repeat(20) + quote + ". The rent is paid monthly.");
        model.willReply("{\"verdicts\": [{\"number\": 1, \"quote\": \"" + quote + "\", \"fits\": true}]}");

        assertThat(semanticSearch.search("tractor")).singleElement()
                .satisfies(hit -> assertThat(hit.snippet()).contains("<b>" + quote + "</b>"));
    }

    @Test
    void aDecisionWithoutAQuoteShowsTheFragment() {
        indexed("lease.txt", "The lessee takes the MTZ-82 tractor for the field work.");
        model.willReply("{\"verdicts\": [{\"number\": 1, \"fits\": true}]}");

        assertThat(semanticSearch.search("tractor")).singleElement()
                .satisfies(hit -> assertThat(hit.snippet()).isEqualTo(
                        "The lessee takes the MTZ-82 tractor for the field work."));
    }

    @Test
    void aDocumentWithoutTextShowsItsName() {
        Document photo = upload("tractor-photo.png", "");
        index.put(photo.getId(), "key", List.of(new DocumentVectorIndex.Fragment(0, "tractor-photo.png", true,
                StubEmbeddingModel.vectorOf("tractor-photo.png"))));
        model.willReply("{\"verdicts\": [{\"number\": 1, \"fits\": true, \"quote\": \"tractor\"}]}");

        assertThat(semanticSearch.search("tractor photo")).singleElement()
                .satisfies(hit -> {
                    assertThat(hit.field()).isEqualTo("Name");
                    assertThat(hit.snippet()).isEqualTo("<b>tractor</b>-photo.png");
                });
    }

    @Test
    void theModelIsHeldToTheSchemaOfTheDecisions() {
        indexed("lease.txt", "The lessee takes the MTZ-82 tractor for the field work.");
        model.willReply("{\"verdicts\": [{\"number\": 1, \"fits\": false, \"quote\": \"\"}]}");

        assertThat(semanticSearch.search("tractor")).isEmpty();

        assertThat(model.prompts().getFirst().getOptions()).isInstanceOfSatisfying(StructuredOutputChatOptions.class,
                options -> {
                    JsonNode verdict = JsonMapper.shared()
                            .readTree(options.getOutputSchema())
                            .at("/properties/verdicts/items");

                    assertThat(verdict.at("/required").valueStream().map(JsonNode::asString))
                            .containsExactly("number", "quote", "fits");
                });
    }

    @Test
    void aBraceInAFragmentLeavesTheCheckWhole() {
        indexed("price.txt", "The price of the tractor is {price} per month, see the {annex} of the lease.");
        model.willReply("{\"verdicts\": [{\"number\": 1, \"fits\": true, \"quote\": \"tractor\"}]}");

        assertThat(semanticSearch.search("tractor lease")).hasSize(1);
        assertThat(model.prompts().getFirst().getUserMessage().getText()).contains("{price}");
    }

    @Test
    void theChatGetsTheDocumentsTheModelKeepsAsThePageDoes() {
        Document lease = indexed("lease.txt", "The lessee takes the MTZ-82 tractor for the field work in spring.");
        indexed("excavator.txt", "The lessee takes a crawler excavator for the field work on the site.");
        model.willReply("""
                {"verdicts": [{"number": 1, "fits": true, "quote": "MTZ-82 tractor"},
                              {"number": 2, "fits": false, "quote": ""}]}""");

        SemanticSearch.Found found = semanticSearch.passages("MTZ-82 tractor spring");

        assertThat(found.documents()).singleElement()
                .satisfies(passages -> assertThat(passages.document().getId()).isEqualTo(lease.getId()));
        assertThat(found.more()).isFalse();
    }

    @Test
    void theChatGetsThreeFragmentsOfTheFirstEightDocumentsAndOneOfTheRest() {
        for (int i = 0; i <= SemanticSearch.CHAT_DOCUMENTS; i++) {
            indexedFragments("tractor-" + i + ".txt", SemanticSearch.CHAT_FRAGMENTS + 1);
        }

        model.willReply(allFit(SemanticSearch.CHAT_DOCUMENTS + 1));

        SemanticSearch.Found found = semanticSearch.passages("tractor fragment");

        assertThat(found.documents()).hasSize(SemanticSearch.CHAT_DOCUMENTS + 1);
        assertThat(found.documents().subList(0, SemanticSearch.CHAT_DOCUMENTS))
                .allSatisfy(passages -> assertThat(passages.fragments()).hasSize(SemanticSearch.CHAT_FRAGMENTS));
        assertThat(found.documents().getLast().fragments()).hasSize(1);
        assertThat(found.more()).isFalse();
    }

    @Test
    void theChatGetsAsManyDocumentsAsThePageAtMost() {
        for (int i = 0; i <= SemanticSearch.PAGE_DOCUMENTS; i++) {
            indexedFragments("tractor-" + i + ".txt", 1);
        }

        model.willReply(allFit(SemanticSearch.PAGE_DOCUMENTS));

        SemanticSearch.Found found = semanticSearch.passages("tractor fragment");

        assertThat(found.documents()).hasSize(SemanticSearch.PAGE_DOCUMENTS);
        assertThat(found.more()).isTrue();
    }

    // long documents fill the nearest fragments the search takes, and the documents after them are not seen
    @Test
    void theChatHearsWhenTheNearestFragmentsMayHaveLeftDocumentsOut() {
        indexedFragments("short.txt", 2);
        model.willReply(allFit(1));

        assertThat(semanticSearch.passages("tractor fragment").more()).isFalse();

        indexedFragments("long-1.txt", 20);
        indexedFragments("long-2.txt", 20);
        model.willReply(allFit(3));
        SemanticSearch.Found found = semanticSearch.passages("tractor fragment");

        assertThat(found.documents()).hasSizeLessThan(SemanticSearch.CHAT_DOCUMENTS);
        assertThat(found.more()).isTrue();
    }

    @Test
    void anIndexThatDoesNotAnswerMakesTheSearchUnavailable() {
        indexed("lease.txt", "The lessee takes the MTZ-82 tractor for the field work.");
        index.setDown(true);

        assertThatThrownBy(() -> semanticSearch.search("tractor")).isInstanceOf(SearchUnavailableException.class);
        assertThatThrownBy(() -> semanticSearch.passages("tractor")).isInstanceOf(SearchUnavailableException.class);
    }

    private Document indexed(String fileName, String text) {
        Document document = upload(fileName, text);
        index.put(document.getId(), "key", List.of(fragment(0, text)));

        return document;
    }

    private void indexedFragments(String fileName, int count) {
        Document document = upload(fileName, "");
        index.put(document.getId(), "key", IntStream.range(0, count)
                .mapToObj(number -> fragment(number, "A tractor fragment " + number + " of " + fileName))
                .toList());
    }

    private Document upload(String fileName, String text) {
        return documentService.upload(sandbox, List.of(TestFiles.file(tempDir, fileName, text)))
                .created()
                .getFirst();
    }

    private static DocumentVectorIndex.Fragment fragment(int number, String text) {
        return new DocumentVectorIndex.Fragment(number, text, false, StubEmbeddingModel.vectorOf(text));
    }

    // the decisions of the check that keeps every candidate
    private static String allFit(int candidates) {
        return IntStream.rangeClosed(1, candidates)
                .mapToObj("{\"number\": %d, \"quote\": \"\", \"fits\": true}"::formatted)
                .collect(Collectors.joining(", ", "{\"verdicts\": [", "]}"));
    }

    private <T> T asEmployee(Supplier<T> search) {
        return systemAuthenticator.withUser(EMPLOYEE, search::get);
    }
}
