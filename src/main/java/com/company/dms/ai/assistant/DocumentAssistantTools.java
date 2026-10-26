package com.company.dms.ai.assistant;

import com.company.dms.ai.DocumentTextReader;
import com.company.dms.ai.classification.ClassificationProposal;
import com.company.dms.ai.classification.DocumentClassifier;
import com.company.dms.model.document.Document;
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
 * The tools the assistant of a document card calls to read the document. They only read: the proposal of a type
 * and fields changes nothing. Each tool works as the person who asks, so the model gets only what that person may
 * open, and shows the status the model wrote in the chat that asks.
 */
@Component
public class DocumentAssistantTools {

    /**
     * The key of the tool context that holds the username of the person who asks.
     */
    public static final String USERNAME = "dms.username";

    /**
     * The key of the tool context that holds the {@link ToolStatusFeed} of the chat that asks.
     */
    public static final String STATUSES = "dms.statuses";

    private static final String STATUS = "What you are doing, for the person: a few words in the language of the "
            + "person, such as \"Reading the lease agreement\"";

    private static final String DOCUMENT_ID = "The id of the document";
    private static final String UNAVAILABLE = "The document does not exist or is not available to this person.";

    private static final String NO_TEXT = "The document has no text to read: its file is a picture, a scan "
            + "or a file of an unknown format.";

    private final DataManager dataManager;
    private final SystemAuthenticator systemAuthenticator;
    private final DocumentTextReader documentTextReader;
    private final DocumentClassifier documentClassifier;

    public DocumentAssistantTools(DataManager dataManager,
                                  SystemAuthenticator systemAuthenticator,
                                  DocumentTextReader documentTextReader,
                                  DocumentClassifier documentClassifier) {
        this.dataManager = dataManager;
        this.systemAuthenticator = systemAuthenticator;
        this.documentTextReader = documentTextReader;
        this.documentClassifier = documentClassifier;
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
     * Proposes the type of a document and the values of its fields from its text, without saving anything.
     */
    @Tool(name = "dms_proposeTypeAndFields",
            description = "Proposes the type of a document and the values of its fields from its text; saves nothing")
    public String proposeTypeAndFields(@ToolParam(description = STATUS) String status,
                                       @ToolParam(description = DOCUMENT_ID) String documentId,
                                       ToolContext context) {
        return asThePerson(status, context, () -> document(documentId)
                .map(document -> textOf(document)
                        .map(text -> describe(documentClassifier.classify(document.getName(), text)))
                        .orElse(NO_TEXT))
                .orElse(UNAVAILABLE));
    }

    // the model answers on a thread of its own, where nobody is signed in
    private String asThePerson(String status, ToolContext context, Supplier<String> work) {
        Runnable done = showStatus(status, context);

        try {
            return systemAuthenticator.withUser((String) context.getContext().get(USERNAME), work::get);
        } finally {
            done.run();
        }
    }

    // a model may leave the status out, and the chat takes no empty one
    private static Runnable showStatus(@Nullable String status, ToolContext context) {
        if (status == null || status.isBlank()) {
            return () -> {
            };
        }

        return ((ToolStatusFeed) context.getContext().get(STATUSES)).start(status);
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

        return "Type: %s%nFields: %s%nDocument date: %s".formatted(proposal.type(), fields, proposal.documentDate());
    }
}
