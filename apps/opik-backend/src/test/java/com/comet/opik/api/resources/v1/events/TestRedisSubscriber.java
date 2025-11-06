package com.comet.opik.api.resources.v1.events;

import com.comet.opik.infrastructure.StreamConfiguration;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;

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

    public TestRedisSubscriber(
            @NonNull StreamConfiguration config,
            @NonNull RedissonReactiveClient redisson,
            @NonNull Function<String, Mono<Void>> processor) {
        super(config, redisson, TestStreamConfiguration.PAYLOAD_FIELD, METRIC_NAMESPACE, METRICS_BASE_NAME);
        this.processor = processor;
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
     * Factory method for creating a subscriber that always fails with a non-retryable exception.
     */
    public static TestRedisSubscriber failingNoRetriesSubscriber(
            StreamConfiguration config,
            RedissonReactiveClient redisson) {
        return createSubscriber(config, redisson, msg -> {
            log.warn("Received message (will fail without retries): '{}'", msg);
            return Mono.error(new NullPointerException("Test non-retryable failure for message '%s'".formatted(msg)));
        });
    }

    /**
     * Factory method for creating a subscriber that always fails with a retryable exception.
     */
    public static TestRedisSubscriber failingRetriesSubscriber(
            StreamConfiguration config,
            RedissonReactiveClient redisson) {
        return createSubscriber(config, redisson, msg -> {
            log.warn("Received message (will fail with retries): '{}'", msg);
            return Mono.error(new RuntimeException("Test retryable failure for message '%s'".formatted(msg)));
        });
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
}
