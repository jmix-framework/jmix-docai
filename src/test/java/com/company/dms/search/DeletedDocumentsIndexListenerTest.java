package com.company.dms.search;

import com.company.dms.model.document.Document;
import io.jmix.core.Id;
import io.jmix.core.event.AttributeChanges;
import io.jmix.core.event.EntityChangedEvent;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.search.index.EntityIndexer;
import io.jmix.search.index.IndexResult;
import io.jmix.search.index.queue.IndexingQueueManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The removal of a deleted document from the index, with OpenSearch replaced: the paths that a test against
 * a running OpenSearch cannot take.
 */
class DeletedDocumentsIndexListenerTest {

    private final EntityIndexer entityIndexer = mock(EntityIndexer.class);
    private final IndexingQueueManager indexingQueueManager = mock(IndexingQueueManager.class);
    private final DeletedDocumentsIndexListener listener =
            new DeletedDocumentsIndexListener(entityIndexer, indexingQueueManager);

    private final Id<Document> id = Id.of(UUID.randomUUID(), Document.class);

    @Test
    void aDeletedDocumentIsRemovedFromTheIndexAtOnce() {
        when(entityIndexer.deleteByEntityId(id)).thenReturn(new IndexResult(1, List.of()));

        listener.onDocumentChanged(event(EntityChangedEvent.Type.DELETED));

        verify(entityIndexer).deleteByEntityId(id);
        verify(indexingQueueManager, never()).enqueueDeleteByEntityId(any());
    }

    @Test
    void theRemovalWaitsInTheQueueWhenOpenSearchDoesNotAnswer() {
        when(entityIndexer.deleteByEntityId(id)).thenThrow(new RuntimeException("Connection refused"));

        listener.onDocumentChanged(event(EntityChangedEvent.Type.DELETED));

        verify(indexingQueueManager).enqueueDeleteByEntityId(id);
    }

    @Test
    void theRemovalWaitsInTheQueueWhenOpenSearchRefusesIt() {
        IndexResult failed = mock(IndexResult.class);
        when(failed.hasFailures()).thenReturn(true);
        when(entityIndexer.deleteByEntityId(id)).thenReturn(failed);

        listener.onDocumentChanged(event(EntityChangedEvent.Type.DELETED));

        verify(indexingQueueManager).enqueueDeleteByEntityId(id);
    }

    @Test
    void otherChangesAreLeftToTheAddOn() {
        listener.onDocumentChanged(event(EntityChangedEvent.Type.UPDATED));

        verifyNoInteractions(entityIndexer, indexingQueueManager);
    }

    private EntityChangedEvent<Document> event(EntityChangedEvent.Type type) {
        return new EntityChangedEvent<>(this, id, type, mock(AttributeChanges.class), mock(MetaClass.class));
    }
}
