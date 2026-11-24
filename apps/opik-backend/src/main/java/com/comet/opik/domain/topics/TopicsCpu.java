package com.comet.opik.domain.topics;

import lombok.NonNull;
import lombok.experimental.UtilityClass;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.Callable;

/**
 * Where a Topics CPU step runs: on the scheduler its entry point put in the Reactor context, once, as
 * {@code contextWrite(context -> context.put(Scheduler.class, workers))}. A caller that sets none, such as an API,
 * runs the step where it subscribes. Blocking MySQL calls don't use this: they go to {@code boundedElastic}.
 */
@UtilityClass
class TopicsCpu {

    /** {@code step}, run on the context's scheduler. */
    <T> Mono<T> step(@NonNull Callable<T> step) {
        return Mono.deferContextual(context -> Mono.fromCallable(step)
                .subscribeOn(context.getOrDefault(Scheduler.class, Schedulers.immediate())));
    }
}
