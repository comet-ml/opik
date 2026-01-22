package com.comet.opik.api.evaluators;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.List;

/**
 * Represents a pre-defined common metric from the Python SDK heuristics.
 * These metrics can be selected by users when creating Online Evaluation rules
 * instead of writing custom Python code.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "A pre-defined common metric from the Python SDK")
public record CommonMetric(
        @Schema(description = "Unique identifier for the metric (lowercase class name)", example = "equals") String id,

        @Schema(description = "Display name of the metric (class name)", example = "Equals") String name,

        @Schema(description = "Description of the metric extracted from the docstring") String description,

        @Schema(description = "Parameters for the score() method - these are mapped to trace/span fields") List<ScoreParameter> scoreParameters,

        @Schema(description = "Configuration parameters from __init__() - these are static values set by the user") List<InitParameter> initParameters) {

    /**
     * Represents a parameter of the score() method.
     * These parameters are mapped to trace/span fields at runtime.
     */
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(description = "A parameter of the metric's score() method")
    public record ScoreParameter(
            @Schema(description = "Parameter name", example = "output") String name,

            @Schema(description = "Parameter type annotation", example = "str") String type,

            @Schema(description = "Parameter description from docstring") String description,

            @Schema(description = "Whether the parameter is required") boolean required) {
    }

    /**
     * Represents a configuration parameter from the __init__() method.
     * These are static values that configure the metric behavior.
     */
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(description = "A configuration parameter from the metric's __init__() method")
    public record InitParameter(
            @Schema(description = "Parameter name", example = "case_sensitive") String name,

            @Schema(description = "Parameter type annotation", example = "bool") String type,

            @Schema(description = "Parameter description from docstring") String description,

            @Schema(description = "Default value as string", example = "False") String defaultValue,

            @Schema(description = "Whether the parameter is required (has no default)") boolean required) {
    }

    /**
     * Response wrapper for the list of common metrics.
     */
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Schema(description = "List of available common metrics")
    public record CommonMetricList(
            @Schema(description = "List of common metrics") List<CommonMetric> content) {
    }
}
