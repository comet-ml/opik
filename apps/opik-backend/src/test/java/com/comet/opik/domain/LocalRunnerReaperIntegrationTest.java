package com.comet.opik.domain;

import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.runner.CreateLocalRunnerJobRequest;
import com.comet.opik.api.runner.LocalRunner;
import com.comet.opik.api.runner.LocalRunnerConnectRequest;
import com.comet.opik.api.runner.LocalRunnerJobResultRequest;
import com.comet.opik.api.runner.LocalRunnerJobStatus;
import com.comet.opik.api.runner.LocalRunnerPairResponse;
import com.comet.opik.infrastructure.LocalRunnerConfig;
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
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalRunnerReaperIntegrationTest {

    private static final String WORKSPACE_ID = "test-workspace";
    private static final String USER_NAME = "test-user";
    private static final String RUNNER_NAME = "my-runner";
    private static final String AGENT_NAME = "test-agent";

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

            RScoredSortedSet<String> wsRunners = redisClient.getScoredSortedSet(
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

            RScoredSortedSet<String> runnerJobs = redisClient.getScoredSortedSet(
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
                    LocalRunnerJobResultRequest.builder().status(LocalRunnerJobStatus.COMPLETED).build());

            RMap<String, String> jobMap = redisClient.getMap(
                    "opik:runners:job:" + jobId, StringCodec.INSTANCE);
            jobMap.put("started_at", Instant.now().minus(java.time.Duration.ofSeconds(30)).toString());

            runnerService.reapDeadRunners();

            assertThat(jobMap.get("status")).isEqualTo("completed");
        }
    }
}
