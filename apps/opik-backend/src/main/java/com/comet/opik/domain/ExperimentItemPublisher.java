package com.comet.opik.domain;

import com.comet.opik.api.events.ExperimentItemToProcess;
import com.comet.opik.infrastructure.ExperimentExecutionConfig;
import com.comet.opik.infrastructure.redis.RedisStreamUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLongReactive;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.List;
import java.util.UUID;

@Singleton
@Slf4j
public class ExperimentItemPublisher {

    private final RedissonReactiveClient redisClient;
    private final ExperimentExecutionConfig config;

    @Inject
    public ExperimentItemPublisher(
            @NonNull RedissonReactiveClient redisClient,
            @NonNull @Config("experimentExecution") ExperimentExecutionConfig config) {
        this.redisClient = redisClient;
        this.config = config;
    }

    /**
     * Sets the batch counter atomically, then publishes all messages to the Redis stream.
     * The counter is set BEFORE publishing to prevent the race where a fast consumer
     * decrements to zero before all messages are published.
     */
    public Mono<Void> publish(@NonNull UUID batchId, @NonNull List<ExperimentItemToProcess> messages) {
        if (messages.isEmpty()) {
            return Mono.empty();
        }

        var counterKey = ExperimentExecutionConfig.BATCH_COUNTER_KEY_PREFIX + batchId;
        RAtomicLongReactive counter = redisClient.getAtomicLong(counterKey);

        var stream = redisClient.getStream(config.getStreamName(), config.getCodec());

        return counter.set(messages.size())
                .then(counter.expire(config.getBatchCounterTtl().toJavaDuration()))
                .thenMany(Flux.fromIterable(messages)
                        .flatMap(message -> stream.add(RedisStreamUtils.buildAddArgs(
                                ExperimentExecutionConfig.PAYLOAD_FIELD, message, config))
                                .doOnNext(id -> log.debug("Published experiment item message with ID: '{}'", id))
                                .doOnError(throwable -> log.error("Error publishing experiment item message",
                                        throwable))))
                .then()
                .doOnSuccess(v -> log.info("Published '{}' experiment item messages for batch '{}'",
                        messages.size(), batchId));
    }
}
