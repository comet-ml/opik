package com.comet.opik.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

@Builder(toBuilder = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProcessDatasetCsvRequest(
        @NotBlank(message = "file_path must not be blank") @Schema(description = "Path to CSV file in S3/MinIO", requiredMode = Schema.RequiredMode.REQUIRED, example = "attachments/workspace-id/dataset-id/file.csv") String filePath) {
}
