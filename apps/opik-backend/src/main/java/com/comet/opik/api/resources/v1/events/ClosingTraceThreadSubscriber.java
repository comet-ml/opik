package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.events.ProjectWithPendingClosureTraceThreads;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.TraceThreadConfig;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.time.Instant;

import static com.comet.opik.domain.ProjectService.DEFAULT_USER;
import static com.comet.opik.infrastructure.auth.RequestContext.USER_NAME;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_ID;

@EagerSingleton
@Slf4j
public class ClosingTraceThreadSubscriber extends BaseRedisSubscriber<ProjectWithPendingClosureTraceThreads> {

    private static final String SUBSCRIBER_NAMESPACE = "closing_trace_threads";

    private final TraceThreadService traceThreadService;
    private final TraceThreadConfig config;

    @Inject
    protected ClosingTraceThreadSubscriber(@NonNull @Config TraceThreadConfig config,
            @NonNull RedissonReactiveClient redisson, TraceThreadService traceThreadService) {
        super(config,
                redisson,
                TraceThreadConfig.PAYLOAD_FIELD,
                SUBSCRIBER_NAMESPACE,
                TraceThreadBufferConfig.BUFFER_SET_NAME);
        this.traceThreadService = traceThreadService;
        this.config = config;
    }

    @Override
    protected Mono<Void> processEvent(ProjectWithPendingClosureTraceThreads message) {
        Instant now = Instant.now();
        Duration defaultTimeoutToMarkThreadAsInactive = config.getTimeoutToMarkThreadAsInactive().toJavaDuration();

        return traceThreadService
                .processProjectWithTraceThreadsPendingClosure(message.projectId(), now,
                        defaultTimeoutToMarkThreadAsInactive)
                .contextWrite(context -> context.put(USER_NAME, DEFAULT_USER)
                        .put(WORKSPACE_ID, message.workspaceId()));
    }
}
