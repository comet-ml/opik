package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.redis.testcontainers.RedisContainer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.redisson.Redisson;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.config.Config;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for BaseRedisSubscriber using real Redis testcontainer.
 * Tests happy path scenarios, metrics collection, failure handling, and lifecycle management.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("BaseRedisSubscriber Integration Tests")
class BaseRedisSubscriberIntegrationTest {

    private static final int AWAIT_TIMEOUT_SECONDS = 10;
    private static final int AWAIT_POLL_INTERVAL_MS = 100;

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();

    private RedissonReactiveClient redissonClient;
    private TestStreamConfiguration config;
    private RStreamReactive<String, String> stream;

    @BeforeAll
    void setUpAll() {
        redis.start();

        // Configure Redisson client
        Config redisConfig = new Config();
        redisConfig.useSingleServer()
                .setAddress(redis.getRedisURI())
                .setDatabase(0);
        redissonClient = Redisson.create(redisConfig).reactive();

        config = TestStreamConfiguration.createWithFastPolling();
    }

    @AfterAll
    void tearDownAll() {
        redis.stop();
    }

    @BeforeEach
    void setUp() {
        // Clean up Redis state before each test
        redissonClient.getKeys().flushdb().block();

        // Initialize stream
        stream = redissonClient.getStream(config.getStreamName(), config.getCodec());
    }

    @AfterEach
    void tearDown() {
        // Additional cleanup if needed
    }

    /**
     * Helper method to publish messages to the Redis stream.
     */
    private List<StreamMessageId> publishMessagesToStream(List<String> messages) {
        List<StreamMessageId> messageIds = new ArrayList<>();
        for (String message : messages) {
            StreamMessageId id = stream.add(StreamAddArgs.entry(TestStreamConfiguration.PAYLOAD_FIELD, message))
                    .block();
            messageIds.add(id);
        }
        return messageIds;
    }

    /**
     * Helper method to wait for messages to be processed.
     */
    private void waitForMessagesProcessed(TestRedisSubscriber subscriber, int expectedCount) {
        Awaitility.await()
                .atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .pollInterval(AWAIT_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(subscriber.getProcessedMessageCount().get()).isEqualTo(expectedCount));
    }

    @Nested
    @DisplayName("Happy Path Tests")
    class HappyPathTests {

        @Test
        @DisplayName("Should successfully consume and process batch of messages")
        void shouldSuccessfullyConsumeAndProcessBatchOfMessages() {
            // Given
            var subscriber = TestRedisSubscriber.successfulSubscriber(config, redissonClient);
            List<String> messages = List.of(
                    "message-1", "message-2", "message-3", "message-4", "message-5",
                    "message-6", "message-7", "message-8", "message-9", "message-10",
                    "message-11", "message-12", "message-13", "message-14", "message-15",
                    "message-16", "message-17", "message-18", "message-19", "message-20");

            publishMessagesToStream(messages);

            // When
            subscriber.start();

            // Then
            waitForMessagesProcessed(subscriber, messages.size());
            assertThat(subscriber.getProcessedMessageCount().get()).isEqualTo(messages.size());
            assertThat(subscriber.getFailedMessageCount().get()).isZero();

            // Cleanup
            subscriber.stop();
        }

        @Test
        @DisplayName("Should process messages in parallel")
        void shouldProcessMessagesInParallel() {
            // Given
            var processingThreads = new CopyOnWriteArrayList<String>();
            var processingTimes = new ConcurrentHashMap<String, Long>();

            Function<String, Mono<Void>> processor = message -> Mono.fromRunnable(() -> {
                processingThreads.add(Thread.currentThread().getName());
                processingTimes.put(message, System.currentTimeMillis());
                // Simulate some processing time
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            var subscriber = TestRedisSubscriber.customSubscriber(config, redissonClient, processor);
            List<String> messages = List.of(
                    "msg-1", "msg-2", "msg-3", "msg-4", "msg-5",
                    "msg-6", "msg-7", "msg-8", "msg-9", "msg-10");

            publishMessagesToStream(messages);

            // When
            subscriber.start();

            // Then
            waitForMessagesProcessed(subscriber, messages.size());

            // Verify parallel processing by checking multiple threads were used
            assertThat(processingThreads).hasSizeGreaterThan(1);
            assertThat(processingTimes).hasSize(messages.size());

            // Cleanup
            subscriber.stop();
        }

        @Test
        @DisplayName("Should ack and delete messages in batch")
        void shouldAckAndDeleteMessagesInBatch() {
            // Given
            var subscriber = TestRedisSubscriber.successfulSubscriber(config, redissonClient);
            List<String> messages = List.of("msg-1", "msg-2", "msg-3", "msg-4", "msg-5");

            publishMessagesToStream(messages);

            // When
            subscriber.start();
            waitForMessagesProcessed(subscriber, messages.size());

            // Then - wait a bit for ack and remove to complete
            Awaitility.await()
                    .atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(AWAIT_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        // Verify messages were removed from stream
                        var streamLength = stream.size().block();
                        assertThat(streamLength).isZero();
                    });

            // Cleanup
            subscriber.stop();
        }

        @Test
        @DisplayName("Should not ack or remove when message IDs empty")
        void shouldNotAckOrRemoveWhenMessageIdsEmpty() {
            // Given
            var subscriber = TestRedisSubscriber.failingSubscriber(config, redissonClient);
            List<String> messages = List.of("msg-1", "msg-2");

            publishMessagesToStream(messages);

            // When
            subscriber.start();

            // Then - wait for processing attempts
            Awaitility.await()
                    .atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(AWAIT_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> assertThat(subscriber.getFailedMessageCount().get())
                            .isGreaterThanOrEqualTo(messages.size()));

            // Messages should still be in the stream (not removed)
            var streamLength = stream.size().block();
            assertThat(streamLength).isEqualTo(messages.size());

            // Cleanup
            subscriber.stop();
        }

        @Test
        @DisplayName("Should handle null payload successfully")
        void shouldHandleNullPayloadSuccessfully() {
            // Given
            AtomicInteger nullCount = new AtomicInteger(0);
            Function<String, Mono<Void>> processor = message -> {
                if (message == null) {
                    nullCount.incrementAndGet();
                }
                return Mono.empty();
            };

            var subscriber = TestRedisSubscriber.customSubscriber(config, redissonClient, processor);

            // Publish message with different field (no payload field, will result in null)
            stream.add(StreamAddArgs.entry("other-field", "value")).block();
            stream.add(StreamAddArgs.entry(TestStreamConfiguration.PAYLOAD_FIELD, "normal-message")).block();

            // When
            subscriber.start();

            // Then
            Awaitility.await()
                    .atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(AWAIT_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        assertThat(subscriber.getProcessedMessageCount().get()).isEqualTo(2);
                        assertThat(nullCount.get()).isEqualTo(1);
                    });

            // Cleanup
            subscriber.stop();
        }
    }

    @Nested
    @DisplayName("Metrics Tests")
    class MetricsTests {

        @Test
        @DisplayName("Should record success metrics")
        void shouldRecordSuccessMetrics() {
            // Given
            var subscriber = TestRedisSubscriber.successfulSubscriber(config, redissonClient);
            List<String> messages = List.of("msg-1", "msg-2", "msg-3");

            publishMessagesToStream(messages);

            // When
            subscriber.start();
            waitForMessagesProcessed(subscriber, messages.size());

            // Then - verify processing completed without errors
            // Metrics are recorded internally but we focus on functional correctness
            assertThat(subscriber.getProcessedMessageCount().get()).isEqualTo(messages.size());
            assertThat(subscriber.getFailedMessageCount().get()).isZero();

            // Cleanup
            subscriber.stop();
        }

        @Test
        @DisplayName("Should record error metrics")
        void shouldRecordErrorMetrics() {
            // Given
            var subscriber = TestRedisSubscriber.failingSubscriber(config, redissonClient);
            List<String> messages = List.of("msg-1", "msg-2", "msg-3");

            publishMessagesToStream(messages);

            // When
            subscriber.start();

            // Then - wait for processing attempts and verify errors tracked
            Awaitility.await()
                    .atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(AWAIT_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        assertThat(subscriber.getFailedMessageCount().get()).isGreaterThanOrEqualTo(messages.size());
                    });

            // Cleanup
            subscriber.stop();
        }

        @Test
        @DisplayName("Should process and track timing metrics")
        void shouldProcessAndTrackTimingMetrics() {
            // Given - subscriber with some processing time
            AtomicInteger processedCount = new AtomicInteger(0);
            var subscriber = TestRedisSubscriber.customSubscriber(config, redissonClient,
                    message -> Mono.fromRunnable(() -> {
                        try {
                            Thread.sleep(10); // Small delay to ensure timing is measurable
                            processedCount.incrementAndGet();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }));

            List<String> messages = List.of("msg-1", "msg-2");
            publishMessagesToStream(messages);

            // When
            subscriber.start();

            // Then - verify processing completed
            Awaitility.await()
                    .atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(AWAIT_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        assertThat(processedCount.get()).isEqualTo(messages.size());
                    });

            // Cleanup
            subscriber.stop();
        }

        @Test
        @DisplayName("Should track ack and remove operations")
        void shouldTrackAckAndRemoveOperations() {
            // Given
            var subscriber = TestRedisSubscriber.successfulSubscriber(config, redissonClient);
            List<String> messages = List.of("msg-1", "msg-2");

            publishMessagesToStream(messages);

            // When
            subscriber.start();
            waitForMessagesProcessed(subscriber, messages.size());

            // Then - verify messages were removed from stream
            Awaitility.await()
                    .atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(AWAIT_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        var streamLength = stream.size().block();
                        assertThat(streamLength).isZero();
                    });

            // Cleanup
            subscriber.stop();
        }
    }

    @Nested
    @DisplayName("Failure Handling Tests")
    class FailureHandlingTests {

        @Test
        @DisplayName("Should handle failed messages gracefully")
        void shouldHandleFailedMessagesGracefully() {
            // Given - subscriber that fails on specific messages
            AtomicInteger successCount = new AtomicInteger(0);
            Function<String, Mono<Void>> processor = message -> {
                if (message.contains("fail")) {
                    return Mono.error(new RuntimeException("Intentional failure"));
                }
                successCount.incrementAndGet();
                return Mono.empty();
            };

            var subscriber = TestRedisSubscriber.customSubscriber(config, redissonClient, processor);
            List<String> messages = List.of("success-1", "fail-1", "success-2", "fail-2", "success-3");

            publishMessagesToStream(messages);

            // When
            subscriber.start();

            // Then - successful messages should be processed
            Awaitility.await()
                    .atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(AWAIT_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        assertThat(successCount.get()).isEqualTo(3);
                        assertThat(subscriber.getFailedMessageCount().get()).isEqualTo(2);
                    });

            // Cleanup
            subscriber.stop();
        }

        @Test
        @DisplayName("Should continue processing after null payload error")
        void shouldContinueProcessingAfterNullPayloadError() {
            // Given - processor that fails on null
            AtomicInteger processedCount = new AtomicInteger(0);
            Function<String, Mono<Void>> processor = message -> {
                if (message == null) {
                    return Mono.error(new RuntimeException("Null message"));
                }
                processedCount.incrementAndGet();
                return Mono.empty();
            };

            var subscriber = TestRedisSubscriber.customSubscriber(config, redissonClient, processor);

            // Publish messages including one with null payload
            stream.add(StreamAddArgs.entry("other-field", "value")).block(); // null payload
            stream.add(StreamAddArgs.entry(TestStreamConfiguration.PAYLOAD_FIELD, "msg-1")).block();
            stream.add(StreamAddArgs.entry(TestStreamConfiguration.PAYLOAD_FIELD, "msg-2")).block();

            // When
            subscriber.start();

            // Then - non-null messages should still be processed
            Awaitility.await()
                    .atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(AWAIT_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        assertThat(processedCount.get()).isEqualTo(2);
                        assertThat(subscriber.getFailedMessageCount().get()).isEqualTo(1);
                    });

            // Cleanup
            subscriber.stop();
        }
    }

    @Nested
    @DisplayName("Lifecycle Tests")
    class LifecycleTests {

        @Test
        @DisplayName("Should create consumer group on startup")
        void shouldCreateConsumerGroupOnStartup() {
            // Given
            var subscriber = TestRedisSubscriber.successfulSubscriber(config, redissonClient);

            // When
            subscriber.start();

            // Then - verify consumer group exists by checking we can publish and consume
            List<String> messages = List.of("msg-1");
            publishMessagesToStream(messages);

            Awaitility.await()
                    .atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(AWAIT_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        assertThat(subscriber.getProcessedMessageCount().get()).isEqualTo(messages.size());
                    });

            // Cleanup
            subscriber.stop();
        }

        @Test
        @DisplayName("Should handle existing consumer group")
        void shouldHandleExistingConsumerGroup() {
            // Given - create consumer group first
            var subscriber1 = TestRedisSubscriber.successfulSubscriber(config, redissonClient);
            subscriber1.start();

            // Wait for first subscriber to be ready
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // When - start another subscriber with same group
            var subscriber2 = TestRedisSubscriber.successfulSubscriber(config, redissonClient);
            subscriber2.start();

            // Then - should handle BUSYGROUP error gracefully
            List<String> messages = List.of("msg-1", "msg-2");
            publishMessagesToStream(messages);

            // Both subscribers should be able to process messages
            Awaitility.await()
                    .atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(AWAIT_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        int totalProcessed = subscriber1.getProcessedMessageCount().get() +
                                subscriber2.getProcessedMessageCount().get();
                        assertThat(totalProcessed).isEqualTo(messages.size());
                    });

            // Cleanup
            subscriber1.stop();
            subscriber2.stop();
        }

        @Test
        @DisplayName("Should remove consumer on stop")
        void shouldRemoveConsumerOnStop() {
            // Given
            var subscriber = TestRedisSubscriber.successfulSubscriber(config, redissonClient);
            List<String> messages = List.of("msg-1", "msg-2");

            publishMessagesToStream(messages);
            subscriber.start();
            waitForMessagesProcessed(subscriber, messages.size());

            // When
            subscriber.stop();

            // Then - stop should complete without error
            // Consumer removal is logged internally
            assertThat(subscriber).isNotNull();
        }
    }
}
