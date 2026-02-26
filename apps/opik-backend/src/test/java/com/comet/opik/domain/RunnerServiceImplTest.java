package com.comet.opik.domain;

import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.runner.ConnectRequest;
import com.comet.opik.api.runner.ConnectResponse;
import com.comet.opik.api.runner.CreateJobRequest;
import com.comet.opik.api.runner.HeartbeatResponse;
import com.comet.opik.api.runner.JobResultRequest;
import com.comet.opik.api.runner.LogEntry;
import com.comet.opik.api.runner.PairResponse;
import com.comet.opik.api.runner.Runner;
import com.comet.opik.api.runner.RunnerJob;
import com.comet.opik.infrastructure.RunnerConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.redis.testcontainers.RedisContainer;
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

import java.time.Duration;
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
        runnerConfig.setHeartbeatTtlSeconds(2);
        runnerConfig.setNextJobPollTimeoutSeconds(1);
        runnerConfig.setMaxPendingJobsPerRunner(3);
        runnerConfig.setDeadRunnerPurgeHours(0);
        runnerConfig.setCompletedJobTtlDays(7);

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

    private String pairAndConnect(String workspaceId, String userName, String runnerName) {
        stubNextId();
        PairResponse pair = runnerService.generatePairingCode(workspaceId, userName);
        ConnectRequest req = ConnectRequest.builder()
                .pairingCode(pair.pairingCode())
                .runnerName(runnerName)
                .build();
        ConnectResponse resp = runnerService.connect(workspaceId, userName, req);
        return resp.runnerId();
    }

    private String connectViaApiKey(String workspaceId, String userName, String runnerName) {
        stubNextId();
        ConnectRequest req = ConnectRequest.builder()
                .runnerName(runnerName)
                .build();
        ConnectResponse resp = runnerService.connect(workspaceId, userName, req);
        return resp.runnerId();
    }

    private RunnerJob createTestJob(String workspaceId, String userName, String agentName) {
        stubNextId();
        CreateJobRequest req = CreateJobRequest.builder()
                .agentName(agentName)
                .build();
        return runnerService.createJob(workspaceId, userName, req);
    }

    private void waitForHeartbeatExpiry() throws InterruptedException {
        Thread.sleep((runnerConfig.getHeartbeatTtlSeconds() + 1) * 1000L);
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
            assertThat(resp.runnerId()).isNotBlank();
            assertThat(resp.expiresInSeconds()).isEqualTo(300);
        }

        @Test
        void createsPairKeyInRedis() {
            stubNextId();
            PairResponse resp = runnerService.generatePairingCode(WORKSPACE_ID, USER_NAME);

            RBucket<String> pairBucket = redisClient.getBucket(
                    "opik:pair:" + resp.pairingCode(), StringCodec.INSTANCE);
            assertThat(pairBucket.isExists()).isTrue();
            assertThat(pairBucket.get()).isEqualTo(resp.runnerId() + ":" + WORKSPACE_ID);
            assertThat(pairBucket.remainTimeToLive()).isPositive();
        }

        @Test
        void createsRunnerHash() {
            stubNextId();
            PairResponse resp = runnerService.generatePairingCode(WORKSPACE_ID, USER_NAME);

            RMap<String, String> runnerMap = redisClient.getMap(
                    "opik:runner:" + resp.runnerId(), StringCodec.INSTANCE);
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
                    "opik:workspace:" + WORKSPACE_ID + ":runners", StringCodec.INSTANCE);
            assertThat(wsRunners.contains(resp.runnerId())).isTrue();

            RSet<String> workspaces = redisClient.getSet(
                    "opik:workspaces:with_runners", StringCodec.INSTANCE);
            assertThat(workspaces.contains(WORKSPACE_ID)).isTrue();
        }

        @Test
        void setsUserRunnerMapping() {
            stubNextId();
            PairResponse resp = runnerService.generatePairingCode(WORKSPACE_ID, USER_NAME);

            RBucket<String> userRunner = redisClient.getBucket(
                    "opik:workspace:" + WORKSPACE_ID + ":user:" + USER_NAME + ":runner",
                    StringCodec.INSTANCE);
            assertThat(userRunner.get()).isEqualTo(resp.runnerId());
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
            ConnectResponse resp = runnerService.connect(WORKSPACE_ID, USER_NAME, req);

            assertThat(resp.runnerId()).isEqualTo(pair.runnerId());
            assertThat(resp.workspaceId()).isEqualTo(WORKSPACE_ID);

            RBucket<String> pairBucket = redisClient.getBucket(
                    "opik:pair:" + pair.pairingCode(), StringCodec.INSTANCE);
            assertThat(pairBucket.isExists()).isFalse();

            RMap<String, String> runnerMap = redisClient.getMap(
                    "opik:runner:" + resp.runnerId(), StringCodec.INSTANCE);
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
                    "opik:runner:" + pair.runnerId(), StringCodec.INSTANCE);
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
            ConnectResponse resp = runnerService.connect(WORKSPACE_ID, USER_NAME, req);

            RBucket<String> hb = redisClient.getBucket(
                    "opik:runner:" + resp.runnerId() + ":heartbeat", StringCodec.INSTANCE);
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
            String runnerId = connectViaApiKey(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            RMap<String, String> runnerMap = redisClient.getMap(
                    "opik:runner:" + runnerId, StringCodec.INSTANCE);
            assertThat(runnerMap.get("status")).isEqualTo("connected");
            assertThat(runnerMap.get("name")).isEqualTo(RUNNER_NAME);
            assertThat(runnerMap.get("workspace_id")).isEqualTo(WORKSPACE_ID);

            RBucket<String> hb = redisClient.getBucket(
                    "opik:runner:" + runnerId + ":heartbeat", StringCodec.INSTANCE);
            assertThat(hb.isExists()).isTrue();
        }

        @Test
        void replacesExistingRunner() {
            String oldRunnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, "old-runner");
            String newRunnerId = connectViaApiKey(WORKSPACE_ID, USER_NAME, "new-runner");

            assertThat(newRunnerId).isNotEqualTo(oldRunnerId);

            RBucket<String> oldHb = redisClient.getBucket(
                    "opik:runner:" + oldRunnerId + ":heartbeat", StringCodec.INSTANCE);
            assertThat(oldHb.isExists()).isFalse();

            RBucket<String> userRunner = redisClient.getBucket(
                    "opik:workspace:" + WORKSPACE_ID + ":user:" + USER_NAME + ":runner",
                    StringCodec.INSTANCE);
            assertThat(userRunner.get()).isEqualTo(newRunnerId);
        }
    }

    // --- listRunners tests ---

    @Nested
    class ListRunners {

        @Test
        void returnsConnectedRunners() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            List<Runner> runners = runnerService.listRunners(WORKSPACE_ID);
            assertThat(runners).hasSize(1);
            assertThat(runners.get(0).id()).isEqualTo(runnerId);
            assertThat(runners.get(0).status()).isEqualTo("connected");
        }

        @Test
        void showsDisconnectedWhenHeartbeatExpired() throws InterruptedException {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            waitForHeartbeatExpiry();

            List<Runner> runners = runnerService.listRunners(WORKSPACE_ID);
            assertThat(runners).hasSize(1);
            assertThat(runners.get(0).status()).isEqualTo("disconnected");
        }

        @Test
        void excludesOtherWorkspaces() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            pairAndConnect(OTHER_WORKSPACE_ID, "other-user", "other-runner");

            List<Runner> runners = runnerService.listRunners(WORKSPACE_ID);
            assertThat(runners).hasSize(1);
        }

        @Test
        void emptyWhenNoRunners() {
            List<Runner> runners = runnerService.listRunners(WORKSPACE_ID);
            assertThat(runners).isEmpty();
        }
    }

    // --- getRunner tests ---

    @Nested
    class GetRunner {

        @Test
        void returnsRunnerWithAgents() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            ObjectNode meta = MAPPER.createObjectNode();
            meta.put("project", "my-project");
            meta.put("description", "Summarizes documents");
            meta.put("language", "python");
            meta.put("executable", "/usr/bin/python3.11");
            meta.put("source_file", "agents/summarizer.py");
            runnerService.registerAgents(runnerId, WORKSPACE_ID, Map.of("agent1", meta));

            Runner runner = runnerService.getRunner(WORKSPACE_ID, runnerId);
            assertThat(runner.id()).isEqualTo(runnerId);
            assertThat(runner.status()).isEqualTo("connected");
            assertThat(runner.agents()).hasSize(1);
            Runner.Agent agent = runner.agents().get(0);
            assertThat(agent.name()).isEqualTo("agent1");
            assertThat(agent.project()).isEqualTo("my-project");
            assertThat(agent.description()).isEqualTo("Summarizes documents");
            assertThat(agent.language()).isEqualTo("python");
            assertThat(agent.executable()).isEqualTo("/usr/bin/python3.11");
            assertThat(agent.sourceFile()).isEqualTo("agents/summarizer.py");
        }

        @Test
        void returnsEmptyAgentsWhenDisconnected() throws InterruptedException {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            ObjectNode meta = MAPPER.createObjectNode();
            meta.put("project", "my-project");
            runnerService.registerAgents(runnerId, WORKSPACE_ID, Map.of("agent1", meta));

            waitForHeartbeatExpiry();

            Runner runner = runnerService.getRunner(WORKSPACE_ID, runnerId);
            assertThat(runner.status()).isEqualTo("disconnected");
            assertThat(runner.agents()).isEmpty();
        }

        @Test
        void throwsNotFoundForMissing() {
            assertThatThrownBy(() -> runnerService.getRunner(WORKSPACE_ID, "non-existent"))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsNotFoundForWrongWorkspace() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            assertThatThrownBy(() -> runnerService.getRunner(OTHER_WORKSPACE_ID, runnerId))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    // --- registerAgents tests ---

    @Nested
    class RegisterAgents {

        @Test
        void storesAgentMetadata() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            ObjectNode meta = MAPPER.createObjectNode();
            meta.put("project", "proj1");
            runnerService.registerAgents(runnerId, WORKSPACE_ID, Map.of("agent1", meta));

            RMap<String, String> agentsMap = redisClient.getMap(
                    "opik:runner:" + runnerId + ":agents", StringCodec.INSTANCE);
            assertThat(agentsMap.size()).isEqualTo(1);
            assertThat(agentsMap.containsKey("agent1")).isTrue();
        }

        @Test
        void replacesExistingAgents() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            ObjectNode meta1 = MAPPER.createObjectNode();
            meta1.put("project", "proj1");
            runnerService.registerAgents(runnerId, WORKSPACE_ID, Map.of("agent1", meta1));

            ObjectNode meta2 = MAPPER.createObjectNode();
            meta2.put("project", "proj2");
            runnerService.registerAgents(runnerId, WORKSPACE_ID, Map.of("agent2", meta2));

            RMap<String, String> agentsMap = redisClient.getMap(
                    "opik:runner:" + runnerId + ":agents", StringCodec.INSTANCE);
            assertThat(agentsMap.size()).isEqualTo(1);
            assertThat(agentsMap.containsKey("agent2")).isTrue();
            assertThat(agentsMap.containsKey("agent1")).isFalse();
        }

        @Test
        void clearsAgentsWhenEmpty() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            ObjectNode meta = MAPPER.createObjectNode();
            runnerService.registerAgents(runnerId, WORKSPACE_ID, Map.of("agent1", meta));
            runnerService.registerAgents(runnerId, WORKSPACE_ID, Map.of());

            RMap<String, String> agentsMap = redisClient.getMap(
                    "opik:runner:" + runnerId + ":agents", StringCodec.INSTANCE);
            assertThat(agentsMap.isExists()).isFalse();
        }

        @Test
        void storesAndReturnsAgentTimeout() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            ObjectNode meta = MAPPER.createObjectNode();
            meta.put("project", "proj1");
            meta.put("timeout", 120);
            runnerService.registerAgents(runnerId, WORKSPACE_ID, Map.of("agent1", meta));

            Runner runner = runnerService.getRunner(WORKSPACE_ID, runnerId);
            assertThat(runner.agents()).hasSize(1);
            assertThat(runner.agents().get(0).timeout()).isEqualTo(120);
        }

        @Test
        void returnsNullTimeoutWhenNotSet() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            ObjectNode meta = MAPPER.createObjectNode();
            meta.put("project", "proj1");
            runnerService.registerAgents(runnerId, WORKSPACE_ID, Map.of("agent1", meta));

            Runner runner = runnerService.getRunner(WORKSPACE_ID, runnerId);
            assertThat(runner.agents().get(0).timeout()).isNull();
        }

        @Test
        void throwsNotFoundForWrongWorkspace() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            ObjectNode meta = MAPPER.createObjectNode();
            assertThatThrownBy(() -> runnerService.registerAgents(runnerId, OTHER_WORKSPACE_ID, Map.of("a", meta)))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    // --- heartbeat tests ---

    @Nested
    class Heartbeat {

        @Test
        void refreshesHeartbeatTTL() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            HeartbeatResponse resp = runnerService.heartbeat(runnerId, WORKSPACE_ID);
            assertThat(resp).isNotNull();

            RBucket<String> hb = redisClient.getBucket(
                    "opik:runner:" + runnerId + ":heartbeat", StringCodec.INSTANCE);
            assertThat(hb.isExists()).isTrue();
            assertThat(hb.remainTimeToLive()).isPositive();
        }

        @Test
        void updatesLastHeartbeatOnActiveJobs() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            RunnerJob job = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            stubNextId();
            RunnerJob claimed = runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();
            assertThat(claimed).isNotNull();

            runnerService.heartbeat(runnerId, WORKSPACE_ID);

            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:job:" + claimed.id(), StringCodec.INSTANCE);
            assertThat(jobMap.get("last_heartbeat")).isNotBlank();
        }

        @Test
        void returnsCancelledJobIds() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            RunnerJob job = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            // Claim the job so it's active, then cancel (cancellation set is only for active jobs now)
            runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();
            runnerService.cancelJob(job.id(), WORKSPACE_ID);

            HeartbeatResponse resp = runnerService.heartbeat(runnerId, WORKSPACE_ID);
            assertThat(resp.cancelledJobIds()).contains(job.id());

            HeartbeatResponse resp2 = runnerService.heartbeat(runnerId, WORKSPACE_ID);
            assertThat(resp2.cancelledJobIds()).isEmpty();
        }

        @Test
        void returnsEmptyWhenNoCancellations() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            HeartbeatResponse resp = runnerService.heartbeat(runnerId, WORKSPACE_ID);
            assertThat(resp.cancelledJobIds()).isEmpty();
        }

        @Test
        void throwsGoneForEvictedRunner() {
            String oldRunnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, "old");
            connectViaApiKey(WORKSPACE_ID, USER_NAME, "new");

            assertThatThrownBy(() -> runnerService.heartbeat(oldRunnerId, WORKSPACE_ID))
                    .isInstanceOf(ClientErrorException.class)
                    .satisfies(e -> assertThat(((ClientErrorException) e).getResponse().getStatus()).isEqualTo(410));
        }

        @Test
        void throwsGoneForDeletedRunner() {
            assertThatThrownBy(() -> runnerService.heartbeat("non-existent", WORKSPACE_ID))
                    .isInstanceOf(ClientErrorException.class)
                    .satisfies(e -> assertThat(((ClientErrorException) e).getResponse().getStatus()).isEqualTo(410));
        }

        @Test
        void throwsNotFoundForWrongWorkspace() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            assertThatThrownBy(() -> runnerService.heartbeat(runnerId, OTHER_WORKSPACE_ID))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    // --- createJob tests ---

    @Nested
    class CreateJob {

        @Test
        void createsJobAndEnqueues() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            stubNextId();
            CreateJobRequest req = CreateJobRequest.builder()
                    .agentName(AGENT_NAME)
                    .project("my-project")
                    .build();
            RunnerJob job = runnerService.createJob(WORKSPACE_ID, USER_NAME, req);

            assertThat(job.id()).isNotBlank();
            assertThat(job.runnerId()).isEqualTo(runnerId);
            assertThat(job.agentName()).isEqualTo(AGENT_NAME);
            assertThat(job.status()).isEqualTo("pending");
            assertThat(job.project()).isEqualTo("my-project");

            RList<String> pending = redisClient.getList(
                    "opik:jobs:" + runnerId + ":pending", StringCodec.INSTANCE);
            assertThat(pending.readAll()).contains(job.id());

            RSet<String> runnerJobs = redisClient.getSet(
                    "opik:runner:" + runnerId + ":jobs", StringCodec.INSTANCE);
            assertThat(runnerJobs.contains(job.id())).isTrue();
        }

        @Test
        void usesUserDefaultRunner() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            stubNextId();
            CreateJobRequest req = CreateJobRequest.builder()
                    .agentName(AGENT_NAME)
                    .build();
            RunnerJob job = runnerService.createJob(WORKSPACE_ID, USER_NAME, req);

            assertThat(job.runnerId()).isEqualTo(runnerId);
        }

        @Test
        void usesExplicitRunnerId() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            stubNextId();
            CreateJobRequest req = CreateJobRequest.builder()
                    .agentName(AGENT_NAME)
                    .runnerId(runnerId)
                    .build();
            RunnerJob job = runnerService.createJob(WORKSPACE_ID, USER_NAME, req);

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
            RunnerJob job = runnerService.createJob(WORKSPACE_ID, USER_NAME, req);

            assertThat(job.project()).isEqualTo("default");
        }

        @Test
        void usesAgentTimeoutWhenSet() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            ObjectNode meta = MAPPER.createObjectNode();
            meta.put("timeout", 300);
            runnerService.registerAgents(runnerId, WORKSPACE_ID, Map.of(AGENT_NAME, meta));

            stubNextId();
            RunnerJob job = runnerService.createJob(WORKSPACE_ID, USER_NAME,
                    CreateJobRequest.builder().agentName(AGENT_NAME).build());

            assertThat(job.timeout()).isEqualTo(300);

            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:job:" + job.id(), StringCodec.INSTANCE);
            assertThat(jobMap.get("timeout")).isEqualTo("300");
        }

        @Test
        void fallsBackToConfigTimeoutWhenAgentHasNone() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            ObjectNode meta = MAPPER.createObjectNode();
            meta.put("project", "proj1");
            runnerService.registerAgents(runnerId, WORKSPACE_ID, Map.of(AGENT_NAME, meta));

            stubNextId();
            RunnerJob job = runnerService.createJob(WORKSPACE_ID, USER_NAME,
                    CreateJobRequest.builder().agentName(AGENT_NAME).build());

            assertThat(job.timeout()).isEqualTo(runnerConfig.getJobTimeoutSeconds());
        }

        @Test
        void fallsBackToConfigTimeoutWhenNoAgentsRegistered() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            stubNextId();
            RunnerJob job = runnerService.createJob(WORKSPACE_ID, USER_NAME,
                    CreateJobRequest.builder().agentName(AGENT_NAME).build());

            assertThat(job.timeout()).isEqualTo(runnerConfig.getJobTimeoutSeconds());
        }
    }

    // --- nextJob tests ---

    @Nested
    class NextJob {

        @Test
        void returnsJobWhenPending() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            RunnerJob created = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            RunnerJob claimed = runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();
            assertThat(claimed).isNotNull();
            assertThat(claimed.id()).isEqualTo(created.id());
            assertThat(claimed.status()).isEqualTo("running");
            assertThat(claimed.startedAt()).isNotNull();
        }

        @Test
        void returnsNullWhenNoPendingJobs() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            RunnerJob claimed = runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();
            assertThat(claimed).isNull();
        }

        @Test
        void removesFromPendingAddsToActive() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            RunnerJob created = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();

            RList<String> pending = redisClient.getList(
                    "opik:jobs:" + runnerId + ":pending", StringCodec.INSTANCE);
            assertThat(pending.size()).isZero();

            RList<String> active = redisClient.getList(
                    "opik:jobs:" + runnerId + ":active", StringCodec.INSTANCE);
            assertThat(active.readAll()).contains(created.id());
        }

        @Test
        void throwsNotFoundForWrongWorkspace() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            assertThatThrownBy(() -> runnerService.nextJob(runnerId, OTHER_WORKSPACE_ID))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    // --- listJobs tests ---

    @Nested
    class ListJobs {

        @Test
        void returnsJobsForRunner() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            RunnerJob.RunnerJobPage page = runnerService.listJobs(runnerId, null, WORKSPACE_ID, 0, 10);
            assertThat(page.content()).hasSize(2);
            assertThat(page.total()).isEqualTo(2);
        }

        @Test
        void filtersByProject() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            stubNextId();
            runnerService.createJob(WORKSPACE_ID, USER_NAME,
                    CreateJobRequest.builder().agentName(AGENT_NAME).project("proj-a").build());
            stubNextId();
            runnerService.createJob(WORKSPACE_ID, USER_NAME,
                    CreateJobRequest.builder().agentName(AGENT_NAME).project("proj-b").build());

            RunnerJob.RunnerJobPage page = runnerService.listJobs(runnerId, "proj-a", WORKSPACE_ID, 0, 10);
            assertThat(page.content()).hasSize(1);
            assertThat(page.content().get(0).project()).isEqualTo("proj-a");
        }

        @Test
        void paginatesCorrectly() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            for (int i = 0; i < 3; i++) {
                createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            }

            RunnerJob.RunnerJobPage page0 = runnerService.listJobs(runnerId, null, WORKSPACE_ID, 0, 2);
            assertThat(page0.content()).hasSize(2);
            assertThat(page0.total()).isEqualTo(3);

            RunnerJob.RunnerJobPage page1 = runnerService.listJobs(runnerId, null, WORKSPACE_ID, 1, 2);
            assertThat(page1.content()).hasSize(1);
        }

        @Test
        void sortsByCreatedAtDescending() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            RunnerJob.RunnerJobPage page = runnerService.listJobs(runnerId, null, WORKSPACE_ID, 0, 10);
            assertThat(page.content()).hasSize(2);
            assertThat(page.content().get(0).createdAt())
                    .isAfterOrEqualTo(page.content().get(1).createdAt());
        }

        @Test
        void excludesOtherWorkspaces() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            String fakeJobId = "fake-job";
            RMap<String, String> fakeJob = redisClient.getMap("opik:job:" + fakeJobId, StringCodec.INSTANCE);
            fakeJob.putAll(Map.of(
                    "id", fakeJobId,
                    "runner_id", runnerId,
                    "agent_name", AGENT_NAME,
                    "status", "pending",
                    "workspace_id", OTHER_WORKSPACE_ID,
                    "created_at", Instant.now().toString()));
            RSet<String> runnerJobs = redisClient.getSet(
                    "opik:runner:" + runnerId + ":jobs", StringCodec.INSTANCE);
            runnerJobs.add(fakeJobId);

            RunnerJob.RunnerJobPage page = runnerService.listJobs(runnerId, null, WORKSPACE_ID, 0, 10);
            assertThat(page.content()).hasSize(1);
        }

        @Test
        void skipsExpiredJobHashes() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            RunnerJob job = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            redisClient.getMap("opik:job:" + job.id(), StringCodec.INSTANCE).delete();

            RunnerJob.RunnerJobPage page = runnerService.listJobs(runnerId, null, WORKSPACE_ID, 0, 10);
            assertThat(page.content()).isEmpty();
        }
    }

    // --- getJob tests ---

    @Nested
    class GetJob {

        @Test
        void returnsJob() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            RunnerJob created = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            RunnerJob fetched = runnerService.getJob(created.id(), WORKSPACE_ID);
            assertThat(fetched.id()).isEqualTo(created.id());
            assertThat(fetched.agentName()).isEqualTo(AGENT_NAME);
        }

        @Test
        void throwsNotFoundForMissing() {
            assertThatThrownBy(() -> runnerService.getJob("non-existent", WORKSPACE_ID))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsNotFoundForWrongWorkspace() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            RunnerJob created = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            assertThatThrownBy(() -> runnerService.getJob(created.id(), OTHER_WORKSPACE_ID))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    // --- getJobLogs tests ---

    @Nested
    class GetJobLogs {

        @Test
        void returnsAllLogs() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            RunnerJob job = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            List<LogEntry> entries = List.of(
                    LogEntry.builder().stream("stdout").text("line1").build(),
                    LogEntry.builder().stream("stderr").text("line2").build());
            runnerService.appendLogs(job.id(), WORKSPACE_ID, entries);

            List<LogEntry> logs = runnerService.getJobLogs(job.id(), 0, WORKSPACE_ID);
            assertThat(logs).hasSize(2);
            assertThat(logs.get(0).text()).isEqualTo("line1");
            assertThat(logs.get(1).text()).isEqualTo("line2");
        }

        @Test
        void returnsLogsFromOffset() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            RunnerJob job = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            List<LogEntry> entries = List.of(
                    LogEntry.builder().stream("stdout").text("line1").build(),
                    LogEntry.builder().stream("stdout").text("line2").build(),
                    LogEntry.builder().stream("stdout").text("line3").build());
            runnerService.appendLogs(job.id(), WORKSPACE_ID, entries);

            List<LogEntry> logs = runnerService.getJobLogs(job.id(), 2, WORKSPACE_ID);
            assertThat(logs).hasSize(1);
            assertThat(logs.get(0).text()).isEqualTo("line3");
        }

        @Test
        void returnsEmptyWhenOffsetBeyondEnd() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            RunnerJob job = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            runnerService.appendLogs(job.id(), WORKSPACE_ID,
                    List.of(LogEntry.builder().stream("stdout").text("line1").build()));

            List<LogEntry> logs = runnerService.getJobLogs(job.id(), 10, WORKSPACE_ID);
            assertThat(logs).isEmpty();
        }

        @Test
        void throwsNotFoundForMissingJob() {
            assertThatThrownBy(() -> runnerService.getJobLogs("non-existent", 0, WORKSPACE_ID))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsNotFoundForWrongWorkspace() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            RunnerJob job = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            assertThatThrownBy(() -> runnerService.getJobLogs(job.id(), 0, OTHER_WORKSPACE_ID))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    // --- appendLogs tests ---

    @Nested
    class AppendLogs {

        @Test
        void appendsEntriesToList() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            RunnerJob job = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            runnerService.appendLogs(job.id(), WORKSPACE_ID,
                    List.of(LogEntry.builder().stream("stdout").text("hello").build()));

            RList<String> logsList = redisClient.getList(
                    "opik:job:" + job.id() + ":logs", StringCodec.INSTANCE);
            assertThat(logsList.size()).isEqualTo(1);
        }

        @Test
        void appendsMultipleBatches() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            RunnerJob job = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            runnerService.appendLogs(job.id(), WORKSPACE_ID,
                    List.of(LogEntry.builder().stream("stdout").text("batch1").build()));
            runnerService.appendLogs(job.id(), WORKSPACE_ID,
                    List.of(LogEntry.builder().stream("stdout").text("batch2").build()));

            RList<String> logsList = redisClient.getList(
                    "opik:job:" + job.id() + ":logs", StringCodec.INSTANCE);
            assertThat(logsList.size()).isEqualTo(2);
        }

        @Test
        void throwsNotFoundForMissingJob() {
            assertThatThrownBy(() -> runnerService.appendLogs("non-existent", WORKSPACE_ID,
                    List.of(LogEntry.builder().stream("stdout").text("x").build())))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsNotFoundForWrongWorkspace() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            RunnerJob job = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            assertThatThrownBy(() -> runnerService.appendLogs(job.id(), OTHER_WORKSPACE_ID,
                    List.of(LogEntry.builder().stream("stdout").text("x").build())))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    // --- reportResult tests ---

    @Nested
    class ReportResult {

        @Test
        void completedJob() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            RunnerJob job = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();

            ObjectNode resultNode = MAPPER.createObjectNode();
            resultNode.put("output", "success");

            runnerService.reportResult(job.id(), WORKSPACE_ID,
                    JobResultRequest.builder().status("completed").result(resultNode).build());

            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:job:" + job.id(), StringCodec.INSTANCE);
            assertThat(jobMap.get("status")).isEqualTo("completed");
            assertThat(jobMap.get("completed_at")).isNotBlank();
            assertThat(jobMap.get("result")).contains("success");

            RList<String> active = redisClient.getList(
                    "opik:jobs:" + runnerId + ":active", StringCodec.INSTANCE);
            assertThat(active.readAll()).doesNotContain(job.id());
        }

        @Test
        void failedJob() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            RunnerJob job = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();

            runnerService.reportResult(job.id(), WORKSPACE_ID,
                    JobResultRequest.builder().status("failed").error("something broke").build());

            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:job:" + job.id(), StringCodec.INSTANCE);
            assertThat(jobMap.get("status")).isEqualTo("failed");
            assertThat(jobMap.get("error")).isEqualTo("something broke");
            assertThat(jobMap.get("completed_at")).isNotBlank();
        }

        @Test
        void setsTraceId() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            RunnerJob job = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();

            runnerService.reportResult(job.id(), WORKSPACE_ID,
                    JobResultRequest.builder().status("completed").traceId("trace-123").build());

            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:job:" + job.id(), StringCodec.INSTANCE);
            assertThat(jobMap.get("trace_id")).isEqualTo("trace-123");
        }

        @Test
        void setsTTLOnJobAndLogs() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            RunnerJob job = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();

            runnerService.appendLogs(job.id(), WORKSPACE_ID,
                    List.of(LogEntry.builder().stream("stdout").text("log").build()));

            runnerService.reportResult(job.id(), WORKSPACE_ID,
                    JobResultRequest.builder().status("completed").build());

            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:job:" + job.id(), StringCodec.INSTANCE);
            assertThat(jobMap.remainTimeToLive()).isPositive();

            RList<String> logsList = redisClient.getList(
                    "opik:job:" + job.id() + ":logs", StringCodec.INSTANCE);
            assertThat(logsList.remainTimeToLive()).isPositive();
        }

        @Test
        void throwsBadRequestForInvalidStatus() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            RunnerJob job = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();

            assertThatThrownBy(() -> runnerService.reportResult(job.id(), WORKSPACE_ID,
                    JobResultRequest.builder().status("running").build()))
                    .isInstanceOf(ClientErrorException.class)
                    .satisfies(e -> assertThat(((ClientErrorException) e).getResponse().getStatus()).isEqualTo(400));
        }

        @Test
        void throwsNotFoundForMissing() {
            assertThatThrownBy(() -> runnerService.reportResult("non-existent", WORKSPACE_ID,
                    JobResultRequest.builder().status("completed").build()))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsNotFoundForWrongWorkspace() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            RunnerJob job = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            assertThatThrownBy(() -> runnerService.reportResult(job.id(), OTHER_WORKSPACE_ID,
                    JobResultRequest.builder().status("completed").build()))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    // --- cancelJob tests ---

    @Nested
    class CancelJob {

        @Test
        void cancelActiveJob_addsToCancellationSet() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            RunnerJob job = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();

            runnerService.cancelJob(job.id(), WORKSPACE_ID);

            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:job:" + job.id(), StringCodec.INSTANCE);
            assertThat(jobMap.get("status")).isEqualTo("cancelled");
            assertThat(jobMap.get("completed_at")).isNotBlank();

            RSet<String> cancellations = redisClient.getSet(
                    "opik:runner:" + runnerId + ":cancellations", StringCodec.INSTANCE);
            assertThat(cancellations.contains(job.id())).isTrue();
        }

        @Test
        void cancelPendingJob_removesFromPendingQueue() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            RunnerJob job = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            runnerService.cancelJob(job.id(), WORKSPACE_ID);

            // Should be removed from pending
            RList<String> pending = redisClient.getList(
                    "opik:jobs:" + runnerId + ":pending", StringCodec.INSTANCE);
            assertThat(pending.readAll()).doesNotContain(job.id());

            // Should NOT be in cancellation set (pending jobs don't need runner notification)
            RSet<String> cancellations = redisClient.getSet(
                    "opik:runner:" + runnerId + ":cancellations", StringCodec.INSTANCE);
            assertThat(cancellations.contains(job.id())).isFalse();

            // Should have TTL set
            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:job:" + job.id(), StringCodec.INSTANCE);
            assertThat(jobMap.get("status")).isEqualTo("cancelled");
            assertThat(jobMap.remainTimeToLive()).isPositive();
        }

        @Test
        void throwsNotFoundForMissing() {
            assertThatThrownBy(() -> runnerService.cancelJob("non-existent", WORKSPACE_ID))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void throwsNotFoundForWrongWorkspace() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            RunnerJob job = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            assertThatThrownBy(() -> runnerService.cancelJob(job.id(), OTHER_WORKSPACE_ID))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    // --- reapDeadRunners tests ---

    @Nested
    class ReapDeadRunners {

        @Test
        void failsOrphanedActiveJobs() throws InterruptedException {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            RunnerJob job = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();

            waitForHeartbeatExpiry();
            runnerService.reapDeadRunners();

            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:job:" + job.id(), StringCodec.INSTANCE);
            assertThat(jobMap.get("status")).isEqualTo("failed");
            assertThat(jobMap.get("error")).isEqualTo("Runner disconnected");
        }

        @Test
        void failsOrphanedPendingJobs() throws InterruptedException {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            RunnerJob job = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            waitForHeartbeatExpiry();
            runnerService.reapDeadRunners();

            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:job:" + job.id(), StringCodec.INSTANCE);
            assertThat(jobMap.get("status")).isEqualTo("failed");
            assertThat(jobMap.get("error")).isEqualTo("Runner disconnected");
        }

        @Test
        void purgesLongDeadRunners() throws InterruptedException {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            waitForHeartbeatExpiry();
            runnerService.reapDeadRunners();

            RMap<String, String> runnerMap = redisClient.getMap(
                    "opik:runner:" + runnerId, StringCodec.INSTANCE);
            assertThat(runnerMap.isExists()).isFalse();

            RBucket<String> hb = redisClient.getBucket(
                    "opik:runner:" + runnerId + ":heartbeat", StringCodec.INSTANCE);
            assertThat(hb.isExists()).isFalse();

            RSet<String> wsRunners = redisClient.getSet(
                    "opik:workspace:" + WORKSPACE_ID + ":runners", StringCodec.INSTANCE);
            assertThat(wsRunners.contains(runnerId)).isFalse();
        }

        @Test
        void skipsAliveRunners() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            runnerService.reapDeadRunners();

            RMap<String, String> runnerMap = redisClient.getMap(
                    "opik:runner:" + runnerId, StringCodec.INSTANCE);
            assertThat(runnerMap.isExists()).isTrue();
        }

        @Test
        void removesEmptyWorkspace() throws InterruptedException {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            waitForHeartbeatExpiry();
            runnerService.reapDeadRunners();

            RSet<String> workspaces = redisClient.getSet(
                    "opik:workspaces:with_runners", StringCodec.INSTANCE);
            assertThat(workspaces.contains(WORKSPACE_ID)).isFalse();
        }

        @Test
        void handlesErrorPerRunner() throws InterruptedException {
            String runner1 = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            String runner2 = pairAndConnect(WORKSPACE_ID, "user2", "runner2");

            waitForHeartbeatExpiry();
            runnerService.reapDeadRunners();

            RMap<String, String> map1 = redisClient.getMap(
                    "opik:runner:" + runner1, StringCodec.INSTANCE);
            RMap<String, String> map2 = redisClient.getMap(
                    "opik:runner:" + runner2, StringCodec.INSTANCE);
            assertThat(map1.isExists()).isFalse();
            assertThat(map2.isExists()).isFalse();
        }

        @Test
        void cleansRunnerJobsSetOnReap() throws InterruptedException {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            waitForHeartbeatExpiry();
            runnerService.reapDeadRunners();

            RSet<String> runnerJobs = redisClient.getSet(
                    "opik:runner:" + runnerId + ":jobs", StringCodec.INSTANCE);
            assertThat(runnerJobs.isExists()).isFalse();
        }

        @Test
        void recordsDisconnectedAtOnFirstReap() throws InterruptedException {
            int originalPurgeHours = runnerConfig.getDeadRunnerPurgeHours();
            runnerConfig.setDeadRunnerPurgeHours(999);
            try {
                String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

                waitForHeartbeatExpiry();
                runnerService.reapDeadRunners();

                RMap<String, String> runnerMap = redisClient.getMap(
                        "opik:runner:" + runnerId, StringCodec.INSTANCE);
                assertThat(runnerMap.get("disconnected_at")).isNotBlank();
            } finally {
                runnerConfig.setDeadRunnerPurgeHours(originalPurgeHours);
            }
        }
    }

    // --- reapStuckJobs tests ---

    @Nested
    class ReapStuckJobs {

        @Test
        void failsJobExceedingPerJobTimeout() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            ObjectNode meta = MAPPER.createObjectNode();
            meta.put("timeout", 60);
            runnerService.registerAgents(runnerId, WORKSPACE_ID, Map.of(AGENT_NAME, meta));

            RunnerJob job = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();

            // Backdate started_at to exceed the 60s timeout
            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:job:" + job.id(), StringCodec.INSTANCE);
            jobMap.put("started_at", Instant.now().minus(Duration.ofSeconds(120)).toString());

            runnerService.reapDeadRunners();

            assertThat(jobMap.get("status")).isEqualTo("failed");
            assertThat(jobMap.get("error")).contains("timed out");
            assertThat(jobMap.get("error")).contains("60s");
        }

        @Test
        void skipsJobWithinTimeout() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            RunnerJob job = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();

            // started_at is "now", default timeout is 1800s — should not be reaped
            runnerService.reapDeadRunners();

            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:job:" + job.id(), StringCodec.INSTANCE);
            assertThat(jobMap.get("status")).isEqualTo("running");
        }

        @Test
        void usesConfigDefaultWhenJobHasNoTimeout() {
            int originalTimeout = runnerConfig.getJobTimeoutSeconds();
            runnerConfig.setJobTimeoutSeconds(10);
            try {
                String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
                RunnerJob job = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
                runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();

                // Remove the timeout field from the job hash to simulate no per-job timeout
                RMap<String, String> jobMap = redisClient.getMap(
                        "opik:job:" + job.id(), StringCodec.INSTANCE);
                jobMap.remove("timeout");

                // Backdate to exceed the 10s config default
                jobMap.put("started_at", Instant.now().minus(Duration.ofSeconds(30)).toString());

                runnerService.reapDeadRunners();

                assertThat(jobMap.get("status")).isEqualTo("failed");
                assertThat(jobMap.get("error")).contains("10s");
            } finally {
                runnerConfig.setJobTimeoutSeconds(originalTimeout);
            }
        }

        @Test
        void removesReapedJobFromActiveList() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            ObjectNode meta = MAPPER.createObjectNode();
            meta.put("timeout", 5);
            runnerService.registerAgents(runnerId, WORKSPACE_ID, Map.of(AGENT_NAME, meta));

            RunnerJob job = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();

            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:job:" + job.id(), StringCodec.INSTANCE);
            jobMap.put("started_at", Instant.now().minus(Duration.ofSeconds(30)).toString());

            runnerService.reapDeadRunners();

            RList<String> activeJobs = redisClient.getList(
                    "opik:jobs:" + runnerId + ":active", StringCodec.INSTANCE);
            assertThat(activeJobs.readAll()).doesNotContain(job.id());
        }

        @Test
        void reapsStuckJobsOnAliveRunners() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            ObjectNode meta = MAPPER.createObjectNode();
            meta.put("timeout", 5);
            runnerService.registerAgents(runnerId, WORKSPACE_ID, Map.of(AGENT_NAME, meta));

            RunnerJob job = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();

            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:job:" + job.id(), StringCodec.INSTANCE);
            jobMap.put("started_at", Instant.now().minus(Duration.ofSeconds(30)).toString());

            // Runner is alive (heartbeat not expired), but job is stuck
            runnerService.reapDeadRunners();

            assertThat(jobMap.get("status")).isEqualTo("failed");
            assertThat(jobMap.get("error")).contains("timed out");
        }

        @Test
        void doesNotReapAlreadyCompletedJob() {
            String runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            ObjectNode meta = MAPPER.createObjectNode();
            meta.put("timeout", 5);
            runnerService.registerAgents(runnerId, WORKSPACE_ID, Map.of(AGENT_NAME, meta));

            RunnerJob job = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();

            // Report result before reaping
            runnerService.reportResult(job.id(), WORKSPACE_ID,
                    JobResultRequest.builder().status("completed").build());

            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:job:" + job.id(), StringCodec.INSTANCE);
            jobMap.put("started_at", Instant.now().minus(Duration.ofSeconds(30)).toString());

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
        ConnectResponse connectResp = runnerService.connect(WORKSPACE_ID, USER_NAME, connectReq);
        String runnerId = connectResp.runnerId();
        assertThat(runnerId).isEqualTo(pair.runnerId());

        ObjectNode agentMeta = MAPPER.createObjectNode();
        agentMeta.put("project", "my-project");
        runnerService.registerAgents(runnerId, WORKSPACE_ID, Map.of(AGENT_NAME, agentMeta));

        List<Runner> runners = runnerService.listRunners(WORKSPACE_ID);
        assertThat(runners).hasSize(1);
        assertThat(runners.get(0).agents()).hasSize(1);

        stubNextId();
        ObjectNode inputs = MAPPER.createObjectNode();
        inputs.put("prompt", "hello");
        CreateJobRequest jobReq = CreateJobRequest.builder()
                .agentName(AGENT_NAME)
                .project("my-project")
                .inputs(inputs)
                .build();
        RunnerJob created = runnerService.createJob(WORKSPACE_ID, USER_NAME, jobReq);
        assertThat(created.status()).isEqualTo("pending");

        RunnerJob claimed = runnerService.nextJob(runnerId, WORKSPACE_ID).toCompletableFuture().join();
        assertThat(claimed).isNotNull();
        assertThat(claimed.id()).isEqualTo(created.id());
        assertThat(claimed.status()).isEqualTo("running");

        HeartbeatResponse hbResp = runnerService.heartbeat(runnerId, WORKSPACE_ID);
        assertThat(hbResp.cancelledJobIds()).isEmpty();

        runnerService.appendLogs(claimed.id(), WORKSPACE_ID,
                List.of(LogEntry.builder().stream("stdout").text("Processing...").build()));

        ObjectNode resultNode = MAPPER.createObjectNode();
        resultNode.put("answer", "world");
        runnerService.reportResult(claimed.id(), WORKSPACE_ID,
                JobResultRequest.builder()
                        .status("completed")
                        .result(resultNode)
                        .traceId("trace-abc")
                        .build());

        RunnerJob finalJob = runnerService.getJob(claimed.id(), WORKSPACE_ID);
        assertThat(finalJob.status()).isEqualTo("completed");
        assertThat(finalJob.traceId()).isEqualTo("trace-abc");
        assertThat(finalJob.result().get("answer").asText()).isEqualTo("world");

        List<LogEntry> logs = runnerService.getJobLogs(claimed.id(), 0, WORKSPACE_ID);
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).text()).isEqualTo("Processing...");
    }
}
