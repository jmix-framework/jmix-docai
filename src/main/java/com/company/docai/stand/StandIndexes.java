package com.company.docai.stand;

import com.company.docai.search.semantic.DocumentVectorIndex;
import com.company.docai.search.semantic.DocumentVectorIndexer;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.search.SearchProperties;
import io.jmix.search.index.IndexConfiguration;
import io.jmix.search.index.mapping.IndexConfigurationManager;
import io.jmix.search.index.queue.IndexingQueueManager;
import org.jspecify.annotations.Nullable;
import org.opensearch.client.json.JsonData;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ExpandWildcard;
import org.opensearch.client.opensearch.indices.CloneIndexRequest;
import org.opensearch.client.opensearch.indices.CloneIndexResponse;
import org.opensearch.client.opensearch.indices.DeleteIndexRequest;
import org.opensearch.client.opensearch.indices.DeleteIndexResponse;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.opensearch.indices.PutIndicesSettingsRequest;
import org.opensearch.client.opensearch.indices.PutIndicesSettingsResponse;
import org.opensearch.client.opensearch.indices.RefreshRequest;
import org.opensearch.client.opensearch.indices.ResolveIndexRequest;
import org.opensearch.client.opensearch.indices.resolve_index.ResolveIndexItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The search indexes of the scopes of the stand. Each template and each copy has an index of the search add-on and an
 * index of fragments of its own, named by the scope, see {@link ScopedIndexConfiguration} and
 * {@link DocumentVectorIndex}: a template fills its indexes with its demo set and seals them read-only, a copy gets
 * clones of the indexes of its template, which take its writes, and the indexes go with the copy. The queue of the
 * add-on lives in the database of each copy, while the built-in jobs of the add-on read only the control database, so
 * one loop processes the queue of every copy in its scope. An index is changed or deleted by its exact name, never by
 * a pattern, which only lists the indexes of the copies an earlier run left; with search off nothing here calls
 * OpenSearch.
 */
@Component
@Profile("stand")
public class StandIndexes {

    private static final Logger log = LoggerFactory.getLogger(StandIndexes.class);

    // how long a template waits for its indexes: the queue of its demo set and the vectors of its documents
    private static final Duration TEMPLATE_TIMEOUT = Duration.ofMinutes(10);

    // the add-on processes one queue at a time in the whole JVM and gives up at once while the loop holds it
    private static final Duration RETRY_PAUSE = Duration.ofMillis(200);

    private static final String REPLICAS = "index.number_of_replicas";
    private static final String WRITE_BLOCK = "index.blocks.write";

    // the start of the names of the indexes of every copy after the prefix of the application, as StandScope names a
    // copy
    private static final String COPIES = "copy_";

    private final SearchProperties searchProperties;
    private final IndexConfigurationManager indexConfigurationManager;
    private final IndexingQueueManager indexingQueueManager;
    private final OpenSearchClient client;
    private final DocumentVectorIndex vectorIndex;
    private final DocumentVectorIndexer vectorIndexer;
    private final UnconstrainedDataManager dataManager;
    private final SystemAuthenticator systemAuthenticator;

    private final Set<StandScope> live = ConcurrentHashMap.newKeySet();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition processed = lock.newCondition();
    // the scope whose queue the loop processes now; guarded by the lock
    private @Nullable StandScope processing;

    public StandIndexes(SearchProperties searchProperties,
                        IndexConfigurationManager indexConfigurationManager,
                        IndexingQueueManager indexingQueueManager,
                        OpenSearchClient client,
                        DocumentVectorIndex vectorIndex,
                        DocumentVectorIndexer vectorIndexer,
                        UnconstrainedDataManager dataManager,
                        SystemAuthenticator systemAuthenticator) {
        this.searchProperties = searchProperties;
        this.indexConfigurationManager = indexConfigurationManager;
        this.indexingQueueManager = indexingQueueManager;
        this.client = client;
        this.vectorIndex = vectorIndex;
        this.vectorIndexer = vectorIndexer;
        this.dataManager = dataManager;
        this.systemAuthenticator = systemAuthenticator;
    }

    /**
     * Puts a copy that has its indexes into the loop of the search queues.
     */
    public void join(StandScope scope) {
        live.add(scope);
    }

    /**
     * Takes the scope out of the loop of the search queues and returns once the loop is done with its batch in flight,
     * if any, so that the loop writes the indexes of the scope no more.
     */
    public void leave(StandScope scope) {
        lock.lock();

        try {
            live.remove(scope);

            while (scope.equals(processing)) {
                processed.awaitUninterruptibly();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Processes the next batch of the search queue of each copy in the loop, as the system and in the scope of the
     * copy: the add-on reads the queue from the database of the scope of the thread and writes the index of that
     * scope. The job of the search queues runs it every five seconds, see {@link SearchQueuesJob}.
     */
    public void processQueues() {
        if (!searchProperties.isEnabled()) {
            return;
        }

        for (StandScope scope : List.copyOf(live)) {
            if (!start(scope)) {
                continue;
            }

            try {
                StandScopeHolder.run(scope, () -> systemAuthenticator.runWithSystem(() -> processQueueOf(scope)));
            } catch (RuntimeException e) {
                log.warn("The search queue of {} is not processed: {}", scope.tag(), StandScope.withTags(e.toString()));
            } finally {
                finish();
            }
        }
    }

    /**
     * Deletes the indexes of an earlier build of the template by their exact names and creates its index of fragments
     * anew, empty: the vector indexer takes up the documents of the set as soon as they are saved, the removed ones
     * too, and removes those from an index that has to be there. The loader of the set creates the index of the
     * add-on.
     */
    public void renew(StandScope template) {
        if (!searchProperties.isEnabled()) {
            return;
        }

        delete(template);
        StandScopeHolder.run(template, vectorIndex::prepare);
    }

    /**
     * Brings the indexes of the template in line with its demo set while its database is in the data source:
     * processes its search queue to the end in its scope and waits until the vector indexer is done with each of its
     * documents, indexed or failed. Vectors that are not done in ten minutes are given up with a warning: the model is
     * optional, so the template keeps the vectors it has, and the documents still waiting fail later at its database,
     * which is gone by then.
     *
     * @throws IllegalStateException when the search queue is not processed in ten minutes, or OpenSearch fails
     */
    public void complete(StandScope template) {
        long deadline = System.nanoTime() + TEMPLATE_TIMEOUT.toNanos();

        try {
            if (searchProperties.isEnabled()) {
                processTheWholeQueue(template, deadline);
            }

            if (!vectorIndexer.awaitIdle(template, Duration.ofNanos(deadline - System.nanoTime()))) {
                log.warn("The vectors of {} are not done in {} minutes: {} documents wait; the template keeps the "
                        + "vectors it has", template.tag(), TEMPLATE_TIMEOUT.toMinutes(),
                        vectorIndexer.waiting(template));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException("The indexes of " + template.id() + " are not complete", e);
        }
    }

    /**
     * Makes the indexes of the built template ready for the clones of its copies: refreshes them, then blocks their
     * writes and leaves them without replicas, as a clone needs.
     */
    public void seal(StandScope template) {
        if (!searchProperties.isEnabled()) {
            return;
        }

        List<String> names = namesOf(template);
        PutIndicesSettingsResponse response;

        try {
            client.indices()
                    .refresh(RefreshRequest.of(request -> request.index(names)));

            response = client.indices()
                    .putSettings(PutIndicesSettingsRequest.of(request -> request.index(names)
                            .settings(settings -> settings.index(index -> index
                                    .blocks(blocks -> blocks.write(true))
                                    .numberOfReplicas(0)))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        if (!response.acknowledged()) {
            throw new IllegalStateException("OpenSearch did not seal the indexes " + names);
        }

        log.info("The indexes of {} are sealed: {}", template.id(), names);
    }

    /**
     * Gives the copy clones of the indexes of the template of its language, open for writes and without replicas: a
     * clone shares the files of its source until it is written, so it costs no copying.
     *
     * @throws RuntimeException when OpenSearch does not clone an index; the clones made so far are deleted then
     */
    public void cloneTemplate(String language, StandScope copy) {
        if (!searchProperties.isEnabled()) {
            return;
        }

        List<String> sources = namesOf(StandScope.template(language));
        List<String> targets = namesOf(copy);

        try {
            for (int i = 0; i < sources.size(); i++) {
                cloneIndex(sources.get(i), targets.get(i));
            }
        } catch (RuntimeException e) {
            try {
                delete(copy);
            } catch (RuntimeException cleanup) {
                e.addSuppressed(cleanup);
            }

            throw e;
        }

        log.info("The indexes of {} are cloned for {}", sources, copy.tag());
    }

    /**
     * Deletes the indexes of the scope by their exact names, those that exist. A copy leaves the loop of the queues and
     * the vector indexer first: a write after the deletion would create an index anew, with a mapping OpenSearch
     * guesses.
     */
    public void delete(StandScope scope) {
        if (!searchProperties.isEnabled()) {
            return;
        }

        List<String> names = namesOf(scope);
        deleteIndexes(names);
        // the names of the indexes of a copy carry its key
        log.info("The indexes of {} are deleted: {}", scope.tag(), names.size());
    }

    /**
     * Whether OpenSearch has every index of the scope, each asked for by its exact name; with search off there is no
     * index to ask for, so the answer is yes.
     */
    public boolean exist(StandScope scope) {
        if (!searchProperties.isEnabled()) {
            return true;
        }

        try {
            for (String name : namesOf(scope)) {
                boolean found = client.indices()
                        .exists(ExistsRequest.of(request -> request.index(name)))
                        .value();

                if (!found) {
                    return false;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return true;
    }

    /**
     * Deletes the indexes of the copies that are gone, as an earlier run of the application leaves them: lists the
     * indexes of copies under the prefix of the application, then deletes each index of a well-formed key the
     * predicate does not know, one by one, by its exact name. An index of another prefix, or one whose name the stand
     * gives no copy, stays; one that is not deleted stays for the next start; with search off nothing is called.
     *
     * @param live whether a key belongs to a copy that exists now
     * @return how many indexes are deleted
     */
    public int deleteIndexesOfGoneCopies(Predicate<String> live) {
        if (!searchProperties.isEnabled()) {
            return 0;
        }

        String copies = searchProperties.getSearchIndexNamePrefix() + COPIES;
        Pattern indexOfACopy = Pattern.compile(Pattern.quote(copies) + "(\\p{Alnum}{32})_.+");
        int deleted = 0;

        for (String name : listIndexes(copies + "*")) {
            Matcher matcher = indexOfACopy.matcher(name);
            String key = matcher.matches() ? matcher.group(1) : "";

            if (!StandScope.isKey(key) || live.test(key)) {
                continue;
            }

            // a name under the pattern that the stand gives no copy is not an index of the copy
            StandScope gone = StandScope.copy(key);
            List<String> namesOfTheCopy = namesOf(gone);

            if (!namesOfTheCopy.contains(name)) {
                continue;
            }

            try {
                deleteIndexes(List.of(name));
                deleted++;
            } catch (RuntimeException e) {
                log.warn("An index of {} an earlier run left is not deleted: {}", gone.tag(),
                        StandScope.withTags(e.toString()));
            }
        }

        return deleted;
    }

    /**
     * The exact names of the indexes of the scope in OpenSearch, sorted; none with search off.
     */
    public List<String> namesInOpenSearch(StandScope scope) {
        return searchProperties.isEnabled()
                ? namesOf(scope)
                : List.of();
    }

    private void processQueueOf(StandScope scope) {
        int indexed = indexingQueueManager.processNextBatch();
        int enqueued = indexingQueueManager.processNextEnqueueingSession();

        if (indexed + enqueued > 0) {
            log.debug("The search queue of {}: {} items processed, {} enqueued", scope.tag(), indexed, enqueued);
        }
    }

    // a scope that left the loop since the pass began is not processed
    private boolean start(StandScope scope) {
        lock.lock();

        try {
            if (!live.contains(scope)) {
                return false;
            }

            processing = scope;

            return true;
        } finally {
            lock.unlock();
        }
    }

    private void finish() {
        lock.lock();

        try {
            processing = null;
            processed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    // an item OpenSearch refuses stays in the queue, so the queue of a template is processed until it is empty
    private void processTheWholeQueue(StandScope template, long deadline) throws InterruptedException {
        while (inScope(template, this::queueSize) > 0) {
            if (System.nanoTime() - deadline > 0) {
                throw new IllegalStateException("The search queue of " + template.id() + " is not processed in "
                        + TEMPLATE_TIMEOUT.toMinutes() + " minutes");
            }

            if (inScope(template, indexingQueueManager::processEntireQueue) == 0) {
                Thread.sleep(RETRY_PAUSE);
            }
        }
    }

    private long queueSize() {
        return dataManager.loadValue("select count(q) from search_IndexingQueue q", Long.class)
                .one();
    }

    // the names of the indexes that match the pattern, open or closed
    private List<String> listIndexes(String pattern) {
        try {
            return client.indices()
                    .resolveIndex(ResolveIndexRequest.of(request -> request.name(pattern)
                            .expandWildcards(ExpandWildcard.Open, ExpandWildcard.Closed)))
                    .indices()
                    .stream()
                    .map(ResolveIndexItem::name)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // by exact names, never a pattern; a name that is not there is no failure
    private void deleteIndexes(List<String> names) {
        DeleteIndexResponse response;

        try {
            response = client.indices()
                    .delete(DeleteIndexRequest.of(request -> request.index(names)
                            .ignoreUnavailable(true)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        if (!response.acknowledged()) {
            throw new IllegalStateException("OpenSearch did not delete " + names.size() + " indexes");
        }
    }

    private void cloneIndex(String source, String target) {
        CloneIndexResponse response;

        try {
            response = client.indices()
                    .clone(CloneIndexRequest.of(request -> request.index(source)
                            .target(target)
                            .settings(REPLICAS, JsonData.of(0))
                            .settings(WRITE_BLOCK, JsonData.of(false))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // acknowledged shards take reads and writes at once
        if (!response.acknowledged() || !response.shardsAcknowledged()) {
            throw new IllegalStateException("OpenSearch did not clone " + source + " for a copy");
        }
    }

    // the names the add-on and the index of fragments give the indexes of the scope, sorted, so that the indexes of
    // two scopes pair up by their places; a name outside the prefix of the scope would reach the indexes of another
    // scope or of the control database
    private List<String> namesOf(StandScope scope) {
        List<String> names = new ArrayList<>();

        for (IndexConfiguration configuration : indexConfigurationManager.getAllIndexConfigurations()) {
            if (!(configuration instanceof ScopedIndexConfiguration scoped)) {
                throw new IllegalStateException("The index of " + configuration.getEntityName()
                        + " does not follow the scope");
            }

            names.add(scoped.getIndexName(scope));
        }

        names.add(vectorIndex.getIndexName(scope));
        String prefix = scope.indexPrefix(searchProperties.getSearchIndexNamePrefix());

        for (String name : names) {
            if (!name.startsWith(prefix)) {
                throw new IllegalStateException("An index outside the prefix of " + scope.tag());
            }
        }

        return names.stream()
                .sorted()
                .toList();
    }

    // the work runs as the system on the database and the indexes of the scope
    private <T> T inScope(StandScope scope, SystemAuthenticator.AuthenticatedOperation<T> work) {
        try {
            return StandScopeHolder.call(scope, () -> systemAuthenticator.withSystem(work));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
