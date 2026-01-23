package com.comet.opik.domain.evaluators.python;

import com.comet.opik.api.evaluators.CommonMetric;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.RetriableHttpClient;
import com.comet.opik.utils.RetryUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Registry of common metrics available for online evaluation.
 * These are heuristic metrics from the Python SDK that don't require LLM calls.
 *
 * Metrics are dynamically fetched from the Python backend on first access and cached.
 * If the Python backend is unavailable, an empty list is returned.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class CommonMetricsRegistry {

    private static final String COMMON_METRICS_URL_TEMPLATE = "%s/v1/private/evaluators/common-metrics";

    private final @NonNull RetriableHttpClient client;
    private final @NonNull OpikConfiguration config;

    private volatile List<CommonMetric> cachedMetrics;
    private volatile boolean initialized = false;

    /**
     * Ensures the metrics are loaded, fetching from Python backend if not already cached.
     * Uses double-checked locking for thread safety.
     */
    private void ensureInitialized() {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    log.info("Lazily initializing CommonMetricsRegistry - fetching metrics from Python backend");
                    try {
                        this.cachedMetrics = fetchMetricsFromPythonBackend();
                        log.info("Successfully fetched '{}' common metrics from Python backend", cachedMetrics.size());
                    } catch (Exception e) {
                        log.warn("Failed to fetch metrics from Python backend, returning empty list", e);
                        this.cachedMetrics = List.of();
                    }
                    initialized = true;
                }
            }
        }
    }

    /**
     * Fetches the list of common metrics from the Python backend.
     *
     * @return List of common metrics
     */
    private List<CommonMetric> fetchMetricsFromPythonBackend() {
        String url = COMMON_METRICS_URL_TEMPLATE.formatted(config.getPythonEvaluator().getUrl());
        log.debug("Fetching common metrics from '{}'", url);

        return RetriableHttpClient.newGet(c -> c.target(url))
                .withRetryPolicy(RetryUtils.handleHttpErrors(
                        config.getPythonEvaluator().getMaxRetryAttempts(),
                        config.getPythonEvaluator().getMinRetryDelay().toJavaDuration(),
                        config.getPythonEvaluator().getMaxRetryDelay().toJavaDuration()))
                .withResponse(this::processResponse)
                .execute(client);
    }

    /**
     * Processes the HTTP response from the Python backend.
     *
     * @param response The HTTP response
     * @return List of common metrics
     */
    private List<CommonMetric> processResponse(Response response) {
        int statusCode = response.getStatus();
        Response.StatusType statusInfo = response.getStatusInfo();

        if (statusInfo.getFamily() == Response.Status.Family.SUCCESSFUL) {
            CommonMetric.CommonMetricList metricList = response.readEntity(CommonMetric.CommonMetricList.class);
            return metricList.content() != null ? metricList.content() : List.of();
        }

        String errorMessage = "Unknown error";
        if (response.hasEntity() && response.bufferEntity()) {
            try {
                errorMessage = response.readEntity(String.class);
            } catch (RuntimeException e) {
                log.warn("Failed to parse error response", e);
            }
        }

        throw new RuntimeException(
                "Failed to fetch common metrics from Python backend (HTTP " + statusCode + "): " + errorMessage);
    }

    /**
     * Returns all available common metrics.
     * Fetches from Python backend on first call and caches the result.
     */
    public CommonMetric.CommonMetricList getAll() {
        ensureInitialized();
        log.debug("Returning '{}' common metrics", cachedMetrics != null ? cachedMetrics.size() : 0);
        return CommonMetric.CommonMetricList.builder()
                .content(cachedMetrics != null ? cachedMetrics : List.of())
                .build();
    }

    /**
     * Finds a metric by its ID.
     * Fetches from Python backend on first call and caches the result.
     */
    public CommonMetric findById(@NonNull String id) {
        ensureInitialized();
        if (cachedMetrics == null) {
            return null;
        }
        return cachedMetrics.stream()
                .filter(m -> m.id().equals(id))
                .findFirst()
                .orElse(null);
    }
}
