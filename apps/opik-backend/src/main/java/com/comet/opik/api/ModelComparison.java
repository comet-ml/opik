package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ModelComparison(
        @JsonView({ModelComparison.View.Public.class, ModelComparison.View.Write.class}) @NotBlank String name,
        @JsonView({ModelComparison.View.Public.class, ModelComparison.View.Write.class}) @NotBlank String description,
        @JsonView({ModelComparison.View.Public.class, ModelComparison.View.Write.class}) @NotNull List<String> modelIds,
        @JsonView({ModelComparison.View.Public.class, ModelComparison.View.Write.class}) @NotNull List<String> datasetNames,
        @JsonView({ModelComparison.View.Public.class, ModelComparison.View.Write.class}) Map<String, Object> filters,
        @JsonView({ModelComparison.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID id,
        @JsonView({ModelComparison.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView({ModelComparison.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,
        @JsonView({ModelComparison.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @JsonView({ModelComparison.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy,
        @JsonView({ModelComparison.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) ModelComparisonResults results) {

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ModelComparisonResults(
            @JsonView(ModelComparison.View.Public.class) List<ModelPerformance> modelPerformances,
            @JsonView(ModelComparison.View.Public.class) CostComparison costComparison,
            @JsonView(ModelComparison.View.Public.class) AccuracyComparison accuracyComparison,
            @JsonView(ModelComparison.View.Public.class) PerformanceComparison performanceComparison,
            @JsonView(ModelComparison.View.Public.class) List<DatasetComparison> datasetComparisons) {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ModelPerformance(
            @JsonView(ModelComparison.View.Public.class) String modelId,
            @JsonView(ModelComparison.View.Public.class) String modelName,
            @JsonView(ModelComparison.View.Public.class) String provider,
            @JsonView(ModelComparison.View.Public.class) Long totalTraces,
            @JsonView(ModelComparison.View.Public.class) Long totalSpans,
            @JsonView(ModelComparison.View.Public.class) BigDecimal totalCost,
            @JsonView(ModelComparison.View.Public.class) BigDecimal averageLatency,
            @JsonView(ModelComparison.View.Public.class) Double successRate,
            @JsonView(ModelComparison.View.Public.class) Map<String, BigDecimal> feedbackScores,
            @JsonView(ModelComparison.View.Public.class) TokenUsage tokenUsage) {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CostComparison(
            @JsonView(ModelComparison.View.Public.class) List<ModelCost> modelCosts,
            @JsonView(ModelComparison.View.Public.class) BigDecimal totalCostDifference,
            @JsonView(ModelComparison.View.Public.class) Double costEfficiencyRatio,
            @JsonView(ModelComparison.View.Public.class) String mostCostEffectiveModel) {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ModelCost(
            @JsonView(ModelComparison.View.Public.class) String modelId,
            @JsonView(ModelComparison.View.Public.class) String modelName,
            @JsonView(ModelComparison.View.Public.class) BigDecimal totalCost,
            @JsonView(ModelComparison.View.Public.class) BigDecimal costPerRequest,
            @JsonView(ModelComparison.View.Public.class) BigDecimal costPerToken,
            @JsonView(ModelComparison.View.Public.class) TokenUsage tokenUsage) {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AccuracyComparison(
            @JsonView(ModelComparison.View.Public.class) List<MetricComparison> metricComparisons,
            @JsonView(ModelComparison.View.Public.class) String bestPerformingModel,
            @JsonView(ModelComparison.View.Public.class) Map<String, Double> overallScores) {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MetricComparison(
            @JsonView(ModelComparison.View.Public.class) String metricName,
            @JsonView(ModelComparison.View.Public.class) String metricCategory,
            @JsonView(ModelComparison.View.Public.class) List<ModelMetricScore> modelScores,
            @JsonView(ModelComparison.View.Public.class) String bestModel,
            @JsonView(ModelComparison.View.Public.class) Double scoreDifference) {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ModelMetricScore(
            @JsonView(ModelComparison.View.Public.class) String modelId,
            @JsonView(ModelComparison.View.Public.class) String modelName,
            @JsonView(ModelComparison.View.Public.class) BigDecimal score,
            @JsonView(ModelComparison.View.Public.class) Long sampleSize,
            @JsonView(ModelComparison.View.Public.class) Double confidence) {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PerformanceComparison(
            @JsonView(ModelComparison.View.Public.class) List<ModelPerformanceMetrics> modelMetrics,
            @JsonView(ModelComparison.View.Public.class) String fastestModel,
            @JsonView(ModelComparison.View.Public.class) String mostReliableModel) {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ModelPerformanceMetrics(
            @JsonView(ModelComparison.View.Public.class) String modelId,
            @JsonView(ModelComparison.View.Public.class) String modelName,
            @JsonView(ModelComparison.View.Public.class) BigDecimal averageLatency,
            @JsonView(ModelComparison.View.Public.class) BigDecimal p95Latency,
            @JsonView(ModelComparison.View.Public.class) BigDecimal p99Latency,
            @JsonView(ModelComparison.View.Public.class) Double successRate,
            @JsonView(ModelComparison.View.Public.class) Double errorRate,
            @JsonView(ModelComparison.View.Public.class) Long totalRequests,
            @JsonView(ModelComparison.View.Public.class) Long failedRequests) {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DatasetComparison(
            @JsonView(ModelComparison.View.Public.class) String datasetName,
            @JsonView(ModelComparison.View.Public.class) List<ModelDatasetPerformance> modelPerformances,
            @JsonView(ModelComparison.View.Public.class) String bestModel,
            @JsonView(ModelComparison.View.Public.class) Long totalItems) {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ModelDatasetPerformance(
            @JsonView(ModelComparison.View.Public.class) String modelId,
            @JsonView(ModelComparison.View.Public.class) String modelName,
            @JsonView(ModelComparison.View.Public.class) Map<String, BigDecimal> feedbackScores,
            @JsonView(ModelComparison.View.Public.class) BigDecimal averageScore,
            @JsonView(ModelComparison.View.Public.class) Long itemsProcessed,
            @JsonView(ModelComparison.View.Public.class) BigDecimal totalCost) {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TokenUsage(
            @JsonView(ModelComparison.View.Public.class) Long totalTokens,
            @JsonView(ModelComparison.View.Public.class) Long inputTokens,
            @JsonView(ModelComparison.View.Public.class) Long outputTokens,
            @JsonView(ModelComparison.View.Public.class) BigDecimal averageTokensPerRequest) {
    }

    @Builder(toBuilder = true)
    public record ModelComparisonPage(
            @JsonView(ModelComparison.View.Public.class) int page,
            @JsonView(ModelComparison.View.Public.class) int size,
            @JsonView(ModelComparison.View.Public.class) long total,
            @JsonView(ModelComparison.View.Public.class) List<ModelComparison> content,
            @JsonView(ModelComparison.View.Public.class) List<String> sortableBy)
            implements Page<ModelComparison> {
        public static ModelComparison.ModelComparisonPage empty(int page, List<String> sortableBy) {
            return new ModelComparison.ModelComparisonPage(page, 0, 0, List.of(), sortableBy);
        }
    }

    public static class View {
        public static class Write {
        }

        public static class Public {
        }
    }
}