package com.company.dms.test_support;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A model of vectors that gives the same vector for the same words: each word of a text adds to one coordinate, so
 * texts with common words are near each other. It keeps the texts it got.
 */
public class StubEmbeddingModel implements EmbeddingModel {

    /**
     * The size of the vectors, the one the application is set up with.
     */
    public static final int DIMENSIONS = 3072;

    private final List<String> texts = new CopyOnWriteArrayList<>();

    /**
     * Returns the vector of the text.
     */
    public static float[] vectorOf(String text) {
        float[] vector = new float[DIMENSIONS];

        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!word.isEmpty()) {
                vector[Math.floorMod(word.hashCode(), DIMENSIONS)] += 1;
            }
        }

        double length = 0;

        for (float value : vector) {
            length += value * value;
        }

        if (length > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= (float) Math.sqrt(length);
            }
        }

        return vector;
    }

    /**
     * Returns the texts the model got, in the order of the calls.
     */
    public List<String> texts() {
        return texts;
    }

    /**
     * Forgets the texts.
     */
    public void reset() {
        texts.clear();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddings = new ArrayList<>();

        for (String text : request.getInstructions()) {
            texts.add(text);
            embeddings.add(new Embedding(vectorOf(text), embeddings.size()));
        }

        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText() == null ? "" : document.getText());
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }
}
