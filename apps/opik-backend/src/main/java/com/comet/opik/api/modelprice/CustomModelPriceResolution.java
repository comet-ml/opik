package com.comet.opik.api.modelprice;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.NonNull;

import java.util.List;

@Builder(toBuilder = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CustomModelPriceResolution(
        @NonNull String model,
        String provider,
        @Schema(description = "What a span with this usage costs, in USD") @NonNull String cost,
        @Schema(allowableValues = {
                "organization", "built_in", "none"}) @NonNull String costSource,
        @Schema(description = "The organization's list version it was priced with; 0 for the built-in list only") long listVersion,
        @Schema(description = "Absent when no row prices the model") Match match,
        @Schema(description = "The rates that went into the cost") @NonNull List<Line> lines) {

    @Builder(toBuilder = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Match(
            @NonNull String provider,
            @Schema(description = "Which spelling of the name matched, such as exact or date_stripped") @NonNull String nameForm,
            @Schema(description = "The organization's row the name matched; absent for a built-in price") CustomModelPriceRow row,
            @Schema(description = "For an alias row, the organization's row it led to; absent when it led to a built-in price") CustomModelPriceRow targetRow) {
    }

    @Builder(toBuilder = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Line(
            @Schema(example = "input") @NonNull String name,
            @Schema(description = "USD per 1M tokens") @NonNull String ratePerMillion,
            @Schema(allowableValues = {
                    "organization", "built_in", "none"}) @NonNull String source) {
    }
}
