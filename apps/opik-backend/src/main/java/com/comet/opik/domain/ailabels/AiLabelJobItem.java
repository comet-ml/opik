package com.comet.opik.domain.ailabels;

import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code ai_label_job_items}: a (trace, task) in flight in a submitted job, with what its status row needs
 * once the job loads: the attempt, the versions and the {@code compaction_level} recorded at submit.
 */
@Builder(toBuilder = true)
public record AiLabelJobItem(
        @NonNull String jobId,
        @NonNull String workspaceId,
        @NonNull UUID projectId,
        @NonNull UUID traceId,
        @NonNull Instant traceCreatedAt,
        @NonNull String task,
        int taskVersion,
        int rendererVersion,
        @NonNull String compactionLevel,
        int attempt) {

    public AiLabelStatus status(@NonNull AiLabelStatus.Status status, boolean retryable, @NonNull String errorCode) {
        return AiLabelStatus.builder()
                .workspaceId(workspaceId)
                .projectId(projectId)
                .task(task)
                .traceId(traceId)
                .traceCreatedAt(traceCreatedAt)
                .taskVersion(taskVersion)
                .rendererVersion(rendererVersion)
                .status(status)
                .retryable(retryable)
                .attempt(attempt)
                .errorCode(errorCode)
                .jobId(jobId)
                .compactionLevel(compactionLevel)
                .build();
    }

    public AiLabelStatus done() {
        return status(AiLabelStatus.Status.DONE, false, "");
    }

    public AiLabelStatus failed(@NonNull String errorCode, boolean retryable) {
        return status(AiLabelStatus.Status.FAILED, retryable, errorCode);
    }
}
