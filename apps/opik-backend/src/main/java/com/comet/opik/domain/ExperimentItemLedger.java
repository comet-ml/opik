package com.comet.opik.domain;

import com.comet.opik.infrastructure.ExperimentExecutionConfig;
import com.google.common.collect.Lists;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMapReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.StringCodec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
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
@Slf4j
public class ExperimentItemLedger {

    private static final int RECORD_CHUNK_SIZE = 500;

    public enum Outcome {
        OK,
        FAILED,
        /** Never run, because its experiment was stopped first. */
        SKIPPED
    }

    private final RedissonReactiveClient redisClient;
    private final ExperimentExecutionConfig config;

    @Inject
    public ExperimentItemLedger(
            @NonNull RedissonReactiveClient redisClient,
            @NonNull @Config("experimentExecution") ExperimentExecutionConfig config) {
        this.redisClient = redisClient;
        this.config = config;
    }

    /** Empty while the entry has not been handled. */
    public Mono<Outcome> outcome(@NonNull UUID experimentId, @NonNull StreamMessageId entryId) {
        return entries(experimentId).get(entryId.toString()).map(Outcome::valueOf);
    }

    /**
     * Records how the entry ended, unless an earlier delivery already did. True for the first record only, so a
     * caller can attach a step that must happen once per item.
     */
    public Mono<Boolean> record(@NonNull UUID experimentId, @NonNull StreamMessageId entryId,
            @NonNull Outcome outcome) {
        var entries = entries(experimentId);

        return entries.fastPutIfAbsent(entryId.toString(), outcome.name())
                .flatMap(recordedNow -> entries.expireIfNotSet(config.getBatchCounterTtl().toJavaDuration())
                        .thenReturn(recordedNow));
    }

    /**
     * Records entries a stop removed from the queue before any consumer took them. An entry a consumer got to
     * in the meantime keeps the outcome it recorded, so either way it is counted once.
     *
     * Pipelined a chunk at a time and expired once, since a stop runs inside its request and a large run
     * purges tens of thousands of entries.
     */
    public Mono<Void> recordSkipped(@NonNull UUID experimentId, @NonNull List<StreamMessageId> entryIds) {
        var key = ExperimentExecutionConfig.itemEntriesKey(experimentId);

        return Flux.fromIterable(Lists.partition(entryIds, RECORD_CHUNK_SIZE))
                .concatMap(chunk -> {
                    var batch = redisClient.createBatch();
                    RMapReactive<String, String> entries = batch.getMap(key, StringCodec.INSTANCE);
                    chunk.forEach(entryId -> entries.fastPutIfAbsent(entryId.toString(), Outcome.SKIPPED.name()));
                    return batch.execute();
                })
                .then(entries(experimentId).expireIfNotSet(config.getBatchCounterTtl().toJavaDuration()))
                .then();
    }

    /**
     * Items of the experiment with no outcome yet. Zero or below means it has stopped producing; a missing
     * total reads as zero, so a run whose counters were discarded or expired reads as drained.
     */
    public Mono<Long> remaining(@NonNull UUID experimentId) {
        return Mono.zip(
                redisClient.getAtomicLong(ExperimentExecutionConfig.itemCounterKey(experimentId)).get(),
                entries(experimentId).size())
                .map(totalAndRecorded -> totalAndRecorded.getT1() - totalAndRecorded.getT2());
    }

    /** Read once, when the run drains, so it is not worth a counter kept up to date per item. */
    public Mono<Long> failures(@NonNull UUID experimentId) {
        return entries(experimentId).readAllValues()
                .map(outcomes -> outcomes.stream()
                        .filter(Outcome.FAILED.name()::equals)
                        .count());
    }

    /**
     * Shortens how long the outcomes outlive a drained run. They are one field per item, so keeping them for the
     * full TTL holds a large run's worth for a day. Deleting them outright reopens the duplicate they exist to
     * stop: the last items' acknowledgements can still be lost, and a redelivery that finds no outcome runs the
     * item again. Every redelivery happens within maxRetries idle periods, so that is how long they are kept.
     */
    public Mono<Void> expireAfterDrain(@NonNull UUID experimentId) {
        return entries(experimentId)
                .expireIfLess(redeliveryWindow())
                .onErrorResume(error -> {
                    // The TTL still clears them; the run has drained either way
                    log.warn("Failed to shorten the outcomes of drained experiment '{}'", experimentId, error);
                    return Mono.just(false);
                })
                .then();
    }

    private Duration redeliveryWindow() {
        return config.getPendingMessageDuration().toJavaDuration().multipliedBy(config.getMaxRetries());
    }

    private RMapReactive<String, String> entries(UUID experimentId) {
        return redisClient.getMap(ExperimentExecutionConfig.itemEntriesKey(experimentId), StringCodec.INSTANCE);
    }
}
