package com.company.docai.app.listener.entity;

import io.jmix.core.event.EntityChangedEvent;
import io.jmix.dynattr.DynAttrMetadata;
import io.jmix.dynattr.model.Category;
import io.jmix.dynattr.model.CategoryAttribute;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Applies a saved document type or field at once, where the add-on reloads its cache only by "Apply changes".
 */
@Component
public class DocumentTypesListener {

    private final DynAttrMetadata dynAttrMetadata;

    public DocumentTypesListener(DynAttrMetadata dynAttrMetadata) {
        this.dynAttrMetadata = dynAttrMetadata;
    }

    @TransactionalEventListener
    public void onTypeChanged(EntityChangedEvent<Category> event) {
        dynAttrMetadata.reload();
    }

    @TransactionalEventListener
    public void onFieldChanged(EntityChangedEvent<CategoryAttribute> event) {
        dynAttrMetadata.reload();
    }
}
