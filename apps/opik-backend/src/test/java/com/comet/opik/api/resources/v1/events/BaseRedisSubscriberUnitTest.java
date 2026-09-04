package com.comet.opik.api.resources.v1.events;

import com.comet.opik.infrastructure.redis.UndecodableStreamMessage;
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
import org.redisson.api.options.PlainOptions;
import org.redisson.api.stream.AutoClaimResult;
import org.redisson.api.stream.PendingEntry;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.api.stream.StreamPendingRangeArgs;
import org.redisson.api.stream.StreamReadGroupArgs;
import reactor.core.publisher.Mono;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Duration;
import java.util.Collections;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
    class SuccessTests {

        @BeforeEach
        void setUp() {
            whenCreateGroupReturnEmpty();
            whenRemoveConsumerReturn();
        }

        @Test
        void shouldInterleaveClaimAndReadOperations() {
            var fastConfig = CONFIG.toBuilder()
                    .claimIntervalRatio(3)
                    .build();
            var claimAttempts = new AtomicInteger();
            var readAttempts = new AtomicInteger();
            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(fastConfig, redissonClient));
            // Mock autoClaim to return empty
            when(stream.autoClaim(
                    fastConfig.getConsumerGroupName(),
                    subscriber.getConsumerId(),
                    fastConfig.getPendingMessageDuration().toJavaDuration().toMillis(),
                    TimeUnit.MILLISECONDS,
                    StreamMessageId.MIN,
                    fastConfig.getConsumerBatchSize()))
                    .thenAnswer(invocation -> {
                        claimAttempts.incrementAndGet();
                        var result = new AutoClaimResult<>(
                                null,
                                Map.of(),
                                List.of());
                        return Mono.just(result);
                    });
            when(stream.readGroup(eq(fastConfig.getConsumerGroupName()), anyString(), any(StreamReadGroupArgs.class)))
                    .thenAnswer(invocation -> {
                        readAttempts.incrementAndGet();
                        return Mono.just(Map.of(new StreamMessageId(System.currentTimeMillis(), 0),
                                Map.of(TestStreamConfiguration.PAYLOAD_FIELD,
                                        podamFactory.manufacturePojo(String.class))));
                    });
            whenAckReturn();
            whenRemoveReturn();

            subscriber.start();

            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        // Verify both claim and read operations occur with proper ratio (approximately)
                        var claimCount = claimAttempts.get();
                        assertThat(claimCount).isGreaterThan(0);
                        assertThat(readAttempts.get()).isGreaterThan(claimCount * fastConfig.getClaimIntervalRatio());
                        // Verify some processing happened
                        assertThat(subscriber.getSuccessMessageCount().get()).isGreaterThan(2);
                        assertThat(subscriber.getFailedMessageCount().get()).isEqualTo(0);
                    });
        }
    }

    @Nested
    class ResilienceTests {

        @BeforeEach
        void setUp() {
            whenCreateGroupReturnEmpty();
            whenRemoveConsumerReturn();
        }

        @Test
        void shouldNotDieDuringBackpressure() {
            var readCount = new AtomicInteger();
            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(CONFIG, redissonClient));
            whenAutoClaimReturnEmpty(subscriber.getConsumerId());
            // Return messages with delay, simulating slow Redis reads
            when(stream.readGroup(eq(CONFIG.getConsumerGroupName()), anyString(), any(StreamReadGroupArgs.class)))
                    .thenAnswer(invocation -> {
                        readCount.incrementAndGet();
                        // Delay longer than polling interval to cause backpressure
                        return Mono.delay(Duration.ofMillis(CONFIG.getPoolingInterval().toMilliseconds() * 2))
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
        void shouldNotDieOnClaimError() {
            var fastConfig = CONFIG.toBuilder()
                    .claimIntervalRatio(3)
                    .build();
            var claimCount = new AtomicInteger();
            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(fastConfig, redissonClient));
            // Mock Auto claim to fail
            when(stream.autoClaim(
                    fastConfig.getConsumerGroupName(),
                    subscriber.getConsumerId(),
                    fastConfig.getPendingMessageDuration().toJavaDuration().toMillis(),
                    TimeUnit.MILLISECONDS,
                    StreamMessageId.MIN,
                    fastConfig.getConsumerBatchSize()))
                    .thenAnswer(invocation -> {
                        claimCount.incrementAndGet();
                        return Mono.error(new RuntimeException("Redis autoClaim error"));
                    });
            whenReadGroupReturnMessages();
            whenAckReturn();
            whenRemoveReturn();

            subscriber.start();

            // Should continue processing after claim errors
            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        // Verify claim attempts occurred
                        assertThat(claimCount.get()).isGreaterThan(1);
                        // Verify successful message processing from reads
                        assertThat(subscriber.getSuccessMessageCount().get()).isGreaterThan(2);
                        assertThat(subscriber.getFailedMessageCount().get()).isEqualTo(0);
                    });
        }

        @Test
        void shouldNotDieOnReadError() {
            var readCount = new AtomicInteger();
            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(CONFIG, redissonClient));
            whenAutoClaimReturnEmpty(subscriber.getConsumerId());
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
        void shouldHandleEmptyListEntryValue() {
            var readCount = new AtomicInteger();
            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(CONFIG, redissonClient));
            whenAutoClaimReturnEmpty(subscriber.getConsumerId());
            when(stream.readGroup(eq(CONFIG.getConsumerGroupName()), anyString(), any(StreamReadGroupArgs.class)))
                    .thenAnswer(invocation -> {
                        int count = readCount.incrementAndGet();
                        if (count == 1) {
                            // Fix for OPIK-5647: simulate receiving Collections.emptyList() as the entry value instead
                            // of a Map, for empty/malformed stream entries.
                            return Mono.just(Map.of(new StreamMessageId(System.currentTimeMillis(), 0),
                                    Collections.emptyList()));
                        }
                        return Mono.just(Map.of(new StreamMessageId(System.currentTimeMillis(), 0),
                                Map.of(TestStreamConfiguration.PAYLOAD_FIELD,
                                        podamFactory.manufacturePojo(String.class))));
                    });
            whenAckReturn();
            whenRemoveReturn();

            subscriber.start();

            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        assertThat(readCount.get()).isGreaterThan(2);
                        assertThat(subscriber.getSuccessMessageCount().get()).isGreaterThan(2);
                        assertThat(subscriber.getFailedMessageCount().get()).isEqualTo(0);
                    });
        }

        /**
         * OPIK-8192, first half. An undecodable payload now arrives as a <em>value</em> rather than a
         * throw from inside Redisson, so it carries a messageId at all — previously it never reached
         * here, was redelivered forever, and at {@code consumerBatchSize > 1} stranded every healthy
         * entry claimed with it.
         * <p>
         * It must NOT be removed on first delivery. "This pod cannot decode it" is not "nobody can":
         * the reader's {@code maxStringLength} is configuration, and during a rolling upgrade a newer
         * pod may read what an older one cannot. Deleting on sight would discard recoverable data.
         */
        @Test
        void shouldNotRemoveUndecodableMessageBeforeMaxRetries() {
            var undecodableId = new StreamMessageId(System.currentTimeMillis(), 0);
            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(CONFIG, redissonClient));
            whenAutoClaimReturnEmpty(subscriber.getConsumerId());
            whenReadGroupReturnsUndecodableThenHealthy(undecodableId);
            // Delivery count below maxRetries (3): the entry stays pending for another consumer.
            // Built before the stubbing call -- Mockito rejects creating a mock inside when(...).
            var pending = pendingEntry(undecodableId, 1);
            when(stream.listPending(any(StreamPendingRangeArgs.class)))
                    .thenReturn(Mono.just(List.of(pending)));
            whenAckReturn();
            whenRemoveReturn();

            subscriber.start();

            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> assertThat(subscriber.getSuccessMessageCount().get()).isGreaterThan(1));

            verify(stream, never()).ack(eq(CONFIG.getConsumerGroupName()),
                    eq(new StreamMessageId[]{undecodableId}));
            verify(stream, never()).remove(eq(new StreamMessageId[]{undecodableId}));
        }

        /**
         * OPIK-8192, second half. Retrying must still terminate: once the delivery count reaches
         * {@code maxRetries} the entry is acked and removed, so a genuinely poisonous payload cannot
         * wedge the stream the way it did before. This is the bound that makes the retryable
         * classification safe.
         */
        @Test
        void shouldRemoveUndecodableMessageOnceMaxRetriesReached() {
            var undecodableId = new StreamMessageId(System.currentTimeMillis(), 0);
            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(CONFIG, redissonClient));
            whenAutoClaimReturnEmpty(subscriber.getConsumerId());
            whenReadGroupReturnsUndecodableThenHealthy(undecodableId);
            var pending = pendingEntry(undecodableId, CONFIG.getMaxRetries());
            when(stream.listPending(any(StreamPendingRangeArgs.class)))
                    .thenReturn(Mono.just(List.of(pending)));
            whenAckReturn();
            whenRemoveReturn();

            subscriber.start();

            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        verify(stream).ack(eq(CONFIG.getConsumerGroupName()),
                                eq(new StreamMessageId[]{undecodableId}));
                        verify(stream).remove(eq(new StreamMessageId[]{undecodableId}));
                    });
            // It never reaches processEvent either way, and healthy traffic behind it keeps flowing.
            assertThat(subscriber.getFailedMessageCount().get()).isEqualTo(0);
        }

        /**
         * A genuinely absent payload field is deterministic — no pod can invent it — so it keeps the
         * pre-existing non-retryable treatment and is removed on the FIRST delivery. The delivery count
         * is stubbed below maxRetries precisely to prove the removal does not depend on exhausting it.
         */
        @Test
        void shouldRemoveMessageWithNoPayloadOnFirstDelivery() {
            var noPayloadId = new StreamMessageId(System.currentTimeMillis(), 0);
            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(CONFIG, redissonClient));
            whenAutoClaimReturnEmpty(subscriber.getConsumerId());
            var readCount = new AtomicInteger();
            when(stream.readGroup(eq(CONFIG.getConsumerGroupName()), anyString(), any(StreamReadGroupArgs.class)))
                    .thenAnswer(invocation -> readCount.incrementAndGet() == 1
                            ? Mono.just(Map.of(noPayloadId, Map.of("not-the-payload-field", "ignored")))
                            : Mono.just(Map.of(new StreamMessageId(System.currentTimeMillis(), readCount.get()),
                                    Map.of(TestStreamConfiguration.PAYLOAD_FIELD,
                                            podamFactory.manufacturePojo(String.class)))));
            whenAckReturn();
            whenRemoveReturn();

            subscriber.start();

            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> verify(stream).remove(eq(new StreamMessageId[]{noPayloadId})));
            // Deliberately no listPending stub: a non-retryable failure is removed without consulting
            // the delivery count at all, so needing one would mean the classification had drifted to
            // retryable. This is the assertion that separates this case from the field-name one below.
            verify(stream, never()).listPending(any(StreamPendingRangeArgs.class));
            assertThat(subscriber.getFailedMessageCount().get()).isEqualTo(0);
        }

        /**
         * A field name the map-key decoder could not decode is a different animal: the sentinel lands
         * among the KEYS, so the payload lookup misses, but LZ4/Kryo skew can differ between pods. It
         * must therefore be retryable — NOT removed below maxRetries — unlike the absent-field case
         * above. Nothing but this test distinguishes the two.
         */
        @Test
        void shouldNotRemoveMessageWithUndecodableFieldNameBeforeMaxRetries() {
            var badKeyId = new StreamMessageId(System.currentTimeMillis(), 0);
            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(CONFIG, redissonClient));
            whenAutoClaimReturnEmpty(subscriber.getConsumerId());
            var readCount = new AtomicInteger();
            when(stream.readGroup(eq(CONFIG.getConsumerGroupName()), anyString(), any(StreamReadGroupArgs.class)))
                    .thenAnswer(invocation -> {
                        if (readCount.incrementAndGet() == 1) {
                            // The sentinel is the KEY, so no entry matches PAYLOAD_FIELD.
                            Map<Object, Object> entry = new java.util.HashMap<>();
                            entry.put(UndecodableStreamMessage.builder()
                                    .encodedBytes(64)
                                    .cause(new IllegalStateException("not an LZ4 frame"))
                                    .build(), "unreachable");
                            return Mono.just(Map.of(badKeyId, entry));
                        }
                        return Mono.just(Map.of(new StreamMessageId(System.currentTimeMillis(), readCount.get()),
                                Map.of(TestStreamConfiguration.PAYLOAD_FIELD,
                                        podamFactory.manufacturePojo(String.class))));
                    });
            var pending = pendingEntry(badKeyId, 1);
            when(stream.listPending(any(StreamPendingRangeArgs.class)))
                    .thenReturn(Mono.just(List.of(pending)));
            whenAckReturn();
            whenRemoveReturn();

            subscriber.start();

            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> assertThat(subscriber.getSuccessMessageCount().get()).isGreaterThan(1));

            verify(stream, never()).remove(eq(new StreamMessageId[]{badKeyId}));
        }

        private void whenReadGroupReturnsUndecodableThenHealthy(StreamMessageId undecodableId) {
            var readCount = new AtomicInteger();
            when(stream.readGroup(eq(CONFIG.getConsumerGroupName()), anyString(), any(StreamReadGroupArgs.class)))
                    .thenAnswer(invocation -> readCount.incrementAndGet() == 1
                            ? Mono.just(Map.of(undecodableId,
                                    Map.of(TestStreamConfiguration.PAYLOAD_FIELD,
                                            UndecodableStreamMessage.builder()
                                                    .encodedBytes(20_054_016)
                                                    .cause(new IllegalStateException(
                                                            "String value length (20054016) exceeds the maximum allowed"))
                                                    .build())))
                            : Mono.just(Map.of(new StreamMessageId(System.currentTimeMillis(), readCount.get()),
                                    Map.of(TestStreamConfiguration.PAYLOAD_FIELD,
                                            podamFactory.manufacturePojo(String.class)))));
        }

        @Test
        void shouldNotDieOnProcessingError() {
            // Subscriber that throws on first message, succeeds on subsequent
            var processCount = new AtomicInteger();
            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(CONFIG, redissonClient, message -> {
                int count = processCount.incrementAndGet();
                if (count == 1) {
                    return Mono.error(new NullPointerException("Unexpected error"));
                }
                return Mono.empty();
            }));
            whenAutoClaimReturnEmpty(subscriber.getConsumerId());
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
            var ackAttempts = new AtomicInteger();
            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(CONFIG, redissonClient));
            whenAutoClaimReturnEmpty(subscriber.getConsumerId());
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
            var removeAttempts = new AtomicInteger();
            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(CONFIG, redissonClient));
            whenAutoClaimReturnEmpty(subscriber.getConsumerId());
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

            // Should handle remove error gracefully and continue processing
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
            var processCount = new AtomicInteger();
            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(CONFIG, redissonClient, message -> {
                int count = processCount.incrementAndGet();
                if (count == 2 || count == 3) {
                    return Mono.error(new RuntimeException("Unexpected error"));
                }
                return Mono.empty();
            }));
            whenAutoClaimReturnEmpty(subscriber.getConsumerId());
            // Simulating a batch of messages, so they're handled in one read
            when(stream.readGroup(eq(CONFIG.getConsumerGroupName()), anyString(), any(StreamReadGroupArgs.class)))
                    .thenReturn(Mono.just(Map.of(
                            new StreamMessageId(System.currentTimeMillis(), 0),
                            Map.of(TestStreamConfiguration.PAYLOAD_FIELD, podamFactory.manufacturePojo(String.class)),
                            new StreamMessageId(System.currentTimeMillis(), 1),
                            Map.of(TestStreamConfiguration.PAYLOAD_FIELD, podamFactory.manufacturePojo(String.class)),
                            new StreamMessageId(System.currentTimeMillis(), 2),
                            Map.of(TestStreamConfiguration.PAYLOAD_FIELD, podamFactory.manufacturePojo(String.class)),
                            new StreamMessageId(System.currentTimeMillis(), 3),
                            Map.of(TestStreamConfiguration.PAYLOAD_FIELD, podamFactory.manufacturePojo(String.class)),
                            new StreamMessageId(System.currentTimeMillis(), 4),
                            Map.of(TestStreamConfiguration.PAYLOAD_FIELD,
                                    podamFactory.manufacturePojo(String.class)))));
            whenAckReturn();
            when(stream.remove(any(StreamMessageId[].class)))
                    .thenReturn(Mono.error(new RuntimeException("Redis remove error")));
            when(stream.listPending(any(StreamPendingRangeArgs.class))).thenReturn(Mono.just(List.of()));

            subscriber.start();

            // Should continue processing after error
            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        assertThat(processCount.get()).isGreaterThan(2);
                        assertThat(subscriber.getSuccessMessageCount().get()).isGreaterThan(2);
                        assertThat(subscriber.getFailedMessageCount().get()).isEqualTo(2);
                        verify(stream, times(2)).listPending(any(StreamPendingRangeArgs.class));
                    });
        }

        @Test
        void shouldNotDieOnListPendingError() {
            var listPendingAttempts = new AtomicInteger();
            var subscriber = trackSubscriber(TestRedisSubscriber.failingRetriesSubscriber(CONFIG, redissonClient));
            whenAutoClaimReturnEmpty(subscriber.getConsumerId());
            whenReadGroupReturnMessages();
            whenAckReturn();
            whenRemoveReturn();
            // Mock list pending to fail once, then succeed
            when(stream.listPending(any(StreamPendingRangeArgs.class)))
                    .thenAnswer(invocation -> {
                        int attempt = listPendingAttempts.incrementAndGet();
                        if (attempt == 1) {
                            return Mono.error(new RuntimeException("List pending error"));
                        }
                        return Mono.just(List.of());
                    });

            subscriber.start();

            // Should handle list pending error gracefully and continue processing
            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        assertThat(listPendingAttempts.get()).isGreaterThan(2);
                        assertThat(subscriber.getSuccessMessageCount().get()).isEqualTo(0);
                        assertThat(subscriber.getFailedMessageCount().get()).isGreaterThan(2);
                    });
        }

        @Test
        void shouldNotDieOnUnhandledError() {
            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(CONFIG, redissonClient));
            whenAutoClaimReturnEmpty(subscriber.getConsumerId());
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
            whenAutoClaimReturnEmpty(subscriber.getConsumerId());
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
    class NoGroupErrorTests {

        @Test
        void shouldRecoverOnClaimAndNotDie() {
            whenCreateGroupReturnEmpty();
            whenRemoveConsumerReturn();
            var fastConfig = CONFIG.toBuilder()
                    .claimIntervalRatio(3)
                    .build();
            var claimCount = new AtomicInteger();
            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(fastConfig, redissonClient));
            // autoClaim fails with NOGROUP on first attempt, then succeeds
            when(stream.autoClaim(
                    fastConfig.getConsumerGroupName(),
                    subscriber.getConsumerId(),
                    fastConfig.getPendingMessageDuration().toJavaDuration().toMillis(),
                    TimeUnit.MILLISECONDS,
                    StreamMessageId.MIN,
                    fastConfig.getConsumerBatchSize()))
                    .thenAnswer(invocation -> {
                        int count = claimCount.incrementAndGet();
                        if (count == 1) {
                            return Mono.error(
                                    new RuntimeException("NOGROUP No such key stream or consumer group"));
                        }
                        var result = new AutoClaimResult<>(null, Map.of(), List.of());
                        return Mono.just(result);
                    });
            // readGroup works fine, isolating NOGROUP to claim path only
            whenReadGroupReturnMessages();
            whenAckReturn();
            whenRemoveReturn();

            subscriber.start();

            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        assertThat(claimCount.get()).isGreaterThan(1);
                        assertThat(subscriber.getSuccessMessageCount().get()).isGreaterThan(2);
                        assertThat(subscriber.getFailedMessageCount().get()).isEqualTo(0);
                        // createGroup called at start + once for NOGROUP recovery on claim
                        verify(stream, times(2)).createGroup(any(StreamCreateGroupArgs.class));
                    });
        }

        @Test
        void shouldNotDieOnRecreatingGroup() {
            whenRemoveConsumerReturn();
            var readCount = new AtomicInteger();
            var createGroupCount = new AtomicInteger();

            // First createGroup succeeds (startup), second fails (NOGROUP recovery)
            when(stream.createGroup(any(StreamCreateGroupArgs.class)))
                    .thenAnswer(invocation -> {
                        int count = createGroupCount.incrementAndGet();
                        if (count == 2) {
                            return Mono.error(new RuntimeException("Redis connection error"));
                        }
                        return Mono.empty();
                    });

            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(CONFIG, redissonClient));
            whenAutoClaimReturnEmpty(subscriber.getConsumerId());

            when(stream.readGroup(eq(CONFIG.getConsumerGroupName()), anyString(), any(StreamReadGroupArgs.class)))
                    .thenAnswer(invocation -> {
                        int count = readCount.incrementAndGet();
                        if (count == 1) {
                            return Mono.error(
                                    new RuntimeException("NOGROUP No such key stream or consumer group"));
                        }
                        return Mono.just(Map.of(new StreamMessageId(System.currentTimeMillis(), 0),
                                Map.of(TestStreamConfiguration.PAYLOAD_FIELD,
                                        podamFactory.manufacturePojo(String.class))));
                    });
            whenAckReturn();
            whenRemoveReturn();

            subscriber.start();

            // Should continue processing even after recovery failure
            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        assertThat(readCount.get()).isGreaterThan(2);
                        assertThat(subscriber.getSuccessMessageCount().get()).isGreaterThan(2);
                        assertThat(subscriber.getFailedMessageCount().get()).isEqualTo(0);
                        assertThat(createGroupCount.get()).isEqualTo(2);
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

    /** A PendingEntry whose delivery count drives the maxRetries decision. */
    private static PendingEntry pendingEntry(StreamMessageId messageId, long deliveryCount) {
        var entry = org.mockito.Mockito.mock(PendingEntry.class);
        lenient().when(entry.getId()).thenReturn(messageId);
        lenient().when(entry.getDeliveryCount()).thenReturn(deliveryCount);
        return entry;
    }

    /**
     * OPIK-8240, for the one part of the claim cursor a real Redis cannot be made to produce on demand:
     * a failing {@code XAUTOCLAIM}.
     * <p>
     * The cursor's normal behaviour -- walking past the first {@code COUNT * 10} examine window and
     * wrapping when Redis reports the pass complete -- is covered end to end in
     * {@link BaseRedisSubscriberTest.ClaimCursorTests} against a PEL deeper than that window, so it is
     * deliberately not duplicated here. What is left is the error path: the cursor must NOT advance past
     * a window whose scan failed, or that window's entries are skipped until the next full wrap. Making
     * a real Redis fail one XAUTOCLAIM mid-run and succeed on the next is not something the container
     * exposes, so it is mocked.
     */
    @Nested
    class ClaimCursorTests {

        @BeforeEach
        void setUp() {
            whenCreateGroupReturnEmpty();
            whenRemoveConsumerReturn();
        }

        @Test
        void shouldRetryTheSameWindowAfterAFailedScan() {
            var fastConfig = CONFIG.toBuilder().claimIntervalRatio(2).build();
            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(fastConfig, redissonClient));
            var starts = new CopyOnWriteArrayList<StreamMessageId>();
            var calls = new AtomicInteger();
            when(stream.autoClaim(
                    eq(fastConfig.getConsumerGroupName()),
                    anyString(),
                    eq(fastConfig.getPendingMessageDuration().toJavaDuration().toMillis()),
                    eq(TimeUnit.MILLISECONDS),
                    any(StreamMessageId.class),
                    eq(fastConfig.getConsumerBatchSize())))
                    .thenAnswer(invocation -> {
                        starts.add(invocation.getArgument(4));
                        if (calls.incrementAndGet() == 1) {
                            return Mono.just(new AutoClaimResult<>(
                                    new StreamMessageId(700L, 0), Map.of(), List.of()));
                        }
                        // A failed scan must not advance the cursor, or the window it covered would be
                        // skipped entirely and its entries left unreachable until the next full wrap.
                        return Mono.error(new RuntimeException("Redis autoClaim error"));
                    });
            whenReadGroupReturnMessages();
            whenAckReturn();
            whenRemoveReturn();

            subscriber.start();

            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS).until(() -> starts.size() >= 3);

            assertThat(starts.get(1)).isEqualTo(new StreamMessageId(700L, 0));
            assertThat(starts.get(2)).isEqualTo(new StreamMessageId(700L, 0));
        }
    }

    private TestRedisSubscriber trackSubscriber(TestRedisSubscriber subscriber) {
        subscribers.add(subscriber);
        return subscriber;
    }

    private void whenCreateGroupReturnEmpty() {
        when(stream.createGroup(any(StreamCreateGroupArgs.class))).thenReturn(Mono.empty());
    }

    private void whenAutoClaimReturnEmpty(String consumerId) {
        // This should be lenient as auto claim only happens after N ratio of reads
        lenient().when(stream.autoClaim(
                CONFIG.getConsumerGroupName(),
                consumerId,
                CONFIG.getPendingMessageDuration().toJavaDuration().toMillis(),
                TimeUnit.MILLISECONDS,
                StreamMessageId.MIN,
                CONFIG.getConsumerBatchSize()))
                .thenAnswer(invocation -> {
                    var result = new AutoClaimResult<>(
                            null,
                            Map.of(),
                            List.of());
                    return Mono.just(result);
                });
    }

    private void whenReadGroupReturnMessages() {
        // Return different messages on different calls
        when(stream.readGroup(eq(CONFIG.getConsumerGroupName()), anyString(), any(StreamReadGroupArgs.class)))
                .thenReturn(Mono.just(Map.of(new StreamMessageId(System.currentTimeMillis(), 0),
                        Map.of(TestStreamConfiguration.PAYLOAD_FIELD, podamFactory.manufacturePojo(String.class)))));
    }

    private void whenAckReturn() {
        // This should be lenient as the test might finish before ack happening
        lenient().when(stream.ack(eq(CONFIG.getConsumerGroupName()), any(StreamMessageId[].class)))
                .thenReturn(Mono.just(1L));
    }

    private void whenRemoveReturn() {
        // This should be lenient as the test might finish before remove happening
        lenient().when(stream.remove(any(StreamMessageId[].class))).thenReturn(Mono.just(1L));
    }

    private void whenRemoveConsumerReturn() {
        when(stream.removeConsumer(anyString(), anyString())).thenReturn(Mono.just(0L));
    }
}
