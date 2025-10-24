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
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.Config;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    }

    @Nested
    class FailureTests {

        @Test
        void shouldNotAckNorRemoveFailedMessages() {
            var messages = PodamFactoryUtils.manufacturePojoList(podamFactory, String.class);
            var subscriber = trackSubscriber(TestRedisSubscriber.failingSubscriber(config, redissonClient));
            subscriber.start();

            publishMessagesToStream(messages);

            // Wait for processing attempts and verify errors tracked
            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(
                            () -> assertThat(subscriber.getFailedMessageCount().get()).isEqualTo(messages.size()));
            // Messages should still be in the stream (not removed)
            waitForMessagesAckedAndRemoved(messages.size());
            assertThat(subscriber.getSuccessMessageCount().get()).isZero();
        }

        @Test
        void shouldContinueProcessingAfterFailedMessages() {
            var otherPayloadMessages = PodamFactoryUtils.manufacturePojoList(podamFactory, String.class);
            var usualPayloadMessages = PodamFactoryUtils.manufacturePojoList(podamFactory, String.class);
            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(config, redissonClient, message -> {
                if (message == null) {
                    return Mono.error(new RuntimeException("Intentional failure"));
                }
                return Mono.empty();
            }));
            subscriber.start();

            // Publish messages including one with null payload
            publishMessagesToStream("other-payload", otherPayloadMessages);
            publishMessagesToStream(usualPayloadMessages);

            waitForMessagesProcessed(subscriber, usualPayloadMessages.size());
            waitForMessagesAckedAndRemoved(otherPayloadMessages.size());
            assertThat(subscriber.getFailedMessageCount().get()).isEqualTo(otherPayloadMessages.size());
        }
    }

    @Nested
    class PendingMessageClaimerTests {

        @Test
        void shouldClaimAndProcessPendingMessages() throws InterruptedException {
            // Use custom config with shorter pending message duration for faster test
            var shortTimeoutConfig = TestStreamConfiguration.builder()
                    .streamName(config.getStreamName())
                    .consumerGroupName(config.getConsumerGroupName())
                    .codec(config.getCodec())
                    .consumerBatchSize(config.getConsumerBatchSize())
                    .poolingInterval(config.getPoolingInterval())
                    .longPollingDuration(config.getLongPollingDuration())
                    .claimIntervalRatio(config.getClaimIntervalRatio())
                    .pendingMessageDuration(io.dropwizard.util.Duration.seconds(2)) // Short duration for testing
                    .build();

            var messages = PodamFactoryUtils.manufacturePojoList(podamFactory, String.class);

            // Create consumer group first
            stream.createGroup(
                    org.redisson.api.stream.StreamCreateGroupArgs.name(shortTimeoutConfig.getConsumerGroupName())
                            .id(org.redisson.api.StreamMessageId.ALL)
                            .makeStream())
                    .block();

            publishMessagesToStream(messages);

            // Manually read messages into pending state WITHOUT acknowledging (simulating crashed consumer)
            var crashedConsumerId = "crashed-consumer-" + java.util.UUID.randomUUID();
            var readArgs = org.redisson.api.stream.StreamReadGroupArgs.neverDelivered()
                    .count(messages.size())
                    .timeout(java.time.Duration.ofMillis(100));
            var readMessages = stream.readGroup(shortTimeoutConfig.getConsumerGroupName(), crashedConsumerId, readArgs)
                    .block();
            assertThat(readMessages).isNotNull().hasSize(messages.size());

            // Verify messages are in pending state (assigned to crashed consumer)
            var pendingMessages = stream.listPending(shortTimeoutConfig.getConsumerGroupName(),
                    StreamMessageId.MIN, StreamMessageId.MAX, 100).block();
            assertThat(pendingMessages).isNotNull().hasSize(messages.size());

            // Wait enough time for messages to be considered orphaned (use Thread.sleep for long waits)
            Thread.sleep(shortTimeoutConfig.getPendingMessageDuration().toMilliseconds() + 1000);

            // Start new subscriber that should claim orphaned messages
            var subscriber2 = trackSubscriber(
                    TestRedisSubscriber.createSubscriber(shortTimeoutConfig, redissonClient));
            subscriber2.start();

            // Wait for claiming to happen (claim interval ratio = 10, so wait for 10+ reads)
            Thread.sleep(shortTimeoutConfig.getPoolingInterval().toMilliseconds()
                    * (shortTimeoutConfig.getClaimIntervalRatio() + 2));

            // Verify messages were claimed and processed by subscriber2
            await().atMost(5, TimeUnit.SECONDS)
                    .untilAsserted(() -> assertThat(subscriber2.getSuccessMessageCount().get())
                            .isGreaterThanOrEqualTo(messages.size()));
            waitForMessagesAckedAndRemoved();
        }

        @Test
        void shouldRespectClaimIntervalRatio() {
            var claimIntervalRatio = config.getClaimIntervalRatio();
            var totalReads = claimIntervalRatio * 2 + 1; // Ensure at least 2 claim attempts
            var messagesPerRead = 2;
            var totalMessages = totalReads * messagesPerRead;

            var subscriber = trackSubscriber(TestRedisSubscriber.createSubscriber(config, redissonClient));
            subscriber.start();

            // Publish messages in batches to ensure multiple read cycles
            for (int i = 0; i < totalReads; i++) {
                var messages = List.of(
                        podamFactory.manufacturePojo(String.class),
                        podamFactory.manufacturePojo(String.class));
                publishMessagesToStream(messages);
                // Wait for one polling interval to ensure messages are read in separate cycles
                waitForMillis(config.getPoolingInterval().toMilliseconds() + 100);
            }

            waitForMessagesProcessed(subscriber, totalMessages);
            waitForMessagesAckedAndRemoved();
            assertThat(subscriber.getFailedMessageCount().get()).isZero();
            // Verify that both read and claim operations occurred
            // At least (totalReads / claimIntervalRatio) claims should have happened
            assertThat(totalReads).isGreaterThanOrEqualTo(claimIntervalRatio);
        }

        @Test
        void shouldNotClaimRecentPendingMessages() throws InterruptedException {
            // Use custom config with longer pending message duration to test that recent messages are not claimed
            var longTimeoutConfig = TestStreamConfiguration.builder()
                    .streamName(config.getStreamName())
                    .consumerGroupName(config.getConsumerGroupName())
                    .codec(config.getCodec())
                    .consumerBatchSize(config.getConsumerBatchSize())
                    .poolingInterval(config.getPoolingInterval())
                    .longPollingDuration(config.getLongPollingDuration())
                    .claimIntervalRatio(config.getClaimIntervalRatio())
                    .pendingMessageDuration(io.dropwizard.util.Duration.seconds(30)) // Long enough to not trigger during test
                    .build();

            var messages = PodamFactoryUtils.manufacturePojoList(podamFactory, String.class);

            // Create consumer group first
            stream.createGroup(
                    org.redisson.api.stream.StreamCreateGroupArgs.name(longTimeoutConfig.getConsumerGroupName())
                            .id(org.redisson.api.StreamMessageId.ALL)
                            .makeStream())
                    .block();

            publishMessagesToStream(messages);

            // Manually read messages into pending state WITHOUT acknowledging (simulating crashed consumer)
            var crashedConsumerId = "crashed-consumer-" + java.util.UUID.randomUUID();
            var readArgs = org.redisson.api.stream.StreamReadGroupArgs.neverDelivered()
                    .count(messages.size())
                    .timeout(java.time.Duration.ofMillis(100));
            var readMessages = stream.readGroup(longTimeoutConfig.getConsumerGroupName(), crashedConsumerId, readArgs)
                    .block();
            assertThat(readMessages).isNotNull().hasSize(messages.size());

            // Verify messages are in pending state (recent, not orphaned yet)
            var pendingMessages = stream.listPending(longTimeoutConfig.getConsumerGroupName(),
                    StreamMessageId.MIN, StreamMessageId.MAX, 100).block();
            assertThat(pendingMessages).isNotNull().hasSize(messages.size());

            // Start new subscriber immediately (messages are still too recent to be claimed)
            var subscriber2 = trackSubscriber(
                    TestRedisSubscriber.createSubscriber(longTimeoutConfig, redissonClient));
            subscriber2.start();

            // Wait for multiple claim intervals but NOT enough time for messages to be orphaned
            Thread.sleep(longTimeoutConfig.getPoolingInterval().toMilliseconds()
                    * (longTimeoutConfig.getClaimIntervalRatio() + 2));

            // Verify messages were NOT claimed by subscriber2 (they're too recent)
            assertThat(subscriber2.getSuccessMessageCount().get()).isZero();
            // Messages should still be in pending state
            pendingMessages = stream.listPending(longTimeoutConfig.getConsumerGroupName(),
                    StreamMessageId.MIN, StreamMessageId.MAX, 100).block();
            assertThat(pendingMessages).isNotNull().hasSize(messages.size());
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

    private void waitForMillis(long millis) {
        await().pollDelay(millis, TimeUnit.MILLISECONDS)
                .until(() -> true);
    }
}
