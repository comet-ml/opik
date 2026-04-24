package com.comet.opik.domain;

import com.comet.opik.api.ExperimentStatus;
import com.comet.opik.api.ExperimentUpdate;
import com.comet.opik.infrastructure.ExperimentExecutionConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.redisson.api.RAtomicLongReactive;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

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

    public Mono<Void> setCounters(@NonNull String workspaceId, Map<UUID, Long> itemsByExperiment) {
        if (MapUtils.isEmpty(itemsByExperiment)) {
            return Mono.empty();
        }
        return Flux.fromIterable(itemsByExperiment.entrySet())
                .flatMap(entry -> {
                    var counter = redisClient.getAtomicLong(counterKey(workspaceId, entry.getKey()));
                    return counter.set(entry.getValue())
                            .then(counter.expire(config.getBatchCounterTtl().toJavaDuration()));
                })
                .then();
    }

    public Mono<Boolean> exists(@NonNull String workspaceId, @NonNull UUID experimentId) {
        return redisClient.getAtomicLong(counterKey(workspaceId, experimentId)).isExists();
    }

    public Mono<Long> decrement(@NonNull String workspaceId, @NonNull UUID experimentId) {
        var counter = redisClient.getAtomicLong(counterKey(workspaceId, experimentId));
        return withExpiryRenewal(counter, counter.decrementAndGet());
    }

    public Mono<Long> adjust(@NonNull String workspaceId, @NonNull UUID experimentId, long delta) {
        var counter = redisClient.getAtomicLong(counterKey(workspaceId, experimentId));
        return withExpiryRenewal(counter, counter.addAndGet(delta));
    }

    private Mono<Long> withExpiryRenewal(RAtomicLongReactive counter, Mono<Long> operation) {
        return operation.flatMap(result -> counter.expire(config.getBatchCounterTtl().toJavaDuration())
                .thenReturn(result));
    }

    public Mono<Void> decrementAndFinishIfComplete(@NonNull String workspaceId, @NonNull UUID experimentId) {
        return exists(workspaceId, experimentId)
                .flatMap(keyExists -> {
                    if (!keyExists) {
                        log.warn("Assertion counter key not found for experiment '{}', skipping", experimentId);
                        return Mono.<Void>empty();
                    }
                    return decrement(workspaceId, experimentId)
                            .flatMap(remaining -> {
                                if (remaining <= 0) {
                                    log.info("Assertion counter reached zero for experiment '{}', finishing",
                                            experimentId);
                                    return finishExperiment(experimentId);
                                }
                                return Mono.<Void>empty();
                            })
                            .then();
                });
    }

    // TODO: deduplicate with ExperimentItemProcessingSubscriber.finishExperiments — extract into
    //  a shared ExperimentFinishListener triggered by an ExperimentProcessed event
    private Mono<Void> finishExperiment(UUID experimentId) {
        var statusUpdate = ExperimentUpdate.builder()
                .status(ExperimentStatus.COMPLETED)
                .build();

        return experimentService.update(experimentId, statusUpdate)
                .then(experimentService.finishExperiments(Set.of(experimentId)))
                .doOnSuccess(unused -> log.info("Finished experiment '{}' after all assertions completed",
                        experimentId));
    }

    private static String counterKey(String workspaceId, UUID experimentId) {
        return ExperimentExecutionConfig.TEST_SUITE_ASSERTION_COUNTER_KEY_PREFIX + workspaceId + ":" + experimentId;
    }
}
