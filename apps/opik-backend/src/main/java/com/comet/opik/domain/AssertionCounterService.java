package com.comet.opik.domain;

import com.comet.opik.infrastructure.ExperimentExecutionConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

@Singleton
@Slf4j
public class AssertionCounterService {

    private final RedissonReactiveClient redisClient;
    private final ExperimentExecutionConfig config;

    @Inject
    public AssertionCounterService(
            @NonNull RedissonReactiveClient redisClient,
            @NonNull @Config("experimentExecution") ExperimentExecutionConfig config) {
        this.redisClient = redisClient;
        this.config = config;
    }

    public Mono<Void> setCounters(@NonNull Map<UUID, Long> itemsByExperiment) {
        return Flux.fromIterable(itemsByExperiment.entrySet())
                .flatMap(entry -> {
                    var counter = redisClient.getAtomicLong(counterKey(entry.getKey()));
                    return counter.set(entry.getValue())
                            .then(counter.expire(config.getBatchCounterTtl().toJavaDuration()));
                })
                .then();
    }

    public Mono<Boolean> exists(@NonNull UUID experimentId) {
        return redisClient.getAtomicLong(counterKey(experimentId)).isExists();
    }

    public Mono<Long> decrement(@NonNull UUID experimentId) {
        return redisClient.getAtomicLong(counterKey(experimentId)).decrementAndGet();
    }

    public Mono<Long> adjust(@NonNull UUID experimentId, long delta) {
        return redisClient.getAtomicLong(counterKey(experimentId)).addAndGet(delta);
    }

    public Mono<Void> deleteCounters(@NonNull Collection<UUID> experimentIds) {
        return Flux.fromIterable(experimentIds)
                .flatMap(id -> redisClient.getAtomicLong(counterKey(id)).delete())
                .then();
    }

    private static String counterKey(UUID experimentId) {
        return ExperimentExecutionConfig.ASSERTION_COUNTER_KEY_PREFIX + experimentId;
    }
}
