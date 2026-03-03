package com.comet.opik.domain;

import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.runner.ConnectRequest;
import com.comet.opik.api.runner.CreateJobRequest;
import com.comet.opik.api.runner.HeartbeatResponse;
import com.comet.opik.api.runner.JobResultRequest;
import com.comet.opik.api.runner.LocalRunner;
import com.comet.opik.api.runner.LocalRunnerJob;
import com.comet.opik.api.runner.LogEntry;
import com.comet.opik.api.runner.PairResponse;
import com.comet.opik.api.runner.RunnerStatus;
import com.comet.opik.infrastructure.RunnerConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.util.Duration;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RList;
import org.redisson.api.RMap;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunnerServiceImplTest {

    private static final String WORKSPACE_ID = "test-workspace";
    private static final String OTHER_WORKSPACE_ID = "other-workspace";
    private static final String USER_NAME = "test-user";
    private static final String RUNNER_NAME = "my-runner";
    private static final String AGENT_NAME = "test-agent";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
    private RedissonClient redisClient;
    private RunnerConfig runnerConfig;
    private IdGenerator idGenerator;
    private RunnerServiceImpl runnerService;

    private int uuidCounter = 0;

    @BeforeAll
    void setUp() {
        redis.start();

        Config config = new Config();
        config.useSingleServer()
                .setAddress(redis.getRedisURI())
                .setDatabase(0);

        redisClient = Redisson.create(config);

        runnerConfig = new RunnerConfig();
        runnerConfig.setEnabled(true);
        runnerConfig.setHeartbeatTtl(Duration.seconds(2));
        runnerConfig.setNextJobPollTimeout(Duration.seconds(1));
        runnerConfig.setMaxPendingJobsPerRunner(3);
        runnerConfig.setDeadRunnerPurgeTime(Duration.seconds(0));
        runnerConfig.setCompletedJobTtl(Duration.days(7));
        runnerConfig.setJobTimeout(Duration.seconds(1800));
        runnerConfig.setReaperLockDuration(Duration.seconds(55));
        runnerConfig.setReaperLockWait(Duration.seconds(5));

        idGenerator = Mockito.mock(IdGenerator.class);

        runnerService = new RunnerServiceImpl(redisClient, runnerConfig, idGenerator);
    }

    @BeforeEach
    void clearDatabase() {
        redisClient.getKeys().flushdb();
        uuidCounter = 0;
    }

    @AfterAll
    void tearDown() {
        redisClient.shutdown();
        redis.stop();
    }

    private UUID nextUUID() {
        uuidCounter++;
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(uuidCounter));
    }

    private void stubNextId() {
        when(idGenerator.generateId()).thenReturn(nextUUID());
    }

    private UUID pairAndConnect(String workspaceId, String userName, String runnerName) {
        stubNextId();
        PairResponse pair = runnerService.generatePairingCode(workspaceId, userName);
        ConnectRequest req = ConnectRequest.builder()
                .pairingCode(pair.pairingCode())
                .runnerName(runnerName)
                .build();
        return runnerService.connect(workspaceId, userName, req);
    }

    private UUID connectViaApiKey(String workspaceId, String userName, String runnerName) {
        stubNextId();
        ConnectRequest req = ConnectRequest.builder()
                .runnerName(runnerName)
                .build();
        return runnerService.connect(workspaceId, userName, req);
    }

    private UUID createTestJob(String workspaceId, String userName, String agentName) {
        stubNextId();
        CreateJobRequest req = CreateJobRequest.builder()
                .agentName(agentName)
                .build();
        return runnerService.createJob(workspaceId, userName, req);
    }

    private void waitForHeartbeatExpiry() throws InterruptedException {
        Thread.sleep((runnerConfig.getHeartbeatTtl().toSeconds() + 1) * 1000L);
    }

    // --- generatePairingCode tests ---

    @Nested
    class GeneratePairingCode {

        @Test
        void createsValidCode() {
            stubNextId();
            PairResponse resp = runnerService.generatePairingCode(WORKSPACE_ID, USER_NAME);

            assertThat(resp.pairingCode()).hasSize(6);
            assertThat(resp.pairingCode()).matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}");
            assertThat(resp.runnerId()).isNotNull();
            assertThat(resp.expiresInSeconds()).isEqualTo(300);
        }

        @Test
        void createsPairKeyInRedis() {
            stubNextId();
            PairResponse resp = runnerService.generatePairingCode(WORKSPACE_ID, USER_NAME);

            RBucket<String> pairBucket = redisClient.getBucket(
                    "opik:runners:pair:" + resp.pairingCode(), StringCodec.INSTANCE);
            assertThat(pairBucket.isExists()).isTrue();
            assertThat(pairBucket.get()).isEqualTo(resp.runnerId() + ":" + WORKSPACE_ID);
            assertThat(pairBucket.remainTimeToLive()).isPositive();
        }

        @Test
        void createsRunnerHash() {
            stubNextId();
            PairResponse resp = runnerService.generatePairingCode(WORKSPACE_ID, USER_NAME);

            RMap<String, String> runnerMap = redisClient.getMap(
                    "opik:runners:runner:" + resp.runnerId(), StringCodec.INSTANCE);
            assertThat(runnerMap.get("status")).isEqualTo("pairing");
            assertThat(runnerMap.get("workspace_id")).isEqualTo(WORKSPACE_ID);
            assertThat(runnerMap.get("user_name")).isEqualTo(USER_NAME);
            assertThat(runnerMap.remainTimeToLive()).isPositive();
        }

        @Test
        void addsToWorkspaceSets() {
            stubNextId();
            PairResponse resp = runnerService.generatePairingCode(WORKSPACE_ID, USER_NAME);

            RSet<String> wsRunners = redisClient.getSet(
                    "opik:runners:workspace:" + WORKSPACE_ID + ":runners", StringCodec.INSTANCE);
            assertThat(wsRunners.contains(resp.runnerId().toString())).isTrue();

            RSet<String> workspaces = redisClient.getSet(
                    "opik:runners:workspaces:with_runners", StringCodec.INSTANCE);
            assertThat(workspaces.contains(WORKSPACE_ID)).isTrue();
        }

        @Test
        void setsUserRunnerMapping() {
            stubNextId();
            PairResponse resp = runnerService.generatePairingCode(WORKSPACE_ID, USER_NAME);

            RBucket<String> userRunner = redisClient.getBucket(
                    "opik:runners:workspace:" + WORKSPACE_ID + ":user:" + USER_NAME + ":runner",
                    StringCodec.INSTANCE);
            assertThat(userRunner.get()).isEqualTo(resp.runnerId().toString());
        }
    }

    // --- connect tests ---

    @Nested
    class Connect {

        @Test
        void withPairingCode_claimsPairAndReturnsCredentials() {
            stubNextId();
            PairResponse pair = runnerService.generatePairingCode(WORKSPACE_ID, USER_NAME);

            ConnectRequest req = ConnectRequest.builder()
                    .pairingCode(pair.pairingCode())
                    .runnerName(RUNNER_NAME)
                    .build();
            UUID runnerId = runnerService.connect(WORKSPACE_ID, USER_NAME, req);

            assertThat(runnerId).isEqualTo(pair.runnerId());

            RBucket<String> pairBucket = redisClient.getBucket(
                    "opik:runners:pair:" + pair.pairingCode(), StringCodec.INSTANCE);
            assertThat(pairBucket.isExists()).isFalse();

            RMap<String, String> runnerMap = redisClient.getMap(
                    "opik:runners:runner:" + runnerId, StringCodec.INSTANCE);
            assertThat(runnerMap.get("status")).isEqualTo("connected");
            assertThat(runnerMap.get("name")).isEqualTo(RUNNER_NAME);
            assertThat(runnerMap.get("connected_at")).isNotBlank();
        }

        @Test
        void withPairingCode_removesRunnerTTL() {
            stubNextId();
            PairResponse pair = runnerService.generatePairingCode(WORKSPACE_ID, USER_NAME);

            ConnectRequest req = ConnectRequest.builder()
                    .pairingCode(pair.pairingCode())
                    .runnerName(RUNNER_NAME)
                    .build();
            runnerService.connect(WORKSPACE_ID, USER_NAME, req);

            RMap<String, String> runnerMap = redisClient.getMap(
                    "opik:runners:runner:" + pair.runnerId(), StringCodec.INSTANCE);
            assertThat(runnerMap.remainTimeToLive()).isEqualTo(-1);
        }

        @Test
        void withPairingCode_setsHeartbeat() {
            stubNextId();
            PairResponse pair = runnerService.generatePairingCode(WORKSPACE_ID, USER_NAME);

            ConnectRequest req = ConnectRequest.builder()
                    .pairingCode(pair.pairingCode())
                    .runnerName(RUNNER_NAME)
                    .build();
            UUID runnerId = runnerService.connect(WORKSPACE_ID, USER_NAME, req);

            RBucket<String> hb = redisClient.getBucket(
                    "opik:runners:runner:" + runnerId + ":heartbeat", StringCodec.INSTANCE);
            assertThat(hb.isExists()).isTrue();
            assertThat(hb.remainTimeToLive()).isPositive();
        }

        @Test
        void withExpiredPairingCode_throwsBadRequest() {
            ConnectRequest req = ConnectRequest.builder()
                    .pairingCode("ZZZZZZ")
                    .runnerName(RUNNER_NAME)
                    .build();

            assertThatThrownBy(() -> runnerService.connect(WORKSPACE_ID, USER_NAME, req))
                    .isInstanceOf(ClientErrorException.class)
                    .satisfies(e -> assertThat(((ClientErrorException) e).getResponse().getStatus()).isEqualTo(400));
        }

        @Test
        void withPairingCodeFromDifferentWorkspace_throwsBadRequest() {
            stubNextId();
            PairResponse pair = runnerService.generatePairingCode(WORKSPACE_ID, USER_NAME);

            ConnectRequest req = ConnectRequest.builder()
                    .pairingCode(pair.pairingCode())
                    .runnerName(RUNNER_NAME)
                    .build();

            assertThatThrownBy(() -> runnerService.connect(OTHER_WORKSPACE_ID, USER_NAME, req))
                    .isInstanceOf(ClientErrorException.class)
                    .satisfies(e -> assertThat(((ClientErrorException) e).getResponse().getStatus()).isEqualTo(400));
        }

        @Test
        void withoutPairingCode_createsNewRunner() {
            UUID runnerId = connectViaApiKey(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            RMap<String, String> runnerMap = redisClient.getMap(
                    "opik:runners:runner:" + runnerId, StringCodec.INSTANCE);
            assertThat(runnerMap.get("status")).isEqualTo("connected");
            assertThat(runnerMap.get("name")).isEqualTo(RUNNER_NAME);
            assertThat(runnerMap.get("workspace_id")).isEqualTo(WORKSPACE_ID);

            RBucket<String> hb = redisClient.getBucket(
                    "opik:runners:runner:" + runnerId + ":heartbeat", StringCodec.INSTANCE);
            assertThat(hb.isExists()).isTrue();
        }

        @Test
        void replacesExistingRunner() {
            UUID oldRunnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, "old-runner");
            UUID newRunnerId = connectViaApiKey(WORKSPACE_ID, USER_NAME, "new-runner");

            assertThat(newRunnerId).isNotEqualTo(oldRunnerId);

            RBucket<String> oldHb = redisClient.getBucket(
                    "opik:runners:runner:" + oldRunnerId + ":heartbeat", StringCodec.INSTANCE);
            assertThat(oldHb.isExists()).isFalse();

            RBucket<String> userRunner = redisClient.getBucket(
                    "opik:runners:workspace:" + WORKSPACE_ID + ":user:" + USER_NAME + ":runner",
                    StringCodec.INSTANCE);
            assertThat(userRunner.get()).isEqualTo(newRunnerId.toString());
        }
    }

    // --- listRunners tests ---

    @Nested
    class ListRunners {

        @Test
        void returnsConnectedRunners() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            LocalRunner.LocalRunnerPage page = runnerService.listRunners(WORKSPACE_ID, 0, 25);
            assertThat(page.content()).hasSize(1);
            assertThat(page.content().get(0).id()).isEqualTo(runnerId);
            assertThat(page.content().get(0).status()).isEqualTo(RunnerStatus.CONNECTED);
        }

        @Test
        void showsDisconnectedWhenHeartbeatExpired() throws InterruptedException {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            waitForHeartbeatExpiry();

            LocalRunner.LocalRunnerPage page = runnerService.listRunners(WORKSPACE_ID, 0, 25);
            assertThat(page.content()).hasSize(1);
            assertThat(page.content().get(0).status()).isEqualTo(RunnerStatus.DISCONNECTED);
        }

        @Test
        void excludesOtherWorkspaces() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            pairAndConnect(OTHER_WORKSPACE_ID, "other-user", "other-runner");

            LocalRunner.LocalRunnerPage page = runnerService.listRunners(WORKSPACE_ID, 0, 25);
            assertThat(page.content()).hasSize(1);
        }

        @Test
        void emptyWhenNoRunners() {
            LocalRunner.LocalRunnerPage page = runnerService.listRunners(WORKSPACE_ID, 0, 25);
            assertThat(page.content()).isEmpty();
        }

        @Test
        void paginatesCorrectly() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            pairAndConnect(WORKSPACE_ID, "user2", "runner2");
            pairAndConnect(WORKSPACE_ID, "user3", "runner3");

            LocalRunner.LocalRunnerPage page0 = runnerService.listRunners(WORKSPACE_ID, 0, 2);
            assertThat(page0.content()).hasSize(2);
            assertThat(page0.total()).isEqualTo(3);

            LocalRunner.LocalRunnerPage page1 = runnerService.listRunners(WORKSPACE_ID, 1, 2);
            assertThat(page1.content()).hasSize(1);
        }
    }

    // --- getRunner tests ---

    @Nested
    class GetRunner {

        @Test
        void returnsRunnerWithAgents() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            LocalRunner.Agent agentInput = LocalRunner.Agent.builder()
                    .project("my-project")
                    .description("Summarizes documents")
                    .language("python")
                    .executable("/usr/bin/python3.11")
                    .sourceFile("agents/summarizer.py")
                    .build();
            runnerService.registerAgents(runnerId, WORKSPACE_ID, Map.of("agent1", agentInput));

            LocalRunner runner = runnerService.getRunner(WORKSPACE_ID, runnerId);
            assertThat(runner.id()).isEqualTo(runnerId);
            assertThat(runner.status()).isEqualTo(RunnerStatus.CONNECTED);
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
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            LocalRunner.Agent agent = LocalRunner.Agent.builder().project("my-project").build();
            runnerService.registerAgents(runnerId, WORKSPACE_ID, Map.of("agent1", agent));

            waitForHeartbeatExpiry();

            LocalRunner runner = runnerService.getRunner(WORKSPACE_ID, runnerId);
            assertThat(runner.status()).isEqualTo(RunnerStatus.DISCONNECTED);
            assertThat(runner.agents()).isEmpty();
        }

        @Test
        void throwsNotFoundForMissing() {
            assertThatThrownBy(() -> runnerService.getRunner(WORKSPACE_ID, UUID.randomUUID()))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsNotFoundForWrongWorkspace() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            assertThatThrownBy(() -> runnerService.getRunner(OTHER_WORKSPACE_ID, runnerId))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    // --- registerAgents tests ---

    @Nested
    class RegisterAgents {

        @Test
        void storesAgentMetadata() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            LocalRunner.Agent agent = LocalRunner.Agent.builder().project("proj1").build();
            runnerService.registerAgents(runnerId, WORKSPACE_ID, Map.of("agent1", agent));

            RMap<String, String> agentsMap = redisClient.getMap(
                    "opik:runners:runner:" + runnerId + ":agents", StringCodec.INSTANCE);
            assertThat(agentsMap.size()).isEqualTo(1);
            assertThat(agentsMap.containsKey("agent1")).isTrue();
        }

        @Test
        void replacesExistingAgents() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            LocalRunner.Agent agent1 = LocalRunner.Agent.builder().project("proj1").build();
            runnerService.registerAgents(runnerId, WORKSPACE_ID, Map.of("agent1", agent1));

            LocalRunner.Agent agent2 = LocalRunner.Agent.builder().project("proj2").build();
            runnerService.registerAgents(runnerId, WORKSPACE_ID, Map.of("agent2", agent2));

            RMap<String, String> agentsMap = redisClient.getMap(
                    "opik:runners:runner:" + runnerId + ":agents", StringCodec.INSTANCE);
            assertThat(agentsMap.size()).isEqualTo(1);
            assertThat(agentsMap.containsKey("agent2")).isTrue();
            assertThat(agentsMap.containsKey("agent1")).isFalse();
        }

        @Test
        void clearsAgentsWhenEmpty() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            LocalRunner.Agent agent = LocalRunner.Agent.builder().build();
            runnerService.registerAgents(runnerId, WORKSPACE_ID, Map.of("agent1", agent));
            runnerService.registerAgents(runnerId, WORKSPACE_ID, Map.of());

            RMap<String, String> agentsMap = redisClient.getMap(
                    "opik:runners:runner:" + runnerId + ":agents", StringCodec.INSTANCE);
            assertThat(agentsMap.isExists()).isFalse();
        }

        @Test
        void storesAndReturnsAgentTimeout() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            LocalRunner.Agent agent = LocalRunner.Agent.builder().project("proj1").timeout(120).build();
            runnerService.registerAgents(runnerId, WORKSPACE_ID, Map.of("agent1", agent));

            LocalRunner runner = runnerService.getRunner(WORKSPACE_ID, runnerId);
            assertThat(runner.agents()).hasSize(1);
            assertThat(runner.agents().get(0).timeout()).isEqualTo(120);
        }

        @Test
        void returnsZeroTimeoutWhenNotSet() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            LocalRunner.Agent agent = LocalRunner.Agent.builder().project("proj1").build();
            runnerService.registerAgents(runnerId, WORKSPACE_ID, Map.of("agent1", agent));

            LocalRunner runner = runnerService.getRunner(WORKSPACE_ID, runnerId);
            assertThat(runner.agents().get(0).timeout()).isEqualTo(0);
        }

        @Test
        void throwsNotFoundForWrongWorkspace() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            LocalRunner.Agent agent = LocalRunner.Agent.builder().build();
            assertThatThrownBy(() -> runnerService.registerAgents(runnerId, OTHER_WORKSPACE_ID, Map.of("a", agent)))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    // --- heartbeat tests ---

    @Nested
    class Heartbeat {

        @Test
        void refreshesHeartbeatTTL() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            HeartbeatResponse resp = runnerService.heartbeat(runnerId, WORKSPACE_ID);
            assertThat(resp).isNotNull();

            RBucket<String> hb = redisClient.getBucket(
                    "opik:runners:runner:" + runnerId + ":heartbeat", StringCodec.INSTANCE);
            assertThat(hb.isExists()).isTrue();
            assertThat(hb.remainTimeToLive()).isPositive();
        }

        @Test
        void updatesLastHeartbeatOnActiveJobs() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            stubNextId();
            LocalRunnerJob claimed = runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();
            assertThat(claimed).isNotNull();

            runnerService.heartbeat(runnerId, WORKSPACE_ID);

            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:runners:job:" + claimed.id(), StringCodec.INSTANCE);
            assertThat(jobMap.get("last_heartbeat")).isNotBlank();
        }

        @Test
        void returnsCancelledJobIds() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();
            runnerService.cancelJob(jobId, WORKSPACE_ID);

            HeartbeatResponse resp = runnerService.heartbeat(runnerId, WORKSPACE_ID);
            assertThat(resp.cancelledJobIds()).contains(jobId);

            HeartbeatResponse resp2 = runnerService.heartbeat(runnerId, WORKSPACE_ID);
            assertThat(resp2.cancelledJobIds()).isEmpty();
        }

        @Test
        void returnsEmptyWhenNoCancellations() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            HeartbeatResponse resp = runnerService.heartbeat(runnerId, WORKSPACE_ID);
            assertThat(resp.cancelledJobIds()).isEmpty();
        }

        @Test
        void throwsGoneForEvictedRunner() {
            UUID oldRunnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, "old");
            connectViaApiKey(WORKSPACE_ID, USER_NAME, "new");

            assertThatThrownBy(() -> runnerService.heartbeat(oldRunnerId, WORKSPACE_ID))
                    .isInstanceOf(ClientErrorException.class)
                    .satisfies(e -> assertThat(((ClientErrorException) e).getResponse().getStatus()).isEqualTo(410));
        }

        @Test
        void throwsGoneForDeletedRunner() {
            assertThatThrownBy(() -> runnerService.heartbeat(UUID.randomUUID(), WORKSPACE_ID))
                    .isInstanceOf(ClientErrorException.class)
                    .satisfies(e -> assertThat(((ClientErrorException) e).getResponse().getStatus()).isEqualTo(410));
        }

        @Test
        void throwsNotFoundForWrongWorkspace() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            assertThatThrownBy(() -> runnerService.heartbeat(runnerId, OTHER_WORKSPACE_ID))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    // --- createJob tests ---

    @Nested
    class CreateJob {

        @Test
        void createsJobAndEnqueues() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            stubNextId();
            CreateJobRequest req = CreateJobRequest.builder()
                    .agentName(AGENT_NAME)
                    .project("my-project")
                    .build();
            UUID jobId = runnerService.createJob(WORKSPACE_ID, USER_NAME, req);

            LocalRunnerJob job = runnerService.getJob(jobId, WORKSPACE_ID);
            assertThat(job.id()).isEqualTo(jobId);
            assertThat(job.runnerId()).isEqualTo(runnerId);
            assertThat(job.agentName()).isEqualTo(AGENT_NAME);
            assertThat(job.status().getValue()).isEqualTo("pending");
            assertThat(job.project()).isEqualTo("my-project");

            RList<String> pending = redisClient.getList(
                    "opik:runners:jobs:" + runnerId + ":pending", StringCodec.INSTANCE);
            assertThat(pending.readAll()).contains(jobId.toString());

            RSet<String> runnerJobs = redisClient.getSet(
                    "opik:runners:runner:" + runnerId + ":jobs", StringCodec.INSTANCE);
            assertThat(runnerJobs.contains(jobId.toString())).isTrue();
        }

        @Test
        void usesUserDefaultRunner() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            stubNextId();
            CreateJobRequest req = CreateJobRequest.builder()
                    .agentName(AGENT_NAME)
                    .build();
            UUID jobId = runnerService.createJob(WORKSPACE_ID, USER_NAME, req);

            LocalRunnerJob job = runnerService.getJob(jobId, WORKSPACE_ID);
            assertThat(job.runnerId()).isEqualTo(runnerId);
        }

        @Test
        void usesExplicitRunnerId() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            stubNextId();
            CreateJobRequest req = CreateJobRequest.builder()
                    .agentName(AGENT_NAME)
                    .runnerId(runnerId)
                    .build();
            UUID jobId = runnerService.createJob(WORKSPACE_ID, USER_NAME, req);

            LocalRunnerJob job = runnerService.getJob(jobId, WORKSPACE_ID);
            assertThat(job.runnerId()).isEqualTo(runnerId);
        }

        @Test
        void throwsNotFoundWhenNoRunner() {
            CreateJobRequest req = CreateJobRequest.builder()
                    .agentName(AGENT_NAME)
                    .build();

            assertThatThrownBy(() -> runnerService.createJob(WORKSPACE_ID, USER_NAME, req))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsConflictWhenRunnerOffline() throws InterruptedException {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            waitForHeartbeatExpiry();

            stubNextId();
            CreateJobRequest req = CreateJobRequest.builder()
                    .agentName(AGENT_NAME)
                    .build();

            assertThatThrownBy(() -> runnerService.createJob(WORKSPACE_ID, USER_NAME, req))
                    .isInstanceOf(ClientErrorException.class)
                    .satisfies(e -> assertThat(((ClientErrorException) e).getResponse().getStatus()).isEqualTo(409));
        }

        @Test
        void throwsTooManyRequests() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            for (int i = 0; i < runnerConfig.getMaxPendingJobsPerRunner(); i++) {
                stubNextId();
                runnerService.createJob(WORKSPACE_ID, USER_NAME,
                        CreateJobRequest.builder().agentName(AGENT_NAME).build());
            }

            stubNextId();
            CreateJobRequest req = CreateJobRequest.builder().agentName(AGENT_NAME).build();

            assertThatThrownBy(() -> runnerService.createJob(WORKSPACE_ID, USER_NAME, req))
                    .isInstanceOf(ClientErrorException.class)
                    .satisfies(e -> assertThat(((ClientErrorException) e).getResponse().getStatus()).isEqualTo(429));
        }

        @Test
        void defaultsProjectToDefault() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            stubNextId();
            CreateJobRequest req = CreateJobRequest.builder()
                    .agentName(AGENT_NAME)
                    .build();
            UUID jobId = runnerService.createJob(WORKSPACE_ID, USER_NAME, req);

            LocalRunnerJob job = runnerService.getJob(jobId, WORKSPACE_ID);
            assertThat(job.project()).isEqualTo("default");
        }

        @Test
        void usesAgentTimeoutWhenSet() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            LocalRunner.Agent agent = LocalRunner.Agent.builder().timeout(300).build();
            runnerService.registerAgents(runnerId, WORKSPACE_ID, Map.of(AGENT_NAME, agent));

            stubNextId();
            UUID jobId = runnerService.createJob(WORKSPACE_ID, USER_NAME,
                    CreateJobRequest.builder().agentName(AGENT_NAME).build());

            LocalRunnerJob job = runnerService.getJob(jobId, WORKSPACE_ID);
            assertThat(job.timeout()).isEqualTo(300);

            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:runners:job:" + jobId, StringCodec.INSTANCE);
            assertThat(jobMap.get("timeout")).isEqualTo("300");
        }

        @Test
        void fallsBackToConfigTimeoutWhenAgentHasNone() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            LocalRunner.Agent agent = LocalRunner.Agent.builder().project("proj1").build();
            runnerService.registerAgents(runnerId, WORKSPACE_ID, Map.of(AGENT_NAME, agent));

            stubNextId();
            UUID jobId = runnerService.createJob(WORKSPACE_ID, USER_NAME,
                    CreateJobRequest.builder().agentName(AGENT_NAME).build());

            LocalRunnerJob job = runnerService.getJob(jobId, WORKSPACE_ID);
            assertThat(job.timeout()).isEqualTo(runnerConfig.getJobTimeout().toSeconds());
        }

        @Test
        void fallsBackToConfigTimeoutWhenNoAgentsRegistered() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            stubNextId();
            UUID jobId = runnerService.createJob(WORKSPACE_ID, USER_NAME,
                    CreateJobRequest.builder().agentName(AGENT_NAME).build());

            LocalRunnerJob job = runnerService.getJob(jobId, WORKSPACE_ID);
            assertThat(job.timeout()).isEqualTo(runnerConfig.getJobTimeout().toSeconds());
        }
    }

    // --- nextJob tests ---

    @Nested
    class NextJob {

        @Test
        void returnsJobWhenPending() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            LocalRunnerJob claimed = runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();
            assertThat(claimed).isNotNull();
            assertThat(claimed.id()).isEqualTo(jobId);
            assertThat(claimed.status().getValue()).isEqualTo("running");
            assertThat(claimed.startedAt()).isNotNull();
        }

        @Test
        void returnsNullWhenNoPendingJobs() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            LocalRunnerJob claimed = runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();
            assertThat(claimed).isNull();
        }

        @Test
        void removesFromPendingAddsToActive() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();

            RList<String> pending = redisClient.getList(
                    "opik:runners:jobs:" + runnerId + ":pending", StringCodec.INSTANCE);
            assertThat(pending.size()).isZero();

            RList<String> active = redisClient.getList(
                    "opik:runners:jobs:" + runnerId + ":active", StringCodec.INSTANCE);
            assertThat(active.readAll()).contains(jobId.toString());
        }

        @Test
        void throwsNotFoundForWrongWorkspace() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            assertThatThrownBy(() -> runnerService.nextJob(runnerId, OTHER_WORKSPACE_ID))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    // --- listJobs tests ---

    @Nested
    class ListJobs {

        @Test
        void returnsJobsForRunner() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            LocalRunnerJob.LocalRunnerJobPage page = runnerService.listJobs(runnerId, null, WORKSPACE_ID, 0, 10);
            assertThat(page.content()).hasSize(2);
            assertThat(page.total()).isEqualTo(2);
        }

        @Test
        void filtersByProject() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            stubNextId();
            runnerService.createJob(WORKSPACE_ID, USER_NAME,
                    CreateJobRequest.builder().agentName(AGENT_NAME).project("proj-a").build());
            stubNextId();
            runnerService.createJob(WORKSPACE_ID, USER_NAME,
                    CreateJobRequest.builder().agentName(AGENT_NAME).project("proj-b").build());

            LocalRunnerJob.LocalRunnerJobPage page = runnerService.listJobs(runnerId, "proj-a", WORKSPACE_ID, 0, 10);
            assertThat(page.content()).hasSize(1);
            assertThat(page.content().get(0).project()).isEqualTo("proj-a");
        }

        @Test
        void paginatesCorrectly() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            for (int i = 0; i < 3; i++) {
                createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            }

            LocalRunnerJob.LocalRunnerJobPage page0 = runnerService.listJobs(runnerId, null, WORKSPACE_ID, 0, 2);
            assertThat(page0.content()).hasSize(2);
            assertThat(page0.total()).isEqualTo(3);

            LocalRunnerJob.LocalRunnerJobPage page1 = runnerService.listJobs(runnerId, null, WORKSPACE_ID, 1, 2);
            assertThat(page1.content()).hasSize(1);
        }

        @Test
        void sortsByCreatedAtDescending() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            LocalRunnerJob.LocalRunnerJobPage page = runnerService.listJobs(runnerId, null, WORKSPACE_ID, 0, 10);
            assertThat(page.content()).hasSize(2);
            assertThat(page.content().get(0).createdAt())
                    .isAfterOrEqualTo(page.content().get(1).createdAt());
        }

        @Test
        void excludesOtherWorkspaces() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            String fakeJobId = UUID.randomUUID().toString();
            RMap<String, String> fakeJob = redisClient.getMap("opik:runners:job:" + fakeJobId, StringCodec.INSTANCE);
            fakeJob.putAll(Map.of(
                    "id", fakeJobId,
                    "runner_id", runnerId.toString(),
                    "agent_name", AGENT_NAME,
                    "status", "pending",
                    "workspace_id", OTHER_WORKSPACE_ID,
                    "created_at", Instant.now().toString()));
            RSet<String> runnerJobs = redisClient.getSet(
                    "opik:runners:runner:" + runnerId + ":jobs", StringCodec.INSTANCE);
            runnerJobs.add(fakeJobId);

            LocalRunnerJob.LocalRunnerJobPage page = runnerService.listJobs(runnerId, null, WORKSPACE_ID, 0, 10);
            assertThat(page.content()).hasSize(1);
        }

        @Test
        void skipsExpiredJobHashes() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            redisClient.getMap("opik:runners:job:" + jobId, StringCodec.INSTANCE).delete();

            LocalRunnerJob.LocalRunnerJobPage page = runnerService.listJobs(runnerId, null, WORKSPACE_ID, 0, 10);
            assertThat(page.content()).isEmpty();
        }
    }

    // --- getJob tests ---

    @Nested
    class GetJob {

        @Test
        void returnsJob() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            LocalRunnerJob fetched = runnerService.getJob(jobId, WORKSPACE_ID);
            assertThat(fetched.id()).isEqualTo(jobId);
            assertThat(fetched.agentName()).isEqualTo(AGENT_NAME);
        }

        @Test
        void throwsNotFoundForMissing() {
            assertThatThrownBy(() -> runnerService.getJob(UUID.randomUUID(), WORKSPACE_ID))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsNotFoundForWrongWorkspace() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            assertThatThrownBy(() -> runnerService.getJob(jobId, OTHER_WORKSPACE_ID))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    // --- getJobLogs tests ---

    @Nested
    class GetJobLogs {

        @Test
        void returnsAllLogs() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            List<LogEntry> entries = List.of(
                    LogEntry.builder().stream("stdout").text("line1").build(),
                    LogEntry.builder().stream("stderr").text("line2").build());
            runnerService.appendLogs(jobId, WORKSPACE_ID, entries);

            List<LogEntry> logs = runnerService.getJobLogs(jobId, 0, WORKSPACE_ID);
            assertThat(logs).hasSize(2);
            assertThat(logs.get(0).text()).isEqualTo("line1");
            assertThat(logs.get(1).text()).isEqualTo("line2");
        }

        @Test
        void returnsLogsFromOffset() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            List<LogEntry> entries = List.of(
                    LogEntry.builder().stream("stdout").text("line1").build(),
                    LogEntry.builder().stream("stdout").text("line2").build(),
                    LogEntry.builder().stream("stdout").text("line3").build());
            runnerService.appendLogs(jobId, WORKSPACE_ID, entries);

            List<LogEntry> logs = runnerService.getJobLogs(jobId, 2, WORKSPACE_ID);
            assertThat(logs).hasSize(1);
            assertThat(logs.get(0).text()).isEqualTo("line3");
        }

        @Test
        void returnsEmptyWhenOffsetBeyondEnd() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            runnerService.appendLogs(jobId, WORKSPACE_ID,
                    List.of(LogEntry.builder().stream("stdout").text("line1").build()));

            List<LogEntry> logs = runnerService.getJobLogs(jobId, 10, WORKSPACE_ID);
            assertThat(logs).isEmpty();
        }

        @Test
        void throwsNotFoundForMissingJob() {
            assertThatThrownBy(() -> runnerService.getJobLogs(UUID.randomUUID(), 0, WORKSPACE_ID))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsNotFoundForWrongWorkspace() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            assertThatThrownBy(() -> runnerService.getJobLogs(jobId, 0, OTHER_WORKSPACE_ID))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    // --- appendLogs tests ---

    @Nested
    class AppendLogs {

        @Test
        void appendsEntriesToList() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            runnerService.appendLogs(jobId, WORKSPACE_ID,
                    List.of(LogEntry.builder().stream("stdout").text("hello").build()));

            RList<String> logsList = redisClient.getList(
                    "opik:runners:job:" + jobId + ":logs", StringCodec.INSTANCE);
            assertThat(logsList.size()).isEqualTo(1);
        }

        @Test
        void appendsMultipleBatches() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            runnerService.appendLogs(jobId, WORKSPACE_ID,
                    List.of(LogEntry.builder().stream("stdout").text("batch1").build()));
            runnerService.appendLogs(jobId, WORKSPACE_ID,
                    List.of(LogEntry.builder().stream("stdout").text("batch2").build()));

            RList<String> logsList = redisClient.getList(
                    "opik:runners:job:" + jobId + ":logs", StringCodec.INSTANCE);
            assertThat(logsList.size()).isEqualTo(2);
        }

        @Test
        void throwsNotFoundForMissingJob() {
            assertThatThrownBy(() -> runnerService.appendLogs(UUID.randomUUID(), WORKSPACE_ID,
                    List.of(LogEntry.builder().stream("stdout").text("x").build())))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsNotFoundForWrongWorkspace() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            assertThatThrownBy(() -> runnerService.appendLogs(jobId, OTHER_WORKSPACE_ID,
                    List.of(LogEntry.builder().stream("stdout").text("x").build())))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    // --- reportResult tests ---

    @Nested
    class ReportResult {

        @Test
        void completedJob() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();

            ObjectNode resultNode = MAPPER.createObjectNode();
            resultNode.put("output", "success");

            runnerService.reportResult(jobId, WORKSPACE_ID,
                    JobResultRequest.builder().status("completed").result(resultNode).build());

            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:runners:job:" + jobId, StringCodec.INSTANCE);
            assertThat(jobMap.get("status")).isEqualTo("completed");
            assertThat(jobMap.get("completed_at")).isNotBlank();
            assertThat(jobMap.get("result")).contains("success");

            RList<String> active = redisClient.getList(
                    "opik:runners:jobs:" + runnerId + ":active", StringCodec.INSTANCE);
            assertThat(active.readAll()).doesNotContain(jobId.toString());
        }

        @Test
        void failedJob() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();

            runnerService.reportResult(jobId, WORKSPACE_ID,
                    JobResultRequest.builder().status("failed").error("something broke").build());

            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:runners:job:" + jobId, StringCodec.INSTANCE);
            assertThat(jobMap.get("status")).isEqualTo("failed");
            assertThat(jobMap.get("error")).isEqualTo("something broke");
            assertThat(jobMap.get("completed_at")).isNotBlank();
        }

        @Test
        void setsTraceId() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();

            UUID traceId = UUID.randomUUID();
            runnerService.reportResult(jobId, WORKSPACE_ID,
                    JobResultRequest.builder().status("completed").traceId(traceId).build());

            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:runners:job:" + jobId, StringCodec.INSTANCE);
            assertThat(jobMap.get("trace_id")).isEqualTo(traceId.toString());
        }

        @Test
        void setsTTLOnJobAndLogs() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();

            runnerService.appendLogs(jobId, WORKSPACE_ID,
                    List.of(LogEntry.builder().stream("stdout").text("log").build()));

            runnerService.reportResult(jobId, WORKSPACE_ID,
                    JobResultRequest.builder().status("completed").build());

            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:runners:job:" + jobId, StringCodec.INSTANCE);
            assertThat(jobMap.remainTimeToLive()).isPositive();

            RList<String> logsList = redisClient.getList(
                    "opik:runners:job:" + jobId + ":logs", StringCodec.INSTANCE);
            assertThat(logsList.remainTimeToLive()).isPositive();
        }

        @Test
        void throwsBadRequestForInvalidStatus() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();

            assertThatThrownBy(() -> runnerService.reportResult(jobId, WORKSPACE_ID,
                    JobResultRequest.builder().status("running").build()))
                    .isInstanceOf(ClientErrorException.class)
                    .satisfies(e -> assertThat(((ClientErrorException) e).getResponse().getStatus()).isEqualTo(400));
        }

        @Test
        void throwsNotFoundForMissing() {
            assertThatThrownBy(() -> runnerService.reportResult(UUID.randomUUID(), WORKSPACE_ID,
                    JobResultRequest.builder().status("completed").build()))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsNotFoundForWrongWorkspace() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            assertThatThrownBy(() -> runnerService.reportResult(jobId, OTHER_WORKSPACE_ID,
                    JobResultRequest.builder().status("completed").build()))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    // --- cancelJob tests ---

    @Nested
    class CancelJob {

        @Test
        void cancelActiveJob_addsToCancellationSet() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();

            runnerService.cancelJob(jobId, WORKSPACE_ID);

            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:runners:job:" + jobId, StringCodec.INSTANCE);
            assertThat(jobMap.get("status")).isEqualTo("cancelled");
            assertThat(jobMap.get("completed_at")).isNotBlank();

            RSet<String> cancellations = redisClient.getSet(
                    "opik:runners:runner:" + runnerId + ":cancellations", StringCodec.INSTANCE);
            assertThat(cancellations.contains(jobId.toString())).isTrue();
        }

        @Test
        void cancelPendingJob_removesFromPendingQueue() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            runnerService.cancelJob(jobId, WORKSPACE_ID);

            RList<String> pending = redisClient.getList(
                    "opik:runners:jobs:" + runnerId + ":pending", StringCodec.INSTANCE);
            assertThat(pending.readAll()).doesNotContain(jobId.toString());

            RSet<String> cancellations = redisClient.getSet(
                    "opik:runners:runner:" + runnerId + ":cancellations", StringCodec.INSTANCE);
            assertThat(cancellations.contains(jobId.toString())).isFalse();

            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:runners:job:" + jobId, StringCodec.INSTANCE);
            assertThat(jobMap.get("status")).isEqualTo("cancelled");
            assertThat(jobMap.remainTimeToLive()).isPositive();
        }

        @Test
        void throwsNotFoundForMissing() {
            assertThatThrownBy(() -> runnerService.cancelJob(UUID.randomUUID(), WORKSPACE_ID))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsNotFoundForWrongWorkspace() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            assertThatThrownBy(() -> runnerService.cancelJob(jobId, OTHER_WORKSPACE_ID))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    // --- reapDeadRunners tests ---

    @Nested
    class ReapDeadRunners {

        @Test
        void failsOrphanedActiveJobs() throws InterruptedException {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();

            waitForHeartbeatExpiry();
            runnerService.reapDeadRunners();

            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:runners:job:" + jobId, StringCodec.INSTANCE);
            assertThat(jobMap.get("status")).isEqualTo("failed");
            assertThat(jobMap.get("error")).isEqualTo("Runner disconnected");
        }

        @Test
        void failsOrphanedPendingJobs() throws InterruptedException {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            waitForHeartbeatExpiry();
            runnerService.reapDeadRunners();

            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:runners:job:" + jobId, StringCodec.INSTANCE);
            assertThat(jobMap.get("status")).isEqualTo("failed");
            assertThat(jobMap.get("error")).isEqualTo("Runner disconnected");
        }

        @Test
        void purgesLongDeadRunners() throws InterruptedException {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            waitForHeartbeatExpiry();
            runnerService.reapDeadRunners();

            RMap<String, String> runnerMap = redisClient.getMap(
                    "opik:runners:runner:" + runnerId, StringCodec.INSTANCE);
            assertThat(runnerMap.isExists()).isFalse();

            RBucket<String> hb = redisClient.getBucket(
                    "opik:runners:runner:" + runnerId + ":heartbeat", StringCodec.INSTANCE);
            assertThat(hb.isExists()).isFalse();

            RSet<String> wsRunners = redisClient.getSet(
                    "opik:runners:workspace:" + WORKSPACE_ID + ":runners", StringCodec.INSTANCE);
            assertThat(wsRunners.contains(runnerId.toString())).isFalse();
        }

        @Test
        void skipsAliveRunners() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            runnerService.reapDeadRunners();

            RMap<String, String> runnerMap = redisClient.getMap(
                    "opik:runners:runner:" + runnerId, StringCodec.INSTANCE);
            assertThat(runnerMap.isExists()).isTrue();
        }

        @Test
        void removesEmptyWorkspace() throws InterruptedException {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            waitForHeartbeatExpiry();
            runnerService.reapDeadRunners();

            RSet<String> workspaces = redisClient.getSet(
                    "opik:runners:workspaces:with_runners", StringCodec.INSTANCE);
            assertThat(workspaces.contains(WORKSPACE_ID)).isFalse();
        }

        @Test
        void handlesErrorPerRunner() throws InterruptedException {
            UUID runner1 = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID runner2 = pairAndConnect(WORKSPACE_ID, "user2", "runner2");

            waitForHeartbeatExpiry();
            runnerService.reapDeadRunners();

            RMap<String, String> map1 = redisClient.getMap(
                    "opik:runners:runner:" + runner1, StringCodec.INSTANCE);
            RMap<String, String> map2 = redisClient.getMap(
                    "opik:runners:runner:" + runner2, StringCodec.INSTANCE);
            assertThat(map1.isExists()).isFalse();
            assertThat(map2.isExists()).isFalse();
        }

        @Test
        void cleansRunnerJobsSetOnReap() throws InterruptedException {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            waitForHeartbeatExpiry();
            runnerService.reapDeadRunners();

            RSet<String> runnerJobs = redisClient.getSet(
                    "opik:runners:runner:" + runnerId + ":jobs", StringCodec.INSTANCE);
            assertThat(runnerJobs.isExists()).isFalse();
        }

        @Test
        void recordsDisconnectedAtOnFirstReap() throws InterruptedException {
            Duration originalPurgeTime = runnerConfig.getDeadRunnerPurgeTime();
            runnerConfig.setDeadRunnerPurgeTime(Duration.hours(999));
            try {
                UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

                waitForHeartbeatExpiry();
                runnerService.reapDeadRunners();

                RMap<String, String> runnerMap = redisClient.getMap(
                        "opik:runners:runner:" + runnerId, StringCodec.INSTANCE);
                assertThat(runnerMap.get("disconnected_at")).isNotBlank();
            } finally {
                runnerConfig.setDeadRunnerPurgeTime(originalPurgeTime);
            }
        }
    }

    // --- reapStuckJobs tests ---

    @Nested
    class ReapStuckJobs {

        @Test
        void failsJobExceedingPerJobTimeout() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            LocalRunner.Agent agent = LocalRunner.Agent.builder().timeout(60).build();
            runnerService.registerAgents(runnerId, WORKSPACE_ID, Map.of(AGENT_NAME, agent));

            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();

            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:runners:job:" + jobId, StringCodec.INSTANCE);
            jobMap.put("started_at", Instant.now().minus(java.time.Duration.ofSeconds(120)).toString());

            runnerService.reapDeadRunners();

            assertThat(jobMap.get("status")).isEqualTo("failed");
            assertThat(jobMap.get("error")).contains("timed out");
            assertThat(jobMap.get("error")).contains("60s");
        }

        @Test
        void skipsJobWithinTimeout() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();

            runnerService.reapDeadRunners();

            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:runners:job:" + jobId, StringCodec.INSTANCE);
            assertThat(jobMap.get("status")).isEqualTo("running");
        }

        @Test
        void usesConfigDefaultWhenJobHasNoTimeout() {
            Duration originalTimeout = runnerConfig.getJobTimeout();
            runnerConfig.setJobTimeout(Duration.seconds(10));
            try {
                UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
                UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
                runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();

                RMap<String, String> jobMap = redisClient.getMap(
                        "opik:runners:job:" + jobId, StringCodec.INSTANCE);
                jobMap.remove("timeout");

                jobMap.put("started_at", Instant.now().minus(java.time.Duration.ofSeconds(30)).toString());

                runnerService.reapDeadRunners();

                assertThat(jobMap.get("status")).isEqualTo("failed");
                assertThat(jobMap.get("error")).contains("10s");
            } finally {
                runnerConfig.setJobTimeout(originalTimeout);
            }
        }

        @Test
        void removesReapedJobFromActiveList() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            LocalRunner.Agent agent = LocalRunner.Agent.builder().timeout(5).build();
            runnerService.registerAgents(runnerId, WORKSPACE_ID, Map.of(AGENT_NAME, agent));

            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();

            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:runners:job:" + jobId, StringCodec.INSTANCE);
            jobMap.put("started_at", Instant.now().minus(java.time.Duration.ofSeconds(30)).toString());

            runnerService.reapDeadRunners();

            RList<String> activeJobs = redisClient.getList(
                    "opik:runners:jobs:" + runnerId + ":active", StringCodec.INSTANCE);
            assertThat(activeJobs.readAll()).doesNotContain(jobId.toString());
        }

        @Test
        void reapsStuckJobsOnAliveRunners() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            LocalRunner.Agent agent = LocalRunner.Agent.builder().timeout(5).build();
            runnerService.registerAgents(runnerId, WORKSPACE_ID, Map.of(AGENT_NAME, agent));

            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();

            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:runners:job:" + jobId, StringCodec.INSTANCE);
            jobMap.put("started_at", Instant.now().minus(java.time.Duration.ofSeconds(30)).toString());

            runnerService.reapDeadRunners();

            assertThat(jobMap.get("status")).isEqualTo("failed");
            assertThat(jobMap.get("error")).contains("timed out");
        }

        @Test
        void doesNotReapAlreadyCompletedJob() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            LocalRunner.Agent agent = LocalRunner.Agent.builder().timeout(5).build();
            runnerService.registerAgents(runnerId, WORKSPACE_ID, Map.of(AGENT_NAME, agent));

            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();

            runnerService.reportResult(jobId, WORKSPACE_ID,
                    JobResultRequest.builder().status("completed").build());

            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:runners:job:" + jobId, StringCodec.INSTANCE);
            jobMap.put("started_at", Instant.now().minus(java.time.Duration.ofSeconds(30)).toString());

            runnerService.reapDeadRunners();

            assertThat(jobMap.get("status")).isEqualTo("completed");
        }
    }

    // --- Full flow test ---

    @Test
    void fullFlow_pairConnectCreateJobNextJobReportResult() {
        stubNextId();
        PairResponse pair = runnerService.generatePairingCode(WORKSPACE_ID, USER_NAME);
        assertThat(pair.pairingCode()).hasSize(6);

        ConnectRequest connectReq = ConnectRequest.builder()
                .pairingCode(pair.pairingCode())
                .runnerName(RUNNER_NAME)
                .build();
        UUID runnerId = runnerService.connect(WORKSPACE_ID, USER_NAME, connectReq);
        assertThat(runnerId).isEqualTo(pair.runnerId());

        LocalRunner.Agent agentMeta = LocalRunner.Agent.builder().project("my-project").build();
        runnerService.registerAgents(runnerId, WORKSPACE_ID, Map.of(AGENT_NAME, agentMeta));

        LocalRunner.LocalRunnerPage runnerPage = runnerService.listRunners(WORKSPACE_ID, 0, 25);
        assertThat(runnerPage.content()).hasSize(1);
        assertThat(runnerPage.content().get(0).agents()).hasSize(1);

        stubNextId();
        ObjectNode inputs = MAPPER.createObjectNode();
        inputs.put("prompt", "hello");
        CreateJobRequest jobReq = CreateJobRequest.builder()
                .agentName(AGENT_NAME)
                .project("my-project")
                .inputs(inputs)
                .build();
        UUID jobId = runnerService.createJob(WORKSPACE_ID, USER_NAME, jobReq);

        LocalRunnerJob created = runnerService.getJob(jobId, WORKSPACE_ID);
        assertThat(created.status().getValue()).isEqualTo("pending");

        LocalRunnerJob claimed = runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();
        assertThat(claimed).isNotNull();
        assertThat(claimed.id()).isEqualTo(jobId);
        assertThat(claimed.status().getValue()).isEqualTo("running");

        HeartbeatResponse hbResp = runnerService.heartbeat(runnerId, WORKSPACE_ID);
        assertThat(hbResp.cancelledJobIds()).isEmpty();

        runnerService.appendLogs(claimed.id(), WORKSPACE_ID,
                List.of(LogEntry.builder().stream("stdout").text("Processing...").build()));

        UUID traceId = UUID.randomUUID();
        ObjectNode resultNode = MAPPER.createObjectNode();
        resultNode.put("answer", "world");
        runnerService.reportResult(claimed.id(), WORKSPACE_ID,
                JobResultRequest.builder()
                        .status("completed")
                        .result(resultNode)
                        .traceId(traceId)
                        .build());

        LocalRunnerJob finalJob = runnerService.getJob(claimed.id(), WORKSPACE_ID);
        assertThat(finalJob.status().getValue()).isEqualTo("completed");
        assertThat(finalJob.traceId()).isEqualTo(traceId);
        assertThat(finalJob.result().get("answer").asText()).isEqualTo("world");

        List<LogEntry> logs = runnerService.getJobLogs(claimed.id(), 0, WORKSPACE_ID);
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).text()).isEqualTo("Processing...");
    }
}
