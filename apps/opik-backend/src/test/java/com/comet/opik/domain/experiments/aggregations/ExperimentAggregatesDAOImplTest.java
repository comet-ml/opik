package com.comet.opik.domain.experiments.aggregations;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExperimentAggregatesDAOImplTest {

    @Test
    @DisplayName("multiple emitted chunks are unioned into exactly one Set (single() would throw here)")
    void unionProjectIdChunksUnionsMultipleChunks() {
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();

        StepVerifier.create(ExperimentAggregatesDAOImpl.unionProjectIdChunks(
                Flux.just(Set.of(a, b), Set.of(b, c))))
                .assertNext(projectIds -> assertThat(projectIds).containsExactlyInAnyOrder(a, b, c))
                .verifyComplete();
    }

    @Test
    @DisplayName("a single emitted chunk passes through unchanged")
    void unionProjectIdChunksPassesSingleChunkThrough() {
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();

        StepVerifier.create(ExperimentAggregatesDAOImpl.unionProjectIdChunks(Flux.just(Set.of(a, b))))
                .assertNext(projectIds -> assertThat(projectIds).containsExactlyInAnyOrder(a, b))
                .verifyComplete();
    }

    @Test
    @DisplayName("an empty stream still emits exactly one, empty Set")
    void unionProjectIdChunksEmitsEmptySetForEmptyStream() {
        StepVerifier.create(ExperimentAggregatesDAOImpl.unionProjectIdChunks(Flux.empty()))
                .assertNext(projectIds -> assertThat(projectIds).isEmpty())
                .verifyComplete();
    }
}
