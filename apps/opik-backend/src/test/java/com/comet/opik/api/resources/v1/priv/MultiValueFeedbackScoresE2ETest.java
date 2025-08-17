package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.Trace;
import com.comet.opik.api.ValueEntry;
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
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.resources.utils.traces.TraceAssertions;
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
import java.util.Map;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.ProjectService.DEFAULT_PROJECT;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

@DisplayName("Multi-value Feedback Scores Test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
public class MultiValueFeedbackScoresE2ETest {
    private static final String API_KEY1 = randomUUID().toString();
    private static final String USER1 = randomUUID().toString();
    private static final String API_KEY2 = randomUUID().toString();
    private static final String USER2 = randomUUID().toString();
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
                                new TestDropwizardAppExtensionUtils.CustomConfig("feedbackScores.writeToAuthored",
                                        "true")))
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private TraceResourceClient traceResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {

        var baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);
        mockTargetWorkspace(API_KEY1, TEST_WORKSPACE, WORKSPACE_ID, USER1);
        mockTargetWorkspace(API_KEY2, TEST_WORKSPACE, WORKSPACE_ID, USER2);

        this.traceResourceClient = new TraceResourceClient(client, baseURI);
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId, String username) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, username);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Test
    @DisplayName("test score trace by multiple authors")
    void testScoreTraceByMultipleAuthors() {
        var trace = factory.manufacturePojo(Trace.class).toBuilder()
                .id(null)
                .projectName(DEFAULT_PROJECT)
                .usage(null)
                .feedbackScores(null)
                .build();
        var traceId = traceResourceClient.createTrace(trace, API_KEY1, TEST_WORKSPACE);

        var user1Score = factory.manufacturePojo(FeedbackScore.class);
        traceResourceClient.feedbackScore(traceId, user1Score, TEST_WORKSPACE, API_KEY1);

        // simulate another user scoring the same trace by using the same name
        var user2Score = factory.manufacturePojo(FeedbackScore.class).toBuilder()
                .name(user1Score.name()).build();
        traceResourceClient.feedbackScore(traceId, user2Score, TEST_WORKSPACE, API_KEY2);

        var actual = traceResourceClient.getTraces(DEFAULT_PROJECT, null, API_KEY2, TEST_WORKSPACE, null,
                null, 5, Map.of());

        assertThat(actual.content()).hasSize(1);
        assertThat(actual.content().getFirst().feedbackScores()).hasSize(1);
        var actualScore = actual.content().getFirst().feedbackScores().getFirst();

        // assert trace values
        assertThat(actualScore.value()).usingComparator(StatsUtils::bigDecimalComparator)
                .isEqualTo(BigDecimal.valueOf(StatsUtils.avgFromList(List.of(user1Score.value(), user2Score.value()))));
        assertAuthorValue(actualScore.valueByAuthor(), USER1, user1Score);
        assertAuthorValue(actualScore.valueByAuthor(), USER2, user2Score);

        // assert trace stats
        ProjectStats actualStats = traceResourceClient.getTraceStats(DEFAULT_PROJECT, null, API_KEY2,
                TEST_WORKSPACE, null, Map.of());
        TraceAssertions.assertStats(actualStats.stats(), StatsUtils.getProjectTraceStatItems(actual.content()));
    }

    private void assertAuthorValue(Map<String, ValueEntry> valueByAuthor, String author, FeedbackScore expected) {
        assertThat(valueByAuthor.get(author).categoryName()).isEqualTo(expected.categoryName());
        assertThat(valueByAuthor.get(author).value()).isEqualByComparingTo(expected.value());
        assertThat(valueByAuthor.get(author).reason()).isEqualTo(expected.reason());
        assertThat(valueByAuthor.get(author).source()).isEqualTo(expected.source());
    }
}
