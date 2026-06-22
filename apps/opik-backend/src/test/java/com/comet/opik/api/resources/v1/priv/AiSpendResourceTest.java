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
import com.comet.opik.api.spend.ModelTiers;
import com.comet.opik.api.spend.SpendBreakdownResponse;
import com.comet.opik.api.spend.SpendCompositionResponse;
import com.comet.opik.api.spend.SpendLane;
import com.comet.opik.api.spend.SpendMetricRequest;
import com.comet.opik.api.spend.SpendUserPage;
import com.comet.opik.api.spend.SpendUserRow;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.SpanType;
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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
@DisplayName("AI Spend Resource Test")
class AiSpendResourceTest {

    private static final String USER = UUID.randomUUID().toString();

    // Per-trace numbers shipped by the canonical cipx fixture (see
    // buildCipxLlmSpanMetadata): every input block carries cache_status=read
    // except user_prompts (fresh); the call has 40 cache_creation tokens that
    // no block can absorb, which become the unattributed residual.
    //
    //   tier columns: input=50, cache_read=2710, cache_creation=40, output=280
    //   input lanes (sum 2800): user_prompts 50, file_attachments 40,
    //     built_in_tools 200, prior_assistant 1000, skills 900,
    //     custom_agents 30, mcp_servers 380, memory 60, static_overhead 100,
    //     unattributed 40
    //   output lanes (sum 280): thinking 150, assistant_text 70,
    //     built_in_tool_calls 30, mcp_tool_calls 20, skill_invocations 10
    private static final String MODEL = "claude-opus-4-8";
    private static final long TRACE_INPUT_SIDE = 2800L;
    private static final long TRACE_OUTPUT_SIDE = 280L;
    private static final long TRACE_TOTAL_TOKENS = 50L + 2710L + 40L + 280L;

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
    @DisplayName("summary: aggregates billed tier tokens, messages and users for the current vs previous window")
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

        createCipxTracesWithLlmSpan(projectName, apiKey, workspaceName, currentTime, List.of(userA, userA, userB));
        createCipxTracesWithLlmSpan(projectName, apiKey, workspaceName, previousTime, List.of(userC));

        var summary = aiSpendResourceClient.getSummary(SpendMetricRequest.builder()
                .projectName(projectName)
                .intervalStart(intervalStart)
                .intervalEnd(intervalEnd)
                .build(), apiKey, workspaceName);

        Map<String, WorkspaceMetricsSummaryResponse.Result> byName = summary.results().stream()
                .collect(Collectors.toMap(WorkspaceMetricsSummaryResponse.Result::name, Function.identity()));

        // Spend tiers ship per model so the FE prices each at its own rate.
        assertThat(summary.spendCurrent()).hasSize(1);
        ModelTiers current = summary.spendCurrent().getFirst();
        assertThat(current.model()).isEqualTo(MODEL);
        assertThat(current.inputTokens()).isEqualTo(150L);
        assertThat(current.cacheReadTokens()).isEqualTo(8130L);
        assertThat(current.cacheCreationTokens()).isEqualTo(120L);
        assertThat(current.outputTokens()).isEqualTo(840L);

        assertThat(summary.spendPrevious()).hasSize(1);
        ModelTiers previous = summary.spendPrevious().getFirst();
        assertThat(previous.model()).isEqualTo(MODEL);
        assertThat(previous.inputTokens()).isEqualTo(50L);
        assertThat(previous.cacheReadTokens()).isEqualTo(2710L);
        assertThat(previous.outputTokens()).isEqualTo(280L);

        assertThat(byName.get("total_messages").current()).isEqualTo(3.0);
        assertThat(byName.get("total_messages").previous()).isEqualTo(1.0);
        assertThat(byName.get("active_users").current()).isEqualTo(2.0);
        assertThat(byName.get("total_users").current()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("composition: rolls up cipx.blocks[] tokens per lane and per model")
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
        createCipxTracesWithLlmSpan(projectName, apiKey, workspaceName, currentTime, List.of(user, user, user));

        var composition = aiSpendResourceClient.getComposition(SpendMetricRequest.builder()
                .projectName(projectName)
                .intervalStart(intervalStart)
                .intervalEnd(intervalEnd)
                .build(), apiKey, workspaceName);

        Map<String, SpendCompositionResponse.Lane> inputLanes = composition.input().lanes().stream()
                .collect(Collectors.toMap(SpendCompositionResponse.Lane::key, Function.identity()));
        Map<String, Long> outputTokens = composition.output().lanes().stream()
                .collect(Collectors.toMap(SpendCompositionResponse.Lane::key,
                        SpendCompositionResponse.Lane::totalTokens));

        assertThat(inputLanes.get("user_prompts").totalTokens()).isEqualTo(150L);
        assertThat(inputLanes.get("file_attachments").totalTokens()).isEqualTo(120L);
        assertThat(inputLanes.get("built_in_tools").totalTokens()).isEqualTo(600L);
        assertThat(inputLanes.get("prior_assistant").totalTokens()).isEqualTo(3000L);
        assertThat(inputLanes.get("skills").totalTokens()).isEqualTo(2700L);
        assertThat(inputLanes.get("custom_agents").totalTokens()).isEqualTo(90L);
        assertThat(inputLanes.get("mcp_servers").totalTokens()).isEqualTo(1140L);
        assertThat(inputLanes.get("memory").totalTokens()).isEqualTo(180L);
        assertThat(inputLanes.get("static_overhead").totalTokens()).isEqualTo(300L);
        assertThat(inputLanes.get("unattributed").totalTokens()).isEqualTo(120L);

        // Per-model tier columns ride along so the FE can price each lane.
        assertThat(inputLanes.get("user_prompts").byModel()).hasSize(1);
        assertThat(inputLanes.get("user_prompts").byModel().getFirst().model()).isEqualTo(MODEL);
        assertThat(inputLanes.get("user_prompts").byModel().getFirst().inputTokens()).isEqualTo(150L);
        assertThat(inputLanes.get("user_prompts").byModel().getFirst().cacheReadTokens()).isEqualTo(0L);
        assertThat(inputLanes.get("prior_assistant").byModel().getFirst().cacheReadTokens()).isEqualTo(3000L);
        assertThat(inputLanes.get("unattributed").byModel().getFirst().cacheCreationTokens()).isEqualTo(120L);
        assertThat(inputLanes.get("unattributed").hasBreakdown()).isFalse();

        assertThat(outputTokens.get("thinking")).isEqualTo(450L);
        assertThat(outputTokens.get("assistant_text")).isEqualTo(210L);
        assertThat(outputTokens.get("built_in_tool_calls")).isEqualTo(90L);
        assertThat(outputTokens.get("mcp_tool_calls")).isEqualTo(60L);
        assertThat(outputTokens.get("skill_invocations")).isEqualTo(30L);

        assertThat(composition.input().totalTokens()).isEqualTo(3 * TRACE_INPUT_SIDE);
        assertThat(composition.output().totalTokens()).isEqualTo(3 * TRACE_OUTPUT_SIDE);

        assertThat(composition.harness()).hasSize(1);
        assertThat(composition.harness().getFirst().key()).isEqualTo("claude_code");
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
        createCipxTracesWithLlmSpan(projectName, apiKey, workspaceName, currentTime, List.of(userA, userA, userB));

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
        assertThat(composition.input().totalTokens()).isEqualTo(2 * TRACE_INPUT_SIDE);
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
    @DisplayName("breakdown: 400 for an unknown lane key or a lane without breakdown")
    void breakdown_rejectsUnknownLane() {
        var workspaceName = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, UUID.randomUUID().toString(), USER);

        var request = SpendMetricRequest.builder()
                .projectName(RandomStringUtils.randomAlphabetic(10))
                .intervalStart(Instant.now().minus(Duration.ofMinutes(10)))
                .intervalEnd(Instant.now())
                .build();

        aiSpendResourceClient.getBreakdown("not_a_lane", request, apiKey, workspaceName, HttpStatus.SC_BAD_REQUEST);
        aiSpendResourceClient.getBreakdown("unattributed", request, apiKey, workspaceName, HttpStatus.SC_BAD_REQUEST);
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

        assertThat(summary.spendCurrent()).isEmpty();
        assertThat(summary.spendPrevious()).isEmpty();
        assertThat(byName.get("total_messages").current()).isEqualTo(0.0);
        assertThat(byName.get("active_users").current()).isEqualTo(0.0);
        assertThat(byName.get("total_users").current()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("breakdown: skills lane aggregates skills_menu and skills_loaded blocks per skill name")
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
        createCipxTracesWithLlmSpan(projectName, apiKey, workspaceName, currentTime, List.of(user, user, user));

        var breakdown = aiSpendResourceClient.getBreakdown("skills", SpendMetricRequest.builder()
                .projectName(projectName)
                .intervalStart(intervalStart)
                .intervalEnd(intervalEnd)
                .build(), apiKey, workspaceName);

        assertThat(breakdown.laneKey()).isEqualTo("skills");
        assertThat(breakdown.title()).isEqualTo("Skills");
        assertThat(breakdown.subtitle()).isEqualTo(SpendLane.SKILLS.getDescription());
        assertThat(breakdown.itemUnit()).isEqualTo("load");
        // Two skills_loaded blocks per trace (opik-frontend, find-skills) × 3 traces = 6 usage events.
        assertThat(breakdown.itemCount()).isEqualTo(6);
        assertThat(breakdown.totalTokens()).isEqualTo(2700L);

        // Per-model tier sums ride along so the FE can price the lane total.
        assertThat(breakdown.byModel()).hasSize(1);
        assertThat(breakdown.byModel().getFirst().model()).isEqualTo(MODEL);
        assertThat(breakdown.byModel().getFirst().cacheReadTokens()).isEqualTo(2700L);
        assertThat(breakdown.byModel().getFirst().inputTokens()).isEqualTo(0L);
        assertThat(breakdown.byModel().getFirst().outputTokens()).isEqualTo(0L);

        Map<String, SpendBreakdownResponse.Item> byLabel = breakdown.items().stream()
                .collect(Collectors.toMap(SpendBreakdownResponse.Item::label, Function.identity()));
        assertThat(byLabel.get("opik-frontend").totalTokens()).isEqualTo(1800L);
        assertThat(byLabel.get("opik-frontend").definitionTokens()).isEqualTo(300L);
        assertThat(byLabel.get("opik-frontend").usageTokens()).isEqualTo(1500L);
        assertThat(byLabel.get("opik-frontend").count()).isEqualTo(3L);
        assertThat(byLabel.get("opik-frontend").byModel().getFirst().cacheReadTokens()).isEqualTo(1800L);
        assertThat(byLabel.get("opik-frontend").byModel().getFirst().inputTokens()).isEqualTo(0L);
        assertThat(byLabel.get("find-skills").totalTokens()).isEqualTo(900L);
        assertThat(byLabel.get("find-skills").definitionTokens()).isEqualTo(0L);
        assertThat(byLabel.get("find-skills").usageTokens()).isEqualTo(900L);
    }

    @Test
    @DisplayName("breakdown: mcp_servers folds tool schema (definition) and tool_io (usage) per server")
    void breakdown_mcpServers() {
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
        createCipxTracesWithLlmSpan(projectName, apiKey, workspaceName, currentTime, List.of(user, user, user));

        var breakdown = getBreakdown(projectName, apiKey, workspaceName, intervalStart, intervalEnd, "mcp_servers");

        assertThat(breakdown.laneKey()).isEqualTo("mcp_servers");
        assertThat(breakdown.totalTokens()).isEqualTo(1140L);
        // chrome-devtools tool_io block per trace × 3 = 3 calls.
        assertThat(breakdown.itemCount()).isEqualTo(3);

        var server = breakdown.items().getFirst();
        assertThat(server.label()).isEqualTo("chrome-devtools");
        assertThat(server.totalTokens()).isEqualTo(1140L);
        assertThat(server.definitionTokens()).isEqualTo(900L);
        assertThat(server.usageTokens()).isEqualTo(240L);
        assertThat(server.count()).isEqualTo(3L);
    }

    @Test
    @DisplayName("breakdown: output tool-call lanes group output blocks by tool/server/skill")
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

        var user = UUID.randomUUID().toString();
        createCipxTracesWithLlmSpan(projectName, apiKey, workspaceName, currentTime, List.of(user, user, user));

        var builtIn = getBreakdown(projectName, apiKey, workspaceName, intervalStart, intervalEnd,
                "built_in_tool_calls");
        assertThat(builtIn.totalTokens()).isEqualTo(90L);
        // Output lanes have no description, so no subtitle.
        assertThat(builtIn.subtitle()).isNull();
        assertThat(builtIn.itemUnit()).isEqualTo("call");
        // Bash tool call: 1 block per trace × 3 traces = 3 calls.
        assertThat(builtIn.itemCount()).isEqualTo(3);
        assertThat(builtIn.items().getFirst().label()).isEqualTo("Bash");
        assertThat(builtIn.items().getFirst().totalTokens()).isEqualTo(90L);
        assertThat(builtIn.items().getFirst().count()).isEqualTo(3L);

        var mcp = getBreakdown(projectName, apiKey, workspaceName, intervalStart, intervalEnd, "mcp_tool_calls");
        assertThat(mcp.totalTokens()).isEqualTo(60L);
        assertThat(mcp.items().getFirst().label()).isEqualTo("chrome");

        var skill = getBreakdown(projectName, apiKey, workspaceName, intervalStart, intervalEnd, "skill_invocations");
        assertThat(skill.totalTokens()).isEqualTo(30L);
        assertThat(skill.items().getFirst().label()).isEqualTo("diagram-generation");
    }

    @Test
    @DisplayName("breakdown: thinking and assistant text lanes return single rows")
    void breakdown_outputTextLanes() {
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
        createCipxTracesWithLlmSpan(projectName, apiKey, workspaceName, currentTime, List.of(user, user, user));

        var thinking = getBreakdown(projectName, apiKey, workspaceName, intervalStart, intervalEnd, "thinking");
        assertThat(thinking.totalTokens()).isEqualTo(450L);
        assertThat(thinking.itemUnit()).isEqualTo("block");
        // 1 thinking block per trace × 3 = 3 emitted blocks.
        assertThat(thinking.itemCount()).isEqualTo(3);
        assertThat(thinking.items().getFirst().label()).isEqualTo("thinking");
        assertThat(thinking.items().getFirst().totalTokens()).isEqualTo(450L);

        var text = getBreakdown(projectName, apiKey, workspaceName, intervalStart, intervalEnd, "assistant_text");
        assertThat(text.totalTokens()).isEqualTo(210L);
        assertThat(text.items().getFirst().label()).isEqualTo("assistant_text");
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

        createManyToolsTrace(projectName, apiKey, workspaceName, currentTime, 201);

        var breakdown = getBreakdown(projectName, apiKey, workspaceName, intervalStart, intervalEnd,
                "built_in_tools");

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

        createManyToolsTrace(projectName, apiKey, workspaceName, currentTime, 200);

        var breakdown = getBreakdown(projectName, apiKey, workspaceName, intervalStart, intervalEnd,
                "built_in_tools");

        assertThat(breakdown.totalTokens()).isEqualTo(200L);
        assertThat(breakdown.itemCount()).isEqualTo(200);
        assertThat(breakdown.items()).hasSize(200);
        assertThat(breakdown.items()).noneMatch(item -> item.label().startsWith("Other ("));
        assertThat(breakdown.items().stream().mapToLong(SpendBreakdownResponse.Item::totalTokens).sum())
                .isEqualTo(200L);
    }

    @Test
    @DisplayName("users: ranks users by billed tokens, joins block activity, flags high spenders")
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

        createCipxTracesWithLlmSpan(projectName, apiKey, workspaceName, currentTime, List.of(userA, userA));
        // userB owns a session-only trace (no LLM-call span). They appear in
        // the leaderboard with zero tokens.
        createIdentityOnlyTraces(projectName, apiKey, workspaceName, currentTime, List.of(userB));

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
        assertThat(top.totalTokens()).isEqualTo(2 * TRACE_TOTAL_TOKENS);
        assertThat(top.byModel()).hasSize(1);
        ModelTiers topModel = top.byModel().getFirst();
        assertThat(topModel.model()).isEqualTo(MODEL);
        assertThat(topModel.inputTokens()).isEqualTo(100L);
        assertThat(topModel.cacheReadTokens()).isEqualTo(5420L);
        assertThat(topModel.cacheCreationTokens()).isEqualTo(80L);
        assertThat(topModel.outputTokens()).isEqualTo(560L);
        assertThat(top.flags()).contains("high_spend");
        assertThat(top.repositories()).containsExactly("repo-a");
        // 2 skills_loaded blocks per LLM-call span × 2 calls = 4.
        assertThat(top.skills()).isEqualTo(4L);
        // chrome-devtools is the only mcp_tools_active server.
        assertThat(top.mcps()).isEqualTo(1L);
        // 1 mcp_tool_calls output block per LLM-call span × 2 calls = 2.
        assertThat(top.mcpCalls()).isEqualTo(2L);

        SpendUserRow second = page.content().get(1);
        assertThat(second.userUuid()).isEqualTo(userB);
        assertThat(second.totalTokens()).isEqualTo(0L);
        assertThat(second.byModel()).isEmpty();
        assertThat(second.flags()).doesNotContain("high_spend");
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
        createCipxTracesWithLlmSpan(projectName, apiKey, workspaceName, currentTime, List.of(userA, userB));

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

        // userA has 2 LLM-call spans, userB has 3 — userB ranks higher by request count.
        createCipxTracesWithLlmSpan(projectName, apiKey, workspaceName, currentTime, List.of(userA, userA));
        createCipxTracesWithLlmSpan(projectName, apiKey, workspaceName, currentTime, List.of(userB, userB, userB));

        var request = SpendMetricRequest.builder()
                .projectName(projectName)
                .intervalStart(intervalStart)
                .intervalEnd(intervalEnd)
                .build();

        SpendUserPage byTokens = aiSpendResourceClient.getUsers(request, 1, 25, apiKey, workspaceName);
        assertThat(byTokens.sortableBy())
                .containsExactly("total_tokens", "requests", "skills", "mcps", "mcp_calls");
        assertThat(byTokens.content()).extracting(SpendUserRow::userUuid).containsExactly(userB, userA);

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

    private List<UUID> createCipxTracesWithLlmSpan(String projectName, String apiKey, String workspaceName,
            Instant time, List<String> userUuids) {
        List<UUID> ids = new ArrayList<>();
        for (String userUuid : userUuids) {
            UUID traceId = idGenerator.getTimeOrderedEpoch(time.toEpochMilli());
            Trace trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .id(traceId)
                    .projectName(projectName)
                    .startTime(time)
                    .metadata(buildCipxSessionMetadata(userUuid))
                    .feedbackScores(null)
                    .build();
            traceResourceClient.createTrace(trace, apiKey, workspaceName);

            Span llmCallSpan = factory.manufacturePojo(Span.class).toBuilder()
                    .id(idGenerator.getTimeOrderedEpoch(time.toEpochMilli()))
                    .traceId(traceId)
                    .startTime(time)
                    .name("user_turn")
                    .type(SpanType.llm)
                    .model(MODEL)
                    .projectId(null)
                    .projectName(projectName)
                    .feedbackScores(null)
                    .metadata(buildCipxLlmSpanMetadata())
                    .build();
            spanResourceClient.batchCreateSpans(List.of(llmCallSpan), apiKey, workspaceName);

            ids.add(traceId);
        }
        return ids;
    }

    private List<UUID> createIdentityOnlyTraces(String projectName, String apiKey, String workspaceName, Instant time,
            List<String> userUuids) {
        List<UUID> ids = new ArrayList<>();
        for (String userUuid : userUuids) {
            UUID id = idGenerator.getTimeOrderedEpoch(time.toEpochMilli());
            Trace trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .id(id)
                    .projectName(projectName)
                    .startTime(time)
                    .metadata(buildCipxSessionMetadata(userUuid))
                    .feedbackScores(null)
                    .build();
            traceResourceClient.createTrace(trace, apiKey, workspaceName);
            ids.add(id);
        }
        return ids;
    }

    private SpendBreakdownResponse getBreakdown(String projectName, String apiKey, String workspaceName,
            Instant intervalStart, Instant intervalEnd, String laneKey) {
        return aiSpendResourceClient.getBreakdown(laneKey, SpendMetricRequest.builder()
                .projectName(projectName)
                .intervalStart(intervalStart)
                .intervalEnd(intervalEnd)
                .build(), apiKey, workspaceName);
    }

    // A trace with one LLM-call span whose only input is N built-in tool_io
    // blocks; each block has chars=1 and cache_status=read. With the call's
    // cache_read_input_tokens = N, the proportional allocator gives each
    // block exactly 1 token.
    private void createManyToolsTrace(String projectName, String apiKey, String workspaceName, Instant time,
            int count) {
        UUID traceId = idGenerator.getTimeOrderedEpoch(time.toEpochMilli());
        Trace trace = factory.manufacturePojo(Trace.class).toBuilder()
                .id(traceId)
                .projectName(projectName)
                .startTime(time)
                .metadata(buildCipxSessionMetadata(UUID.randomUUID().toString()))
                .feedbackScores(null)
                .build();
        traceResourceClient.createTrace(trace, apiKey, workspaceName);

        String blocks = IntStream.range(0, count)
                .mapToObj(i -> """
                        { "side": "input", "category": "tool_io", "cache_status": "read", "chars": 1,
                          "tool_name": "tool_%d", "tool_server": "", "kind": "tool_result" }""".formatted(i))
                .collect(Collectors.joining(","));
        String spanMetadata = """
                {
                  "cipx": {
                    "call": {
                      "model": "%s",
                      "usage": { "input_tokens": 0, "cache_read_input_tokens": %d,
                                 "cache_creation_input_tokens": 0, "output_tokens": 0 }
                    },
                    "blocks": [ %s ]
                  }
                }
                """.formatted(MODEL, count, blocks);

        Span span = factory.manufacturePojo(Span.class).toBuilder()
                .id(idGenerator.getTimeOrderedEpoch(time.toEpochMilli()))
                .traceId(traceId)
                .startTime(time)
                .name("user_turn")
                .type(SpanType.llm)
                .model(MODEL)
                .projectId(null)
                .projectName(projectName)
                .feedbackScores(null)
                .metadata(JsonUtils.getJsonNodeFromString(spanMetadata))
                .build();
        spanResourceClient.batchCreateSpans(List.of(span), apiKey, workspaceName);
    }

    // Trace-level cipx.session shipped by cipx Item 1 (always-on identity).
    private JsonNode buildCipxSessionMetadata(String userUuid) {
        String json = """
                {
                  "cipx": {
                    "session": {
                      "schema_version": 2,
                      "harness": "claude_code",
                      "session_id": "%1$s",
                      "identity": {
                        "user_uuid": "%1$s",
                        "email": "%1$s@comet.com",
                        "display_name": "%1$s"
                      },
                      "repository": { "remote": "repo-a" }
                    }
                  }
                }
                """.formatted(userUuid);
        return JsonUtils.getJsonNodeFromString(json);
    }

    // The canonical LLM-call span metadata. One block per lane (or two for
    // lanes with definition vs usage split). Every block's chars equals its
    // expected attributed-token count so the chars-share allocator inside
    // the BE projects token totals 1:1 onto these values. Input blocks
    // default to cache_status=read; user_prompts is the only fresh block.
    // A 40-token cache_creation_input_tokens count with no write-tier block
    // becomes the per-trace 'unattributed' residual.
    private JsonNode buildCipxLlmSpanMetadata() {
        String json = """
                {
                  "cipx": {
                    "call": {
                      "model": "%1$s",
                      "provider": "anthropic",
                      "trigger": "user_turn",
                      "usage": {
                        "input_tokens": 50,
                        "cache_read_input_tokens": 2710,
                        "cache_creation_input_tokens": 40,
                        "output_tokens": 280
                      }
                    },
                    "blocks": [
                      { "side": "input", "category": "user_prompts", "cache_status": "fresh", "chars": 50, "kind": "text" },
                      { "side": "input", "category": "file_attachments", "cache_status": "read", "chars": 40, "kind": "image", "resource": "screenshot.png" },
                      { "side": "input", "category": "tool_io", "cache_status": "read", "chars": 100, "tool_name": "Bash", "tool_server": "", "kind": "tool_result" },
                      { "side": "input", "category": "tool_io", "cache_status": "read", "chars": 100, "tool_name": "Bash", "tool_server": "", "kind": "tool_result" },
                      { "side": "input", "category": "prior_assistant", "cache_status": "read", "chars": 600, "kind": "text" },
                      { "side": "input", "category": "prior_assistant", "cache_status": "read", "chars": 400, "kind": "thinking" },
                      { "side": "input", "category": "skills_menu", "cache_status": "read", "chars": 100, "resource": "opik-frontend" },
                      { "side": "input", "category": "skills_loaded", "cache_status": "read", "chars": 500, "resource": "opik-frontend" },
                      { "side": "input", "category": "skills_loaded", "cache_status": "read", "chars": 300, "resource": "find-skills" },
                      { "side": "input", "category": "custom_agents", "cache_status": "read", "chars": 30, "resource": "code-reviewer" },
                      { "side": "input", "category": "mcp_tools_active", "cache_status": "read", "chars": 300, "tool_server": "chrome-devtools", "tool_name": "click" },
                      { "side": "input", "category": "tool_io", "cache_status": "read", "chars": 80, "tool_name": "click", "tool_server": "chrome-devtools", "kind": "tool_result" },
                      { "side": "input", "category": "memory", "cache_status": "read", "chars": 60, "resource": "AGENTS.md", "parent_category": "identity_context" },
                      { "side": "input", "category": "system_prompt", "cache_status": "read", "chars": 100 },
                      { "side": "output", "category": "thinking", "chars": 150, "kind": "thinking" },
                      { "side": "output", "category": "assistant_text", "chars": 70, "kind": "text" },
                      { "side": "output", "category": "built_in_tool_calls", "chars": 30, "kind": "tool_use", "tool_name": "Bash" },
                      { "side": "output", "category": "mcp_tool_calls", "chars": 20, "kind": "tool_use", "tool_server": "chrome", "tool_name": "click" },
                      { "side": "output", "category": "skill_invocations", "chars": 10, "kind": "tool_use", "tool_name": "Skill", "resource": "diagram-generation" }
                    ]
                  }
                }
                """
                .formatted(MODEL);
        return JsonUtils.getJsonNodeFromString(json);
    }
}
