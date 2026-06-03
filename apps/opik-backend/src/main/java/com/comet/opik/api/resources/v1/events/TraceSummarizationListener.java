package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Trace;
import com.comet.opik.api.events.TraceToSummarize;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.api.events.TracesUpdated;
import com.comet.opik.domain.TraceSummaryPublisher;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

@EagerSingleton
@Slf4j
public class TraceSummarizationListener {

    // Summarization is best-effort enrichment that costs an LLM call per trace, so only a fraction of completed
    // traces are summarized. Sampling is per-trace, mirroring OnlineScoringSampler's per-trace sampling.
    private static final double SAMPLING_RATE = 0.1;

    private final TraceSummaryPublisher traceSummaryPublisher;
    private final ServiceTogglesConfig serviceTogglesConfig;
    private final double samplingRate;
    private final SecureRandom secureRandom = new SecureRandom();

    @Inject
    public TraceSummarizationListener(@NonNull TraceSummaryPublisher traceSummaryPublisher,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceTogglesConfig) {
        this(traceSummaryPublisher, serviceTogglesConfig, SAMPLING_RATE);
    }

    TraceSummarizationListener(@NonNull TraceSummaryPublisher traceSummaryPublisher,
            @NonNull ServiceTogglesConfig serviceTogglesConfig,
            double samplingRate) {
        this.traceSummaryPublisher = traceSummaryPublisher;
        this.serviceTogglesConfig = serviceTogglesConfig;
        this.samplingRate = samplingRate;
    }

    /**
     * Enqueues traces that arrive already complete (end_time set), i.e. the SDK logged the whole trace in a single
     * call. Partial "start" traces are filtered out here and picked up later by {@link #onTracesUpdated} once their
     * end_time is set.
     */
    @Subscribe
    public void onTracesCreated(@NonNull TracesCreated event) {
        if (!serviceTogglesConfig.isTraceSummarizationEnabled()) {
            return;
        }

        var sampledTraceIds = event.traces().stream()
                .filter(trace -> trace.endTime() != null)
                .filter(trace -> shouldSample())
                .map(Trace::id)
                .toList();

        if (sampledTraceIds.isEmpty()) {
            return;
        }

        log.info("Received TracesCreated for summarization, sampled '{}', total '{}', workspace '{}'",
                sampledTraceIds.size(), event.traces().size(), event.workspaceId());

        enqueueAsync(sampledTraceIds, event.workspaceId(), event.userName());
    }

    /**
     * Enqueues traces completed via a later update (the SDK POSTs a partial trace at function start and PATCHes the
     * end_time at function end, e.g. manual {@code trace.end()}). The consumer fetches the trace + spans by id.
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

        enqueueAsync(sampledTraceIds, event.workspaceId(), event.userName());
    }

    private boolean shouldSample() {
        return secureRandom.nextDouble() < samplingRate;
    }

    private void enqueueAsync(List<UUID> traceIds, String workspaceId, String userName) {
        var messages = traceIds.stream()
                .map(traceId -> TraceToSummarize.builder()
                        .traceId(traceId)
                        .workspaceId(workspaceId)
                        .userName(userName)
                        .build())
                .toList();

        traceSummaryPublisher.enqueue(messages)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        unused -> {
                        },
                        error -> log.error("Failed to enqueue traces for summarization, workspace '{}'", workspaceId,
                                error));
    }
}
