package com.comet.opik.domain;

import com.comet.opik.api.TraceThread;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class ThreadDAOImplTest {

    @Test
    @DisplayName("a multi-element stream collapses to the first thread (singleOrEmpty would throw here)")
    void firstThreadOrEmptyCollapsesMultipleEmissions() {
        var first = TraceThread.builder().id("thread-1").build();
        var second = TraceThread.builder().id("thread-2").build();

        StepVerifier.create(ThreadDAOImpl.firstThreadOrEmpty(Flux.just(first, second)))
                .assertNext(thread -> assertThat(thread.id()).isEqualTo("thread-1"))
                .verifyComplete();
    }

    @Test
    @DisplayName("a single emitted thread passes through unchanged")
    void firstThreadOrEmptyPassesSingleThreadThrough() {
        var thread = TraceThread.builder().id("thread-1").build();

        StepVerifier.create(ThreadDAOImpl.firstThreadOrEmpty(Flux.just(thread)))
                .assertNext(result -> assertThat(result.id()).isEqualTo("thread-1"))
                .verifyComplete();
    }

    @Test
    @DisplayName("an empty stream completes empty (preserving the not-found contract)")
    void firstThreadOrEmptyCompletesEmptyForEmptyStream() {
        StepVerifier.create(ThreadDAOImpl.firstThreadOrEmpty(Flux.empty()))
                .verifyComplete();
    }
}
