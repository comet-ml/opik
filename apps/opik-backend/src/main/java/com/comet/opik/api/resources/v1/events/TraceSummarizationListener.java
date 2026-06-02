package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Trace;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.api.events.TracesUpdated;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.TraceSummaryService;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.security.SecureRandom;
import java.util.List;

@EagerSingleton
@Slf4j
public class TraceSummarizationListener {

    // Summarization is best-effort enrichment that costs an LLM call per trace, so only a fraction of completed
    // traces are summarized. Sampling is per-trace, mirroring OnlineScoringSampler's per-trace sampling.
    private static final double SAMPLING_RATE = 0.1;

    private final TraceSummaryService traceSummaryService;
    private final TraceService traceService;
    private final ServiceTogglesConfig serviceTogglesConfig;
    private final double samplingRate;
    private final SecureRandom secureRandom = new SecureRandom();

    @Inject
    public TraceSummarizationListener(@NonNull TraceSummaryService traceSummaryService,
            @NonNull TraceService traceService,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceTogglesConfig) {
        this(traceSummaryService, traceService, serviceTogglesConfig, SAMPLING_RATE);
    }

    TraceSummarizationListener(@NonNull TraceSummaryService traceSummaryService,
            @NonNull TraceService traceService,
            @NonNull ServiceTogglesConfig serviceTogglesConfig,
            double samplingRate) {
        this.traceSummaryService = traceSummaryService;
        this.traceService = traceService;
        this.serviceTogglesConfig = serviceTogglesConfig;
        this.samplingRate = samplingRate;
    }

    /**
     * Summarizes traces that arrive already complete (end_time set), i.e. the SDK logged the whole trace in a single
     * call. Partial "start" traces are filtered out here and picked up later by {@link #onTracesUpdated} once their
     * end_time is set.
     */
    @Subscribe
    public void onTracesCreated(@NonNull TracesCreated event) {
        if (!serviceTogglesConfig.isTraceSummarizationEnabled()) {
            return;
        }

        var sampledTraces = event.traces().stream()
                .filter(trace -> trace.endTime() != null)
                .filter(trace -> shouldSample())
                .toList();

        if (sampledTraces.isEmpty()) {
            return;
        }

        log.info("Received TracesCreated for summarization, sampled '{}', total '{}', workspace '{}'",
                sampledTraces.size(), event.traces().size(), event.workspaceId());

        summarizeAsync(Mono.just(sampledTraces), event.workspaceId(), event.userName());
    }

    /**
     * Summarizes traces completed via a later update (the SDK POSTs a partial trace at function start and PATCHes the
     * end_time at function end, e.g. manual {@code trace.end()}). Without this, traces completed this way would never
     * be summarized. The event only carries trace ids, so the sampled ids are fetched to obtain input/output.
     */
    @Subscribe
    public void onTracesUpdated(@NonNull TracesUpdated event) {
        if (!serviceTogglesConfig.isTraceSummarizationEnabled()) {
            return;
        }

        if (event.traceUpdate().endTime() == null) {
            return;
        }

        var sampledTraceIds = event.traceIds().stream()
                .filter(traceId -> shouldSample())
                .toList();

        if (sampledTraceIds.isEmpty()) {
            return;
        }

        log.info("Received TracesUpdated with end_time for summarization, sampled '{}', total '{}', workspace '{}'",
                sampledTraceIds.size(), event.traceIds().size(), event.workspaceId());

        Mono<List<Trace>> completedTraces = traceService.getByIds(sampledTraceIds)
                .filter(trace -> trace.endTime() != null)
                .collectList()
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, event.workspaceId())
                        .put(RequestContext.USER_NAME, event.userName()));

        summarizeAsync(completedTraces, event.workspaceId(), event.userName());
    }

    private boolean shouldSample() {
        return secureRandom.nextDouble() < samplingRate;
    }

    private void summarizeAsync(Mono<List<Trace>> traces, String workspaceId, String userName) {
        traces.filter(completedTraces -> !completedTraces.isEmpty())
                .flatMap(completedTraces -> traceSummaryService.summarize(completedTraces, workspaceId))
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, userName))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        unused -> {
                        },
                        error -> log.error("Failed to summarize traces for workspace '{}'", workspaceId, error));
    }
}
