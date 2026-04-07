package com.comet.opik.domain;

import com.comet.opik.api.Project;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.runner.BridgeCommand;
import com.comet.opik.api.runner.BridgeCommandBatchResponse;
import com.comet.opik.api.runner.BridgeCommandResultRequest;
import com.comet.opik.api.runner.BridgeCommandStatus;
import com.comet.opik.api.runner.BridgeCommandSubmitRequest;
import com.comet.opik.api.runner.BridgeCommandType;
import com.comet.opik.api.runner.CreateLocalRunnerJobRequest;
import com.comet.opik.api.runner.LocalRunner;
import com.comet.opik.api.runner.LocalRunnerConnectRequest;
import com.comet.opik.api.runner.LocalRunnerConnectResponse;
import com.comet.opik.api.runner.LocalRunnerHeartbeatResponse;
import com.comet.opik.api.runner.LocalRunnerJob;
import com.comet.opik.api.runner.LocalRunnerJobResultRequest;
import com.comet.opik.api.runner.LocalRunnerJobStatus;
import com.comet.opik.api.runner.LocalRunnerLogEntry;
import com.comet.opik.api.runner.LocalRunnerPairResponse;
import com.comet.opik.infrastructure.LocalRunnerConfig;
import com.comet.opik.infrastructure.redis.StringRedisClient;
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
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalRunnerServiceImplTest {

    private static final String WORKSPACE_ID = "00000000-0000-0000-0000-000000000001";
    private static final String OTHER_WORKSPACE_ID = "00000000-0000-0000-0000-000000000002";
    private static final String USER_NAME = "test-user";
    private static final String RUNNER_NAME = "my-runner";
    private static final String AGENT_NAME = "test-agent";
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final String PROJECT_NAME = "test-project";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
    private RedissonClient redisClient;
    private StringRedisClient stringRedis;
    private LocalRunnerConfig runnerConfig;
    private IdGenerator idGenerator;
    private ProjectService projectService;
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
        stringRedis = new StringRedisClient(redisClient);

        runnerConfig = new LocalRunnerConfig();
        runnerConfig.setEnabled(true);
        runnerConfig.setHeartbeatTtl(Duration.seconds(2));
        runnerConfig.setNextJobPollTimeout(Duration.seconds(1));
        runnerConfig.setMaxPendingJobsPerRunner(3);
        runnerConfig.setDeadRunnerPurgeTime(Duration.hours(24));
        runnerConfig.setCompletedJobTtl(Duration.days(7));
        runnerConfig.setJobTimeout(Duration.seconds(1800));
        runnerConfig.setReaperLockDuration(Duration.seconds(55));
        runnerConfig.setReaperLockWait(Duration.seconds(5));

        idGenerator = Mockito.mock(IdGenerator.class);
        projectService = Mockito.mock(ProjectService.class);

        when(projectService.get(eq(PROJECT_ID), any())).thenReturn(
                Project.builder().id(PROJECT_ID).name(PROJECT_NAME).build());

        runnerService = new LocalRunnerServiceImpl(stringRedis, redisClient.reactive(), runnerConfig, idGenerator,
                projectService);
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
        LocalRunnerPairResponse pair = runnerService.generatePairingCode(workspaceId, userName, PROJECT_ID);
        LocalRunnerConnectRequest req = LocalRunnerConnectRequest.builder()
                .pairingCode(pair.pairingCode())
                .runnerName(runnerName)
                .build();
        LocalRunnerConnectResponse resp = runnerService.connect(workspaceId, userName, req);
        LocalRunner.Agent agent = LocalRunner.Agent.builder()
                .name(AGENT_NAME)
                .build();
        runnerService.registerAgents(resp.runnerId(), workspaceId, userName, Map.of(AGENT_NAME, agent));
        return resp.runnerId();
    }

    private UUID createTestJob(String workspaceId, String userName, String agentName) {
        stubNextId();
        CreateLocalRunnerJobRequest req = CreateLocalRunnerJobRequest.builder()
                .agentName(agentName)
                .projectId(PROJECT_ID)
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
            LocalRunnerPairResponse resp = runnerService.generatePairingCode(WORKSPACE_ID, USER_NAME, PROJECT_ID);

            RBucket<String> pairBucket = stringRedis.getBucket(
                    "opik:runners:pair:" + resp.pairingCode());
            assertThat(pairBucket.isExists()).isTrue();
            String value = pairBucket.get();
            assertThat(value).contains(resp.runnerId().toString());
            assertThat(value).contains(WORKSPACE_ID);
            assertThat(value).contains(PROJECT_ID.toString());
            assertThat(pairBucket.remainTimeToLive()).isPositive();
        }

        @Test
        void createsRunnerHash() {
            stubNextId();
            LocalRunnerPairResponse resp = runnerService.generatePairingCode(WORKSPACE_ID, USER_NAME, PROJECT_ID);

            RMap<String, String> runnerMap = stringRedis.getMap(
                    "opik:runners:runner:" + resp.runnerId());
            assertThat(runnerMap.get("status")).isEqualTo("pairing");
            assertThat(runnerMap.get("workspace_id")).isEqualTo(WORKSPACE_ID);
            assertThat(runnerMap.get("user_name")).isEqualTo(USER_NAME);
            assertThat(runnerMap.get("project_id")).isEqualTo(PROJECT_ID.toString());
            assertThat(runnerMap.remainTimeToLive()).isPositive();
        }

        @Test
        void addsToWorkspaceSets() {
            stubNextId();
            LocalRunnerPairResponse resp = runnerService.generatePairingCode(WORKSPACE_ID, USER_NAME, PROJECT_ID);

            RScoredSortedSet<String> wsRunners = stringRedis.getScoredSortedSet(
                    "opik:runners:workspace:" + WORKSPACE_ID + ":runners");
            assertThat(wsRunners.contains(resp.runnerId().toString())).isTrue();

            RSet<String> workspaces = stringRedis.getSet(
                    "opik:runners:workspaces:with_runners");
            assertThat(workspaces.contains(WORKSPACE_ID)).isTrue();
        }

        @Test
        void doesNotSetUserRunnerMappingUntilConnect() {
            stubNextId();
            runnerService.generatePairingCode(WORKSPACE_ID, USER_NAME, PROJECT_ID);

            RBucket<String> userRunner = stringRedis.getBucket(
                    "opik:runners:workspace:" + WORKSPACE_ID + ":project:" + PROJECT_ID + ":user:" + USER_NAME
                            + ":runner");
            assertThat(userRunner.isExists()).isFalse();
        }

        @Test
        void addsToProjectRunnersSet() {
            stubNextId();
            LocalRunnerPairResponse resp = runnerService.generatePairingCode(WORKSPACE_ID, USER_NAME, PROJECT_ID);

            RSet<String> projectRunners = stringRedis.getSet(
                    "opik:runners:workspace:" + WORKSPACE_ID + ":project:" + PROJECT_ID + ":runners");
            assertThat(projectRunners.contains(resp.runnerId().toString())).isTrue();
        }
    }

    @Nested
    class Connect {

        @Test
        void withPairingCode_claimsPairAndReturnsCredentials() {
            stubNextId();
            LocalRunnerPairResponse pair = runnerService.generatePairingCode(WORKSPACE_ID, USER_NAME, PROJECT_ID);

            LocalRunnerConnectRequest req = LocalRunnerConnectRequest.builder()
                    .pairingCode(pair.pairingCode())
                    .runnerName(RUNNER_NAME)
                    .build();
            LocalRunnerConnectResponse resp = runnerService.connect(WORKSPACE_ID, USER_NAME, req);

            assertThat(resp.runnerId()).isEqualTo(pair.runnerId());
            assertThat(resp.projectId()).isEqualTo(PROJECT_ID);
            assertThat(resp.projectName()).isEqualTo(PROJECT_NAME);

            RBucket<String> pairBucket = stringRedis.getBucket(
                    "opik:runners:pair:" + pair.pairingCode());
            assertThat(pairBucket.isExists()).isFalse();

            RMap<String, String> runnerMap = stringRedis.getMap(
                    "opik:runners:runner:" + resp.runnerId());
            assertThat(runnerMap.get("status")).isEqualTo("connected");
            assertThat(runnerMap.get("name")).isEqualTo(RUNNER_NAME);
            assertThat(runnerMap.get("connected_at")).isNotBlank();
            assertThat(runnerMap.get("project_id")).isEqualTo(PROJECT_ID.toString());
        }

        @Test
        void withPairingCode_removesRunnerTTL() {
            stubNextId();
            LocalRunnerPairResponse pair = runnerService.generatePairingCode(WORKSPACE_ID, USER_NAME, PROJECT_ID);

            LocalRunnerConnectRequest req = LocalRunnerConnectRequest.builder()
                    .pairingCode(pair.pairingCode())
                    .runnerName(RUNNER_NAME)
                    .build();
            runnerService.connect(WORKSPACE_ID, USER_NAME, req);

            RMap<String, String> runnerMap = stringRedis.getMap(
                    "opik:runners:runner:" + pair.runnerId());
            assertThat(runnerMap.remainTimeToLive()).isEqualTo(-1);
        }

        @Test
        void withPairingCode_setsHeartbeat() {
            stubNextId();
            LocalRunnerPairResponse pair = runnerService.generatePairingCode(WORKSPACE_ID, USER_NAME, PROJECT_ID);

            LocalRunnerConnectRequest req = LocalRunnerConnectRequest.builder()
                    .pairingCode(pair.pairingCode())
                    .runnerName(RUNNER_NAME)
                    .build();
            LocalRunnerConnectResponse resp = runnerService.connect(WORKSPACE_ID, USER_NAME, req);

            RBucket<String> hb = stringRedis.getBucket(
                    "opik:runners:runner:" + resp.runnerId() + ":heartbeat");
            assertThat(hb.isExists()).isTrue();
            assertThat(hb.remainTimeToLive()).isPositive();
        }

        @Test
        void replacesExistingRunner() {
            UUID oldRunnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, "old-runner");
            UUID newRunnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, "new-runner");

            assertThat(newRunnerId).isNotEqualTo(oldRunnerId);

            RBucket<String> oldHb = stringRedis.getBucket(
                    "opik:runners:runner:" + oldRunnerId + ":heartbeat");
            assertThat(oldHb.isExists()).isFalse();

            RBucket<String> userRunner = stringRedis.getBucket(
                    "opik:runners:workspace:" + WORKSPACE_ID + ":project:" + PROJECT_ID + ":user:" + USER_NAME
                            + ":runner");
            assertThat(userRunner.get()).isEqualTo(newRunnerId.toString());
        }
    }

    @Nested
    class Heartbeat {

        @Test
        void refreshesHeartbeatTTL() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            LocalRunnerHeartbeatResponse resp = runnerService.heartbeat(runnerId, WORKSPACE_ID, USER_NAME, null);
            assertThat(resp).isNotNull();

            RBucket<String> hb = stringRedis.getBucket(
                    "opik:runners:runner:" + runnerId + ":heartbeat");
            assertThat(hb.isExists()).isTrue();
            assertThat(hb.remainTimeToLive()).isPositive();
        }

        @Test
        void updatesLastHeartbeatOnActiveJobs() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            stubNextId();
            LocalRunnerJob claimed = runnerService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).block();
            assertThat(claimed).isNotNull();

            runnerService.heartbeat(runnerId, WORKSPACE_ID, USER_NAME, null);

            RMap<String, String> jobMap = stringRedis.getMap(
                    "opik:runners:job:" + claimed.id());
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
                    .projectId(PROJECT_ID)
                    .build();
            UUID jobId = runnerService.createJob(WORKSPACE_ID, USER_NAME, req);

            LocalRunnerJob job = runnerService.getJob(jobId, WORKSPACE_ID, USER_NAME);
            assertThat(job.id()).isEqualTo(jobId);
            assertThat(job.runnerId()).isEqualTo(runnerId);
            assertThat(job.agentName()).isEqualTo(AGENT_NAME);
            assertThat(job.status().getValue()).isEqualTo("pending");
            assertThat(job.projectId()).isEqualTo(PROJECT_ID);

            RList<String> pending = stringRedis.getList(
                    "opik:runners:jobs:" + runnerId + ":pending");
            assertThat(pending.readAll()).contains(jobId.toString());

            RScoredSortedSet<String> runnerJobs = stringRedis.getScoredSortedSet(
                    "opik:runners:runner:" + runnerId + ":jobs");
            assertThat(runnerJobs.contains(jobId.toString())).isTrue();
        }
    }

    @Nested
    class NextJob {

        @Test
        void removesFromPendingAddsToActive() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            runnerService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).block();

            RList<String> pending = stringRedis.getList(
                    "opik:runners:jobs:" + runnerId + ":pending");
            assertThat(pending.size()).isZero();

            RList<String> active = stringRedis.getList(
                    "opik:runners:jobs:" + runnerId + ":active");
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
            RMap<String, String> fakeJob = stringRedis.getMap("opik:runners:job:" + fakeJobId);
            fakeJob.putAll(Map.of(
                    "id", fakeJobId,
                    "runner_id", runnerId.toString(),
                    "agent_name", AGENT_NAME,
                    "status", "pending",
                    "workspace_id", OTHER_WORKSPACE_ID,
                    "created_at", Instant.now().toString()));
            RScoredSortedSet<String> runnerJobs = stringRedis.getScoredSortedSet(
                    "opik:runners:runner:" + runnerId + ":jobs");
            runnerJobs.add(Instant.now().toEpochMilli(), fakeJobId);

            LocalRunnerJob.LocalRunnerJobPage page = runnerService.listJobs(runnerId, null, WORKSPACE_ID, USER_NAME, 0,
                    10);
            assertThat(page.content()).hasSize(1);
        }

        @Test
        void skipsExpiredJobHashes() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            stringRedis.getMap("opik:runners:job:" + jobId).delete();

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

            RList<String> logsList = stringRedis.getList(
                    "opik:runners:job:" + jobId + ":logs");
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

            RList<String> logsList = stringRedis.getList(
                    "opik:runners:job:" + jobId + ":logs");
            assertThat(logsList.size()).isEqualTo(2);
        }
    }

    @Nested
    class ReportResult {

        @Test
        void completedJob() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).block();

            ObjectNode resultNode = MAPPER.createObjectNode();
            resultNode.put("output", "success");

            runnerService.reportResult(jobId, WORKSPACE_ID, USER_NAME,
                    LocalRunnerJobResultRequest.builder().status(LocalRunnerJobStatus.COMPLETED).result(resultNode)
                            .build());

            RMap<String, String> jobMap = stringRedis.getMap(
                    "opik:runners:job:" + jobId);
            assertThat(jobMap.get("status")).isEqualTo("completed");
            assertThat(jobMap.get("completed_at")).isNotBlank();
            assertThat(jobMap.get("result")).contains("success");

            RList<String> active = stringRedis.getList(
                    "opik:runners:jobs:" + runnerId + ":active");
            assertThat(active.readAll()).doesNotContain(jobId.toString());
        }

        @Test
        void failedJob() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).block();

            runnerService.reportResult(jobId, WORKSPACE_ID, USER_NAME,
                    LocalRunnerJobResultRequest.builder().status(LocalRunnerJobStatus.FAILED).error("something broke")
                            .build());

            RMap<String, String> jobMap = stringRedis.getMap(
                    "opik:runners:job:" + jobId);
            assertThat(jobMap.get("status")).isEqualTo("failed");
            assertThat(jobMap.get("error")).isEqualTo("something broke");
            assertThat(jobMap.get("completed_at")).isNotBlank();
        }

        @Test
        void setsTraceId() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).block();

            UUID traceId = UUID.randomUUID();
            runnerService.reportResult(jobId, WORKSPACE_ID, USER_NAME,
                    LocalRunnerJobResultRequest.builder().status(LocalRunnerJobStatus.COMPLETED).traceId(traceId)
                            .build());

            RMap<String, String> jobMap = stringRedis.getMap(
                    "opik:runners:job:" + jobId);
            assertThat(jobMap.get("trace_id")).isEqualTo(traceId.toString());
        }

        @Test
        void setsTTLOnJobAndLogs() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).block();

            runnerService.appendLogs(jobId, WORKSPACE_ID, USER_NAME,
                    List.of(LocalRunnerLogEntry.builder().stream("stdout").text("log").build()));

            runnerService.reportResult(jobId, WORKSPACE_ID, USER_NAME,
                    LocalRunnerJobResultRequest.builder().status(LocalRunnerJobStatus.COMPLETED).build());

            RMap<String, String> jobMap = stringRedis.getMap(
                    "opik:runners:job:" + jobId);
            assertThat(jobMap.remainTimeToLive()).isPositive();

            RList<String> logsList = stringRedis.getList(
                    "opik:runners:job:" + jobId + ":logs");
            assertThat(logsList.remainTimeToLive()).isPositive();
        }

        @Test
        void inFlightRunningSetsTraceIdWithoutCompletingJob() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).block();

            UUID traceId = UUID.randomUUID();
            runnerService.reportResult(jobId, WORKSPACE_ID, USER_NAME,
                    LocalRunnerJobResultRequest.builder().status(LocalRunnerJobStatus.RUNNING).traceId(traceId)
                            .build());

            RMap<String, String> jobMap = stringRedis.getMap(
                    "opik:runners:job:" + jobId);
            assertThat(jobMap.get("status")).isEqualTo("running");
            assertThat(jobMap.get("trace_id")).isEqualTo(traceId.toString());
            assertThat(jobMap.get("completed_at")).isNull();

            RList<String> active = stringRedis.getList(
                    "opik:runners:jobs:" + runnerId + ":active");
            assertThat(active.readAll()).contains(jobId.toString());

            assertThat(jobMap.remainTimeToLive()).isEqualTo(-1L);
        }

        @Test
        void inFlightRunningThenTerminalCompletes() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).block();

            UUID traceId = UUID.randomUUID();
            runnerService.reportResult(jobId, WORKSPACE_ID, USER_NAME,
                    LocalRunnerJobResultRequest.builder().status(LocalRunnerJobStatus.RUNNING).traceId(traceId)
                            .build());

            ObjectNode resultNode = MAPPER.createObjectNode();
            resultNode.put("output", "done");
            runnerService.reportResult(jobId, WORKSPACE_ID, USER_NAME,
                    LocalRunnerJobResultRequest.builder().status(LocalRunnerJobStatus.COMPLETED).result(resultNode)
                            .build());

            RMap<String, String> jobMap = stringRedis.getMap(
                    "opik:runners:job:" + jobId);
            assertThat(jobMap.get("status")).isEqualTo("completed");
            assertThat(jobMap.get("trace_id")).isEqualTo(traceId.toString());
            assertThat(jobMap.get("completed_at")).isNotBlank();
            assertThat(jobMap.get("result")).contains("done");

            RList<String> active = stringRedis.getList(
                    "opik:runners:jobs:" + runnerId + ":active");
            assertThat(active.readAll()).doesNotContain(jobId.toString());
        }

        @Test
        void rejectsPendingStatus() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).block();

            assertThatThrownBy(() -> runnerService.reportResult(jobId, WORKSPACE_ID, USER_NAME,
                    LocalRunnerJobResultRequest.builder().status(LocalRunnerJobStatus.PENDING).build()))
                    .isInstanceOf(ClientErrorException.class);
        }
    }

    @Nested
    class CancelJob {

        @Test
        void cancelActiveJob_addsToCancellationSet() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).block();

            runnerService.cancelJob(jobId, WORKSPACE_ID, USER_NAME);

            RMap<String, String> jobMap = stringRedis.getMap(
                    "opik:runners:job:" + jobId);
            assertThat(jobMap.get("status")).isEqualTo("cancelled");
            assertThat(jobMap.get("completed_at")).isNotBlank();

            RSet<String> cancellations = stringRedis.getSet(
                    "opik:runners:runner:" + runnerId + ":cancellations");
            assertThat(cancellations.contains(jobId.toString())).isTrue();
        }

        @Test
        void cancelPendingJob_removesFromPendingQueue() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            runnerService.cancelJob(jobId, WORKSPACE_ID, USER_NAME);

            RList<String> pending = stringRedis.getList(
                    "opik:runners:jobs:" + runnerId + ":pending");
            assertThat(pending.readAll()).doesNotContain(jobId.toString());

            RSet<String> cancellations = stringRedis.getSet(
                    "opik:runners:runner:" + runnerId + ":cancellations");
            assertThat(cancellations.contains(jobId.toString())).isFalse();

            RMap<String, String> jobMap = stringRedis.getMap(
                    "opik:runners:job:" + jobId);
            assertThat(jobMap.get("status")).isEqualTo("cancelled");
            assertThat(jobMap.remainTimeToLive()).isPositive();
        }
    }

    @Test
    void fullFlow_pairConnectCreateJobNextJobReportResult() {
        stubNextId();
        LocalRunnerPairResponse pair = runnerService.generatePairingCode(WORKSPACE_ID, USER_NAME, PROJECT_ID);
        assertThat(pair.pairingCode()).hasSize(6);

        LocalRunnerConnectRequest connectReq = LocalRunnerConnectRequest.builder()
                .pairingCode(pair.pairingCode())
                .runnerName(RUNNER_NAME)
                .build();
        LocalRunnerConnectResponse connectResp = runnerService.connect(WORKSPACE_ID, USER_NAME, connectReq);
        UUID runnerId = connectResp.runnerId();
        assertThat(runnerId).isEqualTo(pair.runnerId());
        assertThat(connectResp.projectId()).isEqualTo(PROJECT_ID);
        assertThat(connectResp.projectName()).isEqualTo(PROJECT_NAME);

        LocalRunner.Agent agentMeta = LocalRunner.Agent.builder().build();
        runnerService.registerAgents(runnerId, WORKSPACE_ID, USER_NAME, Map.of(AGENT_NAME, agentMeta));

        LocalRunner.LocalRunnerPage runnerPage = runnerService.listRunners(WORKSPACE_ID, USER_NAME, PROJECT_ID, null, 0,
                25);
        assertThat(runnerPage.content()).hasSize(1);
        assertThat(runnerPage.content().get(0).agents()).hasSize(1);

        stubNextId();
        ObjectNode inputs = MAPPER.createObjectNode();
        inputs.put("prompt", "hello");
        CreateLocalRunnerJobRequest jobReq = CreateLocalRunnerJobRequest.builder()
                .agentName(AGENT_NAME)
                .projectId(PROJECT_ID)
                .inputs(inputs)
                .build();
        UUID jobId = runnerService.createJob(WORKSPACE_ID, USER_NAME, jobReq);

        LocalRunnerJob created = runnerService.getJob(jobId, WORKSPACE_ID, USER_NAME);
        assertThat(created.status().getValue()).isEqualTo("pending");

        LocalRunnerJob claimed = runnerService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).block();
        assertThat(claimed).isNotNull();
        assertThat(claimed.id()).isEqualTo(jobId);
        assertThat(claimed.status().getValue()).isEqualTo("running");

        LocalRunnerHeartbeatResponse hbResp = runnerService.heartbeat(runnerId, WORKSPACE_ID, USER_NAME, null);
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
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            LocalRunner.LocalRunnerPage page = runnerService.listRunners(WORKSPACE_ID, OTHER_USER, PROJECT_ID, null, 0,
                    25);
            assertThat(page.content()).isEmpty();
            assertThat(page.total()).isZero();
        }

        @Test
        void getRunner_rejectsOtherUser() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            assertThatThrownBy(() -> runnerService.getRunner(WORKSPACE_ID, OTHER_USER, runnerId))
                    .isExactlyInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Runner not found");
        }

        @Test
        void registerAgents_rejectsOtherUser() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            assertThatThrownBy(() -> runnerService.registerAgents(runnerId, WORKSPACE_ID, OTHER_USER,
                    Map.of(AGENT_NAME, LocalRunner.Agent.builder().build())))
                    .isExactlyInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Runner not found");
        }

        @Test
        void heartbeat_rejectsOtherUser() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            assertThatThrownBy(() -> runnerService.heartbeat(runnerId, WORKSPACE_ID, OTHER_USER, null))
                    .isExactlyInstanceOf(ClientErrorException.class)
                    .satisfies(e -> assertThat(((ClientErrorException) e).getResponse().getStatus()).isEqualTo(410));
        }

        @Test
        void createJob_rejectsOtherUsersRunner() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            CreateLocalRunnerJobRequest req = CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME)
                    .projectId(PROJECT_ID)
                    .build();

            assertThatThrownBy(() -> runnerService.createJob(WORKSPACE_ID, OTHER_USER, req))
                    .isExactlyInstanceOf(NotFoundException.class);
        }

        @Test
        void nextJob_rejectsOtherUser() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            assertThatThrownBy(() -> runnerService.nextJob(runnerId, WORKSPACE_ID, OTHER_USER))
                    .isExactlyInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Runner not found");
        }

        @Test
        void listJobs_rejectsOtherUser() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            assertThatThrownBy(() -> runnerService.listJobs(runnerId, null, WORKSPACE_ID, OTHER_USER, 0, 10))
                    .isExactlyInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Runner not found");
        }

        @Test
        void getJob_rejectsOtherUser() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            assertThatThrownBy(() -> runnerService.getJob(jobId, WORKSPACE_ID, OTHER_USER))
                    .isExactlyInstanceOf(NotFoundException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        void getJobLogs_rejectsOtherUser() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            assertThatThrownBy(() -> runnerService.getJobLogs(jobId, 0, WORKSPACE_ID, OTHER_USER))
                    .isExactlyInstanceOf(NotFoundException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        void appendLogs_rejectsOtherUser() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            assertThatThrownBy(() -> runnerService.appendLogs(jobId, WORKSPACE_ID, OTHER_USER,
                    List.of(LocalRunnerLogEntry.builder().stream("stdout").text("hack").build())))
                    .isExactlyInstanceOf(NotFoundException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        void reportResult_rejectsOtherUser() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).block();

            assertThatThrownBy(() -> runnerService.reportResult(jobId, WORKSPACE_ID, OTHER_USER,
                    LocalRunnerJobResultRequest.builder().status(LocalRunnerJobStatus.COMPLETED).build()))
                    .isExactlyInstanceOf(NotFoundException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        void cancelJob_rejectsOtherUser() {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            assertThatThrownBy(() -> runnerService.cancelJob(jobId, WORKSPACE_ID, OTHER_USER))
                    .isExactlyInstanceOf(NotFoundException.class)
                    .hasMessageContaining("not found");
        }
    }

    // ========== Bridge Command Tests ==========

    private UUID pairAndConnectWithBridge(String workspaceId, String userName, String runnerName) {
        UUID runnerId = pairAndConnect(workspaceId, userName, runnerName);
        runnerService.heartbeat(runnerId, workspaceId, userName, List.of("jobs", "bridge"));
        return runnerId;
    }

    private UUID submitTestBridgeCommand(UUID runnerId, String workspaceId, String userName) {
        stubNextId();
        BridgeCommandSubmitRequest req = BridgeCommandSubmitRequest.builder()
                .type(BridgeCommandType.READ_FILE)
                .args(MAPPER.createObjectNode().put("path", "src/main.py"))
                .timeoutSeconds(10)
                .build();
        return runnerService.createBridgeCommand(runnerId, workspaceId, userName, req);
    }

    @Nested
    class BridgeSubmitCommand {

        @Test
        void createsCommandHashInRedis() {
            UUID runnerId = pairAndConnectWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);

            RMap<String, String> cmdMap = stringRedis.getMap("opik:runners:bridge:command:" + commandId);
            assertThat(cmdMap.get("command_id")).isEqualTo(commandId.toString());
            assertThat(cmdMap.get("runner_id")).isEqualTo(runnerId.toString());
            assertThat(cmdMap.get("type")).isEqualTo("ReadFile");
            assertThat(cmdMap.get("status")).isEqualTo("pending");
            assertThat(cmdMap.get("submitted_at")).isNotBlank();
            assertThat(cmdMap.get("timeout_seconds")).isEqualTo("10");
            assertThat(cmdMap.remainTimeToLive()).isPositive();
        }

        @Test
        void pushesToPendingList() {
            UUID runnerId = pairAndConnectWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);

            RList<String> pending = stringRedis.getList("opik:runners:bridge:" + runnerId + ":pending");
            assertThat(pending.readAll()).contains(commandId.toString());
        }

        @Test
        void unknownRunner_throws404() {
            UUID fakeRunner = UUID.randomUUID();
            assertThatThrownBy(() -> submitTestBridgeCommand(fakeRunner, WORKSPACE_ID, USER_NAME))
                    .isExactlyInstanceOf(NotFoundException.class);
        }

        @Test
        void disconnectedRunner_throws404() throws InterruptedException {
            UUID runnerId = pairAndConnectWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            waitForHeartbeatExpiry();

            assertThatThrownBy(() -> submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME))
                    .isExactlyInstanceOf(NotFoundException.class);
        }

        @Test
        void noBridgeCapability_throws409() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            assertThatThrownBy(() -> submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME))
                    .isExactlyInstanceOf(ClientErrorException.class)
                    .hasMessageContaining("409");
        }

        @Test
        void queueFull_throws429() {
            runnerConfig.setBridgeMaxPendingPerRunner(2);
            try {
                UUID runnerId = pairAndConnectWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
                submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
                submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);

                assertThatThrownBy(() -> submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME))
                        .isExactlyInstanceOf(ClientErrorException.class)
                        .hasMessageContaining("429");
            } finally {
                runnerConfig.setBridgeMaxPendingPerRunner(20);
            }
        }

        @Test
        void rateLimitExceeded_throws429() {
            runnerConfig.setBridgeMaxCommandsPerMinute(2);
            try {
                UUID runnerId = pairAndConnectWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
                submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
                submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);

                assertThatThrownBy(() -> submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME))
                        .isExactlyInstanceOf(ClientErrorException.class)
                        .hasMessageContaining("429");
            } finally {
                runnerConfig.setBridgeMaxCommandsPerMinute(60);
            }
        }

        @Test
        void writeRateLimitExceeded_throws429() {
            runnerConfig.setBridgeMaxWriteCommandsPerMinute(1);
            try {
                UUID runnerId = pairAndConnectWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
                stubNextId();
                BridgeCommandSubmitRequest writeReq = BridgeCommandSubmitRequest.builder()
                        .type(BridgeCommandType.WRITE_FILE)
                        .args(MAPPER.createObjectNode().put("path", "f.py").put("content", "x"))
                        .build();
                runnerService.createBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME, writeReq);

                stubNextId();
                assertThatThrownBy(
                        () -> runnerService.createBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME, writeReq))
                        .isExactlyInstanceOf(ClientErrorException.class)
                        .hasMessageContaining("429");
            } finally {
                runnerConfig.setBridgeMaxWriteCommandsPerMinute(10);
            }
        }

        @Test
        void clampsTimeoutToMax() {
            UUID runnerId = pairAndConnectWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            stubNextId();
            BridgeCommandSubmitRequest req = BridgeCommandSubmitRequest.builder()
                    .type(BridgeCommandType.READ_FILE)
                    .args(MAPPER.createObjectNode().put("path", "f.py"))
                    .timeoutSeconds(9999)
                    .build();
            UUID commandId = runnerService.createBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME, req);

            RMap<String, String> cmdMap = stringRedis.getMap("opik:runners:bridge:command:" + commandId);
            assertThat(cmdMap.get("timeout_seconds")).isEqualTo("120");
        }

        @Test
        void payloadTooLarge_throws400() {
            runnerConfig.setBridgeMaxPayloadBytes(100);
            try {
                UUID runnerId = pairAndConnectWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
                stubNextId();
                StringBuilder largeValue = new StringBuilder();
                for (int i = 0; i < 200; i++) {
                    largeValue.append("x");
                }
                BridgeCommandSubmitRequest req = BridgeCommandSubmitRequest.builder()
                        .type(BridgeCommandType.READ_FILE)
                        .args(MAPPER.createObjectNode().put("data", largeValue.toString()))
                        .build();

                assertThatThrownBy(() -> runnerService.createBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME, req))
                        .isExactlyInstanceOf(ClientErrorException.class)
                        .hasMessageContaining("400");
            } finally {
                runnerConfig.setBridgeMaxPayloadBytes(1_048_576);
            }
        }
    }

    @Nested
    class BridgeNextCommands {

        @Test
        void singlePending_returnsOneAndMovesToActive() {
            UUID runnerId = pairAndConnectWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);

            BridgeCommandBatchResponse batch = runnerService
                    .nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

            assertThat(batch.commands()).hasSize(1);
            assertThat(batch.commands().getFirst().commandId()).isEqualTo(commandId);
            assertThat(batch.commands().getFirst().type()).isEqualTo(BridgeCommandType.READ_FILE);

            RList<String> pending = stringRedis.getList("opik:runners:bridge:" + runnerId + ":pending");
            assertThat(pending.size()).isZero();

            RMap<String, String> cmdMap = stringRedis.getMap("opik:runners:bridge:command:" + commandId);
            assertThat(cmdMap.get("status")).isEqualTo("picked_up");
            assertThat(cmdMap.get("picked_up_at")).isNotBlank();
        }

        @Test
        void multiplePending_returnsBatch() {
            UUID runnerId = pairAndConnectWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID cmd1 = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
            UUID cmd2 = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
            UUID cmd3 = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);

            BridgeCommandBatchResponse batch = runnerService
                    .nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

            assertThat(batch.commands()).hasSize(3);
            assertThat(batch.commands().stream().map(c -> c.commandId()).toList())
                    .containsExactly(cmd1, cmd2, cmd3);
        }

        @Test
        void respectsMaxCommands() {
            UUID runnerId = pairAndConnectWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
            submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
            submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);

            BridgeCommandBatchResponse batch = runnerService
                    .nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 2).block();

            assertThat(batch.commands()).hasSize(2);

            RList<String> pending = stringRedis.getList("opik:runners:bridge:" + runnerId + ":pending");
            assertThat(pending.size()).isEqualTo(1);
        }

        @Test
        void noPending_blocksAndReturnsEmpty() {
            runnerConfig.setBridgePollTimeout(Duration.seconds(1));
            try {
                UUID runnerId = pairAndConnectWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

                BridgeCommandBatchResponse batch = runnerService
                        .nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

                assertThat(batch.commands()).isEmpty();
            } finally {
                runnerConfig.setBridgePollTimeout(Duration.seconds(30));
            }
        }
    }

    @Nested
    class BridgeReportResult {

        @Test
        void completed_updatesHashAndRemovesFromActive() {
            UUID runnerId = pairAndConnectWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
            runnerService.nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

            ObjectNode resultNode = MAPPER.createObjectNode().put("content", "file contents");
            runnerService.reportBridgeCommandResult(runnerId, WORKSPACE_ID, USER_NAME, commandId,
                    BridgeCommandResultRequest.builder()
                            .status(BridgeCommandStatus.COMPLETED)
                            .result(resultNode)
                            .durationMs(42L)
                            .build());

            RMap<String, String> cmdMap = stringRedis.getMap("opik:runners:bridge:command:" + commandId);
            assertThat(cmdMap.get("status")).isEqualTo("completed");
            assertThat(cmdMap.get("completed_at")).isNotBlank();
            assertThat(cmdMap.get("result")).contains("file contents");
            assertThat(cmdMap.get("duration_ms")).isEqualTo("42");

            RList<String> active = stringRedis.getList("opik:runners:bridge:" + runnerId + ":active");
            assertThat(active.readAll()).doesNotContain(commandId.toString());
        }

        @Test
        void failed_updatesHashWithError() {
            UUID runnerId = pairAndConnectWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
            runnerService.nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

            ObjectNode errorNode = MAPPER.createObjectNode()
                    .put("code", "file_not_found")
                    .put("message", "File not found");
            runnerService.reportBridgeCommandResult(runnerId, WORKSPACE_ID, USER_NAME, commandId,
                    BridgeCommandResultRequest.builder()
                            .status(BridgeCommandStatus.FAILED)
                            .error(errorNode)
                            .build());

            RMap<String, String> cmdMap = stringRedis.getMap("opik:runners:bridge:command:" + commandId);
            assertThat(cmdMap.get("status")).isEqualTo("failed");
            assertThat(cmdMap.get("error")).contains("file_not_found");
        }

        @Test
        void duplicate_throws409() {
            UUID runnerId = pairAndConnectWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
            runnerService.nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

            runnerService.reportBridgeCommandResult(runnerId, WORKSPACE_ID, USER_NAME, commandId,
                    BridgeCommandResultRequest.builder().status(BridgeCommandStatus.COMPLETED)
                            .result(MAPPER.createObjectNode()).build());

            assertThatThrownBy(() -> runnerService.reportBridgeCommandResult(runnerId, WORKSPACE_ID, USER_NAME,
                    commandId, BridgeCommandResultRequest.builder().status(BridgeCommandStatus.COMPLETED)
                            .result(MAPPER.createObjectNode()).build()))
                    .isExactlyInstanceOf(ClientErrorException.class)
                    .hasMessageContaining("409");
        }

        @Test
        void commandNotOwned_throws404() {
            UUID runnerId = pairAndConnectWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
            runnerService.nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

            UUID otherRunner = UUID.randomUUID();
            assertThatThrownBy(() -> runnerService.reportBridgeCommandResult(otherRunner, WORKSPACE_ID, USER_NAME,
                    commandId, BridgeCommandResultRequest.builder().status(BridgeCommandStatus.COMPLETED)
                            .result(MAPPER.createObjectNode()).build()))
                    .isExactlyInstanceOf(NotFoundException.class);
        }

        @Test
        void writesDoneSentinel() {
            UUID runnerId = pairAndConnectWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
            runnerService.nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

            runnerService.reportBridgeCommandResult(runnerId, WORKSPACE_ID, USER_NAME, commandId,
                    BridgeCommandResultRequest.builder().status(BridgeCommandStatus.COMPLETED)
                            .result(MAPPER.createObjectNode()).build());

            RList<String> doneQueue = stringRedis.getList("opik:runners:bridge:command:" + commandId + ":done");
            assertThat(doneQueue.size()).isGreaterThan(0);
        }

        @Test
        void rejectsNonReportableStatus_PENDING() {
            UUID runnerId = pairAndConnectWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
            runnerService.nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

            assertThatThrownBy(() -> runnerService.reportBridgeCommandResult(runnerId, WORKSPACE_ID, USER_NAME,
                    commandId, BridgeCommandResultRequest.builder().status(BridgeCommandStatus.PENDING).build()))
                    .isExactlyInstanceOf(ClientErrorException.class)
                    .hasMessageContaining("400");
        }

        @Test
        void rejectsNonReportableStatus_TIMED_OUT() {
            UUID runnerId = pairAndConnectWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
            runnerService.nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

            assertThatThrownBy(() -> runnerService.reportBridgeCommandResult(runnerId, WORKSPACE_ID, USER_NAME,
                    commandId,
                    BridgeCommandResultRequest.builder().status(BridgeCommandStatus.TIMED_OUT).build()))
                    .isExactlyInstanceOf(ClientErrorException.class)
                    .hasMessageContaining("400");
        }

        @Test
        void resultPayloadTooLarge_throws400() {
            runnerConfig.setBridgeMaxPayloadBytes(50);
            try {
                UUID runnerId = pairAndConnectWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
                UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
                runnerService.nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

                StringBuilder largeValue = new StringBuilder();
                for (int i = 0; i < 200; i++) {
                    largeValue.append("x");
                }
                ObjectNode bigResult = MAPPER.createObjectNode().put("data", largeValue.toString());

                assertThatThrownBy(() -> runnerService.reportBridgeCommandResult(runnerId, WORKSPACE_ID, USER_NAME,
                        commandId,
                        BridgeCommandResultRequest.builder().status(BridgeCommandStatus.COMPLETED).result(bigResult)
                                .build()))
                        .isExactlyInstanceOf(ClientErrorException.class)
                        .hasMessageContaining("400");
            } finally {
                runnerConfig.setBridgeMaxPayloadBytes(1_048_576);
            }
        }
    }

    @Nested
    class BridgeAwaitCommand {

        @Test
        void alreadyCompleted_returnsImmediately() {
            UUID runnerId = pairAndConnectWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
            runnerService.nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

            ObjectNode resultNode = MAPPER.createObjectNode().put("content", "data");
            runnerService.reportBridgeCommandResult(runnerId, WORKSPACE_ID, USER_NAME, commandId,
                    BridgeCommandResultRequest.builder()
                            .status(BridgeCommandStatus.COMPLETED)
                            .result(resultNode)
                            .build());

            BridgeCommand cmd = runnerService.awaitBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME,
                    commandId, 5).block();

            assertThat(cmd.status()).isEqualTo(BridgeCommandStatus.COMPLETED);
            assertThat(cmd.result().get("content").asText()).isEqualTo("data");
        }

        @Test
        void pendingThenCompleted_blocksAndReturns() {
            UUID runnerId = pairAndConnectWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
            runnerService.nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

            Thread reporter = new Thread(() -> {
                try {
                    Thread.sleep(500);
                    runnerService.reportBridgeCommandResult(runnerId, WORKSPACE_ID, USER_NAME, commandId,
                            BridgeCommandResultRequest.builder().status(BridgeCommandStatus.COMPLETED)
                                    .result(MAPPER.createObjectNode()).build());
                } catch (InterruptedException ignored) {
                }
            });
            reporter.start();

            BridgeCommand cmd = runnerService.awaitBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME,
                    commandId, 10).block();

            assertThat(cmd.status()).isEqualTo(BridgeCommandStatus.COMPLETED);
        }

        @Test
        void timeout_returnsNonTerminal() {
            UUID runnerId = pairAndConnectWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);

            BridgeCommand cmd = runnerService.awaitBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME,
                    commandId, 1).block();

            assertThat(cmd.status()).isEqualTo(BridgeCommandStatus.PENDING);
        }

        @Test
        void noWait_returnsCurrentState() {
            UUID runnerId = pairAndConnectWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);

            BridgeCommand cmd = runnerService.getBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME, commandId);

            assertThat(cmd.status()).isEqualTo(BridgeCommandStatus.PENDING);
            assertThat(cmd.commandId()).isEqualTo(commandId);
            assertThat(cmd.type()).isEqualTo(BridgeCommandType.READ_FILE);
        }

        @Test
        void timeout_doesNotCreateOrphanedDoneQueueKey() {
            UUID runnerId = pairAndConnectWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);

            runnerService.awaitBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME, commandId, 1).block();

            // Redis BLPOP does not create the key on timeout — verify no orphaned done queue
            RList<String> doneList = stringRedis.getList("opik:runners:bridge:command:" + commandId + ":done");
            assertThat(doneList.isExists()).isFalse();
        }

        @Test
        void doneQueueTtl_isShort() {
            UUID runnerId = pairAndConnectWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
            runnerService.nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

            runnerService.reportBridgeCommandResult(runnerId, WORKSPACE_ID, USER_NAME, commandId,
                    BridgeCommandResultRequest.builder().status(BridgeCommandStatus.COMPLETED)
                            .result(MAPPER.createObjectNode()).build());

            RList<String> doneQueue = stringRedis.getList("opik:runners:bridge:command:" + commandId + ":done");
            long ttlMs = doneQueue.remainTimeToLive();
            // Should be ~150s (bridgeMaxCommandTimeout 120s + 30s grace), not 3600s
            assertThat(ttlMs).isPositive();
            assertThat(ttlMs).isLessThan(300_000L);
        }
    }

    @Nested
    class BridgeConcurrency {

        @Test
        void concurrentReports_onlyOneSucceeds() throws InterruptedException {
            UUID runnerId = pairAndConnectWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
            runnerService.nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

            java.util.concurrent.atomic.AtomicInteger successes = new java.util.concurrent.atomic.AtomicInteger(0);
            java.util.concurrent.atomic.AtomicInteger conflicts = new java.util.concurrent.atomic.AtomicInteger(0);
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

            Runnable reporter = () -> {
                try {
                    latch.await();
                    runnerService.reportBridgeCommandResult(runnerId, WORKSPACE_ID, USER_NAME, commandId,
                            BridgeCommandResultRequest.builder().status(BridgeCommandStatus.COMPLETED)
                                    .result(MAPPER.createObjectNode()).build());
                    successes.incrementAndGet();
                } catch (ClientErrorException e) {
                    if (e.getResponse().getStatus() == 409) {
                        conflicts.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                }
            };

            Thread t1 = new Thread(reporter);
            Thread t2 = new Thread(reporter);
            t1.start();
            t2.start();
            latch.countDown();
            t1.join(5000);
            t2.join(5000);

            assertThat(successes.get()).isEqualTo(1);
            assertThat(conflicts.get()).isEqualTo(1);
        }
    }

    @Nested
    class BridgeHeartbeatCapabilities {

        @Test
        void storesCapabilitiesOnRunner() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            runnerService.heartbeat(runnerId, WORKSPACE_ID, USER_NAME, List.of("jobs", "bridge"));

            RMap<String, String> runnerMap = stringRedis.getMap("opik:runners:runner:" + runnerId);
            assertThat(runnerMap.get("capabilities")).isEqualTo("jobs,bridge");
        }

        @Test
        void defaultsToJobsWithoutCapabilities() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            runnerService.heartbeat(runnerId, WORKSPACE_ID, USER_NAME, null);

            LocalRunner runner = runnerService.getRunner(WORKSPACE_ID, USER_NAME, runnerId);
            assertThat(runner.capabilities()).containsExactly("jobs");
        }

        @Test
        void getRunnerIncludesCapabilities() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            runnerService.heartbeat(runnerId, WORKSPACE_ID, USER_NAME, List.of("jobs", "bridge"));

            LocalRunner runner = runnerService.getRunner(WORKSPACE_ID, USER_NAME, runnerId);
            assertThat(runner.capabilities()).containsExactly("jobs", "bridge");
        }
    }

    @Nested
    class RunnerChecklist {

        @Test
        void patchAndGetChecklist() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            ObjectNode patch = MAPPER.createObjectNode();
            ObjectNode instrumentation = patch.putObject("instrumentation");
            instrumentation.put("tracing", true);
            instrumentation.put("entrypoint", false);
            instrumentation.put("configuration", false);

            runnerService.patchChecklist(runnerId, WORKSPACE_ID, USER_NAME, patch);

            LocalRunner runner = runnerService.getRunner(WORKSPACE_ID, USER_NAME, runnerId);
            assertThat(runner.checklist()).isNotNull();
            assertThat(runner.checklist().get("instrumentation").get("tracing").asBoolean()).isTrue();
            assertThat(runner.checklist().get("instrumentation").get("entrypoint").asBoolean()).isFalse();
        }

        @Test
        void patchDeepMergesFields() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            ObjectNode initial = MAPPER.createObjectNode();
            ObjectNode inst = initial.putObject("instrumentation");
            inst.put("tracing", false);
            inst.put("entrypoint", false);
            inst.put("configuration", false);
            initial.put("child_status", "running");
            runnerService.patchChecklist(runnerId, WORKSPACE_ID, USER_NAME, initial);

            ObjectNode update = MAPPER.createObjectNode();
            ObjectNode instUpdate = update.putObject("instrumentation");
            instUpdate.put("tracing", true);
            runnerService.patchChecklist(runnerId, WORKSPACE_ID, USER_NAME, update);

            LocalRunner runner = runnerService.getRunner(WORKSPACE_ID, USER_NAME, runnerId);
            assertThat(runner.checklist().get("instrumentation").get("tracing").asBoolean()).isTrue();
            assertThat(runner.checklist().get("instrumentation").get("entrypoint").asBoolean()).isFalse();
            assertThat(runner.checklist().get("child_status").asText()).isEqualTo("running");
        }

        @Test
        void getRunnerWithNoChecklist_returnsNull() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            LocalRunner runner = runnerService.getRunner(WORKSPACE_ID, USER_NAME, runnerId);
            assertThat(runner.checklist()).isNull();
        }

        @Test
        void patchChecklist_wrongRunner_throws404() {
            assertThatThrownBy(() -> runnerService.patchChecklist(UUID.randomUUID(), WORKSPACE_ID, USER_NAME,
                    MAPPER.createObjectNode().put("tracing", true)))
                    .isExactlyInstanceOf(NotFoundException.class);
        }

        @Test
        void patchChecklist_nonObject_throws400() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            assertThatThrownBy(() -> runnerService.patchChecklist(runnerId, WORKSPACE_ID, USER_NAME,
                    MAPPER.createArrayNode().add("x")))
                    .isExactlyInstanceOf(ClientErrorException.class)
                    .hasMessageContaining("400");
        }
    }

    @Nested
    class BridgeReaper {

        @Test
        void deadRunner_marksCommandsTimedOut() throws InterruptedException {
            runnerConfig.setDeadRunnerPurgeTime(Duration.seconds(0));
            try {
                UUID runnerId = pairAndConnectWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
                UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
                runnerService.nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

                waitForHeartbeatExpiry();
                runnerService.reapDeadRunners();

                RMap<String, String> cmdMap = stringRedis.getMap("opik:runners:bridge:command:" + commandId);
                assertThat(cmdMap.get("status")).isEqualTo("timed_out");
            } finally {
                runnerConfig.setDeadRunnerPurgeTime(Duration.hours(24));
            }
        }

        @Test
        void deadRunner_writesDoneSentinels() throws InterruptedException {
            runnerConfig.setDeadRunnerPurgeTime(Duration.seconds(0));
            try {
                UUID runnerId = pairAndConnectWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
                UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
                runnerService.nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

                waitForHeartbeatExpiry();
                runnerService.reapDeadRunners();

                RList<String> doneQueue = stringRedis.getList("opik:runners:bridge:command:" + commandId + ":done");
                assertThat(doneQueue.size()).isGreaterThan(0);
            } finally {
                runnerConfig.setDeadRunnerPurgeTime(Duration.hours(24));
            }
        }

        @Test
        void activeCommandPastDeadline_marksTimedOut() {
            UUID runnerId = pairAndConnectWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            stubNextId();
            BridgeCommandSubmitRequest req = BridgeCommandSubmitRequest.builder()
                    .type(BridgeCommandType.READ_FILE)
                    .args(MAPPER.createObjectNode().put("path", "f.py"))
                    .timeoutSeconds(1)
                    .build();
            UUID commandId = runnerService.createBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME, req);
            runnerService.nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

            RMap<String, String> cmdMap = stringRedis.getMap("opik:runners:bridge:command:" + commandId);
            cmdMap.put("picked_up_at", Instant.now().minusSeconds(60).toString());

            runnerService.reapStaleBridgeCommands(runnerId);

            assertThat(cmdMap.get("status")).isEqualTo("timed_out");
        }
    }
}
