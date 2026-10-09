package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Span;
import com.comet.opik.api.events.PartialSpanCreated;
import com.comet.opik.api.events.SpansCreated;
import com.comet.opik.domain.SpanService;
import com.comet.opik.utils.AsyncUtils;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

import java.util.List;

/**
 * Registers in span_weeks the weekly partition of every span written, so trace-keyed spans reads can bind their
 * partitions. Runs on the AsyncEventBus virtual threads, off the request path; failures are logged and swallowed, since
 * the span itself was already written. A missed registration only leaves a read unbounded until the coverage job
 * re-checks the project.
 */
@EagerSingleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class SpanWeeksListener {

    private final @NonNull SpanService spanService;

    @Subscribe
    public void onSpansCreated(@NonNull SpansCreated event) {
        register(event.spans(), event.workspaceId(), event.userName());
    }

    @Subscribe
    public void onPartialSpanCreated(@NonNull PartialSpanCreated event) {
        var span = Span.builder()
                .id(event.spanId())
                .traceId(event.spanTraceId())
                .projectId(event.projectId())
                .build();
        register(List.of(span), event.workspaceId(), event.userName());
    }

    private void register(List<Span> spans, String workspaceId, String userName) {
        spanService.registerWeeks(spans)
                .contextWrite(ctx -> AsyncUtils.setRequestContext(ctx, userName, workspaceId))
                .subscribe(
                        null,
                        error -> log.error("Failed to register the weeks of '{}' spans for workspace '{}'",
                                spans.size(), workspaceId, error));
    }
}
