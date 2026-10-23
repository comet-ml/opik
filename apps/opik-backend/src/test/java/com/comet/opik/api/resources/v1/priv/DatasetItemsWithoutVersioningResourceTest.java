package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.FeedbackScoreAssertionUtils.assertFeedbackScores;
import static com.comet.opik.api.resources.utils.FeedbackScoreAssertionUtils.expectedFeedbackScore;
import static com.comet.opik.api.resources.utils.WireMockUtils.WireMockRuntime;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Dataset items with experiment items, dataset versioning disabled")
@ExtendWith(DropwizardAppExtensionProvider.class)
class DatasetItemsWithoutVersioningResourceTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER_CONTAINER);

    private final WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MYSQL, CLICKHOUSE, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICKHOUSE, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(REDIS.getRedisURI())
                        .customConfigs(List.of(
                                new CustomConfig("serviceToggles.datasetVersioningEnabled", "false")))
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private DatasetResourceClient datasetResourceClient;
    private ExperimentResourceClient experimentResourceClient;
    private TraceResourceClient traceResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        var baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);

        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, TEST_WORKSPACE, WORKSPACE_ID, USER);

        datasetResourceClient = new DatasetResourceClient(client, baseURI);
        experimentResourceClient = new ExperimentResourceClient(client, baseURI, factory);
        traceResourceClient = new TraceResourceClient(client, baseURI);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Test
    @DisplayName("when a score has an evaluator revision, then the compare view returns it on the score and its entry")
    void findWithExperimentItems__whenEvaluatorRevisionIsSet__thenReadItBack() {
        var dataset = DatasetResourceClient.buildDataset(factory);
        var datasetId = datasetResourceClient.createDataset(dataset, API_KEY, TEST_WORKSPACE);

        var datasetItem = DatasetResourceClient.buildDatasetItem(factory);
        var batch = DatasetResourceClient.buildDatasetItemBatch(factory).toBuilder()
                .datasetName(null)
                .datasetId(datasetId)
                .items(List.of(datasetItem))
                .build();
        datasetResourceClient.createDatasetItems(batch, TEST_WORKSPACE, API_KEY);

        var trace = factory.manufacturePojo(Trace.class);
        traceResourceClient.createTrace(trace, API_KEY, TEST_WORKSPACE);

        var experiment = experimentResourceClient.createPartialExperiment()
                .datasetName(dataset.name())
                .build();
        experimentResourceClient.create(experiment, API_KEY, TEST_WORKSPACE);

        var experimentItem = factory.manufacturePojo(ExperimentItem.class).toBuilder()
                .experimentId(experiment.id())
                .datasetItemId(datasetItem.id())
                .traceId(trace.id())
                .build();
        experimentResourceClient.createExperimentItem(Set.of(experimentItem), API_KEY, TEST_WORKSPACE);

        var withRevision = FeedbackScoreBatchItem.builder()
                .id(trace.id())
                .projectName(trace.projectName())
                .name("with_revision")
                .value(BigDecimal.ONE)
                .source(ScoreSource.SDK)
                .evaluatorRevision(RandomStringUtils.secure().nextAlphanumeric(10))
                .build();
        var withoutRevision = withRevision.toBuilder()
                .name("without_revision")
                .evaluatorRevision(null)
                .build();
        traceResourceClient.feedbackScores(List.of(withRevision, withoutRevision), API_KEY, TEST_WORKSPACE);

        var page = datasetResourceClient.getDatasetItemsWithExperimentItems(datasetId, List.of(experiment.id()),
                API_KEY, TEST_WORKSPACE);

        assertThat(page.content()).extracting(DatasetItem::id).containsExactly(datasetItem.id());
        assertFeedbackScores(page.content().getFirst().experimentItems().getFirst().feedbackScores(),
                Stream.of(withRevision, withoutRevision)
                        .map(item -> expectedFeedbackScore(item, USER))
                        .toList());
    }
}
