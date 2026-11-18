package com.company.docai.search;

import com.company.docai.model.document.Document;
import io.jmix.search.index.annotation.AutoMappedField;
import io.jmix.search.index.annotation.DynamicAttributes;
import io.jmix.search.index.annotation.ExtendedSearch;
import io.jmix.search.index.annotation.JmixEntitySearchIndex;

/**
 * What full-text search sees of a document: its name, the name and text of the file of its current version, the names
 * of its tags and the text and list fields of its type. The extended search keeps the beginnings of the words of every
 * field as well, from three to eight letters, for the search palette.
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
