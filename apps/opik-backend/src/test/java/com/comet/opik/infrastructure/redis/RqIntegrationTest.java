package com.comet.opik.infrastructure.redis;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.QueuesConfig;
import com.comet.opik.infrastructure.queues.Job;
import com.comet.opik.infrastructure.queues.Queue;
import io.dropwizard.util.Duration;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.Redisson;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.config.Config;

import java.util.List;
import java.util.Map;

/**
 * Integration test for RQ publisher.
 *
 * Run this test with Redis and Python RQ worker running.
 *
 * Prerequisites:
 * - Redis running on localhost:6379 with password 'opik'
 * - Python RQ worker listening on 'opik:optimizer-cloud' queue
 */
@Slf4j
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
class RqIntegrationTest {

    private RqPublisher publisher;
    private RedissonReactiveClient redisClient;

    @BeforeEach
    void setUp() {
        // Configure Redisson
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://localhost:6379")
                .setPassword("opik")
                .setDatabase(0);

        // Create both sync and reactive clients
        var syncRedisClient = Redisson.create(config);
        redisClient = syncRedisClient.reactive();

        // Configure OpikConfiguration
        OpikConfiguration opikConfig = new OpikConfiguration();
        QueuesConfig queuesConfig = new QueuesConfig();
        queuesConfig.setEnabled(true);
        queuesConfig.setDefaultJobTtl(Duration.hours(1));

        opikConfig.setQueues(queuesConfig);

        // Create publisher (needs both reactive and sync clients)
        publisher = new RqPublisher(redisClient, opikConfig);
    }

    @Test
    void testSendMessageToQueue() throws InterruptedException {
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("Starting RQ Integration Test");
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        // Send 5 test messages with different wait times
        for (int i = 1; i <= 5; i++) {
            String testMessage = "Integration test message " + i;
            int waitSeconds = i * 2; // 2, 4, 6, 8, 10 seconds

            log.info("Sending message {}: '{}' (will sleep for {}s)", i, testMessage, waitSeconds);

            // Create Job with function and arguments
            // RQ format: args are positional parameters passed to the function
            Job job = Job.builder()
                    .func("opik_backend.rq_worker.process_hello_world")
                    .args(List.of(Map.of(
                            "message", testMessage,
                            "wait_seconds", waitSeconds)))
                    .kwargs(Map.of())
                    .build();

            // Enqueue job using the simple method
            String jobId = publisher.enqueueJob(Queue.OPTIMIZER_CLOUD.toString(), job).block();

            log.info("✅ Message {} enqueued with job ID: {}", i, jobId);

            // Small delay between messages
            Thread.sleep(500);
        }

        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("All messages sent! Checking queue size...");
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        // Check queue size
        Integer queueSize = publisher.getQueueSize(Queue.OPTIMIZER_CLOUD.toString()).block();
        log.info("Current queue size: {}", queueSize);

        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("Test complete! Check Python RQ worker logs for processing.");
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }
}
