package com.company.dms.test_support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Puts the stub model in place of the OpenAI one. A test class lists this configuration in
 * {@code @SpringBootTest(classes = ...)} explicitly.
 */
@TestConfiguration(proxyBeanMethods = false)
public class StubChatModelConfiguration {

    /**
     * The model the application gets instead of the OpenAI one.
     */
    @Bean
    @Primary
    public StubChatModel stubChatModel() {
        return new StubChatModel();
    }
}
