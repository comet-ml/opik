package com.comet.opik.domain;

import com.comet.opik.api.metrics.WorkspaceMetricsSummaryResponse;
import com.comet.opik.api.spend.OutputLane;
import com.comet.opik.api.spend.SpendBreakdownResponse;
import com.comet.opik.api.spend.SpendCompositionResponse;
import com.comet.opik.api.spend.SpendLane;
import jakarta.inject.Singleton;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Singleton
class AiSpendMapper {

    private static final double HIGH_SPEND_FACTOR = 2.0;

    double highSpendFactor() {
        return HIGH_SPEND_FACTOR;
    }

    List<WorkspaceMetricsSummaryResponse.Result> summaryResults(CostRow cost, CountsRow counts) {
        return List.of(
                result("total_spend", toDouble(cost.current()), toDouble(cost.previous())),
                result("total_tokens", sumTokens(counts.inputTokensCurrent(), cost.outputTokensCurrent()),
                        sumTokens(counts.inputTokensPrevious(), cost.outputTokensPrevious())),
                result("total_messages", toDouble(counts.messagesCurrent()), toDouble(counts.messagesPrevious())),
                result("avg_cost_per_user", average(cost.current(), counts.activeUsersCurrent()),
                        average(cost.previous(), counts.activeUsersPrevious())),
                result("active_users", toDouble(counts.activeUsersCurrent()), null),
                result("total_users", toDouble(counts.totalUsers()), null));
    }

    SpendCompositionResponse composition(Map<SpendLane, Long> inputTokens, Map<String, Long> outputTokens,
            BigDecimal totalCost) {
        List<SpendCompositionResponse.Lane> inputLanes = new ArrayList<>();
        long inputTotal = 0L;
        for (SpendLane lane : SpendLane.values()) {
            long laneTokens = inputTokens.getOrDefault(lane, 0L);
            inputLanes.add(SpendCompositionResponse.Lane.builder()
                    .key(lane.getKey())
                    .label(lane.getLabel())
                    .totalTokens(laneTokens)
                    .hasBreakdown(lane.hasBreakdown())
                    .build());
            inputTotal += laneTokens;
        }

        List<SpendCompositionResponse.Lane> outputLanes = new ArrayList<>();
        long outputTotal = 0L;
        for (OutputLane lane : OutputLane.values()) {
            long laneTokens = outputTokens.getOrDefault(lane.getKey(), 0L);
            outputLanes.add(SpendCompositionResponse.Lane.builder()
                    .key(lane.getKey())
                    .label(lane.getLabel())
                    .totalTokens(laneTokens)
                    .hasBreakdown(lane.hasBreakdown())
                    .build());
            outputTotal += laneTokens;
        }

        return SpendCompositionResponse.builder()
                .input(SpendCompositionResponse.Side.builder().totalTokens(inputTotal).lanes(inputLanes).build())
                .output(SpendCompositionResponse.Side.builder().totalTokens(outputTotal).lanes(outputLanes).build())
                .harness(List.of(SpendCompositionResponse.HarnessEntry.builder()
                        .key("claude_code")
                        .label("Claude Code")
                        .totalEstimatedCost(totalCost)
                        .build()))
                .build();
    }

    SpendBreakdownResponse breakdown(String laneKey, String title, String subtitle, List<BreakdownRow> rows) {
        List<SpendBreakdownResponse.Item> items = rows.stream()
                .map(row -> SpendBreakdownResponse.Item.builder()
                        .label(row.label())
                        .totalTokens(row.totalTokens())
                        .build())
                .collect(Collectors.toCollection(ArrayList::new));

        long grandTotal = rows.isEmpty() ? 0L : rows.getFirst().grandTotal();
        long groupCount = rows.isEmpty() ? 0L : rows.getFirst().groupCount();
        long hidden = groupCount - items.size();
        if (hidden > 0) {
            long shown = items.stream().mapToLong(SpendBreakdownResponse.Item::totalTokens).sum();
            items.add(SpendBreakdownResponse.Item.builder()
                    .label("Other (%d items)".formatted(hidden))
                    .totalTokens(grandTotal - shown)
                    .build());
        }

        return SpendBreakdownResponse.builder()
                .laneKey(laneKey)
                .title(title)
                .subtitle(subtitle)
                .totalTokens(grandTotal)
                .itemCount((int) groupCount)
                .items(items)
                .build();
    }

    OutputCost outputCost(List<OutputLaneCost> rows) {
        Map<String, Long> tokens = rows.stream()
                .filter(row -> row.lane() != null && !row.lane().isEmpty())
                .collect(Collectors.toMap(OutputLaneCost::lane, OutputLaneCost::tokens));
        BigDecimal cost = rows.stream().map(OutputLaneCost::cost).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new OutputCost(tokens, cost);
    }

    private WorkspaceMetricsSummaryResponse.Result result(String name, Double current, Double previous) {
        return WorkspaceMetricsSummaryResponse.Result.builder().name(name).current(current).previous(previous).build();
    }

    private Double average(BigDecimal total, Long count) {
        return total == null || count == null || count == 0L ? null : total.doubleValue() / count;
    }

    private Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private Double toDouble(Long value) {
        return value == null ? null : value.doubleValue();
    }

    private Double sumTokens(Long input, Long output) {
        if (input == null && output == null) {
            return null;
        }
        return (double) ((input == null ? 0L : input) + (output == null ? 0L : output));
    }

    record CostRow(BigDecimal current, BigDecimal previous, Long outputTokensCurrent, Long outputTokensPrevious) {
        static CostRow empty() {
            return new CostRow(null, null, 0L, 0L);
        }
    }

    record CountsRow(Long messagesCurrent, Long messagesPrevious, Long activeUsersCurrent, Long activeUsersPrevious,
            Long totalUsers, Long inputTokensCurrent, Long inputTokensPrevious) {
        static CountsRow empty() {
            return new CountsRow(0L, 0L, 0L, 0L, 0L, 0L, 0L);
        }
    }

    record BreakdownRow(String label, long totalTokens, long grandTotal, long groupCount) {
    }

    record OutputLaneCost(String lane, long tokens, BigDecimal cost) {
    }

    record OutputCost(Map<String, Long> tokens, BigDecimal cost) {
    }
}
