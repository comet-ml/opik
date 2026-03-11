package com.comet.opik.domain;

import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.runner.CreateLocalRunnerJobRequest;
import com.comet.opik.api.runner.LocalRunner;
import com.comet.opik.api.runner.LocalRunnerConnectRequest;
import com.comet.opik.api.runner.LocalRunnerHeartbeatResponse;
import com.comet.opik.api.runner.LocalRunnerJob;
import com.comet.opik.api.runner.LocalRunnerJobResultRequest;
import com.comet.opik.api.runner.LocalRunnerJobStatus;
import com.comet.opik.api.runner.LocalRunnerLogEntry;
import com.comet.opik.api.runner.LocalRunnerPairResponse;
import com.comet.opik.infrastructure.LocalRunnerConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.util.Duration;
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
import org.redisson.api.RScoredSortedSet;
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
class LocalRunnerServiceImplTest {

    private static final String WORKSPACE_ID = "test-workspace";
    private static final String OTHER_WORKSPACE_ID = "other-workspace";
    private static final String USER_NAME = "test-user";
    private static final String RUNNER_NAME = "my-runner";
    private static final String AGENT_NAME = "test-agent";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
    private RedissonClient redisClient;
    private LocalRunnerConfig runnerConfig;
    private IdGenerator idGenerator;
    private LocalRunnerServiceImpl runnerService;

    private int uuidCounter = 0;

    @BeforeAll
    void setUp() {
        redis.start();

        Config config = new Config();
        config.useSingleServer()
                .setAddress(redis.getRedisURI())
                .setDatabase(0);

        redisClient = Redisson.create(config);

        runnerConfig = new LocalRunnerConfig();
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

        runnerService = new LocalRunnerServiceImpl(redisClient, runnerConfig, idGenerator);
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
        LocalRunnerPairResponse pair = runnerService.generatePairingCode(workspaceId, userName);
        LocalRunnerConnectRequest req = LocalRunnerConnectRequest.builder()
                .pairingCode(pair.pairingCode())
                .runnerName(runnerName)
                .build();
        return runnerService.connect(workspaceId, userName, req);
    }

    private UUID connectViaApiKey(String workspaceId, String userName, String runnerName) {
        stubNextId();
        LocalRunnerConnectRequest req = LocalRunnerConnectRequest.builder()
                .runnerName(runnerName)
                .build();
        return runnerService.connect(workspaceId, userName, req);
    }

    private UUID createTestJob(String workspaceId, String userName, String agentName) {
        stubNextId();
        CreateLocalRunnerJobRequest req = CreateLocalRunnerJobRequest.builder()
                .agentName(agentName)
                .build();
        return runnerService.createJob(workspaceId, userName, req);
    }

    private void waitForHeartbeatExpiry() throws InterruptedException {
        Thread.sleep((runnerConfig.getHeartbeatTtl().toSeconds() + 1) * 1000L);
    }

    @Nested
    class GeneratePairingCode {

        @Test
        void createsPairKeyInRedis() {
            stubNextId();
            LocalRunnerPairResponse resp = runnerService.generatePairingCode(WORKSPACE_ID, USER_NAME);

            RBucket<String> pairBucket = redisClient.getBucket(
                    "opik:runners:pair:" + resp.pairingCode(), StringCodec.INSTANCE);
            assertThat(pairBucket.isExists()).isTrue();
            assertThat(pairBucket.get()).isEqualTo(resp.runnerId() + ":" + WORKSPACE_ID);
            assertThat(pairBucket.remainTimeToLive()).isPositive();
        }

        @Test
        void createsRunnerHash() {
            stubNextId();
            LocalRunnerPairResponse resp = runnerService.generatePairingCode(WORKSPACE_ID, USER_NAME);

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
            LocalRunnerPairResponse resp = runnerService.generatePairingCode(WORKSPACE_ID, USER_NAME);

            RScoredSortedSet<String> wsRunners = redisClient.getScoredSortedSet(
                    "opik:runners:workspace:" + WORKSPACE_ID + ":runners", StringCodec.INSTANCE);
            assertThat(wsRunners.contains(resp.runnerId().toString())).isTrue();

            RSet<String> workspaces = redisClient.getSet(
                    "opik:runners:workspaces:with_runners", StringCodec.INSTANCE);
            assertThat(workspaces.contains(WORKSPACE_ID)).isTrue();
        }

        @Test
        void setsUserRunnerMapping() {
            stubNextId();
            LocalRunnerPairResponse resp = runnerService.generatePairingCode(WORKSPACE_ID, USER_NAME);

            RBucket<String> userRunner = redisClient.getBucket(
                    "opik:runners:workspace:" + WORKSPACE_ID + ":user:" + USER_NAME + ":runner",
                    StringCodec.INSTANCE);
            assertThat(userRunner.get()).isEqualTo(resp.runnerId().toString());
        }
    }

    @Nested
    class Connect {

        @Test
        void withPairingCode_claimsPairAndReturnsCredentials() {
            stubNextId();
            LocalRunnerPairResponse pair = runnerService.generatePairingCode(WORKSPACE_ID, USER_NAME);

            LocalRunnerConnectRequest req = LocalRunnerConnectRequest.builder()
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
            LocalRunnerPairResponse pair = runnerService.generatePairingCode(WORKSPACE_ID, USER_NAME);

            LocalRunnerConnectRequest req = LocalRunnerConnectRequest.builder()
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
            LocalRunnerPairResponse pair = runnerService.generatePairingCode(WORKSPACE_ID, USER_NAME);

            LocalRunnerConnectRequest req = LocalRunnerConnectRequest.builder()
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

    @Nested
    class Heartbeat {

        @Test
        void refreshesHeartbeatTTL() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            LocalRunnerHeartbeatResponse resp = runnerService.heartbeat(runnerId, WORKSPACE_ID, USER_NAME);
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
            LocalRunnerJob claimed = runnerService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).toCompletableFuture()
                    .join();
            assertThat(claimed).isNotNull();

            runnerService.heartbeat(runnerId, WORKSPACE_ID, USER_NAME);

            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:runners:job:" + claimed.id(), StringCodec.INSTANCE);
            assertThat(jobMap.get("last_heartbeat")).isNotBlank();
        }
    }

    @Nested
    class CreateJob {

        @Test
        void createsJobAndEnqueues() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            stubNextId();
            CreateLocalRunnerJobRequest req = CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME)
                    .project("my-project")
                    .build();
            UUID jobId = runnerService.createJob(WORKSPACE_ID, USER_NAME, req);

            LocalRunnerJob job = runnerService.getJob(jobId, WORKSPACE_ID, USER_NAME);
            assertThat(job.id()).isEqualTo(jobId);
            assertThat(job.runnerId()).isEqualTo(runnerId);
            assertThat(job.agentName()).isEqualTo(AGENT_NAME);
            assertThat(job.status().getValue()).isEqualTo("pending");
            assertThat(job.project()).isEqualTo("my-project");

            RList<String> pending = redisClient.getList(
                    "opik:runners:jobs:" + runnerId + ":pending", StringCodec.INSTANCE);
            assertThat(pending.readAll()).contains(jobId.toString());

            RScoredSortedSet<String> runnerJobs = redisClient.getScoredSortedSet(
                    "opik:runners:runner:" + runnerId + ":jobs", StringCodec.INSTANCE);
            assertThat(runnerJobs.contains(jobId.toString())).isTrue();
        }
    }

    @Nested
    class NextJob {

        @Test
        void removesFromPendingAddsToActive() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            runnerService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).toCompletableFuture().join();

            RList<String> pending = redisClient.getList(
                    "opik:runners:jobs:" + runnerId + ":pending", StringCodec.INSTANCE);
            assertThat(pending.size()).isZero();

            RList<String> active = redisClient.getList(
                    "opik:runners:jobs:" + runnerId + ":active", StringCodec.INSTANCE);
            assertThat(active.readAll()).contains(jobId.toString());
        }
    }

    @Nested
    class ListJobs {

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
            RScoredSortedSet<String> runnerJobs = redisClient.getScoredSortedSet(
                    "opik:runners:runner:" + runnerId + ":jobs", StringCodec.INSTANCE);
            runnerJobs.add(Instant.now().toEpochMilli(), fakeJobId);

            LocalRunnerJob.LocalRunnerJobPage page = runnerService.listJobs(runnerId, null, WORKSPACE_ID, USER_NAME, 0,
                    10);
            assertThat(page.content()).hasSize(1);
        }

        @Test
        void skipsExpiredJobHashes() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            redisClient.getMap("opik:runners:job:" + jobId, StringCodec.INSTANCE).delete();

            LocalRunnerJob.LocalRunnerJobPage page = runnerService.listJobs(runnerId, null, WORKSPACE_ID, USER_NAME, 0,
                    10);
            assertThat(page.content()).isEmpty();
        }
    }

    @Nested
    class AppendLogs {

        @Test
        void appendsEntriesToList() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            runnerService.appendLogs(jobId, WORKSPACE_ID, USER_NAME,
                    List.of(LocalRunnerLogEntry.builder().stream("stdout").text("hello").build()));

            RList<String> logsList = redisClient.getList(
                    "opik:runners:job:" + jobId + ":logs", StringCodec.INSTANCE);
            assertThat(logsList.size()).isEqualTo(1);
        }

        @Test
        void appendsMultipleBatches() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            runnerService.appendLogs(jobId, WORKSPACE_ID, USER_NAME,
                    List.of(LocalRunnerLogEntry.builder().stream("stdout").text("batch1").build()));
            runnerService.appendLogs(jobId, WORKSPACE_ID, USER_NAME,
                    List.of(LocalRunnerLogEntry.builder().stream("stdout").text("batch2").build()));

            RList<String> logsList = redisClient.getList(
                    "opik:runners:job:" + jobId + ":logs", StringCodec.INSTANCE);
            assertThat(logsList.size()).isEqualTo(2);
        }
    }

    @Nested
    class ReportResult {

        @Test
        void completedJob() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).toCompletableFuture().join();

            ObjectNode resultNode = MAPPER.createObjectNode();
            resultNode.put("output", "success");

            runnerService.reportResult(jobId, WORKSPACE_ID, USER_NAME,
                    LocalRunnerJobResultRequest.builder().status(LocalRunnerJobStatus.COMPLETED).result(resultNode)
                            .build());

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
            runnerService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).toCompletableFuture().join();

            runnerService.reportResult(jobId, WORKSPACE_ID, USER_NAME,
                    LocalRunnerJobResultRequest.builder().status(LocalRunnerJobStatus.FAILED).error("something broke")
                            .build());

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
            runnerService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).toCompletableFuture().join();

            UUID traceId = UUID.randomUUID();
            runnerService.reportResult(jobId, WORKSPACE_ID, USER_NAME,
                    LocalRunnerJobResultRequest.builder().status(LocalRunnerJobStatus.COMPLETED).traceId(traceId)
                            .build());

            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:runners:job:" + jobId, StringCodec.INSTANCE);
            assertThat(jobMap.get("trace_id")).isEqualTo(traceId.toString());
        }

        @Test
        void setsTTLOnJobAndLogs() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).toCompletableFuture().join();

            runnerService.appendLogs(jobId, WORKSPACE_ID, USER_NAME,
                    List.of(LocalRunnerLogEntry.builder().stream("stdout").text("log").build()));

            runnerService.reportResult(jobId, WORKSPACE_ID, USER_NAME,
                    LocalRunnerJobResultRequest.builder().status(LocalRunnerJobStatus.COMPLETED).build());

            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:runners:job:" + jobId, StringCodec.INSTANCE);
            assertThat(jobMap.remainTimeToLive()).isPositive();

            RList<String> logsList = redisClient.getList(
                    "opik:runners:job:" + jobId + ":logs", StringCodec.INSTANCE);
            assertThat(logsList.remainTimeToLive()).isPositive();
        }
    }

    @Nested
    class CancelJob {

        @Test
        void cancelActiveJob_addsToCancellationSet() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).toCompletableFuture().join();

            runnerService.cancelJob(jobId, WORKSPACE_ID, USER_NAME);

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

            runnerService.cancelJob(jobId, WORKSPACE_ID, USER_NAME);

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
    }

    @Test
    void fullFlow_pairConnectCreateJobNextJobReportResult() {
        stubNextId();
        LocalRunnerPairResponse pair = runnerService.generatePairingCode(WORKSPACE_ID, USER_NAME);
        assertThat(pair.pairingCode()).hasSize(6);

        LocalRunnerConnectRequest connectReq = LocalRunnerConnectRequest.builder()
                .pairingCode(pair.pairingCode())
                .runnerName(RUNNER_NAME)
                .build();
        UUID runnerId = runnerService.connect(WORKSPACE_ID, USER_NAME, connectReq);
        assertThat(runnerId).isEqualTo(pair.runnerId());

        LocalRunner.Agent agentMeta = LocalRunner.Agent.builder().project("my-project").build();
        runnerService.registerAgents(runnerId, WORKSPACE_ID, USER_NAME, Map.of(AGENT_NAME, agentMeta));

        LocalRunner.LocalRunnerPage runnerPage = runnerService.listRunners(WORKSPACE_ID, USER_NAME, 0, 25);
        assertThat(runnerPage.content()).hasSize(1);
        assertThat(runnerPage.content().get(0).agents()).hasSize(1);

        stubNextId();
        ObjectNode inputs = MAPPER.createObjectNode();
        inputs.put("prompt", "hello");
        CreateLocalRunnerJobRequest jobReq = CreateLocalRunnerJobRequest.builder()
                .agentName(AGENT_NAME)
                .project("my-project")
                .inputs(inputs)
                .build();
        UUID jobId = runnerService.createJob(WORKSPACE_ID, USER_NAME, jobReq);

        LocalRunnerJob created = runnerService.getJob(jobId, WORKSPACE_ID, USER_NAME);
        assertThat(created.status().getValue()).isEqualTo("pending");

        LocalRunnerJob claimed = runnerService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).toCompletableFuture().join();
        assertThat(claimed).isNotNull();
        assertThat(claimed.id()).isEqualTo(jobId);
        assertThat(claimed.status().getValue()).isEqualTo("running");

        LocalRunnerHeartbeatResponse hbResp = runnerService.heartbeat(runnerId, WORKSPACE_ID, USER_NAME);
        assertThat(hbResp.cancelledJobIds()).isEmpty();

        runnerService.appendLogs(claimed.id(), WORKSPACE_ID, USER_NAME,
                List.of(LocalRunnerLogEntry.builder().stream("stdout").text("Processing...").build()));

        UUID traceId = UUID.randomUUID();
        ObjectNode resultNode = MAPPER.createObjectNode();
        resultNode.put("answer", "world");
        runnerService.reportResult(claimed.id(), WORKSPACE_ID, USER_NAME,
                LocalRunnerJobResultRequest.builder()
                        .status(LocalRunnerJobStatus.COMPLETED)
                        .result(resultNode)
                        .traceId(traceId)
                        .build());

        LocalRunnerJob finalJob = runnerService.getJob(claimed.id(), WORKSPACE_ID, USER_NAME);
        assertThat(finalJob.status().getValue()).isEqualTo("completed");
        assertThat(finalJob.traceId()).isEqualTo(traceId);
        assertThat(finalJob.result().get("answer").asText()).isEqualTo("world");

        List<LocalRunnerLogEntry> logs = runnerService.getJobLogs(claimed.id(), 0, WORKSPACE_ID, USER_NAME);
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).text()).isEqualTo("Processing...");
    }

    @Nested
    class CrossUserIsolation {

        private static final String OTHER_USER = "other-user";

        @Test
        void listRunners_excludesOtherUsersRunners() {
            connectViaApiKey(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            LocalRunner.LocalRunnerPage page = runnerService.listRunners(WORKSPACE_ID, OTHER_USER, 0, 25);
            assertThat(page.content()).isEmpty();
            assertThat(page.total()).isZero();
        }

        @Test
        void getRunner_rejectsOtherUser() {
            UUID runnerId = connectViaApiKey(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            assertThatThrownBy(() -> runnerService.getRunner(WORKSPACE_ID, OTHER_USER, runnerId))
                    .isExactlyInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Runner not found");
        }

        @Test
        void registerAgents_rejectsOtherUser() {
            UUID runnerId = connectViaApiKey(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            assertThatThrownBy(() -> runnerService.registerAgents(runnerId, WORKSPACE_ID, OTHER_USER,
                    Map.of(AGENT_NAME, LocalRunner.Agent.builder().build())))
                    .isExactlyInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Runner not found");
        }

        @Test
        void heartbeat_rejectsOtherUser() {
            UUID runnerId = connectViaApiKey(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            assertThatThrownBy(() -> runnerService.heartbeat(runnerId, WORKSPACE_ID, OTHER_USER))
                    .isExactlyInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Runner not found");
        }

        @Test
        void createJob_rejectsOtherUsersRunner() {
            UUID runnerId = connectViaApiKey(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            CreateLocalRunnerJobRequest req = CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME)
                    .runnerId(runnerId)
                    .build();

            assertThatThrownBy(() -> runnerService.createJob(WORKSPACE_ID, OTHER_USER, req))
                    .isExactlyInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Runner not found");
        }

        @Test
        void nextJob_rejectsOtherUser() {
            UUID runnerId = connectViaApiKey(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            assertThatThrownBy(() -> runnerService.nextJob(runnerId, WORKSPACE_ID, OTHER_USER))
                    .isExactlyInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Runner not found");
        }

        @Test
        void listJobs_rejectsOtherUser() {
            UUID runnerId = connectViaApiKey(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            assertThatThrownBy(() -> runnerService.listJobs(runnerId, null, WORKSPACE_ID, OTHER_USER, 0, 10))
                    .isExactlyInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Runner not found");
        }

        @Test
        void getJob_rejectsOtherUser() {
            connectViaApiKey(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            assertThatThrownBy(() -> runnerService.getJob(jobId, WORKSPACE_ID, OTHER_USER))
                    .isExactlyInstanceOf(NotFoundException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        void getJobLogs_rejectsOtherUser() {
            connectViaApiKey(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            assertThatThrownBy(() -> runnerService.getJobLogs(jobId, 0, WORKSPACE_ID, OTHER_USER))
                    .isExactlyInstanceOf(NotFoundException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        void appendLogs_rejectsOtherUser() {
            connectViaApiKey(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            assertThatThrownBy(() -> runnerService.appendLogs(jobId, WORKSPACE_ID, OTHER_USER,
                    List.of(LocalRunnerLogEntry.builder().stream("stdout").text("hack").build())))
                    .isExactlyInstanceOf(NotFoundException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        void reportResult_rejectsOtherUser() {
            UUID runnerId = connectViaApiKey(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).toCompletableFuture().join();

            assertThatThrownBy(() -> runnerService.reportResult(jobId, WORKSPACE_ID, OTHER_USER,
                    LocalRunnerJobResultRequest.builder().status(LocalRunnerJobStatus.COMPLETED).build()))
                    .isExactlyInstanceOf(NotFoundException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        void cancelJob_rejectsOtherUser() {
            connectViaApiKey(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            assertThatThrownBy(() -> runnerService.cancelJob(jobId, WORKSPACE_ID, OTHER_USER))
                    .isExactlyInstanceOf(NotFoundException.class)
                    .hasMessageContaining("not found");
        }
    }
}
