package com.company.docai.demo;

import com.company.docai.ai.AiSettings;
import com.company.docai.search.semantic.DocumentVectorIndex;
import com.company.docai.search.semantic.DocumentVectorIndexer;
import com.company.docai.search.semantic.SemanticSearch;
import io.jmix.core.TimeSource;
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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The English demo set with the OpenAI models and OpenSearch: the AI search for agricultural machinery finds the
 * twelve documents about farm equipment and nothing else, and an employee who may not open the archive of the last
 * year finds nine.
 * Runs only with {@code ./gradlew aiTest}, the key in {@code OPENAI_API_KEY} and OpenSearch from {@code compose.yaml};
 * the set goes into a database and an index of its own, and the test deletes the index.
 */
@Tag("ai")
@SpringBootTest(properties = {"main.datasource.url=jdbc:hsqldb:mem:demo-live",
        "jmix.search.search-index-name-prefix=docai_test_demo_"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveDemoSetTest {

    private static final String TOPIC = "agricultural machinery";

    // the documents of the set about farm equipment that lie in Procurement, by their names in the description, which
    // write the years and the dates of the day of the load
    private static final List<String> PROCUREMENT = List.of(
            "Equipment Lease Agreement No. 14 — Corwin 82 Tractor",
            "Equipment Purchase Agreement No. 37/{year:signed} — Combine",
            "Schedule 2 to Supply Agreement No. 31/{year:contract}",
            "Delivery Receipt — Self-Propelled Sprayer",
            "Installation Contract No. 22/{year:signed} — Center Pivot Irrigation Machine",
            "Equipment Purchase Agreement No. 11/{year:signed} — Crawler Tractor",
            "Invoice No. 405",
            "Memo No. 18",
            "Master Supply Agreement No. 3/{year:signed}");

    // and in the archive of the last year, which peter may not open
    private static final List<String> ARCHIVE = List.of(
            "Invoice No. 1187",
            "Finance Lease Agreement No. L-17/{year:signed}",
            "Master Service Agreement No. 9/{year:signed} — Forage Harvester");

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

    @Autowired
    private TimeSource timeSource;

    // the names of the documents as the set is loaded today
    private List<String> procurement = List.of();
    private List<String> archive = List.of();

    @BeforeAll
    void loadTheSet() throws IOException {
        assertThat(aiSettings.isAvailable()).isTrue();

        DemoSetTemplate template = DemoSetTemplate.read("en");
        DemoSet generated = template.generate("en", timeSource.now().toLocalDate());
        procurement = namesOf(PROCUREMENT, template.folders(), generated.folders());
        archive = namesOf(ARCHIVE, template.folders(), generated.folders());

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
                .containsExactlyInAnyOrderElementsOf(Stream.concat(procurement.stream(), archive.stream()).toList());
    }

    @Test
    void anEmployeeWhoMayNotOpenTheArchiveFindsNine() {
        assertThat(found("peter")).containsExactlyInAnyOrderElementsOf(procurement);
    }

    // the generated names of the documents the description names so, in the same places of the set
    private static List<String> namesOf(List<String> written, List<DemoSetTemplate.Folder> template,
                                        List<DemoSet.Folder> generated) {
        List<String> names = new ArrayList<>();

        for (int folder = 0; folder < template.size(); folder++) {
            List<DemoSetTemplate.Document> documents = template.get(folder).documents();

            for (int document = 0; document < documents.size(); document++) {
                if (written.contains(documents.get(document).name())) {
                    names.add(generated.get(folder).documents().get(document).name());
                }
            }

            names.addAll(namesOf(written, template.get(folder).folders(), generated.get(folder).folders()));
        }

        return names;
    }

    private List<String> found(String username) {
        return systemAuthenticator.withUser(username, () -> semanticSearch.search(TOPIC)
                .stream()
                .map(hit -> hit.document().getName())
                .toList());
    }
}
