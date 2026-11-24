package com.comet.opik.utils;

import lombok.NonNull;
import lombok.experimental.UtilityClass;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
 *       {@link Schedulers#parallel()}. A step that runs for seconds also needs a concurrency cap at its caller.</li>
 *   <li>{@link #blockingEach(Flux)}: per-item blocking or CPU side effects of a stream, such as writing response
 *       chunks. Moves the items, which a {@code subscribeOn} can't do.</li>
 * </ul>
 *
 * <p>Light work (mapping one row, building a small DTO) needs none of these. The rules are in
 * {@code .agents/skills/opik-backend/reactive-threading.md}.
 */
@UtilityClass
public class Offload {

    /**
     * Runs a blocking call on {@link Schedulers#boundedElastic()}. What follows runs there too until the next hop.
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
     * Runs a CPU step on {@link Schedulers#parallel()}. The step must not block: {@code parallel} has one thread per
     * core and also runs every Reactor timer.
     */
    public static <T> Mono<T> cpu(@NonNull Callable<T> step) {
        return Mono.fromCallable(step).subscribeOn(Schedulers.parallel());
    }

    /**
     * Moves every item of {@code items}, and every operator after this call, to {@link Schedulers#boundedElastic()}.
     * Put it directly before the operator that blocks or burns CPU per item.
     */
    public static <T> Flux<T> blockingEach(@NonNull Flux<T> items) {
        return items.publishOn(Schedulers.boundedElastic());
    }
}
