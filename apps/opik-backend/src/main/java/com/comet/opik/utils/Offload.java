package com.comet.opik.utils;

import lombok.NonNull;
import lombok.experimental.UtilityClass;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

import java.util.concurrent.Callable;

/**
 * Puts a unit of work on the scheduler its kind needs, so a chain never depends on which thread emitted before it.
 *
 * <p>After an async call Reactor keeps running on the client's thread: {@code redisson-netty} after Redis,
 * {@code ClickHouseWorker} after an r2dbc query, {@code chc-operation} after the v2 client, {@code parallel} after a
 * timer. None of those may block, and the first two are shared by every Redis call or every ClickHouse query in the
 * service. Name the kind of work instead of picking a scheduler:
 *
 * <ul>
 *   <li>{@link #blocking(Callable)}: JDBI/MySQL, sync Redisson, S3, sync HTTP, DNS. Runs on
 *       {@link Schedulers#boundedElastic()}.</li>
 *   <li>{@link #cpu(Callable)}: CPU that grows with the input (a batch, a page, a window). Runs on
 *       {@link Schedulers#parallel()}, or on the pool an entry point set with {@link #cpuOn(Context, Scheduler)}.
 *       A step that runs for seconds also needs a concurrency cap at its caller.</li>
 *   <li>{@link #blockingEach(Flux)}: per-item blocking or CPU side effects of a stream, such as writing response
 *       chunks. Moves the items, which a {@code subscribeOn} can't do.</li>
 *   <li>{@link #resumeOriginal(Mono)}: back to the entry point's own pool after a client call, or no hop when it
 *       has none.</li>
 * </ul>
 *
 * <p>Light work (mapping one row, building a small DTO) needs none of these. The rules are in
 * {@code .agents/skills/opik-backend/reactive-threading.md}.
 */
@UtilityClass
public class Offload {

    private static final String CPU_SCHEDULER = Offload.class.getName() + ".cpuScheduler";
    private static final String ORIGINAL_SCHEDULER = Offload.class.getName() + ".originalScheduler";

    /**
     * Runs a blocking call on {@link Schedulers#boundedElastic()}. What follows runs there too until the next hop, so
     * heavy CPU after it goes in its own {@link #cpu(Callable)}: every MySQL, S3 and sync HTTP call waits for this pool.
     */
    public static <T> Mono<T> blocking(@NonNull Callable<T> call) {
        return Mono.fromCallable(call).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Runs a blocking call with no result on {@link Schedulers#boundedElastic()}.
     */
    public static Mono<Void> blocking(@NonNull Runnable call) {
        return Mono.<Void>fromRunnable(call).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Runs a CPU step on the pool its entry point set with {@link #cpuOn(Context, Scheduler)}, or on
     * {@link Schedulers#parallel()} when none is set. The step must not block: {@code parallel} has one thread per
     * core and also runs every Reactor timer.
     */
    public static <T> Mono<T> cpu(@NonNull Callable<T> step) {
        return Mono.deferContextual(context -> Mono.fromCallable(step)
                .subscribeOn(context.getOrDefault(CPU_SCHEDULER, Schedulers.parallel())));
    }

    /**
     * Routes every {@link #cpu(Callable)} step upstream of the {@code contextWrite} to {@code scheduler}, for an
     * entry point whose CPU work runs for seconds and needs its own core-sized pool:
     * {@code .contextWrite(context -> Offload.cpuOn(context, inference))}.
     */
    public static Context cpuOn(@NonNull Context context, @NonNull Scheduler scheduler) {
        return context.put(CPU_SCHEDULER, scheduler);
    }

    /**
     * Sets the entry point's own pool (a subscriber's workers), where {@link #resumeOriginal(Mono)} returns:
     * {@code .contextWrite(context -> Offload.originalOn(context, workers))}.
     */
    public static Context originalOn(@NonNull Context context, @NonNull Scheduler scheduler) {
        return context.put(ORIGINAL_SCHEDULER, scheduler);
    }

    /**
     * Moves what follows {@code source} back to the entry point's pool set with {@link #originalOn(Context, Scheduler)},
     * e.g. after a ClickHouse or Redis call: {@code dao.find(...).transform(Offload::resumeOriginal)}. With no pool
     * set it adds no hop. Never {@code parallel}: a {@code publishOn} moves every operator after it, blocking ones
     * included.
     */
    public static <T> Mono<T> resumeOriginal(@NonNull Mono<T> source) {
        return source.transformDeferredContextual((mono, context) -> context.<Scheduler>getOrEmpty(ORIGINAL_SCHEDULER)
                .map(mono::publishOn)
                .orElse(mono));
    }

    /**
     * {@link #resumeOriginal(Mono)} for a stream: every item and every operator after it.
     */
    public static <T> Flux<T> resumeOriginal(@NonNull Flux<T> source) {
        return source.transformDeferredContextual((flux, context) -> context.<Scheduler>getOrEmpty(ORIGINAL_SCHEDULER)
                .map(flux::publishOn)
                .orElse(flux));
    }

    /**
     * Moves every item of {@code items}, and every operator after this call, to {@link Schedulers#boundedElastic()}.
     * Put it directly before the operator that blocks or burns CPU per item.
     */
    public static <T> Flux<T> blockingEach(@NonNull Flux<T> items) {
        return items.publishOn(Schedulers.boundedElastic());
    }
}
