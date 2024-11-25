package com.comet.opik.api.resources.utils;

import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.ProjectStats.ProjectStatItem;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.math.Quantiles;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;

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
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.utils.ValidationUtils.SCALE;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

public class StatsUtils {

    private static final double TOLERANCE = 0.00009;

    public static List<Double> calculateQuantiles(List<Double> data, List<Double> quantiles) {
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
                .toList();
    }

    public static List<ProjectStatItem<?>> getProjectTraceStatItems(List<Trace> expectedTraces) {
        return getProjectStatItems(expectedTraces,
                expectedTraces.stream().map(Trace::usage).toList(),
                expectedTraces.stream().map(Trace::feedbackScores).toList(),
                Trace::input,
                Trace::output,
                Trace::metadata,
                Trace::tags,
                Trace::startTime,
                Trace::endTime,
                "trace_count");
    }


    public static List<ProjectStatItem<?>> getProjectSpanStatItems(List<Span> expectedSpans) {
        List<Map<String, Long>> list = expectedSpans.stream().map(Span::usage)
                .map(value -> value.entrySet().stream()
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                entry -> entry.getValue().longValue()
                        )))
                .toList();

        return getProjectStatItems(expectedSpans,
                list,
                expectedSpans.stream().map(Span::feedbackScores).toList(),
                Span::input,
                Span::output,
                Span::metadata,
                Span::tags,
                Span::startTime,
                Span::endTime,
                "span_count");
    }

    private static <T> List<ProjectStatItem<?>> getProjectStatItems(
            List<T> expectedEntities,
            List<Map<String, Long>> usages,
            List<List<FeedbackScore>> feedbacks,
            Function<T, JsonNode> inputProvider,
            Function<T, JsonNode> outputProvider,
            Function<T, JsonNode> metadataProvider,
            Function<T, Set<String>> tagsProvider,
            Function<T, Instant> startProvider,
            Function<T, Instant> endProvider,
            String countLabel) {

        if (expectedEntities.isEmpty()) {
            return List.of();
        }

        List<ProjectStats.ProjectStatItem<?>> stats = new ArrayList<>();

        long input = 0;
        long output = 0;
        long metadata = 0;
        double tags = 0;

        for (T entity : expectedEntities) {
            input += inputProvider.apply(entity) != null ? 1 : 0;
            output += outputProvider.apply(entity) != null ? 1 : 0;
            metadata += metadataProvider.apply(entity) != null ? 1 : 0;
            tags += tagsProvider.apply(entity) != null ? tagsProvider.apply(entity).size() : 0;
        }

        List<Double> quantities = StatsUtils.calculateQuantiles(
                expectedEntities.stream()
                        .filter(entity -> endProvider.apply(entity) != null)
                        .map(entity -> startProvider.apply(entity).until(endProvider.apply(entity), ChronoUnit.MICROS))
                        .map(duration -> duration / 1_000.0)
                        .toList(),
                List.of(0.50, 0.90, 0.99));

        Map<String, Double> usage = calculateUsageAverage(usages);
        Map<String, BigDecimal> feedback = calculateFeedbackAverage(feedbacks);

        stats.add(new ProjectStats.CountValueStat(countLabel, input));
        if (!quantities.isEmpty()) {
            stats.add(new ProjectStats.PercentageValueStat("duration",
                    new ProjectStats.PercentageValues(quantities.get(0), quantities.get(1), quantities.get(2))));
        } else {
            stats.add(new ProjectStats.PercentageValueStat("duration", new ProjectStats.PercentageValues(0, 0, 0)));
        }

        stats.add(new ProjectStats.CountValueStat("input", input));
        stats.add(new ProjectStats.CountValueStat("output", output));
        stats.add(new ProjectStats.CountValueStat("metadata", metadata));
        stats.add(new ProjectStats.AvgValueStat("tags", BigDecimal.valueOf(tags / expectedEntities.size())));

        usage.keySet()
                .stream()
                .sorted()
                .forEach(key -> stats
                        .add(new ProjectStats.AvgValueStat("%s.%s".formatted("usage", key),
                                BigDecimal.valueOf(usage.get(key)))));

        feedback.keySet()
                .stream()
                .sorted()
                .forEach(key -> stats
                        .add(new ProjectStats.AvgValueStat("%s.%s".formatted("feedback_score", key),
                                feedback.get(key))));

        return stats;
    }

    private static Map<String, Double> calculateUsageAverage(List<Map<String, Long>> data) {
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

    private static Map<String, BigDecimal> calculateFeedbackAverage(List<List<FeedbackScore>> data) {
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

    private static BigDecimal avgFromList(List<BigDecimal> values) {
        return values.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), SCALE, RoundingMode.HALF_UP);
    }

    public static RecursiveComparisonConfiguration getRecursiveComparisonConfiguration() {
        return RecursiveComparisonConfiguration.builder()
                .withComparatorForType(StatsUtils::percentageValuesCompareTo,
                        ProjectStats.PercentageValues.class)
                .withComparatorForType(StatsUtils::singleValueStatCompareTo,
                        ProjectStats.CountValueStat.class)
                .withComparatorForType(StatsUtils::singleValueStatCompareTo,
                        ProjectStats.AvgValueStat.class)
                .build();
    }

    private static int singleValueStatCompareTo(ProjectStats.SingleValueStat<? extends Number> v1,
            ProjectStats.SingleValueStat<? extends Number> v2) {
        return switch (v1) {
            case ProjectStats.CountValueStat count -> Comparator.comparing(ProjectStats.CountValueStat::getValue)
                    .compare((ProjectStats.CountValueStat) v1, (ProjectStats.CountValueStat) v2);
            case ProjectStats.AvgValueStat avg ->
                numberCompareTo(avg.getValue(), ((ProjectStats.AvgValueStat) v2).getValue());
        };
    }

    private static int numberCompareTo(Number number, Number number2) {
        if (number instanceof BigDecimal value) {
            BigDecimal value2 = (BigDecimal) number2;

            assertThat(value).isCloseTo(value2, within(BigDecimal.valueOf(TOLERANCE)));

            return 0;
        }

        return Double.compare(number.doubleValue(), number2.doubleValue());
    }

    private static int percentageValuesCompareTo(ProjectStats.PercentageValues v1, ProjectStats.PercentageValues v2) {

        Comparator<ProjectStats.PercentageValues> percentageValuesComparator = (o1, o2) -> {
            if (Math.abs(o1.p50() - o2.p50()) < TOLERANCE &&
                    Math.abs(o1.p90() - o2.p90()) < TOLERANCE &&
                    Math.abs(o1.p99() - o2.p99()) < TOLERANCE) {
                return 0;
            }

            return 1;
        };

        return percentageValuesComparator.compare(v1, v2);

    }

}
