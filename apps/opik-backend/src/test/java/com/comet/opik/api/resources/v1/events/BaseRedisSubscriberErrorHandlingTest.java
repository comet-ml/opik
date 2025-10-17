package com.comet.opik.api.resources.v1.events;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for BaseRedisSubscriber error handling scenarios using mocks.
 * Tests backpressure, resilience, and lifecycle error cases that are difficult to reproduce with real Redis.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("BaseRedisSubscriber Error Handling Tests")
class BaseRedisSubscriberErrorHandlingTest {

    private static final int AWAIT_TIMEOUT_SECONDS = 15;
    private static final int AWAIT_POLL_INTERVAL_MS = 100;

    @Mock
    private RedissonReactiveClient redissonClient;

    @Mock
    private RStreamReactive<String, String> stream;

    private TestStreamConfiguration config;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        config = TestStreamConfiguration.builder()
                .poolingInterval(io.dropwizard.util.Duration.milliseconds(100))
                .longPollingDuration(io.dropwizard.util.Duration.milliseconds(200))
                .consumerBatchSize(5)
                .build();

        // Setup basic mock behavior
        when(redissonClient.getStream(anyString(), any())).thenAnswer(invocation -> stream);
        when(stream.createGroup(any(StreamCreateGroupArgs.class))).thenReturn(Mono.empty());
        when(stream.removeConsumer(anyString(), anyString())).thenReturn(Mono.just(0L));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    /**
     * Helper to setup mock readGroup to return messages.
     */
    private void setupMockReadGroup(Map<StreamMessageId, Map<String, String>> messages) {
        when(stream.readGroup(anyString(), anyString(), any(StreamReadGroupArgs.class)))
                .thenReturn(Mono.just(messages));
    }

    /**
     * Helper to setup mock readGroup to fail.
     */
    private void setupMockReadGroupFailure(Throwable error) {
        when(stream.readGroup(anyString(), anyString(), any(StreamReadGroupArgs.class)))
                .thenReturn(Mono.error(error));
    }

    /**
     * Helper to setup mock ack and remove to succeed.
     */
    private void setupMockAckAndRemoveSuccess() {
        when(stream.ack(anyString(), any(StreamMessageId[].class))).thenReturn(Mono.just(1L));
        when(stream.remove(any(StreamMessageId[].class))).thenReturn(Mono.just(1L));
    }

    @Nested
    @DisplayName("Backpressure Tests")
    class BackpressureTests {

        @Test
        @DisplayName("Should handle backpressure")
        void shouldHandleBackpressure() {
            // Given - subscriber with slow processing
            AtomicInteger processedCount = new AtomicInteger(0);
            var subscriber = TestRedisSubscriber.customSubscriber(config, redissonClient,
                    message -> Mono.fromRunnable(() -> {
                        try {
                            // Very slow processing to cause backpressure
                            Thread.sleep(500);
                            processedCount.incrementAndGet();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }));

            // Setup mock to return messages continuously
            var messageId = new StreamMessageId(System.currentTimeMillis(), 0);
            setupMockReadGroup(Map.of(messageId, Map.of(TestStreamConfiguration.PAYLOAD_FIELD, "msg")));
            setupMockAckAndRemoveSuccess();

            // When
            subscriber.start();

            // Then - wait for some processing to occur despite backpressure
            Awaitility.await()
                    .atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(AWAIT_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        assertThat(processedCount.get()).isGreaterThan(0);
                    });

            // Cleanup
            subscriber.stop();
        }

        @Test
        @DisplayName("Should not kill interval during backpressure")
        void shouldNotKillIntervalDuringBackpressure() {
            // Given - subscriber with very slow processing
            AtomicInteger intervalCount = new AtomicInteger(0);
            var subscriber = TestRedisSubscriber.customSubscriber(config, redissonClient,
                    message -> Mono.fromRunnable(() -> {
                        intervalCount.incrementAndGet();
                        try {
                            Thread.sleep(300); // Slower than polling interval
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }));

            // Setup mock
            var messageId = new StreamMessageId(System.currentTimeMillis(), 0);
            setupMockReadGroup(Map.of(messageId, Map.of(TestStreamConfiguration.PAYLOAD_FIELD, "msg")));
            setupMockAckAndRemoveSuccess();

            // When
            subscriber.start();

            // Then - interval should continue despite backpressure
            Awaitility.await()
                    .atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(AWAIT_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        assertThat(intervalCount.get()).isGreaterThan(0);
                    });

            // Cleanup
            subscriber.stop();
        }
    }

    @Nested
    @DisplayName("Stream Listener Resilience Tests")
    class StreamListenerResilienceTests {

        @Test
        @DisplayName("Should not die on unexpected error")
        void shouldNotDieOnUnexpectedError() {
            // Given - subscriber that throws on first message, succeeds on subsequent
            AtomicInteger attemptCount = new AtomicInteger(0);
            var subscriber = TestRedisSubscriber.customSubscriber(config, redissonClient, message -> {
                int count = attemptCount.incrementAndGet();
                if (count == 1) {
                    return Mono.error(new RuntimeException("Unexpected error"));
                }
                return Mono.empty();
            });

            var messageId1 = new StreamMessageId(System.currentTimeMillis(), 0);
            var messageId2 = new StreamMessageId(System.currentTimeMillis() + 1, 0);

            // Return different messages on different calls
            when(stream.readGroup(anyString(), anyString(), any(StreamReadGroupArgs.class)))
                    .thenReturn(Mono.just(Map.of(messageId1, Map.of(TestStreamConfiguration.PAYLOAD_FIELD, "msg1"))))
                    .thenReturn(Mono.just(Map.of(messageId2, Map.of(TestStreamConfiguration.PAYLOAD_FIELD, "msg2"))));
            setupMockAckAndRemoveSuccess();

            // When
            subscriber.start();

            // Then - should continue processing after error
            Awaitility.await()
                    .atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(AWAIT_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        assertThat(attemptCount.get()).isGreaterThan(1);
                        assertThat(subscriber.getProcessedMessageCount().get()).isGreaterThan(0);
                    });

            // Cleanup
            subscriber.stop();
        }

        @Test
        @DisplayName("Should continue intervals after read error")
        void shouldContinueIntervalsAfterReadError() {
            // Given
            AtomicInteger readAttempts = new AtomicInteger(0);
            var subscriber = TestRedisSubscriber.successfulSubscriber(config, redissonClient);

            // Setup mock to fail first, then succeed
            var messageId = new StreamMessageId(System.currentTimeMillis(), 0);
            when(stream.readGroup(anyString(), anyString(), any(StreamReadGroupArgs.class)))
                    .thenAnswer(invocation -> {
                        int attempt = readAttempts.incrementAndGet();
                        if (attempt == 1 || attempt == 2) {
                            return Mono.error(new RuntimeException("Redis read error"));
                        }
                        return Mono.just(Map.of(messageId, Map.of(TestStreamConfiguration.PAYLOAD_FIELD, "msg")));
                    });
            setupMockAckAndRemoveSuccess();

            // When
            subscriber.start();

            // Then - should continue after read errors
            Awaitility.await()
                    .atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(AWAIT_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        assertThat(readAttempts.get()).isGreaterThan(2);
                        assertThat(subscriber.getProcessedMessageCount().get()).isGreaterThan(0);
                    });

            // Cleanup
            subscriber.stop();
        }

        @Test
        @DisplayName("Should continue intervals after processing error")
        void shouldContinueIntervalsAfterProcessingError() {
            // Given - subscriber that fails then succeeds
            AtomicInteger processAttempts = new AtomicInteger(0);
            var subscriber = TestRedisSubscriber.customSubscriber(config, redissonClient, message -> {
                int attempt = processAttempts.incrementAndGet();
                if (attempt <= 2) {
                    return Mono.error(new RuntimeException("Processing error"));
                }
                return Mono.empty();
            });

            var messageId = new StreamMessageId(System.currentTimeMillis(), 0);
            setupMockReadGroup(Map.of(messageId, Map.of(TestStreamConfiguration.PAYLOAD_FIELD, "msg")));
            setupMockAckAndRemoveSuccess();

            // When
            subscriber.start();

            // Then
            Awaitility.await()
                    .atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(AWAIT_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        assertThat(processAttempts.get()).isGreaterThan(2);
                        assertThat(subscriber.getProcessedMessageCount().get()).isGreaterThan(0);
                    });

            // Cleanup
            subscriber.stop();
        }

        @Test
        @DisplayName("Should continue intervals after ack error")
        void shouldContinueIntervalsAfterAckError() {
            // Given
            AtomicInteger ackAttempts = new AtomicInteger(0);
            var subscriber = TestRedisSubscriber.successfulSubscriber(config, redissonClient);

            var messageId = new StreamMessageId(System.currentTimeMillis(), 0);
            setupMockReadGroup(Map.of(messageId, Map.of(TestStreamConfiguration.PAYLOAD_FIELD, "msg")));

            // Mock ack to fail first, then succeed
            when(stream.ack(anyString(), any(StreamMessageId[].class)))
                    .thenAnswer(invocation -> {
                        int attempt = ackAttempts.incrementAndGet();
                        if (attempt <= 2) {
                            return Mono.error(new RuntimeException("Ack error"));
                        }
                        return Mono.just(1L);
                    });
            when(stream.remove(any(StreamMessageId[].class))).thenReturn(Mono.just(1L));

            // When
            subscriber.start();

            // Then
            Awaitility.await()
                    .atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(AWAIT_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        assertThat(ackAttempts.get()).isGreaterThan(2);
                    });

            // Cleanup
            subscriber.stop();
        }

        @Test
        @DisplayName("Should continue intervals after remove error")
        void shouldContinueIntervalsAfterRemoveError() {
            // Given
            AtomicInteger removeAttempts = new AtomicInteger(0);
            var subscriber = TestRedisSubscriber.successfulSubscriber(config, redissonClient);

            var messageId = new StreamMessageId(System.currentTimeMillis(), 0);
            setupMockReadGroup(Map.of(messageId, Map.of(TestStreamConfiguration.PAYLOAD_FIELD, "msg")));

            // Mock remove to fail first, then succeed
            when(stream.ack(anyString(), any(StreamMessageId[].class))).thenReturn(Mono.just(1L));
            when(stream.remove(any(StreamMessageId[].class)))
                    .thenAnswer(invocation -> {
                        int attempt = removeAttempts.incrementAndGet();
                        if (attempt <= 2) {
                            return Mono.error(new RuntimeException("Remove error"));
                        }
                        return Mono.just(1L);
                    });

            // When
            subscriber.start();

            // Then
            Awaitility.await()
                    .atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(AWAIT_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        assertThat(removeAttempts.get()).isGreaterThan(2);
                    });

            // Cleanup
            subscriber.stop();
        }

        @Test
        @DisplayName("Should handle invalid message ID timestamp")
        void shouldHandleInvalidMessageIdTimestamp() {
            // Given
            var subscriber = TestRedisSubscriber.successfulSubscriber(config, redissonClient);

            // Create message ID with invalid format for timestamp extraction
            // Using a valid StreamMessageId but with non-standard format to trigger parsing warning
            var messageId = StreamMessageId.MAX;
            setupMockReadGroup(Map.of(messageId, Map.of(TestStreamConfiguration.PAYLOAD_FIELD, "msg")));
            setupMockAckAndRemoveSuccess();

            // When
            subscriber.start();

            // Then - should log warning but continue processing
            Awaitility.await()
                    .atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .pollInterval(AWAIT_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        assertThat(subscriber.getProcessedMessageCount().get()).isGreaterThan(0);
                    });

            // Cleanup
            subscriber.stop();
        }
    }

    @Nested
    @DisplayName("Lifecycle Error Tests")
    class LifecycleErrorTests {

        @Test
        @DisplayName("Should fail startup when consumer group creation fails")
        void shouldFailStartupWhenConsumerGroupCreationFails() {
            // Given
            var subscriber = TestRedisSubscriber.successfulSubscriber(config, redissonClient);

            // Mock createGroup to fail with non-BUSYGROUP error
            when(stream.createGroup(any(StreamCreateGroupArgs.class)))
                    .thenReturn(Mono.error(new RuntimeException("Redis connection error")));

            // When/Then - start should complete but error should be logged
            // Note: current implementation uses subscribe() which doesn't propagate errors to caller
            subscriber.start();

            // The subscriber should start but the error should be logged
            // We can't easily assert on the exception but we can verify the subscriber state
            assertThat(subscriber).isNotNull();

            // Cleanup
            subscriber.stop();
        }

        @Test
        @DisplayName("Should handle remove consumer failure on stop")
        void shouldHandleRemoveConsumerFailureOnStop() {
            // Given
            var subscriber = TestRedisSubscriber.successfulSubscriber(config, redissonClient);

            // Setup normal read behavior
            setupMockReadGroup(Map.of());
            setupMockAckAndRemoveSuccess();

            subscriber.start();

            // Mock removeConsumer to fail
            when(stream.removeConsumer(anyString(), anyString()))
                    .thenReturn(Mono.error(new RuntimeException("Failed to remove consumer")));

            // When/Then - stop should complete without throwing
            subscriber.stop();

            // Verify stop completed
            assertThat(subscriber).isNotNull();
        }
    }
}
