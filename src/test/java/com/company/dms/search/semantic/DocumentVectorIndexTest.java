package com.company.dms.search.semantic;

import com.company.dms.test_support.StubEmbeddingModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The index of fragments against OpenSearch from {@code compose.yaml}: it is built for the model, keeps the latest
 * fragments of a document and finds the nearest ones only among the documents it is given. Runs only with
 * {@code ./gradlew searchTest}.
 */
@Tag("search")
@SpringBootTest
class DocumentVectorIndexTest {

    @Autowired
    private DocumentVectorIndex index;

    @Autowired
    private OpenSearchClient client;

    @Value("${jmix.search.search-index-name-prefix}")
    private String prefix;

    @BeforeEach
    @AfterEach
    void deleteIndex() throws IOException {
        if (client.indices().exists(request -> request.index(index.getIndexName())).value()) {
            client.indices().delete(request -> request.index(index.getIndexName()));
        }
    }

    @Test
    void theIndexIsBuiltForTheModelAndAnewForAnotherOne() {
        UUID document = UUID.randomUUID();

        assertThat(index.prepare()).isTrue();
        index.replace(document, "key", List.of(fragment(0, "A tractor for the field work")));
        assertThat(index.prepare()).isFalse();
        assertThat(index.sourceKeys()).containsOnlyKeys(document);

        DocumentVectorIndex another = new DocumentVectorIndex(client, prefix, "another-model",
                StubEmbeddingModel.DIMENSIONS);

        assertThat(another.prepare()).isTrue();
        assertThat(index.sourceKeys()).isEmpty();
    }

    @Test
    void aDocumentKeepsOnlyItsLatestFragments() {
        UUID document = UUID.randomUUID();
        index.prepare();

        index.replace(document, "first", List.of(fragment(0, "A tractor"), fragment(1, "A combine"),
                fragment(2, "A seed drill")));
        index.replace(document, "second", List.of(new DocumentVectorIndex.Fragment(0, "tractor-photo.png", true,
                StubEmbeddingModel.vectorOf("tractor-photo.png"))));

        assertThat(index.sourceKeys()).isEqualTo(Map.of(document, "second"));
        assertThat(index.nearest(StubEmbeddingModel.vectorOf("tractor"), List.of(document), 10))
                .containsExactly(new DocumentVectorIndex.Match(document, "tractor-photo.png", true));
    }

    @Test
    void theNearestFragmentsComeOnlyFromTheGivenDocuments() {
        UUID open = UUID.randomUUID();
        UUID closed = UUID.randomUUID();
        index.prepare();
        index.replace(open, "key", List.of(fragment(0, "A wheeled tractor for the farm"),
                fragment(1, "The office furniture")));
        index.replace(closed, "key", List.of(fragment(0, "A wheeled tractor for the field work on the farm")));
        float[] query = StubEmbeddingModel.vectorOf("wheeled tractor field work farm");

        assertThat(index.nearest(query, List.of(open), 10))
                .extracting(DocumentVectorIndex.Match::documentId)
                .containsOnly(open);
        assertThat(index.nearest(query, List.of(open, closed), 10))
                .extracting(DocumentVectorIndex.Match::text)
                .startsWith("A wheeled tractor for the field work on the farm", "A wheeled tractor for the farm");
        assertThat(index.nearest(query, List.of(), 10)).isEmpty();
    }

    @Test
    void deletedDocumentsLeaveTheIndex() {
        UUID kept = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        index.prepare();
        index.replace(kept, "key", List.of(fragment(0, "A tractor")));
        index.replace(deleted, "key", List.of(fragment(0, "A combine")));

        index.delete(List.of(deleted));

        assertThat(index.sourceKeys()).containsOnlyKeys(kept);
    }

    private static DocumentVectorIndex.Fragment fragment(int number, String text) {
        return new DocumentVectorIndex.Fragment(number, text, false, StubEmbeddingModel.vectorOf(text));
    }
}
