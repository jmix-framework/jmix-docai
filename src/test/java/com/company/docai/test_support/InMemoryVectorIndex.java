package com.company.docai.test_support;

import com.company.docai.search.semantic.DocumentVectorIndex;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * The index of fragments kept in memory in place of OpenSearch. It finds the nearest fragments by the cosine of their
 * vectors among the documents it is given, as OpenSearch does, keeps the documents every search was allowed, and can
 * be told not to answer.
 */
public class InMemoryVectorIndex extends DocumentVectorIndex {

    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final List<Collection<UUID>> searchedAmong = new CopyOnWriteArrayList<>();
    private final List<UUID> replaced = new CopyOnWriteArrayList<>();
    private volatile boolean down;

    private record Entry(String sourceKey, List<Fragment> fragments) {
    }

    public InMemoryVectorIndex() {
        super(null, "test_", "stub", StubEmbeddingModel.DIMENSIONS);
    }

    @Override
    public synchronized boolean prepare() {
        failIfDown();

        return false;
    }

    @Override
    public void replace(UUID documentId, String sourceKey, List<Fragment> fragments) {
        failIfDown();
        entries.put(documentId, new Entry(sourceKey, List.copyOf(fragments)));
        replaced.add(documentId);
    }

    @Override
    public void delete(Collection<UUID> documentIds) {
        failIfDown();
        documentIds.forEach(entries::remove);
    }

    @Override
    public List<Match> nearest(float[] vector, Collection<UUID> documentIds, int count) {
        failIfDown();
        searchedAmong.add(List.copyOf(documentIds));

        return entries.entrySet()
                .stream()
                .filter(entry -> documentIds.contains(entry.getKey()))
                .flatMap(entry -> entry.getValue().fragments().stream().map(fragment -> Map.entry(entry.getKey(),
                        fragment)))
                .sorted(Comparator.comparingDouble(entry -> -cosine(vector, entry.getValue().vector())))
                .limit(count)
                .map(entry -> new Match(entry.getKey(), entry.getValue().text(), entry.getValue().ofName()))
                .toList();
    }

    @Override
    public Map<UUID, String> sourceKeys() {
        failIfDown();

        return entries.entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().sourceKey()));
    }

    /**
     * Puts the fragments of a document into the index, as an indexing would.
     */
    public void put(UUID documentId, String sourceKey, List<Fragment> fragments) {
        entries.put(documentId, new Entry(sourceKey, List.copyOf(fragments)));
    }

    /**
     * Returns the fragments of the document in the index, or an empty list.
     */
    public List<Fragment> fragmentsOf(UUID documentId) {
        Entry entry = entries.get(documentId);

        return entry == null ? List.of() : entry.fragments();
    }

    /**
     * Returns how many times the fragments of the document were replaced.
     */
    public long replacements(UUID documentId) {
        return replaced.stream()
                .filter(documentId::equals)
                .count();
    }

    /**
     * Returns the documents every search was allowed, the latest last.
     */
    public List<Collection<UUID>> searchedAmong() {
        return searchedAmong;
    }

    /**
     * Makes the index fail as OpenSearch that does not answer, or answer again.
     */
    public void setDown(boolean down) {
        this.down = down;
    }

    /**
     * Empties the index, forgets the searches and answers again.
     */
    public void reset() {
        entries.clear();
        searchedAmong.clear();
        replaced.clear();
        down = false;
    }

    private void failIfDown() {
        if (down) {
            throw new IllegalStateException("The index does not answer");
        }
    }

    private static double cosine(float[] first, float[] second) {
        double product = 0;

        for (int i = 0; i < first.length; i++) {
            product += first[i] * second[i];
        }

        return product;
    }
}
