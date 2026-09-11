package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.infrastructure.AnnotationQueueRoutingConfig;
import com.comet.opik.infrastructure.redis.RedisStreamUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Puts routing work on the stream: one XADD and nothing else.
 *
 * <p>Called by {@link AnnotationQueueRoutingBufferService#flush()} rather than from the score path, so a
 * message here already represents a group of debounced entities rather than a single score event.
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

    public Mono<Void> enqueue(@NonNull String workspaceId, @NonNull String userName,
            @NonNull AnnotationQueue.AnnotationScope scope, @NonNull Set<UUID> entityIds,
            @NonNull Map<UUID, Set<String>> scoreNamesByEntity) {

        if (!config.isEnabled() || entityIds.isEmpty()) {
            return Mono.empty();
        }

        var message = AnnotationQueueRoutingMessage.builder()
                .workspaceId(workspaceId)
                .userName(userName)
                .scope(scope)
                .entityIds(entityIds)
                .scoreNamesByEntity(scoreNamesByEntity)
                .build();

        // DEBUG: one of these per score event on an automated workspace, so INFO would be noise.
        log.debug("Publishing annotation queue routing message: '{}' entities, scope '{}', workspace '{}'",
                entityIds.size(), scope, workspaceId);

        return Mono.defer(() -> {
            RStreamReactive<String, AnnotationQueueRoutingMessage> stream = redisson.getStream(
                    config.getStreamName(), config.getCodec());

            return stream
                    .add(RedisStreamUtils.buildAddArgs(AnnotationQueueRoutingConfig.PAYLOAD_FIELD, message, config))
                    .doOnError(throwable -> log.error(
                            "Failed to publish annotation queue routing message, workspace '{}'",
                            workspaceId, throwable))
                    .then();
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
