package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.ExperimentStatus;
import com.comet.opik.api.ExperimentUpdate;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.events.ExperimentItemToProcess;
import com.comet.opik.domain.ExperimentCancellationService;
import com.comet.opik.domain.ExperimentItemProcessor;
import com.comet.opik.domain.ExperimentService;
import com.comet.opik.domain.TestSuiteAssertionCounterService;
import com.comet.opik.infrastructure.ExperimentExecutionConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLongReactive;
import org.redisson.api.RedissonReactiveClient;
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
    private final TestSuiteAssertionCounterService testSuiteAssertionCounterService;
    private final ExperimentCancellationService cancellationService;
    private final RedissonReactiveClient redisClient;
    private final ExperimentExecutionConfig config;

    @Inject
    protected ExperimentItemProcessingSubscriber(
            @NonNull @Config("experimentExecution") ExperimentExecutionConfig config,
            @NonNull RedissonReactiveClient redisson,
            @NonNull ExperimentItemProcessor itemProcessor,
            @NonNull ExperimentService experimentService,
            @NonNull TestSuiteAssertionCounterService testSuiteAssertionCounterService,
            @NonNull ExperimentCancellationService cancellationService) {
        super(config, redisson, ExperimentExecutionConfig.PAYLOAD_FIELD, SUBSCRIBER_NAMESPACE, METRICS_BASE_NAME);
        this.itemProcessor = itemProcessor;
        this.experimentService = experimentService;
        this.testSuiteAssertionCounterService = testSuiteAssertionCounterService;
        this.cancellationService = cancellationService;
        this.redisClient = redisson;
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

    @Override
    protected Mono<Void> processEvent(ExperimentItemToProcess message) {
        return cancellationService.isCancelled(message.workspaceId(), message.experimentId())
                .flatMap(cancelled -> {
                    if (cancelled) {
                        log.debug("Skipping item for cancelled experiment '{}'", message.experimentId());
                        return decrementAndFinishIfComplete(message, true);
                    }
                    return itemProcessor.process(message)
                            .thenReturn(true)
                            .onErrorResume(e -> {
                                log.error("Failed to process experiment item for experiment '{}', dataset item '{}'",
                                        message.experimentId(), message.datasetItemId(), e);
                                return Mono.just(false);
                            })
                            .flatMap(success -> decrementAndFinishIfComplete(message, success));
                })
                .contextWrite(buildReactorContext(message));
    }

    /**
     * Counts this item against its own experiment, not against the run. Per prompt variant, each
     * settles on its own work and carries its own faults.
     */
    private Mono<Void> decrementAndFinishIfComplete(ExperimentItemToProcess message, boolean success) {
        RAtomicLongReactive counter = redisClient.getAtomicLong(
                ExperimentExecutionConfig.itemCounterKey(message.experimentId()));
        RAtomicLongReactive failureCounter = redisClient.getAtomicLong(
                ExperimentExecutionConfig.itemFailureCounterKey(message.experimentId()));

        Mono<Void> trackFailure = success
                ? Mono.empty()
                : failureCounter.incrementAndGet()
                        .then(failureCounter.expire(config.getBatchCounterTtl().toJavaDuration()))
                        .then(decrementAssertionCounter(message))
                        .then();

        return trackFailure.then(counter.decrementAndGet())
                .flatMap(remaining -> {
                    if (remaining <= 0) {
                        // Claimed, not just observed: a cancel purging the queue can reach the finish at
                        // the same moment, and a counter past zero would re-finish it per item.
                        return cancellationService
                                .claimFinish(message.workspaceId(), message.experimentId())
                                .filter(Boolean::booleanValue)
                                .flatMap(claimed -> failureCounter.get())
                                .flatMap(failures -> {
                                    if (failures > 0) {
                                        log.warn("Experiment '{}' complete with '{}' failures, marking as FAILED",
                                                message.experimentId(), failures);
                                        return markExperimentFailed(message, buildReactorContext(message));
                                    }
                                    if (message.isTestSuite()) {
                                        log.info("Experiment '{}' complete, waiting for assertions to finish",
                                                message.experimentId());
                                        // Stamped here, not with the assertions: a cancelled suite never
                                        // reaches those, and would look forever like a run still going.
                                        return stampFinished(message);
                                    }
                                    log.info("Experiment '{}' complete, finishing", message.experimentId());
                                    return finishExperiment(message);
                                });
                    }
                    log.debug("Experiment '{}' has '{}' remaining items", message.experimentId(), remaining);
                    return Mono.empty();
                })
                .then();
    }

    private Mono<Void> decrementAssertionCounter(ExperimentItemToProcess message) {
        if (!message.isTestSuite()) {
            return Mono.empty();
        }
        return testSuiteAssertionCounterService.decrementAndFinishIfComplete(
                message.workspaceId(), message.experimentId());
    }

    private Context buildReactorContext(ExperimentItemToProcess message) {
        return Context.of(
                RequestContext.WORKSPACE_ID, message.workspaceId(),
                RequestContext.USER_NAME, message.userName(),
                RequestContext.WORKSPACE_NAME, "",
                RequestContext.VISIBILITY, Visibility.PRIVATE);
    }

    /**
     * Writes how the run ended. A run someone stopped keeps CANCELLED however its items turned out —
     * the stamp is what says no more are coming, which its status cannot, being written when the stop
     * was asked for. Reports whether it was stopped, which is what decides the rest of the ending.
     */
    private Mono<Boolean> settle(ExperimentItemToProcess message, ExperimentStatus earned) {
        return cancellationService.isCancelled(message.workspaceId(), message.experimentId())
                .flatMap(cancelled -> experimentService
                        .update(message.experimentId(), cancelled
                                ? ExperimentUpdate.builder().finished(true).build()
                                : ExperimentUpdate.builder().status(earned).finished(true).build())
                        .thenReturn(cancelled));
    }

    // TODO: deduplicate with TestSuiteAssertionCounterService.finishExperiment — extract into
    //  a shared ExperimentFinishListener triggered by an ExperimentProcessed event
    private Mono<Void> finishExperiment(ExperimentItemToProcess message) {
        var reactorContext = buildReactorContext(message);
        var experimentId = message.experimentId();

        return settle(message, ExperimentStatus.COMPLETED)
                .flatMap(cancelled -> cancelled
                        ? Mono.<Void>empty()
                        : experimentService.finishExperiments(Set.of(experimentId)))
                .contextWrite(reactorContext)
                .onErrorResume(error -> {
                    log.error("Failed to finish experiment '{}', marking as FAILED", experimentId, error);
                    return markExperimentFailed(message, reactorContext);
                });
    }

    /** Records that the run has stopped producing items, leaving its status alone. */
    private Mono<Void> stampFinished(ExperimentItemToProcess message) {
        return experimentService
                .update(message.experimentId(), ExperimentUpdate.builder().finished(true).build())
                .onErrorResume(error -> releaseFinishClaim(message).then(Mono.error(error)))
                .contextWrite(buildReactorContext(message));
    }

    /**
     * Hands the finish back when nothing could be written about how the run ended. There is no item
     * left to drain and try again, so whoever holds this holds the only route to a terminal state —
     * and while it is held a stop reads the run as already finished and leaves it where it is.
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
        return settle(message, ExperimentStatus.FAILED)
                .then()
                .onErrorResume(e -> {
                    // The last writer of a terminal state: past here nothing records how this ended
                    log.error("Failed to mark experiment '{}' as FAILED", message.experimentId(), e);
                    return releaseFinishClaim(message);
                })
                .contextWrite(reactorContext);
    }
}
