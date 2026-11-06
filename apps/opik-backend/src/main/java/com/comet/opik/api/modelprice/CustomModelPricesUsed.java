package com.comet.opik.api.modelprice;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.NonNull;

import java.time.LocalDate;
import java.util.List;

@Builder(toBuilder = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CustomModelPricesUsed(
        @Schema(description = "How many days back the list covers") int days,
        @Schema(description = "True when the organization used more models than the list holds") boolean truncated,
        @NonNull List<Model> content) {

    public static CustomModelPricesUsed empty(int days) {
        return CustomModelPricesUsed.builder().days(days).content(List.of()).build();
    }

    @Builder(toBuilder = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Model(
            @Schema(description = "As the spans sent it, in lower case: the name a price row should use") @NonNull String model,
            @Schema(description = "As spans are looked up; empty when the spans had no provider") @NonNull String provider,
            @NonNull LocalDate lastSeen,
            @Schema(description = "On how many of the covered days a span used it") int daysUsed,
            @NonNull List<Workspace> workspaces,
            @Schema(description = "Where its spans' price comes from today", allowableValues = {
                    "organization", "built_in", "none"}) @NonNull String priceSource) {
    }

    @Builder(toBuilder = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Workspace(@NonNull String id, @NonNull String name) {
    }
}
