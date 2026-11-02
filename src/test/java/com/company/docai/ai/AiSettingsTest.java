package com.company.docai.ai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The switch of the AI features, checked with the property names in a bare Spring context.
 */
class AiSettingsTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(AiSettings.class);

    @Test
    void theAiFeaturesAreOnWhileTheKeyIsSet() {
        contextRunner.withPropertyValues("spring.ai.openai.api-key=test-key")
                .run(context -> assertThat(context.getBean(AiSettings.class).isAvailable()).isTrue());
    }

    @Test
    void theAiFeaturesAreOffWithoutTheKey() {
        contextRunner.withPropertyValues("spring.ai.openai.api-key=")
                .run(context -> assertThat(context.getBean(AiSettings.class).isAvailable()).isFalse());
    }

    @Test
    void theDisabledFlagTurnsTheAiFeaturesOffWhileTheKeyIsSet() {
        contextRunner.withPropertyValues("spring.ai.openai.api-key=test-key", "docai.ai.enabled=false")
                .run(context -> assertThat(context.getBean(AiSettings.class).isAvailable()).isFalse());
    }
}
