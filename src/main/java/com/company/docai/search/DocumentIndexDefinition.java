package com.company.docai.search;

import com.company.docai.model.document.Document;
import io.jmix.search.index.annotation.AutoMappedField;
import io.jmix.search.index.annotation.DynamicAttributes;
import io.jmix.search.index.annotation.JmixEntitySearchIndex;

/**
 * What full-text search sees of a document: its name, the name and text of the file of its current version, the names
 * of its tags and the text and list fields of its type. Older versions, the status, the folder and the type name are
 * not searched, and the add-on does not index numbers and dates at all. The add-on indexes the tags by their instance
 * names and reindexes the documents of a renamed tag itself.
 */
@JmixEntitySearchIndex(entity = Document.class)
public interface DocumentIndexDefinition {

    @AutoMappedField(includeProperties = {"name", "currentRevision.file", "tags"},
            analyzer = DocAiIndexSettings.TEXT_ANALYZER)
    @DynamicAttributes(analyzer = DocAiIndexSettings.TEXT_ANALYZER)
    void mapping();
}
