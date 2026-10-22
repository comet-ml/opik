package com.comet.opik.domain.ailabels;

import com.comet.opik.domain.ailabels.AiLabelRenderDAO.SpanRow;
import com.comet.opik.domain.ailabels.AiLabelRenderDAO.ThreadTurnRow;
import com.comet.opik.domain.ailabels.AiLabelRenderDAO.TraceRow;
import com.comet.opik.domain.ailabels.AiLabelStateFitter.Fit;
import com.comet.opik.domain.ailabels.AiLabelStateFitter.Fitted;
import com.comet.opik.domain.ailabels.AiLabelStateFitter.OverBudget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AiLabelStateFitterTest {

    @Test
    void sizeOfMatchesTheCanonicalBytesForEveryScript() {
        var fixtures = new AiLabelRenderFixtures(42, true);
        for (int i = 0; i < 50; i++) {
            var trace = fixtures.trace(200);
            var state = state(trace, fixtures.spans(trace, 40, 6, 100));

            assertThat(AiLabelJson.sizeOf(state)).isEqualTo(canonicalBytes(state));
        }
    }

    @Test
    void fitDropsSpansDeepestLevelFirstAndCountsTheWholeTree() {
        var trace = trace("{\"q\":\"question\"}", "\"answer\"");
        var spans = List.of(
                span(trace, "a", null, 0, "llm"),
                span(trace, "a1", "a", 1, "tool"),
                span(trace, "a11", "a1", 2, "tool"),
                span(trace, "b", null, 3, "general"));
        var withoutDepth3 = pruned(trace, spans, 3);
        var withoutDepth2 = pruned(trace, spans, 2);
        var summarized = pruned(trace, spans, 1);

        assertThat(fit(trace, spans, null, canonicalBytes(withoutDepth3)))
                .isEqualTo(new Fitted(withoutDepth3, null, canonicalBytes(withoutDepth3), "spans_pruned:3"));
        assertThat(fit(trace, spans, null, canonicalBytes(withoutDepth3) - 1))
                .isEqualTo(new Fitted(withoutDepth2, null, canonicalBytes(withoutDepth2), "spans_pruned:2"));
        assertThat(fit(trace, spans, null, canonicalBytes(summarized)))
                .isEqualTo(new Fitted(summarized, null, canonicalBytes(summarized), "spans_summarized"));
        assertThat(summarized.get("spans_summary").toString())
                .isEqualTo("{\"llm_calls\":1,\"other_spans\":1,\"tool_calls\":2}");
    }

    @Test
    void fitDropsHistoryOutputsThenTurnsOldestFirst() {
        var trace = trace("{\"q\":\"and the second one?\"}", "\"done\"");
        var spans = List.of(span(trace, "a", null, 0, "llm"));
        var summarized = pruned(trace, spans, 1);
        var turns = List.of(turn("first", "one"), turn("second", "two"), turn("third", "three"));

        var expected = new ArrayList<ArrayNode>();
        var history = AiLabelRenderer.toHistory(turns);
        for (int i = 0; i < 3; i++) {
            ((ObjectNode) history.get(i)).remove("output");
            expected.add(history.deepCopy());
        }
        for (int i = 0; i < 3; i++) {
            history.remove(0);
            expected.add(history.deepCopy());
        }
        var levels = List.of("history_outputs_dropped:1", "history_outputs_dropped:2", "history_outputs_dropped:3",
                "history_turns:2", "history_turns:1", "history_turns:0");

        for (int i = 0; i < levels.size(); i++) {
            long bytes = canonicalBytes(summarized) + canonicalBytes(expected.get(i));
            assertThat(fit(trace, spans, AiLabelRenderer.toHistory(turns), bytes))
                    .isEqualTo(new Fitted(summarized, expected.get(i), bytes, levels.get(i)));
        }
    }

    @Test
    void fitCutsTheOutputThenTheInputToTheLargestCapThatFits() {
        var fixtures = new AiLabelRenderFixtures(6, false);
        var input = fixtures.text(3_000);
        var output = fixtures.text(5_000);
        var trace = trace("{\"long\":\"" + input + "\",\"short\":\"keep\"}", "\"" + output + "\"");
        long full = canonicalBytes(state(trace, List.of()));
        long outputBudget = full - 3_000;
        long inputBudget = full - 6_500;

        var outputCut = (Fitted) fit(trace, List.of(), null, outputBudget);
        var bothCut = (Fitted) fit(trace, List.of(), null, inputBudget);

        int outputCap = capOf(outputCut, "output_truncated:");
        assertThat(outputCut.bytes()).isLessThanOrEqualTo(outputBudget).isEqualTo(canonicalBytes(outputCut.state()));
        assertThat(outputCut.state().get("output").textValue()).isEqualTo(cut(output, outputCap));
        assertThat(outputCut.state().get("input").get("long").textValue()).isEqualTo(input);
        var longer = outputCut.state().deepCopy().put("output", cut(output, outputCap + 1));
        assertThat(canonicalBytes(longer)).isGreaterThan(outputBudget);

        int inputCap = capOf(bothCut, "input_truncated:");
        assertThat(bothCut.bytes()).isLessThanOrEqualTo(inputBudget).isEqualTo(canonicalBytes(bothCut.state()));
        assertThat(bothCut.state().get("output").textValue()).isEqualTo(cut(output, 64));
        assertThat(bothCut.state().get("input").get("long").textValue()).isEqualTo(cut(input, inputCap));
        assertThat(bothCut.state().get("input").get("short").textValue()).isEqualTo("keep");
    }

    /** The trace is cut only once every earlier turn is gone; without history, steps 2 and 3 never run. */
    @Test
    void fitCutsTheTraceOnlyOnceTheHistoryIsGone() {
        var fixtures = new AiLabelRenderFixtures(7, false);
        var trace = trace("\"" + fixtures.text(100) + "\"", "\"" + fixtures.text(5_000) + "\"");
        var history = AiLabelRenderer.toHistory(List.of(turn("first", "one"), turn("second", "two")));

        var withHistory = (Fitted) fit(trace, List.of(), history, 1_000);
        var withoutHistory = (Fitted) fit(trace, List.of(), null, 1_000);

        assertThat(withHistory.history()).isEmpty();
        assertThat(withHistory.compactionLevel()).startsWith("output_truncated:");
        assertThat(withHistory.bytes()).isEqualTo(canonicalBytes(withHistory.state()) + 2);
        assertThat(withoutHistory.history()).isNull();
        assertThat(withoutHistory.compactionLevel()).startsWith("output_truncated:");
        assertThat(withoutHistory.bytes()).isEqualTo(canonicalBytes(withoutHistory.state()));
    }

    @Test
    void fitIsOverBudgetWhenBothAt64DoNotFit() {
        var fixtures = new AiLabelRenderFixtures(8, false);
        var output = fixtures.text(1_000);
        var trace = trace("{\"items\":[" + "1,".repeat(5_000) + "1]}", "\"" + output + "\"");
        var atFloor = state(trace, List.of()).put("output", cut(output, 64));

        assertThat(fit(trace, List.of(), null, 1_000)).isEqualTo(new OverBudget(canonicalBytes(atFloor)));
    }

    @Test
    void fitNeverSplitsASurrogatePair() {
        String emoji = "😀".repeat(3_000);
        var trace = trace("\"" + emoji + "\"", null);

        var fit = (Fitted) fit(trace, List.of(), null, 2_000);

        int cap = capOf(fit, "input_truncated:");
        assertThat(fit.state().get("input").textValue()).isEqualTo(cut(emoji, cap));
        assertThat(fit.bytes()).isEqualTo(canonicalBytes(fit.state()));
    }

    /**
     * Random trees up to 20 deep and 2,000 spans, in mixed scripts, with and without history, against random budgets:
     * a fitted state is within budget and measured exactly, and nothing is removed from one that already fits.
     */
    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8})
    void fitIsNeverOverBudgetUnlessItSaysSo(long seed) {
        var random = new Random(seed);
        var fixtures = new AiLabelRenderFixtures(seed, true);
        for (int i = 0; i < 8; i++) {
            var trace = fixtures.trace(random.nextInt(20_000));
            var spans = fixtures.spans(trace, 1 + random.nextInt(2_000), 1 + random.nextInt(20), random.nextInt(300));
            var turns = new ArrayList<ThreadTurnRow>();
            for (int turn = random.nextInt(6); turn > 0; turn--) {
                turns.add(turn(fixtures.text(random.nextInt(2_000)), fixtures.text(random.nextInt(2_000))));
            }
            var history = random.nextBoolean() ? AiLabelRenderer.toHistory(turns) : null;
            long full = canonicalBytes(state(trace, spans)) + (history == null ? 0 : canonicalBytes(history));
            long budget = switch (i % 3) {
                case 0 -> full + random.nextInt(1_000);
                case 1 -> (long) (random.nextDouble() * full);
                default -> 1_000 + random.nextInt(10_000);
            };

            var fit = fit(trace, spans, history == null ? null : history.deepCopy(), budget);

            if (fit instanceof OverBudget overBudget) {
                assertThat(overBudget.bytes()).isGreaterThan(budget);
                continue;
            }
            var fitted = (Fitted) fit;
            long historyBytes = fitted.history() == null ? 0 : canonicalBytes(fitted.history());
            assertThat(fitted.bytes()).isLessThanOrEqualTo(budget)
                    .isEqualTo(canonicalBytes(fitted.state()) + historyBytes);
            assertThat(fitted.history() == null).isEqualTo(history == null);
            if (fitted.compactionLevel().equals("full")) {
                assertThat(fitted.state()).isEqualTo(state(trace, spans));
            } else {
                assertThat(full).isGreaterThan(budget);
                assertThat(fitted.compactionLevel()).matches(
                        "spans_pruned:\\d+|spans_summarized|history_outputs_dropped:\\d+|history_turns:\\d+"
                                + "|output_truncated:\\d+|input_truncated:\\d+");
            }
        }
    }

    private static Fit fit(TraceRow trace, List<SpanRow> spans, ArrayNode history, long budget) {
        return AiLabelStateFitter.fit(state(trace, spans), history, summary(spans), budget);
    }

    /** The state with the spans at {@code depth} and below dropped, built independently of the fitter. */
    private static ObjectNode pruned(TraceRow trace, List<SpanRow> spans, int depth) {
        var state = state(trace, spans);
        state.set("spans_summary", summary(spans));
        prune(state, 0, depth);
        return state;
    }

    private static void prune(JsonNode node, int nodeDepth, int depth) {
        if (nodeDepth == depth - 1) {
            ((ObjectNode) node).remove("spans");
            return;
        }
        var spans = node.get("spans");
        if (spans != null) {
            spans.forEach(span -> prune(span, nodeDepth + 1, depth));
        }
    }

    private static String cut(String original, int cap) {
        return original.substring(0, original.offsetByCodePoints(0, cap))
                + "…[" + (original.codePointCount(0, original.length()) - cap) + " chars truncated]…";
    }

    private static int capOf(Fitted fit, String prefix) {
        assertThat(fit.compactionLevel()).startsWith(prefix);
        int cap = Integer.parseInt(fit.compactionLevel().substring(prefix.length()));
        assertThat(cap).isGreaterThanOrEqualTo(64);
        return cap;
    }

    static long canonicalBytes(JsonNode node) {
        return AiLabelRenderer.canonicalJson(node).getBytes(StandardCharsets.UTF_8).length;
    }

    private static ObjectNode state(TraceRow trace, List<SpanRow> spans) {
        return AiLabelRenderer.toState(trace, AiLabelSpanTree.build(new ArrayList<>(spans)));
    }

    private static ObjectNode summary(List<SpanRow> spans) {
        return AiLabelRenderer.spansSummary(AiLabelSpanTree.build(new ArrayList<>(spans)));
    }

    private static TraceRow trace(String input, String output) {
        return TraceRow.builder()
                .id(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .name("t")
                .input(input)
                .output(output)
                .build();
    }

    private static SpanRow span(TraceRow trace, String id, String parentSpanId, int startSecond, String type) {
        return SpanRow.builder()
                .traceId(trace.id())
                .id(id)
                .parentSpanId(parentSpanId)
                .name(id)
                .type(type)
                .input("{\"arg\":\"" + id + " input\"}")
                .output("{\"result\":\"" + id + " output\"}")
                .startTime(AiLabelRenderFixtures.BASE_TIME.plusSeconds(startSecond))
                .build();
    }

    private static ThreadTurnRow turn(String input, String output) {
        return ThreadTurnRow.builder()
                .id(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .threadId("thread")
                .input(input)
                .output(output)
                .build();
    }
}
