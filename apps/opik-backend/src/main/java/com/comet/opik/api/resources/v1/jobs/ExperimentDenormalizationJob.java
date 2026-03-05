package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.api.events.ExperimentAggregationMessage;
import com.comet.opik.infrastructure.ExperimentDenormalizationConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.jobs.Job;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.stream.StreamAddArgs;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Instant;
import java.util.UUID;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

/**
 * Scheduled job responsible for flushing debounced experiment aggregation events to the Redis stream.
 *
 * <p>Periodically (default 5s, configurable via {@code experimentDenormalization.jobInterval}) this job:
 * <ol>
 *   <li>Queries the Redis ZSET index for members whose debounce window has elapsed.</li>
 *   <li>Each ZSET member encodes both workspaceId and experimentId as {@code "workspaceId:experimentId"},
 *       ensuring cross-workspace isolation for experiments that share the same UUID.</li>
 *   <li>Reads the userName from the associated Redis hash bucket.</li>
 *   <li>Publishes an {@link ExperimentAggregationMessage} to the Redis stream for each ready experiment.</li>
 *   <li>Removes the processed entry from both the ZSET index and the hash bucket.</li>
 *   <li>Handles stale ZSET entries (expired hash) by removing them without publishing.</li>
 * </ol>
 *
 * <p>Uses a ZSET scored by expiry timestamp for O(log(N)+M) index lookups, avoiding full keyspace scans.
 */
@Slf4j
@Singleton
@DisallowConcurrentExecution
public class ExperimentDenormalizationJob extends Job {

    private static final Lock SCAN_LOCK_KEY = new Lock("experiment_denormalization_job:scan_lock");

    private final ExperimentDenormalizationConfig config;
    private final RedissonReactiveClient redisClient;
    private final LockService lockService;

    @Inject
    public ExperimentDenormalizationJob(
            @NonNull @Config("experimentDenormalization") ExperimentDenormalizationConfig config,
            @NonNull RedissonReactiveClient redisClient,
            @NonNull LockService lockService) {
        this.config = config;
        this.redisClient = redisClient;
        this.lockService = lockService;
    }

    @Override
    public void doJob(JobExecutionContext context) {
        if (!config.isEnabled()) {
            log.debug("Experiment denormalization job is disabled, skipping");
            return;
        }

        log.debug("Starting experiment denormalization job - checking for pending experiments");

        lockService.bestEffortLock(
                SCAN_LOCK_KEY,
                Mono.defer(() -> getExperimentsReadyToProcess()
                        .flatMap(this::processExperiment)
                        .onErrorContinue((throwable, experimentId) -> log.error(
                                "Failed to process pending experiment '{}'",
                                experimentId, throwable))
                        .doOnComplete(
                                () -> log.debug(
                                        "Experiment denormalization job finished processing all ready experiments"))
                        .then()),
                Mono.defer(() -> {
                    log.debug(
                            "Could not acquire lock for scanning pending experiments, another job instance is running");
                    return Mono.empty();
                }),
                config.getJobLockTime().toJavaDuration(),
                config.getJobLockWaitTime().toJavaDuration())
                .subscribe(
                        __ -> log.debug("Experiment denormalization job execution completed"),
                        error -> log.error("Experiment denormalization job interrupted while acquiring lock", error));
    }

    /**
     * Queries the ZSET index in pages for experiment IDs whose debounce window has elapsed (score &lt;= now).
     * Uses offset/count pagination to avoid materializing the entire ZSET into memory.
     * Since each processed experiment is removed from the ZSET, we always query from offset 0.
     */
    private Flux<String> getExperimentsReadyToProcess() {
        long nowMillis = Instant.now().toEpochMilli();
        int batchSize = config.getJobBatchSize();
        var index = redisClient.<String>getScoredSortedSet(ExperimentDenormalizationConfig.PENDING_SET_KEY);

        log.debug("Checking for experiments ready to process (up to timestamp: '{}', batchSize: '{}')",
                nowMillis, batchSize);

        return index.valueRange(Double.NEGATIVE_INFINITY, true, nowMillis, true, 0, batchSize)
                .expand(collection -> {
                    if (collection.size() < batchSize) {
                        return Mono.empty();
                    }
                    return index.valueRange(Double.NEGATIVE_INFINITY, true, nowMillis, true, 0, batchSize);
                })
                .flatMapIterable(collection -> collection)
                .map(Object::toString);
    }

    /**
     * Processes a single pending experiment: publishes a stream message and cleans up the Redis state.
     * The {@code member} is a compound key of the form {@code "workspaceId:experimentId"}, which
     * ensures experiments with the same UUID in different workspaces are handled independently.
     * If the hash bucket has already expired (stale ZSET entry), only the ZSET entry is removed.
     */
    private Mono<Void> processExperiment(String member) {
        int separatorIndex = member.indexOf(ExperimentDenormalizationConfig.MEMBER_SEPARATOR);
        String workspaceId = member.substring(0, separatorIndex);
        String experimentIdStr = member.substring(separatorIndex + 1);

        log.debug("Processing pending experiment: '{}' for workspace: '{}'", experimentIdStr, workspaceId);

        var bucket = redisClient.<String, String>getMap(ExperimentDenormalizationConfig.EXPERIMENT_KEY_PREFIX + member);
        var index = redisClient.getScoredSortedSet(ExperimentDenormalizationConfig.PENDING_SET_KEY);
        var stream = redisClient.getStream(config.getStreamName(), config.getCodec());

        return bucket.get(ExperimentDenormalizationConfig.USER_NAME_FIELD)
                .flatMap(userName -> {
                    var message = ExperimentAggregationMessage.builder()
                            .experimentId(UUID.fromString(experimentIdStr))
                            .workspaceId(workspaceId)
                            .userName(userName)
                            .build();

                    return stream.add(StreamAddArgs.entry(ExperimentDenormalizationConfig.PAYLOAD_FIELD, message))
                            .doOnNext(id -> log.debug(
                                    "Enqueued aggregation message for experiment '{}' with stream id '{}'",
                                    experimentIdStr, id))
                            .then(bucket.delete())
                            .then(index.remove(member));
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Stale index entry found with no bucket data, removing member: '{}'", member);
                    return index.remove(member);
                }))
                .then()
                .doOnSuccess(__ -> log.debug("Successfully processed and removed pending experiment: '{}'",
                        experimentIdStr));
    }
}
