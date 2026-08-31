package com.comet.opik.domain;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetVersion;
import com.comet.opik.api.sorting.SortingFactoryDatasets;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.infrastructure.BatchOperationsConfig;
import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.common.eventbus.EventBus;
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
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxAction;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the item-count source selection in {@code enrichDatasetWithAdditionalInformation}: the legacy
 * {@code dataset_items} count is an O(N) scan, so it must only be issued for datasets that cannot take their
 * count from a dataset version.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DatasetServiceEnrichmentTest {

    private static final String WORKSPACE_ID = "test-workspace";
    private static final String WORKSPACE_NAME = "test-workspace-name";
    private static final String USER_NAME = "test-user";

    @Mock
    private IdGenerator idGenerator;
    @Mock
    private TransactionTemplate template;
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
    private Handle handle;
    @Mock
    private DatasetDAO datasetDAO;
    @Mock
    private DatasetVersionDAO datasetVersionDAO;

    private DatasetServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DatasetServiceImpl(idGenerator, template, () -> requestContext, experimentItemDAO,
                datasetItemDAO, experimentDAO, sortingQueryBuilder, filterQueryBuilder, sortingFactory,
                batchOperationsConfig, optimizationDAO, eventBus, featureFlags, projectService);

        when(requestContext.getWorkspaceId()).thenReturn(WORKSPACE_ID);
        when(requestContext.getWorkspaceName()).thenReturn(WORKSPACE_NAME);
        when(requestContext.getUserName()).thenReturn(USER_NAME);

        when(template.inTransaction(any(), any())).thenAnswer(invocation -> {
            TxAction<?> callback = invocation.getArgument(1);
            return callback.execute(handle);
        });
        when(handle.attach(DatasetDAO.class)).thenReturn(datasetDAO);
        when(handle.attach(DatasetVersionDAO.class)).thenReturn(datasetVersionDAO);

        when(experimentItemDAO.findExperimentSummaryByDatasetIds(any())).thenReturn(Flux.empty());
        when(optimizationDAO.findOptimizationSummaryByDatasetIds(any())).thenReturn(Flux.empty());
        when(datasetItemDAO.findDatasetItemSummaryByDatasetIds(any())).thenReturn(Flux.empty());

        when(featureFlags.isDatasetVersioningEnabled()).thenReturn(true);
        when(sortingFactory.getSortableFields()).thenReturn(List.of());
    }

    @Test
    @DisplayName("no dataset_items count is issued when every dataset resolves its count from a version")
    void fullyVersionedBatchSkipsItemCountQuery() {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        givenDatasets(first, second);
        givenLatestVersions(version(first, 100), version(second, 250));

        var content = findPage().content();

        verify(datasetItemDAO, never()).findDatasetItemSummaryByDatasetIds(any());
        assertThat(itemsCountById(content)).containsExactlyInAnyOrderEntriesOf(Map.of(first, 100L, second, 250L));
    }

    @Test
    @DisplayName("dataset_items count is issued and used when versioning is disabled")
    void versioningDisabledFallsBackToItemCount() {
        when(featureFlags.isDatasetVersioningEnabled()).thenReturn(false);

        var id = UUID.randomUUID();
        givenDatasets(id);
        givenLatestVersions(version(id, 100));
        givenItemCounts(Map.of(id, 7L));

        var content = findPage().content();

        verify(datasetItemDAO).findDatasetItemSummaryByDatasetIds(Set.of(id));
        assertThat(itemsCountById(content)).containsExactlyInAnyOrderEntriesOf(Map.of(id, 7L));
    }

    @Test
    @DisplayName("dataset_items count is issued and used when no latest version exists")
    void missingVersionFallsBackToItemCount() {
        var id = UUID.randomUUID();
        givenDatasets(id);
        givenLatestVersions();
        givenItemCounts(Map.of(id, 42L));

        var content = findPage().content();

        verify(datasetItemDAO).findDatasetItemSummaryByDatasetIds(Set.of(id));
        assertThat(itemsCountById(content)).containsExactlyInAnyOrderEntriesOf(Map.of(id, 42L));
    }

    @Test
    @DisplayName("dataset_items count is issued and used when the latest version has a null itemsTotal")
    void nullItemsTotalFallsBackToItemCount() {
        var id = UUID.randomUUID();
        givenDatasets(id);
        givenLatestVersions(version(id, null));
        givenItemCounts(Map.of(id, 13L));

        var content = findPage().content();

        verify(datasetItemDAO).findDatasetItemSummaryByDatasetIds(Set.of(id));
        assertThat(itemsCountById(content)).containsExactlyInAnyOrderEntriesOf(Map.of(id, 13L));
    }

    @Test
    @DisplayName("a dataset with no version and no items reports a zero count")
    void missingVersionAndMissingItemCountYieldsZero() {
        var id = UUID.randomUUID();
        givenDatasets(id);
        givenLatestVersions();
        givenItemCounts(Map.of());

        var content = findPage().content();

        assertThat(itemsCountById(content)).containsExactlyInAnyOrderEntriesOf(Map.of(id, 0L));
    }

    @Test
    @DisplayName("a mixed batch resolves every count correctly in a single narrowed item-count query")
    void mixedBatchNarrowsItemCountQueryToTheFallbackSubset() {
        var versioned = UUID.randomUUID();
        var noVersion = UUID.randomUUID();
        var nullTotal = UUID.randomUUID();
        givenDatasets(versioned, noVersion, nullTotal);
        givenLatestVersions(version(versioned, 500), version(nullTotal, null));
        givenItemCounts(Map.of(noVersion, 3L, nullTotal, 9L));

        var content = findPage().content();

        verify(datasetItemDAO).findDatasetItemSummaryByDatasetIds(Set.of(noVersion, nullTotal));
        assertThat(itemsCountById(content)).containsExactlyInAnyOrderEntriesOf(
                Map.of(versioned, 500L, noVersion, 3L, nullTotal, 9L));
    }

    @Test
    @DisplayName("the single-dataset retrieve path also skips the item-count query for a versioned dataset")
    void findByIdSkipsItemCountForVersionedDataset() {
        var id = UUID.randomUUID();
        var dataset = dataset(id);
        when(datasetDAO.findById(id, WORKSPACE_ID)).thenReturn(Optional.of(dataset));
        givenLatestVersions(version(id, 77));

        var result = service.findById(id);

        verify(datasetItemDAO, never()).findDatasetItemSummaryByDatasetIds(any());
        assertThat(result.datasetItemsCount()).isEqualTo(77L);
    }

    private Dataset.DatasetPage findPage() {
        return service.find(1, 10, DatasetCriteria.builder().build(), List.of());
    }

    private void givenDatasets(UUID... ids) {
        List<Dataset> datasets = Arrays.stream(ids).map(this::dataset).toList();
        when(datasetDAO.find(eq(10), eq(0), eq(WORKSPACE_ID), any(), any(), eq(false), eq(false), any(), any(), any(),
                any())).thenReturn(datasets);
        when(datasetDAO.findCount(eq(WORKSPACE_ID), any(), any(), eq(false), eq(false), any(), any(), any()))
                .thenReturn((long) ids.length);
    }

    private void givenLatestVersions(DatasetVersion... versions) {
        when(datasetVersionDAO.findLatestVersionsByDatasetIds(any(), anyString())).thenReturn(List.of(versions));
    }

    private void givenItemCounts(Map<UUID, Long> countsByDatasetId) {
        var summaries = countsByDatasetId.entrySet().stream()
                .map(entry -> new DatasetItemSummary(entry.getKey(), entry.getValue()))
                .toList();
        when(datasetItemDAO.findDatasetItemSummaryByDatasetIds(any())).thenReturn(Flux.fromIterable(summaries));
    }

    private Dataset dataset(UUID id) {
        return Dataset.builder().id(id).name("dataset-" + id).build();
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
        return datasets.stream().collect(Collectors.toMap(Dataset::id, Dataset::datasetItemsCount));
    }
}
