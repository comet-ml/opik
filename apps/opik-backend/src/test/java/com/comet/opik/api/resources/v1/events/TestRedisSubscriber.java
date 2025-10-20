package com.comet.opik.api.resources.v1.events;

import com.comet.opik.infrastructure.StreamConfiguration;
import jakarta.inject.Inject;
import lombok.Getter;
import lombok.NonNull;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Test-specific implementation of BaseRedisSubscriber that allows customization
 * of message processing behavior for testing different scenarios.
 */
public class TestRedisSubscriber extends BaseRedisSubscriber<String> {

    private static final String METRIC_NAMESPACE = "opik.test";
    private static final String METRICS_BASE_NAME = "test_subscriber";

    private final Function<String, Mono<Void>> processor;

    @Getter
    private final AtomicInteger processedMessageCount = new AtomicInteger(0);

    @Getter
    private final AtomicInteger failedMessageCount = new AtomicInteger(0);

    /**
     * Constructor required for dependency injection during testing
     */
    @Inject
    public TestRedisSubscriber(@NonNull RedissonReactiveClient redisson) {
        this(TestStreamConfiguration.createWithFastPolling(), redisson, msg -> Mono.empty());
    }

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
                .doOnSuccess(unused -> processedMessageCount.incrementAndGet())
                .doOnError(throwable -> failedMessageCount.incrementAndGet());
    }

    /**
     * Factory method for creating a subscriber that always succeeds.
     */
    public static TestRedisSubscriber successfulSubscriber(
            StreamConfiguration config,
            RedissonReactiveClient redisson) {
        return new TestRedisSubscriber(config, redisson, msg -> Mono.empty());
    }

    /**
     * Factory method for creating a subscriber that always fails.
     */
    public static TestRedisSubscriber failingSubscriber(
            StreamConfiguration config,
            RedissonReactiveClient redisson) {
        return new TestRedisSubscriber(config, redisson,
                msg -> Mono.error(new RuntimeException("Test failure")));
    }

    /**
     * Factory method for creating a subscriber with custom processing logic.
     */
    public static TestRedisSubscriber customSubscriber(
            StreamConfiguration config,
            RedissonReactiveClient redisson,
            Function<String, Mono<Void>> processor) {
        return new TestRedisSubscriber(config, redisson, processor);
    }

    public void resetCounters() {
        processedMessageCount.set(0);
        failedMessageCount.set(0);
    }
}
