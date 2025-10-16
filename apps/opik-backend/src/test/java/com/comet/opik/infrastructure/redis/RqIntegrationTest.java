package com.comet.opik.infrastructure.redis;

import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.QueuesConfig;
import com.comet.opik.infrastructure.queues.Job;
import com.comet.opik.infrastructure.queues.JobStatus;
import com.comet.opik.infrastructure.queues.Queue;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.redisson.Redisson;
import org.redisson.api.RMapReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Integration test for RQ publisher.
 *
 * Run this test with Redis and Python RQ worker running.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RqIntegrationTest {

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();

    private RqPublisher publisher;
    private RedissonReactiveClient redisClient;

    @BeforeAll
    void setUp() {
        redis.start();

        // Configure Redisson
        Config config = new Config();
        config.useSingleServer()
                .setAddress(redis.getRedisURI())
                .setDatabase(0);

        // Create both sync and reactive clients
        redisClient = Redisson.create(config).reactive();

        // Configure OpikConfiguration
        OpikConfiguration opikConfig = new OpikConfiguration();
        QueuesConfig queuesConfig = new QueuesConfig();
        queuesConfig.setEnabled(true);
        queuesConfig.setDefaultJobTtl(io.dropwizard.util.Duration.hours(1));

        opikConfig.setQueues(queuesConfig);

        IdGenerator idGenerator = Mockito.mock(IdGenerator.class);
        Mockito.when(idGenerator.generateId()).thenReturn(UUID.randomUUID());

        // Create publisher (needs both reactive and sync clients)
        publisher = new RqPublisher(redisClient, opikConfig, idGenerator);
    }

    @AfterAll
    void tearDown() {
        redis.stop();
    }

    @BeforeEach
    void clearDatabase() {
        redisClient.getKeys().flushdb().block();
    }

    @Test
    void testSendMessageToQueue() {
        // Send 5 test messages with different wait times
        int messages = 5;

        // Create Job with function and arguments
        // RQ format: args are positional parameters passed to the function
        // Enqueue job using the simple method
        // Small delay between messages
        IntStream.range(0, messages).forEach(i -> {

            String testMessage = "Integration test message " + i;
            int waitSeconds = i * 2; // 2, 4, 6, 8, 10 seconds

            Job job = Job.builder()
                    .func("opik_backend.rq_worker.process_optimizer_job")
                    .args(List.of(Map.of(
                            "message", testMessage,
                            "wait_seconds", waitSeconds)))
                    .kwargs(Map.of())
                    .build();

            publisher.enqueueJob(Queue.OPTIMIZER_CLOUD.toString(), job).block();
        });

        // Check queue size
        Integer queueSize = publisher.getQueueSize(Queue.OPTIMIZER_CLOUD.toString()).block();
        Assertions.assertThat(queueSize).isEqualTo(messages);
    }

    @Test
    void testRedisDataFormat() {
        // Send a test message
        String testMessage = RandomStringUtils.secure().nextAlphabetic(10);
        int waitSeconds = 5;

        Job job = Job.builder()
                .func(Queue.OPTIMIZER_CLOUD.getFunctionName())
                .args(List.of(Map.of(
                        "message", testMessage,
                        "wait_seconds", waitSeconds)))
                .kwargs(Map.of())
                .build();

        Instant startedAt = Instant.now();

        String jobId = publisher.enqueueJob(Queue.OPTIMIZER_CLOUD.toString(), job).block();

        Instant now = Instant.now();

        Assertions.assertThat(jobId).isNotNull();

        String jobKey = String.format(RqPublisher.RQ_JOB, jobId);
        RMapReactive<String, Object> map = redisClient.getMap(jobKey, StringCodec.INSTANCE);
        Map<String, Object> jobHash = map.readAllMap().block();

        // Assert RqJobHash fields in Redis

        Assertions.assertThat(jobHash).isNotNull();
        Assertions.assertThat((String) jobHash.get("id"))
                .isEqualTo(jobId.replace(RqPublisher.RQ_JOB.formatted(""), ""));
        Assertions.assertThat((String) jobHash.get("status")).isEqualTo(JobStatus.QUEUED.toString());
        Assertions.assertThat((String) jobHash.get("origin")).isEqualTo(Queue.OPTIMIZER_CLOUD.toString());
        Assertions.assertThat((String) jobHash.get("data")).isNotNull();
        Assertions.assertThat(Instant.parse(jobHash.get("created_at").toString())).isBetween(startedAt, now);
        Assertions.assertThat(Instant.parse(jobHash.get("enqueued_at").toString())).isBetween(startedAt, now);
        Assertions.assertThat((String) jobHash.get("description")).isEqualTo(Queue.OPTIMIZER_CLOUD.getFunctionName());
        Assertions.assertThat((String) jobHash.get("timeout")).isEqualTo("3600"); // default
    }
}
