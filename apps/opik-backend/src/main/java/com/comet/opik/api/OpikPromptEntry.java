package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

import java.util.UUID;

/**
 * One entry of the {@code opik_prompts} trace metadata array. Mirrors the shape the
 * Python SDK writes (see {@code __internal_api__to_info_dict__} on {@code BasePrompt})
 * and the frontend's {@code PromptLibraryMetadata}. Treat changes as a wire-format
 * change requiring SDK + FE coordination.
 */
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OpikPromptEntry(
        UUID id,
        String name,
        TemplateStructure templateStructure,
        Version version) {

    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Version(
            UUID id,
            JsonNode template,
            String commit,
            String versionNumber,
            JsonNode metadata) {
    }
}
