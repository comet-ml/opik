package com.comet.opik.api;

import com.comet.opik.infrastructure.llm.openrouter.OpenRouterRegion;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.List;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OpenRouterRegionResponse(
        @Schema(description = "Where the workspace's OpenRouter requests go: the host of its OpenRouter provider's base_url, or of the server's default OpenRouter URL when it has none. unknown for a host other than openrouter.ai, eu.openrouter.ai or us.openrouter.ai") OpenRouterRegion region,
        @Schema(description = "Decision models the server refuses for this workspace, each with the region that doesn't serve it: the server's decisions URL region or the workspace's region") List<UnavailableDecisionModel> unavailableDecisionModels) {

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UnavailableDecisionModel(String model, OpenRouterRegion region) {
    }
}
