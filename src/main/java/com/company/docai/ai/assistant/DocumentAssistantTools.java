package com.company.docai.ai.assistant;

import com.company.docai.ai.AiSettings;
import com.company.docai.ai.DocumentTextReader;
import com.company.docai.ai.classification.ClassificationProposal;
import com.company.docai.ai.classification.DocumentClassifier;
import com.company.docai.app.service.user.UserService;
import com.company.docai.model.document.Document;
import com.company.docai.model.document.DocumentComment;
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

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    public static final String USERNAME = "docai.username";

    /**
     * The key of the tool context that holds the {@link ToolStatusFeed} of the chat that asks.
     */
    public static final String STATUSES = "docai.statuses";

    /**
     * The description of the status a tool takes from the model and shows in the chat.
     */
    public static final String STATUS = "What you are doing, for the person: a few words in the language of the "
            + "person, such as \"Reading the lease agreement\"";

    private static final String DOCUMENT_ID = "The id of the document";
    private static final String UNAVAILABLE = "The document does not exist or is not available to this person.";

    private static final String NO_TEXT = "The document has no text to read: its file is a picture, a scan "
            + "or a file of an unknown format.";

    private static final String NO_COMMENTS = "Nobody has commented on the document.";
    private static final String EARLIER_LEFT_OUT = "Earlier comments are left out; these are the latest ones.";
    private static final int MAX_COMMENTS = 50;
    private static final DateTimeFormatter COMMENT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final DataManager dataManager;
    private final SystemAuthenticator systemAuthenticator;
    private final DocumentTextReader documentTextReader;
    private final DocumentClassifier documentClassifier;
    private final AiToolStatusPublisher statusPublisher;
    private final UserService userService;
    private final AiSettings aiSettings;

    public DocumentAssistantTools(DataManager dataManager,
                                  SystemAuthenticator systemAuthenticator,
                                  DocumentTextReader documentTextReader,
                                  DocumentClassifier documentClassifier,
                                  AiToolStatusPublisher statusPublisher,
                                  UserService userService,
                                  AiSettings aiSettings) {
        this.dataManager = dataManager;
        this.systemAuthenticator = systemAuthenticator;
        this.documentTextReader = documentTextReader;
        this.documentClassifier = documentClassifier;
        this.statusPublisher = statusPublisher;
        this.userService = userService;
        this.aiSettings = aiSettings;
    }

    /**
     * Returns the text of the current version of a document, or says why there is none.
     */
    @Tool(name = "docai_readDocumentText", description = "Returns the text of the current version of a document")
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
    @Tool(name = "docai_proposeTypeAndFields",
            description = "Proposes the type of a document, the values of its fields and its tags from its text;"
                    + " saves nothing")
    public String proposeTypeAndFields(@ToolParam(description = STATUS) String status,
                                       @ToolParam(description = DOCUMENT_ID) String documentId,
                                       ToolContext context) {
        return asThePerson(status, context, () -> document(documentId)
                .map(document -> textOf(document)
                        .map(text -> describe(documentClassifier.classify(document.getName(), text), document))
                        .orElse(NO_TEXT))
                .orElse(UNAVAILABLE));
    }

    /**
     * Returns the comments people left on a document, the oldest first, with who wrote each and when.
     */
    @Tool(name = "docai_readDocumentComments", description = "Returns the comments people left on a document, the "
            + "oldest first: who wrote each, when, whether it was edited, and its text")
    public String readDocumentComments(@ToolParam(description = STATUS) String status,
                                       @ToolParam(description = DOCUMENT_ID) String documentId,
                                       ToolContext context) {
        return asThePerson(status, context, () -> document(documentId)
                .map(this::commentsOf)
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
                .add("currentRevision", FetchPlan.BASE)
                .add("tags", FetchPlan.BASE);
    }

    private Optional<String> textOf(Document document) {
        return Optional.ofNullable(document.getCurrentRevision())
                .flatMap(documentTextReader::read);
    }

    // the latest comments that fit into what the model reads, put back in the order they were written
    private String commentsOf(Document document) {
        List<DocumentComment> latest = dataManager.load(DocumentComment.class)
                .query("select c from DocumentComment c where c.document = :document"
                        + " order by c.createdDate desc, c.id desc")
                .parameter("document", document)
                .maxResults(MAX_COMMENTS + 1)
                .list();

        if (latest.isEmpty()) {
            return NO_COMMENTS;
        }

        Map<String, String> authors = userService.namesOf(latest.stream()
                .map(DocumentComment::getCreatedBy)
                .toList());

        List<String> entries = new ArrayList<>();
        int length = 0;

        for (DocumentComment comment : latest.subList(0, Math.min(MAX_COMMENTS, latest.size()))) {
            String entry = "%s, %s%s:\n%s".formatted(
                    authors.getOrDefault(comment.getCreatedBy(), comment.getCreatedBy()),
                    COMMENT_TIME.format(comment.getCreatedDate().atZoneSameInstant(ZoneId.systemDefault())),
                    comment.getEditedDate() == null ? "" : ", edited",
                    // on one line, so that a text cannot pass for the next comment and its author
                    comment.getText().replaceAll("\\R", " "));

            if (length + entry.length() > aiSettings.getMaxTextLength()) {
                break;
            }

            entries.addFirst(entry);
            length += entry.length();
        }

        String comments = String.join("\n\n", entries);

        return entries.size() < latest.size() ? EARLIER_LEFT_OUT + "\n\n" + comments : comments;
    }

    // the tags as "Fill with AI" would add them, not as the model proposed them
    private String describe(ClassificationProposal proposal, Document document) {
        String fields = proposal.fields().stream()
                .map(field -> field.code() + " = " + field.value())
                .collect(Collectors.joining("; "));

        return "Type: %s%nFields: %s%nDocument date: %s%nTags: %s".formatted(proposal.type(), fields,
                proposal.documentDate(), String.join(", ", documentClassifier.tagsToAdd(proposal, document)));
    }
}
