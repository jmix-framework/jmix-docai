package com.company.docai.search;

import com.company.docai.model.document.Document;
import io.jmix.core.Metadata;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.search.SearchProperties;
import io.jmix.search.index.mapping.IndexConfigurationManager;
import io.jmix.search.index.queue.IndexingQueueManager;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Fills the document index when it is empty while live documents exist: in Jmix 3.1.0 the add-on fails to enqueue them
 * at startup without a user.
 */
@Component
public class SearchIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexInitializer.class);

    private final SearchProperties searchProperties;
    private final IndexConfigurationManager indexConfigurationManager;
    private final IndexingQueueManager indexingQueueManager;
    private final OpenSearchClient openSearchClient;
    private final UnconstrainedDataManager dataManager;
    private final Metadata metadata;
    private final SystemAuthenticator systemAuthenticator;

    public SearchIndexInitializer(SearchProperties searchProperties,
                                  IndexConfigurationManager indexConfigurationManager,
                                  IndexingQueueManager indexingQueueManager,
                                  OpenSearchClient openSearchClient,
                                  UnconstrainedDataManager dataManager,
                                  Metadata metadata,
                                  SystemAuthenticator systemAuthenticator) {
        this.searchProperties = searchProperties;
        this.indexConfigurationManager = indexConfigurationManager;
        this.indexingQueueManager = indexingQueueManager;
        this.openSearchClient = openSearchClient;
        this.dataManager = dataManager;
        this.metadata = metadata;
        this.systemAuthenticator = systemAuthenticator;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (searchProperties.isEnabled()) {
            systemAuthenticator.runWithSystem(this::fillEmptyIndex);
        }
    }

    /**
     * Enqueues every live document when the document index is empty and there are documents.
     *
     * @return whether the documents were enqueued
     */
    public boolean fillEmptyIndex() {
        String entityName = metadata.getClass(Document.class).getName();
        String indexName = indexConfigurationManager.getIndexConfigurationByEntityName(entityName).getIndexName();

        try {
            if (indexedCount(indexName) > 0 || !hasDocuments()) {
                return false;
            }
        } catch (IOException | OpenSearchException e) {
            log.warn("Search index '{}' is unavailable, the documents are not enqueued: {}", indexName, e.getMessage());
            return false;
        }

        indexingQueueManager.initAsyncEnqueueIndexAll(entityName);
        log.info("Search index '{}' is empty, all documents are enqueued", indexName);

        return true;
    }

    private long indexedCount(String indexName) throws IOException {
        return openSearchClient.count(request -> request.index(indexName)).count();
    }

    private boolean hasDocuments() {
        return dataManager.load(Document.class)
                .all()
                .maxResults(1)
                .optional()
                .isPresent();
    }
}
