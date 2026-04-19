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
import com.comet.opik.api.runner.LocalRunnerHeartbeatResponse;
import com.comet.opik.api.runner.LocalRunnerJob;
import com.comet.opik.api.runner.LocalRunnerJobResultRequest;
import com.comet.opik.api.runner.LocalRunnerJobStatus;
import com.comet.opik.api.runner.LocalRunnerLogEntry;
import com.comet.opik.api.runner.RunnerType;
import com.comet.opik.infrastructure.LocalRunnerConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.bi.AnalyticsService;
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
    private RunnerServiceImpl runnerService;
    private EndpointJobServiceImpl endpointJobService;
    private ConnectBridgeServiceImpl connectBridgeService;

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

        RequestContext requestContext = RequestContext.builder()
                .workspaceId(WORKSPACE_ID)
                .userName(USER_NAME)
                .build();
        runnerService = new RunnerServiceImpl(stringRedis, idGenerator, projectService, runnerConfig,
                () -> endpointJobService, () -> connectBridgeService, () -> requestContext);
        endpointJobService = new EndpointJobServiceImpl(stringRedis, redisClient.reactive(), idGenerator,
                runnerService, runnerConfig, Mockito.mock(AnalyticsService.class));
        connectBridgeService = new ConnectBridgeServiceImpl(stringRedis, idGenerator, runnerService, runnerConfig);
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

    private UUID connectRunner(String workspaceId, String userName, String runnerName) {
        stubNextId();
        UUID runnerId = idGenerator.generateId();
        runnerService.activateFromPairing(workspaceId, userName, PROJECT_ID, runnerId, runnerName,
                RunnerType.ENDPOINT);
        LocalRunner.Agent agent = LocalRunner.Agent.builder()
                .name(AGENT_NAME)
                .build();
        endpointJobService.registerAgents(runnerId, workspaceId, userName, Map.of(AGENT_NAME, agent));
        return runnerId;
    }

    private UUID createTestJob(String workspaceId, String userName, String agentName) {
        stubNextId();
        CreateLocalRunnerJobRequest req = CreateLocalRunnerJobRequest.builder()
                .agentName(agentName)
                .projectId(PROJECT_ID)
                .build();
        return endpointJobService.createJob(workspaceId, userName, req);
    }

    private void waitForHeartbeatExpiry() throws InterruptedException {
        Thread.sleep((runnerConfig.getHeartbeatTtl().toSeconds() + 1) * 1000L);
    }

    @Nested
    class Heartbeat {

        @Test
        void refreshesHeartbeatTTL() {
            UUID runnerId = connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            LocalRunnerHeartbeatResponse resp = runnerService.heartbeat(runnerId, WORKSPACE_ID, USER_NAME, null);
            assertThat(resp).isNotNull();

            RBucket<String> hb = stringRedis.getBucket(
                    "opik:runners:runner:" + runnerId + ":heartbeat");
            assertThat(hb.isExists()).isTrue();
            assertThat(hb.remainTimeToLive()).isPositive();
        }

        @Test
        void updatesLastHeartbeatOnActiveJobs() {
            UUID runnerId = connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            stubNextId();
            LocalRunnerJob claimed = endpointJobService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).block();
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
            UUID runnerId = connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            stubNextId();
            CreateLocalRunnerJobRequest req = CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME)
                    .projectId(PROJECT_ID)
                    .build();
            UUID jobId = endpointJobService.createJob(WORKSPACE_ID, USER_NAME, req);

            LocalRunnerJob job = endpointJobService.getJob(jobId, WORKSPACE_ID, USER_NAME);
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
            UUID runnerId = connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            endpointJobService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).block();

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
            UUID runnerId = connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
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

            LocalRunnerJob.LocalRunnerJobPage page = endpointJobService.listJobs(runnerId, null, WORKSPACE_ID,
                    USER_NAME, 0,
                    10);
            assertThat(page.content()).hasSize(1);
        }

        @Test
        void skipsExpiredJobHashes() {
            UUID runnerId = connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            stringRedis.getMap("opik:runners:job:" + jobId).delete();

            LocalRunnerJob.LocalRunnerJobPage page = endpointJobService.listJobs(runnerId, null, WORKSPACE_ID,
                    USER_NAME, 0,
                    10);
            assertThat(page.content()).isEmpty();
        }
    }

    @Nested
    class AppendLogs {

        @Test
        void appendsEntriesToList() {
            connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            endpointJobService.appendLogs(jobId, WORKSPACE_ID, USER_NAME,
                    List.of(LocalRunnerLogEntry.builder().stream("stdout").text("hello").build()));

            RList<String> logsList = stringRedis.getList(
                    "opik:runners:job:" + jobId + ":logs");
            assertThat(logsList.size()).isEqualTo(1);
        }

        @Test
        void appendsMultipleBatches() {
            connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            endpointJobService.appendLogs(jobId, WORKSPACE_ID, USER_NAME,
                    List.of(LocalRunnerLogEntry.builder().stream("stdout").text("batch1").build()));
            endpointJobService.appendLogs(jobId, WORKSPACE_ID, USER_NAME,
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
            UUID runnerId = connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            endpointJobService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).block();

            ObjectNode resultNode = MAPPER.createObjectNode();
            resultNode.put("output", "success");

            endpointJobService.reportResult(jobId, WORKSPACE_ID, USER_NAME,
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
            UUID runnerId = connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            endpointJobService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).block();

            endpointJobService.reportResult(jobId, WORKSPACE_ID, USER_NAME,
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
            UUID runnerId = connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            endpointJobService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).block();

            UUID traceId = UUID.randomUUID();
            endpointJobService.reportResult(jobId, WORKSPACE_ID, USER_NAME,
                    LocalRunnerJobResultRequest.builder().status(LocalRunnerJobStatus.COMPLETED).traceId(traceId)
                            .build());

            RMap<String, String> jobMap = stringRedis.getMap(
                    "opik:runners:job:" + jobId);
            assertThat(jobMap.get("trace_id")).isEqualTo(traceId.toString());
        }

        @Test
        void setsTTLOnJobAndLogs() {
            UUID runnerId = connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            endpointJobService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).block();

            endpointJobService.appendLogs(jobId, WORKSPACE_ID, USER_NAME,
                    List.of(LocalRunnerLogEntry.builder().stream("stdout").text("log").build()));

            endpointJobService.reportResult(jobId, WORKSPACE_ID, USER_NAME,
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
            UUID runnerId = connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            endpointJobService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).block();

            UUID traceId = UUID.randomUUID();
            endpointJobService.reportResult(jobId, WORKSPACE_ID, USER_NAME,
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
            UUID runnerId = connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            endpointJobService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).block();

            UUID traceId = UUID.randomUUID();
            endpointJobService.reportResult(jobId, WORKSPACE_ID, USER_NAME,
                    LocalRunnerJobResultRequest.builder().status(LocalRunnerJobStatus.RUNNING).traceId(traceId)
                            .build());

            ObjectNode resultNode = MAPPER.createObjectNode();
            resultNode.put("output", "done");
            endpointJobService.reportResult(jobId, WORKSPACE_ID, USER_NAME,
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
            UUID runnerId = connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            endpointJobService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).block();

            assertThatThrownBy(() -> endpointJobService.reportResult(jobId, WORKSPACE_ID, USER_NAME,
                    LocalRunnerJobResultRequest.builder().status(LocalRunnerJobStatus.PENDING).build()))
                    .isInstanceOf(ClientErrorException.class);
        }
    }

    @Nested
    class CancelJob {

        @Test
        void cancelActiveJob_addsToCancellationSet() {
            UUID runnerId = connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            endpointJobService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).block();

            endpointJobService.cancelJob(jobId, WORKSPACE_ID, USER_NAME);

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
            UUID runnerId = connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            endpointJobService.cancelJob(jobId, WORKSPACE_ID, USER_NAME);

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
    void fullFlow_connectCreateJobNextJobReportResult() {
        UUID runnerId = connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

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
        UUID jobId = endpointJobService.createJob(WORKSPACE_ID, USER_NAME, jobReq);

        LocalRunnerJob created = endpointJobService.getJob(jobId, WORKSPACE_ID, USER_NAME);
        assertThat(created.status().getValue()).isEqualTo("pending");

        LocalRunnerJob claimed = endpointJobService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).block();
        assertThat(claimed).isNotNull();
        assertThat(claimed.id()).isEqualTo(jobId);
        assertThat(claimed.status().getValue()).isEqualTo("running");

        LocalRunnerHeartbeatResponse hbResp = runnerService.heartbeat(runnerId, WORKSPACE_ID, USER_NAME, null);
        assertThat(hbResp.cancelledJobIds()).isEmpty();

        endpointJobService.appendLogs(claimed.id(), WORKSPACE_ID, USER_NAME,
                List.of(LocalRunnerLogEntry.builder().stream("stdout").text("Processing...").build()));

        UUID traceId = UUID.randomUUID();
        ObjectNode resultNode = MAPPER.createObjectNode();
        resultNode.put("answer", "world");
        endpointJobService.reportResult(claimed.id(), WORKSPACE_ID, USER_NAME,
                LocalRunnerJobResultRequest.builder()
                        .status(LocalRunnerJobStatus.COMPLETED)
                        .result(resultNode)
                        .traceId(traceId)
                        .build());

        LocalRunnerJob finalJob = endpointJobService.getJob(claimed.id(), WORKSPACE_ID, USER_NAME);
        assertThat(finalJob.status().getValue()).isEqualTo("completed");
        assertThat(finalJob.traceId()).isEqualTo(traceId);
        assertThat(finalJob.result().get("answer").asText()).isEqualTo("world");

        List<LocalRunnerLogEntry> logs = endpointJobService.getJobLogs(claimed.id(), 0, WORKSPACE_ID, USER_NAME);
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).text()).isEqualTo("Processing...");
    }

    @Nested
    class CrossUserIsolation {

        private static final String OTHER_USER = "other-user";

        @Test
        void listRunners_excludesOtherUsersRunners() {
            connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            LocalRunner.LocalRunnerPage page = runnerService.listRunners(WORKSPACE_ID, OTHER_USER, PROJECT_ID, null, 0,
                    25);
            assertThat(page.content()).isEmpty();
            assertThat(page.total()).isZero();
        }

        @Test
        void getRunner_rejectsOtherUser() {
            UUID runnerId = connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            assertThatThrownBy(() -> runnerService.getRunner(WORKSPACE_ID, OTHER_USER, runnerId))
                    .isExactlyInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Runner not found");
        }

        @Test
        void registerAgents_rejectsOtherUser() {
            UUID runnerId = connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            assertThatThrownBy(() -> endpointJobService.registerAgents(runnerId, WORKSPACE_ID, OTHER_USER,
                    Map.of(AGENT_NAME, LocalRunner.Agent.builder().build())))
                    .isExactlyInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Runner not found");
        }

        @Test
        void heartbeat_rejectsOtherUser() {
            UUID runnerId = connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            assertThatThrownBy(() -> runnerService.heartbeat(runnerId, WORKSPACE_ID, OTHER_USER, null))
                    .isExactlyInstanceOf(ClientErrorException.class)
                    .satisfies(e -> assertThat(((ClientErrorException) e).getResponse().getStatus()).isEqualTo(410));
        }

        @Test
        void createJob_rejectsOtherUsersRunner() {
            UUID runnerId = connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            CreateLocalRunnerJobRequest req = CreateLocalRunnerJobRequest.builder()
                    .agentName(AGENT_NAME)
                    .projectId(PROJECT_ID)
                    .build();

            assertThatThrownBy(() -> endpointJobService.createJob(WORKSPACE_ID, OTHER_USER, req))
                    .isExactlyInstanceOf(NotFoundException.class);
        }

        @Test
        void nextJob_rejectsOtherUser() {
            UUID runnerId = connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            assertThatThrownBy(() -> endpointJobService.nextJob(runnerId, WORKSPACE_ID, OTHER_USER))
                    .isExactlyInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Runner not found");
        }

        @Test
        void listJobs_rejectsOtherUser() {
            UUID runnerId = connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            assertThatThrownBy(() -> endpointJobService.listJobs(runnerId, null, WORKSPACE_ID, OTHER_USER, 0, 10))
                    .isExactlyInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Runner not found");
        }

        @Test
        void getJob_rejectsOtherUser() {
            connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            assertThatThrownBy(() -> endpointJobService.getJob(jobId, WORKSPACE_ID, OTHER_USER))
                    .isExactlyInstanceOf(NotFoundException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        void getJobLogs_rejectsOtherUser() {
            connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            assertThatThrownBy(() -> endpointJobService.getJobLogs(jobId, 0, WORKSPACE_ID, OTHER_USER))
                    .isExactlyInstanceOf(NotFoundException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        void appendLogs_rejectsOtherUser() {
            connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            assertThatThrownBy(() -> endpointJobService.appendLogs(jobId, WORKSPACE_ID, OTHER_USER,
                    List.of(LocalRunnerLogEntry.builder().stream("stdout").text("hack").build())))
                    .isExactlyInstanceOf(NotFoundException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        void reportResult_rejectsOtherUser() {
            UUID runnerId = connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            endpointJobService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).block();

            assertThatThrownBy(() -> endpointJobService.reportResult(jobId, WORKSPACE_ID, OTHER_USER,
                    LocalRunnerJobResultRequest.builder().status(LocalRunnerJobStatus.COMPLETED).build()))
                    .isExactlyInstanceOf(NotFoundException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        void cancelJob_rejectsOtherUser() {
            connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            assertThatThrownBy(() -> endpointJobService.cancelJob(jobId, WORKSPACE_ID, OTHER_USER))
                    .isExactlyInstanceOf(NotFoundException.class)
                    .hasMessageContaining("not found");
        }
    }

    // ========== Bridge Command Tests ==========

    private UUID connectRunnerWithBridge(String workspaceId, String userName, String runnerName) {
        UUID runnerId = connectRunner(workspaceId, userName, runnerName);
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
        return connectBridgeService.createBridgeCommand(runnerId, workspaceId, userName, req);
    }

    @Nested
    class BridgeSubmitCommand {

        @Test
        void createsCommandHashInRedis() {
            UUID runnerId = connectRunnerWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
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
            UUID runnerId = connectRunnerWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
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
            UUID runnerId = connectRunnerWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            waitForHeartbeatExpiry();

            assertThatThrownBy(() -> submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME))
                    .isExactlyInstanceOf(NotFoundException.class);
        }

        @Test
        void noBridgeCapability_throws409() {
            UUID runnerId = connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            assertThatThrownBy(() -> submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME))
                    .isExactlyInstanceOf(ClientErrorException.class)
                    .hasMessageContaining("409");
        }

        @Test
        void queueFull_throws429() {
            runnerConfig.setBridgeMaxPendingPerRunner(2);
            try {
                UUID runnerId = connectRunnerWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
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
                UUID runnerId = connectRunnerWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
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
                UUID runnerId = connectRunnerWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
                stubNextId();
                BridgeCommandSubmitRequest writeReq = BridgeCommandSubmitRequest.builder()
                        .type(BridgeCommandType.WRITE_FILE)
                        .args(MAPPER.createObjectNode().put("path", "f.py").put("content", "x"))
                        .build();
                connectBridgeService.createBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME, writeReq);

                stubNextId();
                assertThatThrownBy(
                        () -> connectBridgeService.createBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME, writeReq))
                        .isExactlyInstanceOf(ClientErrorException.class)
                        .hasMessageContaining("429");
            } finally {
                runnerConfig.setBridgeMaxWriteCommandsPerMinute(10);
            }
        }

        @Test
        void clampsTimeoutToMax() {
            UUID runnerId = connectRunnerWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            stubNextId();
            BridgeCommandSubmitRequest req = BridgeCommandSubmitRequest.builder()
                    .type(BridgeCommandType.READ_FILE)
                    .args(MAPPER.createObjectNode().put("path", "f.py"))
                    .timeoutSeconds(9999)
                    .build();
            UUID commandId = connectBridgeService.createBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME, req);

            RMap<String, String> cmdMap = stringRedis.getMap("opik:runners:bridge:command:" + commandId);
            assertThat(cmdMap.get("timeout_seconds")).isEqualTo("120");
        }

        @Test
        void payloadTooLarge_throws400() {
            runnerConfig.setBridgeMaxPayloadBytes(100);
            try {
                UUID runnerId = connectRunnerWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
                stubNextId();
                StringBuilder largeValue = new StringBuilder();
                for (int i = 0; i < 200; i++) {
                    largeValue.append("x");
                }
                BridgeCommandSubmitRequest req = BridgeCommandSubmitRequest.builder()
                        .type(BridgeCommandType.READ_FILE)
                        .args(MAPPER.createObjectNode().put("data", largeValue.toString()))
                        .build();

                assertThatThrownBy(
                        () -> connectBridgeService.createBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME, req))
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
            UUID runnerId = connectRunnerWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);

            BridgeCommandBatchResponse batch = connectBridgeService
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
            UUID runnerId = connectRunnerWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID cmd1 = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
            UUID cmd2 = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
            UUID cmd3 = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);

            BridgeCommandBatchResponse batch = connectBridgeService
                    .nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

            assertThat(batch.commands()).hasSize(3);
            assertThat(batch.commands().stream().map(c -> c.commandId()).toList())
                    .containsExactly(cmd1, cmd2, cmd3);
        }

        @Test
        void respectsMaxCommands() {
            UUID runnerId = connectRunnerWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
            submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
            submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);

            BridgeCommandBatchResponse batch = connectBridgeService
                    .nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 2).block();

            assertThat(batch.commands()).hasSize(2);

            RList<String> pending = stringRedis.getList("opik:runners:bridge:" + runnerId + ":pending");
            assertThat(pending.size()).isEqualTo(1);
        }

        @Test
        void noPending_blocksAndReturnsEmpty() {
            runnerConfig.setBridgePollTimeout(Duration.seconds(1));
            try {
                UUID runnerId = connectRunnerWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

                BridgeCommandBatchResponse batch = connectBridgeService
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
            UUID runnerId = connectRunnerWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
            connectBridgeService.nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

            ObjectNode resultNode = MAPPER.createObjectNode().put("content", "file contents");
            connectBridgeService.reportBridgeCommandResult(runnerId, WORKSPACE_ID, USER_NAME, commandId,
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
            UUID runnerId = connectRunnerWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
            connectBridgeService.nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

            ObjectNode errorNode = MAPPER.createObjectNode()
                    .put("code", "file_not_found")
                    .put("message", "File not found");
            connectBridgeService.reportBridgeCommandResult(runnerId, WORKSPACE_ID, USER_NAME, commandId,
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
            UUID runnerId = connectRunnerWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
            connectBridgeService.nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

            connectBridgeService.reportBridgeCommandResult(runnerId, WORKSPACE_ID, USER_NAME, commandId,
                    BridgeCommandResultRequest.builder().status(BridgeCommandStatus.COMPLETED)
                            .result(MAPPER.createObjectNode()).build());

            assertThatThrownBy(() -> connectBridgeService.reportBridgeCommandResult(runnerId, WORKSPACE_ID, USER_NAME,
                    commandId, BridgeCommandResultRequest.builder().status(BridgeCommandStatus.COMPLETED)
                            .result(MAPPER.createObjectNode()).build()))
                    .isExactlyInstanceOf(ClientErrorException.class)
                    .hasMessageContaining("409");
        }

        @Test
        void commandNotOwned_throws404() {
            UUID runnerId = connectRunnerWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
            connectBridgeService.nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

            UUID otherRunner = UUID.randomUUID();
            assertThatThrownBy(
                    () -> connectBridgeService.reportBridgeCommandResult(otherRunner, WORKSPACE_ID, USER_NAME,
                            commandId, BridgeCommandResultRequest.builder().status(BridgeCommandStatus.COMPLETED)
                                    .result(MAPPER.createObjectNode()).build()))
                    .isExactlyInstanceOf(NotFoundException.class);
        }

        @Test
        void writesDoneSentinel() {
            UUID runnerId = connectRunnerWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
            connectBridgeService.nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

            connectBridgeService.reportBridgeCommandResult(runnerId, WORKSPACE_ID, USER_NAME, commandId,
                    BridgeCommandResultRequest.builder().status(BridgeCommandStatus.COMPLETED)
                            .result(MAPPER.createObjectNode()).build());

            RList<String> doneQueue = stringRedis.getList("opik:runners:bridge:command:" + commandId + ":done");
            assertThat(doneQueue.size()).isGreaterThan(0);
        }

        @Test
        void rejectsNonReportableStatus_PENDING() {
            UUID runnerId = connectRunnerWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
            connectBridgeService.nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

            assertThatThrownBy(() -> connectBridgeService.reportBridgeCommandResult(runnerId, WORKSPACE_ID, USER_NAME,
                    commandId, BridgeCommandResultRequest.builder().status(BridgeCommandStatus.PENDING).build()))
                    .isExactlyInstanceOf(ClientErrorException.class)
                    .hasMessageContaining("400");
        }

        @Test
        void rejectsNonReportableStatus_TIMED_OUT() {
            UUID runnerId = connectRunnerWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
            connectBridgeService.nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

            assertThatThrownBy(() -> connectBridgeService.reportBridgeCommandResult(runnerId, WORKSPACE_ID, USER_NAME,
                    commandId,
                    BridgeCommandResultRequest.builder().status(BridgeCommandStatus.TIMED_OUT).build()))
                    .isExactlyInstanceOf(ClientErrorException.class)
                    .hasMessageContaining("400");
        }

        @Test
        void resultPayloadTooLarge_throws400() {
            runnerConfig.setBridgeMaxPayloadBytes(50);
            try {
                UUID runnerId = connectRunnerWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
                UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
                connectBridgeService.nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

                StringBuilder largeValue = new StringBuilder();
                for (int i = 0; i < 200; i++) {
                    largeValue.append("x");
                }
                ObjectNode bigResult = MAPPER.createObjectNode().put("data", largeValue.toString());

                assertThatThrownBy(() -> connectBridgeService.reportBridgeCommandResult(runnerId, WORKSPACE_ID,
                        USER_NAME,
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
            UUID runnerId = connectRunnerWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
            connectBridgeService.nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

            ObjectNode resultNode = MAPPER.createObjectNode().put("content", "data");
            connectBridgeService.reportBridgeCommandResult(runnerId, WORKSPACE_ID, USER_NAME, commandId,
                    BridgeCommandResultRequest.builder()
                            .status(BridgeCommandStatus.COMPLETED)
                            .result(resultNode)
                            .build());

            BridgeCommand cmd = connectBridgeService.awaitBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME,
                    commandId, 5).block();

            assertThat(cmd.status()).isEqualTo(BridgeCommandStatus.COMPLETED);
            assertThat(cmd.result().get("content").asText()).isEqualTo("data");
        }

        @Test
        void pendingThenCompleted_blocksAndReturns() {
            UUID runnerId = connectRunnerWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
            connectBridgeService.nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

            Thread reporter = new Thread(() -> {
                try {
                    Thread.sleep(500);
                    connectBridgeService.reportBridgeCommandResult(runnerId, WORKSPACE_ID, USER_NAME, commandId,
                            BridgeCommandResultRequest.builder().status(BridgeCommandStatus.COMPLETED)
                                    .result(MAPPER.createObjectNode()).build());
                } catch (InterruptedException ignored) {
                }
            });
            reporter.start();

            BridgeCommand cmd = connectBridgeService.awaitBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME,
                    commandId, 10).block();

            assertThat(cmd.status()).isEqualTo(BridgeCommandStatus.COMPLETED);
        }

        @Test
        void timeout_returnsNonTerminal() {
            UUID runnerId = connectRunnerWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);

            BridgeCommand cmd = connectBridgeService.awaitBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME,
                    commandId, 1).block();

            assertThat(cmd.status()).isEqualTo(BridgeCommandStatus.PENDING);
        }

        @Test
        void noWait_returnsCurrentState() {
            UUID runnerId = connectRunnerWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);

            BridgeCommand cmd = connectBridgeService.getBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME, commandId);

            assertThat(cmd.status()).isEqualTo(BridgeCommandStatus.PENDING);
            assertThat(cmd.commandId()).isEqualTo(commandId);
            assertThat(cmd.type()).isEqualTo(BridgeCommandType.READ_FILE);
        }

        @Test
        void timeout_doesNotCreateOrphanedDoneQueueKey() {
            UUID runnerId = connectRunnerWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);

            connectBridgeService.awaitBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME, commandId, 1).block();

            // Redis BLPOP does not create the key on timeout — verify no orphaned done queue
            RList<String> doneList = stringRedis.getList("opik:runners:bridge:command:" + commandId + ":done");
            assertThat(doneList.isExists()).isFalse();
        }

        @Test
        void doneQueueTtl_isShort() {
            UUID runnerId = connectRunnerWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
            connectBridgeService.nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

            connectBridgeService.reportBridgeCommandResult(runnerId, WORKSPACE_ID, USER_NAME, commandId,
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
            UUID runnerId = connectRunnerWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
            connectBridgeService.nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

            java.util.concurrent.atomic.AtomicInteger successes = new java.util.concurrent.atomic.AtomicInteger(0);
            java.util.concurrent.atomic.AtomicInteger conflicts = new java.util.concurrent.atomic.AtomicInteger(0);
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

            Runnable reporter = () -> {
                try {
                    latch.await();
                    connectBridgeService.reportBridgeCommandResult(runnerId, WORKSPACE_ID, USER_NAME, commandId,
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
            UUID runnerId = connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            runnerService.heartbeat(runnerId, WORKSPACE_ID, USER_NAME, List.of("jobs", "bridge"));

            RMap<String, String> runnerMap = stringRedis.getMap("opik:runners:runner:" + runnerId);
            assertThat(runnerMap.get("capabilities")).isEqualTo("jobs,bridge");
        }

        @Test
        void defaultsToJobsWithoutCapabilities() {
            UUID runnerId = connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            runnerService.heartbeat(runnerId, WORKSPACE_ID, USER_NAME, null);

            LocalRunner runner = runnerService.getRunner(WORKSPACE_ID, USER_NAME, runnerId);
            assertThat(runner.capabilities()).containsExactly("jobs");
        }

        @Test
        void getRunnerIncludesCapabilities() {
            UUID runnerId = connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            runnerService.heartbeat(runnerId, WORKSPACE_ID, USER_NAME, List.of("jobs", "bridge"));

            LocalRunner runner = runnerService.getRunner(WORKSPACE_ID, USER_NAME, runnerId);
            assertThat(runner.capabilities()).containsExactly("jobs", "bridge");
        }
    }

    @Nested
    class RunnerChecklist {

        @Test
        void patchAndGetChecklist() {
            UUID runnerId = connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

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
            UUID runnerId = connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

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
            UUID runnerId = connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
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
            UUID runnerId = connectRunner(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

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
                UUID runnerId = connectRunnerWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
                UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
                connectBridgeService.nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

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
                UUID runnerId = connectRunnerWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
                UUID commandId = submitTestBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME);
                connectBridgeService.nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

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
            UUID runnerId = connectRunnerWithBridge(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            stubNextId();
            BridgeCommandSubmitRequest req = BridgeCommandSubmitRequest.builder()
                    .type(BridgeCommandType.READ_FILE)
                    .args(MAPPER.createObjectNode().put("path", "f.py"))
                    .timeoutSeconds(1)
                    .build();
            UUID commandId = connectBridgeService.createBridgeCommand(runnerId, WORKSPACE_ID, USER_NAME, req);
            connectBridgeService.nextBridgeCommands(runnerId, WORKSPACE_ID, USER_NAME, 10).block();

            RMap<String, String> cmdMap = stringRedis.getMap("opik:runners:bridge:command:" + commandId);
            cmdMap.put("picked_up_at", Instant.now().minusSeconds(60).toString());

            connectBridgeService.reapStaleBridgeCommands(runnerId);

            assertThat(cmdMap.get("status")).isEqualTo("timed_out");
        }
    }
}
