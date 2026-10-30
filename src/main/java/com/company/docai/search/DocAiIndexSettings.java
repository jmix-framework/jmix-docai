package com.company.docai.search;

import io.jmix.searchopensearch.index.OpenSearchIndexSettingsConfigurationContext;
import io.jmix.searchopensearch.index.OpenSearchIndexSettingsConfigurer;
import org.springframework.stereotype.Component;

/**
 * Settings of the search indexes: no replicas, as there is one OpenSearch node, and the analyzer that matches
 * the word forms of Russian and English documents.
 */
@Component
public class DocAiIndexSettings implements OpenSearchIndexSettingsConfigurer {

    /**
     * The analyzer of the indexed text: a dot between letters becomes a space, then the standard tokenizer,
     * the Russian and the English stemmer.
     */
    public static final String TEXT_ANALYZER = "docai_text";

    @Override
    public void configure(OpenSearchIndexSettingsConfigurationContext context) {
        context.getCommonIndexSettingsBuilder().numberOfReplicas(0);

        // the standard tokenizer keeps "contract.pdf" one word, so a dot between letters becomes a space;
        // each stemmer changes only the words of its own alphabet, so one analyzer serves both languages
        context.getCommonAnalysisBuilder()
                .charFilter("docai_letter_dot", charFilter -> charFilter.definition(definition ->
                        definition.patternReplace(replace -> replace.pattern("(?<=\\p{L})\\.(?=\\p{L})")
                                .replacement(" "))))
                .filter("docai_russian_stemmer", filter -> filter.definition(definition ->
                        definition.stemmer(stemmer -> stemmer.language("russian"))))
                .filter("docai_english_stemmer", filter -> filter.definition(definition ->
                        definition.stemmer(stemmer -> stemmer.language("english"))))
                .analyzer(TEXT_ANALYZER, analyzer -> analyzer.custom(custom -> custom
                        .charFilter("docai_letter_dot")
                        .tokenizer("standard")
                        .filter("lowercase", "docai_russian_stemmer", "docai_english_stemmer")));
    }
}
