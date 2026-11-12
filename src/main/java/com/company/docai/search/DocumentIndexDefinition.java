package com.company.docai.search;

import com.company.docai.model.document.Document;
import io.jmix.search.index.annotation.AutoMappedField;
import io.jmix.search.index.annotation.DynamicAttributes;
import io.jmix.search.index.annotation.ExtendedSearch;
import io.jmix.search.index.annotation.JmixEntitySearchIndex;

/**
 * What full-text search sees of a document: its name, the name and text of the file of its current version, the names
 * of its tags and the text and list fields of its type. Older versions, the status, the folder and the type name are
 * not searched, and the add-on does not index numbers and dates at all. The add-on indexes the tags by their instance
 * names and reindexes the documents of a renamed tag itself. The extended search keeps the beginnings of the words of
 * every field as well, from three to eight letters, for the search palette. Its standard tokenizer leaves commas,
 * quotes and brackets out of the words, and the filters of {@link DocAiIndexSettings} split two words joined by a
 * dot, as the main analyzer does: "lease" and "pdf" of "lease.pdf" have beginnings of their own, and a match marks
 * the word only. A new mapping makes the add-on recreate the index at the next start, and
 * {@link SearchIndexInitializer} fills it again.
 */
@JmixEntitySearchIndex(entity = Document.class)
@ExtendedSearch(tokenizer = "standard",
        additionalFilters = {DocAiIndexSettings.PREFIX_LETTER_DOT, DocAiIndexSettings.PREFIX_WORD_SPLIT})
public interface DocumentIndexDefinition {

    @AutoMappedField(includeProperties = {"name", "currentRevision.file", "tags"},
            analyzer = DocAiIndexSettings.TEXT_ANALYZER)
    @DynamicAttributes(analyzer = DocAiIndexSettings.TEXT_ANALYZER)
    void mapping();
}
