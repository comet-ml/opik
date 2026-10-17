package com.comet.opik.domain.ailabels;

import com.comet.opik.infrastructure.db.HasValue;
import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.time.Instant;

/**
 * One row of {@code ai_label_jobs}: an opik-inference batch job. {@code engineCalls} is what it costs against the
 * in-flight budget while {@code submitted}.
 */
@Builder(toBuilder = true)
public record AiLabelJob(
        @NonNull String jobId,
        @NonNull String workspaceId,
        @NonNull AiLabelEndpoint endpoint,
        boolean cache,
        int itemCount,
        int engineCalls,
        @NonNull Status status,
        @NonNull Instant submittedAt,
        @Nullable Instant lastPolledAt,
        @Nullable Instant terminalAt) {

    @Getter
    @RequiredArgsConstructor
    public enum Status implements HasValue {
        SUBMITTED("submitted"),
        LOADED("loaded");

        private final String value;
    }
}
