package com.comet.opik.domain;

import com.comet.opik.api.DeleteFeedbackScore;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.util.List;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.ProjectService.DEFAULT_PROJECT;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

@DisplayName("Feedback Score Deletion with Author Bug Test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
public class FeedbackScoreDeletionWithAuthorTest {
    private static final String API_KEY = randomUUID().toString();
    private static final String USER = randomUUID().toString();
    private static final String WORKSPACE_ID = randomUUID().toString();
    private static final String TEST_WORKSPACE = randomUUID().toString();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer<?> MYSQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
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
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(MYSQL_CONTAINER.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(REDIS.getRedisURI())
                        .customConfigs(List.of(
                                // Set writeToAuthored to false to test the bug scenario
                                new TestDropwizardAppExtensionUtils.CustomConfig("feedbackScores.writeToAuthored",
                                        "false")))
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private TraceResourceClient traceResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        var baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, TEST_WORKSPACE, WORKSPACE_ID, USER);

        this.traceResourceClient = new TraceResourceClient(client, baseURI);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Test
    @DisplayName("delete trace feedback score with author when writeToAuthored is disabled should work")
    void deleteFeedbackScoreWithAuthorWhenWriteToAuthoredDisabled() {
        // Create a trace
        var trace = factory.manufacturePojo(com.comet.opik.api.Trace.class).toBuilder()
                .id(null)
                .projectName(DEFAULT_PROJECT)
                .usage(null)
                .feedbackScores(null)
                .build();
        var traceId = traceResourceClient.createTrace(trace, API_KEY, TEST_WORKSPACE);

        // Create a feedback score
        var score = FeedbackScore.builder()
                .name("test_score")
                .value(BigDecimal.valueOf(0.8))
                .source(ScoreSource.UI)
                .build();
        traceResourceClient.feedbackScore(traceId, score, TEST_WORKSPACE, API_KEY);

        // Verify the score was created
        var traceWithScore = traceResourceClient.getById(traceId, TEST_WORKSPACE, API_KEY);
        assertThat(traceWithScore.feedbackScores()).hasSize(1);
        assertThat(traceWithScore.feedbackScores().getFirst().name()).isEqualTo("test_score");

        // Try to delete the score WITH an author (this is where the bug occurs)
        // When writeToAuthored is disabled, this should still work
        var deleteRequest = DeleteFeedbackScore.builder()
                .name("test_score")
                .author(USER)  // This is the key part - including author in the delete request
                .build();

        traceResourceClient.deleteTraceFeedbackScore(deleteRequest, traceId, API_KEY, TEST_WORKSPACE);

        // Verify the score was actually deleted
        var traceAfterDeletion = traceResourceClient.getById(traceId, TEST_WORKSPACE, API_KEY);
        assertThat(traceAfterDeletion.feedbackScores()).isNull();
    }

    @Test
    @DisplayName("delete trace feedback score without author when writeToAuthored is disabled should work")
    void deleteFeedbackScoreWithoutAuthorWhenWriteToAuthoredDisabled() {
        // Create a trace
        var trace = factory.manufacturePojo(com.comet.opik.api.Trace.class).toBuilder()
                .id(null)
                .projectName(DEFAULT_PROJECT)
                .usage(null)
                .feedbackScores(null)
                .build();
        var traceId = traceResourceClient.createTrace(trace, API_KEY, TEST_WORKSPACE);

        // Create a feedback score
        var score = FeedbackScore.builder()
                .name("test_score_2")
                .value(BigDecimal.valueOf(0.9))
                .source(ScoreSource.UI)
                .build();
        traceResourceClient.feedbackScore(traceId, score, TEST_WORKSPACE, API_KEY);

        // Verify the score was created
        var traceWithScore = traceResourceClient.getById(traceId, TEST_WORKSPACE, API_KEY);
        assertThat(traceWithScore.feedbackScores()).hasSize(1);
        assertThat(traceWithScore.feedbackScores().getFirst().name()).isEqualTo("test_score_2");

        // Try to delete the score WITHOUT an author (this should work before and after the fix)
        var deleteRequest = DeleteFeedbackScore.builder()
                .name("test_score_2")
                // No author specified
                .build();

        traceResourceClient.deleteTraceFeedbackScore(deleteRequest, traceId, API_KEY, TEST_WORKSPACE);

        // Verify the score was deleted
        var traceAfterDeletion = traceResourceClient.getById(traceId, TEST_WORKSPACE, API_KEY);
        assertThat(traceAfterDeletion.feedbackScores()).isNull();
    }
}
