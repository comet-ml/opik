package com.comet.opik.api.resources.v1.events;

import com.comet.opik.podam.PodamFactoryUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.options.PlainOptions;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamReadGroupArgs;
import reactor.core.publisher.Mono;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BaseRedisSubscriber} using mocks, only for hard to reproduce cases with real Redis.
 * General tests are better placed in {@link BaseRedisSubscriberTest}.
 */
@ExtendWith(MockitoExtension.class)
class BaseRedisSubscriberUnitTest {

    private static final int AWAIT_TIMEOUT_SECONDS = 2;

    private static TestStreamConfiguration CONFIG;

    private final List<TestRedisSubscriber> subscribers = new CopyOnWriteArrayList<>();
    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    @Mock
    private RedissonReactiveClient redissonClient;

    @Mock
    private RStreamReactive<String, String> stream;

    @BeforeAll
    static void setUpAll() {
        CONFIG = TestStreamConfiguration.create();
    }

    @BeforeEach
    void setUp() {
        when(redissonClient.getStream(any(PlainOptions.class))).thenAnswer(invocation -> stream);
    }

    @AfterEach
    void tearDown() {
        subscribers.forEach(BaseRedisSubscriber::stop);
    }

    @Nested
    class ResilienceTests {

        @BeforeEach
        void setUpStreamListener() {
            whenCreateGroupReturnEmpty();
            whenRemoveConsumerReturn();
        }

        @Test
        void shouldNotDieDuringBackpressure() {
            var readCount = new AtomicInteger(0);
            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(CONFIG, redissonClient));
            // Return messages with delay, simulating slow Redis reads
            when(stream.readGroup(eq(CONFIG.getConsumerGroupName()), anyString(), any(StreamReadGroupArgs.class)))
                    .thenAnswer(invocation -> {
                        readCount.incrementAndGet();
                        // Delay longer than polling interval to cause backpressure
                        return Mono.delay(Duration.ofMillis(CONFIG.getPoolingInterval().toMilliseconds() * 3))
                                .thenReturn(Map.of(new StreamMessageId(System.currentTimeMillis(), 0),
                                        Map.of(TestStreamConfiguration.PAYLOAD_FIELD,
                                                podamFactory.manufacturePojo(String.class))));
                    });
            whenAckReturn();
            whenRemoveReturn();

            subscriber.start();

            // Interval ticks should be dropped due to backpressure, but interval continues
            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        // Verify multiple read attempts were made (interval continues)
                        assertThat(readCount.get()).isGreaterThan(2);
                        // Verify some processing happened
                        assertThat(subscriber.getSuccessMessageCount().get()).isGreaterThan(2);
                        assertThat(subscriber.getFailedMessageCount().get()).isEqualTo(0);
                    });
        }

        @Test
        void shouldNotDieOnReadError() {
            var readCount = new AtomicInteger(0);
            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(CONFIG, redissonClient));
            // Read that throws on first message, succeeds on subsequent
            when(stream.readGroup(eq(CONFIG.getConsumerGroupName()), anyString(), any(StreamReadGroupArgs.class)))
                    .thenAnswer(invocation -> {
                        int count = readCount.incrementAndGet();
                        if (count == 1) {
                            return Mono.error(new RuntimeException("Redis read error"));
                        }
                        return Mono.just(Map.of(new StreamMessageId(System.currentTimeMillis(), 0), Map.of(
                                TestStreamConfiguration.PAYLOAD_FIELD, podamFactory.manufacturePojo(String.class))));
                    });
            whenAckReturn();
            whenRemoveReturn();

            subscriber.start();

            // Should continue processing after error
            await().atMost(10, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        assertThat(readCount.get()).isGreaterThan(2);
                        assertThat(subscriber.getSuccessMessageCount().get()).isGreaterThan(2);
                        assertThat(subscriber.getFailedMessageCount().get()).isEqualTo(0);
                    });
        }

        @Test
        void shouldNotDieOnProcessingError() {
            // Subscriber that throws on first message, succeeds on subsequent
            var processCount = new AtomicInteger(0);
            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(CONFIG, redissonClient, message -> {
                int count = processCount.incrementAndGet();
                if (count == 1) {
                    return Mono.error(new RuntimeException("Unexpected error"));
                }
                return Mono.empty();
            }));
            whenReadGroupReturnMessages();
            whenAckReturn();
            whenRemoveReturn();

            subscriber.start();

            // Should continue processing after error
            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        assertThat(processCount.get()).isGreaterThan(2);
                        assertThat(subscriber.getSuccessMessageCount().get()).isGreaterThan(2);
                        assertThat(subscriber.getFailedMessageCount().get()).isEqualTo(1);
                    });
        }

        @Test
        void shouldNotDieOnAckError() {
            var ackAttempts = new AtomicInteger(0);
            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(CONFIG, redissonClient));
            whenReadGroupReturnMessages();
            // Mock ack to fail once, then succeed
            when(stream.ack(eq(CONFIG.getConsumerGroupName()), any(StreamMessageId[].class)))
                    .thenAnswer(invocation -> {
                        int attempt = ackAttempts.incrementAndGet();
                        if (attempt == 1) {
                            return Mono.error(new RuntimeException("Ack error"));
                        }
                        return Mono.just(1L);
                    });
            whenRemoveReturn();

            subscriber.start();

            // Should handle ack error gracefully and continue processing
            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        assertThat(ackAttempts.get()).isGreaterThan(2);
                        assertThat(subscriber.getSuccessMessageCount().get()).isGreaterThan(2);
                        assertThat(subscriber.getFailedMessageCount().get()).isEqualTo(0);
                    });
        }

        @Test
        void shouldNotDieOnRemoveError() {
            var removeAttempts = new AtomicInteger(0);
            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(CONFIG, redissonClient));
            whenReadGroupReturnMessages();
            whenAckReturn();
            // Mock remove to fail once, then succeed
            when(stream.remove(any(StreamMessageId[].class)))
                    .thenAnswer(invocation -> {
                        int attempt = removeAttempts.incrementAndGet();
                        if (attempt == 1) {
                            return Mono.error(new RuntimeException("Remove error"));
                        }
                        return Mono.just(1L);
                    });

            subscriber.start();

            // Then - should handle remove error gracefully and continue processing
            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        assertThat(removeAttempts.get()).isGreaterThan(2);
                        assertThat(subscriber.getSuccessMessageCount().get()).isGreaterThan(2);
                        assertThat(subscriber.getFailedMessageCount().get()).isEqualTo(0);
                    });
        }

        @Test
        void shouldNotSkipPostProcessFailureOnPostProcessSuccessError() {
            // Subscriber that throws on some messages, succeeds on the rest
            var processCount = new AtomicInteger(0);
            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(CONFIG, redissonClient, message -> {
                int count = processCount.incrementAndGet();
                if (count == 2 || count == 3) {
                    return Mono.error(new RuntimeException("Unexpected error"));
                }
                return Mono.empty();
            }));
            whenReadGroupReturnMessages();
            whenAckReturn();
            // Not mocking remove, so unhandled null pointer exceptions occur in post-processing success

            subscriber.start();

            // Should continue processing after error
            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        assertThat(processCount.get()).isGreaterThan(2);
                        assertThat(subscriber.getSuccessMessageCount().get()).isGreaterThan(2);
                        assertThat(subscriber.getFailedMessageCount().get()).isEqualTo(2);
                    });
        }

        @Test
        void shouldNotDieOnUnhandledError() {
            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(CONFIG, redissonClient));
            whenReadGroupReturnMessages();
            // Not mocking ack and remove, so unhandled null pointer exceptions occur

            subscriber.start();

            // Should continue processing after unexpected error
            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        assertThat(subscriber.getSuccessMessageCount().get()).isGreaterThan(2);
                        assertThat(subscriber.getFailedMessageCount().get()).isEqualTo(0);
                    });
        }

        @Test
        void shouldHandleInvalidMessageIdTimestamp() {
            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(CONFIG, redissonClient));
            // Create a valid message ID with invalid format for timestamp extraction
            var messageId = StreamMessageId.MAX;
            var message = podamFactory.manufacturePojo(String.class);
            when(stream.readGroup(eq(CONFIG.getConsumerGroupName()), anyString(), any(StreamReadGroupArgs.class)))
                    .thenReturn(Mono.just(Map.of(messageId, Map.of(TestStreamConfiguration.PAYLOAD_FIELD, message))));
            whenAckReturn();
            whenRemoveReturn();

            subscriber.start();

            // Should log warning but continue processing
            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        assertThat(subscriber.getSuccessMessageCount().get()).isGreaterThan(2);
                        assertThat(subscriber.getFailedMessageCount().get()).isEqualTo(0);
                    });
        }
    }

    @Nested
    class LifecycleErrorTests {

        @Test
        void shouldFailStartupWhenConsumerGroupCreationFails() {
            // Fail with non-BUSY GROUP error
            when(stream.createGroup(any(StreamCreateGroupArgs.class)))
                    .thenReturn(Mono.error(new RuntimeException("Redis connection error")));
            // Mocking remove consumer and tracking subscriber for cleanup, to prove that stop handles startup failures
            whenRemoveConsumerReturn();

            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(CONFIG, redissonClient));

            // Start should throw exception when consumer group creation fails
            assertThatThrownBy(subscriber::start)
                    .isExactlyInstanceOf(RuntimeException.class)
                    .hasMessage("Redis connection error");
        }

        @Test
        void shouldHandleRemoveConsumerFailureOnStop() {
            whenCreateGroupReturnEmpty();
            // Mock removeConsumer to fail
            when(stream.removeConsumer(eq(CONFIG.getConsumerGroupName()), anyString()))
                    .thenReturn(Mono.error(new RuntimeException("Failed to remove consumer")));
            // No need to track subscriber, calling stop explicitly
            var subscriber = TestRedisSubscriber.createSubscriber(CONFIG, redissonClient);

            subscriber.start();

            assertThatCode(subscriber::stop).doesNotThrowAnyException();
        }

        @Test
        void shouldHandleRemoveConsumerTimeoutOnStop() {
            whenCreateGroupReturnEmpty();
            // Mock removeConsumer to never complete
            when(stream.removeConsumer(eq(CONFIG.getConsumerGroupName()), anyString()))
                    .thenReturn(Mono.never());
            // No need to track subscriber, calling stop explicitly
            var subscriber = TestRedisSubscriber.createSubscriber(CONFIG, redissonClient);

            subscriber.start();

            assertThatCode(subscriber::stop).doesNotThrowAnyException();
        }
    }

    private TestRedisSubscriber trackSubscriber(TestRedisSubscriber subscriber) {
        subscribers.add(subscriber);
        return subscriber;
    }

    private void whenCreateGroupReturnEmpty() {
        when(stream.createGroup(any(StreamCreateGroupArgs.class))).thenReturn(Mono.empty());
    }

    private void whenReadGroupReturnMessages() {
        // Return different messages on different calls
        when(stream.readGroup(eq(CONFIG.getConsumerGroupName()), anyString(), any(StreamReadGroupArgs.class)))
                .thenReturn(Mono.just(Map.of(new StreamMessageId(System.currentTimeMillis(), 0),
                        Map.of(TestStreamConfiguration.PAYLOAD_FIELD, podamFactory.manufacturePojo(String.class)))));
    }

    private void whenAckReturn() {
        when(stream.ack(eq(CONFIG.getConsumerGroupName()), any(StreamMessageId[].class))).thenReturn(Mono.just(1L));
    }

    private void whenRemoveReturn() {
        when(stream.remove(any(StreamMessageId[].class))).thenReturn(Mono.just(1L));
    }

    private void whenRemoveConsumerReturn() {
        when(stream.removeConsumer(anyString(), anyString())).thenReturn(Mono.just(0L));
    }
}
