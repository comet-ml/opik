package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExperimentExecutionRequest(
        @NotBlank String datasetName,
        UUID datasetVersionId,
        @NotEmpty @Valid List<PromptVariant> prompts,
        String projectName,
        @NotNull UUID datasetId,
        String versionHash,
        List<Experiment.PromptVersionLink> promptVersions) {

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PromptVariant(
            @NotBlank String model,
            @NotNull @NotEmpty List<Message> messages,
            Map<String, JsonNode> configs,
            List<Experiment.PromptVersionLink> promptVersions) {

        @Builder(toBuilder = true)
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
        public record Message(
                @NotBlank String role,
                @NotNull JsonNode content) {
        }
    }
}
