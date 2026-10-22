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
import org.redisson.api.stream.PendingEntry;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.api.stream.StreamPendingRangeArgs;
import org.redisson.client.codec.StringCodec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
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

    private static final int ID_CHUNK_SIZE = 500;
    // Only has to beat the entries a consumer holds at once, which is a batch per pod, so one read
    // covers it unless consumers have stalled and left the list to grow.
    private static final int PENDING_PAGE_SIZE = 1_000;

    // StreamMessageId is not Comparable, and its order is the stream's: timestamp, then sequence.
    private static final Comparator<StreamMessageId> BY_ENTRY_ID = Comparator
            .comparingLong(StreamMessageId::getId0)
            .thenComparingLong(StreamMessageId::getId1);

    private final RedissonReactiveClient redisClient;
    private final ExperimentExecutionConfig config;
    private final ExperimentItemLedger itemLedger;

    @Inject
    public ExperimentCancellationService(
            @NonNull RedissonReactiveClient redisClient,
            @NonNull @Config("experimentExecution") ExperimentExecutionConfig config,
            @NonNull ExperimentItemLedger itemLedger) {
        this.redisClient = redisClient;
        this.config = config;
        this.itemLedger = itemLedger;
    }

    public Mono<Void> cancel(@NonNull String workspaceId, @NonNull Collection<UUID> experimentIds) {
        if (experimentIds.isEmpty()) {
            return Mono.empty();
        }

        return Flux.fromIterable(experimentIds)
                .flatMap(experimentId -> bucket(workspaceId, experimentId)
                        .set("1", config.getBatchCounterTtl().toJavaDuration()))
                .then()
                .doOnSuccess(unused -> log.info("Marked experiments as cancelled, experiments '{}', workspaceId '{}'",
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

                    return Flux.fromIterable(Lists.partition(ids, ID_CHUNK_SIZE))
                            .concatMap(list::addAll)
                            .then(list.expire(config.getBatchCounterTtl().toJavaDuration()));
                })
                .then();
    }

    /**
     * Deletes the run's messages that no consumer has taken yet.
     *
     * Reports whether that leaves nothing outstanding. Deleted entries reach no consumer, so nothing
     * else would ever count them. False whenever a drain cannot be established, since claiming
     * one wrongly reports a run finished while it is still going.
     */
    public Mono<Boolean> purgeQueued(@NonNull String workspaceId, @NonNull UUID experimentId) {
        var list = queuedIds(workspaceId, experimentId);

        return list.readAll()
                .flatMap(ids -> {
                    if (ids.isEmpty()) {
                        return Mono.just(false);
                    }

                    var recorded = ids.stream().map(ExperimentCancellationService::parseId).toList();

                    return inFlightIds(Collections.min(recorded, BY_ENTRY_ID), Collections.max(recorded, BY_ENTRY_ID))
                            .flatMap(inFlight -> {
                                var queued = recorded.stream()
                                        .filter(id -> !inFlight.contains(id))
                                        .toList();

                                if (queued.isEmpty()) {
                                    log.info(
                                            "Nothing left to purge for a cancelled experiment, every item is already with"
                                                    + " a consumer, items '{}', experimentId '{}'",
                                            ids.size(), experimentId);
                                    return Mono.just(false);
                                }

                                // Recorded before they are deleted. An entry deleted but not recorded reaches no
                                // consumer and is never counted; one recorded but not deleted is still delivered,
                                // finds its outcome and is skipped. Either step can fail and the run still drains.
                                return itemLedger.recordSkipped(workspaceId, experimentId, queued)
                                        .thenMany(Flux.fromIterable(Lists.partition(queued, ID_CHUNK_SIZE))
                                                .concatMap(chunk -> stream()
                                                        .remove(chunk.toArray(StreamMessageId[]::new))))
                                        .reduce(0L, Long::sum)
                                        .flatMap(removed -> {
                                            log.info("Removed queued messages for a cancelled experiment, removed '{}',"
                                                    + " withConsumer '{}', experimentId '{}'",
                                                    removed, ids.size() - queued.size(), experimentId);
                                            return isDrained(workspaceId, experimentId);
                                        });
                            })
                            // Trimmed, not deleted: a run still publishing goes on appending here,
                            // and the key would take those ids with it, unpurged. Appends go to the
                            // tail, so what was read is the prefix.
                            .flatMap(drained -> list.trim(ids.size(), -1).thenReturn(drained));
                })
                .onErrorResume(error -> {
                    // The mark still stops the run; failing here only means consumers walk what is left
                    // and record it themselves.
                    log.warn("Failed to remove queued messages for a cancelled experiment, experimentId '{}'",
                            experimentId, error);
                    return Mono.just(false);
                });
    }

    /**
     * Forgets what a run put on the queue, once it has drained. The ids exist so that cancelling can
     * delete what no consumer has taken; a run with nothing left to take has no use for them, and
     * only a cancel deletes them otherwise.
     */
    public Mono<Void> discardQueued(@NonNull String workspaceId, @NonNull UUID experimentId) {
        return queuedIds(workspaceId, experimentId)
                .delete()
                .doOnNext(deleted -> {
                    if (deleted) {
                        log.debug("Discarded the queued ids of a drained experiment, experimentId '{}'", experimentId);
                    }
                })
                .onErrorResume(error -> {
                    // The TTL clears them in the end; the run has drained either way
                    log.warn("Failed to discard the queued ids of an experiment, experimentId '{}'", experimentId,
                            error);
                    return Mono.just(false);
                })
                .then();
    }

    /**
     * Whether the purge left nothing outstanding. What remains is what a consumer still holds, and at zero there
     * is none of it left. An entry XDEL did not find was counted all the same: one a consumer took first keeps
     * the outcome it recorded, and one trimmed off the stream will never run.
     */
    private Mono<Boolean> isDrained(String workspaceId, UUID experimentId) {
        return itemLedger.remaining(workspaceId, experimentId)
                .doOnNext(
                        remaining -> log.info("Counted the purge off an experiment, remaining '{}', experimentId '{}'",
                                remaining, experimentId))
                .map(remaining -> remaining <= 0);
    }

    /**
     * The entries a consumer already holds. XDEL does not stop the consumer that has one, so purging
     * these would drain the counter while their calls are still running; their own consumer counts
     * them down when it finishes.
     */
    private Mono<Set<StreamMessageId>> inFlightIds(StreamMessageId from, StreamMessageId to) {
        return pendingPage(from, to)
                .expand(page -> page.size() < PENDING_PAGE_SIZE
                        ? Mono.empty()
                        : pendingPage(nextId(page.getLast().getId()), to))
                .flatMapIterable(page -> page)
                .map(PendingEntry::getId)
                .collect(Collectors.toSet())
                // No consumer group means nothing was ever delivered, so nothing is in flight
                .onErrorResume(error -> isMissingGroup(error)
                        ? Mono.just(Set.<StreamMessageId>of())
                        : Mono.error(error));
    }

    private Mono<List<PendingEntry>> pendingPage(StreamMessageId startId, StreamMessageId endId) {
        return stream()
                .listPending(StreamPendingRangeArgs.groupName(config.getConsumerGroupName())
                        .startId(startId)
                        .endId(endId)
                        .count(PENDING_PAGE_SIZE));
    }

    /** XPENDING's range is inclusive, so the next page starts one sequence past the last entry. */
    private static StreamMessageId nextId(StreamMessageId id) {
        return new StreamMessageId(id.getId0(), id.getId1() + 1);
    }

    private static boolean isMissingGroup(Throwable error) {
        return error.getMessage() != null && error.getMessage().contains("NOGROUP");
    }

    private RStreamReactive<String, Object> stream() {
        return redisClient.getStream(config.getStreamName(), config.getCodec());
    }

    private static StreamMessageId parseId(String value) {
        var parts = value.split("-");
        return new StreamMessageId(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
    }

    /**
     * True for the first caller only. A cancel emptying the queue and a consumer draining the last item
     * can both reach the finish, and a late decrement would otherwise re-run it per item.
     */
    public Mono<Boolean> claimFinish(@NonNull String workspaceId, @NonNull UUID experimentId) {
        return finishClaim(workspaceId, experimentId)
                .setIfAbsent("1", config.getBatchCounterTtl().toJavaDuration());
    }

    private RBucketReactive<String> finishClaim(String workspaceId, UUID experimentId) {
        return redisClient.getBucket(
                ExperimentExecutionConfig.FINISH_CLAIM_KEY_PREFIX + workspaceId + ":" + experimentId,
                StringCodec.INSTANCE);
    }

    /**
     * Whether something has already taken the finish. Set the moment a consumer's counter drains, which
     * is before its status write lands, so it answers sooner than reading the experiment back does.
     */
    public Mono<Boolean> isFinishClaimed(@NonNull String workspaceId, @NonNull UUID experimentId) {
        return finishClaim(workspaceId, experimentId).isExists();
    }

    /**
     * Gives the finish back, for a claimant that could not write how the run ended. Held, it would only
     * keep a stop from reaching a run that no longer has any item left to settle it.
     */
    public Mono<Boolean> releaseFinish(@NonNull String workspaceId, @NonNull UUID experimentId) {
        return finishClaim(workspaceId, experimentId).delete();
    }

    public Mono<Boolean> isCancelled(@NonNull String workspaceId, @NonNull UUID experimentId) {
        return bucket(workspaceId, experimentId).isExists();
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
