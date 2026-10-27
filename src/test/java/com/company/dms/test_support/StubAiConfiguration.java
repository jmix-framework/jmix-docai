package com.company.dms.test_support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Puts the stub models in place of the OpenAI ones and the index of fragments in memory in place of the one in
 * OpenSearch, so that a test with a key reaches neither. A test class lists this configuration in
 * {@code @SpringBootTest(classes = ...)} explicitly.
 */
@TestConfiguration(proxyBeanMethods = false)
public class StubAiConfiguration {

    /**
     * The chat model the application gets instead of the OpenAI one.
     */
    @Bean
    @Primary
    public StubChatModel stubChatModel() {
        return new StubChatModel();
    }

    /**
     * The model of vectors the application gets instead of the OpenAI one.
     */
    @Bean
    @Primary
    public StubEmbeddingModel stubEmbeddingModel() {
        return new StubEmbeddingModel();
    }

    /**
     * The index of fragments the application gets instead of the one in OpenSearch.
     */
    @Bean
    @Primary
    public InMemoryVectorIndex inMemoryVectorIndex() {
        return new InMemoryVectorIndex();
    }
}
