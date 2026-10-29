package com.company.dms.ai.assistant;

import com.company.dms.ai.DocumentTextReader;
import com.company.dms.ai.classification.ClassificationProposal;
import com.company.dms.ai.classification.DocumentClassifier;
import com.company.dms.model.document.Document;
import io.jmix.aitools.tool.AiToolStatusPublisher;
import io.jmix.aitools.tool.JmixAiTool;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.FetchPlanBuilder;
import io.jmix.core.security.SystemAuthenticator;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The tools the assistants call to read a document: the assistant of a document card and the assistant in the menu,
 * whose add-on registers them as a {@link JmixAiTool}. They only read: the proposal of a type and fields changes
 * nothing. Each tool works as the person who asks, so the model gets only what that person may open, and shows
 * the status the model wrote in the chat that asks.
 */
@Component
public class DocumentAssistantTools implements JmixAiTool {

    /**
     * The key of the tool context that holds the username of the person who asks.
     */
    public static final String USERNAME = "dms.username";

    /**
     * The key of the tool context that holds the {@link ToolStatusFeed} of the chat that asks.
     */
    public static final String STATUSES = "dms.statuses";

    /**
     * The description of the status a tool takes from the model and shows in the chat.
     */
    public static final String STATUS = "What you are doing, for the person: a few words in the language of the "
            + "person, such as \"Reading the lease agreement\"";

    private static final String DOCUMENT_ID = "The id of the document";
    private static final String UNAVAILABLE = "The document does not exist or is not available to this person.";

    private static final String NO_TEXT = "The document has no text to read: its file is a picture, a scan "
            + "or a file of an unknown format.";

    private final DataManager dataManager;
    private final SystemAuthenticator systemAuthenticator;
    private final DocumentTextReader documentTextReader;
    private final DocumentClassifier documentClassifier;
    private final AiToolStatusPublisher statusPublisher;

    public DocumentAssistantTools(DataManager dataManager,
                                  SystemAuthenticator systemAuthenticator,
                                  DocumentTextReader documentTextReader,
                                  DocumentClassifier documentClassifier,
                                  AiToolStatusPublisher statusPublisher) {
        this.dataManager = dataManager;
        this.systemAuthenticator = systemAuthenticator;
        this.documentTextReader = documentTextReader;
        this.documentClassifier = documentClassifier;
        this.statusPublisher = statusPublisher;
    }

    /**
     * Returns the text of the current version of a document, or says why there is none.
     */
    @Tool(name = "dms_readDocumentText", description = "Returns the text of the current version of a document")
    public String readDocumentText(@ToolParam(description = STATUS) String status,
                                   @ToolParam(description = DOCUMENT_ID) String documentId,
                                   ToolContext context) {
        return asThePerson(status, context, () -> document(documentId)
                .map(document -> textOf(document).orElse(NO_TEXT))
                .orElse(UNAVAILABLE));
    }

    /**
     * Proposes the type of a document, the values of its fields and its tags from its text, without saving anything.
     */
    @Tool(name = "dms_proposeTypeAndFields",
            description = "Proposes the type of a document, the values of its fields and its tags from its text;"
                    + " saves nothing")
    public String proposeTypeAndFields(@ToolParam(description = STATUS) String status,
                                       @ToolParam(description = DOCUMENT_ID) String documentId,
                                       ToolContext context) {
        return asThePerson(status, context, () -> document(documentId)
                .map(document -> textOf(document)
                        .map(text -> describe(documentClassifier.classify(document.getName(), text)))
                        .orElse(NO_TEXT))
                .orElse(UNAVAILABLE));
    }

    // the card asks on a thread of its own, where nobody is signed in, and names the person; the chat in the menu
    // asks from a task that already runs as the person
    private String asThePerson(String status, ToolContext context, Supplier<String> work) {
        Runnable done = showStatus(status, context);
        String username = (String) context.getContext().get(USERNAME);

        try {
            return username == null ? work.get() : systemAuthenticator.withUser(username, work::get);
        } finally {
            done.run();
        }
    }

    // a model may leave the status out, and a chat takes no empty one; the chat in the menu has a feed of its own
    private Runnable showStatus(@Nullable String status, ToolContext context) {
        if (status == null || status.isBlank()) {
            return () -> {
            };
        }

        if (context.getContext().get(STATUSES) instanceof ToolStatusFeed statuses) {
            return statuses.start(status);
        }

        statusPublisher.update(status, context);

        return () -> {
        };
    }

    private Optional<Document> document(String id) {
        UUID documentId;

        try {
            documentId = UUID.fromString(id.strip());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }

        return dataManager.load(Document.class)
                .query("select d from Document d where d.id = :id")
                .parameter("id", documentId)
                .fetchPlan(DocumentAssistantTools::fetchPlan)
                .optional();
    }

    private static void fetchPlan(FetchPlanBuilder fetchPlan) {
        fetchPlan.addFetchPlan(FetchPlan.BASE)
                .add("currentRevision", FetchPlan.BASE);
    }

    private Optional<String> textOf(Document document) {
        return Optional.ofNullable(document.getCurrentRevision())
                .flatMap(documentTextReader::read);
    }

    private static String describe(ClassificationProposal proposal) {
        String fields = proposal.fields().stream()
                .map(field -> field.code() + " = " + field.value())
                .collect(Collectors.joining("; "));

        return "Type: %s%nFields: %s%nDocument date: %s%nTags: %s".formatted(proposal.type(), fields,
                proposal.documentDate(), String.join(", ", proposal.tags()));
    }
}
