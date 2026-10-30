package com.company.docai.demo;

import com.company.docai.ai.AiSettings;
import com.company.docai.search.semantic.DocumentVectorIndex;
import com.company.docai.search.semantic.DocumentVectorIndexer;
import com.company.docai.search.semantic.SemanticSearch;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The English demo set with the OpenAI models and OpenSearch: the AI search for agricultural machinery finds the
 * twelve documents about farm equipment and nothing else, and an employee who may not open Archive 2025 finds nine.
 * Runs only with {@code ./gradlew aiTest}, the key in {@code OPENAI_API_KEY} and OpenSearch from {@code compose.yaml};
 * the set goes into a database and an index of its own, and the test deletes the index.
 */
@Tag("ai")
@SpringBootTest(properties = {"main.datasource.url=jdbc:hsqldb:mem:demo-live",
        "jmix.search.search-index-name-prefix=docai_test_demo_"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveDemoSetTest {

    private static final String TOPIC = "agricultural machinery";

    // the documents of the set about farm equipment that lie in Procurement
    private static final List<String> PROCUREMENT = List.of(
            "Lease Agreement No. 14/A-26 — MTZ-82 Tractor",
            "Purchase and Sale Agreement No. 07-K/2026 — Combine Harvester",
            "Specification No. 2 to Supply Agreement No. 31/2026",
            "Delivery and Acceptance Certificate dated June 9, 2026",
            "Work Contract No. 22-M/2026 — Irrigation Machine Installation",
            "Purchase and Sale Agreement No. 11/2026 — DT-75M Crawler Tractor",
            "Invoice No. 405",
            "Memo No. 18",
            "Framework Supply Agreement No. 3/2026");

    // and in Archive 2025, which peter may not open
    private static final List<String> ARCHIVE = List.of(
            "Invoice No. 1187",
            "Finance Lease Agreement No. L-17/2025",
            "Framework Maintenance Agreement No. 9/2025");

    @Autowired
    private DemoDataLoader loader;

    @Autowired
    private SemanticSearch semanticSearch;

    @Autowired
    private DocumentVectorIndexer indexer;

    @Autowired
    private DocumentVectorIndex index;

    @Autowired
    private OpenSearchClient client;

    @Autowired
    private AiSettings aiSettings;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @BeforeAll
    void loadTheSet() {
        assertThat(aiSettings.isAvailable()).isTrue();

        loader.load("en");

        await().atMost(Duration.ofMinutes(3))
                .until(() -> !indexer.isBusy());
    }

    @AfterAll
    void deleteTheIndex() throws IOException {
        client.indices().delete(request -> request.index(index.getIndexName()));
    }

    @RepeatedTest(3)
    void theAiSearchFindsTheFarmEquipmentAndNothingElse() {
        assertThat(found("admin"))
                .containsExactlyInAnyOrderElementsOf(Stream.concat(PROCUREMENT.stream(), ARCHIVE.stream()).toList());
    }

    @Test
    void anEmployeeWhoMayNotOpenTheArchiveFindsNine() {
        assertThat(found("peter")).containsExactlyInAnyOrderElementsOf(PROCUREMENT);
    }

    private List<String> found(String username) {
        return systemAuthenticator.withUser(username, () -> semanticSearch.search(TOPIC)
                .stream()
                .map(hit -> hit.document().getName())
                .toList());
    }
}
