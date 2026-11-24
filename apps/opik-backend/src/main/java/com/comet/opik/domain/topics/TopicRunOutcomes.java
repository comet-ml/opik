package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicProject.RunError;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import reactor.core.publisher.Mono;

import java.util.EnumSet;
import java.util.Set;

/**
 * How a run ends: saved, failed with its code, or cancelled. A failure is marked on the run's row through
 * {@link TopicRunStore#fail}, and its log redacts the codes whose errors can carry trace-derived text.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class TopicRunOutcomes {

    /**
     * Failure codes whose exceptions can carry trace-derived text: of the LLM's answer, or the topic names and definitions
     * a failed save statement quotes in its bound arguments.
     */
    private static final Set<RunError> TEXT_CODES = EnumSet.of(RunError.LLM_TIMEOUT, RunError.LLM_ERROR,
            RunError.LLM_BAD_ANSWER, RunError.SAVE);

    private final @NonNull TopicRunStore store;

    /** The run's window bounds, the stats every outcome carries. */
    static ObjectNode bounds(@NonNull TopicRun run) {
        var stats = JsonUtils.createObjectNode();
        stats.put("window_from", run.from().toString());
        stats.put("window_until", run.to().toString());
        return stats;
    }

    TopicRunOutcome saved(@NonNull TopicRun run, @NonNull TopicRunRows rows, boolean inserted) {
        log.info("Topics run '{}' saved, project '{}', trace topics '{}', stats '{}'", run.runId(), run.projectId(),
                inserted ? rows.traceTopics().size() : 0, rows.stats());
        return outcome(run, rows.stats(), TopicProject.RunStatus.SAVED, null);
    }

    /**
     * Marks the run failed with the error's code ({@code internal} when it has none). When the run had ended already,
     * a timeout raced its commit: the run is saved, without its trace_topics, which the labelling pass gives.
     */
    Mono<TopicRunOutcome> failed(@NonNull TopicRun run, @NonNull Throwable error) {
        var code = error instanceof TopicsRunException exception ? exception.getCode() : RunError.INTERNAL;
        var stats = bounds(run);
        return store.fail(run.runId(), run.workspace(), run.projectId(), code)
                .flatMap(marked -> marked ? Mono.just(false) : store.isSaved(run))
                .onErrorResume(markError -> {
                    log.error("Topics run '{}' could not be marked failed, project '{}'", run.runId(),
                            run.projectId(), markError);
                    return Mono.just(false);
                })
                .map(saved -> {
                    if (saved) {
                        log.warn("Topics run '{}' saved, project '{}', but stopped before its trace_topics insert, "
                                + "code '{}'", run.runId(), run.projectId(), code.getValue());
                        return outcome(run, stats, TopicProject.RunStatus.SAVED, null);
                    }
                    logFailure(run, code, error);
                    return outcome(run, stats, TopicProject.RunStatus.FAILED, code.getValue());
                });
    }

    /** A run its subscriber cancelled fails with {@code timeout}. */
    Mono<Boolean> cancelled(@NonNull TopicRun run) {
        log.warn("Topics run '{}' cancelled, project '{}'", run.runId(), run.projectId());
        return store.fail(run.runId(), run.workspace(), run.projectId(), RunError.TIMEOUT)
                .onErrorResume(error -> {
                    log.error("Topics run '{}' could not be marked failed, project '{}'", run.runId(),
                            run.projectId(), error);
                    return Mono.just(false);
                });
    }

    private static void logFailure(TopicRun run, RunError code, Throwable error) {
        if (TEXT_CODES.contains(code)) {
            // The answer's length and SHA-256 are logged when it arrives.
            log.warn("Topics run '{}' failed, project '{}', code '{}', errors '{}'", run.runId(), run.projectId(),
                    code.getValue(), ExceptionUtils.getThrowableList(error).stream()
                            .map(TopicsErrors::formatError).toList());
        } else {
            log.warn("Topics run '{}' failed, project '{}', code '{}'", run.runId(), run.projectId(),
                    code.getValue(), error);
        }
    }

    private static TopicRunOutcome outcome(TopicRun run, ObjectNode stats, TopicProject.RunStatus status,
            String errorCode) {
        return TopicRunOutcome.builder().runId(run.runId()).status(status).errorCode(errorCode).stats(stats).build();
    }
}
