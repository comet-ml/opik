package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.LocalRunnersResourceClient;
import com.comet.opik.api.runner.CreateLocalRunnerJobRequest;
import com.comet.opik.api.runner.LocalRunner;
import com.comet.opik.api.runner.LocalRunnerConnectRequest;
import com.comet.opik.api.runner.LocalRunnerHeartbeatResponse;
import com.comet.opik.api.runner.LocalRunnerJob;
import com.comet.opik.api.runner.LocalRunnerJobMetadata;
import com.comet.opik.api.runner.LocalRunnerJobResultRequest;
import com.comet.opik.api.runner.LocalRunnerJobStatus;
import com.comet.opik.api.runner.LocalRunnerLogEntry;
import com.comet.opik.api.runner.LocalRunnerPairResponse;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
class LocalRunnersResourceTest {

    private static final String API_KEY = randomUUID().toString();
    private static final String USER = randomUUID().toString();
    private static final String WORKSPACE_ID = randomUUID().toString();
    private static final String TEST_WORKSPACE = randomUUID().toString();

    private static final String OTHER_API_KEY = randomUUID().toString();
    private static final String OTHER_USER = randomUUID().toString();
    private static final String OTHER_WORKSPACE_ID = randomUUID().toString();
    private static final String OTHER_WORKSPACE = randomUUID().toString();

    private static final String AGENT_NAME = "test-agent";
    private static final long HEARTBEAT_TTL_SECONDS = 2;

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
                AppContextConfig.builder()
                        .jdbcUrl(MYSQL_CONTAINER.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(REDIS.getRedisURI())
                        .customConfigs(List.of(
                                new CustomConfig("localRunner.heartbeatTtl", "2s"),
                                new CustomConfig("localRunner.maxPendingJobsPerRunner", "3")))
                        .build());
    }

    private LocalRunnersResourceClient runnersClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        var baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);
        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID, USER);
        mockTargetWorkspace(OTHER_API_KEY, OTHER_WORKSPACE, OTHER_WORKSPACE_ID, OTHER_USER);

        this.runnersClient = new LocalRunnersResourceClient(client, baseURI);
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId, String user) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, user);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private record WorkspaceContext(String apiKey, String workspace) {
    }

    private WorkspaceContext createIsolatedWorkspace() {
        String apiKey = randomUUID().toString();
        String workspace = randomUUID().toString();
        String workspaceId = randomUUID().toString();
        String user = randomUUID().toString();
        mockTargetWorkspace(apiKey, workspace, workspaceId, user);
        return new WorkspaceContext(apiKey, workspace);
    }

    private UUID connectRunner(String name) {
        return connectRunner(name, API_KEY, TEST_WORKSPACE);
    }

    private UUID connectRunner(String name, String apiKey, String workspace) {
        LocalRunnerConnectRequest req = LocalRunnerConnectRequest.builder()
                .runnerName(name)
                .build();
        UUID runnerId = runnersClient.connect(req, apiKey, workspace);
        runnersClient.heartbeat(runnerId, apiKey, workspace);
        return runnerId;
    }

    private UUID connectRunnerWithPairing(String name) {
        LocalRunnerPairResponse pair = runnersClient.generatePairingCode(API_KEY, TEST_WORKSPACE);
        LocalRunnerConnectRequest req = LocalRunnerConnectRequest.builder()
                .pairingCode(pair.pairingCode())
                .runnerName(name)
                .build();
        return runnersClient.connect(req, API_KEY, TEST_WORKSPACE);
    }

    private UUID createRunningJob(UUID runnerId, String agentName) {
        return createRunningJob(runnerId, agentName, API_KEY, TEST_WORKSPACE);
    }

    private UUID createRunningJob(UUID runnerId, String agentName, String apiKey, String workspace) {
        CreateLocalRunnerJobRequest req = CreateLocalRunnerJobRequest.builder()
                .agentName(agentName)
                .runnerId(runnerId)
                .build();
        UUID jobId = runnersClient.createJob(req, apiKey, workspace);
        try (var response = runnersClient.callNextJob(runnerId, apiKey, workspace)) {
            assertThat(response.getStatus()).isEqualTo(200);
        }
        return jobId;
    }

    private void waitForHeartbeatExpiry() throws InterruptedException {
        Thread.sleep((HEARTBEAT_TTL_SECONDS + 1) * 1000L);
    }

    @Test
    @DisplayName("Happy path: pair, connect, register agents, heartbeat, create job, poll next, logs, report result, get job")
    void happyPath() {
        LocalRunnerPairResponse pairResponse = runnersClient.generatePairingCode(API_KEY, TEST_WORKSPACE);
        assertThat(pairResponse.pairingCode()).isNotBlank();
        assertThat(pairResponse.runnerId()).isNotNull();
        assertThat(pairResponse.expiresInSeconds()).isPositive();

        LocalRunnerConnectRequest connectRequest = LocalRunnerConnectRequest.builder()
                .pairingCode(pairResponse.pairingCode())
                .runnerName("test-runner")
                .build();
        UUID runnerId = runnersClient.connect(connectRequest, API_KEY, TEST_WORKSPACE);
        assertThat(runnerId).isEqualTo(pairResponse.runnerId());

        LocalRunner.LocalRunnerPage runnerPage = runnersClient.listRunners(API_KEY, TEST_WORKSPACE);
        assertThat(runnerPage.content()).extracting(LocalRunner::id).contains(runnerId);
        LocalRunner listedRunner = runnerPage.content().stream()
                .filter(r -> r.id().equals(runnerId)).findFirst().orElseThrow();
        assertThat(listedRunner.name()).isEqualTo("test-runner");
        assertThat(listedRunner.status().getValue()).isEqualTo("connected");

        LocalRunner runner = runnersClient.getRunner(runnerId, API_KEY, TEST_WORKSPACE);
        assertThat(runner.id()).isEqualTo(runnerId);
        assertThat(runner.name()).isEqualTo("test-runner");
        assertThat(runner.status().getValue()).isEqualTo("connected");

        LocalRunner.Agent agent = LocalRunner.Agent.builder()
                .name("my-agent")
                .description("A test agent")
                .language("python")
                .timeout(60)
                .build();
        runnersClient.registerAgents(runnerId, Map.of("my-agent", agent), API_KEY, TEST_WORKSPACE);

        LocalRunner runnerWithAgents = runnersClient.getRunner(runnerId, API_KEY, TEST_WORKSPACE);
        assertThat(runnerWithAgents.agents()).hasSize(1);
        assertThat(runnerWithAgents.agents().getFirst().name()).isEqualTo("my-agent");

        LocalRunnerHeartbeatResponse heartbeatResponse = runnersClient.heartbeat(runnerId, API_KEY, TEST_WORKSPACE);
        assertThat(heartbeatResponse.cancelledJobIds()).isEmpty();

        CreateLocalRunnerJobRequest createJobRequest = CreateLocalRunnerJobRequest.builder()
                .agentName("my-agent")
                .runnerId(runnerId)
                .project("default")
                .inputs(new ObjectMapper().createObjectNode().put("prompt", "hello"))
                .build();
        UUID jobId = runnersClient.createJob(createJobRequest, API_KEY, TEST_WORKSPACE);
        assertThat(jobId).isNotNull();

        LocalRunnerJob createdJob = runnersClient.getJob(jobId, API_KEY, TEST_WORKSPACE);
        assertThat(createdJob.status().getValue()).isEqualTo("pending");
        assertThat(createdJob.agentName()).isEqualTo("my-agent");
        assertThat(createdJob.runnerId()).isEqualTo(runnerId);

        try (var response = runnersClient.callNextJob(runnerId, API_KEY, TEST_WORKSPACE)) {
            assertThat(response.getStatus()).isEqualTo(200);
            LocalRunnerJob nextJob = response.readEntity(LocalRunnerJob.class);
            assertThat(nextJob.id()).isEqualTo(jobId);
            assertThat(nextJob.status().getValue()).isEqualTo("running");
        }

        LocalRunnerJob runningJob = runnersClient.getJob(jobId, API_KEY, TEST_WORKSPACE);
        assertThat(runningJob.status().getValue()).isEqualTo("running");
        assertThat(runningJob.startedAt()).isNotNull();

        LocalRunnerJob.LocalRunnerJobPage jobPage = runnersClient.listJobs(runnerId, null, 0, 10,
                API_KEY, TEST_WORKSPACE);
        assertThat(jobPage.total()).isEqualTo(1);
        assertThat(jobPage.content()).hasSize(1);
        assertThat(jobPage.content().getFirst().id()).isEqualTo(jobId);

        List<LocalRunnerLogEntry> logEntries = List.of(
                LocalRunnerLogEntry.builder().stream("stdout").text("Starting agent...").build(),
                LocalRunnerLogEntry.builder().stream("stdout").text("Processing complete.").build());
        runnersClient.appendLogs(jobId, logEntries, API_KEY, TEST_WORKSPACE);

        List<LocalRunnerLogEntry> logs = runnersClient.getJobLogs(jobId, 0, API_KEY, TEST_WORKSPACE);
        assertThat(logs).hasSize(2);
        assertThat(logs.get(0).text()).isEqualTo("Starting agent...");
        assertThat(logs.get(1).text()).isEqualTo("Processing complete.");

        JsonNode resultPayload = new ObjectMapper().createObjectNode().put("answer", "world");
        LocalRunnerJobResultRequest resultRequest = LocalRunnerJobResultRequest.builder()
                .status(LocalRunnerJobStatus.COMPLETED)
                .result(resultPayload)
                .traceId(randomUUID())
                .build();
        runnersClient.reportResult(jobId, resultRequest, API_KEY, TEST_WORKSPACE);

        LocalRunnerJob completedJob = runnersClient.getJob(jobId, API_KEY, TEST_WORKSPACE);
        assertThat(completedJob.status().getValue()).isEqualTo("completed");
        assertThat(completedJob.completedAt()).isNotNull();
        assertThat(completedJob.result().get("answer").asText()).isEqualTo("world");
        assertThat(completedJob.traceId()).isEqualTo(resultRequest.traceId());
    }

    @Test
    @DisplayName("Connect with API key (no pairing code), create and cancel job")
    void apiKeyConnectAndCancelJob() {
        LocalRunnerConnectRequest connectRequest = LocalRunnerConnectRequest.builder()
                .runnerName("direct-runner")
                .build();
        UUID runnerId = runnersClient.connect(connectRequest, API_KEY, TEST_WORKSPACE);
        assertThat(runnerId).isNotNull();

        runnersClient.heartbeat(runnerId, API_KEY, TEST_WORKSPACE);

        CreateLocalRunnerJobRequest request = CreateLocalRunnerJobRequest.builder()
                .agentName("agent-x")
                .runnerId(runnerId)
                .build();
        UUID jobId = runnersClient.createJob(request, API_KEY, TEST_WORKSPACE);

        LocalRunnerJob job = runnersClient.getJob(jobId, API_KEY, TEST_WORKSPACE);
        assertThat(job.status().getValue()).isEqualTo("pending");

        runnersClient.cancelJob(jobId, API_KEY, TEST_WORKSPACE);

        LocalRunnerJob cancelledJob = runnersClient.getJob(jobId, API_KEY, TEST_WORKSPACE);
        assertThat(cancelledJob.status().getValue()).isEqualTo("cancelled");
        assertThat(cancelledJob.completedAt()).isNotNull();
    }

    @Nested
    @DisplayName("Generate Pairing Code")
    class GeneratePairingCode {

        @Test
        void createsValidCode() {
            LocalRunnerPairResponse resp = runnersClient.generatePairingCode(API_KEY, TEST_WORKSPACE);

            assertThat(resp.pairingCode()).hasSize(6);
            assertThat(resp.pairingCode()).matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}");
            assertThat(resp.runnerId()).isNotNull();
            assertThat(resp.expiresInSeconds()).isEqualTo(300);
        }
    }

    @Nested
    @DisplayName("Connect")
    class Connect {

        @Test
        void withExpiredPairingCode_returns400() {
            LocalRunnerConnectRequest req = LocalRunnerConnectRequest.builder()
                    .pairingCode("ZZZZZZ")
                    .runnerName("runner")
                    .build();

            try (var response = runnersClient.callConnect(req, API_KEY, TEST_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(400);
            }
        }

        @Test
        void withPairingCodeFromDifferentWorkspace_returns400() {
            LocalRunnerPairResponse pair = runnersClient.generatePairingCode(API_KEY, TEST_WORKSPACE);

            LocalRunnerConnectRequest req = LocalRunnerConnectRequest.builder()
                    .pairingCode(pair.pairingCode())
                    .runnerName("runner")
                    .build();

            try (var response = runnersClient.callConnect(req, OTHER_API_KEY, OTHER_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(400);
            }
        }
    }

    @Nested
    @DisplayName("List Runners")
    class ListRunners {

        @Test
        void returnsConnectedRunners() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("list-runner-1", ctx.apiKey, ctx.workspace);

            LocalRunner.LocalRunnerPage page = runnersClient.listRunners(ctx.apiKey, ctx.workspace);
            assertThat(page.content()).hasSize(1);
            assertThat(page.content().get(0).id()).isEqualTo(runnerId);
            assertThat(page.content().get(0).status().getValue()).isEqualTo("connected");
        }

        @Test
        void showsDisconnectedWhenHeartbeatExpired() throws InterruptedException {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("list-runner-disconnect", ctx.apiKey, ctx.workspace);
            waitForHeartbeatExpiry();

            LocalRunner.LocalRunnerPage page = runnersClient.listRunners(ctx.apiKey, ctx.workspace);
            LocalRunner runner = page.content().stream()
                    .filter(r -> r.id().equals(runnerId)).findFirst().orElseThrow();
            assertThat(runner.status().getValue()).isEqualTo("disconnected");
        }

        @Test
        void excludesOtherWorkspaces() {
            var ctx1 = createIsolatedWorkspace();
            var ctx2 = createIsolatedWorkspace();
            UUID runnerId = connectRunner("list-runner-mine", ctx1.apiKey, ctx1.workspace);
            connectRunner("other-ws-runner", ctx2.apiKey, ctx2.workspace);

            LocalRunner.LocalRunnerPage page = runnersClient.listRunners(ctx2.apiKey, ctx2.workspace);
            assertThat(page.content()).extracting(LocalRunner::id).doesNotContain(runnerId);
        }

        @Test
        void emptyWhenNoRunners() {
            var ctx = createIsolatedWorkspace();

            LocalRunner.LocalRunnerPage page = runnersClient.listRunners(ctx.apiKey, ctx.workspace);
            assertThat(page.content()).isEmpty();
        }

        @Test
        void paginatesCorrectly() {
            String sharedWorkspace = randomUUID().toString();
            String sharedWorkspaceId = randomUUID().toString();
            String userName = randomUUID().toString();
            String apiKey = randomUUID().toString();
            mockTargetWorkspace(apiKey, sharedWorkspace, sharedWorkspaceId, userName);

            for (int i = 0; i < 3; i++) {
                LocalRunnerConnectRequest req = LocalRunnerConnectRequest.builder()
                        .runnerName("paginate-runner-" + i)
                        .build();
                UUID rid = runnersClient.connect(req, apiKey, sharedWorkspace);
                runnersClient.heartbeat(rid, apiKey, sharedWorkspace);
            }

            LocalRunner.LocalRunnerPage page0 = runnersClient.listRunners(0, 2, apiKey, sharedWorkspace);
            assertThat(page0.content()).hasSize(2);
            assertThat(page0.total()).isEqualTo(3);

            LocalRunner.LocalRunnerPage page1 = runnersClient.listRunners(1, 2, apiKey, sharedWorkspace);
            assertThat(page1.content()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Get Runner")
    class GetRunner {

        @Test
        void returnsRunnerWithAgents() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("get-runner-agents", ctx.apiKey, ctx.workspace);

            LocalRunner.Agent agentInput = LocalRunner.Agent.builder()
                    .project("my-project")
                    .description("Summarizes documents")
                    .language("python")
                    .executable("/usr/bin/python3.11")
                    .sourceFile("agents/summarizer.py")
                    .build();
            runnersClient.registerAgents(runnerId, Map.of("agent1", agentInput), ctx.apiKey, ctx.workspace);

            LocalRunner runner = runnersClient.getRunner(runnerId, ctx.apiKey, ctx.workspace);
            assertThat(runner.id()).isEqualTo(runnerId);
            assertThat(runner.status().getValue()).isEqualTo("connected");
            assertThat(runner.agents()).hasSize(1);
            LocalRunner.Agent agent = runner.agents().get(0);
            assertThat(agent.name()).isEqualTo("agent1");
            assertThat(agent.project()).isEqualTo("my-project");
            assertThat(agent.description()).isEqualTo("Summarizes documents");
            assertThat(agent.language()).isEqualTo("python");
            assertThat(agent.executable()).isEqualTo("/usr/bin/python3.11");
            assertThat(agent.sourceFile()).isEqualTo("agents/summarizer.py");
        }

        @Test
        void returnsEmptyAgentsWhenDisconnected() throws InterruptedException {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("get-runner-disconn", ctx.apiKey, ctx.workspace);

            LocalRunner.Agent agent = LocalRunner.Agent.builder().project("my-project").build();
            runnersClient.registerAgents(runnerId, Map.of("agent1", agent), ctx.apiKey, ctx.workspace);

            waitForHeartbeatExpiry();

            LocalRunner runner = runnersClient.getRunner(runnerId, ctx.apiKey, ctx.workspace);
            assertThat(runner.status().getValue()).isEqualTo("disconnected");
            assertThat(runner.agents()).isEmpty();
        }

        @Test
        void throwsNotFoundForMissing() {
            try (var response = runnersClient.callGetRunner(randomUUID(), API_KEY, TEST_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(404);
            }
        }

        @Test
        void throwsNotFoundForWrongWorkspace() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("get-runner-wrong-ws", ctx.apiKey, ctx.workspace);

            try (var response = runnersClient.callGetRunner(runnerId, OTHER_API_KEY, OTHER_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(404);
            }
        }
    }

    @Nested
    @DisplayName("Register Agents")
    class RegisterAgents {

        @Test
        void storesAndReturnsAgents() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("reg-agents-store", ctx.apiKey, ctx.workspace);

            LocalRunner.Agent agent = LocalRunner.Agent.builder().project("proj1").build();
            runnersClient.registerAgents(runnerId, Map.of("agent1", agent), ctx.apiKey, ctx.workspace);

            LocalRunner runner = runnersClient.getRunner(runnerId, ctx.apiKey, ctx.workspace);
            assertThat(runner.agents()).hasSize(1);
            assertThat(runner.agents().get(0).name()).isEqualTo("agent1");
        }

        @Test
        void replacesExistingAgents() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("reg-agents-replace", ctx.apiKey, ctx.workspace);

            LocalRunner.Agent a = LocalRunner.Agent.builder().project("proj1").build();
            runnersClient.registerAgents(runnerId, Map.of("agent1", a), ctx.apiKey, ctx.workspace);

            LocalRunner.Agent b = LocalRunner.Agent.builder().project("proj2").build();
            runnersClient.registerAgents(runnerId, Map.of("agent2", b), ctx.apiKey, ctx.workspace);

            LocalRunner runner = runnersClient.getRunner(runnerId, ctx.apiKey, ctx.workspace);
            assertThat(runner.agents()).hasSize(1);
            assertThat(runner.agents().get(0).name()).isEqualTo("agent2");
        }

        @Test
        void clearsAgentsWhenEmpty() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("reg-agents-clear", ctx.apiKey, ctx.workspace);

            LocalRunner.Agent a = LocalRunner.Agent.builder().build();
            runnersClient.registerAgents(runnerId, Map.of("agent1", a), ctx.apiKey, ctx.workspace);
            runnersClient.registerAgents(runnerId, Map.of(), ctx.apiKey, ctx.workspace);

            LocalRunner runner = runnersClient.getRunner(runnerId, ctx.apiKey, ctx.workspace);
            assertThat(runner.agents()).isEmpty();
        }

        @Test
        void storesAndReturnsAgentTimeout() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("reg-agents-timeout", ctx.apiKey, ctx.workspace);

            LocalRunner.Agent agent = LocalRunner.Agent.builder().project("proj1").timeout(120).build();
            runnersClient.registerAgents(runnerId, Map.of("agent1", agent), ctx.apiKey, ctx.workspace);

            LocalRunner runner = runnersClient.getRunner(runnerId, ctx.apiKey, ctx.workspace);
            assertThat(runner.agents()).hasSize(1);
            assertThat(runner.agents().get(0).timeout()).isEqualTo(120);
        }

        @Test
        void returnsZeroTimeoutWhenNotSet() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("reg-agents-no-timeout", ctx.apiKey, ctx.workspace);

            LocalRunner.Agent agent = LocalRunner.Agent.builder().project("proj1").build();
            runnersClient.registerAgents(runnerId, Map.of("agent1", agent), ctx.apiKey, ctx.workspace);

            LocalRunner runner = runnersClient.getRunner(runnerId, ctx.apiKey, ctx.workspace);
            assertThat(runner.agents().get(0).timeout()).isEqualTo(0);
        }

        @Test
        void throwsNotFoundForWrongWorkspace() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("reg-agents-wrong-ws", ctx.apiKey, ctx.workspace);

            LocalRunner.Agent agent = LocalRunner.Agent.builder().build();
            try (var response = runnersClient.callRegisterAgents(runnerId, Map.of("a", agent),
                    OTHER_API_KEY, OTHER_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(404);
            }
        }
    }

    @Nested
    @DisplayName("Heartbeat")
    class Heartbeat {

        @Test
        void returnsCancelledJobIds() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("hb-cancelled", ctx.apiKey, ctx.workspace);
            UUID jobId = createRunningJob(runnerId, AGENT_NAME, ctx.apiKey, ctx.workspace);
            runnersClient.cancelJob(jobId, ctx.apiKey, ctx.workspace);

            LocalRunnerHeartbeatResponse resp = runnersClient.heartbeat(runnerId, ctx.apiKey, ctx.workspace);
            assertThat(resp.cancelledJobIds()).contains(jobId);

            LocalRunnerHeartbeatResponse resp2 = runnersClient.heartbeat(runnerId, ctx.apiKey, ctx.workspace);
            assertThat(resp2.cancelledJobIds()).isEmpty();
        }

        @Test
        void returnsEmptyWhenNoCancellations() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("hb-no-cancel", ctx.apiKey, ctx.workspace);

            LocalRunnerHeartbeatResponse resp = runnersClient.heartbeat(runnerId, ctx.apiKey, ctx.workspace);
            assertThat(resp.cancelledJobIds()).isEmpty();
        }

        @Test
        void throwsGoneForEvictedRunner() {
            var ctx = createIsolatedWorkspace();
            UUID oldRunnerId = connectRunner("hb-evicted-old", ctx.apiKey, ctx.workspace);
            connectRunner("hb-evicted-new", ctx.apiKey, ctx.workspace);

            try (var response = runnersClient.callHeartbeat(oldRunnerId, ctx.apiKey, ctx.workspace)) {
                assertThat(response.getStatus()).isEqualTo(410);
            }
        }

        @Test
        void throwsGoneForDeletedRunner() {
            try (var response = runnersClient.callHeartbeat(randomUUID(), API_KEY, TEST_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(410);
            }
        }

        @Test
        void throwsNotFoundForWrongWorkspace() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("hb-wrong-ws", ctx.apiKey, ctx.workspace);

            try (var response = runnersClient.callHeartbeat(runnerId, OTHER_API_KEY, OTHER_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(404);
            }
        }
    }

    @Nested
    @DisplayName("Create Job")
    class CreateJob {

        @Test
        void usesUserDefaultRunner() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("cj-default-runner", ctx.apiKey, ctx.workspace);

            CreateLocalRunnerJobRequest req = CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME)
                    .build();
            UUID jobId = runnersClient.createJob(req, ctx.apiKey, ctx.workspace);

            LocalRunnerJob job = runnersClient.getJob(jobId, ctx.apiKey, ctx.workspace);
            assertThat(job.runnerId()).isEqualTo(runnerId);
        }

        @Test
        void usesExplicitRunnerId() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("cj-explicit-runner", ctx.apiKey, ctx.workspace);

            CreateLocalRunnerJobRequest req = CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME)
                    .runnerId(runnerId)
                    .build();
            UUID jobId = runnersClient.createJob(req, ctx.apiKey, ctx.workspace);

            LocalRunnerJob job = runnersClient.getJob(jobId, ctx.apiKey, ctx.workspace);
            assertThat(job.runnerId()).isEqualTo(runnerId);
        }

        @Test
        void throwsNotFoundWhenNoRunner() {
            var ctx = createIsolatedWorkspace();

            CreateLocalRunnerJobRequest req = CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME)
                    .build();

            try (var response = runnersClient.callCreateJob(req, ctx.apiKey, ctx.workspace)) {
                assertThat(response.getStatus()).isEqualTo(404);
            }
        }

        @Test
        void throwsConflictWhenRunnerOffline() throws InterruptedException {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("cj-offline", ctx.apiKey, ctx.workspace);
            waitForHeartbeatExpiry();

            CreateLocalRunnerJobRequest req = CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME)
                    .runnerId(runnerId)
                    .build();

            try (var response = runnersClient.callCreateJob(req, ctx.apiKey, ctx.workspace)) {
                assertThat(response.getStatus()).isEqualTo(409);
            }
        }

        @Test
        void throwsTooManyRequests() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("cj-too-many", ctx.apiKey, ctx.workspace);

            for (int i = 0; i < 3; i++) {
                runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                        .agentName(AGENT_NAME).runnerId(runnerId).build(), ctx.apiKey, ctx.workspace);
            }

            try (var response = runnersClient.callCreateJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).runnerId(runnerId).build(), ctx.apiKey, ctx.workspace)) {
                assertThat(response.getStatus()).isEqualTo(429);
            }
        }

        @Test
        void defaultsProjectToDefault() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("cj-default-project", ctx.apiKey, ctx.workspace);

            CreateLocalRunnerJobRequest req = CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME)
                    .runnerId(runnerId)
                    .build();
            UUID jobId = runnersClient.createJob(req, ctx.apiKey, ctx.workspace);

            LocalRunnerJob job = runnersClient.getJob(jobId, ctx.apiKey, ctx.workspace);
            assertThat(job.project()).isEqualTo("default");
        }

        @Test
        void usesAgentTimeoutWhenSet() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("cj-agent-timeout", ctx.apiKey, ctx.workspace);

            LocalRunner.Agent agent = LocalRunner.Agent.builder().timeout(300).build();
            runnersClient.registerAgents(runnerId, Map.of(AGENT_NAME, agent), ctx.apiKey, ctx.workspace);

            UUID jobId = runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).runnerId(runnerId).build(), ctx.apiKey, ctx.workspace);

            LocalRunnerJob job = runnersClient.getJob(jobId, ctx.apiKey, ctx.workspace);
            assertThat(job.timeout()).isEqualTo(300);
        }

        @Test
        void fallsBackToConfigTimeoutWhenAgentHasNone() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("cj-config-timeout", ctx.apiKey, ctx.workspace);

            LocalRunner.Agent agent = LocalRunner.Agent.builder().project("proj1").build();
            runnersClient.registerAgents(runnerId, Map.of(AGENT_NAME, agent), ctx.apiKey, ctx.workspace);

            UUID jobId = runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).runnerId(runnerId).build(), ctx.apiKey, ctx.workspace);

            LocalRunnerJob job = runnersClient.getJob(jobId, ctx.apiKey, ctx.workspace);
            assertThat(job.timeout()).isPositive();
        }

        @Test
        void storesMaskIdAndMetadata() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("cj-mask-meta", ctx.apiKey, ctx.workspace);
            UUID maskId = randomUUID();
            var metadata = LocalRunnerJobMetadata.builder()
                    .datasetId(randomUUID())
                    .datasetVersionId(randomUUID())
                    .datasetItemVersionId(randomUUID())
                    .datasetItemId(randomUUID())
                    .build();

            UUID jobId = runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).runnerId(runnerId).maskId(maskId).metadata(metadata).build(),
                    ctx.apiKey, ctx.workspace);

            LocalRunnerJob job = runnersClient.getJob(jobId, ctx.apiKey, ctx.workspace);
            assertThat(job.maskId()).isEqualTo(maskId);
            assertThat(job.metadata()).isEqualTo(metadata);
        }

        @Test
        void storesMaskIdAndMetadataNullWhenNotProvided() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("cj-no-mask-meta", ctx.apiKey, ctx.workspace);

            UUID jobId = runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).runnerId(runnerId).build(),
                    ctx.apiKey, ctx.workspace);

            LocalRunnerJob job = runnersClient.getJob(jobId, ctx.apiKey, ctx.workspace);
            assertThat(job.maskId()).isNull();
            assertThat(job.metadata()).isNull();
        }

        @Test
        void fallsBackToConfigTimeoutWhenNoAgentsRegistered() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("cj-no-agents", ctx.apiKey, ctx.workspace);

            UUID jobId = runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).runnerId(runnerId).build(), ctx.apiKey, ctx.workspace);

            LocalRunnerJob job = runnersClient.getJob(jobId, ctx.apiKey, ctx.workspace);
            assertThat(job.timeout()).isPositive();
        }
    }

    @Nested
    @DisplayName("Next Job")
    class NextJob {

        @Test
        void returnsJobWhenPending() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("nj-pending", ctx.apiKey, ctx.workspace);

            UUID jobId = runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).runnerId(runnerId).build(), ctx.apiKey, ctx.workspace);

            try (var response = runnersClient.callNextJob(runnerId, ctx.apiKey, ctx.workspace)) {
                assertThat(response.getStatus()).isEqualTo(200);
                LocalRunnerJob claimed = response.readEntity(LocalRunnerJob.class);
                assertThat(claimed.id()).isEqualTo(jobId);
                assertThat(claimed.status().getValue()).isEqualTo("running");
                assertThat(claimed.startedAt()).isNotNull();
            }
        }

        @Test
        void returnsMaskIdAndMetadataWhenClaimed() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("nj-mask-meta", ctx.apiKey, ctx.workspace);
            UUID maskId = randomUUID();
            var metadata = LocalRunnerJobMetadata.builder()
                    .datasetId(randomUUID())
                    .datasetVersionId(randomUUID())
                    .datasetItemVersionId(randomUUID())
                    .datasetItemId(randomUUID())
                    .build();

            runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).runnerId(runnerId).maskId(maskId).metadata(metadata).build(),
                    ctx.apiKey, ctx.workspace);

            LocalRunnerJob claimed = runnersClient.nextJob(runnerId, ctx.apiKey, ctx.workspace);
            assertThat(claimed.maskId()).isEqualTo(maskId);
            assertThat(claimed.metadata()).isEqualTo(metadata);
        }

        @Test
        void returnsEmptyWhenNoPendingJobs() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("nj-empty", ctx.apiKey, ctx.workspace);

            try (var response = runnersClient.callNextJob(runnerId, ctx.apiKey, ctx.workspace)) {
                assertThat(response.getStatus()).isIn(200, 204);
                if (response.getStatus() == 200) {
                    assertThat(response.hasEntity()).isFalse();
                }
            }
        }

        @Test
        void throwsNotFoundForWrongWorkspace() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("nj-wrong-ws", ctx.apiKey, ctx.workspace);

            try (var response = runnersClient.callNextJob(runnerId, OTHER_API_KEY, OTHER_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(404);
            }
        }
    }

    @Nested
    @DisplayName("List Jobs")
    class ListJobs {

        @Test
        void returnsJobsForRunner() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("lj-runner", ctx.apiKey, ctx.workspace);
            runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).runnerId(runnerId).build(), ctx.apiKey, ctx.workspace);
            runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).runnerId(runnerId).build(), ctx.apiKey, ctx.workspace);

            LocalRunnerJob.LocalRunnerJobPage page = runnersClient.listJobs(runnerId, null, 0, 10,
                    ctx.apiKey, ctx.workspace);
            assertThat(page.content()).hasSize(2);
            assertThat(page.total()).isEqualTo(2);
        }

        @Test
        void returnsMaskIdAndMetadataInList() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("lj-mask-meta", ctx.apiKey, ctx.workspace);
            UUID maskId = randomUUID();
            var metadata = LocalRunnerJobMetadata.builder()
                    .datasetId(randomUUID())
                    .datasetVersionId(randomUUID())
                    .datasetItemVersionId(randomUUID())
                    .datasetItemId(randomUUID())
                    .build();

            runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).runnerId(runnerId).maskId(maskId).metadata(metadata).build(),
                    ctx.apiKey, ctx.workspace);

            LocalRunnerJob.LocalRunnerJobPage page = runnersClient.listJobs(runnerId, null, 0, 10,
                    ctx.apiKey, ctx.workspace);
            assertThat(page.content()).hasSize(1);
            assertThat(page.content().getFirst().maskId()).isEqualTo(maskId);
            assertThat(page.content().getFirst().metadata()).isEqualTo(metadata);
        }

        @Test
        void filtersByProject() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("lj-filter", ctx.apiKey, ctx.workspace);
            runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).runnerId(runnerId).project("proj-a").build(), ctx.apiKey, ctx.workspace);
            runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).runnerId(runnerId).project("proj-b").build(), ctx.apiKey, ctx.workspace);

            LocalRunnerJob.LocalRunnerJobPage page = runnersClient.listJobs(runnerId, "proj-a", 0, 10,
                    ctx.apiKey, ctx.workspace);
            assertThat(page.content()).hasSize(1);
            assertThat(page.content().get(0).project()).isEqualTo("proj-a");
        }

        @Test
        void paginatesCorrectly() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("lj-paginate", ctx.apiKey, ctx.workspace);
            for (int i = 0; i < 3; i++) {
                runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                        .agentName(AGENT_NAME).runnerId(runnerId).build(), ctx.apiKey, ctx.workspace);
            }

            LocalRunnerJob.LocalRunnerJobPage page0 = runnersClient.listJobs(runnerId, null, 0, 2,
                    ctx.apiKey, ctx.workspace);
            assertThat(page0.content()).hasSize(2);
            assertThat(page0.total()).isEqualTo(3);

            LocalRunnerJob.LocalRunnerJobPage page1 = runnersClient.listJobs(runnerId, null, 1, 2,
                    ctx.apiKey, ctx.workspace);
            assertThat(page1.content()).hasSize(1);
        }

        @Test
        void sortsByCreatedAtDescending() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("lj-sort", ctx.apiKey, ctx.workspace);
            runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).runnerId(runnerId).build(), ctx.apiKey, ctx.workspace);
            runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).runnerId(runnerId).build(), ctx.apiKey, ctx.workspace);

            LocalRunnerJob.LocalRunnerJobPage page = runnersClient.listJobs(runnerId, null, 0, 10,
                    ctx.apiKey, ctx.workspace);
            assertThat(page.content()).hasSize(2);
            assertThat(page.content().get(0).createdAt())
                    .isAfterOrEqualTo(page.content().get(1).createdAt());
        }
    }

    @Nested
    @DisplayName("Get Job")
    class GetJob {

        @Test
        void returnsJob() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("gj-runner", ctx.apiKey, ctx.workspace);
            UUID jobId = runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).runnerId(runnerId).build(), ctx.apiKey, ctx.workspace);

            LocalRunnerJob fetched = runnersClient.getJob(jobId, ctx.apiKey, ctx.workspace);
            assertThat(fetched.id()).isEqualTo(jobId);
            assertThat(fetched.agentName()).isEqualTo(AGENT_NAME);
        }

        @Test
        void throwsNotFoundForMissing() {
            try (var response = runnersClient.callGetJob(randomUUID(), API_KEY, TEST_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(404);
            }
        }

        @Test
        void throwsNotFoundForWrongWorkspace() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("gj-wrong-ws", ctx.apiKey, ctx.workspace);
            UUID jobId = runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).runnerId(runnerId).build(), ctx.apiKey, ctx.workspace);

            try (var response = runnersClient.callGetJob(jobId, OTHER_API_KEY, OTHER_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(404);
            }
        }
    }

    @Nested
    @DisplayName("Get Job Logs")
    class GetJobLogs {

        @Test
        void returnsAllLogs() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("gl-all", ctx.apiKey, ctx.workspace);
            UUID jobId = runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).runnerId(runnerId).build(), ctx.apiKey, ctx.workspace);

            List<LocalRunnerLogEntry> entries = List.of(
                    LocalRunnerLogEntry.builder().stream("stdout").text("line1").build(),
                    LocalRunnerLogEntry.builder().stream("stderr").text("line2").build());
            runnersClient.appendLogs(jobId, entries, ctx.apiKey, ctx.workspace);

            List<LocalRunnerLogEntry> logs = runnersClient.getJobLogs(jobId, 0, ctx.apiKey, ctx.workspace);
            assertThat(logs).hasSize(2);
            assertThat(logs.get(0).text()).isEqualTo("line1");
            assertThat(logs.get(1).text()).isEqualTo("line2");
        }

        @Test
        void returnsLogsFromOffset() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("gl-offset", ctx.apiKey, ctx.workspace);
            UUID jobId = runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).runnerId(runnerId).build(), ctx.apiKey, ctx.workspace);

            List<LocalRunnerLogEntry> entries = List.of(
                    LocalRunnerLogEntry.builder().stream("stdout").text("line1").build(),
                    LocalRunnerLogEntry.builder().stream("stdout").text("line2").build(),
                    LocalRunnerLogEntry.builder().stream("stdout").text("line3").build());
            runnersClient.appendLogs(jobId, entries, ctx.apiKey, ctx.workspace);

            List<LocalRunnerLogEntry> logs = runnersClient.getJobLogs(jobId, 2, ctx.apiKey, ctx.workspace);
            assertThat(logs).hasSize(1);
            assertThat(logs.get(0).text()).isEqualTo("line3");
        }

        @Test
        void returnsEmptyWhenOffsetBeyondEnd() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("gl-beyond", ctx.apiKey, ctx.workspace);
            UUID jobId = runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).runnerId(runnerId).build(), ctx.apiKey, ctx.workspace);

            runnersClient.appendLogs(jobId,
                    List.of(LocalRunnerLogEntry.builder().stream("stdout").text("line1").build()),
                    ctx.apiKey, ctx.workspace);

            List<LocalRunnerLogEntry> logs = runnersClient.getJobLogs(jobId, 10, ctx.apiKey, ctx.workspace);
            assertThat(logs).isEmpty();
        }

        @Test
        void throwsNotFoundForMissingJob() {
            try (var response = runnersClient.callGetJobLogs(randomUUID(), 0, API_KEY, TEST_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(404);
            }
        }

        @Test
        void throwsNotFoundForWrongWorkspace() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("gl-wrong-ws", ctx.apiKey, ctx.workspace);
            UUID jobId = runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).runnerId(runnerId).build(), ctx.apiKey, ctx.workspace);

            try (var response = runnersClient.callGetJobLogs(jobId, 0, OTHER_API_KEY, OTHER_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(404);
            }
        }
    }

    @Nested
    @DisplayName("Report Result")
    class ReportResult {

        @Test
        void throwsBadRequestForInvalidStatus() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("rr-bad-status", ctx.apiKey, ctx.workspace);
            UUID jobId = createRunningJob(runnerId, AGENT_NAME, ctx.apiKey, ctx.workspace);

            try (var response = runnersClient.callReportResult(jobId,
                    LocalRunnerJobResultRequest.builder().status(LocalRunnerJobStatus.RUNNING).build(),
                    ctx.apiKey, ctx.workspace)) {
                assertThat(response.getStatus()).isEqualTo(400);
            }
        }

        @Test
        void throwsNotFoundForMissing() {
            try (var response = runnersClient.callReportResult(randomUUID(),
                    LocalRunnerJobResultRequest.builder().status(LocalRunnerJobStatus.COMPLETED).build(),
                    API_KEY, TEST_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(404);
            }
        }

        @Test
        void throwsNotFoundForWrongWorkspace() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("rr-wrong-ws", ctx.apiKey, ctx.workspace);
            UUID jobId = runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).runnerId(runnerId).build(), ctx.apiKey, ctx.workspace);

            try (var response = runnersClient.callReportResult(jobId,
                    LocalRunnerJobResultRequest.builder().status(LocalRunnerJobStatus.COMPLETED).build(),
                    OTHER_API_KEY, OTHER_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(404);
            }
        }
    }

    @Nested
    @DisplayName("Cancel Job")
    class CancelJob {

        @Test
        void throwsNotFoundForMissing() {
            try (var response = runnersClient.callCancelJob(randomUUID(), API_KEY, TEST_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(404);
            }
        }

        @Test
        void throwsNotFoundForWrongWorkspace() {
            var ctx = createIsolatedWorkspace();
            UUID runnerId = connectRunner("cj-cancel-wrong-ws", ctx.apiKey, ctx.workspace);
            UUID jobId = runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).runnerId(runnerId).build(), ctx.apiKey, ctx.workspace);

            try (var response = runnersClient.callCancelJob(jobId, OTHER_API_KEY, OTHER_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(404);
            }
        }
    }
}
