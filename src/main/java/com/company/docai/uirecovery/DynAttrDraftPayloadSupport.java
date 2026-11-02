package com.company.docai.uirecovery;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaPropertyPath;
import io.jmix.dynattr.DynAttrUtils;
import io.jmix.flowui.model.DataContext;
import io.jmix.uirecoveryflowui.draft.DraftPayloadSupport;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.text.ParseException;
import java.util.List;

/**
 * Restores the values of dynamic attributes from a view draft. UI Recovery 3.1.0 saves them in the draft but skips
 * them on restore: it looks the drafted attributes up in the metadata of the entity, which has no dynamic attributes.
 */
// TODO remove once https://github.com/jmix-framework/jmix/issues/5930 is fixed
@NullMarked
@Primary
@Component
public class DynAttrDraftPayloadSupport extends DraftPayloadSupport {

    private static final Logger log = LoggerFactory.getLogger(DynAttrDraftPayloadSupport.class);

    @Override
    protected void applyDiffAttributes(JsonObject entry, DataContext dataContext, Object managed) {
        // the add-on restores the other attributes and would only warn about these
        JsonObject dynamicAttributes = new JsonObject();

        if (entry.get("attributes") instanceof JsonObject attributes) {
            for (String name : List.copyOf(attributes.keySet())) {
                if (DynAttrUtils.isDynamicAttributeProperty(name)) {
                    dynamicAttributes.add(name, attributes.remove(name));
                }
            }
        }

        super.applyDiffAttributes(entry, dataContext, managed);

        MetaClass metaClass = metadata.getClass(managed);

        for (String name : dynamicAttributes.keySet()) {
            MetaPropertyPath path = metadataTools.resolveMetaPropertyPathOrNull(metaClass, name);
            JsonElement value = dynamicAttributes.get(name);

            // a field removed from the type since the draft, or a value that is not a single datatype value
            if (path == null || !path.getRange().isDatatype() || !(value.isJsonNull() || value.isJsonPrimitive())) {
                log.warn("Skipping drafted dynamic attribute '{}' of {}", name, metaClass.getName());
                continue;
            }

            try {
                EntityValues.setValue(managed, name,
                        value.isJsonNull() ? null : path.getRange().asDatatype().parse(value.getAsString()));
            } catch (ParseException e) {
                log.warn("Skipping drafted dynamic attribute '{}' of {}: {}", name, metaClass.getName(),
                        e.getMessage());
            }
        }
    }
}
