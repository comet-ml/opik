package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.redis.RedisStreamUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

/**
 * Hands a queued run to whichever pod is free. The run's row is written {@code queued} first, so a message that never
 * arrives is published again by the planner ({@code topics.runs.republishAfter}).
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class TopicsRunPublisher {

    private final @NonNull RedissonReactiveClient redisson;
    private final @NonNull @Config("topics") TopicsConfig config;

    Mono<Void> publish(@NonNull TopicsRunMessage message) {
        var runs = config.getRuns();
        return redisson.<String, TopicsRunMessage>getStream(runs.getStreamName(), runs.getCodec())
                .add(RedisStreamUtils.buildAddArgs(TopicsConfig.RunsConfig.PAYLOAD_FIELD, message, runs))
                .then();
    }
}
