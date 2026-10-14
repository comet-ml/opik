package com.comet.opik.api.resources.v1.events;

import com.clickhouse.client.api.ClickHouseException;
import com.comet.opik.api.Span;
import com.comet.opik.api.events.PartialSpanCreated;
import com.comet.opik.api.events.SpansCreated;
import com.comet.opik.domain.SpanService;
import com.comet.opik.infrastructure.SpanWeeksRegistrationConfig;
import com.comet.opik.utils.AsyncUtils;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.util.retry.Retry;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Registers in span_weeks the weekly partition of every span written, so trace-keyed spans reads can bind their
 * partitions. Runs on the AsyncEventBus virtual threads, off the request path, so a read right after a write can miss
 * the new spans until their week lands. Transient failures are retried with exponential backoff
 * ({@code spanWeeksRegistration}), since a repeat registration collapses in span_weeks; any other failure, or a
 * transient one that outlasts the retries, is logged and swallowed, since the span itself was already written, leaving
 * that span's week out of the index until another span of the same trace and week registers it.
 */
@EagerSingleton
@Slf4j
public class SpanWeeksListener {

    private final SpanService spanService;
    private final SpanWeeksRegistrationConfig config;

    @Inject
    public SpanWeeksListener(
            @NonNull SpanService spanService,
            @NonNull @Config("spanWeeksRegistration") SpanWeeksRegistrationConfig config) {
        this.spanService = spanService;
        this.config = config;
    }

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
                .retryWhen(Retry.backoff(config.getMaxRetries(), config.getMinBackoff().toJavaDuration())
                        .maxBackoff(config.getMaxBackoff().toJavaDuration())
                        .filter(SpanWeeksListener::isTransient)
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .contextWrite(ctx -> AsyncUtils.setRequestContext(ctx, userName, workspaceId))
                .subscribe(
                        null,
                        error -> log.error("Failed to register the weeks of '{}' spans for workspace '{}'",
                                spans.size(), workspaceId, error));
    }

    // The v2 client flags its own transient failures; a dropped connection surfaces as an IOException or a timeout.
    private static boolean isTransient(Throwable error) {
        for (var cause = error; cause != null; cause = cause.getCause()) {
            if ((cause instanceof ClickHouseException clickHouseError && clickHouseError.isRetryable())
                    || cause instanceof IOException || cause instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }
}
