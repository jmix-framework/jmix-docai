package com.company.dms.app.service.document;

import com.company.dms.model.document.Document;
import io.jmix.core.EntityStates;
import io.jmix.core.Metadata;
import io.jmix.core.entity.EntitySystemAccess;
import io.jmix.core.entity.EntityValues;
import io.jmix.dynattr.AttributeDefinition;
import io.jmix.dynattr.DynAttrMetadata;
import io.jmix.dynattr.DynAttrUtils;
import io.jmix.dynattr.DynamicAttributes;
import io.jmix.dynattr.DynamicAttributesState;
import io.jmix.dynattr.model.Category;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The fields of a document type: the dynamic attributes of the category the document is marked with.
 * A document loaded without its type or its dynamic attributes has nothing to compare or clear here,
 * so the paths that do not touch the fields leave them as they are.
 */
@Component
public class DocumentFields {

    private static final String TYPE = "category";

    private final DynAttrMetadata dynAttrMetadata;
    private final Metadata metadata;
    private final EntityStates entityStates;

    public DocumentFields(DynAttrMetadata dynAttrMetadata, Metadata metadata, EntityStates entityStates) {
        this.dynAttrMetadata = dynAttrMetadata;
        this.metadata = metadata;
        this.entityStates = entityStates;
    }

    /**
     * Tells whether the type or a field value of the edited document differs from the saved document.
     * A value typed and then put back is no difference, and a field the edited document has not loaded
     * is no difference either.
     */
    public boolean differ(Document edited, Document saved) {
        if (entityStates.isLoaded(edited, TYPE) && !Objects.equals(edited.getCategory(), saved.getCategory())) {
            return true;
        }

        DynamicAttributes editedFields = fieldsOf(edited);
        DynamicAttributes savedFields = fieldsOf(saved);

        if (editedFields == null || savedFields == null) {
            return false;
        }

        return editedFields.getKeys().stream()
                .anyMatch(code -> !sameValue(editedFields.getValue(code), savedFields.getValue(code)));
    }

    /**
     * Clears the fields that the type of the document does not have, all of them for a document without
     * a type, so that the save deletes their values. A type the add-on does not know keeps the values.
     */
    public void keepOwnType(Document document) {
        DynamicAttributes fields = fieldsOf(document);

        if (fields == null || !entityStates.isLoaded(document, TYPE)) {
            return;
        }

        Set<String> own = ownCodes(document);

        // the cache knows a type only after its reload, and a deleted type no more: clearing would lose the values
        if (own == null) {
            return;
        }

        for (String code : List.copyOf(fields.getKeys())) {
            if (!own.contains(code) && fields.getValue(code) != null) {
                EntityValues.setValue(document, DynAttrUtils.getPropertyFromAttributeCode(code), null);
            }
        }
    }

    private @Nullable Set<String> ownCodes(Document document) {
        Category type = document.getCategory();

        if (type == null) {
            return Set.of();
        }

        return dynAttrMetadata.getCategories(metadata.getClass(Document.class)).stream()
                .filter(definition -> type.equals(definition.getSource()))
                .findFirst()
                .map(definition -> definition.getAttributeDefinitions().stream()
                        .map(AttributeDefinition::getCode)
                        .collect(Collectors.toSet()))
                .orElse(null);
    }

    private static @Nullable DynamicAttributes fieldsOf(Document document) {
        DynamicAttributesState state = EntitySystemAccess.getExtraState(document, DynamicAttributesState.class);
        return state == null ? null : state.getDynamicAttributes();
    }

    private static boolean sameValue(@Nullable Object first, @Nullable Object second) {
        // 1.50 and 1.5 are the same amount
        if (first instanceof BigDecimal firstAmount && second instanceof BigDecimal secondAmount) {
            return firstAmount.compareTo(secondAmount) == 0;
        }

        return Objects.equals(first, second);
    }
}
