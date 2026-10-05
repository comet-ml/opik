package com.comet.opik.domain;

import com.comet.opik.infrastructure.ExperimentExecutionConfig;
import com.google.common.collect.Lists;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucketReactive;
import org.redisson.api.RListReactive;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.StringCodec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Which experiments the user has asked to stop.
 *
 * Stopping does two things. It marks the experiment, which is what stops the items already handed
 * to a consumer; and it deletes the ones still queued, which is what stops the next run waiting
 * behind them. The mark alone leaves the consumer to walk every remaining message, which can
 * delay the next one.
 *
 * The mark outlives the counter it races against, so an experiment cannot be reported finished by
 * a batch that drained after the mark expired.
 */
@Singleton
@Slf4j
public class ExperimentCancellationService {

    private static final int PURGE_CHUNK_SIZE = 500;

    private final RedissonReactiveClient redisClient;
    private final ExperimentExecutionConfig config;

    @Inject
    public ExperimentCancellationService(
            @NonNull RedissonReactiveClient redisClient,
            @NonNull @Config("experimentExecution") ExperimentExecutionConfig config) {
        this.redisClient = redisClient;
        this.config = config;
    }

    public Mono<Void> cancel(@NonNull String workspaceId, @NonNull Collection<UUID> experimentIds) {
        if (experimentIds.isEmpty()) {
            return Mono.empty();
        }

        return Flux.fromIterable(experimentIds)
                .flatMap(experimentId -> bucket(workspaceId, experimentId)
                        .set("1", config.getBatchCounterTtl().toJavaDuration()))
                .then()
                .doOnSuccess(unused -> log.info("Marked '{}' experiments as cancelled, workspaceId '{}'",
                        experimentIds.size(), workspaceId));
    }

    /**
     * Remembers which stream entries a run put on the queue, so cancelling it can delete the ones no
     * consumer has taken rather than leaving them to be walked one by one.
     */
    public Mono<Void> recordQueued(@NonNull String workspaceId,
            @NonNull Map<UUID, Collection<StreamMessageId>> idsByExperiment) {

        return Flux.fromIterable(idsByExperiment.entrySet())
                .flatMap(entry -> {
                    var list = queuedIds(workspaceId, entry.getKey());
                    var ids = entry.getValue().stream().map(StreamMessageId::toString).toList();

                    return list.addAll(ids)
                            .then(list.expire(config.getBatchCounterTtl().toJavaDuration()));
                })
                .then();
    }

    /**
     * Deletes the run's messages that no consumer has taken yet.
     *
     * Reports whether that leaves nothing outstanding. Deleted entries reach no consumer, so nothing
     * else would ever count them down. False whenever a drain cannot be established, since claiming
     * one wrongly reports a run finished while it is still going.
     */
    public Mono<Boolean> purgeQueued(@NonNull String workspaceId, @NonNull UUID experimentId) {
        var list = queuedIds(workspaceId, experimentId);

        return list.readAll()
                .flatMap(ids -> {
                    if (ids.isEmpty()) {
                        return Mono.just(false);
                    }

                    return Flux.fromIterable(Lists.partition(ids, PURGE_CHUNK_SIZE))
                            .concatMap(chunk -> stream().remove(chunk.stream()
                                    .map(ExperimentCancellationService::parseId)
                                    .toArray(StreamMessageId[]::new)))
                            .reduce(0L, Long::sum)
                            .flatMap(removed -> {
                                log.info("Removed '{}' queued messages for cancelled experiment '{}'",
                                        removed, experimentId);
                                return releaseItemSlots(experimentId, removed);
                            });
                })
                .flatMap(drained -> list.delete().thenReturn(drained))
                .onErrorResume(error -> {
                    // The mark still stops the run; failing here only means the consumer walks what is
                    // left and counts it down itself.
                    log.warn("Failed to remove queued messages for cancelled experiment '{}'", experimentId, error);
                    return Mono.just(false);
                });
    }

    /**
     * Subtracts the purged messages from the experiment's own counter. What remains is what a
     * consumer still holds, and at zero there is none of it left.
     */
    private Mono<Boolean> releaseItemSlots(UUID experimentId, long removed) {
        if (removed == 0) {
            return Mono.just(false);
        }

        return redisClient
                .getAtomicLong(ExperimentExecutionConfig.itemCounterKey(experimentId))
                .addAndGet(-removed)
                .doOnNext(remaining -> log.info("Experiment '{}' has '{}' items left after the purge",
                        experimentId, remaining))
                .map(remaining -> remaining <= 0);
    }

    private RStreamReactive<String, Object> stream() {
        return redisClient.getStream(config.getStreamName(), config.getCodec());
    }

    private static StreamMessageId parseId(String value) {
        var parts = value.split("-");
        return new StreamMessageId(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
    }

    public Mono<Boolean> isCancelled(@NonNull String workspaceId, @NonNull UUID experimentId) {
        return bucket(workspaceId, experimentId).isExists();
    }

    public Mono<Set<UUID>> filterNotCancelled(@NonNull String workspaceId, @NonNull Collection<UUID> experimentIds) {
        return Flux.fromIterable(experimentIds)
                .filterWhen(experimentId -> isCancelled(workspaceId, experimentId).map(cancelled -> !cancelled))
                .collect(Collectors.toSet());
    }

    private RListReactive<String> queuedIds(String workspaceId, UUID experimentId) {
        return redisClient.getList(
                ExperimentExecutionConfig.QUEUED_IDS_KEY_PREFIX + workspaceId + ":" + experimentId,
                StringCodec.INSTANCE);
    }

    private RBucketReactive<String> bucket(String workspaceId, UUID experimentId) {
        return redisClient.getBucket(
                ExperimentExecutionConfig.CANCELLED_EXPERIMENT_KEY_PREFIX + workspaceId + ":" + experimentId);
    }
}
