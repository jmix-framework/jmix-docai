package com.company.docai.app.service.document;

import com.company.docai.model.document.Document;
import io.jmix.core.LocaleResolver;
import io.jmix.core.MessageTools;
import io.jmix.core.Metadata;
import io.jmix.core.MetadataTools;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.dynattr.AttributeDefinition;
import io.jmix.dynattr.AttributeType;
import io.jmix.dynattr.CategoryDefinition;
import io.jmix.dynattr.DynAttrMetadata;
import io.jmix.dynattr.DynAttrUtils;
import io.jmix.dynattr.model.Category;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The document types and their fields, which the dynamic attributes add-on keeps as the categories of documents and
 * their attributes. Finds a type by its name in any language, names the types and the fields in the language of the
 * current user, and puts a type with the values of its fields, written as text, into a document being edited.
 * Nothing here saves the document.
 */
@Component
public class DocumentTypes {

    private final DynAttrMetadata dynAttrMetadata;
    private final Metadata metadata;
    private final MetadataTools metadataTools;
    private final MessageTools messageTools;
    private final CurrentAuthentication currentAuthentication;

    public DocumentTypes(DynAttrMetadata dynAttrMetadata, Metadata metadata, MetadataTools metadataTools,
                         MessageTools messageTools, CurrentAuthentication currentAuthentication) {
        this.dynAttrMetadata = dynAttrMetadata;
        this.metadata = metadata;
        this.metadataTools = metadataTools;
        this.messageTools = messageTools;
        this.currentAuthentication = currentAuthentication;
    }

    /**
     * The value of one field of a type, written as text.
     *
     * @param code  the code of the field
     * @param value the value as it is written
     */
    public record FieldValue(String code, String value) {
    }

    /**
     * What {@link #fill} put into a document.
     *
     * @param filled  the names of the fields that got values, the date of the document included
     * @param skipped the fields whose values did not fit them, each with its value
     */
    public record Filling(List<String> filled, List<String> skipped) {
    }

    /**
     * Returns the document types.
     */
    public Collection<CategoryDefinition> types() {
        return dynAttrMetadata.getCategories(documentClass());
    }

    /**
     * Returns the fields of all the document types.
     */
    public Collection<AttributeDefinition> fields() {
        return dynAttrMetadata.getAttributes(documentClass());
    }

    /**
     * Finds the type with the name in any language, whatever its case.
     */
    public Optional<CategoryDefinition> find(String name) {
        String wanted = name.strip();

        return types()
                .stream()
                .filter(type -> wanted.equalsIgnoreCase(type.getName())
                        || localizedNames(type)
                                .stream()
                                .anyMatch(wanted::equalsIgnoreCase))
                .findFirst();
    }

    /**
     * Puts the type and the values of its fields into the document being edited, and the date of the document,
     * written as {@code yyyy-MM-dd}. A value that is blank or the word null is passed over, and a value that does not
     * fit its field, or whose field the type lacks or leaves to the person, is left out and reported. The values of
     * other types stay until the document is saved, which clears them.
     */
    public Filling fill(Document document, CategoryDefinition type, List<FieldValue> values,
                        @Nullable String documentDate) {
        // the same cached instance the type field of the card offers
        document.setCategory((Category) type.getSource());

        List<String> filled = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (FieldValue field : values) {
            if (isBlank(field.value())) {
                continue;
            }

            AttributeDefinition attribute = attributeOf(type, field.code())
                    .orElse(null);
            Object value = attribute == null
                    ? null
                    : parse(attribute, field.value())
                            .orElse(null);

            if (value == null) {
                skipped.add((attribute == null ? field.code() : nameOf(attribute)) + ": " + field.value());
                continue;
            }

            EntityValues.setValue(document, DynAttrUtils.getPropertyFromAttributeCode(attribute.getCode()), value);
            filled.add(nameOf(attribute));
        }

        applyDocumentDate(documentDate, document, filled, skipped);

        return new Filling(filled, skipped);
    }

    /**
     * Tells whether {@link #fill} puts values into the field: a list of values and a reference to an entity are
     * left for the person to fill.
     */
    public boolean isFillable(AttributeDefinition attribute) {
        return !attribute.isCollection() && attribute.getDataType() != AttributeType.ENTITY;
    }

    /**
     * Returns the name of the type in the language of the current user.
     */
    public String nameOf(CategoryDefinition type) {
        return type.getSource() instanceof Category category
                ? metadataTools.getInstanceName(category)
                : type.getName();
    }

    /**
     * Returns the name of the field in the language of the current user; a field not named in that language keeps
     * its name, and a field without a name its code.
     */
    public String nameOf(AttributeDefinition attribute) {
        String name = Objects.requireNonNullElse(attribute.getName(), attribute.getCode());
        String language = LocaleResolver.localeToString(currentAuthentication.getLocale());

        return namesByLanguage(attribute.getNameMsgBundle())
                .getOrDefault(language, name);
    }

    /**
     * Returns the code of the field, its name and its names in every language.
     */
    public Stream<String> namesOf(AttributeDefinition attribute) {
        return Stream.concat(Stream.of(attribute.getCode(), attribute.getName()),
                namesByLanguage(attribute.getNameMsgBundle())
                        .values()
                        .stream());
    }

    private Optional<AttributeDefinition> attributeOf(CategoryDefinition type, @Nullable String code) {
        if (isBlank(code)) {
            return Optional.empty();
        }

        String wanted = DynAttrUtils.isDynamicAttributeProperty(code.strip())
                ? DynAttrUtils.getAttributeCodeFromProperty(code.strip())
                : code.strip();

        return type.getAttributeDefinitions()
                .stream()
                .filter(this::isFillable)
                .filter(attribute -> attribute.getCode().equalsIgnoreCase(wanted))
                .findFirst();
    }

    private static Optional<Object> parse(AttributeDefinition attribute, String raw) {
        String value = raw.strip();

        try {
            return switch (attribute.getDataType()) {
                case STRING -> Optional.of(value);
                case ENUMERATION -> {
                    String items = Objects.requireNonNullElse(attribute.getEnumeration(), "");

                    yield Arrays.stream(items.split(","))
                            .map(String::strip)
                            .filter(item -> item.equalsIgnoreCase(value))
                            .findFirst()
                            .map(Object.class::cast);
                }
                case INTEGER -> Optional.of(Integer.valueOf(number(value)));
                case DOUBLE -> Optional.of(Double.valueOf(number(value)));
                case DECIMAL -> Optional.of(new BigDecimal(number(value)));
                case BOOLEAN -> "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)
                        ? Optional.of(Boolean.valueOf(value))
                        : Optional.empty();
                case DATE_WITHOUT_TIME -> Optional.of(LocalDate.parse(value));
                case DATE -> Optional.of(Date.from(LocalDate.parse(value)
                        .atStartOfDay(ZoneId.systemDefault())
                        .toInstant()));
                case ENTITY -> Optional.empty();
            };
        } catch (NumberFormatException | DateTimeParseException e) {
            return Optional.empty();
        }
    }

    // "1 240 000,50" and "1,240,000.50" mean the same amount; in "185,000" the comma may separate either way
    private static String number(String value) {
        String compact = value.replaceAll("[\\s\\u00A0\\u202F]", "");

        if (compact.contains(".")) {
            return compact.replace(",", "");
        }

        if (compact.matches(".*,\\d{3}")) {
            throw new NumberFormatException("The comma of " + value + " may separate thousands or decimals");
        }

        return compact.replace(',', '.');
    }

    private void applyDocumentDate(@Nullable String raw, Document document, List<String> filled,
                                   List<String> skipped) {
        if (isBlank(raw)) {
            return;
        }

        String caption = messageTools.getPropertyCaption(documentClass(), "documentDate");

        try {
            document.setDocumentDate(LocalDate.parse(raw.strip()));
            filled.add(caption);
        } catch (DateTimeParseException e) {
            skipped.add(caption + ": " + raw);
        }
    }

    private Collection<String> localizedNames(CategoryDefinition type) {
        return type.getSource() instanceof Category category
                ? namesByLanguage(category.getLocaleNames()).values()
                : List.of();
    }

    // the "Document types" view keeps the names of a type or a field as lines like "en=Invoice"
    private static Map<String, String> namesByLanguage(@Nullable String names) {
        Properties properties = new Properties();

        try {
            properties.load(new StringReader(Objects.requireNonNullElse(names, "")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return properties.stringPropertyNames()
                .stream()
                .collect(Collectors.toMap(Function.identity(), properties::getProperty));
    }

    private MetaClass documentClass() {
        return metadata.getClass(Document.class);
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank() || "null".equalsIgnoreCase(value.strip());
    }
}
