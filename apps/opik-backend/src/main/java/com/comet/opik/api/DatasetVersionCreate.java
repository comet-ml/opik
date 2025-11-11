package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.Map;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DatasetVersionCreate(
        @Size(max = 100, message = "Tag must be at most 100 characters") @Schema(description = "Optional tag for this version", examples = {
                "baseline", "v1.0"}) String tag,
        @Schema(description = "Optional description of changes in this version", example = "Initial baseline version with production data") String changeDescription,
        @Schema(description = "Optional user-defined metadata") Map<String, String> metadata){
}
