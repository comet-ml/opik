package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.infrastructure.redis.RedisStreamCodec;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.redisson.Redisson;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.client.codec.Codec;
import org.redisson.codec.CompositeCodec;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.codec.LZ4CodecV2;
import org.redisson.config.Config;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.TestUtils.waitForMillis;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for {@link BaseRedisSubscriber}  using real Redis test container.
 * Generally, tests should be created here instead of in the unit test class  {@link BaseRedisSubscriberUnitTest}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BaseRedisSubscriberTest {

    private static final int AWAIT_TIMEOUT_SECONDS = 2;

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();
    private final List<TestRedisSubscriber> subscribers = new CopyOnWriteArrayList<>();

    private RedissonReactiveClient redissonClient;
    private TestStreamConfiguration config;
    private RStreamReactive<String, String> stream;

    @BeforeAll
    void setUpAll() {
        redis.start();

        var redissonConfig = new Config();
        redissonConfig.useSingleServer()
                .setAddress(redis.getRedisURI())
                .setDatabase(0);
        redissonConfig.setCodec(new JsonJacksonCodec(JsonUtils.getMapper()));
        redissonClient = Redisson.create(redissonConfig).reactive();

        config = TestStreamConfiguration.create();
        stream = redissonClient.getStream(config.getStreamName(), config.getCodec());
    }

    @BeforeEach
    void setUp() {
        // Clean up only this test's stream to avoid affecting other test classes
        stream.delete().block();
        subscribers.clear();
    }

    @AfterEach
    void tearDown() {
        subscribers.forEach(BaseRedisSubscriber::stop);
    }

    /**
     * The OPIK-8164 mechanism end to end, on real Redis, with a real decode failure.
     * <p>
     * Every other drop test in this PR either injects a pre-built {@code UndecodableStreamMessage} into
     * a mocked {@code readGroup}, or exercises the decoder in isolation. Neither reproduces the thing
     * that actually happened: the payload is written successfully, because Jackson has no
     * serialization-side limit, and then breaches {@code maxStringLength} on the way back out — inside
     * Redisson's {@code CommandDecoder}, below {@code BaseRedisSubscriber}, which pre-PR is exactly why
     * the entry could never be acked and the stream wedged permanently.
     * <p>
     * A small {@code maxStringLength} stands in for production's 100 MB so the asymmetry is reproduced
     * at a few hundred bytes instead of tens of megabytes. The write path is unbounded either way, so
     * the mechanism is identical.
     * <p>
     * Worth knowing what this run exposed about timing, because the retry timers are compressed here
     * and are not in production. Retirement is driven by the delivery count, which only advances when
     * {@code XAUTOCLAIM} redelivers the entry after {@code pendingMessageDuration}. At the shipped
     * online-scoring values ({@code pendingMessageDuration: 10m}, {@code maxRetries: 3}) an undecodable
     * entry therefore stays in the stream for roughly 30 minutes, being re-read and re-decoded on each
     * cycle, before it is removed. The wedge is bounded rather than eliminated — and for a payload of
     * production size, each cycle re-attempts a large materialization. That is the cost of not deleting
     * on first delivery; it is the right trade while a newer pod might still decode the entry, but it
     * is not free.
     */
    @Nested
    class UndecodablePayloadTests {

        private static final int SMALL_STRING_LIMIT = 512;

        private TestStreamConfiguration smallLimitConfig;
        private RStreamReactive<String, String> smallLimitStream;

        /** The JAVA codec's shape, but with a string limit small enough to breach cheaply. */
        private Codec smallLimitCodec() {
            var mapper = JsonUtils.getMapper().copy();
            mapper.getFactory().setStreamReadConstraints(
                    StreamReadConstraints.builder().maxStringLength(SMALL_STRING_LIMIT).build());
            return RedisStreamCodec.faultTolerant(
                    new CompositeCodec(new LZ4CodecV2(), new JsonJacksonCodec(mapper)));
        }

        @BeforeEach
        void setUp() {
            smallLimitConfig = TestStreamConfiguration.create().toBuilder()
                    .codec(smallLimitCodec())
                    // Retirement is driven by the delivery count, which only rises when XAUTOCLAIM
                    // redelivers the entry after pendingMessageDuration. The class default is 2 minutes
                    // and maxRetries 3, so an undecodable entry would sit in the stream for ~6 minutes.
                    // Compressed here to keep the test quick; see the class javadoc for what this means
                    // at the shipped values.
                    .pendingMessageDuration(io.dropwizard.util.Duration.milliseconds(500))
                    .claimIntervalRatio(2)
                    .maxRetries(2)
                    .build();
            smallLimitStream = redissonClient.getStream(
                    smallLimitConfig.getStreamName(), smallLimitConfig.getCodec());
            smallLimitStream.delete().block();
        }

        @Test
        void shouldDrainAnEntryThatOnlyFailsOnRead() {
            var subscriber = trackSubscriber(
                    TestRedisSubscriber.createSubscriber(smallLimitConfig, redissonClient));
            var oversized = "a".repeat(SMALL_STRING_LIMIT + 1);
            var healthy = "well-within-limits";

            // Start first: the consumer group is created at '$', so it only sees entries added after
            // start(). Publishing beforehand leaves them permanently undelivered and proves nothing.
            subscriber.start();

            // Writes fine: Jackson constrains reads, not writes. This is the asymmetry behind OPIK-8164.
            var oversizedId = smallLimitStream
                    .add(StreamAddArgs.entry(TestStreamConfiguration.PAYLOAD_FIELD, oversized)).block();
            smallLimitStream.add(StreamAddArgs.entry(TestStreamConfiguration.PAYLOAD_FIELD, healthy)).block();
            assertThat(smallLimitStream.size().block()).isEqualTo(2);

            // The healthy entry behind it still gets through -- pre-PR it was stranded in the same batch.
            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> assertThat(subscriber.getSuccessMessageCount().get()).isEqualTo(1));

            // And the undecodable entry eventually leaves too, once the delivery count reaches
            // maxRetries -- bounded, not permanent. This is the half that distinguishes the fix from
            // the pre-PR wedge, where XLEN never returned to zero.
            //
            // Deliberately NOT asserting an intermediate "still exactly 1 entry" here. Ack and remove
            // are asynchronous and batched (postProcessSuccessMessages runs after bufferTimeout), so
            // processEvent completing does not mean the healthy entry's XACK/XDEL has landed -- an
            // immediate size check races it and legitimately observes 2. That the undecodable entry
            // survives its first delivery is pinned deterministically by the mocked unit tests
            // (shouldNotRemoveUndecodableMessageBeforeMaxRetries), which control the delivery count
            // directly instead of inferring it from wall-clock ordering.
            await().atMost(AWAIT_TIMEOUT_SECONDS * 10, TimeUnit.SECONDS)
                    .untilAsserted(() -> assertThat(smallLimitStream.size().block()).isZero());

            // XDEL alone is not enough. A broken XACK would leave the id in the consumer group's PEL,
            // where XAUTOCLAIM keeps reclaiming it -- a wedge by another name, and invisible to XLEN.
            // Checked by id, and via listPending rather than pendingRange so the assertion does not
            // itself have to decode the payload that cannot be decoded.
            // defaultIfEmpty: Redisson's reactive listPending COMPLETES EMPTY rather than emitting an
            // empty list when the PEL is clear, so block() returns null on the passing path.
            var stillPending = smallLimitStream.listPending(
                    smallLimitConfig.getConsumerGroupName(),
                    StreamMessageId.MIN, StreamMessageId.MAX, Integer.MAX_VALUE)
                    .defaultIfEmpty(List.of())
                    .block();
            assertThat(stillPending).noneSatisfy(
                    entry -> assertThat(entry.getId()).isEqualTo(oversizedId));
            assertThat(stillPending).isEmpty();

            // It never reached processEvent: the sentinel is intercepted in processMessage.
            assertThat(subscriber.getFailedMessageCount().get()).isZero();
        }
    }

    @Nested
    class SuccessTests {

        @Test
        void shouldSuccessfullyConsumeAndProcessBatchOfMessages() {
            var messages = PodamFactoryUtils.manufacturePojoList(podamFactory, String.class);
            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(config, redissonClient));
            subscriber.start();

            publishMessagesToStream(messages);

            waitForMessagesProcessed(subscriber, messages.size());
            waitForMessagesAckedAndRemoved();
            assertThat(subscriber.getFailedMessageCount().get()).isZero();
        }

        @Test
        void shouldProcessMessagesInParallel() {
            var processedMessages = new CopyOnWriteArraySet<String>();
            var processingThreads = new CopyOnWriteArraySet<String>();
            var messages = PodamFactoryUtils.manufacturePojoList(podamFactory, String.class);
            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(config, redissonClient,
                    message -> Mono.fromRunnable(() -> {
                        processedMessages.add(message);
                        processingThreads.add(Thread.currentThread().getName());
                        // Simulate some processing time, increases the chances of parallelism
                        Mono.delay(Duration.ofMillis(500)).block();
                    })));
            subscriber.start();

            publishMessagesToStream(messages);

            waitForMessagesProcessed(subscriber, messages.size());
            waitForMessagesAckedAndRemoved();
            assertThat(subscriber.getFailedMessageCount().get()).isZero();
            assertThat(processedMessages).containsExactlyInAnyOrderElementsOf(messages);
            // Verify parallel processing by checking multiple threads were used
            assertThat(processingThreads).hasSizeGreaterThan(1);
        }

        /**
         * OPIK-8192 changed this contract. An entry with no value under the payload field used to be
         * handed to {@code processEvent} as null, leaving every subscriber to dereference it and throw;
         * it is now short-circuited in {@code processMessage} and retired as a non-retryable failure
         * without reaching {@code processEvent} at all. So the assertion is the inverse of what it was:
         * the null never arrives, and only the well-formed entries are processed.
         */
        @Test
        void shouldRemoveEntriesWithNoPayloadWithoutReachingProcessEvent() {
            var nullCount = new AtomicInteger(0);
            var otherPayloadMessages = PodamFactoryUtils.manufacturePojoList(podamFactory, String.class);
            var usualPayloadMessages = PodamFactoryUtils.manufacturePojoList(podamFactory, String.class);
            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(config, redissonClient, message -> {
                if (message == null) {
                    nullCount.incrementAndGet();
                }
                return Mono.empty();
            }));
            subscriber.start();

            // Published under a different field, so nothing resolves under the payload field.
            publishMessagesToStream("other-payload", otherPayloadMessages);
            publishMessagesToStream(usualPayloadMessages);

            // Only the well-formed entries reach processEvent and count as successes.
            waitForMessagesProcessed(subscriber, usualPayloadMessages.size());
            // Both sets leave the stream: the payload-less ones as non-retryable failures.
            waitForMessagesAckedAndRemoved();
            assertThat(nullCount.get()).isZero();
        }

        @Test
        void shouldClaimAndProcessPendingMessages() {
            var fastConfig = config.toBuilder()
                    .claimIntervalRatio(2)
                    .pendingMessageDuration(io.dropwizard.util.Duration.seconds(2))
                    .build();
            var messages = PodamFactoryUtils.manufacturePojoList(podamFactory, String.class);
            // No subscriber yet creating consumer group on start, so creating manually first
            stream.createGroup(StreamCreateGroupArgs.name(fastConfig.getConsumerGroupName()).makeStream()).block();
            // Messages will become orphaned quickly due to short pending duration and no subscriber to process them
            publishMessagesToStream(messages);

            // Manually read messages with ack, simulating crashed consumer, so they go to pending state
            var crashedConsumerId = "crashed-consumer-%s".formatted(UUID.randomUUID());
            var streamReadGroupArgs = StreamReadGroupArgs.neverDelivered()
                    .count(messages.size())
                    .timeout(fastConfig.getLongPollingDuration().toJavaDuration());
            var readMessages = stream.readGroup(
                    fastConfig.getConsumerGroupName(), crashedConsumerId, streamReadGroupArgs)
                    .flatMapIterable(Map::entrySet)
                    .map(Map.Entry::getValue)
                    .map(value -> value.get(TestStreamConfiguration.PAYLOAD_FIELD))
                    .collectList()
                    .block();
            assertThat(readMessages).containsExactlyInAnyOrderElementsOf(messages);

            // Verify messages are in pending state (assigned to crashed consumer)
            var pendingMessages = stream.pendingRange(
                    fastConfig.getConsumerGroupName(), crashedConsumerId, StreamMessageId.MIN, StreamMessageId.MAX,
                    messages.size())
                    .flatMapIterable(Map::entrySet)
                    .map(Map.Entry::getValue)
                    .map(value -> value.get(TestStreamConfiguration.PAYLOAD_FIELD))
                    .collectList()
                    .block();
            assertThat(pendingMessages).containsExactlyInAnyOrderElementsOf(messages);

            // Wait enough time for messages to be considered orphaned
            waitForMillis(fastConfig.getPendingMessageDuration().toMilliseconds() + 100);

            // Start new subscriber that should claim orphaned messages
            var processedMessages = new CopyOnWriteArraySet<String>();
            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(fastConfig, redissonClient,
                    message -> Mono.fromRunnable(() -> processedMessages.add(message))));
            subscriber.start();

            // Wait for claiming to happen
            waitForMillis(fastConfig.getPoolingInterval().toMilliseconds() * (fastConfig.getClaimIntervalRatio() + 2));

            // Verify messages were claimed and processed by subscriber
            waitForMessagesProcessed(subscriber, messages.size());
            waitForMessagesAckedAndRemoved();
            assertThat(subscriber.getFailedMessageCount().get()).isZero();
            assertThat(processedMessages).containsExactlyInAnyOrderElementsOf(messages);
        }
    }

    @Nested
    class FailureTests {

        static Stream<Arguments> nonRetryableExceptions() {
            return Stream.of(
                    Arguments.of("NullPointerException",
                            new NullPointerException("Non-retryable")),
                    Arguments.of("NumberFormatException (subclass of IllegalArgumentException)",
                            new NumberFormatException("Non-retryable")),
                    Arguments.of("ClientErrorException (4xx)",
                            new ClientErrorException("Unauthorized", 401)),
                    Arguments.of("NotFoundException (subclass of ClientErrorException)",
                            new NotFoundException()));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("nonRetryableExceptions")
        void shouldAckAndRemoveNonRetryableFailures(String description, RuntimeException exception) {
            var messages = PodamFactoryUtils.manufacturePojoList(podamFactory, String.class);
            var subscriber = trackSubscriber(TestRedisSubscriber.failingSubscriber(
                    config, redissonClient, exception));
            subscriber.start();

            publishMessagesToStream(messages);

            // Wait for processing attempts and verify errors tracked
            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(
                            () -> assertThat(subscriber.getFailedMessageCount().get()).isEqualTo(messages.size()));
            // Non-retryable errors should be removed from the stream
            waitForMessagesAckedAndRemoved();
            assertThat(subscriber.getSuccessMessageCount().get()).isZero();
        }

        /**
         * Rebased off the null-payload path by OPIK-8192: payload-less entries no longer reach
         * {@code processEvent}, so they can no longer be the source of the failures this test is about.
         * The failure is now raised from a real payload, which is what the test always meant to
         * exercise -- that healthy traffic keeps flowing past failing messages.
         */
        @Test
        void shouldContinueProcessingAfterFailedMessages() {
            var failingMessages = PodamFactoryUtils.manufacturePojoList(podamFactory, String.class);
            var usualPayloadMessages = PodamFactoryUtils.manufacturePojoList(podamFactory, String.class);
            var failing = Set.copyOf(failingMessages);
            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(config, redissonClient, message -> {
                if (failing.contains(message)) {
                    // Non-retryable, so it is removed on first delivery rather than re-claimed.
                    return Mono.error(new IllegalArgumentException("Intentional failure"));
                }
                return Mono.empty();
            }));
            subscriber.start();

            publishMessagesToStream(failingMessages);
            publishMessagesToStream(usualPayloadMessages);

            waitForMessagesProcessed(subscriber, usualPayloadMessages.size());
            waitForMessagesAckedAndRemoved();
            assertThat(subscriber.getFailedMessageCount().get()).isEqualTo(failingMessages.size());
        }

        @Test
        void shouldRecoverFromNoGroupOnReadAndContinueProcessing() {
            var messages = PodamFactoryUtils.manufacturePojoList(podamFactory, String.class);
            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(config, redissonClient));
            subscriber.start();

            // Process initial messages to confirm subscriber works
            publishMessagesToStream(messages);
            waitForMessagesProcessed(subscriber, messages.size());
            waitForMessagesAckedAndRemoved();
            assertThat(subscriber.getFailedMessageCount().get()).isZero();
            var countAfterFirstBatch = subscriber.getSuccessMessageCount().get();

            // Delete the stream (which destroys the consumer group)
            stream.delete().block();

            waitForStreamRecovery();

            // Publish new messages after group deletion
            var newMessages = PodamFactoryUtils.manufacturePojoList(podamFactory, String.class);
            publishMessagesToStream(newMessages);

            // Subscriber should recover and process new messages
            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> assertThat(subscriber.getSuccessMessageCount().get())
                            .isEqualTo(countAfterFirstBatch + newMessages.size()));
            waitForMessagesAckedAndRemoved();
            assertThat(subscriber.getFailedMessageCount().get()).isZero();
        }
    }

    @Nested
    class RetryTests {

        @Test
        void shouldAckAndRemoveAfterMaxRetries() {
            var fastConfig = config.toBuilder()
                    .claimIntervalRatio(2)
                    .pendingMessageDuration(io.dropwizard.util.Duration.milliseconds(500))
                    .maxRetries(2)
                    .build();
            var messages = PodamFactoryUtils.manufacturePojoList(podamFactory, String.class);
            var subscriber = trackSubscriber(TestRedisSubscriber.failingRetriesSubscriber(fastConfig, redissonClient));
            subscriber.start();

            publishMessagesToStream(messages);

            // Messages should fail up to max retries
            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(
                            () -> assertThat(subscriber.getFailedMessageCount().get())
                                    .isEqualTo(messages.size() * fastConfig.getMaxRetries()));
            // Messages should be eventually removed after max retries
            waitForMessagesAckedAndRemoved();
            assertThat(subscriber.getSuccessMessageCount().get()).isZero();
        }

        @Test
        void shouldHandleMixedSuccessRetryableAndNonRetryableMessagesInSameBatch() {
            var nonRetryableMessages = PodamFactoryUtils.manufacturePojoList(podamFactory, String.class);
            var retryableMessages = PodamFactoryUtils.manufacturePojoList(podamFactory, String.class);
            var successMessages = PodamFactoryUtils.manufacturePojoList(podamFactory, String.class);
            var attemptCount = new ConcurrentHashMap<String, AtomicInteger>();
            var fastConfig = config.toBuilder()
                    // Set batch size to total messages to process them all at once
                    .consumerBatchSize(nonRetryableMessages.size() + retryableMessages.size() + successMessages.size())
                    .claimIntervalRatio(2)
                    .pendingMessageDuration(io.dropwizard.util.Duration.milliseconds(500))
                    .build();
            var subscriber = trackSubscriber(
                    TestRedisSubscriber.createSubscriber(fastConfig, redissonClient, message -> {
                        var counter = attemptCount.computeIfAbsent(message, key -> new AtomicInteger());
                        var attempts = counter.incrementAndGet();
                        if (nonRetryableMessages.contains(message)) {
                            // Fail with permanent error non-retryable messages
                            return Mono.error(new NullPointerException("Permanent error"));
                        } else if (retryableMessages.contains(message) && attempts == 1) {
                            // Fail with temporary error on first attempt for retryable messages
                            return Mono.error(new RuntimeException("Temporary error"));
                        }
                        // Succeed on next retry for retryable messages or for success messages
                        return Mono.empty();
                    }));
            subscriber.start();

            var allMessages = Stream.of(nonRetryableMessages, retryableMessages, successMessages)
                    .flatMap(Collection::stream)
                    .toList();
            publishMessagesToStream(allMessages);

            // Success and retryable should eventually succeed
            waitForMessagesProcessed(subscriber, retryableMessages.size() + successMessages.size());
            // All messages should eventually be removed from stream
            waitForMessagesAckedAndRemoved();
            // Messages should fail for non retryable and only on the first attempt for retryable messages
            assertThat(subscriber.getFailedMessageCount().get())
                    .isEqualTo(nonRetryableMessages.size() + retryableMessages.size());
            // Verify non-retryable messages were attempted only once
            nonRetryableMessages.forEach(msg -> assertThat(attemptCount.get(msg).get()).isEqualTo(1));
            // Verify retryable messages were retried up twice: first failed attempt + second successful retry
            retryableMessages.forEach(msg -> assertThat(attemptCount.get(msg).get()).isEqualTo(2));
            // Verify success messages were attempted only once
            successMessages.forEach(msg -> assertThat(attemptCount.get(msg).get()).isEqualTo(1));
        }
    }

    @Nested
    class LifecycleTests {

        @Test
        void shouldHandleExistingConsumerGroup() {
            var subscriber1 = trackSubscriber(TestRedisSubscriber.createSubscriber(config, redissonClient));
            subscriber1.start();

            var subscriber2 = trackSubscriber(TestRedisSubscriber.createSubscriber(config, redissonClient));
            // Start another subscriber with same group, should handle BUSY GROUP error gracefully
            subscriber2.start();

            var messages = PodamFactoryUtils.manufacturePojoList(podamFactory, String.class);
            publishMessagesToStream(messages);

            // Both subscribers should be able to process messages
            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> assertThat(
                            subscriber1.getSuccessMessageCount().get() +
                                    subscriber2.getSuccessMessageCount().get())
                            .isEqualTo(messages.size()));
            waitForMessagesAckedAndRemoved();
            assertThat(subscriber1.getFailedMessageCount().get()).isZero();
            assertThat(subscriber2.getFailedMessageCount().get()).isZero();
        }

        @Test
        void shouldRemoveConsumerOnStop() {
            var messages = PodamFactoryUtils.manufacturePojoList(podamFactory, String.class);
            // Not tracking the subscriber, as we want to test stop behavior explicitly
            var subscriber = TestRedisSubscriber.createSubscriber(config, redissonClient);
            subscriber.start();
            publishMessagesToStream(messages);
            waitForMessagesProcessed(subscriber, messages.size());
            waitForMessagesAckedAndRemoved();
            assertThat(subscriber.getFailedMessageCount().get()).isZero();

            // Verify this specific consumer exists in the group before stopping
            var consumersBeforeStop = stream.listConsumers(config.getConsumerGroupName()).block();
            assertThat(consumersBeforeStop)
                    .anyMatch(consumer -> subscriber.getConsumerId().equals(consumer.getName()));
            var processedCountBeforeStop = subscriber.getSuccessMessageCount().get();

            subscriber.stop();

            // Verify this specific consumer was removed from the group
            // listConsumers returns an empty Mono (block() -> null) when the group has no
            // consumers, which is the expected state right after removing the last one.
            var consumersAfterStop = stream.listConsumers(config.getConsumerGroupName())
                    .blockOptional()
                    .orElse(List.of());
            assertThat(consumersAfterStop)
                    .noneMatch(consumer -> subscriber.getConsumerId().equals(consumer.getName()));

            // Publish new messages after stop and verify they are not consumed
            var newMessages = PodamFactoryUtils.manufacturePojoList(podamFactory, String.class);
            publishMessagesToStream(newMessages);

            // Wait for multiple pooling intervals
            waitForMillis(config.getPoolingInterval().toMilliseconds() * 3);

            // Verify no new messages were consumed after stop
            waitForMessagesAckedAndRemoved(newMessages.size());
            assertThat(subscriber.getSuccessMessageCount().get()).isEqualTo(processedCountBeforeStop);
            assertThat(subscriber.getFailedMessageCount().get()).isZero();
        }
    }

    private TestRedisSubscriber trackSubscriber(TestRedisSubscriber subscriber) {
        subscribers.add(subscriber);
        return subscriber;
    }

    private void publishMessagesToStream(List<String> messages) {
        publishMessagesToStream(TestStreamConfiguration.PAYLOAD_FIELD, messages);
    }

    private void publishMessagesToStream(String payloadField, List<String> messages) {
        Flux.fromIterable(messages)
                .flatMap(message -> stream.add(StreamAddArgs.entry(payloadField, message)))
                .collectList()
                .block();
    }

    private void waitForMessagesProcessed(TestRedisSubscriber subscriber, int expectedCount) {
        await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(subscriber.getSuccessMessageCount().get()).isEqualTo(expectedCount));
    }

    private void waitForMessagesAckedAndRemoved() {
        waitForMessagesAckedAndRemoved(0);
    }

    private void waitForMessagesAckedAndRemoved(long pendingMessages) {
        await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                // Verify messages were removed from stream
                .untilAsserted(() -> assertThat(stream.size().block()).isEqualTo(pendingMessages));
    }

    private void waitForStreamRecovery() {
        await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(stream.isExists().block()).isTrue());
    }
}
