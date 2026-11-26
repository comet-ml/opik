package com.comet.opik.domain.topics;

import io.dropwizard.lifecycle.Managed;
import jakarta.inject.Singleton;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * The Topics runs' own schedulers, apart from {@code parallel()} and from each other. The CPU steps (clustering, the
 * assignment, a nightly update's steps, labelling) run on {@link #cpu()}, one thread per core. The heartbeats and the
 * runs' time limits run on {@link #timer()}, one thread that only fires timers: CPU steps that hold every CPU thread
 * then cannot hold back a heartbeat, so a healthy run is never recorded abandoned.
 */
@Singleton
public class TopicsRunSchedulers implements Managed {

    private final Scheduler cpu = Schedulers.newParallel("topics-cpu", Schedulers.DEFAULT_POOL_SIZE, true);
    private final Scheduler timer = Schedulers.newSingle("topics-timer", true);

    Scheduler cpu() {
        return cpu;
    }

    Scheduler timer() {
        return timer;
    }

    @Override
    public void stop() {
        cpu.dispose();
        timer.dispose();
    }
}
