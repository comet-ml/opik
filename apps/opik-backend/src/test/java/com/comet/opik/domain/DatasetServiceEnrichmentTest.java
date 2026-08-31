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
import reactor.util.context.ContextView;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxAction;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
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

    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER_NAME = "test-user";

    private DatasetService service;

    @BeforeEach
    void setUp() {
        when(requestContextProvider.get()).thenReturn(requestContext);
        when(requestContext.getWorkspaceId()).thenReturn(WORKSPACE_ID);
        when(requestContext.getUserName()).thenReturn(USER_NAME);
        when(requestContext.getVisibility()).thenReturn(Visibility.PRIVATE);

        var handle = mock(Handle.class);
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

    private Dataset stubDataset(UUID id) {
        var dataset = Dataset.builder().id(id).name("dataset-" + id).build();
        when(datasetDAO.findById(id, WORKSPACE_ID)).thenReturn(java.util.Optional.of(dataset));
        return dataset;
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
}
