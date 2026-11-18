package com.company.docai.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Settings of the AI features: on with a key of the model provider unless {@code docai.ai.enabled} is false.
 */
@Component
public class AiSettings {

    private final boolean available;
    private final int maxTextLength;

    public AiSettings(@Value("${spring.ai.openai.api-key:}") String apiKey,
                      @Value("${docai.ai.enabled:true}") boolean enabled,
                      @Value("${docai.ai.max-text-length:12000}") int maxTextLength) {
        this.available = enabled && !apiKey.isBlank();
        this.maxTextLength = maxTextLength;
    }

    /**
     * Returns whether the AI features are on: the key of the model provider is set and they are not turned off.
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Returns how many characters of the text of a document the model reads; the rest is cut off.
     */
    public int getMaxTextLength() {
        return maxTextLength;
    }
}
