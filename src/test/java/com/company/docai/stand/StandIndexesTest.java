package com.company.docai.stand;

import com.company.docai.search.semantic.DocumentVectorIndex;
import com.company.docai.search.semantic.DocumentVectorIndexer;
import io.jmix.core.FluentValueLoader;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.search.SearchProperties;
import io.jmix.search.index.IndexConfiguration;
import io.jmix.search.index.mapping.IndexConfigurationManager;
import io.jmix.search.index.queue.IndexingQueueManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.indices.CloneIndexRequest;
import org.opensearch.client.opensearch.indices.CloneIndexResponse;
import org.opensearch.client.opensearch.indices.DeleteIndexRequest;
import org.opensearch.client.opensearch.indices.DeleteIndexResponse;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.opensearch.indices.IndexSettingBlocks;
import org.opensearch.client.opensearch.indices.IndexSettings;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesClient;
import org.opensearch.client.opensearch.indices.PutIndicesSettingsRequest;
import org.opensearch.client.opensearch.indices.PutIndicesSettingsResponse;
import org.opensearch.client.opensearch.indices.RefreshRequest;
import org.opensearch.client.opensearch.indices.ResolveIndexRequest;
import org.opensearch.client.opensearch.indices.ResolveIndexResponse;
import org.opensearch.client.opensearch.indices.resolve_index.ResolveIndexItem;
import org.opensearch.client.transport.endpoints.BooleanResponse;
import org.quartz.JobExecutionContext;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The indexes of the scopes of the stand against a stand-in of OpenSearch and of the queue of the search add-on.
 */
@ExtendWith(OutputCaptureExtension.class)
class StandIndexesTest {

    private static final String PREFIX = "b5w_";
    private static final String KEY = "0123456789abcdef0123456789abcdef";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final StandScope copy = StandScope.copy(KEY);
    private final StandScope another = StandScope.copy("fedcba9876543210fedcba9876543210");
    private final StandScope third = StandScope.copy("00000000000000000000000000000003");
    private final StandScope template = StandScope.template("en");

    private final SearchProperties searchProperties = mock(SearchProperties.class);
    private final IndexConfigurationManager configurations = mock(IndexConfigurationManager.class);
    private final IndexingQueueManager queue = mock(IndexingQueueManager.class);
    private final OpenSearchClient client = mock(OpenSearchClient.class);
    private final OpenSearchIndicesClient indices = mock(OpenSearchIndicesClient.class);
    private final DocumentVectorIndex vectorIndex = mock(DocumentVectorIndex.class);
    private final DocumentVectorIndexer vectorIndexer = mock(DocumentVectorIndexer.class);
    private final UnconstrainedDataManager dataManager = mock(UnconstrainedDataManager.class);
    private final SystemAuthenticator systemAuthenticator = mock(SystemAuthenticator.class);

    private final StandIndexes indexes = new StandIndexes(searchProperties, configurations, queue, client, vectorIndex,
            vectorIndexer, dataManager, systemAuthenticator);

    @BeforeEach
    void setUp() throws IOException {
        when(searchProperties.isEnabled()).thenReturn(true);
        when(searchProperties.getSearchIndexNamePrefix()).thenReturn(PREFIX);

        // the configuration the add-on makes for the documents at the start, named by the prefix and the entity
        when(configurations.getAllIndexConfigurations()).thenReturn(List.of(new ScopedIndexConfiguration(
                new IndexConfiguration("Document", Object.class, PREFIX + "document", null, Set.of(),
                        entity -> true, null),
                PREFIX)));

        when(vectorIndex.getIndexName(any()))
                .thenAnswer(invocation -> invocation.<StandScope>getArgument(0).indexPrefix(PREFIX)
                        + "document_vectors");

        when(client.indices()).thenReturn(indices);

        when(indices.clone(any(CloneIndexRequest.class)))
                .thenAnswer(invocation -> CloneIndexResponse.of(response -> response.acknowledged(true)
                        .shardsAcknowledged(true)
                        .index(invocation.<CloneIndexRequest>getArgument(0).target())));

        when(indices.delete(any(DeleteIndexRequest.class)))
                .thenReturn(DeleteIndexResponse.of(response -> response.acknowledged(true)));

        when(indices.putSettings(any(PutIndicesSettingsRequest.class)))
                .thenReturn(PutIndicesSettingsResponse.of(response -> response.acknowledged(true)));

        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();

            return null;
        }).when(systemAuthenticator).runWithSystem(any());

        when(systemAuthenticator.withSystem(any()))
                .thenAnswer(invocation -> invocation.<SystemAuthenticator.AuthenticatedOperation<?>>getArgument(0)
                        .call());
    }

    @Test
    void theLoopProcessesTheQueueOfEachLiveCopyInItsScope() {
        List<StandScope> batches = new CopyOnWriteArrayList<>();
        List<StandScope> sessions = new CopyOnWriteArrayList<>();

        when(queue.processNextBatch()).thenAnswer(invocation -> {
            batches.add(StandScopeHolder.current());

            return 0;
        });

        when(queue.processNextEnqueueingSession()).thenAnswer(invocation -> {
            sessions.add(StandScopeHolder.current());

            return 0;
        });

        indexes.join(copy);
        indexes.join(another);
        indexes.join(third);
        indexes.leave(another);
        indexes.processQueues();

        assertThat(batches).containsExactlyInAnyOrder(copy, third);
        assertThat(sessions).containsExactlyInAnyOrder(copy, third);
        assertThat(StandScopeHolder.current()).isNull();
    }

    // Quartz runs the job every five seconds, see StandStartupTest
    @Test
    void theJobOfTheSearchQueuesProcessesTheQueueOfALiveCopyInItsScope() {
        List<StandScope> batches = new CopyOnWriteArrayList<>();

        when(queue.processNextBatch()).thenAnswer(invocation -> {
            batches.add(StandScopeHolder.current());

            return 1;
        });

        indexes.join(copy);
        new SearchQueuesJob(indexes).execute(mock(JobExecutionContext.class));

        assertThat(batches).containsExactly(copy);
        verify(queue).processNextEnqueueingSession();
        verify(systemAuthenticator).runWithSystem(any());
    }

    // a copy deletes its indexes right after it leaves, and the batch in flight writes them until it is done
    @Test
    void aCopyLeavesTheLoopOnlyOnceItsBatchInFlightIsDone() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        List<String> events = new CopyOnWriteArrayList<>();

        when(queue.processNextBatch()).thenAnswer(invocation -> {
            started.countDown();
            gate.await();
            events.add("processed");

            return 1;
        });

        indexes.join(copy);
        CompletableFuture<Void> loop = CompletableFuture.runAsync(indexes::processQueues);
        assertThat(started.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> leaving = CompletableFuture.runAsync(() -> {
            indexes.leave(copy);
            events.add("left");
        });

        await().during(Duration.ofMillis(300))
                .atMost(TIMEOUT)
                .until(() -> !leaving.isDone());

        gate.countDown();
        leaving.join();
        loop.join();
        clearInvocations(queue);
        indexes.processQueues();

        assertThat(events).containsExactly("processed", "left");
        verify(queue, never()).processNextBatch();
    }

    @Test
    void aCopyGetsClonesOfTheIndexesOfItsTemplateOpenForWrites() throws IOException {
        ArgumentCaptor<CloneIndexRequest> clones = ArgumentCaptor.forClass(CloneIndexRequest.class);

        indexes.cloneTemplate("en", copy);

        verify(indices, times(2)).clone(clones.capture());
        assertThat(clones.getAllValues()).extracting(CloneIndexRequest::index, CloneIndexRequest::target)
                .containsExactly(tuple("b5w_tpl_en_document", "b5w_copy_" + KEY + "_document"),
                        tuple("b5w_tpl_en_document_vectors", "b5w_copy_" + KEY + "_document_vectors"));
        assertThat(clones.getAllValues()).allSatisfy(clone -> {
            assertThat(clone.settings().get("index.number_of_replicas").to(Integer.class)).isZero();
            assertThat(clone.settings().get("index.blocks.write").to(Boolean.class)).isFalse();
        });
    }

    // a copy without one of its indexes would write it anew, with a mapping OpenSearch guesses
    @Test
    void aFailedCloneLeavesTheCopyNoIndex() throws IOException {
        ArgumentCaptor<DeleteIndexRequest> deletes = ArgumentCaptor.forClass(DeleteIndexRequest.class);

        when(indices.clone(any(CloneIndexRequest.class)))
                .thenReturn(CloneIndexResponse.of(response -> response.acknowledged(true)
                        .shardsAcknowledged(true)
                        .index("b5w_copy_" + KEY + "_document")))
                .thenThrow(new IOException("OpenSearch does not answer"));

        assertThatThrownBy(() -> indexes.cloneTemplate("en", copy))
                .hasRootCauseInstanceOf(IOException.class);

        verify(indices).delete(deletes.capture());
        assertThat(deletes.getValue().index())
                .containsExactly("b5w_copy_" + KEY + "_document", "b5w_copy_" + KEY + "_document_vectors");
        assertThat(deletes.getValue().ignoreUnavailable()).isTrue();
    }

    @Test
    void aScopeLosesItsIndexesByTheirExactNames() throws IOException {
        ArgumentCaptor<DeleteIndexRequest> deletes = ArgumentCaptor.forClass(DeleteIndexRequest.class);

        indexes.delete(copy);

        verify(indices).delete(deletes.capture());
        assertThat(deletes.getValue().index())
                .containsExactly("b5w_copy_" + KEY + "_document", "b5w_copy_" + KEY + "_document_vectors")
                .noneMatch(name -> name.contains("*") || name.contains(","));
        assertThat(deletes.getValue().ignoreUnavailable()).isTrue();
    }

    @Test
    void aScopeHasItsIndexesWhenOpenSearchHasEachByItsExactName() throws IOException {
        ArgumentCaptor<ExistsRequest> asked = ArgumentCaptor.forClass(ExistsRequest.class);

        when(indices.exists(any(ExistsRequest.class)))
                .thenReturn(new BooleanResponse(true));

        assertThat(indexes.exist(template)).isTrue();

        verify(indices, times(2)).exists(asked.capture());
        assertThat(asked.getAllValues()).extracting(ExistsRequest::index)
                .containsExactly(List.of("b5w_tpl_en_document"), List.of("b5w_tpl_en_document_vectors"));
    }

    @Test
    void aScopeWithoutOneOfItsIndexesHasNotGotThem() throws IOException {
        when(indices.exists(any(ExistsRequest.class)))
                .thenReturn(new BooleanResponse(true))
                .thenReturn(new BooleanResponse(false));

        assertThat(indexes.exist(template)).isFalse();
    }

    // only the names the stand gives a copy go, never one of another prefix or of another shape
    @Test
    void theIndexesOfGoneCopiesGoOneByOneByTheirExactNames() throws IOException {
        String gone = "b5w_copy_" + KEY;
        String live = "b5w_copy_" + another.key();
        ArgumentCaptor<ResolveIndexRequest> listed = ArgumentCaptor.forClass(ResolveIndexRequest.class);
        ArgumentCaptor<DeleteIndexRequest> deletes = ArgumentCaptor.forClass(DeleteIndexRequest.class);

        when(indices.resolveIndex(any(ResolveIndexRequest.class))).thenReturn(listing(
                gone + "_document",
                gone + "_document_vectors",
                live + "_document",
                gone + "_document_backup",
                "b5w_copy_" + KEY.toUpperCase() + "_document",
                "b5w_copy_notakey_document"));

        int deleted = indexes.deleteIndexesOfGoneCopies(key -> key.equals(another.key()));

        verify(indices).resolveIndex(listed.capture());
        verify(indices, times(2)).delete(deletes.capture());
        assertThat(listed.getValue().name()).containsExactly("b5w_copy_*");
        assertThat(deletes.getAllValues()).extracting(DeleteIndexRequest::index)
                .containsExactly(List.of(gone + "_document"), List.of(gone + "_document_vectors"));
        assertThat(deleted).isEqualTo(2);
    }

    // one index OpenSearch keeps does not keep the others, and the next start tries it again
    @Test
    void aGoneIndexThatIsNotDeletedLeavesTheOthersToGo() throws IOException {
        String gone = "b5w_copy_" + KEY;

        when(indices.resolveIndex(any(ResolveIndexRequest.class)))
                .thenReturn(listing(gone + "_document", gone + "_document_vectors"));

        when(indices.delete(any(DeleteIndexRequest.class)))
                .thenThrow(new IOException("OpenSearch does not answer"))
                .thenReturn(DeleteIndexResponse.of(response -> response.acknowledged(true)));

        assertThat(indexes.deleteIndexesOfGoneCopies(key -> false)).isOne();

        verify(indices, times(2)).delete(any(DeleteIndexRequest.class));
    }

    // an index the add-on names apart from the scope is the one of the control database, which no copy deletes
    @Test
    void anIndexOutsideTheScopeIsNeverDeleted() {
        when(configurations.getAllIndexConfigurations()).thenReturn(List.of(new IndexConfiguration("Document",
                Object.class, PREFIX + "document", null, Set.of(), entity -> true, null)));

        assertThatThrownBy(() -> indexes.delete(copy))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Document");

        verifyNoInteractions(indices);
    }

    // the removed documents of the set reach the vector indexer at once, which removes them from the index of
    // fragments, as with the fake key of a walk, where no document gets fragments
    @Test
    void aRenewedTemplateHasAnEmptyIndexOfFragmentsBeforeItsSetLoads() throws IOException {
        List<StandScope> prepared = new CopyOnWriteArrayList<>();
        ArgumentCaptor<DeleteIndexRequest> deletes = ArgumentCaptor.forClass(DeleteIndexRequest.class);

        when(vectorIndex.prepare()).thenAnswer(invocation -> {
            prepared.add(StandScopeHolder.current());

            return true;
        });

        indexes.renew(template);

        InOrder order = inOrder(indices, vectorIndex);
        order.verify(indices).delete(deletes.capture());
        order.verify(vectorIndex).prepare();
        assertThat(deletes.getValue().index()).containsExactly("b5w_tpl_en_document", "b5w_tpl_en_document_vectors");
        assertThat(prepared).containsExactly(template);
    }

    @Test
    void aSealedTemplateIsRefreshedThenReadOnlyWithoutReplicas() throws IOException {
        ArgumentCaptor<RefreshRequest> refresh = ArgumentCaptor.forClass(RefreshRequest.class);
        ArgumentCaptor<PutIndicesSettingsRequest> settings = ArgumentCaptor.forClass(PutIndicesSettingsRequest.class);

        indexes.seal(template);

        InOrder order = inOrder(indices);
        order.verify(indices).refresh(refresh.capture());
        order.verify(indices).putSettings(settings.capture());
        assertThat(refresh.getValue().index()).containsExactly("b5w_tpl_en_document", "b5w_tpl_en_document_vectors");
        IndexSettings index = settings.getValue().settings().index();
        assertThat(settings.getValue().index()).containsExactly("b5w_tpl_en_document", "b5w_tpl_en_document_vectors");
        assertThat(index).isNotNull();
        IndexSettingBlocks blocks = index.blocks();
        assertThat(blocks).isNotNull();
        assertThat(blocks.write()).isTrue();
        assertThat(index.numberOfReplicas()).isZero();
    }

    @Test
    void aTemplateIsCompleteOnceItsQueueIsProcessedAndItsVectorsAreDone() throws Exception {
        List<StandScope> counted = new CopyOnWriteArrayList<>();
        List<StandScope> processed = new CopyOnWriteArrayList<>();
        AtomicLong waiting = new AtomicLong(51);
        FluentValueLoader<Long> queueSize = mock();

        when(dataManager.loadValue(anyString(), eq(Long.class))).thenReturn(queueSize);

        when(queueSize.one()).thenAnswer(invocation -> {
            counted.add(StandScopeHolder.current());

            return waiting.get();
        });

        when(queue.processEntireQueue()).thenAnswer(invocation -> {
            processed.add(StandScopeHolder.current());

            return (int) waiting.getAndSet(0);
        });

        when(vectorIndexer.awaitIdle(eq(template), any())).thenReturn(true);

        indexes.complete(template);

        assertThat(processed).containsExactly(template);
        assertThat(counted).containsOnly(template);
        verify(vectorIndexer).awaitIdle(eq(template), any());
    }

    // a vector step that never finishes, as with a model that does not answer: the model is optional, so the
    // template keeps the vectors it has, and the copies find less by meaning instead of the stand not opening
    @Test
    void aTemplateWhoseVectorsAreNotDoneInTimeKeepsTheVectorsItHas(CapturedOutput output) throws Exception {
        FluentValueLoader<Long> queueSize = mock();

        when(dataManager.loadValue(anyString(), eq(Long.class))).thenReturn(queueSize);
        when(queueSize.one()).thenReturn(0L);
        when(vectorIndexer.awaitIdle(eq(template), any())).thenReturn(false);
        when(vectorIndexer.waiting(template)).thenReturn(3);

        indexes.complete(template);

        assertThat(output.getAll()
                .lines()
                .filter(line -> line.contains("WARN") && line.contains(StandIndexes.class.getSimpleName())))
                .singleElement()
                .asString()
                .endsWith("The vectors of tpl_en are not done in 10 minutes: 3 documents wait; the template keeps "
                        + "the vectors it has");
    }

    // OpenSearch is not optional: a template whose search queue fails is not complete
    @Test
    void aTemplateWhoseSearchQueueFailsIsNotComplete() throws Exception {
        FluentValueLoader<Long> queueSize = mock();

        when(dataManager.loadValue(anyString(), eq(Long.class))).thenReturn(queueSize);
        when(queueSize.one()).thenReturn(5L);
        when(queue.processEntireQueue()).thenThrow(new IllegalStateException("OpenSearch does not answer"));
        when(vectorIndexer.awaitIdle(eq(template), any())).thenReturn(true);

        assertThatThrownBy(() -> indexes.complete(template))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OpenSearch does not answer");
    }

    @Test
    void withSearchOffNothingReachesOpenSearchOrTheQueues() throws Exception {
        when(searchProperties.isEnabled()).thenReturn(false);
        when(vectorIndexer.awaitIdle(eq(template), any())).thenReturn(true);

        indexes.join(copy);
        indexes.processQueues();
        indexes.cloneTemplate("en", copy);
        indexes.delete(copy);
        indexes.renew(template);
        indexes.complete(template);
        indexes.seal(template);

        // with nothing to ask for, a template has its indexes, and no copy has any to delete
        assertThat(indexes.exist(template)).isTrue();
        assertThat(indexes.deleteIndexesOfGoneCopies(key -> false)).isZero();
        verifyNoInteractions(client, indices, queue, dataManager, vectorIndex);
    }

    private static ResolveIndexResponse listing(String... names) {
        List<ResolveIndexItem> items = Stream.of(names)
                .map(name -> ResolveIndexItem.of(item -> item.name(name)
                        .attributes("open")))
                .toList();

        return ResolveIndexResponse.of(response -> response.indices(items)
                .aliases(List.of())
                .dataStreams(List.of()));
    }
}
