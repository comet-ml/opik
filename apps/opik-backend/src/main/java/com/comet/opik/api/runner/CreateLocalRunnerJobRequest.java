package com.comet.opik.api.runner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;

import java.util.Map;
import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CreateLocalRunnerJobRequest(
        @NotBlank String agentName,
        JsonNode inputs,
        @NotNull UUID projectId,
        @Deprecated(forRemoval = true) @Schema(deprecated = true, description = "Deprecated. Use prompt_masks to pass one or more mask overlays keyed by prompt id.") UUID maskId,
        @Schema(description = "Mask overlays to apply during agent execution, keyed by prompt id.") Map<UUID, UUID> promptMasks,
        @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") String blueprintName,
        LocalRunnerJobMetadata metadata) {
}
