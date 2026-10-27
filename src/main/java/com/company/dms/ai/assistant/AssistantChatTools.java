package com.company.dms.ai.assistant;

import com.company.dms.model.document.Document;
import com.company.dms.model.folder.Folder;
import com.company.dms.search.semantic.DocumentVectorIndexer;
import com.company.dms.search.semantic.SemanticSearch;
import io.jmix.aitools.AiToolsDataLoadProperties;
import io.jmix.aitools.tool.AiToolStatusPublisher;
import io.jmix.aitools.tool.JmixAiTool;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Metadata;
import io.jmix.core.MetadataTools;
import io.jmix.core.Sort;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.querycondition.Condition;
import io.jmix.core.querycondition.LogicalCondition;
import io.jmix.core.querycondition.PropertyCondition;
import io.jmix.dynattr.AttributeDefinition;
import io.jmix.dynattr.AttributeType;
import io.jmix.dynattr.CategoryDefinition;
import io.jmix.dynattr.DynAttrMetadata;
import io.jmix.dynattr.DynAttrQueryHints;
import io.jmix.dynattr.DynAttrUtils;
import io.jmix.dynattr.MsgBundleTools;
import io.jmix.dynattr.model.Category;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The tool the assistant in the menu calls to find documents by the fields of their types. Those fields are
 * dynamic attributes, which the query tools of the add-on do not see. The search starts from the documents, so the
 * access rules of the person who asks decide what is found, and the value of a field only narrows it.
 */
@Component
public class AssistantChatTools implements JmixAiTool {

    private static final Logger log = LoggerFactory.getLogger(AssistantChatTools.class);

    private static final String FIELD = "A field of the document types, its code or its name, such as "
            + "\"supplier\"; never the folder, the name, the status, the type or the date of a document. Leave it "
            + "out to look for a value in every text field of the types";

    private static final String VALUE = "The value to look for: a text, a number with a dot as the decimal "
            + "separator, or a date such as 2026-12-31. Leave it out to look for a range";

    private static final String FROM = "For a number or a date field: the lowest number or the earliest date of "
            + "the range, such as 2026-12-01. Leave it out for a range open at the bottom";

    private static final String TO = "For a number or a date field: the highest number or the latest date of "
            + "the range, such as 2026-12-31. Leave it out for a range open at the top";

    // a model asks this tool for the documents of a folder, finds none and takes that for the answer
    private static final String ATTRIBUTES = "The folder, the name, the status, the type and the date are "
            + "attributes of a document, not fields: count or list documents by them with the query tools.";

    private static final String TOPIC = "The topic or the question in the words of the person, short, such as "
            + "\"agricultural machinery\" or \"penalties for late delivery\"";

    // a model would take the nearest documents for all the documents about the topic
    private static final String MORE_BY_MEANING = "These are the documents nearest to the topic, and more may be "
            + "about it: say so, and point the person to the AI search of the search field for all of them.";

    // a model would take the documents found while the index fills for all the documents there are
    private static final String INDEX_FILLING = "The search by meaning is still indexing documents: some may be "
            + "missing, so do not conclude that there are none.";

    // a model would take a search that could not run for an answer that there are no such documents
    private static final String NO_MEANING_SEARCH = "The search by meaning is not available now: say so, and do not "
            + "conclude that there are no such documents.";

    // a model asks this tool for all the documents, with no value at all
    private static final String NO_VALUE = "Give a value to look for, or a range of a number or a date field. To "
            + "list or count documents, all of them or by the folder, the name, the status, the type or the date, "
            + "use the query tools.";

    private static final Set<AttributeType> TEXT = Set.of(AttributeType.STRING, AttributeType.ENUMERATION);
    private static final Set<AttributeType> NUMBERS_AND_DATES = Set.of(AttributeType.INTEGER, AttributeType.DOUBLE,
            AttributeType.DECIMAL, AttributeType.DATE_WITHOUT_TIME);

    private final DataManager dataManager;
    private final Metadata metadata;
    private final MetadataTools metadataTools;
    private final DynAttrMetadata dynAttrMetadata;
    private final MsgBundleTools msgBundleTools;
    private final AiToolsDataLoadProperties dataLoadProperties;
    private final AiToolStatusPublisher statusPublisher;
    private final SemanticSearch semanticSearch;
    private final DocumentVectorIndexer vectorIndexer;

    public AssistantChatTools(DataManager dataManager,
                              Metadata metadata,
                              MetadataTools metadataTools,
                              DynAttrMetadata dynAttrMetadata,
                              MsgBundleTools msgBundleTools,
                              AiToolsDataLoadProperties dataLoadProperties,
                              AiToolStatusPublisher statusPublisher,
                              SemanticSearch semanticSearch,
                              DocumentVectorIndexer vectorIndexer) {
        this.dataManager = dataManager;
        this.metadata = metadata;
        this.metadataTools = metadataTools;
        this.dynAttrMetadata = dynAttrMetadata;
        this.msgBundleTools = msgBundleTools;
        this.dataLoadProperties = dataLoadProperties;
        this.statusPublisher = statusPublisher;
        this.semanticSearch = semanticSearch;
        this.vectorIndexer = vectorIndexer;
    }

    /**
     * Finds the documents the person who asks may open whose field holds the value or, for a number or a date,
     * falls in the range.
     */
    @Tool(name = "dms_findDocumentsByTypeField", description = """
            Only for the fields of the document types, such as the supplier of an invoice, the counterparty or \
            the amount of a contract, a due date: finds the documents whose such field holds a value or, for a \
            number or a date, falls in a range. These fields are not attributes of any entity, so the query tools \
            cannot reach them. Never use this tool for the folder, the name, the status, the type or the date of a \
            document, or to list all the documents: they are attributes of the Document entity, so count or list \
            documents by them with the query tools. A text matches in part and in any case; a number or a date \
            matches exactly or within the range. Returns the id, the name, the type, the date, the folder, who \
            uploaded it and when, and the fields of each document found.""")
    public String findDocumentsByTypeField(@ToolParam(description = DocumentAssistantTools.STATUS) String status,
                                           @ToolParam(required = false, description = FIELD) @Nullable String field,
                                           @ToolParam(required = false, description = VALUE) @Nullable String value,
                                           @ToolParam(required = false, description = FROM) @Nullable String from,
                                           @ToolParam(required = false, description = TO) @Nullable String to,
                                           ToolContext context) {
        // a model may leave the status out, and the chat takes no empty one
        if (status != null && !status.isBlank()) {
            statusPublisher.update(status, context);
        }

        String wanted = value == null ? "" : value.strip();
        boolean ranged = wanted.isEmpty();

        if (ranged && isBlank(from) && isBlank(to)) {
            return NO_VALUE;
        }

        if (ranged && isBlank(field)) {
            return "Name the field to look in for a range. The fields of the document types: %s."
                    .formatted(allFields());
        }

        List<AttributeDefinition> fields = fieldsCalled(field);

        if (fields.isEmpty()) {
            return ("No field is called \"%s\". " + ATTRIBUTES + " The fields of the document types: %s.")
                    .formatted(field, allFields());
        }

        List<Condition> conditions = fields.stream()
                .map(attribute -> ranged ? range(attribute, from, to) : condition(attribute, wanted))
                .flatMap(Optional::stream)
                .toList();

        if (conditions.isEmpty()) {
            return ranged
                    ? ("From %s to %s is not a range of %s: give numbers with a dot as the decimal separator or "
                    + "dates such as 2026-12-31.").formatted(bound(from), bound(to), names(fields))
                    : ("\"%s\" is not a value of %s: give a text, a number with a dot as the decimal separator or "
                    + "a date such as 2026-12-31.").formatted(wanted, names(fields));
        }

        int limit = dataLoadProperties.getJpqlExecutionMaxResult();

        List<Document> documents = dataManager.load(Document.class)
                .condition(LogicalCondition.or(conditions.toArray(Condition[]::new)))
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("folder", FetchPlan.INSTANCE_NAME)
                        .add("category", FetchPlan.INSTANCE_NAME))
                .hint(DynAttrQueryHints.LOAD_DYN_ATTR, true)
                .sort(Sort.by("name"))
                .maxResults(limit + 1)
                .list();

        if (documents.isEmpty()) {
            return (ranged
                    ? "No document has a field from %s to %s. ".formatted(bound(from), bound(to))
                    : "No document has a field holding \"%s\". ".formatted(wanted)) + ATTRIBUTES;
        }

        Map<UUID, String> folders = folderNames(documents);

        String found = documents.stream()
                .limit(limit)
                .map(document -> describe(document, folders))
                .collect(Collectors.joining("\n"));

        return documents.size() > limit
                ? found + "\nThere are more documents: narrow the search."
                : found;
    }

    /**
     * Finds the passages of the documents the person who asks may open that are nearest to the topic by meaning.
     */
    @Tool(name = "dms_findDocumentsByMeaning", description = """
            Finds the passages of the documents nearest to a topic or a question by meaning, even when the documents \
            name the thing in other words, such as tractors and combines for agricultural machinery. Use it for what \
            the documents say and for which documents are about something. Never use it for the fields of the \
            document types (dms_findDocumentsByTypeField), or to list or count documents by their folder, name, \
            status, type or date (the query tools). Returns up to 8 documents the person may open, the nearest \
            first, each with its id, name, type, date, folder and fields and up to 3 passages. Answer from the \
            passages, quote them and name the documents you use.""")
    public String findDocumentsByMeaning(@ToolParam(description = DocumentAssistantTools.STATUS) String status,
                                         @ToolParam(description = TOPIC) String topic,
                                         ToolContext context) {
        if (status != null && !status.isBlank()) {
            statusPublisher.update(status, context);
        }

        SemanticSearch.Found found;

        try {
            found = semanticSearch.passages(topic.strip());
        } catch (RuntimeException e) {
            // OpenSearch or the model of vectors did not answer
            log.warn("The search by meaning failed: {}", e.toString());
            return NO_MEANING_SEARCH;
        }

        String answer = found.documents().isEmpty()
                ? "No document the person may open is about \"%s\".".formatted(topic)
                : describePassages(found);

        return vectorIndexer.isBusy() ? answer + "\n" + INDEX_FILLING : answer;
    }

    private String describePassages(SemanticSearch.Found found) {
        Map<UUID, String> folders = folderNames(found.documents()
                .stream()
                .map(SemanticSearch.Passages::document)
                .toList());

        String passages = found.documents()
                .stream()
                .map(hit -> describe(hit.document(), folders) + hit.fragments()
                        .stream()
                        .map(fragment -> "\n  passage: " + fragment.replace('\n', ' '))
                        .collect(Collectors.joining()))
                .collect(Collectors.joining("\n"));

        return found.more() ? passages + "\n" + MORE_BY_MEANING : passages;
    }

    /**
     * Describes the document types and the fields this tool can search, for the prompt of the model.
     */
    public String describeFields() {
        return dynAttrMetadata.getCategories(metadata.getClass(Document.class)).stream()
                .map(type -> Map.entry(nameOf(type), type.getAttributeDefinitions().stream()
                        .filter(AssistantChatTools::isSearchable)
                        .sorted(Comparator.comparingInt(AttributeDefinition::getOrderNo))
                        .map(attribute -> "%s (%s, %s)".formatted(attribute.getCode(), nameOf(attribute),
                                kindOf(attribute)))
                        .collect(Collectors.joining(", "))))
                .filter(type -> !type.getValue().isEmpty())
                .map(type -> "- %s: %s".formatted(type.getKey(), type.getValue()))
                .collect(Collectors.joining("\n"));
    }

    private List<AttributeDefinition> fieldsCalled(@Nullable String field) {
        Collection<AttributeDefinition> all = allAttributes();

        if (field == null || field.isBlank()) {
            return all.stream()
                    .filter(attribute -> TEXT.contains(attribute.getDataType()))
                    .toList();
        }

        // a model may write the field the way the metamodel names it, "+supplier"
        String wanted = field.strip().replaceFirst("^\\+", "");

        return all.stream()
                .filter(attribute -> namesOf(attribute).anyMatch(wanted::equalsIgnoreCase))
                .toList();
    }

    private Stream<String> namesOf(AttributeDefinition attribute) {
        String names = attribute.getNameMsgBundle();
        Stream<String> ownNames = Stream.of(attribute.getCode(), attribute.getName());

        return names == null
                ? ownNames
                : Stream.concat(ownNames, msgBundleTools.getMsgBundleValues(names).values().stream());
    }

    private static Optional<Condition> condition(AttributeDefinition attribute, String value) {
        String property = DynAttrUtils.getPropertyFromAttributeCode(attribute.getCode());

        return TEXT.contains(attribute.getDataType())
                ? Optional.of(PropertyCondition.contains(property, value))
                : numberOrDate(attribute, value).map(parsed -> PropertyCondition.equal(property, parsed));
    }

    private static Optional<Condition> range(AttributeDefinition attribute, @Nullable String from,
                                             @Nullable String to) {
        String property = DynAttrUtils.getPropertyFromAttributeCode(attribute.getCode());
        List<Condition> ends = new ArrayList<>();

        if (from != null && !from.isBlank()) {
            Optional<Object> lowest = numberOrDate(attribute, from.strip());

            if (lowest.isEmpty()) {
                return Optional.empty();
            }

            ends.add(PropertyCondition.greaterOrEqual(property, lowest.get()));
        }

        if (to != null && !to.isBlank()) {
            Optional<Object> highest = numberOrDate(attribute, to.strip());

            if (highest.isEmpty()) {
                return Optional.empty();
            }

            ends.add(PropertyCondition.lessOrEqual(property, highest.get()));
        }

        return Optional.of(LogicalCondition.and(ends.toArray(Condition[]::new)));
    }

    private static boolean isSearchable(AttributeDefinition attribute) {
        return !attribute.isCollection()
                && (TEXT.contains(attribute.getDataType()) || NUMBERS_AND_DATES.contains(attribute.getDataType()));
    }

    private static String kindOf(AttributeDefinition attribute) {
        return switch (attribute.getDataType()) {
            case ENUMERATION -> "one of: " + String.join(", ",
                    Objects.requireNonNullElse(attribute.getEnumeration(), "").split(","));
            case INTEGER, DOUBLE, DECIMAL -> "number";
            case DATE_WITHOUT_TIME -> "date";
            default -> "text";
        };
    }

    private static Optional<Object> numberOrDate(AttributeDefinition attribute, String value) {
        try {
            return Optional.ofNullable(switch (attribute.getDataType()) {
                case INTEGER -> Integer.valueOf(value);
                case DOUBLE -> Double.valueOf(value);
                case DECIMAL -> new BigDecimal(value);
                case DATE_WITHOUT_TIME -> LocalDate.parse(value);
                default -> null;
            });
        } catch (NumberFormatException | DateTimeParseException e) {
            return Optional.empty();
        }
    }

    // a JPQL row rule filters only the entity a query selects, so a folder the person may not open would show its
    // name through the reference of the document: the names come from a query of the folders themselves
    private Map<UUID, String> folderNames(List<Document> documents) {
        Set<UUID> ids = documents.stream()
                .map(document -> document.getFolder().getId())
                .collect(Collectors.toSet());

        return dataManager.load(Folder.class)
                .query("select f from Folder f where f.id in :ids")
                .parameter("ids", ids)
                .fetchPlan(FetchPlan.INSTANCE_NAME)
                .list()
                .stream()
                .collect(Collectors.toMap(Folder::getId, metadataTools::getInstanceName));
    }

    private String describe(Document document, Map<UUID, String> folders) {
        List<String> facts = new ArrayList<>();
        facts.add("\"%s\"".formatted(document.getName()));
        facts.add(document.getCategory() == null ? "no type" : metadataTools.getInstanceName(document.getCategory()));

        if (document.getDocumentDate() != null) {
            facts.add("dated " + document.getDocumentDate());
        }

        String folder = folders.get(document.getFolder().getId());

        if (folder != null) {
            facts.add("in the folder \"%s\"".formatted(folder));
        }

        facts.add("uploaded by %s on %s".formatted(document.getCreatedBy(),
                document.getCreatedDate().toLocalDateTime().truncatedTo(ChronoUnit.MINUTES)));

        String values = allAttributes().stream()
                .filter(attribute -> valueOf(document, attribute) != null)
                .map(attribute -> nameOf(attribute) + ": " + valueOf(document, attribute))
                .collect(Collectors.joining("; "));

        return "- id %s: %s".formatted(document.getId(), String.join(", ", facts))
                + (values.isEmpty() ? "" : "; " + values);
    }

    private static @Nullable Object valueOf(Document document, AttributeDefinition attribute) {
        return EntityValues.getValue(document, DynAttrUtils.getPropertyFromAttributeCode(attribute.getCode()));
    }

    private Collection<AttributeDefinition> allAttributes() {
        return dynAttrMetadata.getAttributes(metadata.getClass(Document.class));
    }

    private String allFields() {
        return names(allAttributes());
    }

    private String names(Collection<AttributeDefinition> fields) {
        return fields.stream()
                .map(attribute -> "%s (%s)".formatted(attribute.getCode(), nameOf(attribute)))
                .distinct()
                .collect(Collectors.joining(", "));
    }

    private String nameOf(CategoryDefinition type) {
        return type.getSource() instanceof Category category
                ? metadataTools.getInstanceName(category)
                : type.getName();
    }

    private String nameOf(AttributeDefinition attribute) {
        String name = attribute.getName() == null ? attribute.getCode() : attribute.getName();
        String names = attribute.getNameMsgBundle();

        return names == null ? name : msgBundleTools.getLocalizedValue(names, name);
    }

    private static String bound(@Nullable String bound) {
        return bound == null || bound.isBlank() ? "any" : bound.strip();
    }

    private static boolean isBlank(@Nullable String text) {
        return text == null || text.isBlank();
    }
}
