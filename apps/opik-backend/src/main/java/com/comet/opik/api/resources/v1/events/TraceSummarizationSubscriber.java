package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.events.TraceToSummarize;
import com.comet.opik.domain.TraceSummaryService;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.TraceSummaryConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

@EagerSingleton
@Slf4j
public class TraceSummarizationSubscriber extends BaseRedisSubscriber<TraceToSummarize> {

    private static final String METRICS_BASE_NAME = "trace_summarization";

    private final ServiceTogglesConfig serviceTogglesConfig;
    private final TraceSummaryService traceSummaryService;

    @Inject
    public TraceSummarizationSubscriber(@NonNull @Config("traceSummary") TraceSummaryConfig config,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceTogglesConfig,
            @NonNull RedissonReactiveClient redisson,
            @NonNull TraceSummaryService traceSummaryService) {
        super(config, redisson, TraceSummaryConfig.PAYLOAD_FIELD, "opik", METRICS_BASE_NAME);
        this.serviceTogglesConfig = serviceTogglesConfig;
        this.traceSummaryService = traceSummaryService;
    }

    @Override
    public void start() {
        if (serviceTogglesConfig.isTraceSummarizationEnabled()) {
            super.start();
        } else {
            log.warn("Trace summarization consumer won't start as it is disabled.");
        }
    }

    @Override
    public void stop() {
        if (serviceTogglesConfig.isTraceSummarizationEnabled()) {
            super.stop();
        }
    }

    @Override
    protected Mono<Void> processEvent(@NonNull TraceToSummarize message) {
        // Errors are intentionally NOT caught here: they propagate so BaseRedisSubscriber retries the message.
        return traceSummaryService.summarize(message.traceId(), message.workspaceId())
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, message.workspaceId())
                        .put(RequestContext.USER_NAME, message.userName()));
    }
}
