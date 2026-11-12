package com.company.docai.search;

import io.jmix.searchopensearch.index.OpenSearchIndexSettingsConfigurationContext;
import io.jmix.searchopensearch.index.OpenSearchIndexSettingsConfigurer;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Settings of the search indexes: no replicas, as there is one OpenSearch node, the analyzer that matches the word
 * forms of Russian and English documents, and the token filters that end a word at a dot between letters in the
 * beginnings of the words, as the analyzer does in the whole words.
 */
@Component
public class DocAiIndexSettings implements OpenSearchIndexSettingsConfigurer {

    /**
     * The analyzer of the indexed text: a dot between letters becomes a space, then the standard tokenizer,
     * the Russian and the English stemmer.
     */
    public static final String TEXT_ANALYZER = "docai_text";

    /**
     * The token filter of the beginnings of the words that turns a dot between letters into a space, where
     * {@link #PREFIX_WORD_SPLIT} splits the word.
     */
    public static final String PREFIX_LETTER_DOT = "docai_prefix_letter_dot";

    /**
     * The token filter of the beginnings of the words that splits a word at a space, and not at the characters that
     * the standard tokenizer keeps inside Russian and English words.
     */
    public static final String PREFIX_WORD_SPLIT = "docai_prefix_word_split";

    private static final String LETTER_DOT = "(?<=\\p{L})\\.(?=\\p{L})";
    // the characters the standard tokenizer keeps inside Russian and English words stay in them: the dot, comma,
    // semicolon, colon, apostrophes, underscore and middle dot, the soft hyphen, the zero-width non-joiner and
    // joiner, the word joiner, the narrow no-break space and the byte order mark
    private static final List<String> IN_WORD = ".,;:'’‘_·\u00AD\u200C\u200D\u2060\u202F\uFEFF"
            .chars()
            .mapToObj("\\u%04X => ALPHANUM"::formatted)
            .toList();

    @Override
    public void configure(OpenSearchIndexSettingsConfigurationContext context) {
        context.getCommonIndexSettingsBuilder().numberOfReplicas(0);

        // the standard tokenizer keeps "contract.pdf" one word, so a dot between letters becomes a space;
        // each stemmer changes only the words of its own alphabet, so one analyzer serves both languages
        context.getCommonAnalysisBuilder()
                .charFilter("docai_letter_dot", charFilter -> charFilter.definition(definition ->
                        definition.patternReplace(replace -> replace.pattern(LETTER_DOT)
                                .replacement(" "))))
                .filter("docai_russian_stemmer", filter -> filter.definition(definition ->
                        definition.stemmer(stemmer -> stemmer.language("russian"))))
                .filter("docai_english_stemmer", filter -> filter.definition(definition ->
                        definition.stemmer(stemmer -> stemmer.language("english"))))
                .analyzer(TEXT_ANALYZER, analyzer -> analyzer.custom(custom -> custom
                        .charFilter("docai_letter_dot")
                        .tokenizer("standard")
                        .filter("lowercase", "docai_russian_stemmer", "docai_english_stemmer")));

        // the extended search takes token filters only, so its words are split at such a dot after the tokenizer
        context.getCommonAnalysisBuilder()
                .filter(PREFIX_LETTER_DOT, filter -> filter.definition(definition ->
                        definition.patternReplace(replace -> replace.pattern(LETTER_DOT)
                                .replacement(" "))))
                .filter(PREFIX_WORD_SPLIT, filter -> filter.definition(definition ->
                        definition.wordDelimiterGraph(split -> split.splitOnCaseChange(false)
                                .splitOnNumerics(false)
                                .stemEnglishPossessive(false)
                                .typeTable(IN_WORD))));
    }
}
