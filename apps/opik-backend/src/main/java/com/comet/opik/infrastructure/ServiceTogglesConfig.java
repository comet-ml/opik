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
}
