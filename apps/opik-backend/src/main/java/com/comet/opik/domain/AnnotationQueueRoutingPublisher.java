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

import java.util.Set;
import java.util.UUID;

/**
 * Puts routing work on the stream: one XADD and nothing else.
 *
 * <p>Called straight from the event listener, so one message is one score event — which already carries a
 * whole batch of entity ids, so a bulk score call costs one XADD rather than one per entity. Repeated
 * scores on the same entity are folded by the consumer when it reads a batch, not here: the stream is the
 * buffer, and collapsing on the read side keeps the write path to a single Redis command.
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
            @NonNull Set<String> scoreNames) {

        if (entityIds.isEmpty()) {
            return Mono.empty();
        }

        var message = AnnotationQueueRoutingMessage.builder()
                .workspaceId(workspaceId)
                .userName(userName)
                .scope(scope)
                .entityIds(entityIds)
                .scoreNames(scoreNames)
                .build();

        // DEBUG: one of these per score event on an automated workspace, so INFO would be noise.
        log.debug("Publishing annotation queue routing message, entities '{}', scope '{}', workspace '{}'",
                entityIds.size(), scope, workspaceId);

        return Mono.defer(() -> {
            RStreamReactive<String, AnnotationQueueRoutingMessage> stream = redisson.getStream(
                    config.getStreamName(), config.getCodec());

            return stream
                    .add(RedisStreamUtils.buildAddArgs(AnnotationQueueRoutingConfig.PAYLOAD_FIELD, message,
                            config.getStreamMaxLen(), config.getStreamTrimLimit()))
                    // DEBUG, not ERROR: the buffer logs this failure at ERROR with the group's size and
                    // the fact that its members stay pending, so raising it here too only duplicates the
                    // stack trace with less context around it.
                    .doOnError(throwable -> log.debug(
                            "Failed to publish annotation queue routing message, workspace '{}'",
                            workspaceId, throwable))
                    .then();
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
