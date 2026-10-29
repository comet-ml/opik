package com.comet.opik.domain.ailabels;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code ai_label_status}: the pipeline state of one (trace, task). The latest row per (trace, task) is
 * authoritative; {@code updated_at} is set by ClickHouse at insert.
 */
@Builder(toBuilder = true)
public record AiLabelStatus(
        @NonNull String workspaceId,
        @NonNull UUID projectId,
        @NonNull String task,
        @NonNull UUID traceId,
        @NonNull Instant traceCreatedAt,
        int taskVersion,
        int rendererVersion,
        @NonNull Status status,
        boolean retryable,
        int attempt,
        @NonNull String errorCode,
        @NonNull String jobId,
        @NonNull String compactionLevel) {

    @Getter
    @RequiredArgsConstructor
    public enum Status {
        DONE("done"),
        FAILED("failed");

        @JsonValue
        private final String value;
    }
}
