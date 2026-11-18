package com.company.docai.search.semantic;

import com.company.docai.ai.AiSettings;
import com.company.docai.stand.StandScope;
import com.company.docai.stand.StandScopeHolder;
import io.jmix.core.security.SystemAuthenticator;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The queue of the indexer with the scopes of the stand.
 */
class DocumentVectorIndexerScopeTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final StandScope copy = StandScope.copy("0123456789abcdef0123456789abcdef");
    private final StandScope another = StandScope.copy("fedcba9876543210fedcba9876543210");
    private final StandScope template = StandScope.template("en");

    private final DocumentVectorIndex index = mock(DocumentVectorIndex.class);
    private final MockEnvironment environment = new MockEnvironment();
    private final RecordingIndexer indexer = new RecordingIndexer(index, environment);

    private record Indexed(@Nullable StandScope scope, UUID documentId) {
    }

    @AfterEach
    void stopTheQueue() throws InterruptedException {
        indexer.open();
        indexer.shutdown();
    }

    @Test
    void aDocumentIsIndexedInTheScopeItWasQueuedIn() {
        UUID ofTheCopy = UUID.randomUUID();
        UUID ofTheTemplate = UUID.randomUUID();
        UUID ofTheStart = UUID.randomUUID();
        indexer.open();

        StandScopeHolder.run(copy, () -> indexer.enqueue(ofTheCopy));
        StandScopeHolder.run(template, () -> indexer.enqueue(ofTheTemplate));
        indexer.enqueue(ofTheStart);

        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(indexer.indexed).containsExactlyInAnyOrder(
                        new Indexed(copy, ofTheCopy), new Indexed(template, ofTheTemplate),
                        new Indexed(null, ofTheStart)));
    }

    // the copies of a template keep the ids of its documents, so a document of one copy is not the one of another
    @Test
    void aDocumentWaitsOnceInEachScope() throws InterruptedException {
        UUID document = UUID.randomUUID();
        holdTheQueue();

        StandScopeHolder.run(copy, () -> indexer.enqueue(document));
        StandScopeHolder.run(copy, () -> indexer.enqueue(document));
        StandScopeHolder.run(another, () -> indexer.enqueue(document));
        indexer.open();

        assertThat(indexer.awaitIdle(copy, TIMEOUT)).isTrue();
        assertThat(indexer.awaitIdle(another, TIMEOUT)).isTrue();
        assertThat(indexer.indexed).filteredOn(indexed -> indexed.documentId().equals(document))
                .containsExactlyInAnyOrder(new Indexed(copy, document), new Indexed(another, document));
    }

    @Test
    void aForgottenScopeLosesItsWaitingDocuments() throws InterruptedException {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID ofAnother = UUID.randomUUID();
        holdTheQueue();

        StandScopeHolder.run(copy, () -> indexer.enqueue(first));
        StandScopeHolder.run(copy, () -> indexer.enqueue(second));
        StandScopeHolder.run(another, () -> indexer.enqueue(ofAnother));
        indexer.forget(copy);
        indexer.open();

        assertThat(indexer.awaitIdle(copy, TIMEOUT)).isTrue();
        assertThat(indexer.awaitIdle(another, TIMEOUT)).isTrue();
        assertThat(indexer.indexed).extracting(Indexed::scope)
                .containsOnly(template, another);
    }

    // a copy deletes its indexes right after it is forgotten, and the document in flight writes them until it is done
    @Test
    void aScopeIsForgottenOnlyOnceItsDocumentInFlightIsDone() throws InterruptedException {
        UUID document = UUID.randomUUID();
        List<String> events = new CopyOnWriteArrayList<>();
        indexer.events = events;

        StandScopeHolder.run(copy, () -> indexer.enqueue(document));
        assertThat(indexer.started.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> forgetting = CompletableFuture.runAsync(() -> {
            indexer.forget(copy);
            events.add("forgotten");
        });

        await().during(Duration.ofMillis(300))
                .atMost(TIMEOUT)
                .until(() -> !forgetting.isDone());

        indexer.open();
        forgetting.join();

        assertThat(events).containsExactly("indexed", "forgotten");
    }

    @Test
    void aScopeIsBusyOnlyWithItsOwnDocuments() throws Exception {
        UUID document = UUID.randomUUID();

        StandScopeHolder.run(copy, () -> indexer.enqueue(document));

        assertThat(StandScopeHolder.call(copy, indexer::isBusy)).isTrue();
        assertThat(StandScopeHolder.call(another, indexer::isBusy)).isFalse();
        assertThat(indexer.isBusy()).isFalse();
        assertThat(indexer.awaitIdle(copy, Duration.ofMillis(100))).isFalse();

        indexer.open();

        assertThat(indexer.awaitIdle(copy, TIMEOUT)).isTrue();
        assertThat(StandScopeHolder.call(copy, indexer::isBusy)).isFalse();
    }

    // a step that never finishes, as with a model that does not answer, holds the queue: the wait of a template for
    // its vectors runs out, and the documents of each scope that wait or are indexed are counted
    @Test
    void aStepThatNeverFinishesKeepsItsScopeWaiting() throws InterruptedException {
        StandScopeHolder.run(template, () -> indexer.enqueue(UUID.randomUUID()));
        assertThat(indexer.started.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();

        StandScopeHolder.run(template, () -> indexer.enqueue(UUID.randomUUID()));
        StandScopeHolder.run(copy, () -> indexer.enqueue(UUID.randomUUID()));

        assertThat(indexer.awaitIdle(template, Duration.ofMillis(200))).isFalse();
        assertThat(indexer.waiting(template)).isEqualTo(2);
        assertThat(indexer.waiting(copy)).isEqualTo(1);
        assertThat(indexer.waiting(another)).isZero();
    }

    // the control database of the stand has no documents, and each template indexes its own
    @Test
    void theStartOfTheStandChecksNoIndex() {
        environment.setActiveProfiles("stand");

        indexer.onApplicationReady();

        assertThat(indexer.isBusy()).isFalse();
        verifyNoInteractions(index);
    }

    @Test
    void theStartWithoutTheStandChecksTheIndex() {
        indexer.onApplicationReady();

        verify(index, timeout(TIMEOUT.toMillis())).prepare();
    }

    // a task of another scope holds the queue, so the documents queued next wait behind it
    private void holdTheQueue() throws InterruptedException {
        StandScopeHolder.run(template, () -> indexer.enqueue(UUID.randomUUID()));
        assertThat(indexer.started.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
    }

    // notes the scope each document is indexed in; the first document waits at the gate until the test opens it
    private static final class RecordingIndexer extends DocumentVectorIndexer {

        private final List<Indexed> indexed = new CopyOnWriteArrayList<>();
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch gate = new CountDownLatch(1);
        private volatile List<String> events = new CopyOnWriteArrayList<>();

        private RecordingIndexer(DocumentVectorIndex index, MockEnvironment environment) {
            super(index, null, null, null, systemAuthenticator(), new AiSettings("key", true, 12_000), 40_000,
                    environment);
        }

        @Override
        public void index(UUID documentId) {
            started.countDown();

            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            indexed.add(new Indexed(StandScopeHolder.current(), documentId));
            events.add("indexed");
        }

        private void open() {
            gate.countDown();
        }

        private static SystemAuthenticator systemAuthenticator() {
            SystemAuthenticator authenticator = mock(SystemAuthenticator.class);

            doAnswer(invocation -> {
                invocation.<Runnable>getArgument(0).run();

                return null;
            }).when(authenticator).runWithSystem(any());

            doAnswer(invocation -> invocation.<SystemAuthenticator.AuthenticatedOperation<?>>getArgument(0).call())
                    .when(authenticator)
                    .withSystem(any());

            return authenticator;
        }
    }
}
