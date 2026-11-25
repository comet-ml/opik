package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicProject.RunError;
import com.comet.opik.domain.topics.TopicProject.RunStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("topic run outcomes")
class TopicRunOutcomesTest {

    private final TopicRun run = TopicRun.of(UUID.randomUUID(), "workspace", "Workspace", UUID.randomUUID(),
            LocalDate.of(2026, 11, 20), 30);
    private final TopicRunStore store = mock(TopicRunStore.class);
    private final TopicRunOutcomes outcomes = new TopicRunOutcomes(store);

    @Test
    @DisplayName("a run error fails the run with its code; the outcome carries the window bounds")
    void runErrorKeepsItsCode() {
        when(store.fail(run.runId(), run.workspace(), run.projectId(), RunError.LLM_BAD_ANSWER))
                .thenReturn(Mono.just(true));

        var outcome = outcomes
                .failed(run, new TopicsRunException(RunError.LLM_BAD_ANSWER, "no JSON"))
                .block();

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.errorCode()).isEqualTo("llm_bad_answer");
        assertThat(outcome.stats().get("window_from").asText()).isEqualTo(run.from().toString());
        verify(store, never()).isSaved(any());
    }

    @Test
    @DisplayName("a failed run's outcome carries what its error says the run had read, next to the window bounds")
    void failureCarriesWhatTheRunRead() {
        when(store.fail(run.runId(), run.workspace(), run.projectId(), RunError.LLM_ERROR))
                .thenReturn(Mono.just(true));
        var read = TopicRunOutcomes.bounds(run).removeAll().put("window_traces", 120);

        var outcome = outcomes.failed(run, TopicsRunException.withStats(
                new TopicsRunException(RunError.LLM_ERROR, "down"), read)).block();

        assertThat(outcome.stats().get("window_traces").asInt()).isEqualTo(120);
        assertThat(outcome.stats().has("window_from")).isTrue();
    }

    @Test
    @DisplayName("any other error fails the run with internal")
    void otherErrorIsInternal() {
        when(store.fail(run.runId(), run.workspace(), run.projectId(), RunError.INTERNAL))
                .thenReturn(Mono.just(true));

        var outcome = outcomes.failed(run, new IllegalStateException("bug")).block();

        assertThat(outcome.errorCode()).isEqualTo("internal");
    }

    @Test
    @DisplayName("a timeout that lost the race to the commit leaves the run saved")
    void timeoutAfterTheCommitIsSaved() {
        when(store.fail(run.runId(), run.workspace(), run.projectId(), RunError.TIMEOUT))
                .thenReturn(Mono.just(false));
        when(store.isSaved(run)).thenReturn(Mono.just(true));

        var outcome = outcomes
                .failed(run, new TopicsRunException(RunError.TIMEOUT, "late")).block();

        assertThat(outcome.status()).isEqualTo(RunStatus.SAVED);
        assertThat(outcome.errorCode()).isNull();
    }

    @Test
    @DisplayName("a mark that fails still returns the failed outcome")
    void failedMarkStillFails() {
        when(store.fail(run.runId(), run.workspace(), run.projectId(), RunError.SAVE))
                .thenReturn(Mono.error(new IllegalStateException("MySQL down")));

        var outcome = outcomes
                .failed(run, new TopicsRunException(RunError.SAVE, "conflict")).block();

        assertThat(outcome.status()).isEqualTo(RunStatus.FAILED);
        assertThat(outcome.errorCode()).isEqualTo("save");
    }
}
