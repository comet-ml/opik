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
import com.comet.opik.api.spend.SpendLane;
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

    // Per-trace cc.billing fixture numbers (see buildCcMetadata):
    // input-side lanes sum to 2800, output lane to 280; tier totals are
    // input=50, cache_read=2710, cache_creation=40, output=280.
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

        createCcTraces(projectName, apiKey, workspaceName, currentTime, List.of(userA, userA, userB));
        createCcTraces(projectName, apiKey, workspaceName, previousTime, List.of(userC));

        var summary = aiSpendResourceClient.getSummary(SpendMetricRequest.builder()
                .projectName(projectName)
                .intervalStart(intervalStart)
                .intervalEnd(intervalEnd)
                .build(), apiKey, workspaceName);

        Map<String, WorkspaceMetricsSummaryResponse.Result> byName = summary.results().stream()
                .collect(Collectors.toMap(WorkspaceMetricsSummaryResponse.Result::name, Function.identity()));

        assertThat(byName.get("spend_input_tokens").current()).isEqualTo(150.0);
        assertThat(byName.get("spend_input_tokens").previous()).isEqualTo(50.0);
        assertThat(byName.get("spend_cache_read_tokens").current()).isEqualTo(8130.0);
        assertThat(byName.get("spend_cache_read_tokens").previous()).isEqualTo(2710.0);
        assertThat(byName.get("spend_cache_creation_tokens").current()).isEqualTo(120.0);
        assertThat(byName.get("spend_output_tokens").current()).isEqualTo(840.0);
        assertThat(byName.get("spend_output_tokens").previous()).isEqualTo(280.0);
        assertThat(byName.get("total_messages").current()).isEqualTo(3.0);
        assertThat(byName.get("total_messages").previous()).isEqualTo(1.0);
        assertThat(byName.get("active_users").current()).isEqualTo(2.0);
        assertThat(byName.get("total_users").current()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("composition: sums cc.billing lane tier tokens from traces and output lanes from output items")
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

        // Tier columns ride along so the FE can price each lane.
        assertThat(inputLanes.get("user_prompts").inputTokens()).isEqualTo(150L);
        assertThat(inputLanes.get("user_prompts").cacheReadTokens()).isEqualTo(0L);
        assertThat(inputLanes.get("prior_assistant").cacheReadTokens()).isEqualTo(3000L);
        assertThat(inputLanes.get("unattributed").cacheCreationTokens()).isEqualTo(120L);
        assertThat(inputLanes.get("unattributed").hasBreakdown()).isFalse();

        assertThat(outputTokens.get("thinking")).isEqualTo(450L);
        assertThat(outputTokens.get("assistant_text")).isEqualTo(210L);
        assertThat(outputTokens.get("built_in_tool_calls")).isEqualTo(90L);
        assertThat(outputTokens.get("mcp_tool_calls")).isEqualTo(60L);
        assertThat(outputTokens.get("skill_invocations")).isEqualTo(30L);

        assertThat(composition.input().totalTokens()).isEqualTo(3 * TRACE_INPUT_SIDE);
        assertThat(composition.output().totalTokens()).isEqualTo(3 * TRACE_OUTPUT_SIDE);

        assertThat(composition.models()).containsExactly("claude-opus-4-8");

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

        assertThat(byName.get("spend_input_tokens").current()).isEqualTo(0.0);
        assertThat(byName.get("spend_cache_read_tokens").current()).isEqualTo(0.0);
        assertThat(byName.get("spend_cache_creation_tokens").current()).isEqualTo(0.0);
        assertThat(byName.get("spend_output_tokens").current()).isEqualTo(0.0);
        assertThat(byName.get("total_messages").current()).isEqualTo(0.0);
        assertThat(byName.get("active_users").current()).isEqualTo(0.0);
        assertThat(byName.get("total_users").current()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("breakdown: aggregates a lane's items per entity, split into definition vs usage")
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

        var breakdown = aiSpendResourceClient.getBreakdown("skills", SpendMetricRequest.builder()
                .projectName(projectName)
                .intervalStart(intervalStart)
                .intervalEnd(intervalEnd)
                .build(), apiKey, workspaceName);

        assertThat(breakdown.laneKey()).isEqualTo("skills");
        assertThat(breakdown.title()).isEqualTo("Skills");
        assertThat(breakdown.subtitle()).isEqualTo(SpendLane.SKILLS.getDescription());
        assertThat(breakdown.itemUnit()).isEqualTo("load");
        // itemCount is the total occurrence count: opik-frontend (1 usage) +
        // find-skills (1 usage), across 3 traces = 6 calls.
        assertThat(breakdown.itemCount()).isEqualTo(6);
        assertThat(breakdown.totalTokens()).isEqualTo(2700L);

        // Tier sums + model ride along so the FE can price the lane total.
        assertThat(breakdown.cacheReadTokens()).isEqualTo(2700L);
        assertThat(breakdown.inputTokens()).isEqualTo(0L);
        assertThat(breakdown.outputTokens()).isEqualTo(0L);
        assertThat(breakdown.model()).isEqualTo("claude-opus-4-8");

        Map<String, SpendBreakdownResponse.Item> byLabel = breakdown.items().stream()
                .collect(Collectors.toMap(SpendBreakdownResponse.Item::label, Function.identity()));
        assertThat(byLabel.get("opik-frontend").totalTokens()).isEqualTo(1800L);
        assertThat(byLabel.get("opik-frontend").definitionTokens()).isEqualTo(300L);
        assertThat(byLabel.get("opik-frontend").usageTokens()).isEqualTo(1500L);
        assertThat(byLabel.get("opik-frontend").count()).isEqualTo(3L);
        // Per-item tier sums ride along so the FE can price each row.
        assertThat(byLabel.get("opik-frontend").cacheReadTokens()).isEqualTo(1800L);
        assertThat(byLabel.get("opik-frontend").inputTokens()).isEqualTo(0L);
        assertThat(byLabel.get("find-skills").totalTokens()).isEqualTo(900L);
        assertThat(byLabel.get("find-skills").definitionTokens()).isEqualTo(0L);
        assertThat(byLabel.get("find-skills").usageTokens()).isEqualTo(900L);
    }

    @Test
    @DisplayName("breakdown: mcp_servers folds definition and tool usage rows of a server into one entity")
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
        createCcTraces(projectName, apiKey, workspaceName, currentTime, List.of(user, user, user));

        var breakdown = getBreakdown(projectName, apiKey, workspaceName, intervalStart, intervalEnd, "mcp_servers");

        assertThat(breakdown.laneKey()).isEqualTo("mcp_servers");
        assertThat(breakdown.totalTokens()).isEqualTo(1140L);
        // chrome-devtools: 1 usage call per trace, across 3 traces = 3 calls.
        assertThat(breakdown.itemCount()).isEqualTo(3);

        var server = breakdown.items().getFirst();
        assertThat(server.label()).isEqualTo("chrome-devtools");
        assertThat(server.totalTokens()).isEqualTo(1140L);
        assertThat(server.definitionTokens()).isEqualTo(900L);
        assertThat(server.usageTokens()).isEqualTo(240L);
        assertThat(server.count()).isEqualTo(3L);
    }

    @Test
    @DisplayName("breakdown: output tool-call lanes group output items by tool/server/skill")
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
        createCcTraces(projectName, apiKey, workspaceName, currentTime, List.of(user, user, user));

        var builtIn = getBreakdown(projectName, apiKey, workspaceName, intervalStart, intervalEnd,
                "built_in_tool_calls");
        assertThat(builtIn.totalTokens()).isEqualTo(90L);
        // Output lanes have no description, so no subtitle.
        assertThat(builtIn.subtitle()).isNull();
        assertThat(builtIn.itemUnit()).isEqualTo("call");
        // Bash tool call: 1 per trace, across 3 traces = 3 calls.
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
        createCcTraces(projectName, apiKey, workspaceName, currentTime, List.of(user, user, user));

        var thinking = getBreakdown(projectName, apiKey, workspaceName, intervalStart, intervalEnd, "thinking");
        assertThat(thinking.totalTokens()).isEqualTo(450L);
        assertThat(thinking.itemUnit()).isEqualTo("block");
        // The fixture's thinking items carry no event count, so 0 blocks.
        assertThat(thinking.itemCount()).isEqualTo(0);
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

        createBuiltInToolItemsTrace(projectName, apiKey, workspaceName, currentTime, 201);

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

        createBuiltInToolItemsTrace(projectName, apiKey, workspaceName, currentTime, 200);

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
    @DisplayName("users: ranks users by billed tokens, joins span activity, flags high spenders")
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
        userATraces.forEach(traceId -> createMcpSpan(projectName, apiKey, workspaceName, traceId, currentTime));
        createIdentityTraces(projectName, apiKey, workspaceName, currentTime, List.of(userB));

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
        assertThat(top.inputTokens()).isEqualTo(100L);
        assertThat(top.cacheReadTokens()).isEqualTo(5420L);
        assertThat(top.cacheCreationTokens()).isEqualTo(80L);
        assertThat(top.outputTokens()).isEqualTo(560L);
        assertThat(top.flags()).contains("high_spend");
        assertThat(top.repositories()).containsExactly("repo-a");
        assertThat(top.skills()).isEqualTo(4L);
        assertThat(top.mcps()).isEqualTo(1L);
        assertThat(top.mcpCalls()).isEqualTo(2L);
        assertThat(top.model()).isEqualTo("claude-opus-4-8");

        SpendUserRow second = page.content().get(1);
        assertThat(second.userUuid()).isEqualTo(userB);
        assertThat(second.totalTokens()).isEqualTo(0L);
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

        createCcTraces(projectName, apiKey, workspaceName, currentTime, List.of(userA, userA));
        createIdentityTraces(projectName, apiKey, workspaceName, currentTime, List.of(userB, userB, userB));

        var request = SpendMetricRequest.builder()
                .projectName(projectName)
                .intervalStart(intervalStart)
                .intervalEnd(intervalEnd)
                .build();

        SpendUserPage byTokens = aiSpendResourceClient.getUsers(request, 1, 25, apiKey, workspaceName);
        assertThat(byTokens.sortableBy())
                .containsExactly("total_tokens", "requests", "skills", "mcps", "mcp_calls");
        assertThat(byTokens.content()).extracting(SpendUserRow::userUuid).containsExactly(userA, userB);

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
    @DisplayName("recommendations: returns judgment-based items with token-denominated savings")
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

        var recommendations = aiSpendResourceClient.getRecommendations(SpendMetricRequest.builder()
                .projectName(projectName)
                .intervalStart(intervalStart)
                .intervalEnd(intervalEnd)
                .build(), apiKey, workspaceName);

        List<String> ids = recommendations.items().stream()
                .map(SpendRecommendationsResponse.Item::id)
                .toList();
        // thinking is 450/840 > 0.5 of output; prior_assistant is 3000/8400 < 0.4 of input.
        assertThat(ids).contains("thinking_effort", "fewer_tools");
        assertThat(ids).doesNotContain("compact_threshold");
        assertThat(recommendations.items()).hasSizeLessThanOrEqualTo(3);
        // round(450 * 0.3) + round(1140 * 0.5) = 135 + 570
        assertThat(recommendations.totalSavingsTokens()).isEqualTo(705L);

        Map<String, Impact> impacts = recommendations.items().stream()
                .collect(Collectors.toMap(SpendRecommendationsResponse.Item::id,
                        SpendRecommendationsResponse.Item::impact));
        assertThat(impacts.get("thinking_effort")).isEqualTo(Impact.MEDIUM);
        assertThat(impacts.get("fewer_tools")).isEqualTo(Impact.LOW);
    }

    private List<UUID> createCcTraces(String projectName, String apiKey, String workspaceName, Instant time,
            List<String> userUuids) {
        return createTraces(projectName, apiKey, workspaceName, time, userUuids, this::buildCcMetadata);
    }

    private List<UUID> createIdentityTraces(String projectName, String apiKey, String workspaceName, Instant time,
            List<String> userUuids) {
        return createTraces(projectName, apiKey, workspaceName, time, userUuids, this::buildIdentityMetadata);
    }

    private List<UUID> createTraces(String projectName, String apiKey, String workspaceName, Instant time,
            List<String> userUuids, Function<String, JsonNode> metadata) {
        List<UUID> ids = new ArrayList<>();
        for (String userUuid : userUuids) {
            UUID id = idGenerator.getTimeOrderedEpoch(time.toEpochMilli());
            Trace trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .id(id)
                    .projectName(projectName)
                    .startTime(time)
                    .metadata(metadata.apply(userUuid))
                    .feedbackScores(null)
                    .build();
            traceResourceClient.createTrace(trace, apiKey, workspaceName);
            ids.add(id);
        }
        return ids;
    }

    private void createMcpSpan(String projectName, String apiKey, String workspaceName, UUID traceId, Instant time) {
        Span span = factory.manufacturePojo(Span.class).toBuilder()
                .startTime(time)
                .id(idGenerator.getTimeOrderedEpoch(time.toEpochMilli()))
                .traceId(traceId)
                .name("mcp__chrome__click")
                .model("claude-opus-4-8")
                .projectId(null)
                .projectName(projectName)
                .feedbackScores(null)
                .build();
        spanResourceClient.batchCreateSpans(List.of(span), apiKey, workspaceName);
    }

    private SpendBreakdownResponse getBreakdown(String projectName, String apiKey, String workspaceName,
            Instant intervalStart, Instant intervalEnd, String laneKey) {
        return aiSpendResourceClient.getBreakdown(laneKey, SpendMetricRequest.builder()
                .projectName(projectName)
                .intervalStart(intervalStart)
                .intervalEnd(intervalEnd)
                .build(), apiKey, workspaceName);
    }

    private void createBuiltInToolItemsTrace(String projectName, String apiKey, String workspaceName, Instant time,
            int count) {
        String items = IntStream.range(0, count)
                .mapToObj(i -> """
                        { "name": "tool_%d", "kind": "usage", "count": 1, "total": 1, "cache_read": 1 }""".formatted(i))
                .collect(Collectors.joining(","));
        String json = """
                { "cc": { "billing": { "lanes": { "built_in_tools": { "total": %1$d, "cache_read": %1$d, "items": [ %2$s ] } } } } }
                """
                .formatted(count, items);
        Trace trace = factory.manufacturePojo(Trace.class).toBuilder()
                .id(idGenerator.getTimeOrderedEpoch(time.toEpochMilli()))
                .projectName(projectName)
                .startTime(time)
                .metadata(JsonUtils.getJsonNodeFromString(json))
                .feedbackScores(null)
                .build();
        traceResourceClient.createTrace(trace, apiKey, workspaceName);
    }

    private JsonNode buildIdentityMetadata(String userUuid) {
        String json = """
                {
                  "cc": {
                    "identity": { "user_uuid": "%1$s", "user_email": "%1$s@comet.com", "user_display_name": "%1$s" },
                    "git": { "repository": "repo-a" }
                  }
                }
                """.formatted(userUuid);
        return JsonUtils.getJsonNodeFromString(json);
    }

    // Every number under cc.billing is a per-LLM-call billing event: lane tier
    // columns sum to the lane total, lane totals (incl. unattributed) sum to
    // totals, and items sum to their lane.
    private JsonNode buildCcMetadata(String userUuid) {
        String json = """
                {
                  "cc": {
                    "identity": { "user_uuid": "%1$s", "user_email": "%1$s@comet.com", "user_display_name": "%1$s" },
                    "git": { "repository": "repo-a" },
                    "billing": {
                      "llm_calls": 2,
                      "model": "claude-opus-4-8",
                      "totals": { "total": 3080, "input": 50, "cache_read": 2710, "cache_creation": 40, "output": 280 },
                      "lanes": {
                        "user_prompts": { "total": 50, "input": 50, "cache_read": 0, "cache_creation": 0, "output": 0,
                          "items": [ { "name": "short", "kind": "usage", "count": 1, "total": 50, "input": 50 } ] },
                        "file_attachments": { "total": 40, "input": 0, "cache_read": 40, "cache_creation": 0, "output": 0,
                          "items": [ { "name": ".png", "kind": "usage", "count": 1, "total": 40, "cache_read": 40 } ] },
                        "built_in_tools": { "total": 200, "input": 0, "cache_read": 200, "cache_creation": 0, "output": 0,
                          "items": [ { "name": "Bash", "kind": "usage", "count": 2, "total": 200, "cache_read": 200 } ] },
                        "prior_assistant": { "total": 1000, "input": 0, "cache_read": 1000, "cache_creation": 0, "output": 0,
                          "items": [ { "name": "assistant_text", "kind": "usage", "count": 0, "total": 600, "cache_read": 600 },
                                     { "name": "thinking", "kind": "usage", "count": 0, "total": 400, "cache_read": 400 } ] },
                        "skills": { "total": 900, "input": 0, "cache_read": 900, "cache_creation": 0, "output": 0,
                          "items": [ { "name": "opik-frontend", "kind": "definition", "count": 0, "total": 100, "cache_read": 100 },
                                     { "name": "opik-frontend", "kind": "usage", "count": 1, "total": 500, "cache_read": 500 },
                                     { "name": "find-skills", "kind": "usage", "count": 1, "total": 300, "cache_read": 300 } ] },
                        "custom_agents": { "total": 30, "input": 0, "cache_read": 30, "cache_creation": 0, "output": 0,
                          "items": [ { "name": "code-reviewer", "kind": "definition", "count": 0, "total": 30, "cache_read": 30 } ] },
                        "mcp_servers": { "total": 380, "input": 0, "cache_read": 380, "cache_creation": 0, "output": 0,
                          "items": [ { "name": "chrome-devtools", "kind": "definition", "count": 0, "total": 300, "cache_read": 300 },
                                     { "name": "chrome-devtools", "kind": "usage", "count": 1, "total": 80, "cache_read": 80 } ] },
                        "memory": { "total": 60, "input": 0, "cache_read": 60, "cache_creation": 0, "output": 0,
                          "items": [ { "name": "AGENTS.md", "kind": "definition", "count": 1, "total": 60, "cache_read": 60 } ] },
                        "static_overhead": { "total": 100, "input": 0, "cache_read": 100, "cache_creation": 0, "output": 0,
                          "items": [ { "name": "core_prompt", "kind": "definition", "count": 0, "total": 100, "cache_read": 100 } ] },
                        "unattributed": { "total": 40, "input": 0, "cache_read": 0, "cache_creation": 40, "output": 0 },
                        "output": { "total": 280, "input": 0, "cache_read": 0, "cache_creation": 0, "output": 280,
                          "items": [ { "name": "thinking", "kind": "usage", "count": 0, "total": 150, "output": 150 },
                                     { "name": "assistant_text", "kind": "usage", "count": 0, "total": 70, "output": 70 },
                                     { "name": "built_in_tools/Bash", "kind": "usage", "count": 1, "total": 30, "output": 30 },
                                     { "name": "mcp_servers/chrome", "kind": "usage", "count": 1, "total": 20, "output": 20 },
                                     { "name": "skills/diagram-generation", "kind": "usage", "count": 1, "total": 10, "output": 10 } ] }
                      }
                    }
                  }
                }
                """
                .formatted(userUuid);
        return JsonUtils.getJsonNodeFromString(json);
    }
}
