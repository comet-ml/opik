package com.comet.opik.domain;

import com.comet.opik.api.spend.SpendBreakdownResponse;
import com.comet.opik.api.spend.SpendCompositionResponse;
import com.comet.opik.api.spend.SpendLane;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AiSpendMapper")
class AiSpendMapperTest {

    private final AiSpendMapper mapper = new AiSpendMapper();

    @Test
    @DisplayName("composition pivots (lane, model, tier, tokens) rows to the per-lane per-model tier columns")
    void composition_pivotsTierRows() {
        // Two lanes × one model × multiple tiers. Mixed order on purpose so we
        // exercise the group-by, not the SQL ordering.
        var rows = List.of(
                row("user_prompts", "claude-opus-4-8", "input", 100L),
                row("prior_assistant", "claude-opus-4-8", "cache_read", 800L),
                row("user_prompts", "claude-opus-4-8", "cache_read", 50L),
                row("thinking", "claude-opus-4-8", "output", 200L));

        var composition = mapper.composition(rows);

        var inputLanes = byKey(composition.input().lanes());
        assertThat(inputLanes.get("user_prompts").totalTokens()).isEqualTo(150L);
        assertThat(inputLanes.get("user_prompts").byModel()).hasSize(1);
        assertThat(inputLanes.get("user_prompts").byModel().getFirst().inputTokens()).isEqualTo(100L);
        assertThat(inputLanes.get("user_prompts").byModel().getFirst().cacheReadTokens()).isEqualTo(50L);
        assertThat(inputLanes.get("prior_assistant").byModel().getFirst().cacheReadTokens()).isEqualTo(800L);

        var outputLanes = byKey(composition.output().lanes());
        assertThat(outputLanes.get("thinking").byModel().getFirst().outputTokens()).isEqualTo(200L);
    }

    @Test
    @DisplayName("composition merges attributed and residual unattributed rows under the same lane key")
    void composition_mergesUnattributedResidual() {
        // The SQL UNIONs attributed and residual streams; attributed rows can
        // also land in 'unattributed' (when a category isn't in the lane map).
        // The mapper must sum them rather than overwrite or split.
        var rows = List.of(
                // residual: a call had cache_creation tokens but no write-tier block
                row("unattributed", "claude-opus-4-8", "cache_creation", 40L),
                // attributed: identity_context block fell into the unattributed default
                row("unattributed", "claude-opus-4-8", "cache_read", 10L));

        var composition = mapper.composition(rows);

        var unattributed = byKey(composition.input().lanes()).get("unattributed");
        assertThat(unattributed.totalTokens()).isEqualTo(50L);
        assertThat(unattributed.byModel().getFirst().cacheCreationTokens()).isEqualTo(40L);
        assertThat(unattributed.byModel().getFirst().cacheReadTokens()).isEqualTo(10L);
        assertThat(unattributed.hasBreakdown()).isFalse();
    }

    @Test
    @DisplayName("composition splits tokens per model so the FE can price each at its own rate")
    void composition_splitsByModel() {
        var rows = List.of(
                row("user_prompts", "claude-opus-4-8", "input", 100L),
                row("user_prompts", "claude-sonnet-4-5", "input", 60L));

        var composition = mapper.composition(rows);

        var lane = byKey(composition.input().lanes()).get("user_prompts");
        assertThat(lane.totalTokens()).isEqualTo(160L);
        assertThat(lane.byModel()).hasSize(2);
        var byModelKey = lane.byModel().stream()
                .collect(Collectors.toMap(m -> m.model(), Function.identity()));
        assertThat(byModelKey.get("claude-opus-4-8").inputTokens()).isEqualTo(100L);
        assertThat(byModelKey.get("claude-sonnet-4-5").inputTokens()).isEqualTo(60L);
    }

    @Test
    @DisplayName("composition lists every lane in enum order so the FE Sankey layout is stable")
    void composition_emitsAllLanesInEnumOrder() {
        var composition = mapper.composition(List.of(
                row("memory", "claude-opus-4-8", "cache_read", 60L)));

        // Even with one row, every input lane appears (zero-token ones included)
        // and in SpendLane enum order so the FE renders a consistent layout.
        assertThat(composition.input().lanes())
                .extracting(SpendCompositionResponse.Lane::key)
                .containsExactly("user_prompts", "file_attachments", "built_in_tools", "prior_assistant",
                        "skills", "custom_agents", "mcp_servers", "memory", "static_overhead", "unattributed");
        assertThat(composition.output().lanes())
                .extracting(SpendCompositionResponse.Lane::key)
                .containsExactly("thinking", "assistant_text", "built_in_tool_calls", "mcp_tool_calls",
                        "skill_invocations");
    }

    @Test
    @DisplayName("composition skips rows with unrecognized tier — they can't be pivoted onto the 4 tier columns")
    void composition_ignoresUnknownTier() {
        var rows = List.of(
                row("user_prompts", "claude-opus-4-8", "input", 100L),
                row("user_prompts", "claude-opus-4-8", "bogus", 999L));

        var composition = mapper.composition(rows);

        var lane = byKey(composition.input().lanes()).get("user_prompts");
        assertThat(lane.totalTokens()).isEqualTo(100L);
    }

    @Test
    @DisplayName("composition emits empty lanes for tags absent from the row stream")
    void composition_emptyLanesShowZero() {
        var composition = mapper.composition(List.of());

        assertThat(composition.input().lanes()).allSatisfy(lane -> {
            assertThat(lane.totalTokens()).isZero();
            assertThat(lane.byModel()).isEmpty();
        });
        assertThat(composition.input().totalTokens()).isZero();
        assertThat(composition.output().totalTokens()).isZero();
    }

    @Test
    @DisplayName("breakdown rolls per-(label, model) rows into per-label items with stacked definition/usage split")
    void breakdown_aggregatesByLabel() {
        var rows = List.of(
                // opik-frontend: 100 def + 500 usage from one trace, replayed across 3
                breakdownRow("opik-frontend", "claude-opus-4-8", 1800L, 300L, 1500L, 3L,
                        0L, 1800L, 0L, 0L, 2700L, 2L, 6L),
                breakdownRow("find-skills", "claude-opus-4-8", 900L, 0L, 900L, 3L,
                        0L, 900L, 0L, 0L, 2700L, 2L, 6L));

        var response = mapper.breakdown("skills", "Skills", "Skills lane",
                SpendLane.SKILLS.getItemUnit(), rows);

        assertThat(response.laneKey()).isEqualTo("skills");
        assertThat(response.totalTokens()).isEqualTo(2700L);
        assertThat(response.itemCount()).isEqualTo(6);

        var byLabel = response.items().stream()
                .collect(Collectors.toMap(SpendBreakdownResponse.Item::label, Function.identity()));
        assertThat(byLabel.get("opik-frontend").totalTokens()).isEqualTo(1800L);
        assertThat(byLabel.get("opik-frontend").definitionTokens()).isEqualTo(300L);
        assertThat(byLabel.get("opik-frontend").usageTokens()).isEqualTo(1500L);
        assertThat(byLabel.get("opik-frontend").count()).isEqualTo(3L);
        assertThat(byLabel.get("find-skills").definitionTokens()).isZero();
        assertThat(byLabel.get("find-skills").usageTokens()).isEqualTo(900L);
    }

    @Test
    @DisplayName("breakdown renames the __other__ rollup label to 'Other (N items)' using the hidden count")
    void breakdown_renamesOtherLabel() {
        // group_count=5 total, two distinct visible labels + one __other__ rollup means
        // hidden = 5 - 2 = 3.
        var rows = List.of(
                breakdownRow("Bash", "claude-opus-4-8", 100L, 0L, 100L, 1L,
                        0L, 100L, 0L, 0L, 250L, 5L, 5L),
                breakdownRow("Read", "claude-opus-4-8", 80L, 0L, 80L, 1L,
                        0L, 80L, 0L, 0L, 250L, 5L, 5L),
                breakdownRow("__other__", "claude-opus-4-8", 70L, 0L, 70L, 3L,
                        0L, 70L, 0L, 0L, 250L, 5L, 5L));

        var response = mapper.breakdown("built_in_tools", "Built-in tools", null,
                SpendLane.BUILT_IN_TOOLS.getItemUnit(), rows);

        assertThat(response.items()).hasSize(3);
        assertThat(response.items().getLast().label()).isEqualTo("Other (3 items)");
        assertThat(response.items().getLast().totalTokens()).isEqualTo(70L);
    }

    @Test
    @DisplayName("breakdown items carry per-model rows so the FE can price each row at the right rate")
    void breakdown_perItemPerModel() {
        // Same label, two different models — model pricing differs so they
        // need to ship as separate ModelTiers entries.
        var rows = List.of(
                breakdownRow("chrome-devtools", "claude-opus-4-8", 600L, 500L, 100L, 1L,
                        0L, 600L, 0L, 0L, 900L, 1L, 2L),
                breakdownRow("chrome-devtools", "claude-sonnet-4-5", 300L, 250L, 50L, 1L,
                        0L, 300L, 0L, 0L, 900L, 1L, 2L));

        var response = mapper.breakdown("mcp_servers", "MCP servers", null, "call", rows);

        var item = response.items().getFirst();
        assertThat(item.label()).isEqualTo("chrome-devtools");
        assertThat(item.byModel()).hasSize(2);
        assertThat(item.totalTokens()).isEqualTo(900L);
        assertThat(item.definitionTokens()).isEqualTo(750L);
        assertThat(item.usageTokens()).isEqualTo(150L);
    }

    @Test
    @DisplayName("breakdown of an empty row stream returns zeros without throwing")
    void breakdown_empty() {
        var response = mapper.breakdown("skills", "Skills", null, "load", List.of());

        assertThat(response.laneKey()).isEqualTo("skills");
        assertThat(response.totalTokens()).isZero();
        assertThat(response.itemCount()).isZero();
        assertThat(response.items()).isEmpty();
    }

    @Test
    @DisplayName("summary drops zero-token model rows so the FE doesn't render empty chips")
    void summary_dropsZeroRows() {
        var tiers = List.of(
                // current=non-zero, previous=zero → only current appears
                new AiSpendMapper.SummaryTierRow("claude-opus-4-8",
                        50L, 2710L, 40L, 280L,
                        0L, 0L, 0L, 0L),
                // both zero — model shouldn't appear at all
                new AiSpendMapper.SummaryTierRow("claude-haiku-4-5",
                        0L, 0L, 0L, 0L,
                        0L, 0L, 0L, 0L));
        var counts = new AiSpendMapper.CountsRow(3L, 1L, 2L, 1L, 3L);

        var summary = mapper.summary(tiers, counts);

        assertThat(summary.spendCurrent()).hasSize(1);
        assertThat(summary.spendCurrent().getFirst().model()).isEqualTo("claude-opus-4-8");
        assertThat(summary.spendPrevious()).isEmpty();
    }

    private static AiSpendMapper.CompositionRow row(String lane, String model, String tier, long tokens) {
        return new AiSpendMapper.CompositionRow(lane, model, tier, tokens);
    }

    private static AiSpendMapper.BreakdownRow breakdownRow(String label, String model, long totalTokens,
            long definitionTokens, long usageTokens, long events,
            long inputTokens, long cacheReadTokens, long cacheCreationTokens, long outputTokens,
            long grandTotal, long groupCount, long totalEvents) {
        return new AiSpendMapper.BreakdownRow(label, model, totalTokens, definitionTokens, usageTokens, events,
                inputTokens, cacheReadTokens, cacheCreationTokens, outputTokens,
                grandTotal, groupCount, totalEvents);
    }

    private static Map<String, SpendCompositionResponse.Lane> byKey(List<SpendCompositionResponse.Lane> lanes) {
        return lanes.stream().collect(Collectors.toMap(SpendCompositionResponse.Lane::key, Function.identity()));
    }
}
