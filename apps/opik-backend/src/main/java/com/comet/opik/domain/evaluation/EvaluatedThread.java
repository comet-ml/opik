package com.comet.opik.domain.evaluation;

import lombok.Builder;
import lombok.NonNull;

import java.util.UUID;

/**
 * An evaluated thread. Threads have no single input/output/name, so this kind carries only the
 * thread id and project — it inherits the {@code null} defaults for {@code name/input/output}.
 */
@Builder
public record EvaluatedThread(@NonNull String id, UUID projectId, String projectName) implements EvaluatedSubject {

    @Override
    public String evaluatedIdKey() {
        return "evaluated_thread_id";
    }
}
