package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.ExperimentType;
import com.comet.opik.api.FeedbackScoreAverage;
import com.comet.opik.api.Optimization;
import com.comet.opik.api.OptimizationStatus;
import com.comet.opik.api.OptimizationStudioConfig;
import com.comet.opik.api.OptimizationUpdate;
import com.comet.opik.api.Project;
import com.comet.opik.api.Trace;
import com.comet.opik.api.events.OptimizationCreated;
import com.comet.opik.api.events.OptimizationsDeleted;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.OptimizationField;
import com.comet.opik.api.filter.OptimizationFilter;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.StatsUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.OptimizationResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.queues.Queue;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.google.common.eventbus.EventBus;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
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
import org.redisson.Redisson;
import org.redisson.api.RMapReactive;
import org.redisson.api.RQueueReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class OptimizationsResourceTest {

    public static final String[] OPTIMIZATION_IGNORED_FIELDS = {"datasetId", "createdAt",
            "lastUpdatedAt", "createdBy", "lastUpdatedBy", "studioConfig"};

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE_NAME = "workspace" + RandomStringUtils.secure().nextAlphanumeric(36);
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(36);

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);

    private final WireMockUtils.WireMockRuntime wireMock;
    private final TestDropwizardAppExtensionUtils.AppContextConfig contextConfig;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MYSQL_CONTAINER, CLICK_HOUSE_CONTAINER, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICK_HOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL_CONTAINER);
        MigrationUtils.runClickhouseDbMigration(CLICK_HOUSE_CONTAINER);

        contextConfig = TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                .jdbcUrl(MYSQL_CONTAINER.getJdbcUrl())
                .databaseAnalyticsFactory(databaseAnalyticsFactory)
                .runtimeInfo(wireMock.runtimeInfo())
                .redisUrl(REDIS.getRedisURI())
                .authCacheTtlInSeconds(null)
                .mockEventBus(Mockito.mock(EventBus.class))
                .build();

        APP = newTestDropwizardAppExtension(contextConfig);
    }

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;
    private EventBus defaultEventBus;
    private RedissonReactiveClient redisClient;
    private OptimizationResourceClient optimizationResourceClient;
    private DatasetResourceClient datasetResourceClient;
    private ExperimentResourceClient experimentResourceClient;
    private ProjectResourceClient projectResourceClient;
    private TraceResourceClient traceResourceClient;

    @BeforeAll
    void beforeAll(ClientSupport client) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;

        ClientSupportUtils.config(client);
        defaultEventBus = contextConfig.mockEventBus();

        // Initialize Redis client for testing
        Config redisConfig = new Config();
        redisConfig.useSingleServer()
                .setAddress(REDIS.getRedisURI())
                .setDatabase(0);
        this.redisClient = Redisson.create(redisConfig).reactive();

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
            DatasetItemBatch itemBatch = DatasetItemBatch.builder().datasetId(dataset.id()).items(items).build();

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
                    .collect(Collectors.toList());

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
                    apiKey, workspaceName, 1, 10, null, null, null, null, 200);

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
                    apiKey, workspaceName, 1, 1, null, uniqueName, null, null, 200);

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
                    apiKey, workspaceName, 1, 10, dataset.id(), null, null, null, 200);

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
                    apiKey, workspaceName, 1, 2, null, null, null, null, 200);

            // Find second page with size 2
            var page2 = optimizationResourceClient.find(
                    apiKey, workspaceName, 2, 2, null, null, null, null, 200);

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
                    apiKey, workspaceName, 1, 10, null, nonExistentName, null, null, 200);

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
            DatasetItemBatch itemBatch = DatasetItemBatch.builder().datasetId(dataset.id()).items(items).build();

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
                    .collect(Collectors.toList());

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
                    apiKey, workspaceName, 1, 10, dataset.id(), null, null, null, 200);

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

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("Optimization Studio Tests")
    class OptimizationStudioTests {

        private OptimizationStudioConfig createStudioConfig() {
            // opikApiKey is @JsonIgnore and populated server-side, so we don't include it
            return podamFactory.manufacturePojo(OptimizationStudioConfig.class).toBuilder()
                    .opikApiKey(null)
                    .build();
        }

        @Test
        @DisplayName("Create Studio optimization and verify in database")
        void createStudioOptimization() {
            Mockito.reset(defaultEventBus);

            var studioConfig = createStudioConfig();
            var optimization = optimizationResourceClient.createPartialOptimization()
                    .studioConfig(studioConfig)
                    .build();

            var id = optimizationResourceClient.create(optimization, API_KEY, TEST_WORKSPACE_NAME);
            var actualOptimization = optimizationResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, 200);

            // studioConfig is now returned (opikApiKey is null since it's @JsonIgnore)
            assertThat(actualOptimization.studioConfig()).isNotNull();
            assertThat(actualOptimization.studioConfig()).isEqualTo(studioConfig);
            assertOptimization(optimization, actualOptimization);

            ArgumentCaptor<OptimizationCreated> captor = ArgumentCaptor.forClass(OptimizationCreated.class);
            Mockito.verify(defaultEventBus).post(captor.capture());
        }

        @Test
        @DisplayName("Create Studio optimization and verify Redis job enqueued with opikApiKey")
        void createStudioOptimization__thenVerifyRedisJobEnqueued() {
            Mockito.reset(defaultEventBus);

            var studioConfig = createStudioConfig();
            var optimization = optimizationResourceClient.createPartialOptimization()
                    .studioConfig(studioConfig)
                    .build();

            // Get initial queue size
            String queueKey = "rq:queue:" + Queue.OPTIMIZER_CLOUD.toString();
            RQueueReactive<String> queue = redisClient.getQueue(queueKey, StringCodec.INSTANCE);
            Integer initialSize = queue.size().block();
            assertThat(initialSize).isNotNull();

            // Create Studio optimization with custom opikApiKey header
            var customOpikApiKey = "test-opik-api-key-" + UUID.randomUUID();
            var id = optimizationResourceClient.create(optimization, API_KEY, TEST_WORKSPACE_NAME, customOpikApiKey);

            // Wait for async job enqueueing to complete (max 2 seconds)
            await().atMost(2, java.util.concurrent.TimeUnit.SECONDS)
                    .pollInterval(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> assertThat(queue.size().block()).isGreaterThan(initialSize));

            // Verify a job was enqueued for our optimization
            // The optimization ID is inside the job's payload data
            var allJobIds = queue.readAll().block();
            assertThat(allJobIds).isNotEmpty();

            // Find the job containing our optimization ID in its data
            boolean foundJob = allJobIds.stream()
                    .anyMatch(jobId -> {
                        String jobKey = "rq:job:" + jobId;
                        RMapReactive<String, Object> jobMap = redisClient.getMap(jobKey, StringCodec.INSTANCE);
                        String jobData = (String) jobMap.get("data").block();

                        // Verify job data contains our optimization ID, workspace name, and opikApiKey
                        return jobData != null
                                && jobData.contains(id.toString())
                                && jobData.contains(TEST_WORKSPACE_NAME)
                                && jobData.contains(customOpikApiKey);
                    });

            assertThat(foundJob)
                    .as("Expected to find RQ job with optimization ID: %s, workspace name: %s, and opikApiKey: %s",
                            id, TEST_WORKSPACE_NAME, customOpikApiKey)
                    .isTrue();

            // Verify opikApiKey is NOT returned when retrieving the optimization
            var studioResponse = optimizationResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, 200);
            assertThat(studioResponse.studioConfig()).isNotNull();
            assertThat(studioResponse.studioConfig().opikApiKey()).isNull();

            // Verify event was posted
            ArgumentCaptor<OptimizationCreated> captor = ArgumentCaptor.forClass(OptimizationCreated.class);
            Mockito.verify(defaultEventBus).post(captor.capture());
        }

        @Test
        @DisplayName("Get Studio optimization logs")
        void getStudioOptimizationLogs() {
            var studioConfig = createStudioConfig();
            var optimization = optimizationResourceClient.createPartialOptimization()
                    .studioConfig(studioConfig)
                    .build();

            var id = optimizationResourceClient.create(optimization, API_KEY, TEST_WORKSPACE_NAME);

            // Get logs
            var logs = optimizationResourceClient.getStudioLogs(id, API_KEY, TEST_WORKSPACE_NAME, 200);
            assertThat(logs.url()).isNotNull();
            assertThat(logs.url()).contains(WORKSPACE_ID);
            assertThat(logs.url()).contains(id.toString());
            assertThat(logs.expiresAt()).isNotNull();
            assertThat(logs.expiresAt()).isAfter(Instant.now());
        }

        @Test
        @DisplayName("Find optimizations returns studioConfig when present")
        void findOptimizationsReturnsStudioConfig() {
            // Mock target workspace
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Create a regular optimization (without studio config)
            var regularOptimization = optimizationResourceClient.createPartialOptimization()
                    .studioConfig(null)
                    .build();
            var regularId = optimizationResourceClient.create(regularOptimization, apiKey, workspaceName);

            // Create a Studio optimization
            var studioConfig = createStudioConfig();
            var studioOptimization = optimizationResourceClient.createPartialOptimization()
                    .studioConfig(studioConfig)
                    .build();
            var studioId = optimizationResourceClient.create(studioOptimization, apiKey, workspaceName);

            // Find all optimizations - both should be returned
            var page = optimizationResourceClient.find(apiKey, workspaceName, 1, 10, null, null, null, 200);

            // Should return both optimizations
            assertThat(page.content()).hasSize(2);

            // Studio optimization should have studioConfig included
            var studioOpt = page.content().stream()
                    .filter(opt -> opt.id().equals(studioId))
                    .findFirst()
                    .orElseThrow();
            assertThat(studioOpt.studioConfig()).isNotNull();

            // Regular optimization should have null studioConfig
            var regularOpt = page.content().stream()
                    .filter(opt -> opt.id().equals(regularId))
                    .findFirst()
                    .orElseThrow();
            assertThat(regularOpt.studioConfig()).isNull();
        }

        @Test
        @DisplayName("Get optimization by ID returns studioConfig when present")
        void getOptimizationByIdReturnsStudioConfig() {
            var studioConfig = createStudioConfig();
            var optimization = optimizationResourceClient.createPartialOptimization()
                    .studioConfig(studioConfig)
                    .build();

            var id = optimizationResourceClient.create(optimization, API_KEY, TEST_WORKSPACE_NAME);

            // Get using standard endpoint
            var actualOptimization = optimizationResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, 200);

            // Studio config should be included (opikApiKey is null in both since it's @JsonIgnore)
            assertThat(actualOptimization).isNotNull();
            assertThat(actualOptimization.id()).isEqualTo(id);
            assertThat(actualOptimization.studioConfig()).isNotNull();
            assertThat(actualOptimization.studioConfig()).isEqualTo(studioConfig);
        }

        @Test
        @DisplayName("Studio config is preserved after status update")
        void studioConfigPreservedAfterStatusUpdate() {
            var studioConfig = createStudioConfig();
            var optimization = optimizationResourceClient.createPartialOptimization()
                    .studioConfig(studioConfig)
                    .build();

            var id = optimizationResourceClient.create(optimization, API_KEY, TEST_WORKSPACE_NAME);

            // Verify initial state - should have studio_config and INITIALIZED status
            var initialOptimization = optimizationResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, 200);
            assertThat(initialOptimization.studioConfig()).isNotNull();
            assertThat(initialOptimization.studioConfig()).isEqualTo(studioConfig);
            assertThat(initialOptimization.status()).isEqualTo(OptimizationStatus.INITIALIZED);

            // Update status to RUNNING
            var runningUpdate = OptimizationUpdate.builder()
                    .status(OptimizationStatus.RUNNING)
                    .build();
            optimizationResourceClient.update(id, runningUpdate, API_KEY, TEST_WORKSPACE_NAME, 204);

            // Verify studio_config is preserved after RUNNING update
            var runningOptimization = optimizationResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, 200);
            assertThat(runningOptimization.status()).isEqualTo(OptimizationStatus.RUNNING);
            assertThat(runningOptimization.studioConfig()).isNotNull();
            assertThat(runningOptimization.studioConfig()).isEqualTo(studioConfig);

            // Update status to COMPLETED
            var completedUpdate = OptimizationUpdate.builder()
                    .status(OptimizationStatus.COMPLETED)
                    .build();
            optimizationResourceClient.update(id, completedUpdate, API_KEY, TEST_WORKSPACE_NAME, 204);

            // Verify studio_config is still preserved after COMPLETED update
            var completedOptimization = optimizationResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, 200);
            assertThat(completedOptimization.status()).isEqualTo(OptimizationStatus.COMPLETED);
            assertThat(completedOptimization.studioConfig()).isNotNull();
            assertThat(completedOptimization.studioConfig()).isEqualTo(studioConfig);
        }

        @Test
        @DisplayName("Cancel Studio optimization returns NOT_IMPLEMENTED")
        void cancelStudioOptimization__returnsNotImplemented() {
            var studioConfig = createStudioConfig();
            var optimization = optimizationResourceClient.createPartialOptimization()
                    .studioConfig(studioConfig)
                    .build();

            var id = optimizationResourceClient.create(optimization, API_KEY, TEST_WORKSPACE_NAME);

            // Cancel should return 501 NOT_IMPLEMENTED
            optimizationResourceClient.cancelStudio(id, API_KEY, TEST_WORKSPACE_NAME, 501);
        }

        @Test
        @DisplayName("Filter optimizations by status on generic endpoint")
        void filterOptimizationsByStatus() {
            // Create optimizations with different statuses
            var completedOpt = optimizationResourceClient.createPartialOptimization()
                    .status(OptimizationStatus.COMPLETED)
                    .build();
            var runningOpt = optimizationResourceClient.createPartialOptimization()
                    .status(OptimizationStatus.RUNNING)
                    .build();

            optimizationResourceClient.create(completedOpt, API_KEY, TEST_WORKSPACE_NAME);
            optimizationResourceClient.create(runningOpt, API_KEY, TEST_WORKSPACE_NAME);

            // Filter by completed status
            var filter = OptimizationFilter.builder()
                    .field(OptimizationField.STATUS)
                    .operator(Operator.EQUAL)
                    .value("completed")
                    .build();

            var page = optimizationResourceClient.find(API_KEY, TEST_WORKSPACE_NAME, 1, 10,
                    null, null, null, List.of(filter), 200);

            // Should only return completed optimizations
            assertThat(page.content()).isNotEmpty();
            assertThat(page.content()).allMatch(opt -> opt.status() == OptimizationStatus.COMPLETED);
        }

        @Test
        @DisplayName("Filter optimizations by status - comprehensive test")
        void filterOptimizationsByStatus__comprehensive() {
            // Create isolated workspace for this test
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var studioConfig = createStudioConfig();

            // Create 3 optimizations:
            // 1. Studio with INITIALIZED (forced by service layer)
            var studioOpt = optimizationResourceClient.createPartialOptimization()
                    .studioConfig(studioConfig)
                    .build();
            var studioOptId = optimizationResourceClient.create(studioOpt, apiKey, workspaceName);

            // 2. Regular with INITIALIZED (explicitly set)
            var regularInitOpt = optimizationResourceClient.createPartialOptimization()
                    .studioConfig(null)
                    .status(OptimizationStatus.INITIALIZED)
                    .build();
            var regularInitOptId = optimizationResourceClient.create(regularInitOpt, apiKey, workspaceName);

            // 3. Regular with COMPLETED
            var regularCompletedOpt = optimizationResourceClient.createPartialOptimization()
                    .studioConfig(null)
                    .status(OptimizationStatus.COMPLETED)
                    .build();
            var regularCompletedOptId = optimizationResourceClient.create(regularCompletedOpt, apiKey, workspaceName);

            // Test 1: Filter by INITIALIZED status - should get 2 (studio + regular)
            var initializedFilter = OptimizationFilter.builder()
                    .field(OptimizationField.STATUS)
                    .operator(Operator.EQUAL)
                    .value("initialized")
                    .build();

            var initializedPage = optimizationResourceClient.find(apiKey, workspaceName, 1, 10,
                    null, null, null, List.of(initializedFilter), 200);

            assertThat(initializedPage.content()).hasSize(2);
            assertThat(initializedPage.content()).allMatch(opt -> opt.status() == OptimizationStatus.INITIALIZED);
            assertThat(initializedPage.content()).extracting(Optimization::id)
                    .containsExactlyInAnyOrder(studioOptId, regularInitOptId);

            // Test 2: Filter by COMPLETED status - should get 1 (regular only)
            var completedFilter = OptimizationFilter.builder()
                    .field(OptimizationField.STATUS)
                    .operator(Operator.EQUAL)
                    .value("completed")
                    .build();

            var completedPage = optimizationResourceClient.find(apiKey, workspaceName, 1, 10,
                    null, null, null, List.of(completedFilter), 200);

            assertThat(completedPage.content()).hasSize(1);
            assertThat(completedPage.content().get(0).id()).isEqualTo(regularCompletedOptId);
            assertThat(completedPage.content().get(0).status()).isEqualTo(OptimizationStatus.COMPLETED);
        }

        @Test
        @DisplayName("Filter optimizations by dataset_id")
        void filterOptimizationsByDatasetId() {
            // Create isolated workspace for this test
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Create datasets
            var dataset1 = podamFactory.manufacturePojo(Dataset.class).toBuilder().build();
            var dataset1Id = datasetResourceClient.createDataset(dataset1, apiKey, workspaceName);

            var dataset2 = podamFactory.manufacturePojo(Dataset.class).toBuilder().build();
            var dataset2Id = datasetResourceClient.createDataset(dataset2, apiKey, workspaceName);

            // Create optimizations for different datasets
            var opt1 = optimizationResourceClient.createPartialOptimization()
                    .datasetId(dataset1Id)
                    .build();
            var opt2 = optimizationResourceClient.createPartialOptimization()
                    .datasetId(dataset2Id)
                    .build();

            var opt1Id = optimizationResourceClient.create(opt1, apiKey, workspaceName);
            var opt2Id = optimizationResourceClient.create(opt2, apiKey, workspaceName);

            // Retrieve the actual optimizations to get the correct datasetId
            var actualOpt1 = optimizationResourceClient.get(opt1Id, apiKey, workspaceName, 200);
            var actualOpt2 = optimizationResourceClient.get(opt2Id, apiKey, workspaceName, 200);

            // Filter by dataset1 - use the actual datasetId from the retrieved optimization
            var filter = OptimizationFilter.builder()
                    .field(OptimizationField.DATASET_ID)
                    .operator(Operator.EQUAL)
                    .value(actualOpt1.datasetId().toString())
                    .build();

            var page = optimizationResourceClient.find(apiKey, workspaceName, 1, 10,
                    null, null, null, List.of(filter), 200);

            // Should only return optimizations for dataset1
            assertThat(page.content()).isNotEmpty();
            assertThat(page.content()).hasSize(1);
            assertThat(page.content()).allMatch(opt -> opt.datasetId().equals(actualOpt1.datasetId()));
        }

        @Test
        @DisplayName("Filter optimizations by metadata")
        void filterOptimizationsByMetadata() {
            // Create isolated workspace for this test
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Create optimizations with different metadata
            var metadata1 = JsonUtils.getJsonNodeFromString(
                    JsonUtils.writeValueAsString(java.util.Map.of("version", "1.0", "env", "prod")));
            var metadata2 = JsonUtils.getJsonNodeFromString(
                    JsonUtils.writeValueAsString(java.util.Map.of("version", "2.0", "env", "dev")));

            var opt1 = optimizationResourceClient.createPartialOptimization()
                    .metadata(metadata1)
                    .build();
            var opt2 = optimizationResourceClient.createPartialOptimization()
                    .metadata(metadata2)
                    .build();

            optimizationResourceClient.create(opt1, apiKey, workspaceName);
            optimizationResourceClient.create(opt2, apiKey, workspaceName);

            // Filter by metadata version = "2.0"
            var filter = OptimizationFilter.builder()
                    .field(OptimizationField.METADATA)
                    .operator(Operator.EQUAL)
                    .key("version")
                    .value("2.0")
                    .build();

            var page = optimizationResourceClient.find(apiKey, workspaceName, 1, 10,
                    null, null, null, List.of(filter), 200);

            // Should only return optimizations with version 2.0
            assertThat(page.content()).isNotEmpty();
            assertThat(page.content()).hasSize(1);
            assertThat(page.content())
                    .allMatch(opt -> opt.metadata() != null && opt.metadata().get("version").asText().equals("2.0"));
        }

        @Test
        @DisplayName("Filter optimizations with multiple filters - status + metadata")
        void filterOptimizationsWithMultipleFilters() {
            // Create isolated workspace for this test
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var studioConfig = createStudioConfig();
            var metadataProd = JsonUtils.getJsonNodeFromString(
                    JsonUtils.writeValueAsString(java.util.Map.of("env", "prod")));
            var metadataDev = JsonUtils.getJsonNodeFromString(
                    JsonUtils.writeValueAsString(java.util.Map.of("env", "dev")));

            // Create optimizations:
            // 1. Studio with INITIALIZED + prod metadata
            var studioInitProdOpt = optimizationResourceClient.createPartialOptimization()
                    .studioConfig(studioConfig)
                    .metadata(metadataProd)
                    .build();
            var studioInitProdId = optimizationResourceClient.create(studioInitProdOpt, apiKey, workspaceName);

            // 2. Regular with INITIALIZED + prod metadata
            var regularInitProdOpt = optimizationResourceClient.createPartialOptimization()
                    .studioConfig(null)
                    .status(OptimizationStatus.INITIALIZED)
                    .metadata(metadataProd)
                    .build();
            var regularInitProdId = optimizationResourceClient.create(regularInitProdOpt, apiKey, workspaceName);

            // 3. Regular with COMPLETED + prod metadata
            var regularCompletedProdOpt = optimizationResourceClient.createPartialOptimization()
                    .studioConfig(null)
                    .status(OptimizationStatus.COMPLETED)
                    .metadata(metadataProd)
                    .build();
            var regularCompletedProdId = optimizationResourceClient.create(regularCompletedProdOpt, apiKey,
                    workspaceName);

            // 4. Regular with COMPLETED + dev metadata
            var regularCompletedDevOpt = optimizationResourceClient.createPartialOptimization()
                    .studioConfig(null)
                    .status(OptimizationStatus.COMPLETED)
                    .metadata(metadataDev)
                    .build();
            optimizationResourceClient.create(regularCompletedDevOpt, apiKey, workspaceName);

            // Test: Filter by status=INITIALIZED AND metadata.env=prod
            // Should return 2 optimizations (studio + regular, both with INITIALIZED + prod)
            var statusFilter = OptimizationFilter.builder()
                    .field(OptimizationField.STATUS)
                    .operator(Operator.EQUAL)
                    .value("initialized")
                    .build();

            var metadataFilter = OptimizationFilter.builder()
                    .field(OptimizationField.METADATA)
                    .operator(Operator.EQUAL)
                    .key("env")
                    .value("prod")
                    .build();

            var page = optimizationResourceClient.find(apiKey, workspaceName, 1, 10,
                    null, null, null, List.of(statusFilter, metadataFilter), 200);

            assertThat(page.content()).hasSize(2);
            assertThat(page.content()).extracting(Optimization::id)
                    .containsExactlyInAnyOrder(studioInitProdId, regularInitProdId);
            assertThat(page.content()).allMatch(opt -> opt.status() == OptimizationStatus.INITIALIZED);
            assertThat(page.content()).allMatch(opt -> opt.metadata().get("env").asText().equals("prod"));

            // Test: Filter by status=COMPLETED AND metadata.env=prod
            // Should return 1 optimization (regular with COMPLETED + prod)
            var completedFilter = OptimizationFilter.builder()
                    .field(OptimizationField.STATUS)
                    .operator(Operator.EQUAL)
                    .value("completed")
                    .build();

            var completedPage = optimizationResourceClient.find(apiKey, workspaceName, 1, 10,
                    null, null, null, List.of(completedFilter, metadataFilter), 200);

            assertThat(completedPage.content()).hasSize(1);
            assertThat(completedPage.content().get(0).id()).isEqualTo(regularCompletedProdId);
            assertThat(completedPage.content().get(0).status()).isEqualTo(OptimizationStatus.COMPLETED);
            assertThat(completedPage.content().get(0).metadata().get("env").asText()).isEqualTo("prod");
        }

        @Test
        @DisplayName("Cancel running Studio optimization sets Redis signal")
        void cancelRunningStudioOptimization__setsRedisSignal() {
            var studioConfig = createStudioConfig();
            var optimization = optimizationResourceClient.createPartialOptimization()
                    .studioConfig(studioConfig)
                    .build();

            var id = optimizationResourceClient.create(optimization, API_KEY, TEST_WORKSPACE_NAME);

            // Update to RUNNING first (simulating the Python worker starting)
            var runningUpdate = OptimizationUpdate.builder()
                    .status(OptimizationStatus.RUNNING)
                    .build();
            optimizationResourceClient.update(id, runningUpdate, API_KEY, TEST_WORKSPACE_NAME, 204);

            // Verify it's running
            var runningOptimization = optimizationResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, 200);
            assertThat(runningOptimization.status()).isEqualTo(OptimizationStatus.RUNNING);

            // Cancel the optimization
            var cancelUpdate = OptimizationUpdate.builder()
                    .status(OptimizationStatus.CANCELLED)
                    .build();
            optimizationResourceClient.update(id, cancelUpdate, API_KEY, TEST_WORKSPACE_NAME, 204);

            // Verify status is CANCELLED
            var cancelledOptimization = optimizationResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, 200);
            assertThat(cancelledOptimization.status()).isEqualTo(OptimizationStatus.CANCELLED);

            // Verify Redis cancellation signal was set
            String cancelKey = "opik:cancel:" + id;
            await().atMost(2, java.util.concurrent.TimeUnit.SECONDS)
                    .pollInterval(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        Boolean exists = redisClient.getBucket(cancelKey).isExists().block();
                        assertThat(exists).isTrue();
                    });
        }

        @Test
        @DisplayName("Cancel INITIALIZED Studio optimization sets Redis signal")
        void cancelInitializedStudioOptimization__setsRedisSignal() {
            var studioConfig = createStudioConfig();
            var optimization = optimizationResourceClient.createPartialOptimization()
                    .studioConfig(studioConfig)
                    .build();

            var id = optimizationResourceClient.create(optimization, API_KEY, TEST_WORKSPACE_NAME);

            // Verify it's INITIALIZED (forced by service layer for Studio optimizations)
            var initialOptimization = optimizationResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, 200);
            assertThat(initialOptimization.status()).isEqualTo(OptimizationStatus.INITIALIZED);

            // Cancel the optimization directly from INITIALIZED
            var cancelUpdate = OptimizationUpdate.builder()
                    .status(OptimizationStatus.CANCELLED)
                    .build();
            optimizationResourceClient.update(id, cancelUpdate, API_KEY, TEST_WORKSPACE_NAME, 204);

            // Verify status is CANCELLED
            var cancelledOptimization = optimizationResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, 200);
            assertThat(cancelledOptimization.status()).isEqualTo(OptimizationStatus.CANCELLED);

            // Verify Redis cancellation signal was set
            String cancelKey = "opik:cancel:" + id;
            await().atMost(2, java.util.concurrent.TimeUnit.SECONDS)
                    .pollInterval(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        Boolean exists = redisClient.getBucket(cancelKey).isExists().block();
                        assertThat(exists).isTrue();
                    });
        }

        @Test
        @DisplayName("Cancel COMPLETED Studio optimization returns error")
        void cancelCompletedStudioOptimization__returnsError() {
            var studioConfig = createStudioConfig();
            var optimization = optimizationResourceClient.createPartialOptimization()
                    .studioConfig(studioConfig)
                    .build();

            var id = optimizationResourceClient.create(optimization, API_KEY, TEST_WORKSPACE_NAME);

            // Update to COMPLETED
            var completedUpdate = OptimizationUpdate.builder()
                    .status(OptimizationStatus.COMPLETED)
                    .build();
            optimizationResourceClient.update(id, completedUpdate, API_KEY, TEST_WORKSPACE_NAME, 204);

            // Verify it's COMPLETED
            var completedOptimization = optimizationResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, 200);
            assertThat(completedOptimization.status()).isEqualTo(OptimizationStatus.COMPLETED);

            // Try to cancel - should fail with 409 Conflict
            var cancelUpdate = OptimizationUpdate.builder()
                    .status(OptimizationStatus.CANCELLED)
                    .build();
            optimizationResourceClient.update(id, cancelUpdate, API_KEY, TEST_WORKSPACE_NAME, 409);

            // Verify status is still COMPLETED
            var stillCompletedOptimization = optimizationResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, 200);
            assertThat(stillCompletedOptimization.status()).isEqualTo(OptimizationStatus.COMPLETED);
        }

        @Test
        @DisplayName("Cancel non-Studio optimization does not set Redis signal")
        void cancelNonStudioOptimization__doesNotSetRedisSignal() {
            // Create a regular (non-Studio) optimization
            var optimization = optimizationResourceClient.createPartialOptimization()
                    .studioConfig(null)
                    .status(OptimizationStatus.RUNNING)
                    .build();

            var id = optimizationResourceClient.create(optimization, API_KEY, TEST_WORKSPACE_NAME);

            // Cancel the optimization
            var cancelUpdate = OptimizationUpdate.builder()
                    .status(OptimizationStatus.CANCELLED)
                    .build();
            optimizationResourceClient.update(id, cancelUpdate, API_KEY, TEST_WORKSPACE_NAME, 204);

            // Verify status is CANCELLED
            var cancelledOptimization = optimizationResourceClient.get(id, API_KEY, TEST_WORKSPACE_NAME, 200);
            assertThat(cancelledOptimization.status()).isEqualTo(OptimizationStatus.CANCELLED);

            // Verify Redis cancellation signal was NOT set (non-Studio optimization)
            String cancelKey = "opik:cancel:" + id;
            Boolean exists = redisClient.getBucket(cancelKey).isExists().block();
            assertThat(exists).isFalse();
        }

        @Test
        @DisplayName("Filter by status returns correct results after status updates (eventual consistency test)")
        void filterByStatusAfterUpdates__eventualConsistency() {
            // This test verifies that filtering by status works correctly even after
            // multiple status updates, which could be affected by ClickHouse eventual consistency
            String apiKey = UUID.randomUUID().toString();
            String workspaceName = "test-workspace-" + UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Create an optimization with INITIALIZED status
            var optimization = optimizationResourceClient.createPartialOptimization()
                    .status(OptimizationStatus.INITIALIZED)
                    .build();
            var optId = optimizationResourceClient.create(optimization, apiKey, workspaceName);

            // Verify initial state: filter by INITIALIZED should return 1
            var initializedFilter = OptimizationFilter.builder()
                    .field(OptimizationField.STATUS)
                    .operator(Operator.EQUAL)
                    .value("initialized")
                    .build();

            var runningFilter = OptimizationFilter.builder()
                    .field(OptimizationField.STATUS)
                    .operator(Operator.EQUAL)
                    .value("running")
                    .build();

            var completedFilter = OptimizationFilter.builder()
                    .field(OptimizationField.STATUS)
                    .operator(Operator.EQUAL)
                    .value("completed")
                    .build();

            var initialPage = optimizationResourceClient.find(apiKey, workspaceName, 1, 10,
                    null, null, null, List.of(initializedFilter), 200);
            assertThat(initialPage.content()).hasSize(1);
            assertThat(initialPage.content().get(0).id()).isEqualTo(optId);
            assertThat(initialPage.content().get(0).status()).isEqualTo(OptimizationStatus.INITIALIZED);

            // Update status to RUNNING
            var updateToRunning = OptimizationUpdate.builder().status(OptimizationStatus.RUNNING).build();
            optimizationResourceClient.update(optId, updateToRunning, apiKey, workspaceName, 204);

            // After update to RUNNING:
            // - Filter by INITIALIZED should return 0
            // - Filter by RUNNING should return 1
            // - Filter by COMPLETED should return 0
            var afterRunningInitPage = optimizationResourceClient.find(apiKey, workspaceName, 1, 10,
                    null, null, null, List.of(initializedFilter), 200);
            assertThat(afterRunningInitPage.content()).isEmpty();

            var afterRunningRunPage = optimizationResourceClient.find(apiKey, workspaceName, 1, 10,
                    null, null, null, List.of(runningFilter), 200);
            assertThat(afterRunningRunPage.content()).hasSize(1);
            assertThat(afterRunningRunPage.content().get(0).id()).isEqualTo(optId);
            assertThat(afterRunningRunPage.content().get(0).status()).isEqualTo(OptimizationStatus.RUNNING);

            var afterRunningCompletedPage = optimizationResourceClient.find(apiKey, workspaceName, 1, 10,
                    null, null, null, List.of(completedFilter), 200);
            assertThat(afterRunningCompletedPage.content()).isEmpty();

            // Update status to COMPLETED
            var updateToCompleted = OptimizationUpdate.builder().status(OptimizationStatus.COMPLETED).build();
            optimizationResourceClient.update(optId, updateToCompleted, apiKey, workspaceName, 204);

            // After update to COMPLETED:
            // - Filter by INITIALIZED should return 0
            // - Filter by RUNNING should return 0
            // - Filter by COMPLETED should return 1
            var afterCompletedInitPage = optimizationResourceClient.find(apiKey, workspaceName, 1, 10,
                    null, null, null, List.of(initializedFilter), 200);
            assertThat(afterCompletedInitPage.content()).isEmpty();

            var afterCompletedRunPage = optimizationResourceClient.find(apiKey, workspaceName, 1, 10,
                    null, null, null, List.of(runningFilter), 200);
            assertThat(afterCompletedRunPage.content()).isEmpty();

            var afterCompletedCompletedPage = optimizationResourceClient.find(apiKey, workspaceName, 1, 10,
                    null, null, null, List.of(completedFilter), 200);
            assertThat(afterCompletedCompletedPage.content()).hasSize(1);
            assertThat(afterCompletedCompletedPage.content().get(0).id()).isEqualTo(optId);
            assertThat(afterCompletedCompletedPage.content().get(0).status()).isEqualTo(OptimizationStatus.COMPLETED);
        }
    }
}
