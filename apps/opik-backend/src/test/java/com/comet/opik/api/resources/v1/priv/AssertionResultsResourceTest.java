package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.AssertionResultBatchItem;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Span;
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
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
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
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
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
    private SpanResourceClient spanResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        var baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, TEST_WORKSPACE, WORKSPACE_ID, USER);

        this.traceResourceClient = new TraceResourceClient(client, baseURI);
        this.spanResourceClient = new SpanResourceClient(client, baseURI);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Test
    @DisplayName("POST /v1/private/traces/assertion-results stores assertion results and returns 204")
    void saveBatchOfTraceAssertionResults() {
        var trace = factory.manufacturePojo(Trace.class).toBuilder()
                .id(null)
                .projectName(DEFAULT_PROJECT)
                .feedbackScores(null)
                .usage(null)
                .build();
        var traceId = traceResourceClient.createTrace(trace, API_KEY, TEST_WORKSPACE);

        var items = List.of(
                AssertionResultBatchItem.builder()
                        .id(traceId)
                        .projectName(DEFAULT_PROJECT)
                        .name("assertion-grounded")
                        .passed(true)
                        .reason("grounded in context")
                        .source(ScoreSource.SDK)
                        .build(),
                AssertionResultBatchItem.builder()
                        .id(traceId)
                        .projectName(DEFAULT_PROJECT)
                        .name("assertion-concise")
                        .passed(false)
                        .reason(null)
                        .source(ScoreSource.ONLINE_SCORING)
                        .build());

        traceResourceClient.assertionResults(items, API_KEY, TEST_WORKSPACE);
    }

    @Test
    @DisplayName("POST /v1/private/spans/assertion-results stores assertion results and returns 204")
    void saveBatchOfSpanAssertionResults() {
        var span = factory.manufacturePojo(Span.class).toBuilder()
                .id(null)
                .projectName(DEFAULT_PROJECT)
                .feedbackScores(null)
                .usage(null)
                .totalEstimatedCost(null)
                .build();
        var spanId = spanResourceClient.createSpan(span, API_KEY, TEST_WORKSPACE);

        var items = List.of(
                AssertionResultBatchItem.builder()
                        .id(spanId)
                        .projectName(DEFAULT_PROJECT)
                        .name("assertion-relevance")
                        .passed(true)
                        .reason("matches spec")
                        .source(ScoreSource.SDK)
                        .build());

        spanResourceClient.assertionResults(items, API_KEY, TEST_WORKSPACE);
    }

    @Test
    @DisplayName("empty assertion_results list is rejected with 422")
    void emptyBatchIsRejected() {
        try (var response = traceResourceClient.callAssertionResults(List.of(), API_KEY, TEST_WORKSPACE)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
        }
    }

    @Test
    @DisplayName("missing name on item is rejected with 422")
    void missingNameIsRejected() {
        var item = AssertionResultBatchItem.builder()
                .id(UUID.randomUUID())
                .projectName(DEFAULT_PROJECT)
                .name("")
                .passed(true)
                .source(ScoreSource.SDK)
                .build();

        try (var response = traceResourceClient.callAssertionResults(List.of(item), API_KEY, TEST_WORKSPACE)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
        }
    }

    @Test
    @DisplayName("non-v7 UUID id is rejected")
    void nonV7UuidIsRejected() {
        var item = AssertionResultBatchItem.builder()
                .id(UUID.randomUUID()) // default random UUID is v4, not v7
                .projectName(DEFAULT_PROJECT)
                .name("assertion-x")
                .passed(true)
                .source(ScoreSource.SDK)
                .build();

        try (var response = traceResourceClient.callAssertionResults(List.of(item), API_KEY, TEST_WORKSPACE)) {
            assertThat(response.getStatus()).isBetween(400, 499);
        }
    }
}
