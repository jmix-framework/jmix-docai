package com.company.docai.search.semantic;

import com.company.docai.ai.AiSettings;
import com.company.docai.ai.DocumentTextReader;
import com.company.docai.model.document.Document;
import com.company.docai.model.document.DocumentRevision;
import com.company.docai.stand.StandScope;
import com.company.docai.stand.StandScopeHolder;
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
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
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
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Keeps the index of the fragments in line with the documents. A document goes into a queue when it is uploaded, gets
 * another current version or another name, and when it is deleted; at the start of the application the index is
 * checked against the documents. The queue works in the background as the system, and only while the AI is on. On the
 * stand a document is indexed in the scope it was queued in, the template or the copy whose database and index it
 * belongs to; the control database has no documents, so the start checks nothing there.
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
    private final Environment environment;

    private final TokenTextSplitter splitter = TokenTextSplitter.builder()
            .withChunkSize(FRAGMENT_TOKENS)
            .build();

    private final Set<QueuedDocument> queued = ConcurrentHashMap.newKeySet();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition done = lock.newCondition();
    // the tasks that wait or run, by the id of their scope ("" without one), and the scope of the task that runs;
    // guarded by the lock
    private final Map<String, Integer> tasks = new HashMap<>();
    private @Nullable String running;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "docai-vector-indexer");
        thread.setDaemon(true);

        return thread;
    });

    public DocumentVectorIndexer(DocumentVectorIndex index,
                                 EmbeddingModel embeddingModel,
                                 DocumentTextReader textReader,
                                 UnconstrainedDataManager dataManager,
                                 SystemAuthenticator systemAuthenticator,
                                 AiSettings aiSettings,
                                 @Value("${docai.semantic.max-text-length}") int maxTextLength,
                                 Environment environment) {
        this.index = index;
        this.embeddingModel = embeddingModel;
        this.textReader = textReader;
        this.dataManager = dataManager;
        this.systemAuthenticator = systemAuthenticator;
        this.aiSettings = aiSettings;
        this.maxTextLength = maxTextLength;
        this.environment = environment;
    }

    /**
     * The result of a check of the index against the documents.
     *
     * @param queued  how many documents went into the queue
     * @param removed how many documents left the index
     */
    public record Reconciliation(int queued, int removed) {
    }

    // the copies of a template keep the ids of its documents, so a document waits once in each scope
    private record QueuedDocument(@Nullable StandScope scope, UUID documentId) {
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
        if (!aiSettings.isAvailable() || environment.matchesProfiles("stand")) {
            return;
        }

        submit(null, () -> {
            Reconciliation result = systemAuthenticator.withSystem(this::reconcile);

            log.info("The index of fragments is checked: {} documents queued, {} removed", result.queued(),
                    result.removed());
        }, "Cannot check the index of fragments");
    }

    /**
     * Puts the document into the queue, unless it waits there already or the AI is off. The document is indexed in the
     * scope of the stand the calling thread works with, see {@link StandScopeHolder#resolve()}.
     */
    public void enqueue(UUID documentId) {
        StandScope scope = StandScopeHolder.resolve();
        QueuedDocument document = new QueuedDocument(scope, documentId);

        if (!aiSettings.isAvailable() || !queued.add(document)) {
            return;
        }

        submit(scope, () -> {
            // a forgotten scope took its documents out of the queue; a change made while the document is indexed
            // puts it into the queue again
            if (queued.remove(document)) {
                systemAuthenticator.runWithSystem(() -> index(documentId));
            }
        }, "Cannot index the fragments of the document " + documentId);
    }

    /**
     * Returns whether the index is being checked against the documents, or documents wait in the queue or are being
     * indexed, in the scope of the stand the calling thread works with.
     */
    public boolean isBusy() {
        String key = keyOf(StandScopeHolder.resolve());
        lock.lock();

        try {
            return tasks.containsKey(key);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Takes the scope out of the queue: its waiting documents are not indexed, and the call returns once the document
     * of the scope that is being indexed, if any, is done. A copy of the stand is forgotten before its index goes, so
     * that nothing writes the index afterwards.
     */
    public void forget(StandScope scope) {
        queued.removeIf(document -> scope.equals(document.scope()));
        String key = keyOf(scope);
        lock.lock();

        try {
            while (key.equals(running)) {
                done.awaitUninterruptibly();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns how many documents of the scope wait in the queue or are being indexed.
     */
    public int waiting(StandScope scope) {
        String key = keyOf(scope);
        lock.lock();

        try {
            return tasks.getOrDefault(key, 0);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits until no document of the scope waits in the queue or is being indexed, each indexed or failed.
     *
     * @return whether that happened within the timeout
     */
    public boolean awaitIdle(StandScope scope, Duration timeout) throws InterruptedException {
        String key = keyOf(scope);
        long left = timeout.toNanos();
        lock.lock();

        try {
            while (tasks.containsKey(key)) {
                if (left <= 0) {
                    return false;
                }

                left = done.awaitNanos(left);
            }

            return true;
        } finally {
            lock.unlock();
        }
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
    private void submit(@Nullable StandScope scope, Runnable task, String failure) {
        String key = keyOf(scope);
        count(key, 1);

        worker.submit(() -> {
            start(key);

            try {
                if (scope == null) {
                    task.run();
                } else {
                    StandScopeHolder.run(scope, task);
                }
            } catch (RuntimeException e) {
                log.warn("{}: {}", failure, e.toString());
            } finally {
                finish(key);
            }
        });
    }

    private void count(String key, int change) {
        lock.lock();

        try {
            int left = tasks.getOrDefault(key, 0) + change;

            if (left > 0) {
                tasks.put(key, left);
            } else {
                tasks.remove(key);
                done.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    private void start(String key) {
        lock.lock();

        try {
            running = key;
        } finally {
            lock.unlock();
        }
    }

    private void finish(String key) {
        lock.lock();

        try {
            running = null;
            count(key, -1);
            done.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private static String keyOf(@Nullable StandScope scope) {
        return scope == null ? "" : scope.id();
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
