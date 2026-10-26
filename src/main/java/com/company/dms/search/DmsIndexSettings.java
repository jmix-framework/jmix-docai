package com.company.dms.search;

import io.jmix.searchopensearch.index.OpenSearchIndexSettingsConfigurationContext;
import io.jmix.searchopensearch.index.OpenSearchIndexSettingsConfigurer;
import org.springframework.stereotype.Component;

/**
 * Settings of the search indexes: no replicas, as there is one OpenSearch node, and the analyzer that matches
 * the word forms of Russian and English documents.
 */
@Component
public class DmsIndexSettings implements OpenSearchIndexSettingsConfigurer {

    /**
     * The analyzer of the indexed text: a dot between letters becomes a space, then the standard tokenizer,
     * the Russian and the English stemmer.
     */
    public static final String TEXT_ANALYZER = "dms_text";

    @Override
    public void configure(OpenSearchIndexSettingsConfigurationContext context) {
        context.getCommonIndexSettingsBuilder().numberOfReplicas(0);

        // the standard tokenizer keeps "contract.pdf" one word, so a dot between letters becomes a space;
        // each stemmer changes only the words of its own alphabet, so one analyzer serves both languages
        context.getCommonAnalysisBuilder()
                .charFilter("dms_letter_dot", charFilter -> charFilter.definition(definition ->
                        definition.patternReplace(replace -> replace.pattern("(?<=\\p{L})\\.(?=\\p{L})")
                                .replacement(" "))))
                .filter("dms_russian_stemmer", filter -> filter.definition(definition ->
                        definition.stemmer(stemmer -> stemmer.language("russian"))))
                .filter("dms_english_stemmer", filter -> filter.definition(definition ->
                        definition.stemmer(stemmer -> stemmer.language("english"))))
                .analyzer(TEXT_ANALYZER, analyzer -> analyzer.custom(custom -> custom
                        .charFilter("dms_letter_dot")
                        .tokenizer("standard")
                        .filter("lowercase", "dms_russian_stemmer", "dms_english_stemmer")));
    }
}
