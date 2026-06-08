package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.redisson.Redisson;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.Config;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
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

        @Test
        void shouldHandleNullPayloadSuccessfully() {
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

            // Publish message with different field (no payload field, will result in null)
            publishMessagesToStream("other-payload", otherPayloadMessages);
            publishMessagesToStream(usualPayloadMessages);

            waitForMessagesProcessed(subscriber, otherPayloadMessages.size() + usualPayloadMessages.size());
            waitForMessagesAckedAndRemoved();
            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> assertThat(nullCount.get()).isEqualTo(otherPayloadMessages.size()));
            assertThat(subscriber.getFailedMessageCount().get()).isZero();
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

        @Test
        void shouldContinueProcessingAfterFailedMessages() {
            var otherPayloadMessages = PodamFactoryUtils.manufacturePojoList(podamFactory, String.class);
            var usualPayloadMessages = PodamFactoryUtils.manufacturePojoList(podamFactory, String.class);
            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(config, redissonClient, message -> {
                if (message == null) {
                    return Mono.error(new NullPointerException("Intentional failure"));
                }
                return Mono.empty();
            }));
            subscriber.start();

            // Publish messages including one with null payload
            publishMessagesToStream("other-payload", otherPayloadMessages);
            publishMessagesToStream(usualPayloadMessages);

            waitForMessagesProcessed(subscriber, usualPayloadMessages.size());
            waitForMessagesAckedAndRemoved();
            assertThat(subscriber.getFailedMessageCount().get()).isEqualTo(otherPayloadMessages.size());
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

    @Nested
    class AdmissionControlTests {

        // 2048 bytes / 1024 bytes-per-permit = 2 permits of budget.
        private static final long TWO_PERMIT_BUDGET = 2048;
        private static final long ONE_PERMIT_WEIGHT = 1024;

        @Test
        void shouldBoundConcurrencyByInFlightBytes() {
            var current = new AtomicInteger();
            var maxConcurrent = new AtomicInteger();
            var release = new CountDownLatch(1);
            var gatedConfig = config.toBuilder().maxInFlightBytes(TWO_PERMIT_BUDGET).build();
            // 5 messages, each weighing 1 permit, against a 2-permit budget → at most 2 run at once.
            var messages = List.of("m1", "m2", "m3", "m4", "m5");
            var subscriber = trackSubscriber(TestRedisSubscriber.gatedSubscriber(gatedConfig, redissonClient,
                    message -> Mono.fromRunnable(() -> {
                        maxConcurrent.accumulateAndGet(current.incrementAndGet(), Math::max);
                        awaitLatch(release);
                        current.decrementAndGet();
                    }),
                    message -> ONE_PERMIT_WEIGHT));
            subscriber.start();

            publishMessagesToStream(messages);

            // Two messages occupy the whole budget; the rest wait at the gate.
            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> assertThat(current.get()).isEqualTo(2));

            release.countDown();
            waitForMessagesProcessed(subscriber, messages.size());
            waitForMessagesAckedAndRemoved();
            // The gate never let more than the 2-permit budget run concurrently.
            assertThat(maxConcurrent.get()).isEqualTo(2);
            assertThat(subscriber.getFailedMessageCount().get()).isZero();
        }

        @Test
        void shouldProcessMessageLargerThanBudgetSolo() {
            var current = new AtomicInteger();
            var maxConcurrent = new AtomicInteger();
            var release = new CountDownLatch(1);
            var gatedConfig = config.toBuilder().maxInFlightBytes(TWO_PERMIT_BUDGET).build();
            // Each message is far larger than the whole budget — clamped to the full budget, so each
            // runs alone instead of deadlocking (liveness).
            var messages = List.of("big1", "big2", "big3");
            var subscriber = trackSubscriber(TestRedisSubscriber.gatedSubscriber(gatedConfig, redissonClient,
                    message -> Mono.fromRunnable(() -> {
                        maxConcurrent.accumulateAndGet(current.incrementAndGet(), Math::max);
                        awaitLatch(release);
                        current.decrementAndGet();
                    }),
                    message -> 1_000_000L));
            subscriber.start();

            publishMessagesToStream(messages);

            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> assertThat(current.get()).isEqualTo(1));

            release.countDown();
            waitForMessagesProcessed(subscriber, messages.size());
            waitForMessagesAckedAndRemoved();
            // Each oversized message ran alone; all still completed.
            assertThat(maxConcurrent.get()).isEqualTo(1);
            assertThat(subscriber.getFailedMessageCount().get()).isZero();
        }

        @Test
        void shouldNotBoundConcurrencyWhenBudgetDisabled() {
            var current = new AtomicInteger();
            var maxConcurrent = new AtomicInteger();
            var release = new CountDownLatch(1);
            // maxInFlightBytes = 0 (default) → gate disabled even though the subscriber opts in and
            // weighs messages; concurrency is the count-only behavior (up to consumerBatchSize).
            var messages = List.of("m1", "m2", "m3", "m4", "m5");
            var subscriber = trackSubscriber(TestRedisSubscriber.gatedSubscriber(config, redissonClient,
                    message -> Mono.fromRunnable(() -> {
                        maxConcurrent.accumulateAndGet(current.incrementAndGet(), Math::max);
                        awaitLatch(release);
                        current.decrementAndGet();
                    }),
                    message -> ONE_PERMIT_WEIGHT));
            subscriber.start();

            publishMessagesToStream(messages);

            // All messages run concurrently (consumerBatchSize = 5) — no byte bound applied.
            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> assertThat(current.get()).isEqualTo(messages.size()));

            release.countDown();
            waitForMessagesProcessed(subscriber, messages.size());
            waitForMessagesAckedAndRemoved();
            assertThat(maxConcurrent.get()).isEqualTo(messages.size());
            assertThat(subscriber.getFailedMessageCount().get()).isZero();
        }

        // Bytes-awareness: with the budget fixed at 4 permits (4096 bytes), the number of evals that
        // may run concurrently is budget / per-message-weight. Heavier messages each consume more of
        // the budget, so fewer run at once — this is what distinguishes the gate from a plain count.
        @ParameterizedTest(name = "weight={0} permits/msg @ 4-permit budget -> max concurrency {1}")
        @CsvSource({"1, 4", "2, 2", "4, 1"})
        void concurrencyScalesInverselyWithPerMessageWeight(int weightPermits, int expectedConcurrent) {
            long budgetBytes = 4 * ONE_PERMIT_WEIGHT;
            long weightBytes = weightPermits * ONE_PERMIT_WEIGHT;
            var current = new AtomicInteger();
            var maxConcurrent = new AtomicInteger();
            var release = new CountDownLatch(1);
            // consumerBatchSize 8 > budget, so the byte budget — not the count — is the binding limit.
            var gatedConfig = config.toBuilder().consumerBatchSize(8).maxInFlightBytes(budgetBytes).build();
            var messages = IntStream.range(0, 8).mapToObj(i -> "m" + i).toList();
            var subscriber = trackSubscriber(TestRedisSubscriber.gatedSubscriber(gatedConfig, redissonClient,
                    message -> Mono.fromRunnable(() -> {
                        maxConcurrent.accumulateAndGet(current.incrementAndGet(), Math::max);
                        awaitLatch(release);
                        current.decrementAndGet();
                    }),
                    message -> weightBytes));
            subscriber.start();

            publishMessagesToStream(messages);

            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> assertThat(current.get()).isEqualTo(expectedConcurrent));

            release.countDown();
            waitForMessagesProcessed(subscriber, messages.size());
            waitForMessagesAckedAndRemoved();
            assertThat(maxConcurrent.get()).isEqualTo(expectedConcurrent);
            assertThat(subscriber.getFailedMessageCount().get()).isZero();
        }

        @Test
        void handlesNullPayloadWhenGatedWithoutLeakingBudget() {
            var nullCount = new AtomicInteger();
            var gatedConfig = config.toBuilder().maxInFlightBytes(TWO_PERMIT_BUDGET).build();
            // Estimator dereferences the message: a null (missing-payload) message must bypass the gate
            // rather than NPE eagerly and escape the reactive failure path. Valid messages still flow
            // through the gate, so a leaked budget would stall them.
            var subscriber = trackSubscriber(TestRedisSubscriber.gatedSubscriber(gatedConfig, redissonClient,
                    message -> {
                        if (message == null) {
                            nullCount.incrementAndGet();
                        }
                        return Mono.empty();
                    },
                    message -> (long) message.length()));
            subscriber.start();

            var malformedMessages = PodamFactoryUtils.manufacturePojoList(podamFactory, String.class);
            var validMessages = PodamFactoryUtils.manufacturePojoList(podamFactory, String.class);
            // Wrong field → null message; then a normal batch through the gate.
            publishMessagesToStream("other-payload", malformedMessages);
            publishMessagesToStream(validMessages);

            waitForMessagesProcessed(subscriber, malformedMessages.size() + validMessages.size());
            waitForMessagesAckedAndRemoved();
            assertThat(nullCount.get()).isEqualTo(malformedMessages.size());
            assertThat(subscriber.getFailedMessageCount().get()).isZero();
        }

        private void awaitLatch(CountDownLatch latch) {
            try {
                latch.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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
