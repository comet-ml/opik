package com.comet.opik.api;

import com.comet.opik.api.validation.HttpUrl;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

import java.util.Objects;

@Builder(toBuilder = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "Request with Ollama instance base URL for connection testing or model discovery.")
public record OllamaInstanceBaseUrlRequest(
        @Schema(description = "Base URL of the Ollama instance. May include /v1 suffix which will be automatically removed for connection testing. "
                + "For inference, use the URL with /v1 suffix for OpenAI-compatible endpoints.", example = "http://localhost:11434/v1") @NotBlank(message = "Base URL is required") @HttpUrl String baseUrl,

        @Schema(description = "Optional API key for authenticated Ollama instances. "
                + "If provided, will be sent as Bearer token in Authorization header.") @Nullable String apiKey) {

    /**
     * Override toString to prevent API key from being logged.
     */
    @Override
    public String toString() {
        return "OllamaInstanceBaseUrlRequest[baseUrl=" + baseUrl + ", apiKey="
                + Objects.toString(apiKey, "null").replaceAll(".", "*") + "]";
    }
}