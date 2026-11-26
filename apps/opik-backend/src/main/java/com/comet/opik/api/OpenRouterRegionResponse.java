package com.comet.opik.api;

import com.comet.opik.infrastructure.llm.openrouter.OpenRouterRegion;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OpenRouterRegionResponse(
        @Schema(description = "Where the workspace's OpenRouter requests go: the host of its OpenRouter provider's base_url, or of the server's default OpenRouter URL when it has none. unknown for a host other than openrouter.ai, eu.openrouter.ai or us.openrouter.ai") OpenRouterRegion region) {
}
