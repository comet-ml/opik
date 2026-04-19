package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.connect.ActivateRequest;
import com.comet.opik.api.connect.CreateSessionRequest;
import com.comet.opik.api.connect.CreateSessionResponse;
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
import com.comet.opik.api.resources.utils.resources.PairingResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.runner.BridgeCommand;
import com.comet.opik.api.runner.BridgeCommandBatchResponse;
import com.comet.opik.api.runner.BridgeCommandNextRequest;
import com.comet.opik.api.runner.BridgeCommandResultRequest;
import com.comet.opik.api.runner.BridgeCommandStatus;
import com.comet.opik.api.runner.BridgeCommandSubmitRequest;
import com.comet.opik.api.runner.BridgeCommandSubmitResponse;
import com.comet.opik.api.runner.BridgeCommandType;
import com.comet.opik.api.runner.CreateLocalRunnerJobRequest;
import com.comet.opik.api.runner.LocalRunner;
import com.comet.opik.api.runner.LocalRunnerHeartbeatResponse;
import com.comet.opik.api.runner.LocalRunnerJob;
import com.comet.opik.api.runner.LocalRunnerJobMetadata;
import com.comet.opik.api.runner.LocalRunnerJobResultRequest;
import com.comet.opik.api.runner.LocalRunnerJobStatus;
import com.comet.opik.api.runner.LocalRunnerLogEntry;
import com.comet.opik.api.runner.LocalRunnerStatus;
import com.comet.opik.api.runner.RunnerType;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
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
                                new CustomConfig("localRunner.maxPendingJobsPerRunner", "3"),
                                new CustomConfig("localRunner.bridgePollTimeout", "2s"),
                                new CustomConfig("localRunner.bridgeMaxPendingPerRunner", "3")))
                        .build());
    }

    private LocalRunnersResourceClient runnersClient;
    private PairingResourceClient pairingClient;
    private ProjectResourceClient projectClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        var baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);
        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID, USER);
        mockTargetWorkspace(OTHER_API_KEY, OTHER_WORKSPACE, OTHER_WORKSPACE_ID, OTHER_USER);

        this.runnersClient = new LocalRunnersResourceClient(client, baseURI);
        this.pairingClient = new PairingResourceClient(client, baseURI);
        this.projectClient = new ProjectResourceClient(client, baseURI, PodamFactoryUtils.newPodamFactory());
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

    private UUID createProject(String apiKey, String workspace) {
        return projectClient.createProject("test-project-" + randomUUID(), apiKey, workspace);
    }

    private static byte[] randomActivationKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static String computeHmac(UUID sessionId, byte[] activationKey, String runnerName) {
        try {
            byte[] sessionIdBytes = uuidToBytes(sessionId);
            byte[] runnerNameHash = MessageDigest.getInstance("SHA-256")
                    .digest(runnerName.getBytes(StandardCharsets.UTF_8));
            byte[] message = new byte[sessionIdBytes.length + runnerNameHash.length];
            System.arraycopy(sessionIdBytes, 0, message, 0, sessionIdBytes.length);
            System.arraycopy(runnerNameHash, 0, message, sessionIdBytes.length, runnerNameHash.length);

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(activationKey, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(message));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] uuidToBytes(UUID uuid) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return bb.array();
    }

    private UUID connectRunnerWithPairing(String name, UUID projectId, String apiKey, String workspace) {
        return connectRunnerWithPairing(name, projectId, apiKey, workspace, RunnerType.ENDPOINT);
    }

    private UUID connectRunnerWithPairing(String name, UUID projectId, String apiKey, String workspace,
            RunnerType type) {
        byte[] activationKey = randomActivationKey();
        CreateSessionRequest sessionReq = CreateSessionRequest.builder()
                .projectId(projectId)
                .activationKey(Base64.getEncoder().encodeToString(activationKey))
                .ttlSeconds(300)
                .type(type)
                .build();
        CreateSessionResponse session = pairingClient.createSession(sessionReq, apiKey, workspace);
        ActivateRequest activateReq = ActivateRequest.builder()
                .runnerName(name)
                .hmac(computeHmac(session.sessionId(), activationKey, name))
                .build();
        UUID runnerId = pairingClient.activate(session.sessionId(), activateReq, apiKey, workspace);
        LocalRunner.Agent agent = LocalRunner.Agent.builder()
                .name(AGENT_NAME)
                .build();
        runnersClient.registerAgents(runnerId, Map.of(AGENT_NAME, agent), apiKey, workspace);
        runnersClient.heartbeat(runnerId, apiKey, workspace);
        return runnerId;
    }

    private UUID createRunningJob(UUID runnerId, String agentName, UUID projectId, String apiKey, String workspace) {
        CreateLocalRunnerJobRequest req = CreateLocalRunnerJobRequest.builder()
                .agentName(agentName)
                .projectId(projectId)
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
    @DisplayName("opik endpoint happy path: pair → activate → register agents → heartbeat → create job → poll → logs → report result")
    void endpointHappyPath() {
        UUID projectId = createProject(API_KEY, TEST_WORKSPACE);

        byte[] activationKey = randomActivationKey();
        CreateSessionRequest sessionReq = CreateSessionRequest.builder()
                .projectId(projectId)
                .activationKey(Base64.getEncoder().encodeToString(activationKey))
                .ttlSeconds(300)
                .type(RunnerType.ENDPOINT)
                .build();
        CreateSessionResponse session = pairingClient.createSession(sessionReq, API_KEY, TEST_WORKSPACE);
        assertThat(session.sessionId()).isNotNull();
        assertThat(session.runnerId()).isNotNull();

        String runnerName = "test-runner";
        ActivateRequest activateReq = ActivateRequest.builder()
                .runnerName(runnerName)
                .hmac(computeHmac(session.sessionId(), activationKey, runnerName))
                .build();
        UUID runnerId = pairingClient.activate(session.sessionId(), activateReq, API_KEY, TEST_WORKSPACE);
        assertThat(runnerId).isEqualTo(session.runnerId());

        LocalRunner.LocalRunnerPage runnerPage = runnersClient.listRunners(projectId, API_KEY, TEST_WORKSPACE);
        assertThat(runnerPage.content()).extracting(LocalRunner::id).contains(runnerId);
        LocalRunner listedRunner = runnerPage.content().stream()
                .filter(r -> r.id().equals(runnerId)).findFirst().orElseThrow();
        assertThat(listedRunner.name()).isEqualTo("test-runner");
        assertThat(listedRunner.status().getValue()).isEqualTo("connected");
        assertThat(listedRunner.type()).isEqualTo(RunnerType.ENDPOINT);

        LocalRunner runner = runnersClient.getRunner(runnerId, API_KEY, TEST_WORKSPACE);
        assertThat(runner.id()).isEqualTo(runnerId);
        assertThat(runner.name()).isEqualTo("test-runner");
        assertThat(runner.status().getValue()).isEqualTo("connected");
        assertThat(runner.type()).isEqualTo(RunnerType.ENDPOINT);

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
                .projectId(projectId)
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
    @DisplayName("Connect with pairing code, create and cancel job")
    void pairingConnectAndCancelJob() {
        UUID projectId = createProject(API_KEY, TEST_WORKSPACE);
        UUID runnerId = connectRunnerWithPairing("cancel-runner", projectId, API_KEY, TEST_WORKSPACE);

        CreateLocalRunnerJobRequest request = CreateLocalRunnerJobRequest.builder()
                .agentName(AGENT_NAME)
                .projectId(projectId)
                .build();
        UUID jobId = runnersClient.createJob(request, API_KEY, TEST_WORKSPACE);

        LocalRunnerJob job = runnersClient.getJob(jobId, API_KEY, TEST_WORKSPACE);
        assertThat(job.status().getValue()).isEqualTo("pending");

        runnersClient.cancelJob(jobId, API_KEY, TEST_WORKSPACE);

        LocalRunnerJob cancelledJob = runnersClient.getJob(jobId, API_KEY, TEST_WORKSPACE);
        assertThat(cancelledJob.status().getValue()).isEqualTo("cancelled");
        assertThat(cancelledJob.completedAt()).isNotNull();
    }

    @Test
    @DisplayName("opik connect happy path: pair → activate → heartbeat with bridge → submit command → poll → report result")
    void connectHappyPath() {
        var ctx = createIsolatedWorkspace();
        UUID projectId = createProject(ctx.apiKey, ctx.workspace);

        byte[] activationKey = randomActivationKey();
        CreateSessionRequest sessionReq = CreateSessionRequest.builder()
                .projectId(projectId)
                .activationKey(Base64.getEncoder().encodeToString(activationKey))
                .ttlSeconds(300)
                .type(RunnerType.CONNECT)
                .build();
        CreateSessionResponse session = pairingClient.createSession(sessionReq, ctx.apiKey, ctx.workspace);
        assertThat(session.sessionId()).isNotNull();

        String runnerName = "connect-runner";
        ActivateRequest activateReq = ActivateRequest.builder()
                .runnerName(runnerName)
                .hmac(computeHmac(session.sessionId(), activationKey, runnerName))
                .build();
        UUID runnerId = pairingClient.activate(session.sessionId(), activateReq, ctx.apiKey, ctx.workspace);
        assertThat(runnerId).isEqualTo(session.runnerId());

        runnersClient.heartbeatWithCapabilities(runnerId, List.of("jobs", "bridge"), ctx.apiKey, ctx.workspace);

        LocalRunner runner = runnersClient.getRunner(runnerId, ctx.apiKey, ctx.workspace);
        assertThat(runner.type()).isEqualTo(RunnerType.CONNECT);
        assertThat(runner.status().getValue()).isEqualTo("connected");
        assertThat(runner.capabilities()).containsExactly("jobs", "bridge");

        ObjectMapper mapper = new ObjectMapper();
        BridgeCommandSubmitRequest submitReq = BridgeCommandSubmitRequest.builder()
                .type(BridgeCommandType.READ_FILE)
                .args(mapper.createObjectNode().put("path", "src/main.py"))
                .timeoutSeconds(30)
                .build();
        BridgeCommandSubmitResponse submitResp = runnersClient.createBridgeCommand(runnerId, submitReq,
                ctx.apiKey, ctx.workspace);
        UUID commandId = submitResp.commandId();
        assertThat(commandId).isNotNull();

        BridgeCommandBatchResponse batch = runnersClient.nextBridgeCommands(runnerId,
                BridgeCommandNextRequest.builder().maxCommands(10).build(), ctx.apiKey, ctx.workspace);
        assertThat(batch.commands()).hasSize(1);
        assertThat(batch.commands().getFirst().commandId()).isEqualTo(commandId);
        assertThat(batch.commands().getFirst().type()).isEqualTo(BridgeCommandType.READ_FILE);

        BridgeCommandResultRequest resultReq = BridgeCommandResultRequest.builder()
                .status(BridgeCommandStatus.COMPLETED)
                .result(mapper.createObjectNode().put("content", "print('hello')"))
                .durationMs(42L)
                .build();
        try (var response = runnersClient.callReportBridgeResult(runnerId, commandId, resultReq,
                ctx.apiKey, ctx.workspace)) {
            assertThat(response.getStatus()).isEqualTo(204);
        }

        BridgeCommand cmd = runnersClient.getBridgeCommand(runnerId, commandId, false, 0,
                ctx.apiKey, ctx.workspace);
        assertThat(cmd.status()).isEqualTo(BridgeCommandStatus.COMPLETED);
        assertThat(cmd.result().get("content").asText()).isEqualTo("print('hello')");
    }

    @Test
    @DisplayName("Type coexistence: CONNECT and ENDPOINT runners coexist on same user+project")
    void typeCoexistence() {
        var ctx = createIsolatedWorkspace();
        UUID projectId = createProject(ctx.apiKey, ctx.workspace);

        UUID endpointRunner = connectRunnerWithPairing("endpoint-runner", projectId,
                ctx.apiKey, ctx.workspace, RunnerType.ENDPOINT);
        UUID connectRunner = connectRunnerWithBridge("connect-runner", projectId,
                ctx.apiKey, ctx.workspace);

        LocalRunner.LocalRunnerPage page = runnersClient.listRunners(projectId, ctx.apiKey, ctx.workspace);
        assertThat(page.content()).hasSize(2);
        assertThat(page.content()).extracting(LocalRunner::id)
                .containsExactlyInAnyOrder(endpointRunner, connectRunner);

        LocalRunner endpoint = page.content().stream()
                .filter(r -> r.id().equals(endpointRunner)).findFirst().orElseThrow();
        assertThat(endpoint.type()).isEqualTo(RunnerType.ENDPOINT);
        assertThat(endpoint.status().getValue()).isEqualTo("connected");

        LocalRunner connect = page.content().stream()
                .filter(r -> r.id().equals(connectRunner)).findFirst().orElseThrow();
        assertThat(connect.type()).isEqualTo(RunnerType.CONNECT);
        assertThat(connect.status().getValue()).isEqualTo("connected");

        CreateLocalRunnerJobRequest jobReq = CreateLocalRunnerJobRequest.builder()
                .agentName(AGENT_NAME)
                .projectId(projectId)
                .build();
        UUID jobId = runnersClient.createJob(jobReq, ctx.apiKey, ctx.workspace);
        LocalRunnerJob job = runnersClient.getJob(jobId, ctx.apiKey, ctx.workspace);
        assertThat(job.runnerId()).isEqualTo(endpointRunner);

        ObjectMapper mapper = new ObjectMapper();
        BridgeCommandSubmitResponse bridgeResp = runnersClient.createBridgeCommand(connectRunner,
                BridgeCommandSubmitRequest.builder()
                        .type(BridgeCommandType.READ_FILE)
                        .args(mapper.createObjectNode().put("path", "f.py"))
                        .build(),
                ctx.apiKey, ctx.workspace);
        assertThat(bridgeResp.commandId()).isNotNull();

        UUID newEndpoint = connectRunnerWithPairing("endpoint-v2", projectId,
                ctx.apiKey, ctx.workspace, RunnerType.ENDPOINT);
        assertThat(newEndpoint).isNotEqualTo(endpointRunner);

        LocalRunner connectAfterEviction = runnersClient.getRunner(connectRunner, ctx.apiKey, ctx.workspace);
        assertThat(connectAfterEviction.status().getValue()).isEqualTo("connected");
    }

    @Nested
    @DisplayName("List Runners")
    class ListRunners {

        @Test
        void returnsConnectedRunners() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithPairing("list-runner-1", projectId, ctx.apiKey, ctx.workspace);

            LocalRunner.LocalRunnerPage page = runnersClient.listRunners(projectId, ctx.apiKey, ctx.workspace);
            assertThat(page.content()).hasSize(1);
            assertThat(page.content().get(0).id()).isEqualTo(runnerId);
            assertThat(page.content().get(0).status().getValue()).isEqualTo("connected");
        }

        @Test
        void showsDisconnectedWhenHeartbeatExpired() throws InterruptedException {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithPairing("list-runner-disconnect", projectId, ctx.apiKey, ctx.workspace);
            waitForHeartbeatExpiry();

            LocalRunner.LocalRunnerPage page = runnersClient.listRunners(projectId, ctx.apiKey, ctx.workspace);
            LocalRunner runner = page.content().stream()
                    .filter(r -> r.id().equals(runnerId)).findFirst().orElseThrow();
            assertThat(runner.status().getValue()).isEqualTo("disconnected");
        }

        @Test
        void excludesOtherWorkspaces() {
            var ctx1 = createIsolatedWorkspace();
            var ctx2 = createIsolatedWorkspace();
            UUID projectId1 = createProject(ctx1.apiKey, ctx1.workspace);
            UUID projectId2 = createProject(ctx2.apiKey, ctx2.workspace);
            UUID runnerId = connectRunnerWithPairing("list-runner-mine", projectId1, ctx1.apiKey, ctx1.workspace);
            connectRunnerWithPairing("other-ws-runner", projectId2, ctx2.apiKey, ctx2.workspace);

            LocalRunner.LocalRunnerPage page = runnersClient.listRunners(projectId2, ctx2.apiKey, ctx2.workspace);
            assertThat(page.content()).extracting(LocalRunner::id).doesNotContain(runnerId);
        }

        @Test
        void emptyWhenNoRunners() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);

            LocalRunner.LocalRunnerPage page = runnersClient.listRunners(projectId, ctx.apiKey, ctx.workspace);
            assertThat(page.content()).isEmpty();
        }

        @Test
        void filtersByProjectId() {
            var ctx = createIsolatedWorkspace();
            UUID projectId1 = createProject(ctx.apiKey, ctx.workspace);
            UUID projectId2 = createProject(ctx.apiKey, ctx.workspace);
            UUID runner1 = connectRunnerWithPairing("proj1-runner", projectId1, ctx.apiKey, ctx.workspace);
            UUID runner2 = connectRunnerWithPairing("proj2-runner", projectId2, ctx.apiKey, ctx.workspace);

            LocalRunner.LocalRunnerPage page = runnersClient.listRunners(projectId1, 0, 25, ctx.apiKey, ctx.workspace);
            assertThat(page.content()).hasSize(1);
            assertThat(page.content().get(0).id()).isEqualTo(runner1);
        }

        @Test
        void filtersByStatus() throws InterruptedException {
            var ctx = createIsolatedWorkspace();
            UUID projectId1 = createProject(ctx.apiKey, ctx.workspace);
            UUID projectId2 = createProject(ctx.apiKey, ctx.workspace);
            UUID connectedRunner = connectRunnerWithPairing("connected-runner", projectId1, ctx.apiKey, ctx.workspace);
            UUID disconnectedRunner = connectRunnerWithPairing("disconnected-runner", projectId2, ctx.apiKey,
                    ctx.workspace);
            waitForHeartbeatExpiry();
            runnersClient.heartbeat(connectedRunner, ctx.apiKey, ctx.workspace);

            LocalRunner.LocalRunnerPage connectedPage = runnersClient.listRunners(projectId1,
                    LocalRunnerStatus.CONNECTED, 0, 25, ctx.apiKey, ctx.workspace);
            assertThat(connectedPage.content()).hasSize(1);
            assertThat(connectedPage.content().getFirst().id()).isEqualTo(connectedRunner);
            assertThat(connectedPage.total()).isEqualTo(1);

            LocalRunner.LocalRunnerPage disconnectedPage = runnersClient.listRunners(projectId2,
                    LocalRunnerStatus.DISCONNECTED, 0, 25, ctx.apiKey, ctx.workspace);
            assertThat(disconnectedPage.content()).hasSize(1);
            assertThat(disconnectedPage.content().getFirst().id()).isEqualTo(disconnectedRunner);
            assertThat(disconnectedPage.total()).isEqualTo(1);

            LocalRunner.LocalRunnerPage noMatch = runnersClient.listRunners(projectId1,
                    LocalRunnerStatus.DISCONNECTED, 0, 25, ctx.apiKey, ctx.workspace);
            assertThat(noMatch.content()).isEmpty();
            assertThat(noMatch.total()).isEqualTo(0);
        }

        @Test
        void filtersByConnectedStatus() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);

            UUID connectedRunner = connectRunnerWithPairing("connected-runner", projectId, ctx.apiKey, ctx.workspace);

            LocalRunner.LocalRunnerPage connectedPage = runnersClient.listRunners(projectId,
                    LocalRunnerStatus.CONNECTED, 0, 25, ctx.apiKey, ctx.workspace);
            assertThat(connectedPage.content()).hasSize(1);
            assertThat(connectedPage.content().getFirst().id()).isEqualTo(connectedRunner);
            assertThat(connectedPage.total()).isEqualTo(1);

            LocalRunner.LocalRunnerPage pairingPage = runnersClient.listRunners(projectId,
                    LocalRunnerStatus.PAIRING, 0, 25, ctx.apiKey, ctx.workspace);
            assertThat(pairingPage.content()).isEmpty();
            assertThat(pairingPage.total()).isEqualTo(0);
        }

        @Test
        void newRunnerEvictsOldForSameProject() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);

            connectRunnerWithPairing("runner-old", projectId, ctx.apiKey, ctx.workspace);
            UUID newRunner = connectRunnerWithPairing("runner-new", projectId, ctx.apiKey, ctx.workspace);

            LocalRunner.LocalRunnerPage page = runnersClient.listRunners(projectId, ctx.apiKey, ctx.workspace);
            assertThat(page.content()).hasSize(1);
            assertThat(page.total()).isEqualTo(1);
            assertThat(page.content().getFirst().id()).isEqualTo(newRunner);
        }
    }

    @Nested
    @DisplayName("Get Runner")
    class GetRunner {

        @Test
        void returnsRunnerWithAgents() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithPairing("get-runner-agents", projectId, ctx.apiKey, ctx.workspace);

            LocalRunner.Agent agentInput = LocalRunner.Agent.builder()
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
            assertThat(agent.description()).isEqualTo("Summarizes documents");
            assertThat(agent.language()).isEqualTo("python");
            assertThat(agent.executable()).isEqualTo("/usr/bin/python3.11");
            assertThat(agent.sourceFile()).isEqualTo("agents/summarizer.py");
        }

        @Test
        void returnsEmptyAgentsWhenDisconnected() throws InterruptedException {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithPairing("get-runner-disconn", projectId, ctx.apiKey, ctx.workspace);

            LocalRunner.Agent agent = LocalRunner.Agent.builder().build();
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
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithPairing("get-runner-wrong-ws", projectId, ctx.apiKey, ctx.workspace);

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
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithPairing("reg-agents-store", projectId, ctx.apiKey, ctx.workspace);

            LocalRunner.Agent agent = LocalRunner.Agent.builder().build();
            runnersClient.registerAgents(runnerId, Map.of("agent1", agent), ctx.apiKey, ctx.workspace);

            LocalRunner runner = runnersClient.getRunner(runnerId, ctx.apiKey, ctx.workspace);
            assertThat(runner.agents()).hasSize(1);
            assertThat(runner.agents().get(0).name()).isEqualTo("agent1");
        }

        @Test
        void replacesExistingAgents() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithPairing("reg-agents-replace", projectId, ctx.apiKey, ctx.workspace);

            LocalRunner.Agent a = LocalRunner.Agent.builder().build();
            runnersClient.registerAgents(runnerId, Map.of("agent1", a), ctx.apiKey, ctx.workspace);

            LocalRunner.Agent b = LocalRunner.Agent.builder().build();
            runnersClient.registerAgents(runnerId, Map.of("agent2", b), ctx.apiKey, ctx.workspace);

            LocalRunner runner = runnersClient.getRunner(runnerId, ctx.apiKey, ctx.workspace);
            assertThat(runner.agents()).hasSize(1);
            assertThat(runner.agents().get(0).name()).isEqualTo("agent2");
        }

        @Test
        void clearsAgentsWhenEmpty() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithPairing("reg-agents-clear", projectId, ctx.apiKey, ctx.workspace);

            LocalRunner.Agent a = LocalRunner.Agent.builder().build();
            runnersClient.registerAgents(runnerId, Map.of("agent1", a), ctx.apiKey, ctx.workspace);
            runnersClient.registerAgents(runnerId, Map.of(), ctx.apiKey, ctx.workspace);

            LocalRunner runner = runnersClient.getRunner(runnerId, ctx.apiKey, ctx.workspace);
            assertThat(runner.agents()).isEmpty();
        }

        @Test
        void storesAndReturnsAgentTimeout() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithPairing("reg-agents-timeout", projectId, ctx.apiKey, ctx.workspace);

            LocalRunner.Agent agent = LocalRunner.Agent.builder().timeout(120).build();
            runnersClient.registerAgents(runnerId, Map.of("agent1", agent), ctx.apiKey, ctx.workspace);

            LocalRunner runner = runnersClient.getRunner(runnerId, ctx.apiKey, ctx.workspace);
            assertThat(runner.agents()).hasSize(1);
            assertThat(runner.agents().get(0).timeout()).isEqualTo(120);
        }

        @Test
        void returnsZeroTimeoutWhenNotSet() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithPairing("reg-agents-no-timeout", projectId, ctx.apiKey, ctx.workspace);

            LocalRunner.Agent agent = LocalRunner.Agent.builder().build();
            runnersClient.registerAgents(runnerId, Map.of("agent1", agent), ctx.apiKey, ctx.workspace);

            LocalRunner runner = runnersClient.getRunner(runnerId, ctx.apiKey, ctx.workspace);
            assertThat(runner.agents().get(0).timeout()).isEqualTo(0);
        }

        @Test
        void throwsNotFoundForWrongWorkspace() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithPairing("reg-agents-wrong-ws", projectId, ctx.apiKey, ctx.workspace);

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
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithPairing("hb-cancelled", projectId, ctx.apiKey, ctx.workspace);
            UUID jobId = createRunningJob(runnerId, AGENT_NAME, projectId, ctx.apiKey, ctx.workspace);
            runnersClient.cancelJob(jobId, ctx.apiKey, ctx.workspace);

            LocalRunnerHeartbeatResponse resp = runnersClient.heartbeat(runnerId, ctx.apiKey, ctx.workspace);
            assertThat(resp.cancelledJobIds()).contains(jobId);

            LocalRunnerHeartbeatResponse resp2 = runnersClient.heartbeat(runnerId, ctx.apiKey, ctx.workspace);
            assertThat(resp2.cancelledJobIds()).isEmpty();
        }

        @Test
        void returnsEmptyWhenNoCancellations() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithPairing("hb-no-cancel", projectId, ctx.apiKey, ctx.workspace);

            LocalRunnerHeartbeatResponse resp = runnersClient.heartbeat(runnerId, ctx.apiKey, ctx.workspace);
            assertThat(resp.cancelledJobIds()).isEmpty();
        }

        @Test
        void throwsGoneForEvictedRunner() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID oldRunnerId = connectRunnerWithPairing("hb-evicted-old", projectId, ctx.apiKey, ctx.workspace);
            connectRunnerWithPairing("hb-evicted-new", projectId, ctx.apiKey, ctx.workspace);

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
        void throwsGoneForWrongWorkspace() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithPairing("hb-wrong-ws", projectId, ctx.apiKey, ctx.workspace);

            try (var response = runnersClient.callHeartbeat(runnerId, OTHER_API_KEY, OTHER_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(410);
            }
        }
    }

    @Nested
    @DisplayName("Create Job")
    class CreateJob {

        @Test
        void usesProjectScopedRunner() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithPairing("cj-default-runner", projectId, ctx.apiKey, ctx.workspace);

            CreateLocalRunnerJobRequest req = CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME)
                    .projectId(projectId)
                    .build();
            UUID jobId = runnersClient.createJob(req, ctx.apiKey, ctx.workspace);

            LocalRunnerJob job = runnersClient.getJob(jobId, ctx.apiKey, ctx.workspace);
            assertThat(job.runnerId()).isEqualTo(runnerId);
        }

        @Test
        void throwsNotFoundWhenNoRunner() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);

            CreateLocalRunnerJobRequest req = CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME)
                    .projectId(projectId)
                    .build();

            try (var response = runnersClient.callCreateJob(req, ctx.apiKey, ctx.workspace)) {
                assertThat(response.getStatus()).isEqualTo(404);
            }
        }

        @Test
        void throwsConflictWhenRunnerOffline() throws InterruptedException {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            connectRunnerWithPairing("cj-offline", projectId, ctx.apiKey, ctx.workspace);
            waitForHeartbeatExpiry();

            CreateLocalRunnerJobRequest req = CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME)
                    .projectId(projectId)
                    .build();

            try (var response = runnersClient.callCreateJob(req, ctx.apiKey, ctx.workspace)) {
                assertThat(response.getStatus()).isEqualTo(409);
            }
        }

        @Test
        void throwsTooManyRequests() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            connectRunnerWithPairing("cj-too-many", projectId, ctx.apiKey, ctx.workspace);

            for (int i = 0; i < 3; i++) {
                runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                        .agentName(AGENT_NAME).projectId(projectId).build(), ctx.apiKey, ctx.workspace);
            }

            try (var response = runnersClient.callCreateJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).projectId(projectId).build(), ctx.apiKey, ctx.workspace)) {
                assertThat(response.getStatus()).isEqualTo(429);
            }
        }

        @Test
        void usesAgentTimeoutWhenSet() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithPairing("cj-agent-timeout", projectId, ctx.apiKey, ctx.workspace);

            LocalRunner.Agent agent = LocalRunner.Agent.builder().timeout(300).build();
            runnersClient.registerAgents(runnerId, Map.of(AGENT_NAME, agent), ctx.apiKey, ctx.workspace);

            UUID jobId = runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).projectId(projectId).build(), ctx.apiKey, ctx.workspace);

            LocalRunnerJob job = runnersClient.getJob(jobId, ctx.apiKey, ctx.workspace);
            assertThat(job.timeout()).isEqualTo(300);
        }

        @Test
        void fallsBackToConfigTimeoutWhenAgentHasNone() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithPairing("cj-config-timeout", projectId, ctx.apiKey, ctx.workspace);

            LocalRunner.Agent agent = LocalRunner.Agent.builder().build();
            runnersClient.registerAgents(runnerId, Map.of(AGENT_NAME, agent), ctx.apiKey, ctx.workspace);

            UUID jobId = runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).projectId(projectId).build(), ctx.apiKey, ctx.workspace);

            LocalRunnerJob job = runnersClient.getJob(jobId, ctx.apiKey, ctx.workspace);
            assertThat(job.timeout()).isPositive();
        }

        @Test
        void storesMaskIdAndMetadata() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            connectRunnerWithPairing("cj-mask-id", projectId, ctx.apiKey, ctx.workspace);
            UUID maskId = randomUUID();
            var metadata = LocalRunnerJobMetadata.builder()
                    .datasetId(randomUUID())
                    .datasetVersionId(randomUUID())
                    .datasetItemVersionId(randomUUID())
                    .datasetItemId(randomUUID())
                    .build();

            UUID jobId = runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).projectId(projectId).maskId(maskId).metadata(metadata).build(),
                    ctx.apiKey, ctx.workspace);

            LocalRunnerJob job = runnersClient.getJob(jobId, ctx.apiKey, ctx.workspace);
            assertThat(job.maskId()).isEqualTo(maskId);
            assertThat(job.metadata()).isEqualTo(metadata);
        }

        @Test
        void storesMaskIdAndMetadataNullWhenNotProvided() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            connectRunnerWithPairing("cj-no-mask-id", projectId, ctx.apiKey, ctx.workspace);

            UUID jobId = runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).projectId(projectId).build(),
                    ctx.apiKey, ctx.workspace);

            LocalRunnerJob job = runnersClient.getJob(jobId, ctx.apiKey, ctx.workspace);
            assertThat(job.maskId()).isNull();
            assertThat(job.metadata()).isNull();
        }

        @Test
        void fallsBackToConfigTimeoutWhenNoAgentsRegistered() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            connectRunnerWithPairing("cj-no-agents", projectId, ctx.apiKey, ctx.workspace);

            UUID jobId = runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).projectId(projectId).build(), ctx.apiKey, ctx.workspace);

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
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithPairing("nj-pending", projectId, ctx.apiKey, ctx.workspace);

            UUID jobId = runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).projectId(projectId).build(), ctx.apiKey, ctx.workspace);

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
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithPairing("nj-mask-id", projectId, ctx.apiKey, ctx.workspace);
            UUID maskId = randomUUID();
            var metadata = LocalRunnerJobMetadata.builder()
                    .datasetId(randomUUID())
                    .datasetVersionId(randomUUID())
                    .datasetItemVersionId(randomUUID())
                    .datasetItemId(randomUUID())
                    .build();

            runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).projectId(projectId).maskId(maskId).metadata(metadata).build(),
                    ctx.apiKey, ctx.workspace);

            LocalRunnerJob claimed = runnersClient.nextJob(runnerId, ctx.apiKey, ctx.workspace);
            assertThat(claimed.maskId()).isEqualTo(maskId);
            assertThat(claimed.metadata()).isEqualTo(metadata);
        }

        @Test
        void returnsEmptyWhenNoPendingJobs() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithPairing("nj-empty", projectId, ctx.apiKey, ctx.workspace);

            try (var response = runnersClient.callNextJob(runnerId, ctx.apiKey, ctx.workspace)) {
                assertThat(response.getStatus()).isEqualTo(200);
                assertThat(response.readEntity(String.class)).isEqualTo("null");
            }
        }

        @Test
        void throwsNotFoundForWrongWorkspace() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithPairing("nj-wrong-ws", projectId, ctx.apiKey, ctx.workspace);

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
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithPairing("lj-runner", projectId, ctx.apiKey, ctx.workspace);
            runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).projectId(projectId).build(), ctx.apiKey, ctx.workspace);
            runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).projectId(projectId).build(), ctx.apiKey, ctx.workspace);

            LocalRunnerJob.LocalRunnerJobPage page = runnersClient.listJobs(runnerId, null, 0, 10,
                    ctx.apiKey, ctx.workspace);
            assertThat(page.content()).hasSize(2);
            assertThat(page.total()).isEqualTo(2);
        }

        @Test
        void returnsMaskIdAndMetadataInList() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithPairing("lj-mask-id", projectId, ctx.apiKey, ctx.workspace);
            UUID maskId = randomUUID();
            var metadata = LocalRunnerJobMetadata.builder()
                    .datasetId(randomUUID())
                    .datasetVersionId(randomUUID())
                    .datasetItemVersionId(randomUUID())
                    .datasetItemId(randomUUID())
                    .build();

            runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).projectId(projectId).maskId(maskId).metadata(metadata).build(),
                    ctx.apiKey, ctx.workspace);

            LocalRunnerJob.LocalRunnerJobPage page = runnersClient.listJobs(runnerId, null, 0, 10,
                    ctx.apiKey, ctx.workspace);
            assertThat(page.content()).hasSize(1);
            assertThat(page.content().getFirst().maskId()).isEqualTo(maskId);
            assertThat(page.content().getFirst().metadata()).isEqualTo(metadata);
        }

        @Test
        void filtersByProjectId() {
            var ctx = createIsolatedWorkspace();
            UUID projectIdA = createProject(ctx.apiKey, ctx.workspace);
            UUID projectIdB = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithPairing("lj-filter", projectIdA, ctx.apiKey, ctx.workspace);

            runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).projectId(projectIdA).build(), ctx.apiKey, ctx.workspace);

            LocalRunnerJob.LocalRunnerJobPage page = runnersClient.listJobs(runnerId, projectIdA, 0, 10,
                    ctx.apiKey, ctx.workspace);
            assertThat(page.content()).hasSize(1);
            assertThat(page.content().get(0).projectId()).isEqualTo(projectIdA);
        }

        @Test
        void paginatesCorrectly() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithPairing("lj-paginate", projectId, ctx.apiKey, ctx.workspace);
            for (int i = 0; i < 3; i++) {
                runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                        .agentName(AGENT_NAME).projectId(projectId).build(), ctx.apiKey, ctx.workspace);
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
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithPairing("lj-sort", projectId, ctx.apiKey, ctx.workspace);
            runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).projectId(projectId).build(), ctx.apiKey, ctx.workspace);
            runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).projectId(projectId).build(), ctx.apiKey, ctx.workspace);

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
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            connectRunnerWithPairing("gj-runner", projectId, ctx.apiKey, ctx.workspace);
            UUID jobId = runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).projectId(projectId).build(), ctx.apiKey, ctx.workspace);

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
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            connectRunnerWithPairing("gj-wrong-ws", projectId, ctx.apiKey, ctx.workspace);
            UUID jobId = runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).projectId(projectId).build(), ctx.apiKey, ctx.workspace);

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
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            connectRunnerWithPairing("gl-all", projectId, ctx.apiKey, ctx.workspace);
            UUID jobId = runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).projectId(projectId).build(), ctx.apiKey, ctx.workspace);

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
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            connectRunnerWithPairing("gl-offset", projectId, ctx.apiKey, ctx.workspace);
            UUID jobId = runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).projectId(projectId).build(), ctx.apiKey, ctx.workspace);

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
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            connectRunnerWithPairing("gl-beyond", projectId, ctx.apiKey, ctx.workspace);
            UUID jobId = runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).projectId(projectId).build(), ctx.apiKey, ctx.workspace);

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
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            connectRunnerWithPairing("gl-wrong-ws", projectId, ctx.apiKey, ctx.workspace);
            UUID jobId = runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).projectId(projectId).build(), ctx.apiKey, ctx.workspace);

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
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithPairing("rr-bad-status", projectId, ctx.apiKey, ctx.workspace);
            UUID jobId = createRunningJob(runnerId, AGENT_NAME, projectId, ctx.apiKey, ctx.workspace);

            try (var response = runnersClient.callReportResult(jobId,
                    LocalRunnerJobResultRequest.builder().status(LocalRunnerJobStatus.PENDING).build(),
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
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            connectRunnerWithPairing("rr-wrong-ws", projectId, ctx.apiKey, ctx.workspace);
            UUID jobId = runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).projectId(projectId).build(), ctx.apiKey, ctx.workspace);

            try (var response = runnersClient.callReportResult(jobId,
                    LocalRunnerJobResultRequest.builder().status(LocalRunnerJobStatus.COMPLETED).build(),
                    OTHER_API_KEY, OTHER_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(404);
            }
        }

        @Test
        void supportsInFlightTraceIdReporting() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithPairing("rr-in-flight-trace", projectId, ctx.apiKey, ctx.workspace);
            UUID jobId = createRunningJob(runnerId, AGENT_NAME, projectId, ctx.apiKey, ctx.workspace);

            UUID traceId = randomUUID();
            runnersClient.reportResult(jobId,
                    LocalRunnerJobResultRequest.builder()
                            .status(LocalRunnerJobStatus.RUNNING)
                            .traceId(traceId)
                            .build(),
                    ctx.apiKey, ctx.workspace);

            LocalRunnerJob runningJob = runnersClient.getJob(jobId, ctx.apiKey, ctx.workspace);
            assertThat(runningJob.status().getValue()).isEqualTo("running");
            assertThat(runningJob.traceId()).isEqualTo(traceId);
            assertThat(runningJob.completedAt()).isNull();
        }

        @Test
        void inFlightTraceIdIsPreservedWhenJobCompletes() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithPairing("rr-in-flight-complete", projectId, ctx.apiKey, ctx.workspace);
            UUID jobId = createRunningJob(runnerId, AGENT_NAME, projectId, ctx.apiKey, ctx.workspace);

            UUID traceId = randomUUID();
            runnersClient.reportResult(jobId,
                    LocalRunnerJobResultRequest.builder()
                            .status(LocalRunnerJobStatus.RUNNING)
                            .traceId(traceId)
                            .build(),
                    ctx.apiKey, ctx.workspace);

            LocalRunnerJob runningJob = runnersClient.getJob(jobId, ctx.apiKey, ctx.workspace);
            assertThat(runningJob.traceId()).isEqualTo(traceId);
            assertThat(runningJob.status().getValue()).isEqualTo("running");

            JsonNode result = new ObjectMapper().createObjectNode().put("output", "completed");
            runnersClient.reportResult(jobId,
                    LocalRunnerJobResultRequest.builder()
                            .status(LocalRunnerJobStatus.COMPLETED)
                            .result(result)
                            .build(),
                    ctx.apiKey, ctx.workspace);

            LocalRunnerJob completedJob = runnersClient.getJob(jobId, ctx.apiKey, ctx.workspace);
            assertThat(completedJob.status().getValue()).isEqualTo("completed");
            assertThat(completedJob.traceId()).isEqualTo(traceId);
            assertThat(completedJob.completedAt()).isNotNull();
            assertThat(completedJob.result().get("output").asText()).isEqualTo("completed");
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
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            connectRunnerWithPairing("cj-cancel-wrong-ws", projectId, ctx.apiKey, ctx.workspace);
            UUID jobId = runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).projectId(projectId).build(), ctx.apiKey, ctx.workspace);

            try (var response = runnersClient.callCancelJob(jobId, OTHER_API_KEY, OTHER_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(404);
            }
        }
    }

    // ========== Bridge Tests ==========

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private UUID connectRunnerWithBridge(String name, UUID projectId, String apiKey, String workspace) {
        UUID runnerId = connectRunnerWithPairing(name, projectId, apiKey, workspace, RunnerType.CONNECT);
        runnersClient.heartbeatWithCapabilities(runnerId, List.of("jobs", "bridge"), apiKey, workspace);
        return runnerId;
    }

    @Nested
    @DisplayName("Bridge Happy Path")
    class BridgeHappyPath {

        @Test
        @DisplayName("Full lifecycle: submit → poll → report → await")
        void fullLifecycle() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithBridge("bridge-happy", projectId, ctx.apiKey, ctx.workspace);

            BridgeCommandSubmitRequest submitReq = BridgeCommandSubmitRequest.builder()
                    .type(BridgeCommandType.READ_FILE)
                    .args(MAPPER.createObjectNode().put("path", "src/main.py"))
                    .timeoutSeconds(30)
                    .build();
            BridgeCommandSubmitResponse submitResp = runnersClient.createBridgeCommand(runnerId, submitReq,
                    ctx.apiKey, ctx.workspace);
            UUID commandId = submitResp.commandId();
            assertThat(commandId).isNotNull();

            BridgeCommandBatchResponse batch = runnersClient.nextBridgeCommands(runnerId,
                    BridgeCommandNextRequest.builder().maxCommands(10).build(), ctx.apiKey, ctx.workspace);
            assertThat(batch.commands()).hasSize(1);
            assertThat(batch.commands().getFirst().commandId()).isEqualTo(commandId);
            assertThat(batch.commands().getFirst().type()).isEqualTo(BridgeCommandType.READ_FILE);

            BridgeCommandResultRequest resultReq = BridgeCommandResultRequest.builder()
                    .status(BridgeCommandStatus.COMPLETED)
                    .result(MAPPER.createObjectNode().put("content", "file data"))
                    .durationMs(15L)
                    .build();
            try (var response = runnersClient.callReportBridgeResult(runnerId, commandId, resultReq,
                    ctx.apiKey, ctx.workspace)) {
                assertThat(response.getStatus()).isEqualTo(204);
            }

            BridgeCommand cmd = runnersClient.getBridgeCommand(runnerId, commandId, false, 0,
                    ctx.apiKey, ctx.workspace);
            assertThat(cmd.status()).isEqualTo(BridgeCommandStatus.COMPLETED);
            assertThat(cmd.result().get("content").asText()).isEqualTo("file data");
            assertThat(cmd.durationMs()).isEqualTo(15L);
        }

        @Test
        @DisplayName("Batch poll: submit 3, poll returns all 3")
        void batchPoll() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithBridge("bridge-batch", projectId, ctx.apiKey, ctx.workspace);

            for (int i = 0; i < 3; i++) {
                runnersClient.createBridgeCommand(runnerId,
                        BridgeCommandSubmitRequest.builder()
                                .type(BridgeCommandType.READ_FILE)
                                .args(MAPPER.createObjectNode().put("path", "file" + i + ".py"))
                                .build(),
                        ctx.apiKey, ctx.workspace);
            }

            BridgeCommandBatchResponse batch = runnersClient.nextBridgeCommands(runnerId,
                    BridgeCommandNextRequest.builder().maxCommands(10).build(), ctx.apiKey, ctx.workspace);
            assertThat(batch.commands()).hasSize(3);
        }

        @Test
        @DisplayName("No interference with job endpoints")
        void noInterferenceWithJobs() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID connectRunner = connectRunnerWithBridge("bridge-no-interference", projectId,
                    ctx.apiKey, ctx.workspace);
            connectRunnerWithPairing("endpoint-runner", projectId, ctx.apiKey, ctx.workspace, RunnerType.ENDPOINT);

            runnersClient.createBridgeCommand(connectRunner,
                    BridgeCommandSubmitRequest.builder()
                            .type(BridgeCommandType.READ_FILE)
                            .args(MAPPER.createObjectNode().put("path", "f.py"))
                            .build(),
                    ctx.apiKey, ctx.workspace);

            UUID jobId = runnersClient.createJob(CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME).projectId(projectId).build(), ctx.apiKey, ctx.workspace);
            assertThat(jobId).isNotNull();

            BridgeCommandBatchResponse batch = runnersClient.nextBridgeCommands(connectRunner,
                    BridgeCommandNextRequest.builder().maxCommands(10).build(), ctx.apiKey, ctx.workspace);
            assertThat(batch.commands()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Bridge Submit")
    class BridgeSubmit {

        @Test
        void runnerWithoutBridgeCapability_returns409() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithPairing("bridge-no-cap", projectId, ctx.apiKey, ctx.workspace);

            try (var response = runnersClient.callSubmitBridgeCommand(runnerId,
                    BridgeCommandSubmitRequest.builder()
                            .type(BridgeCommandType.READ_FILE)
                            .args(MAPPER.createObjectNode().put("path", "f.py"))
                            .build(),
                    ctx.apiKey, ctx.workspace)) {
                assertThat(response.getStatus()).isEqualTo(409);
            }
        }

        @Test
        void queueFull_returns429() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithBridge("bridge-queue-full", projectId, ctx.apiKey, ctx.workspace);

            BridgeCommandSubmitRequest req = BridgeCommandSubmitRequest.builder()
                    .type(BridgeCommandType.READ_FILE)
                    .args(MAPPER.createObjectNode().put("path", "f.py"))
                    .build();

            for (int i = 0; i < 3; i++) {
                runnersClient.createBridgeCommand(runnerId, req, ctx.apiKey, ctx.workspace);
            }

            try (var response = runnersClient.callSubmitBridgeCommand(runnerId, req, ctx.apiKey, ctx.workspace)) {
                assertThat(response.getStatus()).isEqualTo(429);
            }
        }

        @Test
        void wrongWorkspace_returns404() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithBridge("bridge-wrong-ws", projectId, ctx.apiKey, ctx.workspace);

            try (var response = runnersClient.callSubmitBridgeCommand(runnerId,
                    BridgeCommandSubmitRequest.builder()
                            .type(BridgeCommandType.READ_FILE)
                            .args(MAPPER.createObjectNode().put("path", "f.py"))
                            .build(),
                    OTHER_API_KEY, OTHER_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(404);
            }
        }
    }

    @Nested
    @DisplayName("Bridge Await")
    class BridgeAwait {

        @Test
        void longPollUnblocksOnResult() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithBridge("bridge-await", projectId, ctx.apiKey, ctx.workspace);

            BridgeCommandSubmitResponse submitResp = runnersClient.createBridgeCommand(runnerId,
                    BridgeCommandSubmitRequest.builder()
                            .type(BridgeCommandType.READ_FILE)
                            .args(MAPPER.createObjectNode().put("path", "f.py"))
                            .build(),
                    ctx.apiKey, ctx.workspace);
            UUID commandId = submitResp.commandId();

            runnersClient.nextBridgeCommands(runnerId,
                    BridgeCommandNextRequest.builder().maxCommands(10).build(), ctx.apiKey, ctx.workspace);

            Thread reporter = new Thread(() -> {
                try {
                    Thread.sleep(500);
                    runnersClient.callReportBridgeResult(runnerId, commandId,
                            BridgeCommandResultRequest.builder()
                                    .status(BridgeCommandStatus.COMPLETED)
                                    .result(MAPPER.createObjectNode().put("content", "data"))
                                    .build(),
                            ctx.apiKey, ctx.workspace).close();
                } catch (Exception ignored) {
                }
            });
            reporter.start();

            BridgeCommand cmd = runnersClient.getBridgeCommand(runnerId, commandId, true, 10,
                    ctx.apiKey, ctx.workspace);
            assertThat(cmd.status()).isEqualTo(BridgeCommandStatus.COMPLETED);
        }

        @Test
        void noWait_returnsCurrentState() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithBridge("bridge-no-wait", projectId, ctx.apiKey, ctx.workspace);

            BridgeCommandSubmitResponse submitResp = runnersClient.createBridgeCommand(runnerId,
                    BridgeCommandSubmitRequest.builder()
                            .type(BridgeCommandType.READ_FILE)
                            .args(MAPPER.createObjectNode().put("path", "f.py"))
                            .build(),
                    ctx.apiKey, ctx.workspace);

            BridgeCommand cmd = runnersClient.getBridgeCommand(runnerId, submitResp.commandId(),
                    false, 0, ctx.apiKey, ctx.workspace);
            assertThat(cmd.status()).isEqualTo(BridgeCommandStatus.PENDING);
        }

        @Test
        void commandNotFound_returns404() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithBridge("bridge-cmd-notfound", projectId, ctx.apiKey, ctx.workspace);

            try (var response = runnersClient.callGetBridgeCommand(runnerId, randomUUID(), false, 0,
                    ctx.apiKey, ctx.workspace)) {
                assertThat(response.getStatus()).isEqualTo(404);
            }
        }
    }

    @Nested
    @DisplayName("Bridge Heartbeat")
    class BridgeHeartbeat {

        @Test
        void capabilitiesReturnedInGetRunner() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithBridge("bridge-caps", projectId, ctx.apiKey, ctx.workspace);

            LocalRunner runner = runnersClient.getRunner(runnerId, ctx.apiKey, ctx.workspace);
            assertThat(runner.capabilities()).containsExactly("jobs", "bridge");
        }

        @Test
        void oldStyleHeartbeatStillWorks() {
            var ctx = createIsolatedWorkspace();
            UUID projectId = createProject(ctx.apiKey, ctx.workspace);
            UUID runnerId = connectRunnerWithPairing("bridge-old-hb", projectId, ctx.apiKey, ctx.workspace);

            LocalRunnerHeartbeatResponse resp = runnersClient.heartbeat(runnerId, ctx.apiKey, ctx.workspace);
            assertThat(resp).isNotNull();

            LocalRunner runner = runnersClient.getRunner(runnerId, ctx.apiKey, ctx.workspace);
            assertThat(runner.capabilities()).containsExactly("jobs");
        }
    }
}
