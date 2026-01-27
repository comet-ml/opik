package com.comet.opik.api;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder(toBuilder = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "Response from Ollama API v1 connection test. "
        + "Only Ollama instances with version >= 0.1.0 (API v1-compatible) are supported.")
public record OllamaConnectionTestResponse(
        @Schema(description = "Whether the connection was successful and the instance is API v1-compatible") boolean connected,

        @Schema(description = "Server version (returned even if connection failed or version is incompatible)") String version,

        @Schema(description = "Error message if connection failed or version is not API v1-compatible") String errorMessage) {
}
