package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.events.ExperimentAggregationMessage;
import com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesService;
import com.comet.opik.domain.experiments.aggregations.ExperimentAggregationPublisher;
import com.comet.opik.infrastructure.ExperimentDenormalizationConfig;
import com.comet.opik.infrastructure.lock.LockService;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.Set;
import java.util.concurrent.TimeoutException;

import static com.comet.opik.infrastructure.auth.RequestContext.USER_NAME;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_ID;
import static com.comet.opik.infrastructure.lock.LockService.Lock;

@EagerSingleton
@Slf4j
public class ExperimentAggregatesSubscriber extends BaseRedisSubscriber<ExperimentAggregationMessage> {

    private static final String SUBSCRIBER_NAMESPACE = "experiment_aggregates";
    private static final String METRICS_BASE_NAME = "experiment_aggregates_subscriber";
    private static final String EXPERIMENT_AGGREGATE_LOCK_KEY = "experiment_aggregates:compute:%s:%s";
    private static final String AGGREGATION_RETRY_COUNT_KEY = "experiment_aggregates:lock_expiry_retries:%s:%s";

    private final ExperimentAggregatesService experimentAggregatesService;
    private final ExperimentAggregationPublisher publisher;
    private final LockService lockService;
    private final ExperimentDenormalizationConfig config;
    private final RedissonReactiveClient redissonClient;

    @Inject
    protected ExperimentAggregatesSubscriber(
            @NonNull @Config("experimentDenormalization") ExperimentDenormalizationConfig config,
            @NonNull RedissonReactiveClient redisson,
            @NonNull ExperimentAggregatesService experimentAggregatesService,
            @NonNull ExperimentAggregationPublisher publisher,
            @NonNull LockService lockService) {
        super(config, redisson, ExperimentDenormalizationConfig.PAYLOAD_FIELD, SUBSCRIBER_NAMESPACE,
                METRICS_BASE_NAME);
        this.experimentAggregatesService = experimentAggregatesService;
        this.publisher = publisher;
        this.lockService = lockService;
        this.config = config;
        this.redissonClient = redisson;
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

        var action = Mono.defer(() -> experimentAggregatesService.populateAggregations(message.experimentId()))
                .timeout(config.getAggregationLockTime().toJavaDuration())
                .then(Mono.defer(() -> resetRetryCounter(message)))
                .contextWrite(context -> context
                        .put(USER_NAME, message.userName())
                        .put(WORKSPACE_ID, message.workspaceId()));

        return lockService.bestEffortLock(
                lockKey,
                action,
                Mono.fromRunnable(() -> log.info(
                        "Skipping aggregation: lock already held by another node, experimentId='{}', workspaceId='{}'",
                        message.experimentId(), message.workspaceId())),
                config.getAggregationLockTime().toJavaDuration(),
                config.getLockAcquireWait().toJavaDuration())
                .onErrorResume(TimeoutException.class, e -> {
                    log.warn(
                            "Aggregation cancelled after exceeding lock TTL, re-triggering via debounce, experimentId='{}', workspaceId='{}', lockTtlMs='{}'",
                            message.experimentId(), message.workspaceId(),
                            config.getAggregationLockTime().toMilliseconds());
                    return retriggerIfBelowMaxRetries(message);
                })
                .doOnSuccess(unused -> log.info(
                        "Finished processing experiment aggregates, experimentId='{}'",
                        message.experimentId()))
                .doOnError(error -> log.error(
                        "Error processing experiment aggregates, experimentId='{}', workspaceId='{}'",
                        message.experimentId(), message.workspaceId(), error));
    }

    private Mono<Void> retriggerIfBelowMaxRetries(ExperimentAggregationMessage message) {
        var counter = redissonClient.getAtomicLong(buildRetryCountKey(message));

        return counter.incrementAndGet()
                .flatMap(retryCount -> {
                    if (retryCount > config.getMaxLockExpiryRetries()) {
                        log.warn(
                                "Max lock-expiry retries reached, stopping automatic re-trigger, experimentId='{}', workspaceId='{}', maxRetries='{}'",
                                message.experimentId(), message.workspaceId(), config.getMaxLockExpiryRetries());
                        return counter.delete().then();
                    }

                    log.warn(
                            "Re-triggering aggregation via debounce after lock expiry, experimentId='{}', workspaceId='{}', attempt='{}/{}'",
                            message.experimentId(), message.workspaceId(), retryCount,
                            config.getMaxLockExpiryRetries());

                    return counter.expire(config.getRetryCounterTtl().toJavaDuration())
                            .then(publisher.publish(
                                    Set.of(message.experimentId()),
                                    message.workspaceId(),
                                    message.userName()));
                });
    }

    private Mono<Void> resetRetryCounter(ExperimentAggregationMessage message) {
        return redissonClient.getAtomicLong(buildRetryCountKey(message)).delete().then();
    }

    private static String buildRetryCountKey(ExperimentAggregationMessage message) {
        return AGGREGATION_RETRY_COUNT_KEY.formatted(message.workspaceId(), message.experimentId());
    }

}
