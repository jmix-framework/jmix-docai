package com.company.docai.app.listener.entity;

import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.model.document.Document;
import com.company.docai.model.document.DocumentStatus;
import io.jmix.core.FetchPlan;
import io.jmix.core.FetchPlanBuilder;
import io.jmix.core.Messages;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.event.AttributeChanges;
import io.jmix.core.event.EntityChangedEvent;
import io.jmix.data.PersistenceHints;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Keeps document rules on every save path: a new document is a draft, the status follows
 * its transitions, a live archived document keeps its name, date, type and version, and the current
 * revision belongs to the document.
 */
@Component
public class DocumentEventListener {

    private static final List<String> ARCHIVE_LOCKED_ATTRIBUTES = List.of("name", "documentDate", "category",
            "currentRevision");

    // the rules hold whoever saves, also when the save hides the item from them
    private final UnconstrainedDataManager dataManager;
    private final Messages messages;

    public DocumentEventListener(UnconstrainedDataManager dataManager, Messages messages) {
        this.dataManager = dataManager;
        this.messages = messages;
    }

    @EventListener
    public void onDocumentChanged(EntityChangedEvent<Document> event) {
        if (event.getType() == EntityChangedEvent.Type.DELETED) {
            return;
        }

        // a document in the trash changes too: access changes move it with the live ones
        Document document = dataManager.load(event.getEntityId())
                .fetchPlan(DocumentEventListener::fetchPlan)
                .hint(PersistenceHints.SOFT_DELETION, false)
                .one();

        AttributeChanges changes = event.getChanges();

        if (event.getType() == EntityChangedEvent.Type.CREATED) {
            if (document.getStatus() != DocumentStatus.DRAFT) {
                throw reject("newDocumentNotDraft");
            }
        } else {
            DocumentStatus oldStatus = changes.isChanged("status")
                    ? changes.getOldValue("status")
                    : document.getStatus();

            if (changes.isChanged("status") && !oldStatus.canTransitionTo(document.getStatus())) {
                throw reject("statusTransitionNotAllowed");
            }

            // the trash may change the documents it holds, and a restore brings one back with such changes
            boolean live = document.getDeletedDate() == null && !changes.isChanged("deletedDate");

            if (oldStatus == DocumentStatus.ARCHIVED && live
                    && ARCHIVE_LOCKED_ATTRIBUTES.stream().anyMatch(changes::isChanged)) {
                throw reject("archivedDocumentLocked");
            }
        }

        if (document.getCurrentRevision() != null && !document.getCurrentRevision().getDocument().equals(document)) {
            throw reject("currentRevisionOfAnotherDocument");
        }
    }

    private OperationRejectedException reject(String key) {
        return new OperationRejectedException(messages.getMessage(getClass(), key));
    }

    private static void fetchPlan(FetchPlanBuilder fetchPlan) {
        fetchPlan.addFetchPlan(FetchPlan.BASE)
                .add("currentRevision",
                        revision -> revision.add("document", FetchPlan.INSTANCE_NAME));
    }
}
