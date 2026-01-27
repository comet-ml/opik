package com.comet.opik.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

@Builder(toBuilder = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "Request to test Ollama connection")
public record OllamaConnectionTestRequest(
        @Schema(description = "Base URL of the Ollama instance (e.g., http://localhost:11434)", example = "http://localhost:11434") @NotBlank(message = "Base URL is required") String baseUrl) {
}
