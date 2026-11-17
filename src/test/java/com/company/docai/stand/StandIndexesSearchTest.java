package com.company.docai.stand;

import com.company.docai.DocAiApplication;
import com.company.docai.model.document.Document;
import com.company.docai.search.DocumentSearch;
import io.jmix.core.DataManager;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The indexes of the stand against OpenSearch from {@code compose.yaml}: the copies of a template search indexes of
 * their own, so a document renamed in one copy is found there and not in another, and a deleted copy leaves no index
 * behind. The loop of the stand processes the queues. Runs only with {@code ./gradlew searchTest}.
 */
@Tag("search")
@SpringBootTest(classes = DocAiApplication.class, properties = "docai.stand.data-dir=build/test-stand-search")
@ActiveProfiles({"test", "stand"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StandIndexesSearchTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private TemplateBuilder templateBuilder;

    @Autowired
    private CopyFactory copyFactory;

    @Autowired
    private StandIndexes indexes;

    @Autowired
    private DocumentSearch documentSearch;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @Autowired
    private OpenSearchClient client;

    @Value("${jmix.search.search-index-name-prefix}")
    private String prefix;

    private final List<String> copies = new ArrayList<>();

    @BeforeAll
    void buildTheTemplate() {
        templateBuilder.build("en");
    }

    @AfterEach
    void deleteTheCopies() {
        copies.forEach(copyFactory::delete);
        copies.clear();
    }

    @AfterAll
    void deleteTheIndexesOfTheTemplate() {
        indexes.delete(StandScope.template("en"));
    }

    @Test
    void aRenamedDocumentIsFoundOnlyInItsCopy() throws Exception {
        String renamed = newCopy();
        String other = newCopy();
        String word = "standword" + System.nanoTime();
        UUID document = inCopy(renamed, this::anyDocument);

        inCopy(renamed, () -> rename(document, word));

        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(found(renamed, word)).containsExactly(document));
        assertThat(found(other, word)).isEmpty();
    }

    @Test
    void aDeletedCopyLeavesNoIndex() {
        String key = newCopy();
        String scope = prefix + StandScope.copy(key).id();
        List<String> names = List.of(scope + "_document", scope + "_document_vectors");

        assertThat(names).allMatch(this::exists);

        copyFactory.delete(key);
        copies.remove(key);

        assertThat(names).noneMatch(this::exists);
    }

    private String newCopy() {
        String key = copyFactory.create("en").key();
        copies.add(key);

        return key;
    }

    private <T> T inCopy(String key, Supplier<T> work) throws Exception {
        return StandScopeHolder.call(StandScope.copy(key), () -> systemAuthenticator.withSystem(work::get));
    }

    private UUID anyDocument() {
        return dataManager.loadValue("select d.id from Document d order by d.name", UUID.class)
                .maxResults(1)
                .one();
    }

    private UUID rename(UUID id, String word) {
        Document document = dataManager.load(Document.class)
                .id(id)
                .one();

        document.setName(document.getName() + " " + word);
        dataManager.saveWithoutReload(document);

        return id;
    }

    // what the visitor of the copy finds by the word
    private List<UUID> found(String key, String word) throws Exception {
        return StandScopeHolder.call(StandScope.copy(key), () -> systemAuthenticator.withUser("admin",
                () -> documentSearch.search(word)
                        .hits()
                        .stream()
                        .map(hit -> hit.document().getId())
                        .toList()));
    }

    private boolean exists(String index) {
        try {
            return client.indices()
                    .exists(request -> request.index(index))
                    .value();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
