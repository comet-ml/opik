package com.comet.opik.domain;

import com.comet.opik.api.ExperimentStatus;
import com.comet.opik.api.ExperimentUpdate;
import com.comet.opik.infrastructure.ExperimentExecutionConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
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
public class AssertionCounterService {

    private final RedissonReactiveClient redisClient;
    private final ExperimentService experimentService;
    private final ExperimentExecutionConfig config;

    @Inject
    public AssertionCounterService(
            @NonNull RedissonReactiveClient redisClient,
            @NonNull ExperimentService experimentService,
            @NonNull @Config("experimentExecution") ExperimentExecutionConfig config) {
        this.redisClient = redisClient;
        this.experimentService = experimentService;
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

    public Mono<Void> decrementAndFinishIfComplete(
            @NonNull UUID experimentId, @NonNull String workspaceId, @NonNull String userName) {
        return decrement(experimentId)
                .flatMap(remaining -> {
                    if (remaining <= 0) {
                        log.info("Assertion counter reached zero for experiment '{}', finishing", experimentId);
                        return finishExperiment(experimentId, workspaceId, userName);
                    }
                    return Mono.empty();
                })
                .then();
    }

    private Mono<Void> finishExperiment(UUID experimentId, String workspaceId, String userName) {
        var statusUpdate = ExperimentUpdate.builder()
                .status(ExperimentStatus.COMPLETED)
                .build();

        return experimentService.update(experimentId, statusUpdate)
                .then(Mono.defer(() -> experimentService.finishExperiments(Set.of(experimentId))))
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, userName))
                .doOnSuccess(unused -> log.info("Finished experiment '{}' after all assertions completed",
                        experimentId))
                .onErrorResume(error -> {
                    log.error("Failed to finish experiment '{}' after assertions", experimentId, error);
                    return Mono.empty();
                });
    }

    private static String counterKey(UUID experimentId) {
        return ExperimentExecutionConfig.ASSERTION_COUNTER_KEY_PREFIX + experimentId;
    }
}
