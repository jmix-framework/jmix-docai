package com.company.dms.search;

import com.company.dms.model.document.Document;
import io.jmix.search.index.annotation.AutoMappedField;
import io.jmix.search.index.annotation.DynamicAttributes;
import io.jmix.search.index.annotation.JmixEntitySearchIndex;

/**
 * What full-text search sees of a document: its name, the name and text of the file of its current version and
 * the text and list fields of its type. Older versions, the status, the folder and the type name are not searched,
 * and the add-on does not index numbers and dates at all.
 */
@JmixEntitySearchIndex(entity = Document.class)
public interface DocumentIndexDefinition {

    @AutoMappedField(includeProperties = {"name", "currentRevision.file"}, analyzer = DmsIndexSettings.TEXT_ANALYZER)
    @DynamicAttributes(analyzer = DmsIndexSettings.TEXT_ANALYZER)
    void mapping();
}
