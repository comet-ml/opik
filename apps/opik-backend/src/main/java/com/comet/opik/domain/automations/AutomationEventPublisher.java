package com.comet.opik.domain.automations;

import com.comet.opik.infrastructure.AutomationEventsConfig;
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

/** Puts an event on the automation stream, where the subscriber runs it durably and retried. */
@Slf4j
@Singleton
public class AutomationEventPublisher {

    private final @NonNull RedissonReactiveClient redisson;
    private final @NonNull AutomationEventsConfig config;

    @Inject
    public AutomationEventPublisher(@NonNull RedissonReactiveClient redisson,
            @NonNull @Config("automationEvents") AutomationEventsConfig config) {
        this.redisson = redisson;
        this.config = config;
    }

    public Mono<Void> enqueue(@NonNull AutomationEvent event) {
        log.debug("Publishing automation event '{}' on '{}', workspace '{}'", event.event(), event.itemId(),
                event.workspaceId());
        return Mono.defer(() -> {
            RStreamReactive<String, AutomationEvent> stream = redisson.getStream(config.getStreamName(),
                    config.getCodec());
            return stream
                    .add(RedisStreamUtils.buildAddArgs(AutomationEventsConfig.PAYLOAD_FIELD, event, config))
                    .doOnError(throwable -> log.error("Failed to publish automation event '{}' on '{}', workspace '{}'",
                            event.event(), event.itemId(), event.workspaceId(), throwable))
                    .then();
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
