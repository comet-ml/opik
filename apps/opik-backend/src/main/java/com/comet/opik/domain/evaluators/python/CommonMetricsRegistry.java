package com.comet.opik.domain.evaluators.python;

import com.comet.opik.api.evaluators.CommonMetric;
import com.comet.opik.api.evaluators.CommonMetric.InitParameter;
import com.comet.opik.api.evaluators.CommonMetric.ScoreParameter;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Registry of common metrics available for online evaluation.
 * These are simple heuristic metrics from the Python SDK that don't require LLM calls.
 * 
 * For now, this is a static list. In the future, we may dynamically parse these from the SDK.
 */
@Slf4j
@Singleton
public class CommonMetricsRegistry {

    private static final List<CommonMetric> COMMON_METRICS = List.of(
            // Equals metric
            CommonMetric.builder()
                    .id("equals")
                    .name("Equals")
                    .description("A metric that checks if an output string exactly matches a reference string. " +
                            "Returns 1.0 if the strings match exactly, and 0.0 otherwise.")
                    .scoreParameters(List.of(
                            ScoreParameter.builder()
                                    .name("output")
                                    .type("str")
                                    .description("The output string to check.")
                                    .required(true)
                                    .build(),
                            ScoreParameter.builder()
                                    .name("reference")
                                    .type("str")
                                    .description("The reference string to compare against.")
                                    .required(true)
                                    .build()))
                    .initParameters(List.of(
                            InitParameter.builder()
                                    .name("case_sensitive")
                                    .type("bool")
                                    .description("Whether the comparison should be case-sensitive.")
                                    .defaultValue("False")
                                    .required(false)
                                    .build()))
                    .build(),

            // Contains metric
            CommonMetric.builder()
                    .id("contains")
                    .name("Contains")
                    .description("A metric that checks if a reference string is contained within an output string. " +
                            "Returns 1.0 if the reference is found in the output, 0.0 otherwise.")
                    .scoreParameters(List.of(
                            ScoreParameter.builder()
                                    .name("output")
                                    .type("str")
                                    .description("The output string to check.")
                                    .required(true)
                                    .build(),
                            ScoreParameter.builder()
                                    .name("reference")
                                    .type("str")
                                    .description("The reference string to look for in the output. " +
                                            "If not provided, falls back to the default reference set at initialization.")
                                    .required(false)
                                    .build()))
                    .initParameters(List.of(
                            InitParameter.builder()
                                    .name("case_sensitive")
                                    .type("bool")
                                    .description("Whether the comparison should be case-sensitive.")
                                    .defaultValue("False")
                                    .required(false)
                                    .build(),
                            InitParameter.builder()
                                    .name("reference")
                                    .type("str")
                                    .description("Optional default reference string. If provided, it will be used " +
                                            "unless a reference is explicitly passed to score().")
                                    .defaultValue(null)
                                    .required(false)
                                    .build()))
                    .build(),

            // LevenshteinRatio metric
            CommonMetric.builder()
                    .id("levenshtein_ratio")
                    .name("LevenshteinRatio")
                    .description("A metric that calculates the Levenshtein ratio between two strings. " +
                            "Returns a score between 0.0 and 1.0, where 1.0 indicates identical strings.")
                    .scoreParameters(List.of(
                            ScoreParameter.builder()
                                    .name("output")
                                    .type("str")
                                    .description("The output string to compare.")
                                    .required(true)
                                    .build(),
                            ScoreParameter.builder()
                                    .name("reference")
                                    .type("str")
                                    .description("The reference string to compare against.")
                                    .required(true)
                                    .build()))
                    .initParameters(List.of(
                            InitParameter.builder()
                                    .name("case_sensitive")
                                    .type("bool")
                                    .description("Whether the comparison should be case-sensitive.")
                                    .defaultValue("False")
                                    .required(false)
                                    .build()))
                    .build(),

            // RegexMatch metric
            CommonMetric.builder()
                    .id("regex_match")
                    .name("RegexMatch")
                    .description("A metric that checks if an output string matches a given regular expression pattern. " +
                            "Returns 1.0 if the output matches the pattern, 0.0 otherwise.")
                    .scoreParameters(List.of(
                            ScoreParameter.builder()
                                    .name("output")
                                    .type("str")
                                    .description("The output string to check against the regex pattern.")
                                    .required(true)
                                    .build()))
                    .initParameters(List.of(
                            InitParameter.builder()
                                    .name("regex")
                                    .type("str")
                                    .description("The regular expression pattern to match against.")
                                    .defaultValue(null)
                                    .required(true)
                                    .build()))
                    .build(),

            // IsJson metric
            CommonMetric.builder()
                    .id("is_json")
                    .name("IsJson")
                    .description("A metric that checks if a given output string is valid JSON. " +
                            "Returns 1.0 if the output can be parsed as JSON, 0.0 otherwise.")
                    .scoreParameters(List.of(
                            ScoreParameter.builder()
                                    .name("output")
                                    .type("str")
                                    .description("The output string to check for JSON validity.")
                                    .required(true)
                                    .build()))
                    .initParameters(List.of())
                    .build());

    /**
     * Returns all available common metrics.
     */
    public CommonMetric.CommonMetricList getAll() {
        log.debug("Returning '{}' common metrics", COMMON_METRICS.size());
        return CommonMetric.CommonMetricList.builder()
                .content(COMMON_METRICS)
                .build();
    }

    /**
     * Finds a metric by its ID.
     */
    public CommonMetric findById(@NonNull String id) {
        return COMMON_METRICS.stream()
                .filter(m -> m.id().equals(id))
                .findFirst()
                .orElse(null);
    }
}
