package com.company.docai.app.service.discussion;

import com.company.docai.model.discussion.DiscussedDocument;
import com.company.docai.model.document.Document;
import io.jmix.aitoolsflowui.service.AiConversationService;
import io.jmix.aitoolsflowuidata.entity.AiConversationEntity;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.SaveContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Keeps the selections of documents that conversations of the assistant are about.
 */
@Service
public class DocumentDiscussionService {

    private final DataManager dataManager;
    private final AiConversationService conversationService;

    public DocumentDiscussionService(DataManager dataManager, AiConversationService conversationService) {
        this.dataManager = dataManager;
        this.conversationService = conversationService;
    }

    /**
     * Returns the documents of the selection of the conversation that the person who asks may open, in the order
     * in which they were added.
     */
    public List<Document> documentsOf(UUID conversationId) {
        return readable(discussed(conversationId).stream()
                .map(DiscussedDocument::getDocument)
                .filter(Objects::nonNull)
                .map(Document::getId)
                .toList());
    }

    /**
     * Makes the given documents, in the given order, the selection of the conversation. A document the person who
     * asks may not open, or one deleted meanwhile, stays out of it.
     *
     * @throws IllegalArgumentException if the conversation is not one of the person who asks
     */
    public void saveSelection(UUID conversationId, List<Document> documents) {
        // the rows load only for the owner of the conversation, and only the owner may change them
        if (conversationService.loadConversation(conversationId) == null) {
            throw new IllegalArgumentException("No conversation %s of the current user".formatted(conversationId));
        }

        // a reference to a deleted document fails the save, as the deleted one no longer loads
        List<Document> readable = readable(documents.stream()
                .map(Document::getId)
                .toList());

        List<DiscussedDocument> stored = discussed(conversationId);

        Set<UUID> wanted = readable.stream()
                .map(Document::getId)
                .collect(Collectors.toSet());

        Map<UUID, DiscussedDocument> byDocument = stored.stream()
                .filter(entry -> entry.getDocument() != null)
                .collect(Collectors.toMap(entry -> entry.getDocument().getId(), Function.identity()));

        SaveContext context = new SaveContext();

        stored.stream()
                .filter(entry -> entry.getDocument() == null || !wanted.contains(entry.getDocument().getId()))
                .forEach(context::removing);

        AiConversationEntity conversation = dataManager.getReference(AiConversationEntity.class, conversationId);

        for (int position = 0; position < readable.size(); position++) {
            Document document = readable.get(position);
            DiscussedDocument entry = byDocument.get(document.getId());

            if (entry == null) {
                entry = dataManager.create(DiscussedDocument.class);
                entry.setConversation(conversation);
                entry.setDocument(document);
            }

            if (!Objects.equals(entry.getPosition(), position)) {
                entry.setPosition(position);
                context.saving(entry);
            }
        }

        dataManager.save(context.setDiscardSaved(true));
    }

    // the documents load on their own, so the access of the person decides which of them come back
    private List<Document> readable(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }

        Map<UUID, Document> loaded = dataManager.load(Document.class)
                .query("select d from Document d where d.id in :ids")
                .parameter("ids", ids)
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("category", FetchPlan.INSTANCE_NAME))
                .list()
                .stream()
                .collect(Collectors.toMap(Document::getId, Function.identity()));

        return ids.stream()
                .map(loaded::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private List<DiscussedDocument> discussed(UUID conversationId) {
        return dataManager.load(DiscussedDocument.class)
                .query("e.conversation.id = :conversationId order by e.position")
                .parameter("conversationId", conversationId)
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("document", FetchPlan.INSTANCE_NAME))
                .list();
    }
}
