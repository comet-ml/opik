package com.comet.opik.domain;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetVersion;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.sorting.SortingFactoryDatasets;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.infrastructure.BatchOperationsConfig;
import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.common.eventbus.EventBus;
import jakarta.inject.Provider;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.ContextView;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxAction;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DatasetService enrichment Test")
class DatasetServiceEnrichmentTest {

    @Mock
    private IdGenerator idGenerator;
    @Mock
    private TransactionTemplate template;
    @Mock
    private Provider<RequestContext> requestContextProvider;
    @Mock
    private RequestContext requestContext;
    @Mock
    private ExperimentItemDAO experimentItemDAO;
    @Mock
    private DatasetItemDAO datasetItemDAO;
    @Mock
    private ExperimentDAO experimentDAO;
    @Mock
    private SortingQueryBuilder sortingQueryBuilder;
    @Mock
    private FilterQueryBuilder filterQueryBuilder;
    @Mock
    private SortingFactoryDatasets sortingFactory;
    @Mock
    private BatchOperationsConfig batchOperationsConfig;
    @Mock
    private OptimizationDAO optimizationDAO;
    @Mock
    private EventBus eventBus;
    @Mock
    private FeatureFlags featureFlags;
    @Mock
    private ProjectService projectService;
    @Mock
    private DatasetVersionDAO datasetVersionDAO;
    @Mock
    private DatasetDAO datasetDAO;

    private static final long SUBSCRIBE_TIMEOUT_SECONDS = 5;
    // Generous relative to SUBSCRIBE_TIMEOUT_SECONDS so a slow-but-correct run fails the assertion
    // rather than tripping the workers' own deadline under CI scheduler load.
    private static final long WORKER_RELEASE_TIMEOUT_SECONDS = 60;

    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER_NAME = "test-user";

    private DatasetService service;
    private Handle handle;

    @BeforeEach
    void setUp() {
        when(requestContextProvider.get()).thenReturn(requestContext);
        when(requestContext.getWorkspaceId()).thenReturn(WORKSPACE_ID);
        when(requestContext.getUserName()).thenReturn(USER_NAME);
        when(requestContext.getVisibility()).thenReturn(Visibility.PRIVATE);

        handle = mock(Handle.class);
        when(handle.attach(DatasetDAO.class)).thenReturn(datasetDAO);
        when(handle.attach(DatasetVersionDAO.class)).thenReturn(datasetVersionDAO);
        when(template.inTransaction(any(), any())).thenAnswer(invocation -> {
            TxAction<?> callback = invocation.getArgument(1);
            return callback.execute(handle);
        });

        service = new DatasetServiceImpl(idGenerator, template, requestContextProvider, experimentItemDAO,
                datasetItemDAO, experimentDAO, sortingQueryBuilder, filterQueryBuilder, sortingFactory,
                batchOperationsConfig, optimizationDAO, eventBus, featureFlags, projectService);
    }

    private static final class VersionLookupShortCircuit extends RuntimeException {
    }

    private Dataset stubDataset(UUID id) {
        var dataset = Dataset.builder().id(id).name("dataset-" + id).build();
        when(datasetDAO.findById(id, WORKSPACE_ID)).thenReturn(java.util.Optional.of(dataset));
        return dataset;
    }

    @Test
    @DisplayName("A duplicate dataset_id from a lookup fails loudly instead of silently dropping a row")
    void enrichmentWhenLookupReturnsDuplicateKeyFailsLoudly() {
        var datasetId = UUID.randomUUID();
        stubDataset(datasetId);

        // The queries GROUP BY dataset_id so this should not happen; if a query change ever broke that,
        // last-wins collection would quietly return one row's summary instead of surfacing the problem.
        when(experimentItemDAO.findExperimentSummaryByDatasetIds(anySet()))
                .thenReturn(Flux.just(new ExperimentItemDAO.ExperimentSummary(datasetId, 1, null),
                        new ExperimentItemDAO.ExperimentSummary(datasetId, 99, null)));
        when(datasetItemDAO.findDatasetItemSummaryByDatasetIds(anySet())).thenReturn(Flux.empty());
        when(optimizationDAO.findOptimizationSummaryByDatasetIds(anySet())).thenReturn(Flux.empty());
        when(datasetVersionDAO.findLatestVersionsByDatasetIds(anySet(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.findById(datasetId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate key");
    }

    @Test
    @DisplayName("Enrichment combines all four summaries when every lookup returns data")
    void enrichmentWhenAllSummariesPresentReturnsCombinedDataset() {
        var datasetId = UUID.randomUUID();
        stubDataset(datasetId);

        var experimentAt = Instant.now().minusSeconds(60);
        var optimizationAt = Instant.now().minusSeconds(30);

        when(experimentItemDAO.findExperimentSummaryByDatasetIds(anySet()))
                .thenReturn(Flux.just(new ExperimentItemDAO.ExperimentSummary(datasetId, 3, experimentAt)));
        when(datasetItemDAO.findDatasetItemSummaryByDatasetIds(anySet()))
                .thenReturn(Flux.just(new DatasetItemSummary(datasetId, 7)));
        when(optimizationDAO.findOptimizationSummaryByDatasetIds(anySet()))
                .thenReturn(Flux.just(new OptimizationDAO.OptimizationSummary(datasetId, 2, optimizationAt)));
        when(datasetVersionDAO.findLatestVersionsByDatasetIds(anySet(), any())).thenReturn(List.of());

        var actual = service.findById(datasetId);

        assertThat(actual.experimentCount()).isEqualTo(3);
        assertThat(actual.mostRecentExperimentAt()).isEqualTo(experimentAt);
        assertThat(actual.datasetItemsCount()).isEqualTo(7);
        assertThat(actual.optimizationCount()).isEqualTo(2);
        assertThat(actual.mostRecentOptimizationAt()).isEqualTo(optimizationAt);
    }

    @Test
    @DisplayName("Enrichment falls back to empty summaries when no lookup returns data")
    void enrichmentWhenNoSummariesPresentReturnsEmptyDefaults() {
        var datasetId = UUID.randomUUID();
        stubDataset(datasetId);

        when(experimentItemDAO.findExperimentSummaryByDatasetIds(anySet())).thenReturn(Flux.empty());
        when(datasetItemDAO.findDatasetItemSummaryByDatasetIds(anySet())).thenReturn(Flux.empty());
        when(optimizationDAO.findOptimizationSummaryByDatasetIds(anySet())).thenReturn(Flux.empty());
        when(datasetVersionDAO.findLatestVersionsByDatasetIds(anySet(), any())).thenReturn(List.of());

        var actual = service.findById(datasetId);

        assertThat(actual.experimentCount()).isZero();
        assertThat(actual.mostRecentExperimentAt()).isNull();
        assertThat(actual.datasetItemsCount()).isZero();
        assertThat(actual.optimizationCount()).isZero();
        assertThat(actual.mostRecentOptimizationAt()).isNull();
        assertThat(actual.latestVersion()).isNull();
    }

    @Test
    @DisplayName("Enrichment applies per-dataset defaults when only some datasets have summaries")
    void enrichmentWhenMixedPageAppliesDefaultsPerDataset() {
        var withSummaries = UUID.randomUUID();
        var withoutSummaries = UUID.randomUUID();
        stubDataset(withoutSummaries);

        // The summary maps carry an entry for another dataset only: the requested one must still default to empty
        // rather than picking up the other dataset's values.
        when(experimentItemDAO.findExperimentSummaryByDatasetIds(anySet()))
                .thenReturn(Flux.just(new ExperimentItemDAO.ExperimentSummary(withSummaries, 5, Instant.now())));
        when(datasetItemDAO.findDatasetItemSummaryByDatasetIds(anySet()))
                .thenReturn(Flux.just(new DatasetItemSummary(withSummaries, 9)));
        when(optimizationDAO.findOptimizationSummaryByDatasetIds(anySet()))
                .thenReturn(Flux.just(new OptimizationDAO.OptimizationSummary(withSummaries, 4, Instant.now())));
        when(datasetVersionDAO.findLatestVersionsByDatasetIds(anySet(), any())).thenReturn(List.of());

        var actual = service.findById(withoutSummaries);

        assertThat(actual.experimentCount()).isZero();
        assertThat(actual.datasetItemsCount()).isZero();
        assertThat(actual.optimizationCount()).isZero();
    }

    @Test
    @DisplayName("A failure in one lookup propagates instead of yielding an empty summary")
    void enrichmentWhenOneLookupFailsPropagatesError() {
        var datasetId = UUID.randomUUID();
        stubDataset(datasetId);

        when(experimentItemDAO.findExperimentSummaryByDatasetIds(anySet()))
                .thenReturn(Flux.error(new IllegalStateException("clickhouse unavailable")));
        when(datasetItemDAO.findDatasetItemSummaryByDatasetIds(anySet()))
                .thenReturn(Flux.just(new DatasetItemSummary(datasetId, 7)));
        when(optimizationDAO.findOptimizationSummaryByDatasetIds(anySet())).thenReturn(Flux.empty());
        when(datasetVersionDAO.findLatestVersionsByDatasetIds(anySet(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.findById(datasetId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("clickhouse unavailable");
    }

    @Test
    @DisplayName("Request context is visible to every concurrent lookup subscription")
    void enrichmentPropagatesRequestContextToEveryLookup() {
        var datasetId = UUID.randomUUID();
        stubDataset(datasetId);

        var observedWorkspaces = ConcurrentHashMap.<String>newKeySet();

        Function<ContextView, Void> record = ctx -> {
            observedWorkspaces.add(ctx.get(RequestContext.WORKSPACE_ID));
            return null;
        };

        when(experimentItemDAO.findExperimentSummaryByDatasetIds(anySet()))
                .thenReturn(Flux.deferContextual(ctx -> {
                    record.apply(ctx);
                    return Flux.just(new ExperimentItemDAO.ExperimentSummary(datasetId, 1, Instant.now()));
                }));
        when(datasetItemDAO.findDatasetItemSummaryByDatasetIds(anySet()))
                .thenReturn(Flux.deferContextual(ctx -> {
                    record.apply(ctx);
                    return Flux.just(new DatasetItemSummary(datasetId, 1));
                }));
        when(optimizationDAO.findOptimizationSummaryByDatasetIds(anySet()))
                .thenReturn(Flux.deferContextual(ctx -> {
                    record.apply(ctx);
                    return Flux.just(new OptimizationDAO.OptimizationSummary(datasetId, 1, Instant.now()));
                }));
        when(datasetVersionDAO.findLatestVersionsByDatasetIds(anySet(), any())).thenReturn(List.of());

        service.findById(datasetId);

        assertThat(observedWorkspaces).containsExactly(WORKSPACE_ID);
    }

    @Test
    @DisplayName("The version-independent lookups are in flight simultaneously, not one after another")
    void enrichmentIssuesLookupsConcurrently() throws Exception {
        var datasetId = UUID.randomUUID();
        stubDataset(datasetId);

        // Scope note: the mocks below add .subscribeOn(boundedElastic()), which production does not. The real
        // ClickHouse sources are async at subscribe (ClickHouseConnection forces ASYNC=true), so Mono.zip's
        // sequential subscription does not serialize them. This test therefore fences off a structural
        // regression -- reverting to collect-each-result-eagerly -- but NOT a DAO becoming blocking at
        // subscribe, which would silently degrade the zip back to serial while this test still passed.
        //
        // Only the two version-independent lookups are gated. The dataset_items count is deliberately
        // chained after the version lookup rather than zipped alongside it (OPIK-8175): which datasets need
        // it is only known once the latest versions are in hand, and for a fully-versioned page it is not
        // issued at all. enrichmentDefersItemCountUntilVersionsAreKnown covers that ordering.
        //
        // Each gated lookup counts itself in, then blocks until both have arrived. A serial implementation
        // parks on the first lookup and never reaches the second, so SUBSCRIBE_TIMEOUT expires and the
        // assertion below fails. The worker deadline is deliberately much longer than the assertion
        // deadline: the two run concurrently, so a shared budget would let a slow-but-correct run exhaust
        // the workers' wait and throw from a worker thread instead of failing the assertion cleanly.
        var allSubscribed = new CountDownLatch(2);
        var released = new CountDownLatch(1);

        Runnable gate = () -> {
            allSubscribed.countDown();
            try {
                if (!released.await(WORKER_RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("lookups were never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        };

        when(experimentItemDAO.findExperimentSummaryByDatasetIds(anySet()))
                .thenReturn(Flux.defer(() -> {
                    gate.run();
                    return Flux.just(new ExperimentItemDAO.ExperimentSummary(datasetId, 1, Instant.now()));
                }).subscribeOn(Schedulers.boundedElastic()));
        when(datasetItemDAO.findDatasetItemSummaryByDatasetIds(anySet()))
                .thenReturn(Flux.just(new DatasetItemSummary(datasetId, 1)));
        when(optimizationDAO.findOptimizationSummaryByDatasetIds(anySet()))
                .thenReturn(Flux.defer(() -> {
                    gate.run();
                    return Flux.just(new OptimizationDAO.OptimizationSummary(datasetId, 1, Instant.now()));
                }).subscribeOn(Schedulers.boundedElastic()));
        when(datasetVersionDAO.findLatestVersionsByDatasetIds(anySet(), any())).thenReturn(List.of());

        var enrichment = CompletableFuture.supplyAsync(() -> service.findById(datasetId));

        try {
            assertThat(allSubscribed.await(SUBSCRIBE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("both version-independent lookups should be subscribed before either completes")
                    .isTrue();
        } finally {
            // Always release, so a failed assertion reports the real cause instead of being masked by
            // workers timing out and throwing.
            released.countDown();
        }

        var actual = enrichment.get(WORKER_RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(actual.experimentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("An empty version lookup yields empty versions rather than failing the whole enrichment")
    void enrichmentWhenVersionLookupEmitsNothingStillReturnsDataset() {
        var datasetId = UUID.randomUUID();
        stubDataset(datasetId);

        when(experimentItemDAO.findExperimentSummaryByDatasetIds(anySet()))
                .thenReturn(Flux.just(new ExperimentItemDAO.ExperimentSummary(datasetId, 3, null)));
        when(datasetItemDAO.findDatasetItemSummaryByDatasetIds(anySet()))
                .thenReturn(Flux.just(new DatasetItemSummary(datasetId, 7)));
        when(optimizationDAO.findOptimizationSummaryByDatasetIds(anySet())).thenReturn(Flux.empty());

        // Drive the version lookup to produce no value at all. The handle is prepared outside the answer
        // (nested stubbing would corrupt the outer stub), and only the DatasetVersionDAO attach is
        // short-circuited, so the dataset lookup itself is untouched. Mono.zip drops a source that
        // completes empty and then emits nothing itself, so without defaultIfEmpty the block() in
        // enrichment returns null and NPEs instead of reporting a dataset with no version.
        var versionLookupHandle = mock(Handle.class);
        when(versionLookupHandle.attach(DatasetDAO.class)).thenReturn(datasetDAO);
        when(versionLookupHandle.attach(DatasetVersionDAO.class)).thenThrow(new VersionLookupShortCircuit());

        doAnswer(invocation -> {
            TxAction<?> callback = invocation.getArgument(1);
            try {
                return callback.execute(versionLookupHandle);
            } catch (VersionLookupShortCircuit e) {
                return null;
            }
        }).when(template).inTransaction(any(), any());

        var actual = service.findById(datasetId);

        assertThat(actual.latestVersion()).isNull();
        assertThat(actual.experimentCount()).isEqualTo(3);
        assertThat(actual.datasetItemsCount()).isEqualTo(7);
        assertThat(actual.optimizationCount()).isZero();
    }

    @Test
    @DisplayName("The dataset version lookup is bound to the request workspace off the request thread")
    void enrichmentPassesWorkspaceIdToVersionLookup() {
        var datasetId = UUID.randomUUID();
        stubDataset(datasetId);

        when(experimentItemDAO.findExperimentSummaryByDatasetIds(anySet())).thenReturn(Flux.empty());
        when(datasetItemDAO.findDatasetItemSummaryByDatasetIds(anySet())).thenReturn(Flux.empty());
        when(optimizationDAO.findOptimizationSummaryByDatasetIds(anySet())).thenReturn(Flux.empty());

        var version = DatasetVersion.builder()
                .id(UUID.randomUUID())
                .datasetId(datasetId)
                .itemsTotal(42)
                .build();
        when(datasetVersionDAO.findLatestVersionsByDatasetIds(Set.of(datasetId), WORKSPACE_ID))
                .thenReturn(List.of(version));
        when(featureFlags.isDatasetVersioningEnabled()).thenReturn(true);

        var actual = service.findById(datasetId);

        assertThat(actual.datasetItemsCount()).isEqualTo(42);
        assertThat(actual.latestVersion()).isNotNull();
    }

    @Test
    @DisplayName("A mixed page keeps dataset order and maps each row to its own summaries")
    void enrichmentOnPageKeepsOrderAndPerRowSummaries() {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        var third = UUID.randomUUID();

        var page = List.of(
                Dataset.builder().id(first).name("first").build(),
                Dataset.builder().id(second).name("second").build(),
                Dataset.builder().id(third).name("third").build());

        when(sortingQueryBuilder.toOrderBySql(any())).thenReturn(null);
        when(sortingFactory.getSortableFields()).thenReturn(List.of());
        when(datasetDAO.findCount(anyString(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any()))
                .thenReturn(3L);
        when(datasetDAO.find(anyInt(), anyInt(), anyString(), any(), any(), anyBoolean(), anyBoolean(), any(), any(),
                any(), any())).thenReturn(page);

        var experimentAt = Instant.now().minusSeconds(90);
        var optimizationAt = Instant.now().minusSeconds(45);

        // Only the first and third datasets have summaries; the second must fall back to zeroes.
        when(experimentItemDAO.findExperimentSummaryByDatasetIds(anySet()))
                .thenReturn(Flux.just(new ExperimentItemDAO.ExperimentSummary(third, 11, experimentAt)));
        when(datasetItemDAO.findDatasetItemSummaryByDatasetIds(anySet()))
                .thenReturn(Flux.just(new DatasetItemSummary(first, 5)));
        when(optimizationDAO.findOptimizationSummaryByDatasetIds(anySet()))
                .thenReturn(Flux.just(new OptimizationDAO.OptimizationSummary(third, 2, optimizationAt)));
        when(datasetVersionDAO.findLatestVersionsByDatasetIds(anySet(), any())).thenReturn(List.of());

        var actual = service.find(1, 10, DatasetCriteria.builder().build(), List.of());

        assertThat(actual.content()).extracting(Dataset::id).containsExactly(first, second, third);
        assertThat(actual.total()).isEqualTo(3L);

        assertThat(actual.content().get(0).datasetItemsCount()).isEqualTo(5);
        assertThat(actual.content().get(0).experimentCount()).isZero();

        assertThat(actual.content().get(1).datasetItemsCount()).isZero();
        assertThat(actual.content().get(1).experimentCount()).isZero();
        assertThat(actual.content().get(1).optimizationCount()).isZero();
        assertThat(actual.content().get(1).mostRecentExperimentAt()).isNull();

        assertThat(actual.content().get(2).experimentCount()).isEqualTo(11);
        assertThat(actual.content().get(2).mostRecentExperimentAt()).isEqualTo(experimentAt);
        assertThat(actual.content().get(2).optimizationCount()).isEqualTo(2);
        assertThat(actual.content().get(2).mostRecentOptimizationAt()).isEqualTo(optimizationAt);
    }

    @Test
    @DisplayName("Enrichment returns the datasets untouched when the page is empty")
    void enrichmentOnEmptyPageReturnsEmptyContent() {
        when(sortingQueryBuilder.toOrderBySql(any())).thenReturn(null);
        when(sortingFactory.getSortableFields()).thenReturn(List.of());
        when(datasetDAO.findCount(anyString(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any()))
                .thenReturn(0L);
        when(datasetDAO.find(anyInt(), anyInt(), anyString(), any(), any(), anyBoolean(), anyBoolean(), any(), any(),
                any(), any())).thenReturn(List.of());

        var actual = service.find(1, 10, DatasetCriteria.builder().build(), List.of());

        assertThat(actual.content()).isEmpty();
        assertThat(actual.total()).isZero();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Item-count source selection (OPIK-8175): the legacy dataset_items count is an O(N) scan, so it must only
    // be issued for datasets that cannot take their count from a dataset version.
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("No dataset_items count is issued when every dataset resolves its count from a version")
    void enrichmentWhenFullyVersionedSkipsItemCountQuery() {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        stubPage(first, second);
        stubVersioningEnabled();
        stubLatestVersions(version(first, 100), version(second, 250));

        var actual = service.find(1, 10, DatasetCriteria.builder().build(), List.of());

        verify(datasetItemDAO, never()).findDatasetItemSummaryByDatasetIds(anySet());
        assertThat(itemsCountById(actual.content()))
                .containsExactlyInAnyOrderEntriesOf(Map.of(first, 100L, second, 250L));
    }

    @Test
    @DisplayName("The dataset_items count is issued and used when versioning is disabled")
    void enrichmentWhenVersioningDisabledFallsBackToItemCount() {
        var datasetId = UUID.randomUUID();
        stubPage(datasetId);
        when(featureFlags.isDatasetVersioningEnabled()).thenReturn(false);
        stubLatestVersions(version(datasetId, 100));
        stubItemCounts(new DatasetItemSummary(datasetId, 7));

        var actual = service.find(1, 10, DatasetCriteria.builder().build(), List.of());

        verify(datasetItemDAO).findDatasetItemSummaryByDatasetIds(Set.of(datasetId));
        assertThat(itemsCountById(actual.content())).containsExactlyInAnyOrderEntriesOf(Map.of(datasetId, 7L));
    }

    @Test
    @DisplayName("The dataset_items count is issued and used when no latest version exists")
    void enrichmentWhenVersionMissingFallsBackToItemCount() {
        var datasetId = UUID.randomUUID();
        stubPage(datasetId);
        stubVersioningEnabled();
        stubLatestVersions();
        stubItemCounts(new DatasetItemSummary(datasetId, 42));

        var actual = service.find(1, 10, DatasetCriteria.builder().build(), List.of());

        verify(datasetItemDAO).findDatasetItemSummaryByDatasetIds(Set.of(datasetId));
        assertThat(itemsCountById(actual.content())).containsExactlyInAnyOrderEntriesOf(Map.of(datasetId, 42L));
    }

    @Test
    @DisplayName("The dataset_items count is issued and used when the latest version has a null itemsTotal")
    void enrichmentWhenItemsTotalNullFallsBackToItemCount() {
        var datasetId = UUID.randomUUID();
        stubPage(datasetId);
        stubVersioningEnabled();
        stubLatestVersions(version(datasetId, null));
        stubItemCounts(new DatasetItemSummary(datasetId, 13));

        var actual = service.find(1, 10, DatasetCriteria.builder().build(), List.of());

        verify(datasetItemDAO).findDatasetItemSummaryByDatasetIds(Set.of(datasetId));
        assertThat(itemsCountById(actual.content())).containsExactlyInAnyOrderEntriesOf(Map.of(datasetId, 13L));
    }

    @Test
    @DisplayName("The not-migrated sentinel is not an authoritative count and falls back to dataset_items")
    void enrichmentWhenItemsTotalIsNotMigratedSentinelFallsBackToItemCount() {
        var datasetId = UUID.randomUUID();
        stubPage(datasetId);
        stubVersioningEnabled();
        stubLatestVersions(version(datasetId, DatasetVersionDAO.ITEMS_TOTAL_NOT_MIGRATED));
        stubItemCounts(new DatasetItemSummary(datasetId, 21));

        var actual = service.find(1, 10, DatasetCriteria.builder().build(), List.of());

        verify(datasetItemDAO).findDatasetItemSummaryByDatasetIds(Set.of(datasetId));
        assertThat(itemsCountById(actual.content())).containsExactlyInAnyOrderEntriesOf(Map.of(datasetId, 21L));
    }

    @Test
    @DisplayName("A dataset with no version and no items reports a zero count")
    void enrichmentWhenVersionAndItemCountMissingYieldsZero() {
        var datasetId = UUID.randomUUID();
        stubPage(datasetId);
        stubVersioningEnabled();
        stubLatestVersions();
        stubItemCounts();

        var actual = service.find(1, 10, DatasetCriteria.builder().build(), List.of());

        assertThat(itemsCountById(actual.content())).containsExactlyInAnyOrderEntriesOf(Map.of(datasetId, 0L));
    }

    @Test
    @DisplayName("A mixed batch resolves every count correctly in a single narrowed item-count query")
    void enrichmentOnMixedBatchNarrowsItemCountQueryToFallbackSubset() {
        var versioned = UUID.randomUUID();
        var noVersion = UUID.randomUUID();
        var nullTotal = UUID.randomUUID();
        stubPage(versioned, noVersion, nullTotal);
        stubVersioningEnabled();
        stubLatestVersions(version(versioned, 500), version(nullTotal, null));
        stubItemCounts(new DatasetItemSummary(noVersion, 3), new DatasetItemSummary(nullTotal, 9));

        var actual = service.find(1, 10, DatasetCriteria.builder().build(), List.of());

        verify(datasetItemDAO).findDatasetItemSummaryByDatasetIds(Set.of(noVersion, nullTotal));
        assertThat(itemsCountById(actual.content())).containsExactlyInAnyOrderEntriesOf(
                Map.of(versioned, 500L, noVersion, 3L, nullTotal, 9L));
    }

    @Test
    @DisplayName("The single-dataset retrieve path also skips the item-count query for a versioned dataset")
    void enrichmentOnFindByIdSkipsItemCountForVersionedDataset() {
        var datasetId = UUID.randomUUID();
        stubDataset(datasetId);
        stubNoSummaries();
        stubVersioningEnabled();
        stubLatestVersions(version(datasetId, 77));

        var actual = service.findById(datasetId);

        verify(datasetItemDAO, never()).findDatasetItemSummaryByDatasetIds(anySet());
        assertThat(actual.datasetItemsCount()).isEqualTo(77L);
    }

    @Test
    @DisplayName("The item-count query is issued only after the version lookup has resolved")
    void enrichmentDefersItemCountUntilVersionsAreKnown() {
        var datasetId = UUID.randomUUID();
        stubPage(datasetId);
        stubVersioningEnabled();

        var versionLookupDone = new java.util.concurrent.atomic.AtomicBoolean(false);
        var itemCountSawResolvedVersions = new java.util.concurrent.atomic.AtomicBoolean(false);

        when(datasetVersionDAO.findLatestVersionsByDatasetIds(anySet(), any())).thenAnswer(invocation -> {
            versionLookupDone.set(true);
            return List.of();
        });
        when(datasetItemDAO.findDatasetItemSummaryByDatasetIds(anySet())).thenAnswer(invocation -> {
            itemCountSawResolvedVersions.set(versionLookupDone.get());
            return Flux.just(new DatasetItemSummary(datasetId, 4));
        });

        var actual = service.find(1, 10, DatasetCriteria.builder().build(), List.of());

        assertThat(itemCountSawResolvedVersions)
                .as("the narrowed item-count query must not be issued before versions are known")
                .isTrue();
        assertThat(itemsCountById(actual.content())).containsExactlyInAnyOrderEntriesOf(Map.of(datasetId, 4L));
    }

    @Test
    @DisplayName("The item-count query does not wait on the experiment and optimization summaries")
    void enrichmentDoesNotBlockItemCountOnUnrelatedSummaries() throws Exception {
        var datasetId = UUID.randomUUID();
        stubPage(datasetId);
        stubVersioningEnabled();
        stubLatestVersions();

        // The item count depends only on the versions, so it must be subscribed while the two unrelated
        // summaries are still in flight. Both are held open until the count has been issued; if the count
        // were chained off the whole zip it would never be reached and the latch would time out.
        var itemCountSubscribed = new CountDownLatch(1);
        var releaseSummaries = new CountDownLatch(1);

        Flux<ExperimentItemDAO.ExperimentSummary> heldExperiments = Flux.defer(() -> {
            awaitQuietly(releaseSummaries);
            return Flux.<ExperimentItemDAO.ExperimentSummary>empty();
        }).subscribeOn(Schedulers.boundedElastic());

        Flux<OptimizationDAO.OptimizationSummary> heldOptimizations = Flux.defer(() -> {
            awaitQuietly(releaseSummaries);
            return Flux.<OptimizationDAO.OptimizationSummary>empty();
        }).subscribeOn(Schedulers.boundedElastic());

        when(experimentItemDAO.findExperimentSummaryByDatasetIds(anySet())).thenReturn(heldExperiments);
        when(optimizationDAO.findOptimizationSummaryByDatasetIds(anySet())).thenReturn(heldOptimizations);
        when(datasetItemDAO.findDatasetItemSummaryByDatasetIds(anySet())).thenAnswer(invocation -> {
            itemCountSubscribed.countDown();
            return Flux.just(new DatasetItemSummary(datasetId, 6));
        });

        var enrichment = CompletableFuture
                .supplyAsync(() -> service.find(1, 10, DatasetCriteria.builder().build(), List.of()));

        try {
            assertThat(itemCountSubscribed.await(SUBSCRIBE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("the item-count query should be issued while the unrelated summaries are still pending")
                    .isTrue();
        } finally {
            releaseSummaries.countDown();
        }

        var actual = enrichment.get(WORKER_RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(itemsCountById(actual.content())).containsExactlyInAnyOrderEntriesOf(Map.of(datasetId, 6L));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(WORKER_RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("summaries were never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void stubVersioningEnabled() {
        when(featureFlags.isDatasetVersioningEnabled()).thenReturn(true);
    }

    // The item-count tests assert only on datasetItemsCount, so the sibling lookups just need to be empty
    // rather than null -- these run under LENIENT strictness, so an unused stub is not an error.
    private void stubNoSummaries() {
        when(experimentItemDAO.findExperimentSummaryByDatasetIds(anySet())).thenReturn(Flux.empty());
        when(optimizationDAO.findOptimizationSummaryByDatasetIds(anySet())).thenReturn(Flux.empty());
        when(datasetItemDAO.findDatasetItemSummaryByDatasetIds(anySet())).thenReturn(Flux.empty());
    }

    private void stubPage(UUID... ids) {
        List<Dataset> page = java.util.Arrays.stream(ids)
                .map(id -> Dataset.builder().id(id).name("dataset-" + id).build())
                .toList();

        stubNoSummaries();

        when(sortingQueryBuilder.toOrderBySql(any())).thenReturn(null);
        when(sortingFactory.getSortableFields()).thenReturn(List.of());
        when(datasetDAO.findCount(anyString(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any()))
                .thenReturn((long) ids.length);
        when(datasetDAO.find(anyInt(), anyInt(), anyString(), any(), any(), anyBoolean(), anyBoolean(), any(), any(),
                any(), any())).thenReturn(page);
    }

    private void stubLatestVersions(DatasetVersion... versions) {
        when(datasetVersionDAO.findLatestVersionsByDatasetIds(anySet(), any())).thenReturn(List.of(versions));
    }

    private void stubItemCounts(DatasetItemSummary... summaries) {
        when(datasetItemDAO.findDatasetItemSummaryByDatasetIds(anySet()))
                .thenReturn(Flux.just(summaries));
    }

    private DatasetVersion version(UUID datasetId, Integer itemsTotal) {
        return DatasetVersion.builder()
                .id(UUID.randomUUID())
                .datasetId(datasetId)
                .itemsTotal(itemsTotal)
                .isLatest(true)
                .build();
    }

    private Map<UUID, Long> itemsCountById(List<Dataset> datasets) {
        return datasets.stream()
                .collect(java.util.stream.Collectors.toMap(Dataset::id, Dataset::datasetItemsCount));
    }
}
