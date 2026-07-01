package com.comet.opik.domain.observability;

import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Domain-agnostic observability core: records an internal backend flow as hidden traces and spans, as
 * a side effect, without ever disrupting the flow being observed. It has no knowledge of any specific
 * producer (online evaluation or otherwise) — any flow can use it to make itself observable in Opik.
 * <p>
 * Every write here is hardened the way a logging or metrics framework (SLF4J, OTel) is: it never
 * throws back to the caller, never propagates an error and never blocks the observed flow. The entity
 * is built inside the reactive pipeline (so a builder failure becomes a swallowed error, not a thrown
 * exception), write errors are logged and swallowed, and persistence is offloaded to a separate
 * scheduler and subscribed in a detached, fire-and-forget manner.
 * <p>
 * Callers express observability as side effects only: {@link #recordSpan(Mono, ObservabilityContext,
 * Function, Function)} taps an in-flight call and returns exactly what that call emits, while the
 * {@code void} {@link #recordSpan(ObservabilityContext, Supplier)} / {@link #recordTrace} schedule a
 * write and return immediately. There is no API shape that lets a caller compose an observability
 * write into its own value/error chain, so the observed flow cannot be coupled to it by accident.
 */
@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class ObservabilityTraceRecorder {

    private final @NonNull TraceService traceService;
    private final @NonNull SpanService spanService;

    /**
     * Taps an in-flight call, recording it as a span without altering the call's outcome. The
     * returned {@link Mono} emits exactly what {@code source} emits (same value, same error,
     * unchanged), and the span is built and written fire-and-forget on success or error.
     * <p>
     * {@code onSuccess}/{@code onError} run synchronously when the source emits, so any bookkeeping
     * they perform (e.g. counters) happens before the value continues downstream; only the
     * persistence is detached.
     */
    public <T> Mono<T> recordSpan(@NonNull Mono<T> source, @NonNull ObservabilityContext context,
            @NonNull Function<? super T, Span> onSuccess, @NonNull Function<Throwable, Span> onError) {
        return source
                .doOnNext(value -> recordSpan(context, () -> onSuccess.apply(value)))
                .doOnError(error -> recordSpan(context, () -> onError.apply(error)));
    }

    /** Schedules a fire-and-forget span write. Returns immediately; failures are logged and swallowed. */
    public void recordSpan(@NonNull ObservabilityContext context, @NonNull Supplier<Span> spanBuilder) {
        persist(context, spanBuilder, spanService::create, "span");
    }

    /** Schedules a fire-and-forget trace write. Returns immediately; failures are logged and swallowed. */
    public void recordTrace(@NonNull ObservabilityContext context, @NonNull Supplier<Trace> traceBuilder) {
        persist(context, traceBuilder, traceService::create, "trace");
    }

    private <E> void persist(ObservabilityContext context, Supplier<E> builder, Function<E, Mono<UUID>> create,
            String kind) {
        // Build AND write run on a separate scheduler, in parallel with the observed flow — the caller's
        // thread only schedules and returns. So even building the entity (token usage, JSON serialization)
        // never runs on, blocks, or can throw into the flow being observed. A failure anywhere (build or
        // write) becomes an onError on this detached pipeline and is logged and swallowed; the outer
        // try/catch additionally guarantees that even scheduling can never surface to the observed flow.
        try {
            Mono.fromCallable(builder::get)
                    .flatMap(create)
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.USER_NAME, context.userName())
                            .put(RequestContext.WORKSPACE_ID, context.workspaceId()))
                    .doOnError(throwable -> log.warn("Failed to record observability {}", kind, throwable))
                    .onErrorComplete()
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe();
        } catch (Throwable throwable) {
            log.warn("Failed to schedule observability {}", kind, throwable);
        }
    }
}
