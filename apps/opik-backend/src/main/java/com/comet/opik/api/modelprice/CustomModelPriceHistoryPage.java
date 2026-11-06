package com.comet.opik.api.modelprice;

import com.comet.opik.api.Page;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CustomModelPriceHistoryPage(int page, int size, long total, @NonNull List<Entry> content)
        implements
            Page<CustomModelPriceHistoryPage.Entry> {

    @Builder(toBuilder = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Entry(
            @Schema(description = "The list version this change produced") long listVersion,
            @NonNull UUID rowId,
            @Schema(allowableValues = {
                    "created", "updated", "deleted"}) @NonNull String action,
            @Schema(description = "The row before the change, in the shape the list returns; absent for a created row") CustomModelPriceRow before,
            @Schema(description = "The row after the change, in the shape the list returns; absent for a deleted row") CustomModelPriceRow after,
            @NonNull Instant createdAt,
            @NonNull String createdBy) {
    }

    public static CustomModelPriceHistoryPage empty(int page, int size) {
        return CustomModelPriceHistoryPage.builder().page(page).size(size).total(0).content(List.of()).build();
    }
}
