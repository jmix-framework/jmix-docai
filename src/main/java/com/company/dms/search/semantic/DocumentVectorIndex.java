package com.company.dms.search.semantic;

import jakarta.json.JsonObject;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.Refresh;
import org.opensearch.client.opensearch._types.aggregations.StringTermsBucket;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.core.search.Hit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The index of the fragments of the documents in OpenSearch: one entry per fragment with the id of its document, the
 * version and name it was cut from, its text and its vector. It finds the fragments nearest to a vector among the
 * documents it is given, so the rights of the person who searches decide what can be found.
 */
@Component
public class DocumentVectorIndex {

    private static final String MODEL = "model";
    private static final String DIMENSIONS = "dimensions";

    // the reconciliation reads the source keys of all the documents in one aggregation
    private static final int MAX_DOCUMENTS = 10_000;

    private final OpenSearchClient client;
    private final String indexName;
    private final String model;
    private final int dimensions;

    public DocumentVectorIndex(OpenSearchClient client,
                               @Value("${jmix.search.search-index-name-prefix}") String prefix,
                               @Value("${spring.ai.openai.embedding.model}") String model,
                               @Value("${dms.semantic.dimensions}") int dimensions) {
        this.client = client;
        this.indexName = prefix + "document_vectors";
        this.model = model;
        this.dimensions = dimensions;
    }

    /**
     * A fragment of a document with its vector.
     *
     * @param number the number of the fragment in the document, from 0
     * @param text   the text of the fragment as the person sees it, without the name of the document
     * @param ofName whether the fragment is the name of a document that has no text
     * @param vector the vector of the name and the text of the fragment
     */
    public record Fragment(int number, String text, boolean ofName, float[] vector) {
    }

    /**
     * A fragment found near a vector.
     *
     * @param documentId the id of the document of the fragment
     * @param text       the text of the fragment
     * @param ofName     whether the fragment is the name of a document that has no text
     */
    public record Match(UUID documentId, String text, boolean ofName) {
    }

    /**
     * Returns the name of the index in OpenSearch.
     */
    public String getIndexName() {
        return indexName;
    }

    /**
     * Creates the index when there is none, or creates it anew, empty, when it was built with another model or another
     * size of the vectors.
     *
     * @return whether the index is new
     */
    public synchronized boolean prepare() {
        try {
            boolean exists = client.indices()
                    .exists(request -> request.index(indexName))
                    .value();

            if (exists && builtWithTheModel()) {
                return false;
            }

            if (exists) {
                client.indices()
                        .delete(request -> request.index(indexName));
            }

            client.indices().create(request -> request.index(indexName)
                    .settings(settings -> settings.knn(true).numberOfShards(1).numberOfReplicas(0))
                    .mappings(mapping -> mapping
                            .meta(MODEL, JsonData.of(model))
                            .meta(DIMENSIONS, JsonData.of(dimensions))
                            .properties("documentId", property -> property.keyword(keyword -> keyword))
                            .properties("sourceKey", property -> property.keyword(keyword -> keyword))
                            .properties("number", property -> property.integer(number -> number))
                            .properties("ofName", property -> property.boolean_(flag -> flag))
                            .properties("text", property -> property.text(text -> text.index(false)))
                            .properties("vector", property -> property.knnVector(vector -> vector
                                    .dimension(dimensions)
                                    .method(method -> method.name("hnsw")
                                            .engine("lucene")
                                            .spaceType("cosinesimil"))))));

            return true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Replaces the fragments of a document in the index with the given ones, creating the index first when there is
     * none.
     *
     * @param sourceKey what the fragments were cut from, see {@link DocumentVectorIndexer#sourceKeyOf}
     */
    public void replace(UUID documentId, String sourceKey, List<Fragment> fragments) {
        // the documents a demo set loads at the start reach the queue before the check of the index creates it
        prepare();
        delete(List.of(documentId));

        List<BulkOperation> operations = fragments.stream()
                .map(fragment -> BulkOperation.of(operation -> operation.index(index -> index.index(indexName)
                        .id(documentId + ":" + fragment.number())
                        .document(source(documentId, sourceKey, fragment)))))
                .toList();

        try {
            BulkResponse response = client.bulk(request -> request.operations(operations).refresh(Refresh.WaitFor));

            if (response.errors()) {
                // a document with a part of its fragments would be found by them alone
                delete(List.of(documentId));

                throw new IllegalStateException("Cannot index the fragments of the document %s: %s"
                        .formatted(documentId, firstError(response)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Removes the documents from the index.
     */
    public void delete(Collection<UUID> documentIds) {
        if (documentIds.isEmpty()) {
            return;
        }

        try {
            client.deleteByQuery(request -> request.index(indexName)
                    .query(byDocuments(documentIds))
                    .refresh(Refresh.True));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Returns the fragments nearest to the vector among the fragments of the given documents, the nearest first.
     */
    public List<Match> nearest(float[] vector, Collection<UUID> documentIds, int count) {
        if (documentIds.isEmpty()) {
            return List.of();
        }

        SearchResponse<JsonData> response;

        try {
            response = client.search(request -> request.index(indexName)
                            .size(count)
                            .source(source -> source.filter(filter -> filter.excludes("vector")))
                            .query(query -> query.knn(knn -> knn.field("vector")
                                    .vector(toList(vector))
                                    .k(count)
                                    .filter(byDocuments(documentIds)))),
                    JsonData.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return response.hits()
                .hits()
                .stream()
                .map(Hit::source)
                .filter(Objects::nonNull)
                .map(source -> source.toJson().asJsonObject())
                .map(DocumentVectorIndex::match)
                .toList();
    }

    /**
     * Returns the documents in the index with the source keys of their fragments.
     */
    public Map<UUID, String> sourceKeys() {
        SearchResponse<Void> response;

        try {
            response = client.search(request -> request.index(indexName)
                            .size(0)
                            .aggregations("documents", documents -> documents
                                    .terms(terms -> terms.field("documentId").size(MAX_DOCUMENTS))
                                    .aggregations("sourceKey", keys -> keys
                                            .terms(terms -> terms.field("sourceKey").size(1)))),
                    Void.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        Map<UUID, String> keys = new HashMap<>();

        for (StringTermsBucket bucket : response.aggregations().get("documents").sterms().buckets().array()) {
            List<StringTermsBucket> sourceKeys = bucket.aggregations().get("sourceKey").sterms().buckets().array();
            keys.put(UUID.fromString(bucket.key()), sourceKeys.isEmpty() ? "" : sourceKeys.getFirst().key());
        }

        return keys;
    }

    private boolean builtWithTheModel() throws IOException {
        Map<String, JsonData> meta = client.indices()
                .getMapping(request -> request.index(indexName))
                .result()
                .get(indexName)
                .mappings()
                .meta();

        return meta.containsKey(MODEL) && meta.containsKey(DIMENSIONS)
                && model.equals(meta.get(MODEL).to(String.class))
                && dimensions == meta.get(DIMENSIONS).to(Integer.class);
    }

    private static Map<String, Object> source(UUID documentId, String sourceKey, Fragment fragment) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("documentId", documentId.toString());
        source.put("sourceKey", sourceKey);
        source.put("number", fragment.number());
        source.put("ofName", fragment.ofName());
        source.put("text", fragment.text());
        source.put("vector", toList(fragment.vector()));

        return source;
    }

    private static Match match(JsonObject source) {
        return new Match(UUID.fromString(source.getString("documentId")), source.getString("text"),
                source.getBoolean("ofName", false));
    }

    private static Query byDocuments(Collection<UUID> documentIds) {
        List<FieldValue> values = documentIds.stream()
                .map(id -> FieldValue.of(id.toString()))
                .toList();

        return Query.of(query -> query.terms(terms -> terms.field("documentId").terms(field -> field.value(values))));
    }

    private static String firstError(BulkResponse response) {
        return response.items()
                .stream()
                .filter(item -> item.error() != null)
                .map(item -> item.error().reason())
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("no reason given");
    }

    private static List<Float> toList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);

        for (float value : vector) {
            values.add(value);
        }

        return values;
    }
}
