package com.company.docai.search;

import io.jmix.searchopensearch.index.OpenSearchIndexSettingsConfigurationContext;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch._types.analysis.TokenFilter;
import org.opensearch.client.opensearch._types.analysis.TokenFilterDefinition;
import org.opensearch.client.opensearch.indices.IndexSettingsAnalysis;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The analyzer of the indexed text. The search tests check word forms on OpenSearch with English samples only,
 * so the Russian stemmer is checked here, in the settings.
 */
class DocAiIndexSettingsTest {

    @Test
    void theTextAnalyzerStemsRussianAndEnglishWords() {
        OpenSearchIndexSettingsConfigurationContext context = new OpenSearchIndexSettingsConfigurationContext();
        new DocAiIndexSettings().configure(context);
        IndexSettingsAnalysis analysis = context.getCommonAnalysisBuilder().build();

        List<String> stemmerLanguages = analysis.analyzer()
                .get(DocAiIndexSettings.TEXT_ANALYZER)
                .custom()
                .filter()
                .stream()
                .map(analysis.filter()::get)
                .filter(Objects::nonNull)
                .map(TokenFilter::definition)
                .filter(TokenFilterDefinition::isStemmer)
                .map(definition -> definition.stemmer().language())
                .toList();

        assertThat(stemmerLanguages).containsExactlyInAnyOrder("russian", "english");
    }
}
