package com.comet.opik.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder(toBuilder = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "Response from Ollama connection test")
public record OllamaConnectionTestResponse(
        @Schema(description = "Whether the connection was successful") boolean connected,

        @Schema(description = "Server version if connection successful") String version,

        @Schema(description = "Error message if connection failed") String errorMessage) {
}
