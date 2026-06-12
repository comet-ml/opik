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
public record SpendCompositionResponse(
        Side input,
        List<HarnessEntry> harness,
        Side output,
        // Distinct cc.billing.model values in the window — the FE uses
        // these to price the tier columns.
        List<String> models) {

    @Builder(toBuilder = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Side(
            Long totalTokens,
            List<Lane> lanes) {
    }

    @Builder(toBuilder = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Lane(
            String key,
            String label,
            Long totalTokens,
            // Raw cache-tier sums from cc.billing — the FE prices these
            // (hardcoded Claude rates); the BE ships data, not dollars.
            Long inputTokens,
            Long cacheReadTokens,
            Long cacheCreationTokens,
            Long outputTokens,
            boolean hasBreakdown) {
    }

    @Builder(toBuilder = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HarnessEntry(
            String key,
            String label) {
    }
}
