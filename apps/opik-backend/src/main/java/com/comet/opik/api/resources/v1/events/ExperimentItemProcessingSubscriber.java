package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.ExperimentStatus;
import com.comet.opik.api.ExperimentUpdate;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.events.ExperimentItemToProcess;
import com.comet.opik.domain.ExperimentItemProcessor;
import com.comet.opik.domain.ExperimentService;
import com.comet.opik.infrastructure.ExperimentExecutionConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLongReactive;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Flux;
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
    private final RedissonReactiveClient redisClient;
    private final ExperimentExecutionConfig config;

    @Inject
    protected ExperimentItemProcessingSubscriber(
            @NonNull @Config("experimentExecution") ExperimentExecutionConfig config,
            @NonNull RedissonReactiveClient redisson,
            @NonNull ExperimentItemProcessor itemProcessor,
            @NonNull ExperimentService experimentService) {
        super(config, redisson, ExperimentExecutionConfig.PAYLOAD_FIELD, SUBSCRIBER_NAMESPACE, METRICS_BASE_NAME);
        this.itemProcessor = itemProcessor;
        this.experimentService = experimentService;
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
        return itemProcessor.process(message)
                .thenReturn(true)
                .onErrorResume(e -> {
                    log.error("Failed to process experiment item for experiment '{}', dataset item '{}'",
                            message.experimentId(), message.datasetItemId(), e);
                    return Mono.just(false);
                })
                .flatMap(success -> decrementAndFinishIfComplete(message, success))
                .contextWrite(buildReactorContext(message));
    }

    private Mono<Void> decrementAndFinishIfComplete(ExperimentItemToProcess message, boolean success) {
        var counterKey = ExperimentExecutionConfig.BATCH_COUNTER_KEY_PREFIX + message.batchId();
        var failureKey = ExperimentExecutionConfig.BATCH_COUNTER_KEY_PREFIX + message.batchId() + ":failures";
        RAtomicLongReactive counter = redisClient.getAtomicLong(counterKey);
        RAtomicLongReactive failureCounter = redisClient.getAtomicLong(failureKey);

        Mono<Void> trackFailure = success
                ? Mono.empty()
                : failureCounter.incrementAndGet()
                        .then(failureCounter.expire(config.getBatchCounterTtl().toJavaDuration()))
                        .then();

        return trackFailure.then(counter.decrementAndGet())
                .flatMap(remaining -> {
                    if (remaining <= 0) {
                        return failureCounter.get()
                                .flatMap(failures -> {
                                    if (failures > 0) {
                                        log.warn(
                                                "Batch '{}' complete with '{}' failures, marking '{}' experiments as FAILED",
                                                message.batchId(), failures, message.allExperimentIds().size());
                                        return markExperimentsFailed(message,
                                                buildReactorContext(message));
                                    }
                                    log.info("Batch '{}' complete, finishing '{}' experiments",
                                            message.batchId(), message.allExperimentIds().size());
                                    return finishExperiments(message);
                                });
                    }
                    log.debug("Batch '{}' has '{}' remaining items", message.batchId(), remaining);
                    return Mono.empty();
                })
                .then();
    }

    private Context buildReactorContext(ExperimentItemToProcess message) {
        return Context.of(
                RequestContext.WORKSPACE_ID, message.workspaceId(),
                RequestContext.USER_NAME, message.userName(),
                RequestContext.WORKSPACE_NAME, "",
                RequestContext.VISIBILITY, Visibility.PRIVATE);
    }

    private Mono<Void> finishExperiments(ExperimentItemToProcess message) {
        var reactorContext = buildReactorContext(message);

        var statusUpdate = ExperimentUpdate.builder()
                .status(ExperimentStatus.COMPLETED)
                .build();

        return Flux.fromIterable(message.allExperimentIds())
                .concatMap(experimentId -> experimentService.update(experimentId, statusUpdate))
                .then(experimentService.finishExperiments(Set.copyOf(message.allExperimentIds())))
                .contextWrite(reactorContext)
                .doOnSuccess(unused -> log.info("Finished '{}' experiments for batch '{}'",
                        message.allExperimentIds().size(), message.batchId()))
                .onErrorResume(error -> {
                    log.error("Failed to finish experiments for batch '{}', marking as FAILED",
                            message.batchId(), error);
                    return markExperimentsFailed(message, reactorContext);
                });
    }

    private Mono<Void> markExperimentsFailed(ExperimentItemToProcess message, Context reactorContext) {
        var failedUpdate = ExperimentUpdate.builder()
                .status(ExperimentStatus.CANCELLED)
                .build();

        return Flux.fromIterable(message.allExperimentIds())
                .concatMap(experimentId -> experimentService.update(experimentId, failedUpdate)
                        .onErrorResume(e -> {
                            log.error("Failed to mark experiment '{}' as FAILED", experimentId, e);
                            return Mono.empty();
                        }))
                .contextWrite(reactorContext)
                .then();
    }
}
