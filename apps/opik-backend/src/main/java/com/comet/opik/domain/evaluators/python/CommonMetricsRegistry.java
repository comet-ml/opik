package com.comet.opik.domain.evaluators.python;

import com.comet.opik.api.evaluators.CommonMetric;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Registry of common metrics parsed from Python SDK heuristic files.
 * This registry is loaded at application startup and provides access to
 * pre-defined metrics that users can select for Online Evaluation rules.
 */
@Slf4j
public class CommonMetricsRegistry {

    private static final String HEURISTICS_RESOURCE_PATH = "/python-metrics/heuristics/";

    /**
     * List of heuristic metric files to parse.
     * These files are bundled as resources in the backend JAR.
     */
    private static final List<String> HEURISTIC_FILES = List.of(
            "equals.py",
            "contains.py",
            "is_json.py",
            "regex_match.py",
            "levenshtein_ratio.py",
            "bleu.py",
            "gleu.py",
            "chrf.py",
            "meteor.py",
            "rouge.py",
            "sentiment.py",
            "vader_sentiment.py",
            "tone.py",
            "readability.py",
            "bertscore.py",
            "spearman.py",
            "prompt_injection.py",
            "language_adherence.py",
            "distribution_metrics.py");

    @Getter
    private final List<CommonMetric> metrics;

    /**
     * Creates a new CommonMetricsRegistry by parsing all heuristic files.
     */
    public CommonMetricsRegistry() {
        this.metrics = Collections.unmodifiableList(loadMetrics());
        log.info("Loaded '{}' common metrics from Python SDK heuristics", this.metrics.size());
    }

    /**
     * Returns all available common metrics.
     */
    public CommonMetric.CommonMetricList getAll() {
        return CommonMetric.CommonMetricList.builder()
                .content(metrics)
                .build();
    }

    /**
     * Finds a metric by its ID.
     */
    public CommonMetric findById(@NonNull String id) {
        return metrics.stream()
                .filter(m -> m.id().equals(id))
                .findFirst()
                .orElse(null);
    }

    /**
     * Loads all metrics from the bundled Python files.
     */
    private List<CommonMetric> loadMetrics() {
        List<CommonMetric> allMetrics = new ArrayList<>();

        for (String fileName : HEURISTIC_FILES) {
            try {
                String resourcePath = HEURISTICS_RESOURCE_PATH + fileName;
                String pythonCode = loadResource(resourcePath);

                if (pythonCode != null && !pythonCode.isEmpty()) {
                    List<CommonMetric> parsedMetrics = PythonMetricParser.parse(pythonCode, fileName);

                    // Filter to only include metrics valid for online evaluation
                    List<CommonMetric> validMetrics = parsedMetrics.stream()
                            .filter(PythonMetricParser::isValidForOnlineEvaluation)
                            .toList();

                    allMetrics.addAll(validMetrics);
                    log.debug("Loaded '{}' metrics from '{}'", validMetrics.size(), fileName);
                } else {
                    log.warn("Empty or missing resource: '{}'", resourcePath);
                }
            } catch (Exception e) {
                log.error("Failed to load metrics from '{}': '{}'", fileName, e.getMessage());
            }
        }

        // Sort metrics alphabetically by name for consistent ordering
        allMetrics.sort(Comparator.comparing(CommonMetric::name));

        return allMetrics;
    }

    /**
     * Loads a resource file as a string.
     */
    private String loadResource(String resourcePath) {
        try (InputStream inputStream = getClass().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                log.warn("Resource not found: '{}'", resourcePath);
                return null;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (IOException e) {
            log.error("Error reading resource '{}': '{}'", resourcePath, e.getMessage());
            return null;
        }
    }
}
