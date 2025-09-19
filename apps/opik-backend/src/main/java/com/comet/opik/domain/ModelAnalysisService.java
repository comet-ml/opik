package com.comet.opik.domain;

import com.comet.opik.api.ModelComparison;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Handle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @__({@Inject}))
@Slf4j
public class ModelAnalysisService {

    private final @NonNull TraceDao traceDao;
    private final @NonNull SpanDao spanDao;
    private final @NonNull FeedbackScoreDao feedbackScoreDao;
    private final @NonNull TransactionTemplate transactionTemplate;

    public ModelComparison.ModelComparisonResults analyzeModels(
            List<String> modelIds,
            List<String> datasetNames,
            Map<String, Object> filters
    ) {
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var traceRepository = handle.attach(TraceDao.class);
            var spanRepository = handle.attach(SpanDao.class);
            var feedbackRepository = handle.attach(FeedbackScoreDao.class);

            log.info("Analyzing models: '{}' with datasets: '{}'", modelIds, datasetNames);

            // Get traces and spans for the specified models and datasets
            var traces = traceRepository.findTracesForModelComparison(modelIds, datasetNames, filters).collectList().block();
            var spans = spanRepository.findSpansForModelComparison(modelIds, datasetNames, filters).collectList().block();
            var feedbackScores = feedbackRepository.findFeedbackScoresForModelComparison(modelIds, datasetNames, filters).block();

            // Handle null results
            if (traces == null) traces = List.of();
            if (spans == null) spans = List.of();
            if (feedbackScores == null) feedbackScores = List.of();

            // Analyze model performances
            var modelPerformances = analyzeModelPerformances(spans, feedbackScores);
            
            // Analyze costs
            var costComparison = analyzeCostComparison(spans);
            
            // Analyze accuracy
            var accuracyComparison = analyzeAccuracyComparison(feedbackScores);
            
            // Analyze performance metrics
            var performanceComparison = analyzePerformanceComparison(spans);
            
            // Analyze dataset-specific performance
            var datasetComparisons = analyzeDatasetComparisons(traces, spans, feedbackScores, datasetNames);

            return ModelComparison.ModelComparisonResults.builder()
                    .modelPerformances(modelPerformances)
                    .costComparison(costComparison)
                    .accuracyComparison(accuracyComparison)
                    .performanceComparison(performanceComparison)
                    .datasetComparisons(datasetComparisons)
                    .build();
        });
    }

    private List<ModelComparison.ModelPerformance> analyzeModelPerformances(
            List<Span> spans,
            List<FeedbackScore> feedbackScores
    ) {
        return spans.stream()
                .collect(Collectors.groupingBy(Span::getModelName))
                .entrySet()
                .stream()
                .map(entry -> {
                    String modelName = entry.getKey();
                    List<Span> modelSpans = entry.getValue();
                    
                    // Calculate metrics
                    long totalTraces = modelSpans.stream()
                            .map(Span::getTraceId)
                            .distinct()
                            .count();
                    
                    long totalSpans = modelSpans.size();
                    
                    BigDecimal totalCost = modelSpans.stream()
                            .map(span -> span.getUsage() != null ? span.getUsage().getTotalCost() : BigDecimal.ZERO)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    
                    BigDecimal averageLatency = calculateAverageLatency(modelSpans);
                    
                    double successRate = calculateSuccessRate(modelSpans);
                    
                    Map<String, BigDecimal> feedbackScoresMap = calculateAverageFeedbackScores(
                            modelSpans, feedbackScores
                    );
                    
                    ModelComparison.TokenUsage tokenUsage = calculateTokenUsage(modelSpans);
                    
                    return ModelComparison.ModelPerformance.builder()
                            .modelId(modelName)
                            .modelName(modelName)
                            .provider(extractProvider(modelName))
                            .totalTraces(totalTraces)
                            .totalSpans(totalSpans)
                            .totalCost(totalCost)
                            .averageLatency(averageLatency)
                            .successRate(successRate)
                            .feedbackScores(feedbackScoresMap)
                            .tokenUsage(tokenUsage)
                            .build();
                })
                .collect(Collectors.toList());
    }

    private ModelComparison.CostComparison analyzeCostComparison(List<Span> spans) {
        var modelCosts = spans.stream()
                .collect(Collectors.groupingBy(Span::getModelName))
                .entrySet()
                .stream()
                .map(entry -> {
                    String modelName = entry.getKey();
                    List<Span> modelSpans = entry.getValue();
                    
                    BigDecimal totalCost = modelSpans.stream()
                            .map(span -> span.getUsage() != null ? span.getUsage().getTotalCost() : BigDecimal.ZERO)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    
                    BigDecimal costPerRequest = modelSpans.isEmpty() ? BigDecimal.ZERO :
                            totalCost.divide(BigDecimal.valueOf(modelSpans.size()), 6, RoundingMode.HALF_UP);
                    
                    ModelComparison.TokenUsage tokenUsage = calculateTokenUsage(modelSpans);
                    BigDecimal costPerToken = tokenUsage.totalTokens() == 0 ? BigDecimal.ZERO :
                            totalCost.divide(BigDecimal.valueOf(tokenUsage.totalTokens()), 8, RoundingMode.HALF_UP);
                    
                    return ModelComparison.ModelCost.builder()
                            .modelId(modelName)
                            .modelName(modelName)
                            .totalCost(totalCost)
                            .costPerRequest(costPerRequest)
                            .costPerToken(costPerToken)
                            .tokenUsage(tokenUsage)
                            .build();
                })
                .collect(Collectors.toList());
        
        // Find most cost-effective model
        String mostCostEffectiveModel = modelCosts.stream()
                .min(Comparator.comparing(ModelComparison.ModelCost::costPerRequest))
                .map(ModelComparison.ModelCost::modelName)
                .orElse("N/A");
        
        // Calculate cost difference
        BigDecimal totalCostDifference = BigDecimal.ZERO;
        if (modelCosts.size() >= 2) {
            var sortedCosts = modelCosts.stream()
                    .sorted(Comparator.comparing(ModelComparison.ModelCost::totalCost))
                    .collect(Collectors.toList());
            totalCostDifference = sortedCosts.get(sortedCosts.size() - 1).totalCost()
                    .subtract(sortedCosts.get(0).totalCost());
        }
        
        return ModelComparison.CostComparison.builder()
                .modelCosts(modelCosts)
                .totalCostDifference(totalCostDifference)
                .costEfficiencyRatio(calculateCostEfficiencyRatio(modelCosts))
                .mostCostEffectiveModel(mostCostEffectiveModel)
                .build();
    }

    private ModelComparison.AccuracyComparison analyzeAccuracyComparison(List<FeedbackScore> feedbackScores) {
        var metricComparisons = feedbackScores.stream()
                .collect(Collectors.groupingBy(FeedbackScore::getName))
                .entrySet()
                .stream()
                .map(entry -> {
                    String metricName = entry.getKey();
                    List<FeedbackScore> scores = entry.getValue();
                    
                    var modelScores = scores.stream()
                            .collect(Collectors.groupingBy(score -> extractModelFromSpanId(score.getSpanId())))
                            .entrySet()
                            .stream()
                            .map(modelEntry -> {
                                String modelId = modelEntry.getKey();
                                List<FeedbackScore> modelScoresList = modelEntry.getValue();
                                
                                BigDecimal averageScore = modelScoresList.stream()
                                        .map(FeedbackScore::getValue)
                                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                                        .divide(BigDecimal.valueOf(modelScoresList.size()), 4, RoundingMode.HALF_UP);
                                
                                return ModelComparison.ModelMetricScore.builder()
                                        .modelId(modelId)
                                        .modelName(modelId)
                                        .score(averageScore)
                                        .sampleSize((long) modelScoresList.size())
                                        .confidence(calculateConfidence(modelScoresList.size()))
                                        .build();
                            })
                            .collect(Collectors.toList());
                    
                    String bestModel = modelScores.stream()
                            .max(Comparator.comparing(ModelComparison.ModelMetricScore::score))
                            .map(ModelComparison.ModelMetricScore::modelName)
                            .orElse("N/A");
                    
                    Double scoreDifference = modelScores.size() >= 2 ? 
                            calculateScoreDifference(modelScores) : 0.0;
                    
                    return ModelComparison.MetricComparison.builder()
                            .metricName(metricName)
                            .metricCategory(extractMetricCategory(metricName))
                            .modelScores(modelScores)
                            .bestModel(bestModel)
                            .scoreDifference(scoreDifference)
                            .build();
                })
                .collect(Collectors.toList());
        
        String bestPerformingModel = findBestPerformingModel(metricComparisons);
        Map<String, Double> overallScores = calculateOverallScores(metricComparisons);
        
        return ModelComparison.AccuracyComparison.builder()
                .metricComparisons(metricComparisons)
                .bestPerformingModel(bestPerformingModel)
                .overallScores(overallScores)
                .build();
    }

    private ModelComparison.PerformanceComparison analyzePerformanceComparison(List<Span> spans) {
        var modelMetrics = spans.stream()
                .collect(Collectors.groupingBy(Span::getModelName))
                .entrySet()
                .stream()
                .map(entry -> {
                    String modelName = entry.getKey();
                    List<Span> modelSpans = entry.getValue();
                    
                    List<BigDecimal> latencies = modelSpans.stream()
                            .map(this::calculateLatency)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());
                    
                    BigDecimal averageLatency = latencies.isEmpty() ? BigDecimal.ZERO :
                            latencies.stream()
                                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                                    .divide(BigDecimal.valueOf(latencies.size()), 2, RoundingMode.HALF_UP);
                    
                    BigDecimal p95Latency = calculatePercentile(latencies, 95);
                    BigDecimal p99Latency = calculatePercentile(latencies, 99);
                    
                    long totalRequests = modelSpans.size();
                    long failedRequests = modelSpans.stream()
                            .mapToLong(span -> span.getStatus() != null && span.getStatus().contains("error") ? 1 : 0)
                            .sum();
                    
                    double successRate = totalRequests == 0 ? 0.0 : 
                            (double) (totalRequests - failedRequests) / totalRequests;
                    double errorRate = totalRequests == 0 ? 0.0 : 
                            (double) failedRequests / totalRequests;
                    
                    return ModelComparison.ModelPerformanceMetrics.builder()
                            .modelId(modelName)
                            .modelName(modelName)
                            .averageLatency(averageLatency)
                            .p95Latency(p95Latency)
                            .p99Latency(p99Latency)
                            .successRate(successRate)
                            .errorRate(errorRate)
                            .totalRequests(totalRequests)
                            .failedRequests(failedRequests)
                            .build();
                })
                .collect(Collectors.toList());
        
        String fastestModel = modelMetrics.stream()
                .min(Comparator.comparing(ModelComparison.ModelPerformanceMetrics::averageLatency))
                .map(ModelComparison.ModelPerformanceMetrics::modelName)
                .orElse("N/A");
        
        String mostReliableModel = modelMetrics.stream()
                .max(Comparator.comparing(ModelComparison.ModelPerformanceMetrics::successRate))
                .map(ModelComparison.ModelPerformanceMetrics::modelName)
                .orElse("N/A");
        
        return ModelComparison.PerformanceComparison.builder()
                .modelMetrics(modelMetrics)
                .fastestModel(fastestModel)
                .mostReliableModel(mostReliableModel)
                .build();
    }

    private List<ModelComparison.DatasetComparison> analyzeDatasetComparisons(
            List<Trace> traces,
            List<Span> spans,
            List<FeedbackScore> feedbackScores,
            List<String> datasetNames
    ) {
        return datasetNames.stream()
                .map(datasetName -> {
                    // Filter data for this dataset
                    var datasetTraces = traces.stream()
                            .filter(trace -> datasetName.equals(trace.getDatasetName()))
                            .collect(Collectors.toList());
                    
                    var datasetSpans = spans.stream()
                            .filter(span -> datasetTraces.stream()
                                    .anyMatch(trace -> trace.getId().equals(span.getTraceId())))
                            .collect(Collectors.toList());
                    
                    var datasetFeedbackScores = feedbackScores.stream()
                            .filter(score -> datasetSpans.stream()
                                    .anyMatch(span -> span.getId().equals(score.getSpanId())))
                            .collect(Collectors.toList());
                    
                    var modelPerformances = datasetSpans.stream()
                            .collect(Collectors.groupingBy(Span::getModelName))
                            .entrySet()
                            .stream()
                            .map(entry -> {
                                String modelName = entry.getKey();
                                List<Span> modelSpans = entry.getValue();
                                
                                Map<String, BigDecimal> feedbackScoresMap = calculateAverageFeedbackScores(
                                        modelSpans, datasetFeedbackScores
                                );
                                
                                BigDecimal averageScore = feedbackScoresMap.values().stream()
                                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                                        .divide(BigDecimal.valueOf(feedbackScoresMap.size()), 4, RoundingMode.HALF_UP);
                                
                                BigDecimal totalCost = modelSpans.stream()
                                        .map(span -> span.getUsage() != null ? span.getUsage().getTotalCost() : BigDecimal.ZERO)
                                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                                
                                return ModelComparison.ModelDatasetPerformance.builder()
                                        .modelId(modelName)
                                        .modelName(modelName)
                                        .feedbackScores(feedbackScoresMap)
                                        .averageScore(averageScore)
                                        .itemsProcessed((long) modelSpans.size())
                                        .totalCost(totalCost)
                                        .build();
                            })
                            .collect(Collectors.toList());
                    
                    String bestModel = modelPerformances.stream()
                            .max(Comparator.comparing(ModelComparison.ModelDatasetPerformance::averageScore))
                            .map(ModelComparison.ModelDatasetPerformance::modelName)
                            .orElse("N/A");
                    
                    return ModelComparison.DatasetComparison.builder()
                            .datasetName(datasetName)
                            .modelPerformances(modelPerformances)
                            .bestModel(bestModel)
                            .totalItems((long) datasetTraces.size())
                            .build();
                })
                .collect(Collectors.toList());
    }

    // Helper methods
    private BigDecimal calculateAverageLatency(List<Span> spans) {
        var latencies = spans.stream()
                .map(this::calculateLatency)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        
        if (latencies.isEmpty()) {
            return BigDecimal.ZERO;
        }
        
        return latencies.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(latencies.size()), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateLatency(Span span) {
        if (span.getStartTime() != null && span.getEndTime() != null) {
            return BigDecimal.valueOf(
                    span.getEndTime().toEpochMilli() - span.getStartTime().toEpochMilli()
            );
        }
        return null;
    }

    private double calculateSuccessRate(List<Span> spans) {
        if (spans.isEmpty()) {
            return 0.0;
        }
        
        long successfulSpans = spans.stream()
                .mapToLong(span -> span.getStatus() == null || !span.getStatus().contains("error") ? 1 : 0)
                .sum();
        
        return (double) successfulSpans / spans.size();
    }

    private Map<String, BigDecimal> calculateAverageFeedbackScores(
            List<Span> spans,
            List<FeedbackScore> feedbackScores
    ) {
        return feedbackScores.stream()
                .filter(score -> spans.stream().anyMatch(span -> span.getId().equals(score.getSpanId())))
                .collect(Collectors.groupingBy(FeedbackScore::getName))
                .entrySet()
                .stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().stream()
                                .map(FeedbackScore::getValue)
                                .reduce(BigDecimal.ZERO, BigDecimal::add)
                                .divide(BigDecimal.valueOf(entry.getValue().size()), 4, RoundingMode.HALF_UP)
                ));
    }

    private ModelComparison.TokenUsage calculateTokenUsage(List<Span> spans) {
        long totalTokens = spans.stream()
                .mapToLong(span -> span.getUsage() != null ? span.getUsage().getTotalTokens() : 0)
                .sum();
        
        long inputTokens = spans.stream()
                .mapToLong(span -> span.getUsage() != null ? span.getUsage().getInputTokens() : 0)
                .sum();
        
        long outputTokens = spans.stream()
                .mapToLong(span -> span.getUsage() != null ? span.getUsage().getOutputTokens() : 0)
                .sum();
        
        BigDecimal averageTokensPerRequest = spans.isEmpty() ? BigDecimal.ZERO :
                BigDecimal.valueOf(totalTokens).divide(BigDecimal.valueOf(spans.size()), 2, RoundingMode.HALF_UP);
        
        return ModelComparison.TokenUsage.builder()
                .totalTokens(totalTokens)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .averageTokensPerRequest(averageTokensPerRequest)
                .build();
    }

    private String extractProvider(String modelName) {
        // Extract provider from model name (e.g., "gpt-4" -> "openai")
        if (modelName.contains("gpt")) return "openai";
        if (modelName.contains("claude")) return "anthropic";
        if (modelName.contains("gemini")) return "google";
        return "unknown";
    }

    private String extractModelFromSpanId(String spanId) {
        // This would need to be implemented based on how span IDs are structured
        // For now, return a placeholder
        return "model-" + spanId;
    }

    private String extractMetricCategory(String metricName) {
        // Categorize metrics based on their names
        if (metricName.toLowerCase().contains("relevance")) return "Quality";
        if (metricName.toLowerCase().contains("accuracy")) return "Accuracy";
        if (metricName.toLowerCase().contains("cost")) return "Cost";
        return "Other";
    }

    private Double calculateConfidence(int sampleSize) {
        // Simple confidence calculation based on sample size
        return Math.min(1.0, sampleSize / 100.0);
    }

    private Double calculateScoreDifference(List<ModelComparison.ModelMetricScore> modelScores) {
        if (modelScores.size() < 2) return 0.0;
        
        var sortedScores = modelScores.stream()
                .sorted(Comparator.comparing(ModelComparison.ModelMetricScore::score))
                .collect(Collectors.toList());
        
        return sortedScores.get(sortedScores.size() - 1).score()
                .subtract(sortedScores.get(0).score())
                .doubleValue();
    }

    private String findBestPerformingModel(List<ModelComparison.MetricComparison> metricComparisons) {
        return metricComparisons.stream()
                .collect(Collectors.groupingBy(ModelComparison.MetricComparison::bestModel))
                .entrySet()
                .stream()
                .max(Comparator.comparing(entry -> entry.getValue().size()))
                .map(Map.Entry::getKey)
                .orElse("N/A");
    }

    private Map<String, Double> calculateOverallScores(List<ModelComparison.MetricComparison> metricComparisons) {
        return metricComparisons.stream()
                .flatMap(comparison -> comparison.modelScores().stream())
                .collect(Collectors.groupingBy(ModelComparison.ModelMetricScore::modelName))
                .entrySet()
                .stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().stream()
                                .mapToDouble(score -> score.score().doubleValue())
                                .average()
                                .orElse(0.0)
                ));
    }

    private BigDecimal calculatePercentile(List<BigDecimal> values, int percentile) {
        if (values.isEmpty()) return BigDecimal.ZERO;
        
        var sorted = values.stream().sorted().collect(Collectors.toList());
        int index = (int) Math.ceil((percentile / 100.0) * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private Double calculateCostEfficiencyRatio(List<ModelComparison.ModelCost> modelCosts) {
        if (modelCosts.size() < 2) return 0.0;
        
        var sortedCosts = modelCosts.stream()
                .sorted(Comparator.comparing(ModelComparison.ModelCost::costPerRequest))
                .collect(Collectors.toList());
        
        BigDecimal lowestCost = sortedCosts.get(0).costPerRequest();
        BigDecimal highestCost = sortedCosts.get(sortedCosts.size() - 1).costPerRequest();
        
        if (lowestCost.equals(BigDecimal.ZERO)) return 0.0;
        
        return highestCost.divide(lowestCost, 4, RoundingMode.HALF_UP).doubleValue();
    }

    public Map<String, Object> exportResults(ModelComparison comparison, String format) {
        // Export results in the specified format
        Map<String, Object> exportData = new HashMap<>();
        exportData.put("comparison", comparison);
        exportData.put("format", format);
        exportData.put("exportedAt", java.time.Instant.now());
        
        return exportData;
    }
}