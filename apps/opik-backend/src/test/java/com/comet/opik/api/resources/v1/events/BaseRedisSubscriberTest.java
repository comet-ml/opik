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
 * Generally, tests should be created here instead of in the unit test class  {@link BaseRedisSubscriberTest}.
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
        redissonConfig.setCodec(new JsonJacksonCodec(JsonUtils.MAPPER));
        redissonClient = Redisson.create(redissonConfig).reactive();

        config = TestStreamConfiguration.create();
        stream = redissonClient.getStream(config.getStreamName(), config.getCodec());
    }

    @BeforeEach
    void setUp() {
        // Clean up Redis state before each test
        redissonClient.getKeys().flushdb().block();
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
            waitForConsumerGroupReady();

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
            waitForConsumerGroupReady();

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
            waitForConsumerGroupReady();

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
            waitForConsumerGroupReady();

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
            waitForConsumerGroupReady();

            // Publish messages including one with null payload
            publishMessagesToStream("other-payload", otherPayloadMessages);
            publishMessagesToStream(usualPayloadMessages);

            waitForMessagesProcessed(subscriber, usualPayloadMessages.size());
            waitForMessagesAckedAndRemoved(otherPayloadMessages.size());
            assertThat(subscriber.getFailedMessageCount().get()).isEqualTo(otherPayloadMessages.size());
        }
    }

    @Nested
    class LifecycleTests {

        @Test
        void shouldHandleExistingConsumerGroup() {
            var subscriber1 = trackSubscriber(TestRedisSubscriber.createSubscriber(config, redissonClient));
            subscriber1.start();
            waitForConsumerGroupReady();

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
            waitForConsumerGroupReady();
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
            await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        var consumersAfterStop = stream.listConsumers(config.getConsumerGroupName()).block();
                        assertThat(consumersAfterStop)
                                .noneMatch(consumer -> subscriber.getConsumerId().equals(consumer.getName()));
                    });

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

    /**
     * This ensures the subscriber is ready to consume messages.
     */
    private void waitForConsumerGroupReady() {
        await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .until(() -> Boolean.TRUE.equals(
                        stream.listGroups()
                                .flatMapMany(Flux::fromIterable)
                                .any(group -> config.getConsumerGroupName().equals(group.getName()))
                                .block()));
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
