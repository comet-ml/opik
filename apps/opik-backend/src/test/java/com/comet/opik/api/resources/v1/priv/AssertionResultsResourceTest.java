package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.AssertionResult;
import com.comet.opik.api.AssertionResultBatchItem;
import com.comet.opik.api.AssertionStatus;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.EvaluationMethod;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.AssertionResultsResourceClient;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.EntityType;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.ProjectService.DEFAULT_PROJECT;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Assertion Results Endpoint Test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class AssertionResultsResourceTest {

    private static final String API_KEY = randomUUID().toString();
    private static final String USER = randomUUID().toString();
    private static final String WORKSPACE_ID = randomUUID().toString();
    private static final String TEST_WORKSPACE = randomUUID().toString();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MYSQL_CONTAINER, CLICK_HOUSE_CONTAINER, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICK_HOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL_CONTAINER);
        MigrationUtils.runClickhouseDbMigration(CLICK_HOUSE_CONTAINER);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL_CONTAINER.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private TraceResourceClient traceResourceClient;
    private DatasetResourceClient datasetResourceClient;
    private ExperimentResourceClient experimentResourceClient;
    private AssertionResultsResourceClient assertionResultsResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        var baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, TEST_WORKSPACE, WORKSPACE_ID, USER);

        this.traceResourceClient = new TraceResourceClient(client, baseURI);
        this.datasetResourceClient = new DatasetResourceClient(client, baseURI);
        this.experimentResourceClient = new ExperimentResourceClient(client, baseURI, factory);
        this.assertionResultsResourceClient = new AssertionResultsResourceClient(client, baseURI);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Test
    @DisplayName("Trace assertion results are persisted and retrievable via the experiment-items API")
    void traceAssertionsArePersistedAndRetrievableViaExperimentItems() {
        var trace = factory.manufacturePojo(Trace.class).toBuilder()
                .id(null)
                .projectName(DEFAULT_PROJECT)
                .feedbackScores(null)
                .usage(null)
                .build();
        UUID traceId = traceResourceClient.createTrace(trace, API_KEY, TEST_WORKSPACE);

        String experimentName = setUpExperimentItemForTrace(traceId, DEFAULT_PROJECT);

        var items = List.of(
                AssertionResultBatchItem.builder()
                        .entityId(traceId)
                        .projectName(DEFAULT_PROJECT)
                        .name("assertion-grounded")
                        .status(AssertionStatus.PASSED)
                        .reason("grounded in context")
                        .source(ScoreSource.SDK)
                        .build(),
                AssertionResultBatchItem.builder()
                        .entityId(traceId)
                        .projectName(DEFAULT_PROJECT)
                        .name("assertion-concise")
                        .status(AssertionStatus.FAILED)
                        .reason(null)
                        .source(ScoreSource.ONLINE_SCORING)
                        .build());

        assertionResultsResourceClient.store(EntityType.TRACE, items, API_KEY, TEST_WORKSPACE);

        List<ExperimentItem> retrievedItems = experimentResourceClient.getExperimentItems(
                experimentName, API_KEY, TEST_WORKSPACE);
        assertThat(retrievedItems).hasSize(1);

        // Empty-string reason is the DAO normalisation for null/blank input — see AssertionResultDAOImpl.getValueOrDefault.
        assertThat(retrievedItems.getFirst().assertionResults()).containsExactlyInAnyOrder(
                AssertionResult.builder()
                        .value("assertion-grounded")
                        .passed(true)
                        .reason("grounded in context")
                        .build(),
                AssertionResult.builder()
                        .value("assertion-concise")
                        .passed(false)
                        .reason("")
                        .build());
    }

    @Test
    @DisplayName("Multi-project batch resolves projects independently and persists rows under each project")
    void multiProjectBatchResolvesIndependently() {
        String projectA = "assertion-multi-project-a-" + randomUUID();
        String projectB = "assertion-multi-project-b-" + randomUUID();

        var traceA = factory.manufacturePojo(Trace.class).toBuilder()
                .id(null)
                .projectName(projectA)
                .feedbackScores(null)
                .usage(null)
                .build();
        UUID traceAId = traceResourceClient.createTrace(traceA, API_KEY, TEST_WORKSPACE);

        var traceB = factory.manufacturePojo(Trace.class).toBuilder()
                .id(null)
                .projectName(projectB)
                .feedbackScores(null)
                .usage(null)
                .build();
        UUID traceBId = traceResourceClient.createTrace(traceB, API_KEY, TEST_WORKSPACE);

        String experimentNameA = setUpExperimentItemForTrace(traceAId, projectA);
        String experimentNameB = setUpExperimentItemForTrace(traceBId, projectB);

        var items = List.of(
                AssertionResultBatchItem.builder()
                        .entityId(traceAId)
                        .projectName(projectA)
                        .name("assertion-cross-project")
                        .status(AssertionStatus.PASSED)
                        .source(ScoreSource.SDK)
                        .build(),
                AssertionResultBatchItem.builder()
                        .entityId(traceBId)
                        .projectName(projectB)
                        .name("assertion-cross-project")
                        .status(AssertionStatus.FAILED)
                        .source(ScoreSource.SDK)
                        .build());

        assertionResultsResourceClient.store(EntityType.TRACE, items, API_KEY, TEST_WORKSPACE);

        List<ExperimentItem> projectAItems = experimentResourceClient.getExperimentItems(
                experimentNameA, API_KEY, TEST_WORKSPACE);
        assertThat(projectAItems).hasSize(1);
        assertThat(projectAItems.getFirst().assertionResults()).containsExactly(
                AssertionResult.builder()
                        .value("assertion-cross-project")
                        .passed(true)
                        .reason("")
                        .build());

        List<ExperimentItem> projectBItems = experimentResourceClient.getExperimentItems(
                experimentNameB, API_KEY, TEST_WORKSPACE);
        assertThat(projectBItems).hasSize(1);
        assertThat(projectBItems.getFirst().assertionResults()).containsExactly(
                AssertionResult.builder()
                        .value("assertion-cross-project")
                        .passed(false)
                        .reason("")
                        .build());
    }

    @ParameterizedTest(name = "entity_type={0} is rejected with 400 (only TRACE/SPAN are supported)")
    @EnumSource(value = EntityType.class, mode = EnumSource.Mode.EXCLUDE, names = {"TRACE", "SPAN"})
    @DisplayName("Unsupported entity_type is rejected with 400 by the service-level guard")
    void unsupportedEntityTypeIsRejected(EntityType entityType) {
        var item = AssertionResultBatchItem.builder()
                .entityId(UUID.randomUUID())
                .projectName(DEFAULT_PROJECT)
                .name("assertion-x")
                .status(AssertionStatus.PASSED)
                .source(ScoreSource.SDK)
                .build();

        try (var response = assertionResultsResourceClient.callStore(entityType, List.of(item), API_KEY,
                TEST_WORKSPACE)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
        }
    }

    @Test
    @DisplayName("non-v7 UUID entity_id is rejected with 400 (IdGenerator.validateVersion)")
    void nonV7EntityIdIsRejected() {
        var item = AssertionResultBatchItem.builder()
                .entityId(UUID.randomUUID()) // default random UUID is v4, not v7
                .projectName(DEFAULT_PROJECT)
                .name("assertion-x")
                .status(AssertionStatus.PASSED)
                .source(ScoreSource.SDK)
                .build();

        try (var response = assertionResultsResourceClient.callStore(EntityType.TRACE, List.of(item), API_KEY,
                TEST_WORKSPACE)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
        }
    }

    /**
     * Sets up a TEST_SUITE experiment with one dataset item linked to the given trace, so that
     * assertion results posted for the trace surface through the experiment-items read API.
     * Returns the experiment name used to retrieve the items later.
     */
    private String setUpExperimentItemForTrace(UUID traceId, String projectName) {
        var dataset = DatasetResourceClient.buildDataset(factory).toBuilder()
                .id(null)
                .build();
        UUID datasetId = datasetResourceClient.createDataset(dataset, API_KEY, TEST_WORKSPACE);

        var datasetItem = DatasetResourceClient.buildDatasetItem(factory).toBuilder()
                .datasetId(datasetId)
                .traceId(traceId)
                .spanId(null)
                .source(DatasetItemSource.TRACE)
                .build();
        datasetResourceClient.createDatasetItems(
                DatasetItemBatch.builder().datasetId(datasetId).items(List.of(datasetItem)).build(),
                TEST_WORKSPACE, API_KEY);

        var experiment = experimentResourceClient.createPartialExperiment()
                .datasetId(datasetId)
                .datasetName(dataset.name())
                .evaluationMethod(EvaluationMethod.TEST_SUITE)
                .build();
        experimentResourceClient.create(experiment, API_KEY, TEST_WORKSPACE);

        var experimentItem = ExperimentItem.builder()
                .id(factory.manufacturePojo(UUID.class))
                .experimentId(experiment.id())
                .datasetItemId(datasetItem.id())
                .traceId(traceId)
                .build();
        experimentResourceClient.createExperimentItem(Set.of(experimentItem), API_KEY, TEST_WORKSPACE);

        return experiment.name();
    }
}
