package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.infrastructure.AnnotationQueueRoutingConfig;
import com.comet.opik.infrastructure.redis.RedisStreamUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.Set;
import java.util.UUID;

/**
 * Puts routing work on the stream: one XADD and nothing else.
 *
 * <p>Called by the buffer flush, so one message is one (workspace, scope) batch of everything scored in the
 * buffer window, already deduplicated. The consumer processes it as a unit: one automation lookup and one
 * score read for every entity in it.
 */
@Slf4j
@Singleton
public class AnnotationQueueRoutingPublisher {

    private final @NonNull RedissonReactiveClient redisson;
    private final @NonNull AnnotationQueueRoutingConfig config;

    @Inject
    public AnnotationQueueRoutingPublisher(@NonNull RedissonReactiveClient redisson,
            @NonNull @Config("annotationQueueRouting") AnnotationQueueRoutingConfig config) {
        this.redisson = redisson;
        this.config = config;
    }

    public Mono<Void> enqueue(@NonNull String workspaceId, @NonNull AnnotationQueue.AnnotationScope scope,
            Set<UUID> entityIds) {

        if (CollectionUtils.isEmpty(entityIds)) {
            return Mono.empty();
        }

        var message = AnnotationQueueRoutingMessage.builder()
                .workspaceId(workspaceId)
                .scope(scope)
                .entityIds(entityIds)
                .build();

        // DEBUG: one of these per flushed batch on an automated workspace, so INFO would be noise.
        log.debug("Publishing annotation queue routing message, entities '{}', scope '{}', workspace '{}'",
                entityIds.size(), scope, workspaceId);

        return Mono.defer(() -> {
            RStreamReactive<String, AnnotationQueueRoutingMessage> stream = redisson.getStream(
                    config.getStreamName(), config.getCodec());

            return stream
                    .add(RedisStreamUtils.buildAddArgs(AnnotationQueueRoutingConfig.PAYLOAD_FIELD, message,
                            config.getStreamMaxLen(), config.getStreamTrimLimit()))
                    .doOnError(throwable -> log.error(
                            "Failed to publish annotation queue routing message, entities '{}', scope '{}', "
                                    + "workspace '{}'",
                            entityIds.size(), scope, workspaceId, throwable))
                    .then();
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
