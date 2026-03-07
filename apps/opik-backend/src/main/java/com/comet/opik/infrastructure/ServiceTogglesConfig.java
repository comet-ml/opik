package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ServiceTogglesConfig {
    @Valid @JsonProperty
    @NotNull boolean pythonEvaluatorEnabled;
    @JsonProperty
    @NotNull boolean traceThreadPythonEvaluatorEnabled;
    @JsonProperty
    @NotNull boolean spanLlmAsJudgeEnabled;
    @JsonProperty
    @NotNull boolean spanUserDefinedMetricPythonEnabled;
    @JsonProperty
    @NotNull boolean guardrailsEnabled;
    @JsonProperty
    @NotNull boolean opikAIEnabled;
    @JsonProperty
    @NotNull boolean alertsEnabled;
    @JsonProperty
    @NotNull boolean welcomeWizardEnabled;
    @JsonProperty
    @NotNull boolean csvUploadEnabled;
    @JsonProperty
    @NotNull boolean exportEnabled;
    @JsonProperty
    @NotNull boolean optimizationStudioEnabled;
    @JsonProperty
    @NotNull boolean datasetVersioningEnabled;
    @JsonProperty
    @NotNull boolean datasetExportEnabled;
    // LLM Provider feature flags
    @JsonProperty
    @NotNull boolean openaiProviderEnabled;
    @JsonProperty
    @NotNull boolean anthropicProviderEnabled;
    @JsonProperty
    @NotNull boolean geminiProviderEnabled;
    @JsonProperty
    @NotNull boolean openrouterProviderEnabled;
    @JsonProperty
    @NotNull boolean vertexaiProviderEnabled;
    @JsonProperty
    @NotNull boolean bedrockProviderEnabled;
    @JsonProperty
    @NotNull boolean customllmProviderEnabled;
    @JsonProperty
    @NotNull boolean ollamaProviderEnabled;
    @JsonProperty
    @NotNull boolean collaboratorsTabEnabled;
    @JsonProperty
    @NotNull boolean onlineEvaluationOptionalVariableMappingEnabled;
}
