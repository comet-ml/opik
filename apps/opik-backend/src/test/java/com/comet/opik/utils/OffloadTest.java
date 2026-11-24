package com.comet.opik.utils;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import static org.assertj.core.api.Assertions.assertThat;

class OffloadTest {

    @Test
    void cpuRunsOnParallelUnlessTheEntryPointSetsAPool() {
        var workers = Schedulers.newSingle("offload-test-workers");
        try {
            var defaultThread = Offload.cpu(() -> Thread.currentThread().getName()).block();
            var routedThread = Mono.just(1)
                    .flatMap(__ -> Offload.cpu(() -> Thread.currentThread().getName()))
                    .contextWrite(context -> Offload.cpuOn(context, workers))
                    .block();

            assertThat(defaultThread).startsWith("parallel-");
            assertThat(routedThread).startsWith("offload-test-workers-");
        } finally {
            workers.dispose();
        }
    }
}
