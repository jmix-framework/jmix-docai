package com.company.docai.test_support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Puts the stub models in place of the OpenAI ones and the index of fragments in memory in place of the one in
 * OpenSearch, so that a test with a key reaches neither.
 */
@TestConfiguration(proxyBeanMethods = false)
public class StubAiConfiguration {

    @Bean
    @Primary
    public StubChatModel stubChatModel() {
        return new StubChatModel();
    }

    @Bean
    @Primary
    public StubEmbeddingModel stubEmbeddingModel() {
        return new StubEmbeddingModel();
    }

    @Bean
    @Primary
    public InMemoryVectorIndex inMemoryVectorIndex() {
        return new InMemoryVectorIndex();
    }
}
