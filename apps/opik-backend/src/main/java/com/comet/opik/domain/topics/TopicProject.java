package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.db.HasValue;
import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;

/**
 * One row of {@code topic_projects}: a project's current topics state, never its history. Each run and night
 * logs itself and emits metrics instead. The vectors tick creates the row when it first sees the project, a discovery
 * run when it first starts one. Each group of fields is null, 0 or false until its writer first writes it, and each
 * writer updates its own group: the vectors tick the backfill fields, a run the attempt fields ({@code run*},
 * {@code failedRuns}, {@code firstFailedAt}), the save the list fields ({@code savedRuns} to {@code embedModel}),
 * the night {@code lastNight} and {@code notReachedNights}. For the backfill, ClickHouse stays the only source of
 * which traces have no vector.
 *
 * @param backfillStartedAt when this backfill began: the tick first saw the project, or reset the backfill because it
 *        did not run for the workspace for 2 x {@code topics.vectors.longWindowInterval}
 * @param backfillDrainedAt the start of the tick that drained the project since {@code backfillStartedAt}, null until
 *        one does. Drained: the tick read the project's whole backfill window, found fewer than
 *        {@code maxTracesPerTick} traces, and left none of them for want of time, a read, a back-off or an insert. So
 *        every trace with a done status in the window up to that instant, and a usable summary, had a vector when the
 *        tick ended, but for those the embed service refuses or answers with an invalid vector
 * @param backfillLastDoneAt the project's latest done status, as last discovered
 * @param backfillTracesTotal about how many traces of the project can get a vector: its done status rows of traces
 *        created in the backfill window, as last discovered. Too high by the traces whose summary is blank or
 *        missing, by those the embed service refuses, and by a relabelled trace's older done rows until ClickHouse
 *        merges them
 * @param backfillTracesDone about how many of them have a vector, as last discovered, plus the vectors the backfill
 *        wrote since. Never above {@code backfillTracesTotal}. Not kept up once the project is drained
 * @param savedRuns the saved runs, discovery run or nightly update
 * @param listRunId the run whose list is saved; the save is a compare-and-set on it
 * @param windowUntil the end of that run's window
 * @param labelsComplete false from the save until the labelling pass has given every trace of that window a
 *        {@code trace_topics} row of the run
 * @param otherVectors the known-Other prototype vectors, null when the run kept none
 * @param embedModel the model of the saved vectors, the topics' and {@code otherVectors}; their size is
 *        {@link TopicEmbedding#DIMENSIONS}
 * @param runErrorCode why the attempt failed, null unless it did
 * @param failedRuns the failed attempts since the last saved one
 * @param firstFailedAt the start of the first of them
 * @param lastNight the last night that looked at the project
 * @param notReachedNights the nights in a row whose budget ended before the project
 */
@Builder(toBuilder = true)
public record TopicProject(
        @NonNull String workspaceId,
        @NonNull UUID projectId,
        @Nullable Instant backfillStartedAt,
        @Nullable Instant backfillDrainedAt,
        @Nullable Instant backfillLastDoneAt,
        long backfillTracesTotal,
        long backfillTracesDone,
        int savedRuns,
        @Nullable Instant firstBuiltAt,
        @Nullable Instant lastSuccessAt,
        @Nullable UUID listRunId,
        @Nullable Instant windowUntil,
        boolean labelsComplete,
        @Nullable PrototypeVectors otherVectors,
        @Nullable String embedModel,
        @Nullable UUID runId,
        @Nullable RunMode runMode,
        @Nullable RunStatus runStatus,
        @Nullable Instant runStartedAt,
        @Nullable Instant runFinishedAt,
        @Nullable RunError runErrorCode,
        int failedRuns,
        @Nullable Instant firstFailedAt,
        @Nullable LocalDate lastNight,
        int notReachedNights) {

    @Getter
    @RequiredArgsConstructor
    public enum RunMode implements HasValue {
        DISCOVERY("discovery"),
        UPDATE("update");

        private final String value;

        public static RunMode fromString(String value) {
            return Arrays.stream(values())
                    .filter(candidate -> candidate.value.equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown topic run mode '%s'".formatted(value)));
        }
    }

    @Getter
    @RequiredArgsConstructor
    public enum RunStatus implements HasValue {
        RUNNING("running"),
        SAVED("saved"),
        FAILED("failed");

        private final String value;

        public static RunStatus fromString(String value) {
            return Arrays.stream(values())
                    .filter(candidate -> candidate.value.equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown topic run status '%s'".formatted(value)));
        }
    }

    /**
     * Why an attempt failed: the code a {@link TopicsRunException} carries, or {@link #ABANDONED} when its pod died
     * while it ran (the project's next attempt records it).
     */
    @Getter
    @RequiredArgsConstructor
    public enum RunError implements HasValue {
        /** An LLM call ran out of its deadline. */
        LLM_TIMEOUT("llm_timeout"),
        /** An LLM call failed for another reason (an error answer after the retries, a 4xx). */
        LLM_ERROR("llm_error"),
        /** The answer has no JSON object or breaks the schema. */
        LLM_BAD_ANSWER("llm_bad_answer"),
        WINDOW_READ("window_read"),
        /** A check before the save failed: nothing is written. */
        INVARIANT("invariant"),
        /** The run outlasted its time limit. */
        TIMEOUT("timeout"),
        SAVE("save"),
        INTERNAL("internal"),
        ABANDONED("abandoned");

        private final String value;

        public static RunError fromString(String value) {
            return Arrays.stream(values())
                    .filter(candidate -> candidate.value.equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown topic run error '%s'".formatted(value)));
        }
    }
}
