package com.comet.opik.api;

import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A model has no status of its own: its state is its runs. {@code servingRunId} is its most recent COMPLETED run, null
 * until one completes, which is what makes the model usable in a rule; {@code latestRunId} is its most recent run,
 * whose status the Models tab shows.
 */
@Builder(toBuilder = true)
public record GuardrailModel(
        UUID id,
        String name,
        String description,
        UUID servingRunId,
        UUID latestRunId,
        Instant createdAt,
        String createdBy,
        Instant lastUpdatedAt,
        String lastUpdatedBy) {

    @Builder(toBuilder = true)
    public record GuardrailModelPage(
            List<GuardrailModel> content,
            int page,
            int size,
            long total,
            List<String> sortableBy) implements Page<GuardrailModel> {
    }
}
