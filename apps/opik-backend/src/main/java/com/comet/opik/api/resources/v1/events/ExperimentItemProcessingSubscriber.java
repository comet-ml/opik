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
        return Mono.fromRunnable(() -> {
            try {
                itemProcessor.process(
                        message.prompt(), message.datasetItem(), message.experimentId(),
                        message.datasetId(), message.versionHash(),
                        message.projectName(), message.workspaceId(), message.userName());
            } catch (Exception e) {
                log.error("Failed to process experiment item for experiment '{}', dataset item '{}'",
                        message.experimentId(), message.datasetItem().id(), e);
            }
        }).then(Mono.defer(() -> decrementAndFinishIfComplete(message)));
    }

    private Mono<Void> decrementAndFinishIfComplete(ExperimentItemToProcess message) {
        var counterKey = ExperimentExecutionConfig.BATCH_COUNTER_KEY_PREFIX + message.batchId();
        RAtomicLongReactive counter = redisClient.getAtomicLong(counterKey);

        return counter.decrementAndGet()
                .flatMap(remaining -> {
                    if (remaining <= 0) {
                        log.info("Batch '{}' complete, finishing '{}' experiments",
                                message.batchId(), message.allExperimentIds().size());
                        return finishExperiments(message);
                    }
                    log.debug("Batch '{}' has '{}' remaining items", message.batchId(), remaining);
                    return Mono.empty();
                })
                .then();
    }

    private Mono<Void> finishExperiments(ExperimentItemToProcess message) {
        var reactorContext = reactor.util.context.Context.of(
                RequestContext.WORKSPACE_ID, message.workspaceId(),
                RequestContext.USER_NAME, message.userName(),
                RequestContext.WORKSPACE_NAME, message.workspaceId(),
                RequestContext.VISIBILITY, Visibility.PRIVATE);

        var statusUpdate = ExperimentUpdate.builder()
                .status(ExperimentStatus.COMPLETED)
                .build();

        return Flux.fromIterable(message.allExperimentIds())
                .concatMap(experimentId -> experimentService.update(experimentId, statusUpdate))
                .then(experimentService.finishExperiments(Set.copyOf(message.allExperimentIds())))
                .contextWrite(reactorContext)
                .doOnSuccess(unused -> log.info("Finished '{}' experiments for batch '{}'",
                        message.allExperimentIds().size(), message.batchId()))
                .doOnError(error -> log.error("Failed to finish experiments for batch '{}'",
                        message.batchId(), error));
    }
}
