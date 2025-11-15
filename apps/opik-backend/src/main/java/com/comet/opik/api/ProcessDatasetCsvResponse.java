package com.comet.opik.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder(toBuilder = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProcessDatasetCsvResponse(
        @Schema(description = "Status of the CSV processing", example = "processing") CsvProcessingStatus status,
        @Schema(description = "Message describing the processing status", example = "CSV processing started") String message) {
}
