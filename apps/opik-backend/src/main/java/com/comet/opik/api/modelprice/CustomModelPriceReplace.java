package com.comet.opik.api.modelprice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CustomModelPriceReplace(
        @NotNull @Schema(description = "The whole list after the change; rows left out are deleted") List<JsonNode> rows,
        @Schema(description = "The version the rows were read at; a newer list answers 409 and saves nothing") Long expectedVersion) {
}
