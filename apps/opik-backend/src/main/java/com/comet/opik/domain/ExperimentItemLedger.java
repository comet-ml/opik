package com.comet.opik.domain;

import com.comet.opik.infrastructure.ExperimentExecutionConfig;
import com.google.common.collect.Lists;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.BatchOptions;
import org.redisson.api.RMapReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.StringCodec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.List;
import java.util.UUID;

/**
 * What became of each item of a run, keyed by the stream entry that carried it.
 *
 * Redis delivers a message again whenever its acknowledgement is lost, so one item can reach a consumer more
 * than once. Counting a run down by decrementing turns every such delivery into another decrement, and
 * finishes the run while items are still to come. Recording facts instead makes a repeat harmless: the first
 * outcome for an entry wins, a second changes nothing, and what is left is read off what was recorded.
 *
 * The entry id is the key, not the dataset item: a dataset that runs each item several times queues the same
 * dataset item more than once, and each of those is its own piece of work.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class ExperimentItemLedger {

    private static final int RECORD_CHUNK_SIZE = 500;
    // How many redelivery idle periods a drained run's outcomes outlive the last delivery that saw the drain
    private static final int DRAINED_RETENTION_PERIODS = 6;

    public enum Outcome {
        OK,
        FAILED,
        /** Never run, because its experiment was stopped first. */
        SKIPPED
    }

    private final @NonNull RedissonReactiveClient redisClient;
    private final @NonNull @Config("experimentExecution") ExperimentExecutionConfig config;

    /** Empty while the entry has not been handled. */
    public Mono<Outcome> outcome(@NonNull String workspaceId, @NonNull UUID experimentId,
            @NonNull StreamMessageId entryId) {
        return entries(workspaceId, experimentId).get(entryId.toString()).map(Outcome::valueOf);
    }

    /**
     * Records how the entry ended, unless an earlier delivery already did.
     *
     * The outcome is kept for the full TTL while the run is going, and for a shorter window once it drains (see
     * {@link #expireAfterDrain}): a redelivery that finds no outcome runs the item again.
     */
    public Mono<Void> record(@NonNull String workspaceId, @NonNull UUID experimentId, @NonNull StreamMessageId entryId,
            @NonNull Outcome outcome) {
        return recordAll(workspaceId, experimentId, List.of(entryId), outcome);
    }

    /**
     * Records as skipped the entries a stop is about to delete from the queue, which no consumer has taken. An
     * entry a consumer got to in the meantime keeps the outcome it recorded, so either way it is counted once.
     *
     * Sent a chunk per round trip: a stop runs inside its request, and a single MULTI/EXEC over the tens of
     * thousands of entries a large run purges would hold up every other client of the shared Redis.
     */
    public Mono<Void> recordSkipped(@NonNull String workspaceId, @NonNull UUID experimentId,
            @NonNull List<StreamMessageId> entryIds) {
        return Flux.fromIterable(Lists.partition(entryIds, RECORD_CHUNK_SIZE))
                .concatMap(chunk -> recordAll(workspaceId, experimentId, chunk, Outcome.SKIPPED))
                .then();
    }

    /**
     * The outcomes and the hash's expiry in one MULTI/EXEC. Sent separately, an expiry that failed after the
     * outcomes landed would never be retried: a redelivery finds the outcome and records nothing, so the hash
     * could be left with no TTL at all.
     */
    private Mono<Void> recordAll(String workspaceId, UUID experimentId, List<StreamMessageId> entryIds,
            Outcome outcome) {
        return Mono.defer(() -> {
            var batch = redisClient.createBatch(
                    BatchOptions.defaults().executionMode(BatchOptions.ExecutionMode.IN_MEMORY_ATOMIC));
            RMapReactive<String, String> entries = batch.getMap(
                    ExperimentExecutionConfig.itemEntriesKey(workspaceId, experimentId), StringCodec.INSTANCE);
            entryIds.forEach(entryId -> entries.fastPutIfAbsent(entryId.toString(), outcome.name()));
            entries.expireIfNotSet(config.getBatchCounterTtl().toJavaDuration());
            return batch.execute().then();
        });
    }

    /**
     * Keeps a drained run's outcomes for a few redelivery periods instead of a day. Every delivery that sees the
     * drain sets this again, so an entry whose acknowledgement keeps failing still finds its outcome on return.
     */
    public Mono<Void> expireAfterDrain(@NonNull String workspaceId, @NonNull UUID experimentId) {
        return entries(workspaceId, experimentId)
                .expire(config.getPendingMessageDuration().toJavaDuration().multipliedBy(DRAINED_RETENTION_PERIODS))
                .onErrorResume(error -> {
                    // The full TTL still clears them; the run has drained either way
                    log.warn("Failed to shorten the outcomes of a drained experiment, workspaceId '{}',"
                            + " experimentId '{}'", workspaceId, experimentId, error);
                    return Mono.just(false);
                })
                .then();
    }

    /** Whether the run's total still exists. It is gone once a failed launch discards it, or once it expires. */
    public Mono<Boolean> hasTotal(@NonNull String workspaceId, @NonNull UUID experimentId) {
        return redisClient.getAtomicLong(ExperimentExecutionConfig.itemCounterKey(workspaceId, experimentId))
                .isExists();
    }

    /**
     * Items of the experiment with no outcome yet. Zero or below means it has stopped producing; a missing
     * total reads as zero, so a run whose counters were discarded or expired reads as drained.
     */
    public Mono<Long> remaining(@NonNull String workspaceId, @NonNull UUID experimentId) {
        return Mono.zip(
                redisClient.getAtomicLong(ExperimentExecutionConfig.itemCounterKey(workspaceId, experimentId)).get(),
                entries(workspaceId, experimentId).size())
                .map(totalAndRecorded -> totalAndRecorded.getT1() - totalAndRecorded.getT2());
    }

    /**
     * Read only when the run drains, by a delivery that has not seen the finish claimed. That is a handful of
     * reads per run, not one per item, so it is not worth a counter kept up to date per item.
     */
    public Mono<Long> failures(@NonNull String workspaceId, @NonNull UUID experimentId) {
        return entries(workspaceId, experimentId).readAllValues()
                .map(outcomes -> outcomes.stream()
                        .filter(Outcome.FAILED.name()::equals)
                        .count());
    }

    private RMapReactive<String, String> entries(String workspaceId, UUID experimentId) {
        return redisClient.getMap(ExperimentExecutionConfig.itemEntriesKey(workspaceId, experimentId),
                StringCodec.INSTANCE);
    }
}
