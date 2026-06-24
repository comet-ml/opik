package com.comet.opik.api;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

@Builder
public record ProjectRetrieve(@NotBlank String name, @Nullable Boolean includeStats) {

    /**
     * Whether the response should be enriched with project-level statistics
     * (feedback scores, trace/span aggregations, duration, cost, counts).
     * <p>
     * Defaults to {@code false}: callers that only need to resolve a project
     * name to its id (the SDK and the UI redirect path) should not trigger the
     * expensive ClickHouse aggregations. The canonical accessor is overridden to
     * coalesce {@code null} to {@code false}, so it never returns {@code null}.
     * See OPIK-7101.
     */
    @Override
    public Boolean includeStats() {
        return Boolean.TRUE.equals(includeStats);
    }
}
