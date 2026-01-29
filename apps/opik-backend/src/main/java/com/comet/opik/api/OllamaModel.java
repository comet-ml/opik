package com.comet.opik.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;

@Builder(toBuilder = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "Ollama model information")
public record OllamaModel(
        @NonNull @NotBlank @Schema(description = "Model name", example = "llama2") String name,

        @Schema(description = "Model size in bytes") Long size,

        @Schema(description = "Model digest/hash") String digest,

        @Schema(description = "Model modification date") Instant modifiedAt) {
}
