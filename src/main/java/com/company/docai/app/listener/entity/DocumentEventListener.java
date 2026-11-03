package com.company.docai.app.listener.entity;

import com.company.docai.app.service.OperationRejectedException;
import com.company.docai.app.service.document.DocumentStatusService;
import com.company.docai.model.document.Document;
import com.company.docai.model.document.DocumentStatus;
import io.jmix.core.FetchPlan;
import io.jmix.core.FetchPlanBuilder;
import io.jmix.core.Messages;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.event.AttributeChanges;
import io.jmix.core.event.EntityChangedEvent;
import io.jmix.core.event.EntitySavingEvent;
import io.jmix.data.PersistenceHints;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Keeps document rules on every save path: a new document gets the initial status, the status follows
 * the transitions of its status, a live document in a locked status keeps its name, date, type and version,
 * and the current revision belongs to the document.
 */
@Component
public class DocumentEventListener {

    private static final List<String> LOCKED_ATTRIBUTES = List.of("name", "documentDate", "category",
            "currentRevision");

    // the rules hold whoever saves, also when the save hides the item from them
    private final UnconstrainedDataManager dataManager;
    private final DocumentStatusService statusService;
    private final Messages messages;

    public DocumentEventListener(UnconstrainedDataManager dataManager, DocumentStatusService statusService,
                                 Messages messages) {
        this.dataManager = dataManager;
        this.statusService = statusService;
        this.messages = messages;
    }

    // a new document gets the initial status on every save path: an upload, the card, the demo sets, the tests
    @EventListener
    public void onDocumentSaving(EntitySavingEvent<Document> event) {
        if (event.isNewEntity() && event.getEntity().getStatus() == null) {
            event.getEntity().setStatus(statusService.getInitial());
        }
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
            if (!Boolean.TRUE.equals(statusService.reload(document.getStatus()).getInitial())) {
                throw reject("newDocumentNotInitial");
            }
        } else {
            DocumentStatus oldStatus = changes.isChanged("status")
                    ? dataManager.load(Objects.requireNonNull(changes.<DocumentStatus>getOldReferenceId("status")))
                            .fetchPlan(status -> status.addFetchPlan(FetchPlan.BASE)
                                    .add("targets", FetchPlan.BASE))
                            .one()
                    : statusService.reload(document.getStatus());

            if (changes.isChanged("status") && !oldStatus.canMoveTo(document.getStatus())) {
                throw reject("statusTransitionNotAllowed");
            }

            // the trash may change the documents it holds, and a restore brings one back with such changes
            boolean live = document.getDeletedDate() == null && !changes.isChanged("deletedDate");

            if (Boolean.TRUE.equals(oldStatus.getLocked()) && live
                    && LOCKED_ATTRIBUTES.stream().anyMatch(changes::isChanged)) {
                throw new OperationRejectedException(messages.formatMessage(getClass(),
                        "lockedDocumentKeepsContent", oldStatus.getName()));
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
                        revision -> revision.add("document", FetchPlan.INSTANCE_NAME))
                .add("status", status -> status.addFetchPlan(FetchPlan.BASE)
                        .add("targets", FetchPlan.BASE));
    }
}
