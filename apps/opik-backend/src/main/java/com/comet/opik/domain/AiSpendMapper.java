package com.comet.opik.domain;

import com.comet.opik.api.metrics.WorkspaceMetricsSummaryResponse;
import com.comet.opik.api.spend.ModelTiers;
import com.comet.opik.api.spend.OutputLane;
import com.comet.opik.api.spend.SpendBreakdownResponse;
import com.comet.opik.api.spend.SpendCompositionResponse;
import com.comet.opik.api.spend.SpendLane;
import com.comet.opik.api.spend.SpendSummaryResponse;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Singleton
class AiSpendMapper {

    private static final double HIGH_SPEND_FACTOR = 2.0;
    static final String OTHER_LABEL = "__other__";

    double highSpendFactor() {
        return HIGH_SPEND_FACTOR;
    }

    SpendSummaryResponse summary(List<SummaryTierRow> tierRows, CountsRow counts) {
        List<WorkspaceMetricsSummaryResponse.Result> results = List.of(
                result("total_messages", toDouble(counts.messagesCurrent()), toDouble(counts.messagesPrevious())),
                result("active_users", toDouble(counts.activeUsersCurrent()), null),
                result("total_users", toDouble(counts.totalUsers()), null));

        List<ModelTiers> spendCurrent = new ArrayList<>();
        List<ModelTiers> spendPrevious = new ArrayList<>();
        for (SummaryTierRow row : tierRows) {
            addNonZero(spendCurrent, row.model(), row.inputCurrent(), row.cacheReadCurrent(),
                    row.cacheCreationCurrent(), row.outputCurrent());
            addNonZero(spendPrevious, row.model(), row.inputPrevious(), row.cacheReadPrevious(),
                    row.cacheCreationPrevious(), row.outputPrevious());
        }

        return SpendSummaryResponse.builder()
                .results(results)
                .spendCurrent(spendCurrent)
                .spendPrevious(spendPrevious)
                .build();
    }

    SpendCompositionResponse composition(List<ModelLanesRow> inputRows, List<OutputModelRow> outputRows) {
        List<SpendCompositionResponse.Lane> inputLanes = new ArrayList<>();
        long inputTotal = 0L;
        for (SpendLane lane : SpendLane.values()) {
            List<ModelTiers> byModel = new ArrayList<>();
            long laneTotal = 0L;
            for (ModelLanesRow row : inputRows) {
                LaneTiers tiers = row.lanes().getOrDefault(lane, LaneTiers.empty());
                laneTotal += tiers.total();
                addNonZero(byModel, row.model(), tiers.input(), tiers.cacheRead(), tiers.cacheCreation(),
                        tiers.output());
            }
            inputLanes.add(SpendCompositionResponse.Lane.builder()
                    .key(lane.getKey())
                    .label(lane.getLabel())
                    .totalTokens(laneTotal)
                    .byModel(byModel)
                    .hasBreakdown(lane.hasBreakdown())
                    .build());
            inputTotal += laneTotal;
        }

        Map<String, List<OutputModelRow>> outputByLane = new LinkedHashMap<>();
        for (OutputModelRow row : outputRows) {
            if (row.lane() != null && !row.lane().isEmpty()) {
                outputByLane.computeIfAbsent(row.lane(), key -> new ArrayList<>()).add(row);
            }
        }

        List<SpendCompositionResponse.Lane> outputLanes = new ArrayList<>();
        long outputTotal = 0L;
        for (OutputLane lane : OutputLane.values()) {
            List<OutputModelRow> rows = outputByLane.getOrDefault(lane.getKey(), List.of());
            List<ModelTiers> byModel = new ArrayList<>();
            long laneTotal = 0L;
            for (OutputModelRow row : rows) {
                laneTotal += row.tokens();
                addNonZero(byModel, row.model(), 0L, 0L, 0L, row.tokens());
            }
            outputLanes.add(SpendCompositionResponse.Lane.builder()
                    .key(lane.getKey())
                    .label(lane.getLabel())
                    .totalTokens(laneTotal)
                    .byModel(byModel)
                    .hasBreakdown(lane.hasBreakdown())
                    .build());
            outputTotal += laneTotal;
        }

        return SpendCompositionResponse.builder()
                .input(SpendCompositionResponse.Side.builder().totalTokens(inputTotal).lanes(inputLanes).build())
                .output(SpendCompositionResponse.Side.builder().totalTokens(outputTotal).lanes(outputLanes).build())
                .harness(List.of(SpendCompositionResponse.HarnessEntry.builder()
                        .key("claude_code")
                        .label("Claude Code")
                        .build()))
                .build();
    }

    SpendBreakdownResponse breakdown(String laneKey, String title, String subtitle, String itemUnit,
            List<BreakdownRow> rows) {
        long grandTotal = rows.isEmpty() ? 0L : rows.getFirst().grandTotal();
        long groupCount = rows.isEmpty() ? 0L : rows.getFirst().groupCount();
        long totalEvents = rows.isEmpty() ? 0L : rows.getFirst().totalEvents();

        Map<String, List<BreakdownRow>> grouped = new LinkedHashMap<>();
        Map<String, long[]> laneByModel = new LinkedHashMap<>();
        for (BreakdownRow row : rows) {
            grouped.computeIfAbsent(row.label(), key -> new ArrayList<>()).add(row);
            laneByModel.computeIfAbsent(row.model(), key -> new long[4]);
            long[] acc = laneByModel.get(row.model());
            acc[0] += row.inputTokens();
            acc[1] += row.cacheReadTokens();
            acc[2] += row.cacheCreationTokens();
            acc[3] += row.outputTokens();
        }

        long shownLabels = grouped.keySet().stream().filter(label -> !OTHER_LABEL.equals(label)).count();
        long hidden = groupCount - shownLabels;

        List<SpendBreakdownResponse.Item> items = new ArrayList<>();
        for (Map.Entry<String, List<BreakdownRow>> entry : grouped.entrySet()) {
            List<BreakdownRow> group = entry.getValue();
            String label = OTHER_LABEL.equals(entry.getKey())
                    ? "Other (%d items)".formatted(hidden)
                    : entry.getKey();
            items.add(SpendBreakdownResponse.Item.builder()
                    .label(label)
                    .totalTokens(group.stream().mapToLong(BreakdownRow::totalTokens).sum())
                    .definitionTokens(group.stream().mapToLong(BreakdownRow::definitionTokens).sum())
                    .usageTokens(group.stream().mapToLong(BreakdownRow::usageTokens).sum())
                    .count(group.stream().mapToLong(BreakdownRow::events).sum())
                    .byModel(itemByModel(group))
                    .build());
        }

        return SpendBreakdownResponse.builder()
                .laneKey(laneKey)
                .title(title)
                .subtitle(subtitle)
                .totalTokens(grandTotal)
                .byModel(toModelTiers(laneByModel))
                .itemCount((int) totalEvents)
                .itemUnit(itemUnit)
                .items(items)
                .build();
    }

    private List<ModelTiers> itemByModel(List<BreakdownRow> group) {
        List<ModelTiers> byModel = new ArrayList<>();
        for (BreakdownRow row : group) {
            addNonZero(byModel, row.model(), row.inputTokens(), row.cacheReadTokens(), row.cacheCreationTokens(),
                    row.outputTokens());
        }
        return byModel;
    }

    private List<ModelTiers> toModelTiers(Map<String, long[]> byModel) {
        List<ModelTiers> result = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : byModel.entrySet()) {
            long[] t = entry.getValue();
            addNonZero(result, entry.getKey(), t[0], t[1], t[2], t[3]);
        }
        return result;
    }

    private void addNonZero(List<ModelTiers> target, String model, long input, long cacheRead, long cacheCreation,
            long output) {
        if (input + cacheRead + cacheCreation + output <= 0L) {
            return;
        }
        target.add(ModelTiers.builder()
                .model(model)
                .inputTokens(input)
                .cacheReadTokens(cacheRead)
                .cacheCreationTokens(cacheCreation)
                .outputTokens(output)
                .build());
    }

    private WorkspaceMetricsSummaryResponse.Result result(String name, Double current, Double previous) {
        return WorkspaceMetricsSummaryResponse.Result.builder().name(name).current(current).previous(previous).build();
    }

    private Double toDouble(Long value) {
        return value == null ? null : value.doubleValue();
    }

    record SummaryTierRow(String model, long inputCurrent, long cacheReadCurrent, long cacheCreationCurrent,
            long outputCurrent, long inputPrevious, long cacheReadPrevious, long cacheCreationPrevious,
            long outputPrevious) {
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

    record ModelLanesRow(String model, Map<SpendLane, LaneTiers> lanes) {
    }

    record OutputModelRow(String lane, String model, long tokens) {
    }

    record BreakdownRow(String label, String model, long totalTokens, long definitionTokens, long usageTokens,
            long events, long inputTokens, long cacheReadTokens, long cacheCreationTokens, long outputTokens,
            long grandTotal, long groupCount, long totalEvents) {
    }
}
