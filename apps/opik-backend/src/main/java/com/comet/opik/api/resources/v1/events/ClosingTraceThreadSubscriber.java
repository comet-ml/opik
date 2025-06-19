package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.events.ProjectWithPendingClosureTraceThreads;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.TraceThreadConfig;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RSetReactive;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Instant;
import java.util.UUID;

import static com.comet.opik.domain.ProjectService.DEFAULT_USER;
import static com.comet.opik.infrastructure.auth.RequestContext.USER_NAME;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_ID;

@EagerSingleton
@Slf4j
public class ClosingTraceThreadSubscriber extends BaseRedisSubscriber<ProjectWithPendingClosureTraceThreads> {

    private static final String SUBSCRIBER_NAMESPACE = "closing_trace_threads";

    private final TraceThreadService traceThreadService;
    private final TraceThreadConfig config;
    private final RedissonReactiveClient redisson;

    @Inject
    protected ClosingTraceThreadSubscriber(@NonNull @Config TraceThreadConfig config,
            @NonNull RedissonReactiveClient redisson, TraceThreadService traceThreadService) {
        super(config, redisson, TraceThreadBufferConfig.BUFFER_SET_NAME, TraceThreadConfig.PAYLOAD_FIELD);
        this.traceThreadService = traceThreadService;
        this.config = config;
        this.redisson = redisson;
    }

    @Override
    protected String getMetricNamespace() {
        return SUBSCRIBER_NAMESPACE;
    }

    @Override
    protected Mono<Void> processEvent(ProjectWithPendingClosureTraceThreads message) {
        var lastUpdatedUntil = Instant.now().minus(config.getTimeoutToMarkThreadAsInactive().toJavaDuration());

        return traceThreadService.processProjectWithTraceThreadsPendingClosure(message.projectId(), lastUpdatedUntil)
                .then(removeFromPendingList(message.projectId()))
                .contextWrite(context -> context.put(USER_NAME, DEFAULT_USER)
                        .put(WORKSPACE_ID, message.workspaceId()));
    }

    private Mono<Void> removeFromPendingList(UUID projectId) {
        RSetReactive<UUID> projectWithThreadsPendingClosure = redisson.getSet(TraceThreadBufferConfig.BUFFER_SET_NAME);
        return projectWithThreadsPendingClosure.expire(config.getTimeoutToMarkThreadAsInactive().toJavaDuration())
                .then(Mono.defer(() -> projectWithThreadsPendingClosure.remove(projectId)))
                .onErrorResume(e -> {
                    log.error("Error removing project {} from pending closure list: {}", projectId, e.getMessage());
                    return Mono.empty();
                })
                .then();

    }
}
