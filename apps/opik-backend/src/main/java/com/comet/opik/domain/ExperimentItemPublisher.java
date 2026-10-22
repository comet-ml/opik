package com.comet.opik.domain;

import com.comet.opik.api.events.ExperimentItemToProcess;
import com.comet.opik.infrastructure.ExperimentExecutionConfig;
import com.comet.opik.infrastructure.redis.RedisStreamUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.redisson.api.RAtomicLongReactive;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Singleton
@Slf4j
public class ExperimentItemPublisher {

    private final RedissonReactiveClient redisClient;
    private final ExperimentExecutionConfig config;
    private final TestSuiteAssertionCounterService testSuiteAssertionCounterService;
    private final ExperimentCancellationService cancellationService;
    private final ExperimentItemLedger itemLedger;

    @Inject
    public ExperimentItemPublisher(
            @NonNull RedissonReactiveClient redisClient,
            @NonNull @Config("experimentExecution") ExperimentExecutionConfig config,
            @NonNull TestSuiteAssertionCounterService testSuiteAssertionCounterService,
            @NonNull ExperimentCancellationService cancellationService,
            @NonNull ExperimentItemLedger itemLedger) {
        this.redisClient = redisClient;
        this.config = config;
        this.testSuiteAssertionCounterService = testSuiteAssertionCounterService;
        this.cancellationService = cancellationService;
        this.itemLedger = itemLedger;
    }

    /**
     * Starts tracking a run, before anything of it is published or made visible. A stop reaches
     * only what this service is tracking, and the experiments become stoppable the moment they
     * are persisted — so a run tracked any later than this has a window where a stop finds nothing to
     * stop, returns as though it had, and the run goes on to queue every item.
     */
    public Mono<Void> startTrackingRun(List<ExperimentItemToProcess> messages, boolean testSuite) {
        if (CollectionUtils.isEmpty(messages)) {
            return Mono.empty();
        }

        return setItemCounters(messages)
                .then(testSuite ? setAssertionCounters(messages) : Mono.empty());
    }

    /** Publishes all messages to the Redis stream. Counters are already set by {@link #startTrackingRun}. */
    public Mono<Void> publish(@NonNull UUID batchId, List<ExperimentItemToProcess> messages) {
        if (CollectionUtils.isEmpty(messages)) {
            return Mono.empty();
        }

        var stream = redisClient.getStream(config.getStreamName(), config.getCodec());

        return Flux.fromIterable(messages)
                .flatMap(message -> stream.add(RedisStreamUtils.buildAddArgs(
                        ExperimentExecutionConfig.PAYLOAD_FIELD, message, config))
                        .doOnNext(id -> log.debug("Published experiment item message with ID: '{}'", id))
                        .map(id -> Map.entry(message.experimentId(), id)))
                .collectMultimap(Map.Entry::getKey, Map.Entry::getValue)
                .flatMap(idsByExperiment -> cancellationService.recordQueued(
                        messages.getFirst().workspaceId(), idsByExperiment))
                .doOnSuccess(v -> log.info("Published '{}' experiment item messages for batch '{}'",
                        messages.size(), batchId));
    }

    /**
     * How much is already queued, across every run and every workspace. The stream is shared and
     * trimmed by length, so what another tenant has in flight decides whether this run fits.
     */
    public Mono<Long> queuedMessageCount() {
        return redisClient.getStream(config.getStreamName(), config.getCodec()).size();
    }

    /**
     * Whether this service is still counting a run down. Experiments logged by an SDK never had a
     * counter, and one whose counter has expired has no consumer left to tell either — stopping
     * those writes a status nothing will ever stamp finished, which reads as a run still going.
     */
    public Mono<Boolean> isTracked(@NonNull String workspaceId, @NonNull UUID experimentId) {
        return itemLedger.hasTotal(workspaceId, experimentId);
    }

    /**
     * Removes a run's item totals and recorded outcomes. A launch that failed partway leaves totals no one
     * will ever reach, and outcomes nothing will read, holding one field per item until the TTL.
     */
    public Mono<Void> discardCounters(@NonNull String workspaceId, @NonNull Collection<UUID> experimentIds) {
        if (CollectionUtils.isEmpty(experimentIds)) {
            return Mono.empty();
        }

        var keys = experimentIds.stream()
                .flatMap(experimentId -> Stream.of(
                        ExperimentExecutionConfig.itemCounterKey(workspaceId, experimentId),
                        ExperimentExecutionConfig.itemEntriesKey(workspaceId, experimentId)))
                .toArray(String[]::new);

        return redisClient.getKeys().delete(keys)
                .then()
                .onErrorResume(error -> {
                    // The TTL still clears them; failing the abort over it would lose the status write
                    log.warn("Failed to discard counters for '{}' experiments", experimentIds.size(), error);
                    return Mono.empty();
                });
    }

    /**
     * One total per prompt variant, all set before the first message is published so a fast
     * consumer cannot drain a variant before its own total exists.
     */
    private Mono<Void> setItemCounters(List<ExperimentItemToProcess> messages) {
        var workspaceId = messages.getFirst().workspaceId();
        var itemsByExperiment = messages.stream()
                .collect(Collectors.groupingBy(ExperimentItemToProcess::experimentId, Collectors.counting()));

        return Flux.fromIterable(itemsByExperiment.entrySet())
                .flatMap(entry -> {
                    RAtomicLongReactive counter = redisClient
                            .getAtomicLong(ExperimentExecutionConfig.itemCounterKey(workspaceId, entry.getKey()));

                    return counter.set(entry.getValue())
                            .then(counter.expire(config.getBatchCounterTtl().toJavaDuration()));
                })
                .then();
    }

    private Mono<Void> setAssertionCounters(List<ExperimentItemToProcess> messages) {
        var workspaceId = messages.getFirst().workspaceId();
        var itemsByExperiment = messages.stream()
                .filter(m -> m.experimentId() != null)
                .collect(Collectors.groupingBy(ExperimentItemToProcess::experimentId, Collectors.counting()));

        return testSuiteAssertionCounterService.setCounters(workspaceId, itemsByExperiment);
    }
}
