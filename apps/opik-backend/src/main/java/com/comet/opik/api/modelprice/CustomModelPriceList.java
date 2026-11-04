package com.comet.opik.api.modelprice;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.NonNull;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CustomModelPriceList(
        @Schema(description = "Raised by every saved change; send it back as expected_version") long version,
        @NonNull List<CustomModelPriceRow> rows) {

    public static final CustomModelPriceList EMPTY = new CustomModelPriceList(0, List.of());
}
