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
import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.api.spend.Impact;
import com.comet.opik.api.spend.SpendBreakdownResponse;
import com.comet.opik.api.spend.SpendCompositionResponse;
import com.comet.opik.api.spend.SpendMetricRequest;
import com.comet.opik.api.spend.SpendRecommendationsResponse;
import com.comet.opik.api.spend.SpendUserPage;
import com.comet.opik.api.spend.SpendUserRow;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.hc.core5.http.HttpStatus;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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

        createCcTraces(projectName, apiKey, workspaceName, currentTime, List.of(userA, userA, userB));
        createCcTraces(projectName, apiKey, workspaceName, previousTime, List.of(userC));

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
        createLlmCallSpans(projectName, apiKey, workspaceName, currentTime);

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

        assertThat(inputTokens.get("prior_assistant")).isEqualTo(3000L);
        assertThat(inputTokens.get("tool_results")).isEqualTo(600L);
        assertThat(inputTokens.get("user_prompts")).isEqualTo(150L);
        assertThat(inputTokens.get("skills_available")).isEqualTo(300L);
        assertThat(inputTokens.get("skills_loaded")).isEqualTo(2400L);
        assertThat(inputTokens.get("tools")).isEqualTo(1140L);
        assertThat(inputTokens.get("memory")).isEqualTo(180L);
        assertThat(inputTokens.get("file_attachments")).isEqualTo(120L);

        assertThat(outputTokens.get("thinking")).isEqualTo(150L);
        assertThat(outputTokens.get("assistant_text")).isEqualTo(70L);
        assertThat(outputTokens.get("built_in_tool_calls")).isEqualTo(30L);
        assertThat(outputTokens.get("mcp_tool_calls")).isEqualTo(20L);
        assertThat(outputTokens.get("skill_invocations")).isEqualTo(10L);

        assertThat(composition.input().totalTokens()).isEqualTo(7890L);
        assertThat(composition.output().totalTokens()).isEqualTo(280L);

        assertThat(composition.harness()).hasSize(1);
        assertThat(composition.harness().getFirst().key()).isEqualTo("claude_code");
        assertThat(composition.harness().getFirst().totalEstimatedCost().doubleValue()).isCloseTo(8.0, within(0.0001));
    }

    @Test
    @DisplayName("composition: user_id restricts the result to that user's traces")
    void composition_filtersByUserId() {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);

        var projectName = RandomStringUtils.randomAlphabetic(10);
        projectResourceClient.createProject(projectName, apiKey, workspaceName);

        Instant intervalStart = Instant.now().minus(Duration.ofMinutes(10));
        Instant intervalEnd = Instant.now();
        Instant currentTime = intervalStart.plus(Duration.ofMinutes(5));

        var userA = UUID.randomUUID().toString();
        var userB = UUID.randomUUID().toString();
        createCcTraces(projectName, apiKey, workspaceName, currentTime, List.of(userA, userA, userB));

        var composition = aiSpendResourceClient.getComposition(SpendMetricRequest.builder()
                .projectName(projectName)
                .intervalStart(intervalStart)
                .intervalEnd(intervalEnd)
                .userId(userA)
                .build(), apiKey, workspaceName);

        Map<String, Long> inputTokens = composition.input().lanes().stream()
                .collect(Collectors.toMap(SpendCompositionResponse.Lane::key,
                        SpendCompositionResponse.Lane::totalTokens));
        assertThat(inputTokens.get("prior_assistant")).isEqualTo(2000L);
    }

    @Test
    @DisplayName("composition: rejects interval_start that is not before interval_end")
    void composition_rejectsInvalidInterval() {
        var workspaceName = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, UUID.randomUUID().toString(), USER);

        Instant now = Instant.now();
        aiSpendResourceClient.getComposition(SpendMetricRequest.builder()
                .projectName(RandomStringUtils.randomAlphabetic(10))
                .intervalStart(now)
                .intervalEnd(now.minus(Duration.ofMinutes(1)))
                .build(), apiKey, workspaceName, HttpStatus.SC_UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("composition: rejects request with neither project_id nor project_name")
    void composition_rejectsMissingProject() {
        var workspaceName = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, UUID.randomUUID().toString(), USER);

        aiSpendResourceClient.getComposition(SpendMetricRequest.builder()
                .intervalStart(Instant.now().minus(Duration.ofMinutes(10)))
                .intervalEnd(Instant.now())
                .build(), apiKey, workspaceName, HttpStatus.SC_UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("composition: 404 when the project does not exist")
    void composition_notFoundForUnknownProject() {
        var workspaceName = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, UUID.randomUUID().toString(), USER);

        aiSpendResourceClient.getComposition(SpendMetricRequest.builder()
                .projectName(RandomStringUtils.randomAlphabetic(10))
                .intervalStart(Instant.now().minus(Duration.ofMinutes(10)))
                .intervalEnd(Instant.now())
                .build(), apiKey, workspaceName, HttpStatus.SC_NOT_FOUND);
    }

    @Test
    @DisplayName("breakdown: 400 for an unknown lane key")
    void breakdown_rejectsUnknownLane() {
        var workspaceName = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, UUID.randomUUID().toString(), USER);

        aiSpendResourceClient.getBreakdown("not_a_lane", SpendMetricRequest.builder()
                .projectName(RandomStringUtils.randomAlphabetic(10))
                .intervalStart(Instant.now().minus(Duration.ofMinutes(10)))
                .intervalEnd(Instant.now())
                .build(), apiKey, workspaceName, HttpStatus.SC_BAD_REQUEST);
    }

    @Test
    @DisplayName("users: rejects page below 1")
    void users_rejectsInvalidPage() {
        var workspaceName = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, UUID.randomUUID().toString(), USER);

        aiSpendResourceClient.getUsers(SpendMetricRequest.builder()
                .projectName(RandomStringUtils.randomAlphabetic(10))
                .intervalStart(Instant.now().minus(Duration.ofMinutes(10)))
                .intervalEnd(Instant.now())
                .build(), 0, 25, null, apiKey, workspaceName, HttpStatus.SC_BAD_REQUEST);
    }

    @Test
    @DisplayName("users: rejects size above the cap")
    void users_rejectsSizeAboveMax() {
        var workspaceName = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, UUID.randomUUID().toString(), USER);

        aiSpendResourceClient.getUsers(SpendMetricRequest.builder()
                .projectName(RandomStringUtils.randomAlphabetic(10))
                .intervalStart(Instant.now().minus(Duration.ofMinutes(10)))
                .intervalEnd(Instant.now())
                .build(), 1, 1001, null, apiKey, workspaceName, HttpStatus.SC_BAD_REQUEST);
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

        assertThat(byName.get("total_spend").current()).isEqualTo(0.0);
        assertThat(byName.get("total_messages").current()).isEqualTo(0.0);
        assertThat(byName.get("active_users").current()).isEqualTo(0.0);
        assertThat(byName.get("total_users").current()).isEqualTo(0.0);
        assertThat(byName.get("avg_cost_per_user").current()).isNull();
    }

    @Test
    @DisplayName("breakdown: aggregates a lane's cc.* detail array per item")
    void breakdown_happyPath() {
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

        var breakdown = aiSpendResourceClient.getBreakdown("skills_loaded", SpendMetricRequest.builder()
                .projectName(projectName)
                .intervalStart(intervalStart)
                .intervalEnd(intervalEnd)
                .build(), apiKey, workspaceName);

        assertThat(breakdown.laneKey()).isEqualTo("skills_loaded");
        assertThat(breakdown.title()).isEqualTo("Skills loaded");
        assertThat(breakdown.subtitle()).isEqualTo("Tokens per loaded skill body");
        assertThat(breakdown.itemCount()).isEqualTo(2);
        assertThat(breakdown.totalTokens()).isEqualTo(2400L);

        Map<String, Long> byLabel = breakdown.items().stream()
                .collect(Collectors.toMap(SpendBreakdownResponse.Item::label,
                        SpendBreakdownResponse.Item::totalTokens));
        assertThat(byLabel.get("opik-frontend")).isEqualTo(1500L);
        assertThat(byLabel.get("find-skills")).isEqualTo(900L);
    }

    @Test
    @DisplayName("breakdown: tools lane adds a Built-in row so items sum to the lane total")
    void breakdown_toolsMatchesLaneTotal() {
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

        var breakdown = aiSpendResourceClient.getBreakdown("tools", SpendMetricRequest.builder()
                .projectName(projectName)
                .intervalStart(intervalStart)
                .intervalEnd(intervalEnd)
                .build(), apiKey, workspaceName);

        assertThat(breakdown.laneKey()).isEqualTo("tools");
        assertThat(breakdown.totalTokens()).isEqualTo(1140L);

        Map<String, Long> byLabel = breakdown.items().stream()
                .collect(Collectors.toMap(SpendBreakdownResponse.Item::label,
                        SpendBreakdownResponse.Item::totalTokens));
        assertThat(byLabel.get("chrome-devtools")).isEqualTo(900L);
        assertThat(byLabel.get("Built-in tools")).isEqualTo(240L);
    }

    @Test
    @DisplayName("breakdown: output tool-call lanes group by tool/server/skill and sum to lane total")
    void breakdown_outputToolCalls() {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);

        var projectName = RandomStringUtils.randomAlphabetic(10);
        projectResourceClient.createProject(projectName, apiKey, workspaceName);

        Instant intervalStart = Instant.now().minus(Duration.ofMinutes(10));
        Instant intervalEnd = Instant.now();
        Instant currentTime = intervalStart.plus(Duration.ofMinutes(5));

        createLlmCallSpans(projectName, apiKey, workspaceName, currentTime);

        var builtIn = getBreakdown(projectName, apiKey, workspaceName, intervalStart, intervalEnd,
                "built_in_tool_calls");
        assertThat(builtIn.totalTokens()).isEqualTo(30L);
        assertThat(builtIn.itemCount()).isEqualTo(1);
        assertThat(builtIn.items().getFirst().label()).isEqualTo("Bash");
        assertThat(builtIn.items().getFirst().totalTokens()).isEqualTo(30L);

        var mcp = getBreakdown(projectName, apiKey, workspaceName, intervalStart, intervalEnd, "mcp_tool_calls");
        assertThat(mcp.totalTokens()).isEqualTo(20L);
        assertThat(mcp.items().getFirst().label()).isEqualTo("chrome");

        var skill = getBreakdown(projectName, apiKey, workspaceName, intervalStart, intervalEnd, "skill_invocations");
        assertThat(skill.totalTokens()).isEqualTo(10L);
        assertThat(skill.items().getFirst().label()).isEqualTo("diagram-generation");
    }

    @Test
    @DisplayName("breakdown: thinking and assistant text break down by model")
    void breakdown_outputByModel() {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);

        var projectName = RandomStringUtils.randomAlphabetic(10);
        projectResourceClient.createProject(projectName, apiKey, workspaceName);

        Instant intervalStart = Instant.now().minus(Duration.ofMinutes(10));
        Instant intervalEnd = Instant.now();
        Instant currentTime = intervalStart.plus(Duration.ofMinutes(5));

        createLlmCallSpans(projectName, apiKey, workspaceName, currentTime);

        var thinking = getBreakdown(projectName, apiKey, workspaceName, intervalStart, intervalEnd, "thinking");
        assertThat(thinking.totalTokens()).isEqualTo(150L);
        assertThat(thinking.itemCount()).isEqualTo(1);
        assertThat(thinking.items().getFirst().label()).isEqualTo("claude-opus-4-8");
        assertThat(thinking.items().getFirst().totalTokens()).isEqualTo(150L);

        var text = getBreakdown(projectName, apiKey, workspaceName, intervalStart, intervalEnd, "assistant_text");
        assertThat(text.totalTokens()).isEqualTo(70L);
        assertThat(text.items().getFirst().label()).isEqualTo("claude-opus-4-8");
    }

    @Test
    @DisplayName("breakdown: caps items at the limit and folds the remainder into an Other row")
    void breakdown_truncatesWithOtherRow() {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);

        var projectName = RandomStringUtils.randomAlphabetic(10);
        projectResourceClient.createProject(projectName, apiKey, workspaceName);

        Instant intervalStart = Instant.now().minus(Duration.ofMinutes(10));
        Instant intervalEnd = Instant.now();
        Instant currentTime = intervalStart.plus(Duration.ofMinutes(5));

        createBuiltInToolSpans(projectName, apiKey, workspaceName, currentTime, 201);

        var breakdown = getBreakdown(projectName, apiKey, workspaceName, intervalStart, intervalEnd,
                "built_in_tool_calls");

        assertThat(breakdown.totalTokens()).isEqualTo(201L);
        assertThat(breakdown.itemCount()).isEqualTo(201);
        assertThat(breakdown.items()).hasSize(201);
        assertThat(breakdown.items().stream().mapToLong(SpendBreakdownResponse.Item::totalTokens).sum())
                .isEqualTo(201L);

        var other = breakdown.items().getLast();
        assertThat(other.label()).isEqualTo("Other (1 items)");
        assertThat(other.totalTokens()).isEqualTo(1L);
    }

    @Test
    @DisplayName("breakdown: at exactly the item cap there is no Other row")
    void breakdown_atCapHasNoOtherRow() {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);

        var projectName = RandomStringUtils.randomAlphabetic(10);
        projectResourceClient.createProject(projectName, apiKey, workspaceName);

        Instant intervalStart = Instant.now().minus(Duration.ofMinutes(10));
        Instant intervalEnd = Instant.now();
        Instant currentTime = intervalStart.plus(Duration.ofMinutes(5));

        createBuiltInToolSpans(projectName, apiKey, workspaceName, currentTime, 200);

        var breakdown = getBreakdown(projectName, apiKey, workspaceName, intervalStart, intervalEnd,
                "built_in_tool_calls");

        assertThat(breakdown.totalTokens()).isEqualTo(200L);
        assertThat(breakdown.itemCount()).isEqualTo(200);
        assertThat(breakdown.items()).hasSize(200);
        assertThat(breakdown.items()).noneMatch(item -> item.label().startsWith("Other ("));
        assertThat(breakdown.items().stream().mapToLong(SpendBreakdownResponse.Item::totalTokens).sum())
                .isEqualTo(200L);
    }

    @Test
    @DisplayName("users: ranks users by spend, joins per-user cost, flags high spenders")
    void users_happyPath() {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);

        var projectName = RandomStringUtils.randomAlphabetic(10);
        projectResourceClient.createProject(projectName, apiKey, workspaceName);

        Instant intervalStart = Instant.now().minus(Duration.ofMinutes(10));
        Instant intervalEnd = Instant.now();
        Instant currentTime = intervalStart.plus(Duration.ofMinutes(5));

        var userA = UUID.randomUUID().toString();
        var userB = UUID.randomUUID().toString();

        var userATraces = createCcTraces(projectName, apiKey, workspaceName, currentTime, List.of(userA, userA));
        userATraces.forEach(traceId -> createLinkedSpan(projectName, apiKey, workspaceName, traceId, currentTime));
        createCcTraces(projectName, apiKey, workspaceName, currentTime, List.of(userB));

        SpendUserPage page = aiSpendResourceClient.getUsers(SpendMetricRequest.builder()
                .projectName(projectName)
                .intervalStart(intervalStart)
                .intervalEnd(intervalEnd)
                .build(), 1, 25, apiKey, workspaceName);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.content()).hasSize(2);

        SpendUserRow top = page.content().getFirst();
        assertThat(top.userUuid()).isEqualTo(userA);
        assertThat(top.requests()).isEqualTo(2);
        assertThat(top.totalEstimatedCost().doubleValue()).isCloseTo(4.0, within(0.0001));
        assertThat(top.flags()).contains("high_spend");
        assertThat(top.repositories()).containsExactly("repo-a");
        assertThat(top.skills()).isEqualTo(4L);

        SpendUserRow second = page.content().get(1);
        assertThat(second.userUuid()).isEqualTo(userB);
        assertThat(second.totalEstimatedCost().doubleValue()).isCloseTo(0.0, within(0.0001));
    }

    @Test
    @DisplayName("users: name filter narrows the leaderboard by name/email, case-insensitively")
    void users_filtersByName() {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);

        var projectName = RandomStringUtils.randomAlphabetic(10);
        projectResourceClient.createProject(projectName, apiKey, workspaceName);

        Instant intervalStart = Instant.now().minus(Duration.ofMinutes(10));
        Instant intervalEnd = Instant.now();
        Instant currentTime = intervalStart.plus(Duration.ofMinutes(5));

        var userA = UUID.randomUUID().toString();
        var userB = UUID.randomUUID().toString();
        createCcTraces(projectName, apiKey, workspaceName, currentTime, List.of(userA, userB));

        var request = SpendMetricRequest.builder()
                .projectName(projectName)
                .intervalStart(intervalStart)
                .intervalEnd(intervalEnd)
                .build();

        SpendUserPage match = aiSpendResourceClient.getUsers(request, 1, 25, null, userA, apiKey, workspaceName);
        assertThat(match.total()).isEqualTo(1);
        assertThat(match.content()).extracting(SpendUserRow::userUuid).containsExactly(userA);

        SpendUserPage caseInsensitive = aiSpendResourceClient.getUsers(request, 1, 25, null,
                userA.toUpperCase(), apiKey, workspaceName);
        assertThat(caseInsensitive.content()).extracting(SpendUserRow::userUuid).containsExactly(userA);
    }

    @Test
    @DisplayName("users: honors DB-side sorting and pagination")
    void users_sortingAndPagination() {
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);

        var projectName = RandomStringUtils.randomAlphabetic(10);
        projectResourceClient.createProject(projectName, apiKey, workspaceName);

        Instant intervalStart = Instant.now().minus(Duration.ofMinutes(10));
        Instant intervalEnd = Instant.now();
        Instant currentTime = intervalStart.plus(Duration.ofMinutes(5));

        var userA = UUID.randomUUID().toString();
        var userB = UUID.randomUUID().toString();

        var userATraces = createCcTraces(projectName, apiKey, workspaceName, currentTime, List.of(userA, userA));
        userATraces.forEach(traceId -> createLinkedSpan(projectName, apiKey, workspaceName, traceId, currentTime));
        createCcTraces(projectName, apiKey, workspaceName, currentTime, List.of(userB, userB, userB));

        var request = SpendMetricRequest.builder()
                .projectName(projectName)
                .intervalStart(intervalStart)
                .intervalEnd(intervalEnd)
                .build();

        SpendUserPage bySpend = aiSpendResourceClient.getUsers(request, 1, 25, apiKey, workspaceName);
        assertThat(bySpend.sortableBy())
                .containsExactly("total_estimated_cost", "requests", "skills", "mcps", "mcp_calls");
        assertThat(bySpend.content()).extracting(SpendUserRow::userUuid).containsExactly(userA, userB);

        var byRequestsDesc = List.of(SortingField.builder().field("requests").direction(Direction.DESC).build());
        SpendUserPage byRequests = aiSpendResourceClient.getUsers(request, 1, 25, byRequestsDesc, apiKey,
                workspaceName);
        assertThat(byRequests.content()).extracting(SpendUserRow::userUuid).containsExactly(userB, userA);
        assertThat(byRequests.content().getFirst().requests()).isEqualTo(3);

        SpendUserPage firstPage = aiSpendResourceClient.getUsers(request, 1, 1, byRequestsDesc, apiKey, workspaceName);
        assertThat(firstPage.total()).isEqualTo(2);
        assertThat(firstPage.content()).extracting(SpendUserRow::userUuid).containsExactly(userB);

        SpendUserPage secondPage = aiSpendResourceClient.getUsers(request, 2, 1, byRequestsDesc, apiKey, workspaceName);
        assertThat(secondPage.total()).isEqualTo(2);
        assertThat(secondPage.content()).extracting(SpendUserRow::userUuid).containsExactly(userA);
    }

    @Test
    @DisplayName("recommendations: returns up to 3 judgment-based items for the current usage")
    void recommendations_happyPath() {
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
        createLlmCallSpans(projectName, apiKey, workspaceName, currentTime);

        var recommendations = aiSpendResourceClient.getRecommendations(SpendMetricRequest.builder()
                .projectName(projectName)
                .intervalStart(intervalStart)
                .intervalEnd(intervalEnd)
                .build(), apiKey, workspaceName);

        List<String> ids = recommendations.items().stream()
                .map(SpendRecommendationsResponse.Item::id)
                .toList();
        assertThat(ids).contains("thinking_effort", "fewer_tools");
        assertThat(ids).doesNotContain("compact_threshold");
        assertThat(recommendations.items()).hasSizeLessThanOrEqualTo(3);
        assertThat(recommendations.totalSavings().doubleValue()).isGreaterThan(0.0);

        Map<String, Impact> impacts = recommendations.items().stream()
                .collect(Collectors.toMap(SpendRecommendationsResponse.Item::id,
                        SpendRecommendationsResponse.Item::impact));
        assertThat(impacts.get("thinking_effort")).isEqualTo(Impact.MEDIUM);
        assertThat(impacts.get("fewer_tools")).isEqualTo(Impact.LOW);
    }

    private List<UUID> createCcTraces(String projectName, String apiKey, String workspaceName, Instant time,
            List<String> userUuids) {
        List<UUID> ids = new ArrayList<>();
        for (String userUuid : userUuids) {
            UUID id = idGenerator.getTimeOrderedEpoch(time.toEpochMilli());
            Trace trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .id(id)
                    .projectName(projectName)
                    .startTime(time)
                    .metadata(buildCcMetadata(userUuid))
                    .feedbackScores(null)
                    .build();
            traceResourceClient.createTrace(trace, apiKey, workspaceName);
            ids.add(id);
        }
        return ids;
    }

    private void createLinkedSpan(String projectName, String apiKey, String workspaceName, UUID traceId, Instant time) {
        Span span = factory.manufacturePojo(Span.class).toBuilder()
                .startTime(time)
                .id(idGenerator.getTimeOrderedEpoch(time.toEpochMilli()))
                .traceId(traceId)
                .projectId(null)
                .projectName(projectName)
                .totalEstimatedCost(SPAN_COST)
                .feedbackScores(null)
                .build();
        spanResourceClient.batchCreateSpans(List.of(span), apiKey, workspaceName);
    }

    private void createCostSpans(String projectName, String apiKey, String workspaceName, Instant time, int count) {
        var spans = IntStream.range(0, count)
                .mapToObj(i -> factory.manufacturePojo(Span.class).toBuilder()
                        .startTime(time)
                        .id(idGenerator.getTimeOrderedEpoch(time.toEpochMilli()))
                        .traceId(idGenerator.getTimeOrderedEpoch(time.toEpochMilli()))
                        .projectId(null)
                        .projectName(projectName)
                        .totalEstimatedCost(SPAN_COST)
                        .feedbackScores(null)
                        .build())
                .toList();
        spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);
    }

    private SpendBreakdownResponse getBreakdown(String projectName, String apiKey, String workspaceName,
            Instant intervalStart, Instant intervalEnd, String laneKey) {
        return aiSpendResourceClient.getBreakdown(laneKey, SpendMetricRequest.builder()
                .projectName(projectName)
                .intervalStart(intervalStart)
                .intervalEnd(intervalEnd)
                .build(), apiKey, workspaceName);
    }

    private void createBuiltInToolSpans(String projectName, String apiKey, String workspaceName, Instant time,
            int count) {
        JsonNode metadata = JsonUtils.getJsonNodeFromString(
                "{ \"cc\": { \"llm_call\": { \"block_kind\": \"tool_use\", \"attributed_output_tokens\": 1 } } }");
        List<Span> spans = IntStream.range(0, count)
                .mapToObj(i -> factory.manufacturePojo(Span.class).toBuilder()
                        .startTime(time)
                        .id(idGenerator.getTimeOrderedEpoch(time.toEpochMilli()))
                        .traceId(idGenerator.getTimeOrderedEpoch(time.toEpochMilli()))
                        .name("tool_" + i)
                        .projectId(null)
                        .projectName(projectName)
                        .totalEstimatedCost(BigDecimal.ZERO)
                        .metadata(metadata)
                        .feedbackScores(null)
                        .build())
                .toList();
        spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);
    }

    private void createLlmCallSpans(String projectName, String apiKey, String workspaceName, Instant time) {
        createLlmCallSpan(projectName, apiKey, workspaceName, time, "thinking", "Thinking", 150, null);
        createLlmCallSpan(projectName, apiKey, workspaceName, time, "text", "Text", 70, null);
        createLlmCallSpan(projectName, apiKey, workspaceName, time, "tool_use", "Bash", 30, null);
        createLlmCallSpan(projectName, apiKey, workspaceName, time, "tool_use", "mcp__chrome__click", 20, null);
        createLlmCallSpan(projectName, apiKey, workspaceName, time, "tool_use", "Skill", 10, "diagram-generation");
    }

    private void createLlmCallSpan(String projectName, String apiKey, String workspaceName, Instant time,
            String blockKind, String name, long attributedTokens, String skill) {
        String json = """
                { "cc": { "llm_call": { "block_kind": "%s", "attributed_output_tokens": %d } } }
                """.formatted(blockKind, attributedTokens);
        var builder = factory.manufacturePojo(Span.class).toBuilder()
                .startTime(time)
                .id(idGenerator.getTimeOrderedEpoch(time.toEpochMilli()))
                .traceId(idGenerator.getTimeOrderedEpoch(time.toEpochMilli()))
                .name(name)
                .model("claude-opus-4-8")
                .projectId(null)
                .projectName(projectName)
                .totalEstimatedCost(BigDecimal.ZERO)
                .metadata(JsonUtils.getJsonNodeFromString(json))
                .feedbackScores(null);
        if (skill != null) {
            builder.input(JsonUtils.getJsonNodeFromString("{ \"skill\": \"%s\" }".formatted(skill)));
        }
        spanResourceClient.batchCreateSpans(List.of(builder.build()), apiKey, workspaceName);
    }

    private JsonNode buildCcMetadata(String userUuid) {
        String json = """
                {
                  "cc": {
                    "identity": { "user_uuid": "%1$s", "user_email": "%1$s@comet.com", "user_display_name": "%1$s" },
                    "git": { "repository": "repo-a" },
                    "prior_assistant": { "summary": { "total_tokens": 1000 } },
                    "tool_results": { "summary": { "total_tokens": 200, "count": 8 }, "by_tool": [ { "name": "Bash", "tokens": 200 } ] },
                    "user_prompts": { "summary": { "total_tokens": 50 } },
                    "skills": { "summary": { "loaded_tokens": 800, "menu_tokens": 100, "loaded_count": 2 }, "loaded": [ { "name": "opik-frontend", "body_tokens": 500 }, { "name": "find-skills", "body_tokens": 300 } ] },
                    "tools": { "summary": { "schema_tokens": 380, "by_source": { "mcp": { "schema_tokens": 300, "available_count": 1 }, "builtin": { "schema_tokens": 80 } }, "by_server": [ { "server": "chrome-devtools", "schema_tokens": 300 } ] } },
                    "file_attachments": { "summary": { "total_tokens": 40 }, "files": [ { "path": "a.png", "body_tokens": 40 } ] },
                    "memory": { "summary": { "total_tokens": 60 }, "files": [ { "path": "AGENTS.md", "body_tokens": 60 } ] },
                    "thinking": { "summary": { "total_tokens": 150 }, "by_model": [ { "model": "claude-opus-4-7", "tokens": 150 } ] },
                    "assistant_text": { "summary": { "total_tokens": 70 } }
                  }
                }
                """
                .formatted(userUuid);
        return JsonUtils.getJsonNodeFromString(json);
    }
}
