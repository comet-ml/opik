package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.RunnersResourceClient;
import com.comet.opik.api.runner.ConnectRequest;
import com.comet.opik.api.runner.CreateJobRequest;
import com.comet.opik.api.runner.HeartbeatResponse;
import com.comet.opik.api.runner.JobResultRequest;
import com.comet.opik.api.runner.LocalRunner;
import com.comet.opik.api.runner.LocalRunnerJob;
import com.comet.opik.api.runner.LogEntry;
import com.comet.opik.api.runner.PairResponse;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.testcontainers.RedisContainer;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Runners Resource Test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class RunnersResourceTest {

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

    private RunnersResourceClient runnersClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        var baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);
        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);

        this.runnersClient = new RunnersResourceClient(client, baseURI);
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Test
    @DisplayName("Happy path: pair, connect, register agents, heartbeat, create job, poll next, logs, report result, get job")
    void happyPath() {
        // 1. Generate pairing code
        PairResponse pairResponse = runnersClient.generatePairingCode(API_KEY, TEST_WORKSPACE);
        assertThat(pairResponse.pairingCode()).isNotBlank();
        assertThat(pairResponse.runnerId()).isNotNull();
        assertThat(pairResponse.expiresInSeconds()).isPositive();

        // 2. Connect runner using pairing code
        ConnectRequest connectRequest = ConnectRequest.builder()
                .pairingCode(pairResponse.pairingCode())
                .runnerName("test-runner")
                .build();
        UUID runnerId = runnersClient.connect(connectRequest, API_KEY, TEST_WORKSPACE);
        assertThat(runnerId).isEqualTo(pairResponse.runnerId());

        // 3. List runners — should contain the connected runner
        LocalRunner.LocalRunnerPage runnerPage = runnersClient.listRunners(API_KEY, TEST_WORKSPACE);
        assertThat(runnerPage.content()).extracting(LocalRunner::id).contains(runnerId);
        LocalRunner listedRunner = runnerPage.content().stream()
                .filter(r -> r.id().equals(runnerId)).findFirst().orElseThrow();
        assertThat(listedRunner.name()).isEqualTo("test-runner");
        assertThat(listedRunner.status().getValue()).isEqualTo("connected");

        // 4. Get single runner
        LocalRunner runner = runnersClient.getRunner(runnerId, API_KEY, TEST_WORKSPACE);
        assertThat(runner.id()).isEqualTo(runnerId);
        assertThat(runner.name()).isEqualTo("test-runner");
        assertThat(runner.status().getValue()).isEqualTo("connected");

        // 5. Register agents
        LocalRunner.Agent agent = LocalRunner.Agent.builder()
                .name("my-agent")
                .description("A test agent")
                .language("python")
                .timeout(60)
                .build();
        runnersClient.registerAgents(runnerId, Map.of("my-agent", agent), API_KEY, TEST_WORKSPACE);

        // Verify agent appears on runner
        LocalRunner runnerWithAgents = runnersClient.getRunner(runnerId, API_KEY, TEST_WORKSPACE);
        assertThat(runnerWithAgents.agents()).hasSize(1);
        assertThat(runnerWithAgents.agents().getFirst().name()).isEqualTo("my-agent");

        // 6. Heartbeat
        HeartbeatResponse heartbeatResponse = runnersClient.heartbeat(runnerId, API_KEY, TEST_WORKSPACE);
        assertThat(heartbeatResponse.cancelledJobIds()).isEmpty();

        // 7. Create job
        CreateJobRequest createJobRequest = CreateJobRequest.builder()
                .agentName("my-agent")
                .runnerId(runnerId)
                .project("default")
                .inputs(new ObjectMapper().createObjectNode().put("prompt", "hello"))
                .build();
        UUID jobId = runnersClient.createJob(createJobRequest, API_KEY, TEST_WORKSPACE);
        assertThat(jobId).isNotNull();

        // Verify job was created
        LocalRunnerJob createdJob = runnersClient.getJob(jobId, API_KEY, TEST_WORKSPACE);
        assertThat(createdJob.status().getValue()).isEqualTo("pending");
        assertThat(createdJob.agentName()).isEqualTo("my-agent");
        assertThat(createdJob.runnerId()).isEqualTo(runnerId);

        // 8. Poll next job (simulating runner picking up work)
        try (var response = runnersClient.callNextJob(runnerId, API_KEY, TEST_WORKSPACE)) {
            assertThat(response.getStatus()).isEqualTo(200);
            LocalRunnerJob nextJob = response.readEntity(LocalRunnerJob.class);
            assertThat(nextJob.id()).isEqualTo(jobId);
            assertThat(nextJob.status().getValue()).isEqualTo("running");
        }

        // 9. Get job — should now be running
        LocalRunnerJob runningJob = runnersClient.getJob(jobId, API_KEY, TEST_WORKSPACE);
        assertThat(runningJob.status().getValue()).isEqualTo("running");
        assertThat(runningJob.startedAt()).isNotNull();

        // 10. List jobs
        LocalRunnerJob.LocalRunnerJobPage jobPage = runnersClient.listJobs(runnerId, null, 0, 10,
                API_KEY, TEST_WORKSPACE);
        assertThat(jobPage.total()).isEqualTo(1);
        assertThat(jobPage.content()).hasSize(1);
        assertThat(jobPage.content().getFirst().id()).isEqualTo(jobId);

        // 11. Append logs
        List<LogEntry> logEntries = List.of(
                LogEntry.builder().stream("stdout").text("Starting agent...").build(),
                LogEntry.builder().stream("stdout").text("Processing complete.").build());
        runnersClient.appendLogs(jobId, logEntries, API_KEY, TEST_WORKSPACE);

        // 12. Get logs
        List<LogEntry> logs = runnersClient.getJobLogs(jobId, 0, API_KEY, TEST_WORKSPACE);
        assertThat(logs).hasSize(2);
        assertThat(logs.get(0).text()).isEqualTo("Starting agent...");
        assertThat(logs.get(1).text()).isEqualTo("Processing complete.");

        // 13. Report result
        JsonNode resultPayload = new ObjectMapper().createObjectNode().put("answer", "world");
        JobResultRequest resultRequest = JobResultRequest.builder()
                .status("completed")
                .result(resultPayload)
                .traceId(randomUUID())
                .build();
        runnersClient.reportResult(jobId, resultRequest, API_KEY, TEST_WORKSPACE);

        // 14. Get final job state
        LocalRunnerJob completedJob = runnersClient.getJob(jobId, API_KEY, TEST_WORKSPACE);
        assertThat(completedJob.status().getValue()).isEqualTo("completed");
        assertThat(completedJob.completedAt()).isNotNull();
        assertThat(completedJob.result().get("answer").asText()).isEqualTo("world");
        assertThat(completedJob.traceId()).isEqualTo(resultRequest.traceId());
    }

    @Test
    @DisplayName("Connect with API key (no pairing code), create and cancel job")
    void apiKeyConnectAndCancelJob() {
        // Connect directly without pairing code
        ConnectRequest connectRequest = ConnectRequest.builder()
                .runnerName("direct-runner")
                .build();
        UUID runnerId = runnersClient.connect(connectRequest, API_KEY, TEST_WORKSPACE);
        assertThat(runnerId).isNotNull();

        // Heartbeat to keep alive
        runnersClient.heartbeat(runnerId, API_KEY, TEST_WORKSPACE);

        // Create job
        CreateJobRequest request = CreateJobRequest.builder()
                .agentName("agent-x")
                .runnerId(runnerId)
                .build();
        UUID jobId = runnersClient.createJob(request, API_KEY, TEST_WORKSPACE);

        // Verify pending
        LocalRunnerJob job = runnersClient.getJob(jobId, API_KEY, TEST_WORKSPACE);
        assertThat(job.status().getValue()).isEqualTo("pending");

        // Cancel job
        runnersClient.cancelJob(jobId, API_KEY, TEST_WORKSPACE);

        // Verify cancelled
        LocalRunnerJob cancelledJob = runnersClient.getJob(jobId, API_KEY, TEST_WORKSPACE);
        assertThat(cancelledJob.status().getValue()).isEqualTo("cancelled");
        assertThat(cancelledJob.completedAt()).isNotNull();
    }
}
