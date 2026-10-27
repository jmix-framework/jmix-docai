package com.company.dms.ai.assistant;

import com.company.dms.model.document.Document;
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
import io.jmix.dynattr.DynAttrMetadata;
import io.jmix.dynattr.DynAttrQueryHints;
import io.jmix.dynattr.DynAttrUtils;
import io.jmix.dynattr.MsgBundleTools;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The tool the assistant in the menu calls to find documents by the fields of their types. Those fields are
 * dynamic attributes, which the query tools of the add-on do not see. The search starts from the documents, so the
 * access rules of the person who asks decide what is found, and the value of a field only narrows it.
 */
@Component
public class AssistantChatTools implements JmixAiTool {

    private static final String FIELD = "A field of the document types, its code or its name, such as "
            + "\"supplier\"; never the folder, the name, the status, the type or the date of a document. Leave it "
            + "out to look in every text field of the types";

    private static final String VALUE = "The value to look for: a text, a number with a dot as the decimal "
            + "separator, or a date such as 2026-12-31";

    // a model asks this tool for the documents of a folder, finds none and takes that for the answer
    private static final String ATTRIBUTES = "The folder, the name, the status, the type and the date are "
            + "attributes of a document, not fields: count or list documents by them with the query tools.";

    private static final Set<AttributeType> TEXT = Set.of(AttributeType.STRING, AttributeType.ENUMERATION);

    private final DataManager dataManager;
    private final Metadata metadata;
    private final MetadataTools metadataTools;
    private final DynAttrMetadata dynAttrMetadata;
    private final MsgBundleTools msgBundleTools;
    private final AiToolsDataLoadProperties dataLoadProperties;
    private final AiToolStatusPublisher statusPublisher;

    public AssistantChatTools(DataManager dataManager,
                              Metadata metadata,
                              MetadataTools metadataTools,
                              DynAttrMetadata dynAttrMetadata,
                              MsgBundleTools msgBundleTools,
                              AiToolsDataLoadProperties dataLoadProperties,
                              AiToolStatusPublisher statusPublisher) {
        this.dataManager = dataManager;
        this.metadata = metadata;
        this.metadataTools = metadataTools;
        this.dynAttrMetadata = dynAttrMetadata;
        this.msgBundleTools = msgBundleTools;
        this.dataLoadProperties = dataLoadProperties;
        this.statusPublisher = statusPublisher;
    }

    /**
     * Finds the documents the person who asks may open whose field holds the value.
     */
    @Tool(name = "dms_findDocumentsByTypeField", description = """
            Only for the fields of the document types, such as the supplier of an invoice, the counterparty or \
            the amount of a contract, a due date: finds the documents whose such field holds a value. These \
            fields are not attributes of any entity, so the query tools cannot reach them. Never use this tool \
            for the folder, the name, the status, the type or the date of a document: they are attributes of \
            the Document entity, so count or list documents by them with the query tools. A text matches in part \
            and in any case; a number or a date matches exactly. Returns the id, the name, the type, the date and \
            the fields of each document found.""")
    public String findDocumentsByTypeField(@ToolParam(description = DocumentAssistantTools.STATUS) String status,
                                           @ToolParam(required = false, description = FIELD) @Nullable String field,
                                           @ToolParam(description = VALUE) String value,
                                           ToolContext context) {
        // a model may leave the status out, and the chat takes no empty one
        if (status != null && !status.isBlank()) {
            statusPublisher.update(status, context);
        }

        List<AttributeDefinition> fields = fieldsCalled(field);

        if (fields.isEmpty()) {
            return ("No field is called \"%s\". " + ATTRIBUTES + " The fields of the document types: %s.")
                    .formatted(field, allFields());
        }

        List<Condition> conditions = fields.stream()
                .map(attribute -> condition(attribute, value.strip()))
                .flatMap(Optional::stream)
                .toList();

        if (conditions.isEmpty()) {
            return ("\"%s\" is not a value of %s: give a text, a number with a dot as the decimal separator or "
                    + "a date such as 2026-12-31.").formatted(value, names(fields));
        }

        int limit = dataLoadProperties.getJpqlExecutionMaxResult();

        List<Document> documents = dataManager.load(Document.class)
                .condition(LogicalCondition.or(conditions.toArray(Condition[]::new)))
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("category", FetchPlan.INSTANCE_NAME))
                .hint(DynAttrQueryHints.LOAD_DYN_ATTR, true)
                .sort(Sort.by("name"))
                .maxResults(limit + 1)
                .list();

        if (documents.isEmpty()) {
            return "No document has a field holding \"%s\". ".formatted(value) + ATTRIBUTES;
        }

        String found = documents.stream()
                .limit(limit)
                .map(document -> describe(document, fields))
                .collect(Collectors.joining("\n"));

        return documents.size() > limit
                ? found + "\nThere are more documents: narrow the search."
                : found;
    }

    private List<AttributeDefinition> fieldsCalled(@Nullable String field) {
        Collection<AttributeDefinition> all = dynAttrMetadata.getAttributes(metadata.getClass(Document.class));

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

        try {
            return Optional.ofNullable(switch (attribute.getDataType()) {
                case STRING, ENUMERATION -> PropertyCondition.contains(property, value);
                case INTEGER -> PropertyCondition.equal(property, Integer.valueOf(value));
                case DOUBLE -> PropertyCondition.equal(property, Double.valueOf(value));
                case DECIMAL -> PropertyCondition.equal(property, new BigDecimal(value));
                case DATE_WITHOUT_TIME -> PropertyCondition.equal(property, LocalDate.parse(value));
                default -> null;
            });
        } catch (NumberFormatException | DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private String describe(Document document, List<AttributeDefinition> fields) {
        String type = document.getCategory() == null
                ? "no type"
                : metadataTools.getInstanceName(document.getCategory());

        String values = fields.stream()
                .filter(attribute -> valueOf(document, attribute) != null)
                .map(attribute -> nameOf(attribute) + ": " + valueOf(document, attribute))
                .collect(Collectors.joining("; "));

        return "- id %s: \"%s\", %s, dated %s; %s".formatted(document.getId(), document.getName(), type,
                document.getDocumentDate(), values);
    }

    private static @Nullable Object valueOf(Document document, AttributeDefinition attribute) {
        return EntityValues.getValue(document, DynAttrUtils.getPropertyFromAttributeCode(attribute.getCode()));
    }

    private String allFields() {
        return names(dynAttrMetadata.getAttributes(metadata.getClass(Document.class)));
    }

    private String names(Collection<AttributeDefinition> fields) {
        return fields.stream()
                .map(attribute -> "%s (%s)".formatted(attribute.getCode(), nameOf(attribute)))
                .distinct()
                .collect(Collectors.joining(", "));
    }

    private String nameOf(AttributeDefinition attribute) {
        String name = attribute.getName() == null ? attribute.getCode() : attribute.getName();
        String names = attribute.getNameMsgBundle();

        return names == null ? name : msgBundleTools.getLocalizedValue(names, name);
    }
}
