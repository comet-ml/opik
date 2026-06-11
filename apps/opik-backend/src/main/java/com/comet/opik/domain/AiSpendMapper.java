package com.comet.opik.domain;

import com.comet.opik.api.metrics.WorkspaceMetricsSummaryResponse;
import com.comet.opik.api.spend.OutputLane;
import com.comet.opik.api.spend.SpendBreakdownResponse;
import com.comet.opik.api.spend.SpendCompositionResponse;
import com.comet.opik.api.spend.SpendLane;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Singleton
class AiSpendMapper {

    private static final double HIGH_SPEND_FACTOR = 2.0;

    double highSpendFactor() {
        return HIGH_SPEND_FACTOR;
    }

    List<WorkspaceMetricsSummaryResponse.Result> summaryResults(TiersRow tiers, CountsRow counts) {
        return List.of(
                // Raw tier tokens — the FE prices them and derives
                // total_spend / avg_cost_per_user client-side.
                result("spend_input_tokens", toDouble(tiers.inputCurrent()), toDouble(tiers.inputPrevious())),
                result("spend_cache_read_tokens", toDouble(tiers.cacheReadCurrent()),
                        toDouble(tiers.cacheReadPrevious())),
                result("spend_cache_creation_tokens", toDouble(tiers.cacheCreationCurrent()),
                        toDouble(tiers.cacheCreationPrevious())),
                result("spend_output_tokens", toDouble(tiers.outputCurrent()), toDouble(tiers.outputPrevious())),
                result("total_messages", toDouble(counts.messagesCurrent()), toDouble(counts.messagesPrevious())),
                result("active_users", toDouble(counts.activeUsersCurrent()), null),
                result("total_users", toDouble(counts.totalUsers()), null));
    }

    SpendCompositionResponse composition(InputLanesRow inputRow, Map<String, Long> outputTokens) {
        List<SpendCompositionResponse.Lane> inputLanes = new ArrayList<>();
        long inputTotal = 0L;
        for (SpendLane lane : SpendLane.values()) {
            LaneTiers tiers = inputRow.lanes().getOrDefault(lane, LaneTiers.empty());
            inputLanes.add(SpendCompositionResponse.Lane.builder()
                    .key(lane.getKey())
                    .label(lane.getLabel())
                    .totalTokens(tiers.total())
                    .inputTokens(tiers.input())
                    .cacheReadTokens(tiers.cacheRead())
                    .cacheCreationTokens(tiers.cacheCreation())
                    .outputTokens(tiers.output())
                    .hasBreakdown(lane.hasBreakdown())
                    .build());
            inputTotal += tiers.total();
        }

        List<SpendCompositionResponse.Lane> outputLanes = new ArrayList<>();
        long outputTotal = 0L;
        for (OutputLane lane : OutputLane.values()) {
            long laneTokens = outputTokens.getOrDefault(lane.getKey(), 0L);
            outputLanes.add(SpendCompositionResponse.Lane.builder()
                    .key(lane.getKey())
                    .label(lane.getLabel())
                    .totalTokens(laneTokens)
                    .outputTokens(laneTokens)
                    .hasBreakdown(lane.hasBreakdown())
                    .build());
            outputTotal += laneTokens;
        }

        return SpendCompositionResponse.builder()
                .input(SpendCompositionResponse.Side.builder().totalTokens(inputTotal).lanes(inputLanes).build())
                .output(SpendCompositionResponse.Side.builder().totalTokens(outputTotal).lanes(outputLanes).build())
                .models(inputRow.models())
                .harness(List.of(SpendCompositionResponse.HarnessEntry.builder()
                        .key("claude_code")
                        .label("Claude Code")
                        .build()))
                .build();
    }

    SpendBreakdownResponse breakdown(String laneKey, String title, String subtitle, List<BreakdownRow> rows) {
        List<SpendBreakdownResponse.Item> items = rows.stream()
                .map(row -> SpendBreakdownResponse.Item.builder()
                        .label(row.label())
                        .totalTokens(row.totalTokens())
                        .definitionTokens(row.definitionTokens())
                        .usageTokens(row.usageTokens())
                        .count(row.events())
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

    Map<String, Long> outputTokens(List<OutputLaneRow> rows) {
        return rows.stream()
                .filter(row -> row.lane() != null && !row.lane().isEmpty())
                .collect(Collectors.toMap(OutputLaneRow::lane, OutputLaneRow::tokens));
    }

    private WorkspaceMetricsSummaryResponse.Result result(String name, Double current, Double previous) {
        return WorkspaceMetricsSummaryResponse.Result.builder().name(name).current(current).previous(previous).build();
    }

    private Double toDouble(Long value) {
        return value == null ? null : value.doubleValue();
    }

    record TiersRow(long inputCurrent, long cacheReadCurrent, long cacheCreationCurrent, long outputCurrent,
            long inputPrevious, long cacheReadPrevious, long cacheCreationPrevious, long outputPrevious) {
        static TiersRow empty() {
            return new TiersRow(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        }
    }

    record CountsRow(Long messagesCurrent, Long messagesPrevious, Long activeUsersCurrent, Long activeUsersPrevious,
            Long totalUsers) {
        static CountsRow empty() {
            return new CountsRow(0L, 0L, 0L, 0L, 0L);
        }
    }

    record LaneTiers(long total, long input, long cacheRead, long cacheCreation, long output) {
        static LaneTiers empty() {
            return new LaneTiers(0L, 0L, 0L, 0L, 0L);
        }
    }

    record InputLanesRow(Map<SpendLane, LaneTiers> lanes, List<String> models) {
        static InputLanesRow empty() {
            return new InputLanesRow(new EnumMap<>(SpendLane.class), List.of());
        }
    }

    record BreakdownRow(String label, long totalTokens, long definitionTokens, long usageTokens, long events,
            long grandTotal, long groupCount) {
    }

    record OutputLaneRow(String lane, long tokens) {
    }
}
