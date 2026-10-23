package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.annotation.Nullable;
import lombok.Builder;

import java.util.List;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LlmModelDefinition(
        String id,
        String qualifiedName,
        String label,
        boolean structuredOutput,
        boolean reasoning,
        @Nullable List<String> supportedParameters,
        @Nullable List<String> reasoningEfforts,
        ModelParams params,
        @Nullable String retiresOn) {
}
