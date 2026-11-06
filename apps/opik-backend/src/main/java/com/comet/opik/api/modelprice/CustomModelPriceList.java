package com.comet.opik.api.modelprice;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.NonNull;

import java.util.List;

@Builder(toBuilder = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CustomModelPriceList(
        @Schema(description = "Raised by every saved change; send it back as expected_version") long version,
        @NonNull List<CustomModelPriceRow> rows) {

    public static final CustomModelPriceList EMPTY = CustomModelPriceList.builder().version(0).rows(List.of()).build();
}
