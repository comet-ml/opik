package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.AssertionResultBatch;
import com.comet.opik.api.AssertionResultBatchItem;
import com.comet.opik.api.AssertionStatus;
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
import com.comet.opik.api.resources.utils.resources.AssertionResultsResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.EntityType;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
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
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
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

    private ClientSupport client;
    private String baseURI;
    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;
    private AssertionResultsResourceClient assertionResultsResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.client = client;
        this.baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, TEST_WORKSPACE, WORKSPACE_ID, USER);

        this.traceResourceClient = new TraceResourceClient(client, baseURI);
        this.spanResourceClient = new SpanResourceClient(client, baseURI);
        this.assertionResultsResourceClient = new AssertionResultsResourceClient(client, baseURI);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Test
    @DisplayName("PUT /v1/private/assertion-results stores trace assertion results and returns 204")
    void storeTraceAssertionsBatch() {
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
                        .passed(AssertionStatus.PASSED)
                        .reason("grounded in context")
                        .source(ScoreSource.SDK)
                        .build(),
                AssertionResultBatchItem.builder()
                        .id(traceId)
                        .projectName(DEFAULT_PROJECT)
                        .name("assertion-concise")
                        .passed(AssertionStatus.FAILED)
                        .reason(null)
                        .source(ScoreSource.ONLINE_SCORING)
                        .build());

        assertionResultsResourceClient.store(EntityType.TRACE, items, API_KEY, TEST_WORKSPACE);
    }

    @Test
    @DisplayName("PUT /v1/private/assertion-results stores span assertion results and returns 204")
    void storeSpanAssertionsBatch() {
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
                        .passed(AssertionStatus.PASSED)
                        .reason("matches spec")
                        .source(ScoreSource.SDK)
                        .build());

        assertionResultsResourceClient.store(EntityType.SPAN, items, API_KEY, TEST_WORKSPACE);
    }

    @Test
    @DisplayName("missing entity_type is rejected with 422")
    void missingEntityTypeIsRejected() {
        var item = traceItemWithRandomV7Id();
        var batch = AssertionResultBatch.builder()
                .entityType(null)
                .assertionResults(List.of(item))
                .build();

        try (var response = assertionResultsResourceClient.callStore(batch, API_KEY, TEST_WORKSPACE)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
        }
    }

    @Test
    @DisplayName("entity_type=THREAD is rejected with 400")
    void threadEntityTypeIsRejected() {
        var item = traceItemWithRandomV7Id();

        try (var response = assertionResultsResourceClient.callStore(EntityType.THREAD, List.of(item), API_KEY,
                TEST_WORKSPACE)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
        }
    }

    @Test
    @DisplayName("empty assertion_results list is rejected with 422")
    void emptyBatchIsRejected() {
        try (var response = assertionResultsResourceClient.callStore(EntityType.TRACE, List.of(), API_KEY,
                TEST_WORKSPACE)) {
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
                .passed(AssertionStatus.PASSED)
                .source(ScoreSource.SDK)
                .build();

        try (var response = assertionResultsResourceClient.callStore(EntityType.TRACE, List.of(item), API_KEY,
                TEST_WORKSPACE)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
        }
    }

    @Test
    @DisplayName("missing passed on item is rejected with 422")
    void missingPassedIsRejected() {
        var item = AssertionResultBatchItem.builder()
                .id(UUID.randomUUID())
                .projectName(DEFAULT_PROJECT)
                .name("assertion-x")
                .passed(null)
                .source(ScoreSource.SDK)
                .build();

        try (var response = assertionResultsResourceClient.callStore(EntityType.TRACE, List.of(item), API_KEY,
                TEST_WORKSPACE)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
        }
    }

    @Test
    @DisplayName("invalid passed enum value is rejected")
    void invalidPassedEnumIsRejected() {
        // Send a raw JSON body with an unknown 'passed' value to exercise the enum binding boundary.
        var bodyJson = """
                {
                  "entity_type": "trace",
                  "assertion_results": [
                    {
                      "id": "%s",
                      "project_name": "%s",
                      "name": "assertion-x",
                      "passed": "maybe",
                      "source": "sdk"
                    }
                  ]
                }
                """.formatted(UUID.randomUUID(), DEFAULT_PROJECT);

        try (var response = client.target("%s/v1/private/assertion-results".formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, API_KEY)
                .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                .put(Entity.entity(bodyJson, MediaType.APPLICATION_JSON))) {
            assertThat(response.getStatus()).isBetween(400, 499);
        }
    }

    @Test
    @DisplayName("non-v7 UUID id is rejected")
    void nonV7UuidIsRejected() {
        var item = AssertionResultBatchItem.builder()
                .id(UUID.randomUUID()) // default random UUID is v4, not v7
                .projectName(DEFAULT_PROJECT)
                .name("assertion-x")
                .passed(AssertionStatus.PASSED)
                .source(ScoreSource.SDK)
                .build();

        try (var response = assertionResultsResourceClient.callStore(EntityType.TRACE, List.of(item), API_KEY,
                TEST_WORKSPACE)) {
            assertThat(response.getStatus()).isBetween(400, 499);
        }
    }

    @Test
    @DisplayName("multi-project batch resolves projects independently and returns 204")
    void multiProjectBatchResolvesIndependently() {
        var traceA = factory.manufacturePojo(Trace.class).toBuilder()
                .id(null)
                .projectName("assertion-multi-project-a-" + randomUUID())
                .feedbackScores(null)
                .usage(null)
                .build();
        var traceAId = traceResourceClient.createTrace(traceA, API_KEY, TEST_WORKSPACE);

        var traceB = factory.manufacturePojo(Trace.class).toBuilder()
                .id(null)
                .projectName("assertion-multi-project-b-" + randomUUID())
                .feedbackScores(null)
                .usage(null)
                .build();
        var traceBId = traceResourceClient.createTrace(traceB, API_KEY, TEST_WORKSPACE);

        var items = List.of(
                AssertionResultBatchItem.builder()
                        .id(traceAId)
                        .projectName(traceA.projectName())
                        .name("assertion-cross-project")
                        .passed(AssertionStatus.PASSED)
                        .source(ScoreSource.SDK)
                        .build(),
                AssertionResultBatchItem.builder()
                        .id(traceBId)
                        .projectName(traceB.projectName())
                        .name("assertion-cross-project")
                        .passed(AssertionStatus.FAILED)
                        .source(ScoreSource.SDK)
                        .build());

        assertionResultsResourceClient.store(EntityType.TRACE, items, API_KEY, TEST_WORKSPACE);
    }

    private AssertionResultBatchItem traceItemWithRandomV7Id() {
        return AssertionResultBatchItem.builder()
                .id(UUID.randomUUID())
                .projectName(DEFAULT_PROJECT)
                .name("assertion-x")
                .passed(AssertionStatus.PASSED)
                .source(ScoreSource.SDK)
                .build();
    }
}
