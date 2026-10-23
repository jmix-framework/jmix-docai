package com.company.dms.app.listener.entity;

import com.company.dms.app.service.OperationRejectedException;
import com.company.dms.model.document.Document;
import com.company.dms.model.document.DocumentRevision;
import io.jmix.core.Id;
import io.jmix.core.Messages;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.event.EntityChangedEvent;
import io.jmix.data.PersistenceHints;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * A saved revision never changes, and it is deleted only together with its document.
 */
@Component
public class DocumentRevisionEventListener {

    private static final Set<String> DELETION_ATTRIBUTES = Set.of("deletedDate", "deletedBy");

    // the rules hold whoever saves, also when the save hides the item from them
    private final UnconstrainedDataManager dataManager;
    private final Messages messages;

    public DocumentRevisionEventListener(UnconstrainedDataManager dataManager, Messages messages) {
        this.dataManager = dataManager;
        this.messages = messages;
    }

    @EventListener
    public void onRevisionChanged(EntityChangedEvent<DocumentRevision> event) {
        switch (event.getType()) {
            case CREATED -> {
                // do nothing
            }
            case UPDATED -> {
                if (!DELETION_ATTRIBUTES.containsAll(event.getChanges().getAttributes())) {
                    throw reject("revisionImmutable");
                }
            }
            case DELETED -> {
                Id<Document> documentId = event.getChanges().getOldReferenceId("document");
                Document document = dataManager.load(documentId)
                        .hint(PersistenceHints.SOFT_DELETION, false)
                        .one();

                if (document.getDeletedDate() == null) {
                    throw reject("revisionDeletedAlone");
                }
            }
        }
    }

    private OperationRejectedException reject(String key) {
        return new OperationRejectedException(messages.getMessage(getClass(), key));
    }
}
