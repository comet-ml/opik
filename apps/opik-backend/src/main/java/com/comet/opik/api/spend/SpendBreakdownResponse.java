package com.comet.opik.api.spend;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

import java.util.List;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SpendBreakdownResponse(
        String laneKey,
        String title,
        String subtitle,
        Long totalTokens,
        // Per-model tier sums for the lane's window total — the FE prices
        // each model at its own rate and sums.
        List<ModelTiers> byModel,
        // Total occurrences across the lane (the "calls" figure), not the
        // distinct-entity count.
        int itemCount,
        // Singular noun for itemCount / item.count ("prompt", "call", "load").
        // Null when counts are structurally 0 for the lane; the UI then hides
        // the count column and header segment.
        String itemUnit,
        List<Item> items) {

    @Builder(toBuilder = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(
            String label,
            Long totalTokens,
            // Stacked-bar segments: always-on definition cost vs
            // conversation-driven usage cost. Sum == totalTokens.
            Long definitionTokens,
            Long usageTokens,
            // Per-model tier sums so the FE can price each row at the right
            // rate when an item spans more than one model.
            List<ModelTiers> byModel,
            // New events this turn summed across traces = true counts
            // (loads, calls, prompts, files).
            Long count) {
    }
}
