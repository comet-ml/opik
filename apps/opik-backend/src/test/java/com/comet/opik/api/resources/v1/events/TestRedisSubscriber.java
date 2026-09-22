package com.comet.opik.api.resources.v1.events;

import com.comet.opik.infrastructure.StreamConfiguration;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.stream.StreamMessageId;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Test specific implementation of BaseRedisSubscriber that allows customization
 * of message processing behavior for testing different scenarios.
 */
@Slf4j
public class TestRedisSubscriber extends BaseRedisSubscriber<String> {

    private static final String METRIC_NAMESPACE = "opik.test";
    private static final String METRICS_BASE_NAME = "test_subscriber";

    @Getter
    private final AtomicInteger successMessageCount = new AtomicInteger(0);
    @Getter
    private final AtomicInteger failedMessageCount = new AtomicInteger(0);

    private final Function<String, Mono<Void>> processor;

    /** Null keeps the base class default, which is what every subscriber but one does. */
    private final Function<Map<StreamMessageId, String>, List<MessageGroup<String>>> collapser;

    public TestRedisSubscriber(
            @NonNull StreamConfiguration config,
            @NonNull RedissonReactiveClient redisson,
            @NonNull Function<String, Mono<Void>> processor) {
        this(config, redisson, processor, null);
    }

    public TestRedisSubscriber(
            @NonNull StreamConfiguration config,
            @NonNull RedissonReactiveClient redisson,
            @NonNull Function<String, Mono<Void>> processor,
            Function<Map<StreamMessageId, String>, List<MessageGroup<String>>> collapser) {
        super(config, redisson, TestStreamConfiguration.PAYLOAD_FIELD, METRIC_NAMESPACE, METRICS_BASE_NAME);
        this.processor = processor;
        this.collapser = collapser;
    }

    @Override
    protected List<MessageGroup<String>> collapse(Map<StreamMessageId, String> batch) {
        return collapser == null ? super.collapse(batch) : collapser.apply(batch);
    }

    @Override
    protected Mono<Void> processEvent(String message) {
        return processor.apply(message)
                .doOnSuccess(unused -> successMessageCount.incrementAndGet())
                .doOnError(throwable -> failedMessageCount.incrementAndGet());
    }

    /**
     * Factory method for creating a subscriber that always succeeds.
     */
    public static TestRedisSubscriber createSubscriber(
            StreamConfiguration config,
            RedissonReactiveClient redisson) {
        return createSubscriber(config, redisson, msg -> {
            log.info("Received message: '{}'", msg);
            return Mono.empty();
        });
    }

    /**
     * Factory method for creating a subscriber that always fails.
     */
    public static TestRedisSubscriber failingSubscriber(
            StreamConfiguration config,
            RedissonReactiveClient redisson,
            Throwable throwable) {
        return createSubscriber(config, redisson, msg -> {
            log.warn("Received message (will fail): '{}'", msg);
            return Mono.error(throwable);
        });
    }

    /**
     * Factory method for creating a subscriber that always fails with a retryable exception.
     */
    public static TestRedisSubscriber failingRetriesSubscriber(
            StreamConfiguration config,
            RedissonReactiveClient redisson) {
        return failingSubscriber(config, redisson, new RuntimeException("Test retryable failure"));
    }

    /**
     * Factory method for creating a subscriber with custom processing logic.
     */
    public static TestRedisSubscriber createSubscriber(
            StreamConfiguration config,
            RedissonReactiveClient redisson,
            Function<String, Mono<Void>> processor) {
        return new TestRedisSubscriber(config, redisson, processor);
    }

    /**
     * Factory method for a subscriber that overrides the collapse hook.
     */
    public static TestRedisSubscriber collapsingSubscriber(
            StreamConfiguration config,
            RedissonReactiveClient redisson,
            Function<Map<StreamMessageId, String>, List<MessageGroup<String>>> collapser) {
        return new TestRedisSubscriber(config, redisson, msg -> {
            log.info("Received message: '{}'", msg);
            return Mono.empty();
        }, collapser);
    }
}
