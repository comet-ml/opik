package com.comet.opik.domain;

import com.comet.opik.api.Project;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.runner.CreateLocalRunnerJobRequest;
import com.comet.opik.api.runner.LocalRunner;
import com.comet.opik.api.runner.LocalRunnerConnectRequest;
import com.comet.opik.api.runner.LocalRunnerConnectResponse;
import com.comet.opik.api.runner.LocalRunnerJobResultRequest;
import com.comet.opik.api.runner.LocalRunnerJobStatus;
import com.comet.opik.api.runner.LocalRunnerPairResponse;
import com.comet.opik.infrastructure.LocalRunnerConfig;
import com.comet.opik.infrastructure.redis.StringRedisClient;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.util.Duration;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalRunnerReaperIntegrationTest {

    private static final String WORKSPACE_ID = "00000000-0000-0000-0000-000000000001";
    private static final String USER_NAME = "test-user";
    private static final String RUNNER_NAME = "my-runner";
    private static final String AGENT_NAME = "test-agent";
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final String PROJECT_NAME = "test-project";

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
        runnerConfig.setDeadRunnerPurgeTime(Duration.seconds(0));
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
    class ReapDeadRunners {

        @Test
        void failsOrphanedActiveJobs() throws InterruptedException {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).block();

            waitForHeartbeatExpiry();
            runnerService.reapDeadRunners();

            RMap<String, String> jobMap = stringRedis.getMap(
                    "opik:runners:job:" + jobId);
            assertThat(jobMap.get("status")).isEqualTo("failed");
            assertThat(jobMap.get("error")).isEqualTo("Runner disconnected");
        }

        @Test
        void failsOrphanedPendingJobs() throws InterruptedException {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            waitForHeartbeatExpiry();
            runnerService.reapDeadRunners();

            RMap<String, String> jobMap = stringRedis.getMap(
                    "opik:runners:job:" + jobId);
            assertThat(jobMap.get("status")).isEqualTo("failed");
            assertThat(jobMap.get("error")).isEqualTo("Runner disconnected");
        }

        @Test
        void purgesLongDeadRunners() throws InterruptedException {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            waitForHeartbeatExpiry();
            runnerService.reapDeadRunners();

            RMap<String, String> runnerMap = stringRedis.getMap(
                    "opik:runners:runner:" + runnerId);
            assertThat(runnerMap.isExists()).isFalse();

            RBucket<String> hb = stringRedis.getBucket(
                    "opik:runners:runner:" + runnerId + ":heartbeat");
            assertThat(hb.isExists()).isFalse();

            RScoredSortedSet<String> wsRunners = stringRedis.getScoredSortedSet(
                    "opik:runners:workspace:" + WORKSPACE_ID + ":runners");
            assertThat(wsRunners.contains(runnerId.toString())).isFalse();
        }

        @Test
        void skipsAliveRunners() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            runnerService.reapDeadRunners();

            RMap<String, String> runnerMap = stringRedis.getMap(
                    "opik:runners:runner:" + runnerId);
            assertThat(runnerMap.isExists()).isTrue();
        }

        @Test
        void removesEmptyWorkspace() throws InterruptedException {
            pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            waitForHeartbeatExpiry();
            runnerService.reapDeadRunners();

            RSet<String> workspaces = stringRedis.getSet(
                    "opik:runners:workspaces:with_runners");
            assertThat(workspaces.contains(WORKSPACE_ID)).isFalse();
        }

        @Test
        void handlesErrorPerRunner() throws InterruptedException {
            UUID runner1 = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            UUID runner2 = pairAndConnect(WORKSPACE_ID, "user2", "runner2");

            waitForHeartbeatExpiry();
            runnerService.reapDeadRunners();

            RMap<String, String> map1 = stringRedis.getMap(
                    "opik:runners:runner:" + runner1);
            RMap<String, String> map2 = stringRedis.getMap(
                    "opik:runners:runner:" + runner2);
            assertThat(map1.isExists()).isFalse();
            assertThat(map2.isExists()).isFalse();
        }

        @Test
        void cleansRunnerJobsSetOnReap() throws InterruptedException {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
            createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);

            waitForHeartbeatExpiry();
            runnerService.reapDeadRunners();

            RScoredSortedSet<String> runnerJobs = stringRedis.getScoredSortedSet(
                    "opik:runners:runner:" + runnerId + ":jobs");
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

                RMap<String, String> runnerMap = stringRedis.getMap(
                        "opik:runners:runner:" + runnerId);
                assertThat(runnerMap.get("disconnected_at")).isNotBlank();
            } finally {
                runnerConfig.setDeadRunnerPurgeTime(originalPurgeTime);
            }
        }
    }

    @Nested
    class ReapStuckJobs {

        @Test
        void failsJobExceedingPerJobTimeout() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            LocalRunner.Agent agent = LocalRunner.Agent.builder().timeout(60).build();
            runnerService.registerAgents(runnerId, WORKSPACE_ID, USER_NAME, Map.of(AGENT_NAME, agent));

            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).block();

            RMap<String, String> jobMap = stringRedis.getMap(
                    "opik:runners:job:" + jobId);
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
            runnerService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).block();

            runnerService.reapDeadRunners();

            RMap<String, String> jobMap = stringRedis.getMap(
                    "opik:runners:job:" + jobId);
            assertThat(jobMap.get("status")).isEqualTo("running");
        }

        @Test
        void usesConfigDefaultWhenJobHasNoTimeout() {
            Duration originalTimeout = runnerConfig.getJobTimeout();
            runnerConfig.setJobTimeout(Duration.seconds(10));
            try {
                UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);
                UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
                runnerService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).block();

                RMap<String, String> jobMap = stringRedis.getMap(
                        "opik:runners:job:" + jobId);
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
            runnerService.registerAgents(runnerId, WORKSPACE_ID, USER_NAME, Map.of(AGENT_NAME, agent));

            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).block();

            RMap<String, String> jobMap = stringRedis.getMap(
                    "opik:runners:job:" + jobId);
            jobMap.put("started_at", Instant.now().minus(java.time.Duration.ofSeconds(30)).toString());

            runnerService.reapDeadRunners();

            RList<String> activeJobs = stringRedis.getList(
                    "opik:runners:jobs:" + runnerId + ":active");
            assertThat(activeJobs.readAll()).doesNotContain(jobId.toString());
        }

        @Test
        void reapsStuckJobsOnAliveRunners() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            LocalRunner.Agent agent = LocalRunner.Agent.builder().timeout(5).build();
            runnerService.registerAgents(runnerId, WORKSPACE_ID, USER_NAME, Map.of(AGENT_NAME, agent));

            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).block();

            RMap<String, String> jobMap = stringRedis.getMap(
                    "opik:runners:job:" + jobId);
            jobMap.put("started_at", Instant.now().minus(java.time.Duration.ofSeconds(30)).toString());

            runnerService.reapDeadRunners();

            assertThat(jobMap.get("status")).isEqualTo("failed");
            assertThat(jobMap.get("error")).contains("timed out");
        }

        @Test
        void doesNotReapAlreadyCompletedJob() {
            UUID runnerId = pairAndConnect(WORKSPACE_ID, USER_NAME, RUNNER_NAME);

            LocalRunner.Agent agent = LocalRunner.Agent.builder().timeout(5).build();
            runnerService.registerAgents(runnerId, WORKSPACE_ID, USER_NAME, Map.of(AGENT_NAME, agent));

            UUID jobId = createTestJob(WORKSPACE_ID, USER_NAME, AGENT_NAME);
            runnerService.nextJob(runnerId, WORKSPACE_ID, USER_NAME).block();

            runnerService.reportResult(jobId, WORKSPACE_ID, USER_NAME,
                    LocalRunnerJobResultRequest.builder().status(LocalRunnerJobStatus.COMPLETED).build());

            RMap<String, String> jobMap = stringRedis.getMap(
                    "opik:runners:job:" + jobId);
            jobMap.put("started_at", Instant.now().minus(java.time.Duration.ofSeconds(30)).toString());

            runnerService.reapDeadRunners();

            assertThat(jobMap.get("status")).isEqualTo("completed");
        }
    }
}
