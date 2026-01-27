package com.comet.opik.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

@Builder(toBuilder = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "Request to test Ollama API v1 connection. "
        + "For OpenAI-compatible endpoints, the base URL must end with /v1 (e.g., http://localhost:11434/v1). "
        + "For native Ollama API endpoints (/api/version, /api/tags), use the base URL without /v1.")
public record OllamaConnectionTestRequest(
        @Schema(description = "Base URL of the Ollama instance. "
                + "Use http://localhost:11434 for native Ollama API endpoints, "
                + "or http://localhost:11434/v1 for OpenAI-compatible endpoints.", example = "http://localhost:11434") @NotBlank(message = "Base URL is required") String baseUrl) {
}
