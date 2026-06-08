package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.metrics.WorkspaceMetricsSummaryResponse;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.AiSpendResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.spend.SpendCompositionResponse;
import com.comet.opik.api.spend.SpendMetricRequest;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.utils.JsonUtils;
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
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.podam.PodamFactoryUtils.newPodamFactory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
@DisplayName("AI Spend Resource Test")
class AiSpendResourceTest {

    private static final String USER = UUID.randomUUID().toString();
    private static final BigDecimal SPAN_COST = BigDecimal.valueOf(2.0);

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, CLICKHOUSE_CONTAINER, MYSQL, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE_CONTAINER);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final PodamFactory factory = newPodamFactory();

    private String baseURI;
    private ProjectResourceClient projectResourceClient;
    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;
    private AiSpendResourceClient aiSpendResourceClient;
    private IdGenerator idGenerator;

    @BeforeAll
    void setUpAll(ClientSupport client, IdGenerator idGenerator) throws SQLException {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.projectResourceClient = new ProjectResourceClient(client, baseURI, factory);
        this.traceResourceClient = new TraceResourceClient(client, baseURI);
        this.spanResourceClient = new SpanResourceClient(client, baseURI);
        this.aiSpendResourceClient = new AiSpendResourceClient(client, baseURI);
        this.idGenerator = idGenerator;

        ClientSupportUtils.config(client);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Test
    @DisplayName("summary: aggregates spend, messages and users for the current vs previous window")
    void summary_happyPath() {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);

        var projectName = RandomStringUtils.randomAlphabetic(10);
        projectResourceClient.createProject(projectName, apiKey, workspaceName);

        Instant intervalStart = Instant.now().minus(Duration.ofMinutes(10));
        Instant intervalEnd = Instant.now();
        Instant currentTime = intervalStart.plus(Duration.ofMinutes(5));
        Instant previousTime = intervalStart.minus(Duration.ofMinutes(5));

        var userA = UUID.randomUUID().toString();
        var userB = UUID.randomUUID().toString();
        var userC = UUID.randomUUID().toString();

        // current window: 3 traces, 2 distinct users (A, A, B)
        createCcTraces(projectName, apiKey, workspaceName, currentTime, List.of(userA, userA, userB));
        // previous window: 1 trace, user C
        createCcTraces(projectName, apiKey, workspaceName, previousTime, List.of(userC));

        // 4 current spans + 2 previous spans, each costing SPAN_COST
        createCostSpans(projectName, apiKey, workspaceName, currentTime, 4);
        createCostSpans(projectName, apiKey, workspaceName, previousTime, 2);

        var summary = aiSpendResourceClient.getSummary(SpendMetricRequest.builder()
                .projectName(projectName)
                .intervalStart(intervalStart)
                .intervalEnd(intervalEnd)
                .build(), apiKey, workspaceName);

        Map<String, WorkspaceMetricsSummaryResponse.Result> byName = summary.results().stream()
                .collect(Collectors.toMap(WorkspaceMetricsSummaryResponse.Result::name, Function.identity()));

        assertThat(byName.get("total_spend").current()).isCloseTo(8.0, within(0.0001));
        assertThat(byName.get("total_spend").previous()).isCloseTo(4.0, within(0.0001));
        assertThat(byName.get("total_messages").current()).isEqualTo(3.0);
        assertThat(byName.get("total_messages").previous()).isEqualTo(1.0);
        assertThat(byName.get("active_users").current()).isEqualTo(2.0);
        assertThat(byName.get("total_users").current()).isEqualTo(3.0);
        assertThat(byName.get("avg_cost_per_user").current()).isCloseTo(4.0, within(0.0001));
    }

    @Test
    @DisplayName("composition: sums cc.* token lanes from traces and total cost from spans")
    void composition_happyPath() {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);

        var projectName = RandomStringUtils.randomAlphabetic(10);
        projectResourceClient.createProject(projectName, apiKey, workspaceName);

        Instant intervalStart = Instant.now().minus(Duration.ofMinutes(10));
        Instant intervalEnd = Instant.now();
        Instant currentTime = intervalStart.plus(Duration.ofMinutes(5));

        var user = UUID.randomUUID().toString();
        createCcTraces(projectName, apiKey, workspaceName, currentTime, List.of(user, user, user));
        createCostSpans(projectName, apiKey, workspaceName, currentTime, 4);

        var composition = aiSpendResourceClient.getComposition(SpendMetricRequest.builder()
                .projectName(projectName)
                .intervalStart(intervalStart)
                .intervalEnd(intervalEnd)
                .build(), apiKey, workspaceName);

        Map<String, Long> inputTokens = composition.input().lanes().stream()
                .collect(Collectors.toMap(SpendCompositionResponse.Lane::key,
                        SpendCompositionResponse.Lane::totalTokens));
        Map<String, Long> outputTokens = composition.output().lanes().stream()
                .collect(Collectors.toMap(SpendCompositionResponse.Lane::key,
                        SpendCompositionResponse.Lane::totalTokens));

        // per-trace token values × 3 traces
        assertThat(inputTokens.get("prior_assistant")).isEqualTo(3000L);
        assertThat(inputTokens.get("tool_results")).isEqualTo(600L);
        assertThat(inputTokens.get("user_prompts")).isEqualTo(150L);
        assertThat(inputTokens.get("skills_loaded")).isEqualTo(2400L);
        assertThat(inputTokens.get("mcp_servers")).isEqualTo(900L);
        assertThat(inputTokens.get("file_attachments")).isEqualTo(120L);
        assertThat(inputTokens.get("static_overhead")).isEqualTo(720L); // (60 + 100 + 80) × 3

        assertThat(outputTokens.get("thinking")).isEqualTo(450L);
        assertThat(outputTokens.get("assistant_text")).isEqualTo(210L);

        assertThat(composition.input().totalTokens()).isEqualTo(7890L);
        assertThat(composition.output().totalTokens()).isEqualTo(660L);

        assertThat(composition.harness()).hasSize(1);
        assertThat(composition.harness().getFirst().key()).isEqualTo("claude_code");
        assertThat(composition.harness().getFirst().totalEstimatedCost().doubleValue()).isCloseTo(8.0, within(0.0001));
    }

    @Test
    @DisplayName("summary: returns zeros when there is no data")
    void summary_emptyData() {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);

        var projectName = RandomStringUtils.randomAlphabetic(10);
        projectResourceClient.createProject(projectName, apiKey, workspaceName);

        var summary = aiSpendResourceClient.getSummary(SpendMetricRequest.builder()
                .projectName(projectName)
                .intervalStart(Instant.now().minus(Duration.ofMinutes(10)))
                .intervalEnd(Instant.now())
                .build(), apiKey, workspaceName);

        Map<String, WorkspaceMetricsSummaryResponse.Result> byName = summary.results().stream()
                .collect(Collectors.toMap(WorkspaceMetricsSummaryResponse.Result::name, Function.identity()));

        assertThat(byName.get("total_messages").current()).isEqualTo(0.0);
        assertThat(byName.get("active_users").current()).isEqualTo(0.0);
    }

    private void createCcTraces(String projectName, String apiKey, String workspaceName, Instant time,
            List<String> userUuids) {
        for (String userUuid : userUuids) {
            Trace trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .id(idGenerator.getTimeOrderedEpoch(time.toEpochMilli()))
                    .projectName(projectName)
                    .startTime(time)
                    .metadata(buildCcMetadata(userUuid))
                    .feedbackScores(null)
                    .build();
            traceResourceClient.createTrace(trace, apiKey, workspaceName);
        }
    }

    private void createCostSpans(String projectName, String apiKey, String workspaceName, Instant time, int count) {
        var spans = java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> factory.manufacturePojo(Span.class).toBuilder()
                        .startTime(time)
                        .id(idGenerator.getTimeOrderedEpoch(time.toEpochMilli()))
                        .projectId(null)
                        .projectName(projectName)
                        .totalEstimatedCost(SPAN_COST)
                        .feedbackScores(null)
                        .build())
                .toList();
        spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);
    }

    private com.fasterxml.jackson.databind.JsonNode buildCcMetadata(String userUuid) {
        String json = """
                {
                  "cc": {
                    "identity": { "user_uuid": "%s" },
                    "prior_assistant": { "summary": { "total_tokens": 1000 } },
                    "tool_results": { "summary": { "total_tokens": 200 } },
                    "user_prompts": { "summary": { "total_tokens": 50 } },
                    "skills": { "summary": { "loaded_tokens": 800, "menu_tokens": 100 } },
                    "tools": { "summary": { "by_source": { "mcp": { "schema_tokens": 300 }, "builtin": { "schema_tokens": 80 } } } },
                    "file_attachments": { "summary": { "total_tokens": 40 } },
                    "memory": { "summary": { "total_tokens": 60 } },
                    "thinking": { "summary": { "total_tokens": 150 } },
                    "assistant_text": { "summary": { "total_tokens": 70 } }
                  }
                }
                """
                .formatted(userUuid);
        return JsonUtils.getJsonNodeFromString(json);
    }
}
