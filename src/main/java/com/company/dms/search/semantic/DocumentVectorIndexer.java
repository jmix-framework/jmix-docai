package com.company.dms.search.semantic;

import com.company.dms.ai.AiSettings;
import com.company.dms.ai.DocumentTextReader;
import com.company.dms.model.document.Document;
import com.company.dms.model.document.DocumentRevision;
import io.jmix.core.FetchPlan;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.entity.KeyValueEntity;
import io.jmix.core.event.EntityChangedEvent;
import io.jmix.core.security.SystemAuthenticator;
import jakarta.annotation.PreDestroy;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps the index of the fragments in line with the documents. A document goes into a queue when it is uploaded, gets
 * another current version or another name, and when it is deleted; at the start of the application the index is
 * checked against the documents. The queue works in the background as the system, and only while the AI is on.
 */
@Component
public class DocumentVectorIndexer {

    private static final Logger log = LoggerFactory.getLogger(DocumentVectorIndexer.class);

    // about 1000 characters of a Russian text, the size of the fragments the prototype found well
    private static final int FRAGMENT_TOKENS = 300;

    private final DocumentVectorIndex index;
    private final EmbeddingModel embeddingModel;
    private final DocumentTextReader textReader;
    private final UnconstrainedDataManager dataManager;
    private final SystemAuthenticator systemAuthenticator;
    private final AiSettings aiSettings;
    private final int maxTextLength;

    private final TokenTextSplitter splitter = TokenTextSplitter.builder()
            .withChunkSize(FRAGMENT_TOKENS)
            .build();

    private final Set<UUID> queued = ConcurrentHashMap.newKeySet();
    private final AtomicInteger waiting = new AtomicInteger();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "dms-vector-indexer");
        thread.setDaemon(true);

        return thread;
    });

    public DocumentVectorIndexer(DocumentVectorIndex index,
                                 EmbeddingModel embeddingModel,
                                 DocumentTextReader textReader,
                                 UnconstrainedDataManager dataManager,
                                 SystemAuthenticator systemAuthenticator,
                                 AiSettings aiSettings,
                                 @Value("${dms.semantic.max-text-length}") int maxTextLength) {
        this.index = index;
        this.embeddingModel = embeddingModel;
        this.textReader = textReader;
        this.dataManager = dataManager;
        this.systemAuthenticator = systemAuthenticator;
        this.aiSettings = aiSettings;
        this.maxTextLength = maxTextLength;
    }

    /**
     * The result of a check of the index against the documents.
     *
     * @param queued  how many documents went into the queue
     * @param removed how many documents left the index
     */
    public record Reconciliation(int queued, int removed) {
    }

    // a move, a status, a type or access do not change what a document says: the rights apply when it is searched;
    // a document restored from the trash comes back into the index
    @TransactionalEventListener
    public void onDocumentChanged(EntityChangedEvent<Document> event) {
        if (event.getType() == EntityChangedEvent.Type.UPDATED
                && !event.getChanges().isChanged("name")
                && !event.getChanges().isChanged("currentRevision")
                && !event.getChanges().isChanged("deletedDate")) {
            return;
        }

        enqueue((UUID) event.getEntityId().getValue());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!aiSettings.isAvailable()) {
            return;
        }

        submit(() -> {
            Reconciliation result = systemAuthenticator.withSystem(this::reconcile);

            log.info("The index of fragments is checked: {} documents queued, {} removed", result.queued(),
                    result.removed());
        }, "Cannot check the index of fragments");
    }

    /**
     * Puts the document into the queue, unless it waits there already or the AI is off.
     */
    public void enqueue(UUID documentId) {
        if (!aiSettings.isAvailable() || !queued.add(documentId)) {
            return;
        }

        submit(() -> {
            // a change made while the document is indexed puts it into the queue again
            queued.remove(documentId);
            systemAuthenticator.runWithSystem(() -> index(documentId));
        }, "Cannot index the fragments of the document " + documentId);
    }

    /**
     * Returns whether the index is being checked against the documents, or documents wait in the queue or are being
     * indexed.
     */
    public boolean isBusy() {
        return waiting.get() > 0;
    }

    /**
     * Brings the index in line with the documents: queues the documents that are not in it or were cut from another
     * version or name, and removes the documents that are gone. Creates the index anew when it was built with another
     * model.
     */
    public Reconciliation reconcile() {
        index.prepare();

        Map<UUID, String> indexed = index.sourceKeys();

        List<KeyValueEntity> documents = dataManager.loadValues(
                        "select d.id, d.name, r.id from Document d left join d.currentRevision r")
                .properties("id", "name", "revisionId")
                .list();

        Set<UUID> live = new HashSet<>();
        int queuedCount = 0;

        for (KeyValueEntity document : documents) {
            UUID id = document.getValue("id");
            live.add(id);

            if (!sourceKey(document.getValue("revisionId"), document.getValue("name")).equals(indexed.get(id))) {
                enqueue(id);
                queuedCount++;
            }
        }

        Set<UUID> gone = new HashSet<>(indexed.keySet());
        gone.removeAll(live);
        index.delete(gone);

        return new Reconciliation(queuedCount, gone.size());
    }

    /**
     * Indexes the document now: cuts its text into fragments, gets their vectors and replaces its fragments in the
     * index. A document without text gets one fragment of its name; a document that is gone leaves the index.
     */
    public void index(UUID documentId) {
        Optional<Document> found = dataManager.load(Document.class)
                .id(documentId)
                .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan(FetchPlan.BASE)
                        .add("currentRevision", FetchPlan.BASE))
                .optional();

        if (found.isEmpty()) {
            index.delete(List.of(documentId));
            return;
        }

        Document document = found.get();
        String name = document.getName();
        List<String> texts = textsOf(document.getCurrentRevision());
        boolean ofName = texts.isEmpty();

        // the name tells what a document is about as much as its text does
        List<float[]> vectors = embeddingModel.embed(ofName
                ? List.of(name)
                : texts.stream().map(text -> name + "\n\n" + text).toList());

        List<DocumentVectorIndex.Fragment> fragments = new ArrayList<>();

        for (int number = 0; number < vectors.size(); number++) {
            fragments.add(new DocumentVectorIndex.Fragment(number, ofName ? name : texts.get(number), ofName,
                    vectors.get(number)));
        }

        index.replace(documentId, sourceKeyOf(document), fragments);
    }

    /**
     * Returns what the fragments of the document are cut from: its current version and its name.
     */
    public static String sourceKeyOf(Document document) {
        DocumentRevision revision = document.getCurrentRevision();

        return sourceKey(revision == null ? null : revision.getId(), document.getName());
    }

    @PreDestroy
    public void shutdown() throws InterruptedException {
        worker.shutdownNow();

        if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
            log.warn("The queue of the index of fragments did not stop in 5 seconds");
        }
    }

    // the check at the start counts too: until it is over, the documents changed meanwhile are not found by meaning
    private void submit(Runnable task, String failure) {
        waiting.incrementAndGet();

        worker.submit(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.warn("{}: {}", failure, e.toString());
            } finally {
                waiting.decrementAndGet();
            }
        });
    }

    private List<String> textsOf(@Nullable DocumentRevision revision) {
        return Optional.ofNullable(revision)
                .flatMap(current -> textReader.readStart(current, maxTextLength))
                .map(text -> splitter.split(new org.springframework.ai.document.Document(text))
                        .stream()
                        .map(org.springframework.ai.document.Document::getText)
                        .filter(Objects::nonNull)
                        .toList())
                .orElse(List.of());
    }

    private static String sourceKey(@Nullable UUID revisionId, String name) {
        return revisionId + ":" + name;
    }
}
