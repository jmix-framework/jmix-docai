package com.company.docai.ai.assistant;

import com.company.docai.app.service.discussion.DocumentDiscussionService;
import com.company.docai.model.document.Document;
import io.jmix.aitools.tool.AiToolStatusUpdate;
import io.jmix.aitoolsflowui.model.AiChatMessage;
import io.jmix.aitoolsflowui.model.AiChatMessageType;
import io.jmix.aitoolsflowui.model.AiConversation;
import io.jmix.aitoolsflowui.service.AiChatMessageService;
import io.jmix.aitoolsflowui.service.AiChatService;
import io.jmix.aitoolsflowui.service.AiConversationService;
import io.jmix.aitoolsflowui.view.chat.support.ConversationTitleSupport;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A discussion of a selection of documents with the assistant, as a side window of the repository holds it: the
 * selection, and the conversation of the AI Tools add-on that the first question starts with it, so the conversation
 * is in the history of the assistant. Each window gets its own instance.
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class DocumentDiscussion {

    private final DocumentDiscussionService discussionService;
    private final AiConversationService conversationService;
    private final AiChatMessageService messageService;
    private final AiChatService chatService;
    private final ConversationTitleSupport titleSupport;
    private final int maxDocuments;

    private final List<Document> documents = new ArrayList<>();
    private @Nullable AiConversation conversation;

    public DocumentDiscussion(DocumentDiscussionService discussionService,
                              AiConversationService conversationService,
                              AiChatMessageService messageService,
                              AiChatService chatService,
                              ConversationTitleSupport titleSupport,
                              @Value("${docai.ai.discussion.max-documents:10}") int maxDocuments) {
        this.discussionService = discussionService;
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.chatService = chatService;
        this.titleSupport = titleSupport;
        this.maxDocuments = maxDocuments;
    }

    /**
     * Returns the documents of the selection in the order in which they were added.
     */
    public synchronized List<Document> getDocuments() {
        return List.copyOf(documents);
    }

    /**
     * Returns the conversation, which starts with the first question.
     */
    public synchronized Optional<AiConversation> getConversation() {
        return Optional.ofNullable(conversation);
    }

    /**
     * Returns how many documents the selection takes at most.
     */
    public int getMaxDocuments() {
        return maxDocuments;
    }

    /**
     * Adds the documents that are not in the selection yet, while there is room for them.
     *
     * @return whether all of them found room
     */
    public synchronized boolean add(Collection<Document> added) {
        boolean fit = true;

        for (Document document : added) {
            if (documents.contains(document)) {
                continue;
            }

            if (documents.size() >= maxDocuments) {
                fit = false;
                break;
            }

            documents.add(document);
        }

        store();

        return fit;
    }

    /**
     * Takes the document out of the selection.
     */
    public synchronized void remove(Document document) {
        documents.remove(document);
        store();
    }

    /**
     * Asks the question in the conversation, starting it with the selection on the first question, and returns the
     * answer as the conversation keeps it: with links to the cards of the documents it names.
     *
     * @param statuses receives what the tools of the assistant do meanwhile
     */
    public String ask(String question, Consumer<AiToolStatusUpdate> statuses) {
        AiConversation current = conversationFor(question);
        AiChatMessage message = messageService.createMessage(current, AiChatMessageType.USER, question);

        // the add-on returns the answer of the model; the conversation keeps it with the links made by the service
        chatService.processMessage(message, statuses);

        // a document the person may no longer open, or one deleted meanwhile, leaves the selection
        List<Document> readable = discussionService.documentsOf(current.getId());

        synchronized (this) {
            documents.retainAll(readable);
        }

        AiChatMessage answer = messageService.loadLatestMessage(current, AiChatMessageType.ASSISTANT);

        if (answer == null) {
            throw new IllegalStateException("No answer in conversation " + current.getId());
        }

        return answer.getContent();
    }

    private synchronized AiConversation conversationFor(String question) {
        if (conversation == null) {
            AiConversation created = conversationService.create();
            created.setTitle(titleSupport.buildInitialTitle(question));
            conversation = conversationService.save(created);
            store();
        }

        return conversation;
    }

    private void store() {
        if (conversation != null) {
            discussionService.saveSelection(conversation.getId(), List.copyOf(documents));
        }
    }
}
