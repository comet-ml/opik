package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

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
    @NotNull boolean exportEnabled;
    @JsonProperty
    @NotNull boolean costIntelligenceEnabled;
    @JsonProperty
    @NotNull boolean datasetVersioningEnabled;
    @JsonProperty
    @NotNull boolean datasetExportEnabled;
    @JsonProperty
    @NotNull boolean demoDataEnabled;
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
    @NotNull boolean ollieEnabled;
    @JsonProperty
    @NotNull boolean projectHomepageEnabled;
    @JsonProperty
    @NotNull boolean onlineScoringTracingEnabled;

    /**
     * Workspaces allowed to use Custom Charts, comma-separated. Empty (the default) disables the feature everywhere.
     * Membership also routes the workspace's free-form SQL to the wider Custom Charts ClickHouse account, so its
     * Agent Insights queries run under that account too — intended while the allowlist is internal-only, and the
     * reason this is an allowlist rather than a plain boolean.
     *
     * <p>Held as a String because Dropwizard substitutes env vars as scalars, so a comma-separated value cannot bind
     * to a collection; {@link #getCustomChartsEnabledWorkspaces()} splits, strips and drops blanks.
     */
    @JsonProperty
    @NotNull String customChartsEnabledWorkspaces = "";

    /** Derived: the parsed, stripped, blank-free set of allowlisted workspace ids. */
    public Set<String> getCustomChartsEnabledWorkspaces() {
        return Arrays.stream(customChartsEnabledWorkspaces.split(","))
                .map(String::strip)
                .filter(workspaceId -> !workspaceId.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    @JsonProperty
    @Min(5) @Max(100) int defaultPageSize;
}
