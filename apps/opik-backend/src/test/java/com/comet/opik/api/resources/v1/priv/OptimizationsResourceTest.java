package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.ExperimentType;
import com.comet.opik.api.FeedbackScoreAverage;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.Optimization;
import com.comet.opik.api.OptimizationStatus;
import com.comet.opik.api.OptimizationUpdate;
import com.comet.opik.api.Project;
import com.comet.opik.api.Trace;
import com.comet.opik.api.events.OptimizationCreated;
import com.comet.opik.api.events.OptimizationsDeleted;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.StatsUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.OptimizationResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.google.common.eventbus.EventBus;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class OptimizationsResourceTest {

    public static final String[] OPTIMIZATION_IGNORED_FIELDS = {"datasetId", "createdAt",
            "lastUpdatedAt", "createdBy", "lastUpdatedBy"};

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE_NAME = "workspace" + RandomStringUtils.secure().nextAlphanumeric(36);
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(36);

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer<?> MYSQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);

    private final WireMockUtils.WireMockRuntime wireMock;
    private final TestDropwizardAppExtensionUtils.AppContextConfig contextConfig;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(REDIS, MYSQL_CONTAINER, CLICK_HOUSE_CONTAINER, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICK_HOUSE_CONTAINER, DATABASE_NAME);

        contextConfig = TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                .jdbcUrl(MYSQL_CONTAINER.getJdbcUrl())
                .databaseAnalyticsFactory(databaseAnalyticsFactory)
                .runtimeInfo(wireMock.runtimeInfo())
                .redisUrl(REDIS.getRedisURI())
                .authCacheTtlInSeconds(null)
                .mockEventBus(Mockito.mock(EventBus.class))
                .build();

        app = newTestDropwizardAppExtension(contextConfig);
    }

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;
    private EventBus defaultEventBus;
    private OptimizationResourceClient optimizationResourceClient;
    private DatasetResourceClient datasetResourceClient;
    private ExperimentResourceClient experimentResourceClient;
    private ProjectResourceClient projectResourceClient;
    private TraceResourceClient traceResourceClient;

    @BeforeAll
    void beforeAll(ClientSupport client, Jdbi jdbi) throws SQLException {
        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICK_HOUSE_CONTAINER.createConnection("")) {
            MigrationUtils.runClickhouseDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        baseURI = "http://localhost:%d".formatted(client.getPort());
        this.client = client;

        ClientSupportUtils.config(client);
        defaultEventBus = contextConfig.mockEventBus();

        this.optimizationResourceClient = new OptimizationResourceClient(this.client, baseURI, podamFactory);
        this.datasetResourceClient = new DatasetResourceClient(this.client, baseURI);
        this.experimentResourceClient = new ExperimentResourceClient(this.client, baseURI, podamFactory);
        this.projectResourceClient = new ProjectResourceClient(this.client, baseURI, podamFactory);
        this.traceResourceClient = new TraceResourceClient(this.client, baseURI);

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE_NAME, WORKSPACE_ID);
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Test
    @DisplayName("Create optimizer")
    void createOptimizer() {
        Mockito.reset(defaultEventBus);

        optimizationResourceClient.create(API_KEY, TEST_WORKSPACE_NAME);

        ArgumentCaptor<OptimizationCreated> experimentCaptor = ArgumentCaptor.forClass(OptimizationCreated.class);
        Mockito.verify(defaultEventBus).post(experimentCaptor.capture());
    }

    @ParameterizedTest
    @MethodSource("getLastUpdatedAt")
    @DisplayName("Get optimizer by id")
    void upsertOptimizer(Instant lastUpdatedAt) {
        Mockito.reset(defaultEventBus);

        var optimization = optimizationResourceClient.createPartialOptimization()
                .lastUpdatedAt(lastUpdatedAt)
                .build();

        // Create optimization via upsert
        var id = optimizationResourceClient.upsert(optimization, API_KEY, TEST_WORKSPACE_NAME);
        var actualOptimization = optimizationResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, 200);

        assertOptimization(optimization, actualOptimization);
        ArgumentCaptor<OptimizationCreated> experimentCaptor = ArgumentCaptor.forClass(OptimizationCreated.class);

        Mockito.verify(defaultEventBus).post(experimentCaptor.capture());

        // Update the same optimization
        var updatedOptimization = actualOptimization.toBuilder()
                .name(UUID.randomUUID().toString())
                .status(OptimizationStatus.COMPLETED)
                .objectiveName(UUID.randomUUID().toString())
                .build();
        optimizationResourceClient.upsert(updatedOptimization, API_KEY, TEST_WORKSPACE_NAME);

        var updatedActualOptimization = optimizationResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, 200);
        assertOptimization(updatedOptimization, updatedActualOptimization);
    }

    static Stream<Instant> getLastUpdatedAt() {
        return Stream.of(null, Instant.now());
    }

    @Nested
    @DisplayName("Get optimizer by id")
    class GetOptimizerById {

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.OptimizationsResourceTest#getLastUpdatedAt")
        @DisplayName("Get optimizer by id")
        void getById(Instant lastUpdatedAt) {
            var optimization = optimizationResourceClient.createPartialOptimization()
                    .lastUpdatedAt(lastUpdatedAt)
                    .build();

            var id = optimizationResourceClient.create(optimization, API_KEY, TEST_WORKSPACE_NAME);

            var actualOptimization = optimizationResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, 200);

            assertOptimization(optimization, actualOptimization);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 10})
        @DisplayName("Get optimizer by id with the number of trials")
        void getByIdWithNumTrials(long numTrials) {
            var optimization = optimizationResourceClient.createPartialOptimization()
                    .numTrials(numTrials)
                    .build();

            var id = optimizationResourceClient.create(optimization, API_KEY, TEST_WORKSPACE_NAME);

            LongStream.range(0, numTrials)
                    .parallel()
                    .forEach(i -> {
                        var experiment = experimentResourceClient.createPartialExperiment()
                                .optimizationId(id)
                                .type(ExperimentType.TRIAL)
                                .build();

                        experimentResourceClient.create(experiment, API_KEY, TEST_WORKSPACE_NAME);
                    });

            var actualOptimization = optimizationResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, 200);

            assertThat(actualOptimization)
                    .usingRecursiveComparison()
                    .ignoringFields(OPTIMIZATION_IGNORED_FIELDS)
                    .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                    .isEqualTo(optimization);
        }

        @Test
        @DisplayName("Get optimizer by id with feedback scores")
        void getByIdWithFeedbackScores() {
            // Create dataset
            Dataset dataset = podamFactory.manufacturePojo(Dataset.class);
            datasetResourceClient.createDataset(dataset, API_KEY, TEST_WORKSPACE_NAME);

            List<DatasetItem> items = PodamFactoryUtils.manufacturePojoList(podamFactory, DatasetItem.class);
            DatasetItemBatch itemBatch = new DatasetItemBatch(null, dataset.id(), items);

            datasetResourceClient.createDatasetItems(itemBatch, TEST_WORKSPACE_NAME, API_KEY);

            // Create experiment and attach it to the created dataset and optimizer
            List<FeedbackScoreAverage> feedbackScoreItems = PodamFactoryUtils.manufacturePojoList(podamFactory,
                    FeedbackScoreAverage.class);

            var optimization = optimizationResourceClient.createPartialOptimization()
                    .datasetId(dataset.id())
                    .objectiveName(feedbackScoreItems.getFirst().name())
                    .build();

            var id = optimizationResourceClient.create(optimization, API_KEY, TEST_WORKSPACE_NAME);

            Experiment experiment = experimentResourceClient.createPartialExperiment()
                    .datasetId(dataset.id())
                    .optimizationId(optimization.id())
                    .datasetName(dataset.name())
                    .type(ExperimentType.TRIAL)
                    .build();

            experimentResourceClient.create(experiment, API_KEY, TEST_WORKSPACE_NAME);

            Project project = podamFactory.manufacturePojo(Project.class).toBuilder()
                    .name("Experiment-%s".formatted(dataset.name()))
                    .build();

            projectResourceClient.createProject(project, API_KEY, TEST_WORKSPACE_NAME);

            Set<ExperimentItem> experimentItems = new HashSet<>();
            List<Trace> traces = new ArrayList<>();

            for (DatasetItem datasetItem : items) {

                Trace trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                        .projectId(project.id())
                        .projectName(project.name())
                        .guardrailsValidations(null)
                        .threadId(null)
                        .feedbackScores(null)
                        .usage(null)
                        .build();

                ExperimentItem experimentItem = podamFactory.manufacturePojo(ExperimentItem.class).toBuilder()
                        .experimentId(experiment.id())
                        .traceId(trace.id())
                        .input(JsonUtils.readTree(datasetItem.data()))
                        .datasetItemId(datasetItem.id())
                        .build();

                experimentItems.add(experimentItem);
                traces.add(trace);
            }

            traceResourceClient.batchCreateTraces(traces, API_KEY, TEST_WORKSPACE_NAME);
            experimentResourceClient.createExperimentItem(experimentItems, API_KEY, TEST_WORKSPACE_NAME);

            List<FeedbackScoreBatchItem> scoreBatchItems = traces.stream()
                    .flatMap(trace -> feedbackScoreItems.stream()
                            .map(score -> podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                                    .projectName(project.name())
                                    .id(trace.id())
                                    .name(score.name())
                                    .build()))
                    .toList();

            traceResourceClient.feedbackScores(scoreBatchItems, API_KEY, TEST_WORKSPACE_NAME);

            optimization = optimization.toBuilder()
                    .feedbackScores(
                            StatsUtils.calculateFeedbackBatchAverage(scoreBatchItems)
                                    .entrySet()
                                    .stream()
                                    .map(entry -> FeedbackScoreAverage.builder()
                                            .name(entry.getKey())
                                            .value(BigDecimal.valueOf(entry.getValue()))
                                            .build())
                                    .toList())
                    .numTrials(1L)
                    .build();

            // then
            var actualOptimization = optimizationResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, 200);

            assertThat(actualOptimization)
                    .usingRecursiveComparison()
                    .ignoringFields(OPTIMIZATION_IGNORED_FIELDS)
                    .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                    .ignoringCollectionOrderInFields("feedbackScores")
                    .isEqualTo(optimization);
        }

    }

    @Test
    @DisplayName("Delete optimizers by ids")
    void deleteByIds() {
        Mockito.reset(defaultEventBus);

        var id = optimizationResourceClient.create(API_KEY, TEST_WORKSPACE_NAME);

        // verify optimization was created
        optimizationResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, 200);

        // delete
        optimizationResourceClient.delete(Set.of(id), API_KEY, TEST_WORKSPACE_NAME);

        // verify optimization was deleted
        optimizationResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, 404);

        ArgumentCaptor<OptimizationsDeleted> experimentCaptor = ArgumentCaptor.forClass(OptimizationsDeleted.class);
        Mockito.verify(defaultEventBus).post(experimentCaptor.capture());
    }

    @ParameterizedTest
    @MethodSource
    @DisplayName("Update optimizer by id")
    void updateById(OptimizationUpdate update) {

        // Create optimization
        var optimization = optimizationResourceClient.createPartialOptimization().build();
        var id = optimizationResourceClient.create(optimization, API_KEY, TEST_WORKSPACE_NAME);

        // Update optimization
        optimizationResourceClient.update(id, update, API_KEY, TEST_WORKSPACE_NAME, 204);

        optimization = optimization.toBuilder().id(id)
                .name(update.name() != null ? update.name() : optimization.name())
                .status(update.status() != null ? update.status() : optimization.status())
                .build();

        var actualOptimization = optimizationResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, 200);

        assertOptimization(optimization, actualOptimization);
    }

    private Stream<Arguments> updateById() {
        return Stream.of(
                arguments(podamFactory.manufacturePojo(OptimizationUpdate.class)),
                arguments(podamFactory.manufacturePojo(OptimizationUpdate.class).toBuilder().name(null).build()),
                arguments(podamFactory.manufacturePojo(OptimizationUpdate.class).toBuilder().status(null).build()));
    }

    @Nested
    @DisplayName("Find optimizations")
    class FindOptimizations {

        private void assertOptimizationPage(Optimization.OptimizationPage page, int expectedPage,
                int expectedSize, int expectedContentSize,
                List<Optimization> expectedOptimizations) {
            // Validate page metadata
            assertThat(page).isNotNull();
            assertThat(page.page()).isEqualTo(expectedPage);
            assertThat(page.size()).isEqualTo(expectedSize);
            assertThat(page.content()).hasSize(expectedContentSize);

            // Validate that all expected optimizations are found with correct values
            assertThat(page.content())
                    .usingRecursiveComparison()
                    .ignoringFields(OPTIMIZATION_IGNORED_FIELDS)
                    .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                    .ignoringCollectionOrderInFields("feedbackScores")
                    .isEqualTo(expectedOptimizations);
        }

        @Test
        @DisplayName("Find optimizations with default parameters")
        void findWithDefaultParameters() {

            // Mock target workspace
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Create multiple optimizations
            var optimization1 = optimizationResourceClient.createPartialOptimization().build();
            var optimization2 = optimizationResourceClient.createPartialOptimization().build();

            var id1 = optimizationResourceClient.create(optimization1, apiKey, workspaceName);
            var id2 = optimizationResourceClient.create(optimization2, apiKey, workspaceName);

            // Update optimizations with IDs
            optimization1 = optimization1.toBuilder().id(id1).build();
            optimization2 = optimization2.toBuilder().id(id2).build();

            List<Optimization> expectedOptimizations = List.of(optimization1, optimization2);

            // Find optimizations with default parameters
            var optimizationPage = optimizationResourceClient.find(
                    apiKey, workspaceName, 1, 10, null, null, false, 200);

            // Verify results
            assertOptimizationPage(optimizationPage, 1, 2,
                    optimizationPage.content().size(), expectedOptimizations.reversed());
        }

        @Test
        @DisplayName("Find optimizations by name")
        void findByName() {

            // Mock target workspace
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Create optimization with specific name
            var uniqueName = "UniqueOptimizationName-" + UUID.randomUUID();
            var optimization = optimizationResourceClient.createPartialOptimization()
                    .name(uniqueName)
                    .build();

            var id = optimizationResourceClient.create(optimization, apiKey, workspaceName);
            optimization = optimization.toBuilder().id(id).build();

            // Find optimizations by name
            var optimizationPage = optimizationResourceClient.find(
                    apiKey, workspaceName, 1, 1, null, uniqueName, false, 200);

            // Verify results
            assertOptimizationPage(optimizationPage, 1, 1, 1, List.of(optimization));
        }

        @Test
        @DisplayName("Find optimizations by dataset ID")
        void findByDatasetId() {
            // Mock target workspace
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Create dataset
            Dataset dataset = podamFactory.manufacturePojo(Dataset.class);
            datasetResourceClient.createDataset(dataset, apiKey, workspaceName);

            // Create optimization with specific dataset ID
            var optimization = optimizationResourceClient.createPartialOptimization()
                    .datasetId(dataset.id())
                    .datasetName(dataset.name())
                    .build();

            var id = optimizationResourceClient.create(optimization, apiKey, workspaceName);
            optimization = optimization.toBuilder().id(id).build();

            // Find optimizations by dataset ID
            var optimizationPage = optimizationResourceClient.find(
                    apiKey, workspaceName, 1, 10, dataset.id(), null, false, 200);

            // Verify results
            assertOptimizationPage(optimizationPage, 1, 1, 1, List.of(optimization));
        }

        @Test
        @DisplayName("Find optimizations with pagination")
        void findWithPagination() {

            // Mock target workspace
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Create multiple optimizations with unique names to ensure we can identify them
            var optimizations = new ArrayList<Optimization>();

            for (int i = 0; i < 5; i++) {
                var name = "PaginationTest-" + i + "-" + UUID.randomUUID();
                var optimization = optimizationResourceClient.createPartialOptimization()
                        .name(name)
                        .build();

                var id = optimizationResourceClient.create(optimization, apiKey, workspaceName);
                optimizations.add(optimization.toBuilder().id(id).build());
            }

            // Find first page with size 2
            var page1 = optimizationResourceClient.find(
                    apiKey, workspaceName, 1, 2, null, null, false, 200);

            // Find second page with size 2
            var page2 = optimizationResourceClient.find(
                    apiKey, workspaceName, 2, 2, null, null, false, 200);

            // Verify pagination
            assertOptimizationPage(page1, 1, 2, 2, optimizations.reversed().subList(0, 2));
            assertOptimizationPage(page2, 2, 2, 2, optimizations.reversed().subList(2, 4));
        }

        @Test
        @DisplayName("Find optimizations with empty result")
        void findWithEmptyResult() {
            // Mock target workspace
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Find optimizations with non-existent name
            var nonExistentName = "NonExistentName-" + UUID.randomUUID();
            var optimizationPage = optimizationResourceClient.find(
                    apiKey, workspaceName, 1, 10, null, nonExistentName, false, 200);

            // Verify empty results
            assertOptimizationPage(optimizationPage, 1, 0, 0, List.of());
        }

        @Test
        @DisplayName("Find optimizations with feedback scores")
        void findWithFeedbackScores() {
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Create dataset
            Dataset dataset = podamFactory.manufacturePojo(Dataset.class);
            datasetResourceClient.createDataset(dataset, apiKey, workspaceName);

            List<DatasetItem> items = PodamFactoryUtils.manufacturePojoList(podamFactory, DatasetItem.class);
            DatasetItemBatch itemBatch = new DatasetItemBatch(null, dataset.id(), items);

            datasetResourceClient.createDatasetItems(itemBatch, workspaceName, apiKey);

            // Create feedback score items
            List<FeedbackScoreAverage> feedbackScoreItems = PodamFactoryUtils.manufacturePojoList(podamFactory,
                    FeedbackScoreAverage.class);

            // Create optimization with dataset and objective name
            var optimization = optimizationResourceClient.createPartialOptimization()
                    .datasetId(dataset.id())
                    .datasetName(dataset.name())
                    .objectiveName(feedbackScoreItems.getFirst().name())
                    .build();

            var id = optimizationResourceClient.create(optimization, apiKey, workspaceName);

            // Create experiment and attach it to the created dataset and optimizer
            Experiment experiment = experimentResourceClient.createPartialExperiment()
                    .datasetId(dataset.id())
                    .optimizationId(id)
                    .datasetName(dataset.name())
                    .type(ExperimentType.TRIAL)
                    .build();

            experimentResourceClient.create(experiment, apiKey, workspaceName);

            // Create project
            Project project = podamFactory.manufacturePojo(Project.class).toBuilder()
                    .name("Experiment-%s".formatted(dataset.name()))
                    .build();

            projectResourceClient.createProject(project, apiKey, workspaceName);

            // Create experiment items and traces
            Set<ExperimentItem> experimentItems = new HashSet<>();
            List<Trace> traces = new ArrayList<>();

            for (DatasetItem datasetItem : items) {
                Trace trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                        .projectId(project.id())
                        .projectName(project.name())
                        .guardrailsValidations(null)
                        .threadId(null)
                        .feedbackScores(null)
                        .usage(null)
                        .build();

                ExperimentItem experimentItem = podamFactory.manufacturePojo(ExperimentItem.class).toBuilder()
                        .experimentId(experiment.id())
                        .traceId(trace.id())
                        .input(JsonUtils.readTree(datasetItem.data()))
                        .datasetItemId(datasetItem.id())
                        .build();

                experimentItems.add(experimentItem);
                traces.add(trace);
            }

            traceResourceClient.batchCreateTraces(traces, apiKey, workspaceName);
            experimentResourceClient.createExperimentItem(experimentItems, apiKey, workspaceName);

            // Create feedback scores
            List<FeedbackScoreBatchItem> scoreBatchItems = traces.stream()
                    .flatMap(trace -> feedbackScoreItems.stream()
                            .map(score -> podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                                    .projectName(project.name())
                                    .id(trace.id())
                                    .name(score.name())
                                    .build()))
                    .toList();

            traceResourceClient.feedbackScores(scoreBatchItems, apiKey, workspaceName);

            // Update optimization with expected feedback scores
            optimization = optimization.toBuilder()
                    .id(id)
                    .feedbackScores(
                            StatsUtils.calculateFeedbackBatchAverage(scoreBatchItems)
                                    .entrySet()
                                    .stream()
                                    .map(entry -> FeedbackScoreAverage.builder()
                                            .name(entry.getKey())
                                            .value(BigDecimal.valueOf(entry.getValue()))
                                            .build())
                                    .toList())
                    .numTrials(1L)
                    .build();

            // Find optimization
            var optimizationPage = optimizationResourceClient.find(
                    apiKey, workspaceName, 1, 10, dataset.id(), null, false, 200);

            // Verify results with feedback scores
            assertOptimizationPage(optimizationPage, 1, 1, 1, List.of(optimization));
        }
    }

    private void assertOptimization(Optimization expected, Optimization actual) {
        assertThat(actual)
                .usingRecursiveComparison()
                .ignoringFields(OPTIMIZATION_IGNORED_FIELDS)
                .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                .isEqualTo(expected);

        if (expected.lastUpdatedAt() != null) {
            assertThat(actual.lastUpdatedAt().truncatedTo(ChronoUnit.MICROS))
                    // Some JVMs can resolve higher than microseconds, such as nanoseconds in the Ubuntu AMD64 JVM
                    .isAfterOrEqualTo(expected.lastUpdatedAt().truncatedTo(ChronoUnit.MICROS));
        } else {
            assertThat(actual.lastUpdatedAt()).isCloseTo(Instant.now(), within(2, ChronoUnit.SECONDS));
        }
    }
}