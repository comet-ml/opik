package com.comet.opik.api.resources.utils;

import com.comet.opik.api.ErrorInfo;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.GuardrailsValidation;
import com.comet.opik.api.PercentageValues;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.ProjectStats.ProjectStatItem;
import com.comet.opik.api.ProjectStats.SingleValueStat;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.domain.GuardrailResult;
import com.comet.opik.domain.cost.CostService;
import com.comet.opik.domain.stats.StatsMapper;
import com.comet.opik.utils.ValidationUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.math.Quantiles;
import org.apache.commons.collections4.CollectionUtils;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
import org.junit.platform.commons.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.ProjectStats.AvgValueStat;
import static com.comet.opik.api.ProjectStats.CountValueStat;
import static com.comet.opik.api.ProjectStats.PercentageValueStat;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

public class StatsUtils {

    private static final double TOLERANCE = 0.00009;

    public static List<BigDecimal> calculateQuantiles(List<Double> data, List<Double> quantiles) {
        if (data.isEmpty()) {
            return new ArrayList<>();
        }

        List<Integer> percentiles = quantiles.stream()
                .map(q -> (int) (q * 100))
                .toList();

        Quantiles.Scale scaleAndIndex = Quantiles.scale(100);
        Map<Integer, Double> quantileResult = scaleAndIndex.indexes(percentiles).compute(data);

        return percentiles.stream()
                .map(quantileResult::get)
                .map(BigDecimal::valueOf)
                .toList();
    }

    public static List<ProjectStatItem<?>> getProjectTraceStatItems(List<Trace> expectedTraces) {
        var stats = getProjectStatItems(expectedTraces,
                expectedTraces.stream().map(Trace::usage).toList(),
                expectedTraces.stream().map(Trace::feedbackScores).toList(),
                expectedTraces.stream().map(Trace::spanFeedbackScores).toList(),
                Trace::input,
                Trace::output,
                Trace::metadata,
                Trace::tags,
                Trace::startTime,
                Trace::endTime,
                Trace::totalEstimatedCost,
                Trace::llmSpanCount,
                Trace::spanCount,
                Trace::guardrailsValidations,
                Trace::errorInfo,
                "trace_count");

        if (CollectionUtils.isNotEmpty(expectedTraces)) {
            stats.add(new CountValueStat(StatsMapper.THREAD_COUNT, calculateExpectedThreadCount(expectedTraces)));
        }

        return stats;
    }

    public static List<ProjectStatItem<?>> getProjectSpanStatItems(List<Span> expectedSpans) {
        List<Map<String, Long>> list = expectedSpans.stream().map(Span::usage)
                .filter(Objects::nonNull)
                .map(value -> value.entrySet().stream()
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                entry -> entry.getValue().longValue())))
                .toList();

        return getProjectStatItems(expectedSpans,
                list,
                expectedSpans.stream().map(Span::feedbackScores).toList(),
                null, // Spans don't have span feedback scores
                Span::input,
                Span::output,
                Span::metadata,
                Span::tags,
                Span::startTime,
                Span::endTime,
                span -> {
                    String model = StringUtils.isNotBlank(span.model())
                            ? span.model()
                            : Optional.ofNullable(span.metadata())
                                    .map(metadata -> metadata.get("model"))
                                    .map(JsonNode::asText).orElse(null);

                    if (model != null) {
                        Map<String, Integer> usage = Optional.ofNullable(span.usage())
                                .orElse(Map.of())
                                .entrySet()
                                .stream()
                                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().intValue()));

                        return CostService.calculateCost(span.model(), span.provider(), usage, null);
                    }

                    return BigDecimal.ZERO;
                },
                null,
                null,
                null,
                Span::errorInfo,
                "span_count");
    }

    private static long calculateExpectedThreadCount(List<Trace> traces) {
        return traces.stream()
                .map(Trace::threadId)
                .filter(Objects::nonNull)
                .filter(threadId -> !threadId.isEmpty())
                .distinct()
                .count();
    }

    private static <T> List<ProjectStatItem<?>> getProjectStatItems(
            List<T> expectedEntities,
            List<Map<String, Long>> usages,
            List<List<FeedbackScore>> feedbacks,
            List<List<FeedbackScore>> spanFeedbacks,
            Function<T, JsonNode> inputProvider,
            Function<T, JsonNode> outputProvider,
            Function<T, JsonNode> metadataProvider,
            Function<T, Set<String>> tagsProvider,
            Function<T, Instant> startProvider,
            Function<T, Instant> endProvider,
            Function<T, BigDecimal> totalEstimatedCostProvider,
            Function<T, Integer> llmSpanCountProvider,
            Function<T, Integer> spanCountProvider,
            Function<T, List<GuardrailsValidation>> guardrailsProvider,
            Function<T, ErrorInfo> errorProvider,
            String countLabel) {

        if (expectedEntities.isEmpty()) {
            return List.of();
        }

        List<ProjectStats.ProjectStatItem<?>> stats = new ArrayList<>();

        long input = 0;
        long output = 0;
        long metadata = 0;
        double tags = 0;
        BigDecimal totalEstimatedCost = BigDecimal.ZERO;
        int countEstimatedCost = 0;
        int llmSpanCount = 0;
        int spanCount = 0;
        long errorCount = 0;

        for (T entity : expectedEntities) {
            input += inputProvider.apply(entity) != null ? 1 : 0;
            output += outputProvider.apply(entity) != null ? 1 : 0;
            metadata += metadataProvider.apply(entity) != null ? 1 : 0;
            tags += tagsProvider.apply(entity) != null ? tagsProvider.apply(entity).size() : 0;
            errorCount += errorProvider.apply(entity) != null ? 1 : 0;

            llmSpanCount += llmSpanCountProvider != null &&
                    llmSpanCountProvider.apply(entity) != null ? llmSpanCountProvider.apply(entity) : 0;

            spanCount += spanCountProvider != null &&
                    spanCountProvider.apply(entity) != null ? spanCountProvider.apply(entity) : 0;

            BigDecimal cost = totalEstimatedCostProvider.apply(entity) != null
                    ? totalEstimatedCostProvider.apply(entity)
                    : BigDecimal.ZERO;

            totalEstimatedCost = totalEstimatedCost.add(cost);

            if (cost.compareTo(BigDecimal.ZERO) > 0) {
                countEstimatedCost++;
            }
        }

        List<BigDecimal> quantities = StatsUtils.calculateQuantiles(
                expectedEntities.stream()
                        .filter(entity -> endProvider.apply(entity) != null)
                        .map(entity -> startProvider.apply(entity).until(endProvider.apply(entity), ChronoUnit.MICROS))
                        .map(duration -> duration / 1_000.0)
                        .toList(),
                List.of(0.50, 0.90, 0.99));

        Map<String, Double> usage = calculateUsageAverage(usages);
        Map<String, Double> feedback = calculateFeedbackAverage(feedbacks);
        Map<String, Double> spanFeedback = spanFeedbacks != null
                ? calculateFeedbackAverage(spanFeedbacks)
                : Map.of();

        stats.add(new CountValueStat(countLabel, input));
        if (!quantities.isEmpty()) {
            stats.add(new PercentageValueStat(StatsMapper.DURATION,
                    new PercentageValues(quantities.get(0), quantities.get(1), quantities.get(2))));
        } else {
            stats.add(new PercentageValueStat(StatsMapper.DURATION,
                    new PercentageValues(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)));
        }

        var totalEstimatedCostValue = countEstimatedCost == 0
                ? BigDecimal.ZERO
                : totalEstimatedCost.divide(BigDecimal.valueOf(countEstimatedCost), ValidationUtils.SCALE,
                        RoundingMode.HALF_UP);

        Long failedGuardrails = guardrailsProvider == null
                ? null
                : expectedEntities.stream()
                        .map(guardrailsProvider)
                        .filter(Objects::nonNull)
                        .flatMap(List::stream)
                        .map(GuardrailsValidation::checks)
                        .flatMap(List::stream)
                        .filter(guardrail -> guardrail.result() == GuardrailResult.FAILED)
                        .count();

        stats.add(new CountValueStat(StatsMapper.INPUT, input));
        stats.add(new CountValueStat(StatsMapper.OUTPUT, output));
        stats.add(new CountValueStat(StatsMapper.METADATA, metadata));
        stats.add(new AvgValueStat(StatsMapper.TAGS, (tags / expectedEntities.size())));

        if (llmSpanCountProvider != null) {
            var avgLlmSpanCount = llmSpanCount == 0 ? 0 : (double) llmSpanCount / expectedEntities.size();
            stats.add(new AvgValueStat(StatsMapper.LLM_SPAN_COUNT, avgLlmSpanCount));
        }

        if (spanCountProvider != null) {
            var avgSpanCount = spanCount == 0 ? 0 : (double) spanCount / expectedEntities.size();
            stats.add(new AvgValueStat(StatsMapper.SPAN_COUNT, avgSpanCount));
        }

        stats.add(new AvgValueStat(StatsMapper.TOTAL_ESTIMATED_COST, totalEstimatedCostValue.doubleValue()));
        stats.add(new AvgValueStat(StatsMapper.TOTAL_ESTIMATED_COST_SUM, totalEstimatedCost.doubleValue()));

        usage.keySet()
                .stream()
                .sorted()
                .forEach(key -> stats
                        .add(new AvgValueStat("%s.%s".formatted(StatsMapper.USAGE, key), usage.get(key))));

        feedback.keySet()
                .stream()
                .sorted()
                .forEach(key -> stats
                        .add(new AvgValueStat("%s.%s".formatted(StatsMapper.FEEDBACK_SCORE, key),
                                feedback.get(key))));

        // Only add span feedback scores statistics for traces (not spans) when there are actual values
        if (spanFeedbacks != null && countLabel.equals("trace_count") && !spanFeedback.isEmpty()) {
            spanFeedback.keySet()
                    .stream()
                    .sorted()
                    .forEach(key -> stats
                            .add(new AvgValueStat("%s.%s".formatted(StatsMapper.SPAN_FEEDBACK_SCORE, key),
                                    spanFeedback.get(key))));
        }

        Optional.ofNullable(failedGuardrails).ifPresent(failedGuardrailCount -> stats
                .add(new CountValueStat(StatsMapper.GUARDRAILS_FAILED_COUNT, failedGuardrailCount)));

        stats.add(new CountValueStat(StatsMapper.ERROR_COUNT, errorCount));

        return stats;
    }

    public static Map<String, Double> calculateUsageAverage(List<Map<String, Long>> data) {
        return data.stream()
                .filter(Objects::nonNull)
                .map(Map::entrySet)
                .flatMap(Collection::stream)
                .collect(groupingBy(
                        Map.Entry::getKey,
                        mapping(Map.Entry::getValue, toList())))
                .entrySet()
                .stream()
                .map(e -> Map.entry(e.getKey(),
                        avgFromDoubleList(e.getValue().stream().map(Double::valueOf).toList())))
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public static Map<String, Long> calculateUsage(List<Map<String, Long>> data) {
        return data.stream()
                .filter(Objects::nonNull)
                .map(Map::entrySet)
                .flatMap(Collection::stream)
                .collect(groupingBy(
                        Map.Entry::getKey,
                        mapping(Map.Entry::getValue, toList())))
                .entrySet()
                .stream()
                .map(e -> Map.entry(e.getKey(), e.getValue().stream().mapToLong(Long::longValue).average()))
                .collect(toMap(Map.Entry::getKey, e -> (long) e.getValue().orElseThrow()));
    }

    private static Map<String, Double> calculateFeedbackAverage(List<List<FeedbackScore>> data) {
        return data
                .stream()
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .collect(groupingBy(
                        FeedbackScore::name,
                        mapping(FeedbackScore::value, toList())))
                .entrySet()
                .stream()
                .map(e -> Map.entry(e.getKey(), avgFromList(e.getValue())))
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static Double avgFromDoubleList(List<Double> values) {
        return values.stream()
                .reduce(0.0, Double::sum) / values.size();
    }

    public static Double avgFromList(List<BigDecimal> values) {
        return values.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .doubleValue() / values.size();
    }

    public static RecursiveComparisonConfiguration getRecursiveComparisonConfiguration() {
        return RecursiveComparisonConfiguration.builder()
                .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                .withComparatorForType(StatsUtils::singleValueStatCompareTo,
                        CountValueStat.class)
                .withComparatorForType(StatsUtils::singleValueStatCompareTo,
                        AvgValueStat.class)
                .build();
    }

    private static int singleValueStatCompareTo(SingleValueStat<? extends Number> v1,
            SingleValueStat<? extends Number> v2) {
        if (!v1.getName().equals(v2.getName())) {
            return Comparator.comparing(String::toString).compare(v1.getName(), v2.getName());
        }
        if (!v1.getType().equals(v2.getType())) {
            return Comparator.comparing(String::toString).compare(v1.getType().toString(), v2.getType().toString());
        }

        return switch (v1) {
            case CountValueStat count -> Comparator.comparing(CountValueStat::getValue)
                    .compare((CountValueStat) v1, (CountValueStat) v2);
            case AvgValueStat avg -> getComparator().compare(avg.getValue(), ((AvgValueStat) v2).getValue());
        };
    }

    private static Comparator<Double> getComparator() {
        return (o1, o2) -> {
            if (Math.abs(o1 - o2) < TOLERANCE) {
                return 0;
            }

            return 1;
        };
    }

    public static int compareDoubles(Double d1, Double d2) {
        if (d1 == null && d2 == null) return 0;
        if (d1 == null) return -1;
        if (d2 == null) return 1;
        return Math.abs(d1 - d2) < 1e-6 ? 0 : Double.compare(d1, d2);
    }

    public static int bigDecimalComparator(BigDecimal v1, BigDecimal v2) {
        //TODO This is a workaround to compare BigDecimals and clickhouse floats seems to have some precision issues
        // Compare the integer parts directly

        // Handle null cases (if nulls are allowed)
        if (v1 == null && v2 == null) {
            return 0; // Both null are considered equal
        } else if (v1 == null) {
            return -1; // Null is considered "less than"
        } else if (v2 == null) {
            return 1; // Non-null is considered "greater than"
        }

        if (v1.compareTo(v2) == 0) {
            return 0;
        }

        // Define an absolute tolerance for comparison
        BigDecimal tolerance = new BigDecimal("0.001");

        // Normalize by stripping trailing zeros for consistent comparison
        BigDecimal strippedV1 = v1.stripTrailingZeros();
        BigDecimal strippedV2 = v2.stripTrailingZeros();

        // Calculate the absolute difference
        BigDecimal difference = strippedV1.subtract(strippedV2).abs();

        // If the difference is within the tolerance, consider them equal
        if (difference.compareTo(tolerance) <= 0) {
            return 0;
        }

        BigDecimal v1DecimalInt = strippedV1.movePointRight(strippedV1.scale());
        BigDecimal v2DecimalInt = strippedV2.movePointRight(strippedV2.scale());

        // Calculate the difference between the integer representations of the decimal parts
        BigDecimal decimalDifference = v1DecimalInt.subtract(v2DecimalInt).abs();

        if (decimalDifference.compareTo(BigDecimal.ONE) <= 0) {
            return 0;
        }

        /*
         * For p50, p90, p99, the calculation is not accurate, so we need to compare the integer part of the number
         * */
        return strippedV1.toBigInteger().compareTo(strippedV2.toBigInteger());
    }

    public static int closeToEpsilonComparator(Object v1, Object v2) {
        //TODO This is a workaround to compare averages originating from BigDecimals calculated by code vs. the same
        // calculated by Clickhouse

        // Handle null cases (if nulls are allowed)
        if (v1 == null && v2 == null) {
            return 0; // Both null are considered equal
        } else if (v1 == null) {
            return -1; // Null is considered "less than"
        } else if (v2 == null) {
            return 1; // Non-null is considered "greater than"
        }

        if (v1.equals(v2)) {
            return 0;
        }

        Number numv1 = (Number) v1, numv2 = (Number) v2;

        // Define an absolute tolerance for comparison
        double epsilon = .00001;

        // Calculate the absolute difference
        BigDecimal difference = BigDecimal.valueOf(numv1.doubleValue())
                .subtract(BigDecimal.valueOf(numv2.doubleValue())).abs();

        // If the difference is within the tolerance, consider them equal
        if (difference.doubleValue() <= epsilon) {
            return 0;
        } else if (difference.toString().replace("0", "").equals(".1")) {
            // This is a special case where the difference is exactly 1, which should also be considered equal
            return 0;
        }

        // otherwise return ordinary comparison
        return 1;
    }

    public static Map<String, Long> aggregateSpansUsage(List<Span> spans) {
        return spans.stream()
                .flatMap(span -> span.usage().entrySet().stream())
                .map(entry -> Map.entry(entry.getKey(), Long.valueOf(entry.getValue())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, Long::sum));
    }

    public static BigDecimal aggregateSpansCost(List<Span> spans) {
        return spans.stream()
                .map(span -> CostService.calculateCost(span.model(), span.provider(), span.usage(), null))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public static Map<String, Double> calculateFeedbackBatchAverage(List<FeedbackScoreBatchItem> data) {
        return data
                .stream()
                .filter(Objects::nonNull)
                .collect(groupingBy(
                        FeedbackScoreItem::name,
                        mapping(FeedbackScoreItem::value, toList())))
                .entrySet()
                .stream()
                .map(e -> Map.entry(e.getKey(), avgFromList(e.getValue())))
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
