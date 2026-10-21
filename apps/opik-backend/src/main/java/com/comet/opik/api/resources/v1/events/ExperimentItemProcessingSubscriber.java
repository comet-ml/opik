package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.ExperimentStatus;
import com.comet.opik.api.ExperimentUpdate;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.events.ExperimentItemToProcess;
import com.comet.opik.domain.ExperimentCancellationService;
import com.comet.opik.domain.ExperimentItemLedger;
import com.comet.opik.domain.ExperimentItemLedger.Outcome;
import com.comet.opik.domain.ExperimentItemProcessor;
import com.comet.opik.domain.ExperimentService;
import com.comet.opik.infrastructure.ExperimentExecutionConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.stream.StreamMessageId;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.Set;

@EagerSingleton
@Slf4j
public class ExperimentItemProcessingSubscriber extends BaseRedisSubscriber<ExperimentItemToProcess> {

    private static final String SUBSCRIBER_NAMESPACE = "experiment_item_processing";
    private static final String METRICS_BASE_NAME = "experiment_item_processing_subscriber";

    private final ExperimentItemProcessor itemProcessor;
    private final ExperimentService experimentService;
    private final ExperimentCancellationService cancellationService;
    private final ExperimentItemLedger itemLedger;
    private final ExperimentExecutionConfig config;

    @Inject
    protected ExperimentItemProcessingSubscriber(
            @NonNull @Config("experimentExecution") ExperimentExecutionConfig config,
            @NonNull RedissonReactiveClient redisson,
            @NonNull ExperimentItemProcessor itemProcessor,
            @NonNull ExperimentService experimentService,
            @NonNull ExperimentCancellationService cancellationService,
            @NonNull ExperimentItemLedger itemLedger) {
        super(config, redisson, ExperimentExecutionConfig.PAYLOAD_FIELD, SUBSCRIBER_NAMESPACE, METRICS_BASE_NAME);
        this.itemProcessor = itemProcessor;
        this.experimentService = experimentService;
        this.cancellationService = cancellationService;
        this.itemLedger = itemLedger;
        this.config = config;
    }

    @Override
    public void start() {
        if (!config.isEnabled()) {
            log.info("Experiment item processing subscriber is disabled");
            return;
        }
        log.info("Starting experiment item processing subscriber with streamName='{}', consumerGroupName='{}'",
                config.getStreamName(), config.getConsumerGroupName());
        super.start();
    }

    @Override
    public void stop() {
        if (!config.isEnabled()) {
            log.info("Experiment item processing subscriber is disabled");
            return;
        }
        log.info("Stopping experiment item processing subscriber");
        super.stop();
    }

    /** Only the entry-aware overload is reached: the base subscriber always knows which entry it is handling. */
    @Override
    protected Mono<Void> processEvent(ExperimentItemToProcess message) {
        return Mono.error(new UnsupportedOperationException("Experiment items are processed by stream entry"));
    }

    /**
     * Handles one delivery of an item, which may not be the first. Redis delivers a message again when its
     * acknowledgement is lost, so an entry that already has an outcome ran on an earlier delivery: calling the
     * provider again would pay for the same call twice and write a second trace. It goes straight to the drain
     * check instead, which an earlier delivery may never have reached.
     */
    @Override
    protected Mono<Void> processEvent(ExperimentItemToProcess message, StreamMessageId entryId) {
        return itemLedger.outcome(message.workspaceId(), message.experimentId(), entryId)
                .doOnNext(outcome -> log.info(
                        "Skipping a redelivered entry that already ran, outcome '{}', entryId '{}', experimentId '{}'",
                        outcome, entryId, message.experimentId()))
                .hasElement()
                .flatMap(alreadyRan -> alreadyRan ? Mono.<Void>empty() : runAndRecord(message, entryId))
                .then(Mono.defer(() -> finishIfDrained(message)))
                .contextWrite(buildReactorContext(message));
    }

    private Mono<Void> runAndRecord(ExperimentItemToProcess message, StreamMessageId entryId) {
        return cancellationService.isCancelled(message.workspaceId(), message.experimentId())
                .flatMap(cancelled -> cancelled
                        ? recordSkipped(message, entryId)
                        // Failed items stay on the assertion counter: the drain fails the run, and a zeroed
                        // counter would let the assertion path record COMPLETED first.
                        : run(message).flatMap(outcome -> itemLedger.record(
                                message.workspaceId(), message.experimentId(), entryId, outcome)));
    }

    private Mono<Outcome> run(ExperimentItemToProcess message) {
        return itemProcessor.process(message)
                .thenReturn(Outcome.OK)
                .onErrorResume(e -> {
                    log.error("Failed to process an experiment item, experimentId '{}', datasetItemId '{}'",
                            message.experimentId(), message.datasetItemId(), e);
                    return Mono.just(Outcome.FAILED);
                });
    }

    /**
     * Counts a stopped run's item as skipped, which is what lets the run drain. Not once its total is gone: an
     * aborted launch discards the total together with the outcomes, and recording one would bring the outcomes
     * back for a day with nothing left to read them.
     */
    private Mono<Void> recordSkipped(ExperimentItemToProcess message, StreamMessageId entryId) {
        return itemLedger.hasTotal(message.workspaceId(), message.experimentId())
                .flatMap(tracked -> {
                    if (!tracked) {
                        log.debug("Not recording a skip for an experiment no longer tracked, entryId '{}',"
                                + " experimentId '{}'", entryId, message.experimentId());
                        return Mono.empty();
                    }
                    log.debug("Skipping an item of a cancelled experiment, entryId '{}', experimentId '{}'",
                            entryId, message.experimentId());
                    return itemLedger.record(message.workspaceId(), message.experimentId(), entryId,
                            Outcome.SKIPPED);
                });
    }

    /**
     * Settles the experiment once every item it queued has an outcome. Each variant of a run counts its own
     * items, so it settles on its own work and carries its own faults. Any delivery that sees the drain comes
     * through here, a repeated one included; the claim inside the finish keeps that to one ending.
     */
    private Mono<Void> finishIfDrained(ExperimentItemToProcess message) {
        return itemLedger.remaining(message.workspaceId(), message.experimentId())
                .flatMap(remaining -> {
                    if (remaining > 0) {
                        log.debug("Items still to come, remaining '{}', experimentId '{}'", remaining,
                                message.experimentId());
                        return Mono.empty();
                    }
                    return cancellationService.discardQueued(message.workspaceId(), message.experimentId())
                            .then(cancellationService.isFinishClaimed(message.workspaceId(), message.experimentId()))
                            .flatMap(claimed -> {
                                if (claimed) {
                                    // Its ending is already written, so this only records that the items
                                    // stopped, as a claim loser would, without reading every outcome first.
                                    log.info(
                                            "Recording only the drain, the ending is already claimed, experimentId '{}'",
                                            message.experimentId());
                                    return stampFinished(message);
                                }
                                return settleDrained(message);
                            });
                });
    }

    private Mono<Void> settleDrained(ExperimentItemToProcess message) {
        return itemLedger.failures(message.workspaceId(), message.experimentId())
                .flatMap(failures -> {
                    if (failures > 0) {
                        log.warn("Marking a drained experiment FAILED, failures '{}', experimentId '{}'", failures,
                                message.experimentId());
                        return markExperimentFailed(message, buildReactorContext(message));
                    }
                    if (message.isTestSuite()) {
                        log.info("Drained a test suite, waiting for its assertions, experimentId '{}'",
                                message.experimentId());
                        // Stamped here, not with the assertions: a cancelled suite never
                        // reaches those, and would look forever like a run still going.
                        return stampFinished(message);
                    }
                    log.info("Drained an experiment, finishing it, experimentId '{}'", message.experimentId());
                    return finishExperiment(message);
                });
    }

    private Context buildReactorContext(ExperimentItemToProcess message) {
        return Context.of(
                RequestContext.WORKSPACE_ID, message.workspaceId(),
                RequestContext.USER_NAME, message.userName(),
                RequestContext.WORKSPACE_NAME, "",
                RequestContext.VISIBILITY, Visibility.PRIVATE);
    }

    /**
     * Writes how the run ended, for the one caller that wins the right to. Two things are separate here
     * and were not before: the stamp says no more items are coming, and every observer of the drain
     * writes it; the status says how it ended, and only the claim holder writes it. A stop that got
     * there first owns CANCELLED, and this still records that the items stopped — which is what the
     * page waits on, and what a status alone cannot say, being written when the stop was asked for.
     */
    private Mono<Void> settle(ExperimentItemToProcess message, ExperimentStatus earned, Mono<Void> onWon) {
        return cancellationService.claimFinish(message.workspaceId(), message.experimentId())
                .flatMap(won -> {
                    if (!won) {
                        log.info("Experiment '{}' already has its ending written, recording the drain only",
                                message.experimentId());
                        return stampFinished(message);
                    }
                    return experimentService
                            .update(message.experimentId(),
                                    ExperimentUpdate.builder().status(earned).finished(true).build())
                            // Handed back by the holder only. A caller that lost the claim releasing it would
                            // let the next one write over the ending the holder already wrote.
                            .onErrorResume(error -> releaseFinishClaim(message).then(Mono.error(error)))
                            .then(onWon);
                });
    }

    // TODO: deduplicate with TestSuiteAssertionCounterService.finishExperiment — extract into
    //  a shared ExperimentFinishListener triggered by an ExperimentProcessed event
    private Mono<Void> finishExperiment(ExperimentItemToProcess message) {
        var reactorContext = buildReactorContext(message);
        var experimentId = message.experimentId();

        // Aggregates describe a run that ran, so they are built only by the caller that completed it
        return settle(message, ExperimentStatus.COMPLETED,
                Mono.defer(() -> experimentService.finishExperiments(Set.of(experimentId))
                        .onErrorResume(error -> {
                            // COMPLETED is already written and is true: aggregates are derived from
                            // it and can be rebuilt, so FAILED would describe the run less well.
                            log.error("Failed to aggregate completed experiment '{}'", experimentId, error);
                            return Mono.empty();
                        })))
                .contextWrite(reactorContext);
    }

    /** Records that the run has stopped producing items, leaving its status alone. */
    private Mono<Void> stampFinished(ExperimentItemToProcess message) {
        return experimentService.stampFinished(message.experimentId())
                .contextWrite(buildReactorContext(message));
    }

    /**
     * Hands the finish back when its holder could not write how the run ended, so the redelivery that retries
     * the finish can claim it again. Held, it would also keep a stop from reaching a run that has no ending:
     * while it is held, a stop reads the run as already finished and leaves it where it is.
     */
    private Mono<Void> releaseFinishClaim(ExperimentItemToProcess message) {
        return cancellationService.releaseFinish(message.workspaceId(), message.experimentId())
                .doOnSuccess(released -> log.warn(
                        "Released the finish of experiment '{}' so it can still be stopped",
                        message.experimentId()))
                .onErrorResume(error -> {
                    log.error("Failed to release the finish of experiment '{}'", message.experimentId(), error);
                    return Mono.empty();
                })
                .then();
    }

    private Mono<Void> markExperimentFailed(ExperimentItemToProcess message, Context reactorContext) {
        return settle(message, ExperimentStatus.FAILED, Mono.empty())
                .contextWrite(reactorContext);
    }
}
