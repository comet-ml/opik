package com.comet.opik.api;

import com.comet.opik.api.validation.HttpUrl;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

@Builder(toBuilder = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "Request with Ollama instance base URL for connection testing or model discovery. "
        + "The URL may be provided with or without /v1 suffix (e.g., http://localhost:11434 or http://localhost:11434/v1). "
        + "The /v1 suffix will be automatically removed for connection testing and model discovery. "
        + "For actual LLM inference, the URL must include /v1 suffix (e.g., http://localhost:11434/v1).")
public record OllamaInstanceBaseUrlRequest(
        @Schema(description = "Base URL of the Ollama instance (with or without /v1 suffix, e.g., http://localhost:11434 or http://localhost:11434/v1). "
                + "For inference, the URL must include /v1 suffix for OpenAI-compatible endpoints.", example = "http://localhost:11434/v1") @NotBlank(message = "Base URL is required") @HttpUrl String baseUrl) {
}
