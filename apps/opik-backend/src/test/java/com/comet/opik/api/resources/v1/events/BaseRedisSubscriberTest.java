package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.redisson.Redisson;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamCreateGroupArgs;
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

        @Test
        void shouldAckAndRemoveNonRetryableFailures() {
            var messages = PodamFactoryUtils.manufacturePojoList(podamFactory, String.class);
            var subscriber = trackSubscriber(TestRedisSubscriber.failingNoRetriesSubscriber(config, redissonClient));
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
            var consumersAfterStop = stream.listConsumers(config.getConsumerGroupName()).block();
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
}
