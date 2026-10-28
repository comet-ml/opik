package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.db.HasValue;
import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code topic_runs}: one run of the nightly job on a project. {@code stats} is a JSON object, set when the
 * run ends.
 */
@Builder(toBuilder = true)
public record TopicRun(
        @NonNull UUID runId,
        @NonNull String workspaceId,
        @NonNull UUID projectId,
        @NonNull Mode mode,
        @NonNull Status status,
        @NonNull Instant startedAt,
        @Nullable Instant finishedAt,
        @Nullable String errorCode,
        @Nullable String errorMessage,
        @Nullable String stats) {

    /** {@code topic_runs.error_message} is cut to this many characters. */
    public static final int MAX_ERROR_MESSAGE = 4_000;

    @Getter
    @RequiredArgsConstructor
    public enum Mode implements HasValue {
        FIRST_BUILD("first_build"),
        NIGHTLY_UPDATE("nightly_update");

        private final String value;
    }

    @Getter
    @RequiredArgsConstructor
    public enum Status implements HasValue {
        RUNNING("running"),
        SAVED("saved"),
        FAILED("failed");

        private final String value;
    }
}
