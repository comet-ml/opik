package com.comet.opik.domain.experiments.aggregations;

import com.comet.opik.infrastructure.ExperimentDenormalizationConfig;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMapReactive;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@ImplementedBy(ExperimentAggregationPublisher.ExperimentAggregationPublisherImpl.class)
public interface ExperimentAggregationPublisher {

    Mono<Void> publish(@NonNull Set<UUID> experimentIds, @NonNull String workspaceId, @NonNull String userName);

    @Singleton
    @Slf4j
    class ExperimentAggregationPublisherImpl implements ExperimentAggregationPublisher {

        private final ExperimentDenormalizationConfig config;
        private final RedissonReactiveClient redisClient;

        @Inject
        ExperimentAggregationPublisherImpl(
                @NonNull @Config("experimentDenormalization") ExperimentDenormalizationConfig config,
                @NonNull RedissonReactiveClient redisClient) {
            this.config = config;
            this.redisClient = redisClient;
        }

        @Override
        public Mono<Void> publish(@NonNull Set<UUID> experimentIds, @NonNull String workspaceId,
                @NonNull String userName) {
            if (!config.isEnabled() || experimentIds.isEmpty()) {
                log.info("Skipping publish: enabled='{}', experimentIds.size='{}'",
                        config.isEnabled(), experimentIds.size());
                return Mono.empty();
            }

            Instant expiryTimestamp = Instant.now().plusMillis(config.getDebounceDelay().toMilliseconds());
            var index = redisClient.getScoredSortedSet(ExperimentDenormalizationConfig.PENDING_SET_KEY);

            return Flux.fromIterable(experimentIds)
                    .flatMap(experimentId -> {
                        String member = workspaceId + ":" + experimentId;
                        RMapReactive<String, String> bucket = redisClient
                                .getMap(ExperimentDenormalizationConfig.EXPERIMENT_KEY_PREFIX + member);

                        return index.add(expiryTimestamp.toEpochMilli(), member)
                                .then(bucket.put(ExperimentDenormalizationConfig.USER_NAME_FIELD, userName))
                                .then(bucket.expire(Duration.ofMillis(config.getDebounceDelay().toMilliseconds() * 2)))
                                .doOnSuccess(__ -> log.info(
                                        "Enqueued experiment '{}' for workspace '{}' in pending bucket with expiryTimestamp='{}'",
                                        experimentId, workspaceId, expiryTimestamp));
                    })
                    .doOnError(error -> log.error("Error enqueueing experiments in pending bucket", error))
                    .then();
        }
    }
}
