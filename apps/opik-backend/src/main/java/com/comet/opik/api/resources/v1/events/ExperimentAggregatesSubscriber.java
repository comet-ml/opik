package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.events.ExperimentAggregationMessage;
import com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesService;
import com.comet.opik.infrastructure.ExperimentDenormalizationConfig;
import com.comet.opik.infrastructure.lock.LockService;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import static com.comet.opik.infrastructure.auth.RequestContext.USER_NAME;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_ID;
import static com.comet.opik.infrastructure.lock.LockService.Lock;

@EagerSingleton
@Slf4j
public class ExperimentAggregatesSubscriber extends BaseRedisSubscriber<ExperimentAggregationMessage> {

    private static final String SUBSCRIBER_NAMESPACE = "experiment_aggregates";
    private static final String METRICS_BASE_NAME = "experiment_aggregates_subscriber";
    private static final String EXPERIMENT_AGGREGATE_LOCK_KEY = "experiment_aggregates:compute:%s:%s";

    private final ExperimentAggregatesService experimentAggregatesService;
    private final LockService lockService;
    private final ExperimentDenormalizationConfig config;

    @Inject
    protected ExperimentAggregatesSubscriber(
            @NonNull @Config("experimentDenormalization") ExperimentDenormalizationConfig config,
            @NonNull RedissonReactiveClient redisson,
            @NonNull ExperimentAggregatesService experimentAggregatesService,
            @NonNull LockService lockService) {
        super(config, redisson, ExperimentDenormalizationConfig.PAYLOAD_FIELD, SUBSCRIBER_NAMESPACE,
                METRICS_BASE_NAME);
        this.experimentAggregatesService = experimentAggregatesService;
        this.lockService = lockService;
        this.config = config;
    }

    @Override
    public void start() {
        if (!config.isEnabled()) {
            log.info("Experiment aggregates subscriber is disabled");
            return;
        }
        log.info("Starting experiment aggregates subscriber with streamName='{}', consumerGroupName='{}'",
                config.getStreamName(), config.getConsumerGroupName());
        super.start();
    }

    @Override
    public void stop() {
        if (!config.isEnabled()) {
            log.info("Experiment aggregates subscriber is disabled");
            return;
        }
        log.info("Stopping experiment aggregates subscriber");
        super.stop();
    }

    @Override
    protected Mono<Void> processEvent(ExperimentAggregationMessage message) {
        var lockKey = new Lock(
                EXPERIMENT_AGGREGATE_LOCK_KEY.formatted(message.workspaceId(), message.experimentId()));

        return lockService.executeWithLockCustomExpire(
                lockKey,
                Mono.defer(() -> experimentAggregatesService.populateAggregations(message.experimentId()))
                        .contextWrite(context -> context
                                .put(USER_NAME, message.userName())
                                .put(WORKSPACE_ID, message.workspaceId())),
                config.getAggregationLockTime().toJavaDuration())
                .then()
                .doOnSuccess(unused -> log.info(
                        "Finished processing experiment aggregates for experimentId: '{}'",
                        message.experimentId()))
                .doOnError(error -> log.error(
                        "Error processing experiment aggregates for experimentId: '{}'",
                        message.experimentId(), error));
    }

}
