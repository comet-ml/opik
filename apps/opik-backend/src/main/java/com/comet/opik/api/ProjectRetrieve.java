package com.comet.opik.api;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

@Builder
public record ProjectRetrieve(@NotBlank String name, Boolean includeStats) {

    /**
     * Whether the response should be enriched with project-level statistics
     * (feedback scores, trace/span aggregations, duration, cost, counts).
     * <p>
     * Defaults to {@code false}: callers that only need to resolve a project
     * name to its id (the SDK and the UI redirect path) should not trigger the
     * expensive ClickHouse aggregations. See OPIK-7101.
     */
    public boolean shouldIncludeStats() {
        return Boolean.TRUE.equals(includeStats);
    }
}
