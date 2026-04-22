package com.comet.opik.domain;

import com.comet.opik.api.ExperimentStatus;
import com.comet.opik.api.ExperimentUpdate;
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
import java.util.Set;
import java.util.UUID;

@Singleton
@Slf4j
public class TestSuiteAssertionCounterService {

    private final RedissonReactiveClient redisClient;
    private final ExperimentExecutionConfig config;
    private final ExperimentService experimentService;

    @Inject
    public TestSuiteAssertionCounterService(
            @NonNull RedissonReactiveClient redisClient,
            @NonNull @Config("experimentExecution") ExperimentExecutionConfig config,
            @NonNull ExperimentService experimentService) {
        this.redisClient = redisClient;
        this.config = config;
        this.experimentService = experimentService;
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

    public Mono<Void> decrementAndFinishIfComplete(@NonNull UUID experimentId) {
        return decrement(experimentId)
                .flatMap(remaining -> {
                    if (remaining <= 0) {
                        log.info("Assertion counter reached zero for experiment '{}', finishing", experimentId);
                        return finishExperiment(experimentId);
                    }
                    return Mono.empty();
                })
                .then();
    }

    private Mono<Void> finishExperiment(UUID experimentId) {
        var statusUpdate = ExperimentUpdate.builder()
                .status(ExperimentStatus.COMPLETED)
                .build();

        return experimentService.update(experimentId, statusUpdate)
                .then(experimentService.finishExperiments(Set.of(experimentId)))
                .doOnSuccess(unused -> log.info("Finished experiment '{}' after all assertions completed",
                        experimentId))
                .onErrorResume(error -> {
                    log.error("Failed to finish experiment '{}' after assertions", experimentId, error);
                    return Mono.empty();
                });
    }

    public Mono<Void> deleteCounters(@NonNull Collection<UUID> experimentIds) {
        return Flux.fromIterable(experimentIds)
                .flatMap(id -> redisClient.getAtomicLong(counterKey(id)).delete())
                .then();
    }

    private static String counterKey(UUID experimentId) {
        return ExperimentExecutionConfig.TEST_SUITE_ASSERTION_COUNTER_KEY_PREFIX + experimentId;
    }
}
