package com.comet.opik.domain;

import com.comet.opik.api.events.TraceToSummarize;
import com.comet.opik.infrastructure.TraceSummaryConfig;
import com.comet.opik.infrastructure.redis.RedisStreamUtils;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.List;

@ImplementedBy(TraceSummaryPublisherImpl.class)
public interface TraceSummaryPublisher {
    Mono<Void> enqueue(List<TraceToSummarize> messages);
}

@Singleton
@Slf4j
class TraceSummaryPublisherImpl implements TraceSummaryPublisher {

    private final TraceSummaryConfig config;
    private final RedissonReactiveClient redisClient;

    @Inject
    public TraceSummaryPublisherImpl(@NonNull @Config("traceSummary") TraceSummaryConfig config,
            @NonNull RedissonReactiveClient redisClient) {
        this.config = config;
        this.redisClient = redisClient;
    }

    @Override
    public Mono<Void> enqueue(@NonNull List<TraceToSummarize> messages) {
        var stream = redisClient.getStream(config.getStreamName(), config.getCodec());
        return Flux.fromIterable(messages)
                .flatMap(message -> stream
                        .add(RedisStreamUtils.buildAddArgs(TraceSummaryConfig.PAYLOAD_FIELD, message, config))
                        .doOnNext(id -> log.debug("Enqueued summarization message id '{}' into stream '{}'",
                                id, config.getStreamName())))
                .then();
    }
}
