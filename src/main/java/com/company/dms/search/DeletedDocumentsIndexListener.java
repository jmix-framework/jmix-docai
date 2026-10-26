package com.company.dms.search;

import com.company.dms.model.document.Document;
import io.jmix.core.Id;
import io.jmix.core.event.EntityChangedEvent;
import io.jmix.search.index.EntityIndexer;
import io.jmix.search.index.queue.IndexingQueueManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Removes a deleted document from the search index once the deletion is committed. The add-on enqueues the removal
 * itself, but when the same transaction enqueues the document for indexing through its folder or version, the queue
 * keeps only the later item, and in Jmix 3.1.0 indexing a deleted document does nothing: the document would stay
 * in the index.
 */
@Component
public class DeletedDocumentsIndexListener {

    private static final Logger log = LoggerFactory.getLogger(DeletedDocumentsIndexListener.class);

    private final EntityIndexer entityIndexer;
    private final IndexingQueueManager indexingQueueManager;

    public DeletedDocumentsIndexListener(EntityIndexer entityIndexer, IndexingQueueManager indexingQueueManager) {
        this.entityIndexer = entityIndexer;
        this.indexingQueueManager = indexingQueueManager;
    }

    @TransactionalEventListener
    public void onDocumentChanged(EntityChangedEvent<Document> event) {
        if (event.getType() != EntityChangedEvent.Type.DELETED) {
            return;
        }

        Id<Document> id = event.getEntityId();

        try {
            if (!entityIndexer.deleteByEntityId(id).hasFailures()) {
                return;
            }
        } catch (RuntimeException e) {
            log.warn("Failed to remove {} from the search index: {}", id, e.getMessage());
        }

        // OpenSearch does not answer: the removal waits in the queue, after the indexing item
        indexingQueueManager.enqueueDeleteByEntityId(id);
    }
}
