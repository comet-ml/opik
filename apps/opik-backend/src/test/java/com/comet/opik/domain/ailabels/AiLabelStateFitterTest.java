package com.comet.opik.domain.ailabels;

import com.comet.opik.domain.ailabels.AiLabelRenderDAO.SpanRow;
import com.comet.opik.domain.ailabels.AiLabelRenderDAO.TraceRow;
import com.comet.opik.domain.ailabels.AiLabelStateFitter.Fitted;
import com.comet.opik.domain.ailabels.AiLabelStateFitter.OverBudget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class AiLabelStateFitterTest {

    private static final Pattern MARKER = Pattern.compile("…\\[(\\d+) chars truncated]…");

    @Test
    void sizeOfMatchesTheCanonicalBytesForEveryScript() {
        var fixtures = new AiLabelRenderFixtures(42, true);
        for (int i = 0; i < 50; i++) {
            var trace = fixtures.trace(200);
            var state = state(trace, fixtures.spans(trace, 40, 6, 100));

            assertThat(AiLabelStateFitter.sizeOf(state)).isEqualTo(canonicalBytes(state));
        }
    }

    @Test
    void sizeOfCountsLoneSurrogatesAsTheyAreEncoded() {
        var trace = TraceRow.builder()
                .id(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .input("{\"\\ud800key\": \"\\udc00 value \\ud83d\\ude00\", \"x\": [1e400, -0.0, 12345678901234567890123]}")
                .build();
        var state = AiLabelRenderer.toState(trace, List.of());

        assertThat(state.get("input").get("\ud800key").textValue()).isEqualTo("\uFFFD value \uD83D\uDE00");
        assertThat(AiLabelStateFitter.sizeOf(state)).isEqualTo(canonicalBytes(state));
    }

    @Test
    void fitLeavesAStateWithinBudgetWhole() {
        var fixtures = new AiLabelRenderFixtures(1, true);
        var trace = fixtures.trace(100);
        var state = state(trace, fixtures.spans(trace, 20, 4, 50));
        var expected = state.deepCopy();
        long bytes = canonicalBytes(state);

        var fit = AiLabelStateFitter.fit(state, bytes);

        assertThat(fit).isEqualTo(new Fitted(expected, bytes, "full"));
    }

    @Test
    void fitDropsSpanIoFromTheDeepestLevelUp() {
        var trace = trace("t", "{\"q\":\"question\"}");
        var spans = List.of(
                span(trace, "a", null, 0),
                span(trace, "a1", "a", 1),
                span(trace, "a11", "a1", 2),
                span(trace, "b", null, 3));
        long full = canonicalBytes(state(trace, spans));
        long withoutDepth3 = canonicalBytes(pruned(state(trace, spans), 3));
        long withoutDepth2 = canonicalBytes(pruned(state(trace, spans), 2));
        long withoutDepth1 = canonicalBytes(pruned(state(trace, spans), 1));
        assertThat(List.of(full, withoutDepth3, withoutDepth2, withoutDepth1))
                .isSortedAccordingTo((x, y) -> y.compareTo(x))
                .doesNotHaveDuplicates();

        assertFits(trace, spans, full - 1, pruned(state(trace, spans), 3), withoutDepth3, "spans_io_pruned:3");
        assertFits(trace, spans, withoutDepth3, pruned(state(trace, spans), 3), withoutDepth3, "spans_io_pruned:3");
        assertFits(trace, spans, withoutDepth3 - 1, pruned(state(trace, spans), 2), withoutDepth2,
                "spans_io_pruned:2");
        assertFits(trace, spans, withoutDepth1, pruned(state(trace, spans), 1), withoutDepth1, "spans_io_pruned:1");
    }

    @Test
    void fitCutsSpanStringsBeforeTraceStrings() {
        var fixtures = new AiLabelRenderFixtures(5, false);
        var trace = trace(fixtures.text(1_000), "\"" + fixtures.text(5_000) + "\"");
        var spans = List.of(span(trace, "a", null, 0).toBuilder().name(fixtures.text(20_000)).build());
        long budget = canonicalBytes(pruned(state(trace, spans), 1)) - 10_000;

        var fit = (Fitted) AiLabelStateFitter.fit(state(trace, spans), budget);

        int cap = capOf(fit, "truncated_spans:");
        assertThat(fit.bytes()).isLessThanOrEqualTo(budget).isEqualTo(canonicalBytes(fit.state()));
        assertThat(fit.state().get("name").textValue()).isEqualTo(trace.name());
        assertThat(fit.state().get("input").textValue()).hasSize(5_000);
        assertCut(fit.state().get("spans").get(0).get("name").textValue(), spans.getFirst().name(), cap);
        // The largest cap that fits: one more code point would not.
        var longer = pruned(state(trace, spans), 1);
        ((ObjectNode) longer.get("spans").get(0)).put("name", cut(spans.getFirst().name(), cap + 1));
        assertThat(canonicalBytes(longer)).isGreaterThan(budget);
    }

    @Test
    void fitCutsTraceStringsOnceSpanStringsAtTheFloorAreNotEnough() {
        var fixtures = new AiLabelRenderFixtures(6, false);
        var trace = trace(fixtures.text(3_000), "{\"long\":\"" + fixtures.text(30_000) + "\",\"short\":\"keep\"}");
        var spans = List.of(span(trace, "a", null, 0).toBuilder().name(fixtures.text(2_000)).build());
        long budget = 5_000;

        var fit = (Fitted) AiLabelStateFitter.fit(state(trace, spans), budget);

        int cap = capOf(fit, "truncated:");
        assertThat(fit.bytes()).isLessThanOrEqualTo(budget).isEqualTo(canonicalBytes(fit.state()));
        assertCut(fit.state().get("spans").get(0).get("name").textValue(), spans.getFirst().name(), 64);
        assertCut(fit.state().get("input").get("long").textValue(), trace.input().substring(9, 30_009), cap);
        assertCut(fit.state().get("name").textValue(), trace.name(), cap);
        assertThat(fit.state().get("input").get("short").textValue()).isEqualTo("keep");
    }

    @Test
    void fitIsOverBudgetWhenStringsAtTheFloorDoNotFit() {
        var trace = trace("t", "{\"items\":[" + "1,".repeat(5_000) + "1]}");
        var state = state(trace, List.of());
        long bytes = canonicalBytes(state);

        assertThat(AiLabelStateFitter.fit(state, 1_000)).isEqualTo(new OverBudget(bytes));
    }

    @Test
    void fitNeverSplitsASurrogatePair() {
        String emoji = "\uD83D\uDE00".repeat(3_000);
        var trace = trace("t", "\"" + emoji + "\"");

        var fit = (Fitted) AiLabelStateFitter.fit(state(trace, List.of()), 2_000);

        int cap = capOf(fit, "truncated:");
        assertCut(fit.state().get("input").textValue(), emoji, cap);
        assertThat(
                fit.state().get("input").textValue().chars().filter(c -> Character.isSurrogate((char) c)).count() % 2)
                .isZero();
    }

    /**
     * Random trees up to 20 deep and 2,000 spans, in mixed scripts, against random budgets: a fitted state is within
     * budget and measured exactly, and its level is the first step that fits.
     */
    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8})
    void fitIsNeverOverBudgetUnlessItSaysSo(long seed) {
        var random = new Random(seed);
        var fixtures = new AiLabelRenderFixtures(seed, true);
        for (int i = 0; i < 8; i++) {
            var trace = fixtures.trace(random.nextInt(20_000));
            var spans = fixtures.spans(trace, 1 + random.nextInt(2_000), 1 + random.nextInt(20), random.nextInt(300));
            long full = canonicalBytes(state(trace, spans));
            long pruned = canonicalBytes(pruned(state(trace, spans), 1));
            // Aimed at each step in turn: whole, span I/O dropped, span strings cut, everything cut.
            long budget = switch (i % 4) {
                case 0 -> full + random.nextInt(1_000);
                case 1 -> pruned + (long) (random.nextDouble() * (full - pruned));
                case 2 -> pruned / 2 + (long) (random.nextDouble() * pruned / 2);
                default -> 1_000 + random.nextInt(10_000);
            };

            var fit = AiLabelStateFitter.fit(state(trace, spans), budget);

            if (fit instanceof OverBudget overBudget) {
                assertThat(overBudget.bytes()).isGreaterThan(budget);
                continue;
            }
            var fitted = (Fitted) fit;
            assertThat(fitted.bytes()).isLessThanOrEqualTo(budget).isEqualTo(canonicalBytes(fitted.state()));
            assertFirstStepThatFits(trace, spans, budget, fitted);
        }
    }

    private static void assertFirstStepThatFits(TraceRow trace, List<SpanRow> spans, long budget, Fitted fitted) {
        String level = fitted.compactionLevel();
        if (level.equals("full")) {
            return;
        }
        assertThat(canonicalBytes(state(trace, spans))).isGreaterThan(budget);
        if (level.startsWith("spans_io_pruned:")) {
            int depth = Integer.parseInt(level.substring("spans_io_pruned:".length()));
            assertThat(fitted.state()).isEqualTo(pruned(state(trace, spans), depth));
            assertThat(canonicalBytes(pruned(state(trace, spans), depth + 1))).isGreaterThan(budget);
            return;
        }
        assertThat(canonicalBytes(pruned(state(trace, spans), 1))).isGreaterThan(budget);
        assertThat(level).matches("truncated(_spans)?:\\d+");
        if (level.startsWith("truncated_spans:")) {
            var traceLevel = fitted.state().deepCopy();
            var expectedTraceLevel = pruned(state(trace, spans), 1);
            traceLevel.remove("spans");
            expectedTraceLevel.remove("spans");
            assertThat(traceLevel).isEqualTo(expectedTraceLevel);
        }
        assertThat(spanNodes(fitted.state()))
                .allSatisfy(span -> assertThat(span.has("input") || span.has("output") || span.has("metadata"))
                        .isFalse());
    }

    private static void assertFits(TraceRow trace, List<SpanRow> spans, long budget, ObjectNode expectedState,
            long expectedBytes, String expectedLevel) {
        assertThat(AiLabelStateFitter.fit(state(trace, spans), budget))
                .isEqualTo(new Fitted(expectedState, expectedBytes, expectedLevel));
    }

    private static void assertCut(String actual, String original, int cap) {
        assertThat(actual).isEqualTo(cut(original, cap));
        assertThat(MARKER.matcher(actual).results().count()).isEqualTo(1);
    }

    private static String cut(String original, int cap) {
        int head = cap * 7 / 10;
        return original.substring(0, original.offsetByCodePoints(0, head))
                + "…[" + (original.codePointCount(0, original.length()) - cap) + " chars truncated]…"
                + original.substring(original.offsetByCodePoints(original.length(), head - cap));
    }

    private static int capOf(Fitted fit, String prefix) {
        assertThat(fit.compactionLevel()).startsWith(prefix);
        int cap = Integer.parseInt(fit.compactionLevel().substring(prefix.length()));
        assertThat(cap).isGreaterThanOrEqualTo(64);
        return cap;
    }

    /** The state with span I/O dropped at {@code shallowestDepth} and below, built independently of the fitter. */
    private static ObjectNode pruned(ObjectNode state, int shallowestDepth) {
        prune(state.get("spans"), 1, shallowestDepth);
        return state;
    }

    private static void prune(JsonNode spans, int depth, int shallowestDepth) {
        if (spans == null) {
            return;
        }
        for (JsonNode span : spans) {
            if (depth >= shallowestDepth) {
                ((ObjectNode) span).remove(List.of("input", "output", "metadata"));
            }
            prune(span.get("spans"), depth + 1, shallowestDepth);
        }
    }

    private static List<JsonNode> spanNodes(JsonNode node) {
        var spans = node.get("spans");
        if (spans == null) {
            return List.of();
        }
        return StreamSupport.stream(spans.spliterator(), false)
                .flatMap(span -> Stream.concat(Stream.of(span), spanNodes(span).stream()))
                .toList();
    }

    static long canonicalBytes(JsonNode state) {
        return AiLabelRenderer.canonicalJson(state).getBytes(StandardCharsets.UTF_8).length;
    }

    private static ObjectNode state(TraceRow trace, List<SpanRow> spans) {
        return AiLabelRenderer.toState(trace, AiLabelSpanTree.build(new ArrayList<>(spans)));
    }

    private static TraceRow trace(String name, String input) {
        return TraceRow.builder()
                .id(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .name(name)
                .input(input)
                .startTime(AiLabelRenderFixtures.BASE_TIME)
                .build();
    }

    private static SpanRow span(TraceRow trace, String id, String parentSpanId, int startSecond) {
        return SpanRow.builder()
                .traceId(trace.id())
                .id(id)
                .parentSpanId(parentSpanId)
                .name(id)
                .type("tool")
                .input("{\"arg\":\"" + id + " input\"}")
                .output("{\"result\":\"" + id + " output\"}")
                .metadata("{\"k\":\"" + id + "\"}")
                .startTime(AiLabelRenderFixtures.BASE_TIME.plusSeconds(startSecond))
                .build();
    }
}
