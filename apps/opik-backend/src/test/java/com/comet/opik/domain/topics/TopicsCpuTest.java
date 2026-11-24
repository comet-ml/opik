package com.comet.opik.domain.topics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import static org.assertj.core.api.Assertions.assertThat;

class TopicsCpuTest {

    @Test
    @DisplayName("a CPU step runs on the scheduler in the context, and where it is subscribed when there is none")
    void stepRunsOnTheContextScheduler() {
        var workers = Schedulers.newSingle("topics-cpu-test");
        try {
            var onWorkers = TopicsCpu.step(() -> Thread.currentThread().getName())
                    .contextWrite(context -> context.put(Scheduler.class, workers))
                    .block();
            var onCaller = TopicsCpu.step(() -> Thread.currentThread().getName()).block();

            assertThat(onWorkers).startsWith("topics-cpu-test");
            assertThat(onCaller).isEqualTo(Thread.currentThread().getName());
        } finally {
            workers.dispose();
        }
    }
}
