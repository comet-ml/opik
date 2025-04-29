package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.ExperimentType;
import com.comet.opik.api.FeedbackScoreAverage;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.Project;
import com.comet.opik.api.Trace;
import com.comet.opik.api.events.OptimizationCreated;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class OptimizationsResourceTest {

    public static final String[] OPTIMIZATION_IGNORED_FIELDS = {"datasetId", "createdAt",
            "lastUpdatedAt", "createdBy", "lastUpdatedBy"};

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = "workspace" + RandomStringUtils.secure().nextAlphanumeric(36);
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

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);
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

        optimizationResourceClient.create(API_KEY, TEST_WORKSPACE);

        ArgumentCaptor<OptimizationCreated> experimentCaptor = ArgumentCaptor.forClass(OptimizationCreated.class);
        Mockito.verify(defaultEventBus).post(experimentCaptor.capture());
    }

    @Nested
    @DisplayName("Get optimizer by id")
    class GetOptimizerById {

        @Test
        @DisplayName("Get optimizer by id")
        void getById() {
            var optimization = optimizationResourceClient.createPartialOptimization().build();

            var id = optimizationResourceClient.create(optimization, API_KEY, TEST_WORKSPACE);

            var actualOptimization = optimizationResourceClient.get(id, API_KEY, TEST_WORKSPACE, 200);

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
            datasetResourceClient.createDataset(dataset, API_KEY, TEST_WORKSPACE);

            List<DatasetItem> items = PodamFactoryUtils.manufacturePojoList(podamFactory, DatasetItem.class);
            DatasetItemBatch itemBatch = new DatasetItemBatch(null, dataset.id(), items);

            datasetResourceClient.createDatasetItems(itemBatch, TEST_WORKSPACE, API_KEY);

            // Create experiment and attach it to the created dataset and optimizer
            List<FeedbackScoreAverage> feedbackScoreItems = PodamFactoryUtils.manufacturePojoList(podamFactory,
                    FeedbackScoreAverage.class);

            var optimization = optimizationResourceClient.createPartialOptimization()
                    .datasetId(dataset.id())
                    .objectiveName(feedbackScoreItems.getFirst().name())
                    .build();

            var id = optimizationResourceClient.create(optimization, API_KEY, TEST_WORKSPACE);

            Experiment experiment = experimentResourceClient.createPartialExperiment()
                    .datasetId(dataset.id())
                    .optimizationId(optimization.id())
                    .datasetName(dataset.name())
                    .type(ExperimentType.TRIAL)
                    .build();

            experimentResourceClient.create(experiment, API_KEY, TEST_WORKSPACE);

            Project project = podamFactory.manufacturePojo(Project.class).toBuilder()
                    .name("Experiment-%s".formatted(dataset.name()))
                    .build();

            projectResourceClient.createProject(project, API_KEY, TEST_WORKSPACE);

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

            traceResourceClient.batchCreateTraces(traces, API_KEY, TEST_WORKSPACE);
            experimentResourceClient.createExperimentItem(experimentItems, API_KEY, TEST_WORKSPACE);

            List<FeedbackScoreBatchItem> scoreBatchItems = traces.stream()
                    .flatMap(trace -> feedbackScoreItems.stream()
                            .map(score -> podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                                    .projectName(project.name())
                                    .id(trace.id())
                                    .name(score.name())
                                    .build()))
                    .toList();

            traceResourceClient.feedbackScores(scoreBatchItems, API_KEY, TEST_WORKSPACE);

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
                    .build();

            // then
            var actualOptimization = optimizationResourceClient.get(id, API_KEY, TEST_WORKSPACE, 200);

            assertThat(actualOptimization)
                    .usingRecursiveComparison()
                    .ignoringFields(OPTIMIZATION_IGNORED_FIELDS)
                    .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                    .ignoringCollectionOrder()
                    .isEqualTo(optimization);
        }

    }

}