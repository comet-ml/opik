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

import static com.comet.opik.domain.ailabels.AiLabelJson.NODE_FACTORY;
import static com.comet.opik.domain.ailabels.AiLabelStateFitter.MIN_CAP;
import static com.comet.opik.domain.ailabels.AiLabelStateFitter.MIN_ITEMS;
import static org.assertj.core.api.Assertions.assertThat;

class AiLabelStateFitterTest {

    private static final long BUDGET = 48L * 1024;

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
    void fitIsOverBudgetOnlyWhenTheStateWithoutItsFieldsDoesNotFit() {
        var trace = trace("{\"q\":\"question\"}", "\"answer\"").toBuilder().name("n".repeat(2_000)).build();

        var fit = fit(trace, List.of(), null, 1_000);

        assertThat(fit).isInstanceOf(OverBudget.class);
        assertThat(((OverBudget) fit).bytes()).isGreaterThan(1_000);
    }

    /** An embedding means nothing to a summary: it's gone from every state, spans included, whether or not it fits. */
    @Test
    void stateDropsEveryEmbedding() {
        var answer = new AiLabelRenderFixtures(11, false).text(500);
        var output = NODE_FACTORY.objectNode().put("answer", answer);
        output.set("embedding", floats(new Random(11), 1_536));
        var vectorWithNaN = floats(new Random(12), 20).add(NODE_FACTORY.nullNode());
        var trace = trace("{\"q\":\"question\"}", output.toString());
        var embed = span(trace, "embed", null, 0, "general").toBuilder()
                .output("{\"vector\":" + vectorWithNaN + ",\"bbox\":[1,2,3,4]}").build();

        var fitted = (Fitted) fit(trace, List.of(embed), null, BUDGET);

        assertThat(fitted.compactionLevel()).isEqualTo("full");
        assertThat(fitted.state().get("output").get("embedding").textValue()).isEqualTo("…[1536 numbers omitted]…");
        assertThat(fitted.state().get("output").get("answer").textValue()).isEqualTo(answer);
        var spanOutput = fitted.state().get("spans").get(0).get("output");
        assertThat(spanOutput.get("vector").textValue()).isEqualTo("…[21 numbers omitted]…");
        assertThat(spanOutput.get("bbox")).hasSize(4);
    }

    /** A knowledge graph: nodes carrying embeddings, text chunks, and edges; bulk that strings at 64 don't remove. */
    @Test
    void fitCapsTheArraysOfAKnowledgeGraphOutput() {
        var random = new Random(12);
        var fixtures = new AiLabelRenderFixtures(12, false);
        var output = NODE_FACTORY.objectNode();
        var nodes = output.putArray("graph_nodes");
        for (int i = 0; i < 2_000; i++) {
            var node = nodes.addObject().put("id", "n" + i).put("label", fixtures.text(12)).put("type", "entity");
            node.set("embedding", floats(random, 384));
        }
        var chunks = output.putArray("chunks");
        for (int i = 0; i < 300; i++) {
            chunks.addObject().put("chunk_id", i).put("text", fixtures.text(800));
        }
        var edges = output.putObject("consolidated_graph").putArray("edges");
        for (int i = 0; i < 3_000; i++) {
            edges.addObject().put("from", "n" + i % 2_000).put("to", "n" + (i * 7) % 2_000).put("rel", "links");
        }
        var trace = trace("{\"query\":\"" + fixtures.text(200) + "\"}", output.toString());

        var fitted = fitTwice(trace, BUDGET);

        int items = capOf(fitted, "arrays_capped:", MIN_ITEMS);
        var fittedOutput = fitted.state().get("output");
        assertThat(fittedOutput.get("graph_nodes")).hasSize(items + 1);
        assertThat(fittedOutput.get("graph_nodes").get(0).get("embedding").textValue())
                .isEqualTo("…[384 numbers omitted]…");
        assertThat(fittedOutput.get("graph_nodes").get(0).get("label").textValue()).hasSize(12);
        assertThat(fittedOutput.get("graph_nodes").get(2).textValue()).isEqualTo("…[" + (2_000 - items)
                + " items truncated]…");
        assertThat(fittedOutput.get("chunks").get(items).get("text").textValue()).hasSize(800);
        assertThat(fitted.state().get("input")).isEqualTo(state(trace, List.of()).get("input"));
    }

    /** A large nested output object of many keys, beside a long retrieval context. */
    @Test
    void fitCapsTheArraysOfALargeNestedOutputWithAContext() {
        var fixtures = new AiLabelRenderFixtures(13, false);
        var output = NODE_FACTORY.objectNode();
        for (int key = 0; key < 15; key++) {
            var section = output.putObject("section_" + key);
            section.put("title", fixtures.text(40));
            var entries = section.putArray("entries");
            for (int i = 0; i < 150; i++) {
                entries.addObject().put("name", fixtures.text(8)).put("score", i).put("ok", i % 2 == 0);
            }
        }
        var context = output.putArray("context");
        for (int i = 0; i < 400; i++) {
            context.addObject().put("source", "doc-" + i).put("snippet", fixtures.text(60));
        }
        var trace = trace("{\"request\":\"" + fixtures.text(300) + "\"}", output.toString());
        assertThat(canonicalBytes(state(trace, List.of()).get("output"))).isGreaterThan(100 * 1024);

        var fitted = fitTwice(trace, BUDGET);

        int items = capOf(fitted, "arrays_capped:", MIN_ITEMS);
        var fittedOutput = fitted.state().get("output");
        assertThat(fittedOutput.properties()).hasSize(16);
        assertThat(fittedOutput.get("context").get(items).get("source").textValue()).isEqualTo("doc-399");
        assertThat(fittedOutput.get("section_14").get("title").textValue()).hasSize(40);
    }

    /** A long chat: the newest turns carry the intent, so the last message is kept whole. */
    @Test
    void fitKeepsTheNewestMessagesOfALongChatInput() {
        var fixtures = new AiLabelRenderFixtures(14, false);
        var input = NODE_FACTORY.objectNode();
        var messages = input.putArray("messages");
        messages.addObject().put("role", "system").put("content", fixtures.text(2_000));
        for (int i = 1; i < 2_000; i++) {
            messages.addObject().put("role", i % 2 == 1 ? "user" : "assistant").put("content", fixtures.text(250));
        }
        var last = messages.get(1_999).deepCopy();
        var trace = trace(input.toString(), "{\"reply\":\"" + fixtures.text(300) + "\"}");

        var fitted = fitTwice(trace, BUDGET);

        int items = capOf(fitted, "arrays_capped:", MIN_ITEMS);
        var fittedMessages = fitted.state().get("input").get("messages");
        assertThat(fittedMessages).hasSize(items + 1);
        assertThat(fittedMessages.get(0)).isEqualTo(messages.get(0));
        assertThat(fittedMessages.get(2).textValue()).isEqualTo("…[" + (2_000 - items) + " items truncated]…");
        assertThat(fittedMessages.get(items)).isEqualTo(last);
        assertThat(items).isGreaterThan(100);
        assertThat(fitted.state().get("output")).isEqualTo(state(trace, List.of()).get("output"));
    }

    @Test
    void fitCapsNestedArraysAtEveryLevel() {
        var output = NODE_FACTORY.arrayNode();
        for (int row = 0; row < 300; row++) {
            var cells = output.addArray();
            for (int cell = 0; cell < 300; cell++) {
                cells.add("r" + row + "c" + cell);
            }
        }
        var trace = trace("\"question\"", output.toString());

        var fitted = fitTwice(trace, 20_000);

        int items = capOf(fitted, "arrays_capped:", MIN_ITEMS);
        var rows = fitted.state().get("output");
        assertThat(rows).hasSize(items + 1);
        assertThat(rows.get(items).get(items).textValue()).isEqualTo("r299c299");
        assertThat(rows.get(0).get(2).textValue()).isEqualTo("…[" + (300 - items) + " items truncated]…");
    }

    @Test
    void fitCutsLongStringsBesideAnEmbedding() {
        var random = new Random(15);
        var fixtures = new AiLabelRenderFixtures(15, false);
        var text = fixtures.text(20_000);
        var output = NODE_FACTORY.objectNode().put("text", text);
        output.set("embedding", floats(random, 3_072));
        var trace = trace("{\"q\":\"question\"}", output.toString());

        var fitted = fitTwice(trace, 8_000);

        int cap = capOf(fitted, "output_truncated:", MIN_CAP);
        assertThat(fitted.state().get("output").get("text").textValue()).isEqualTo(cut(text, cap));
        assertThat(fitted.state().get("output").get("embedding").textValue())
                .isEqualTo("…[3072 numbers omitted]…");
    }

    /** Thousands of keys of short values: no array or string to cut, so the output becomes cut JSON text. */
    @Test
    void fitTurnsAWideObjectIntoTextAsALastResort() {
        var output = NODE_FACTORY.objectNode();
        for (int i = 0; i < 10_000; i++) {
            output.put("key_%05d".formatted(i), i);
        }
        var trace = trace("{\"q\":\"question\"}", output.toString());

        var fitted = fitTwice(trace, BUDGET);

        assertThat(fitted.compactionLevel()).isEqualTo("output_as_text");
        var text = fitted.state().get("output").textValue();
        assertThat(text).startsWith("{\"key_00000\":0,").endsWith(",\"key_09999\":9999}")
                .contains(" chars truncated]…");
        assertThat(fitted.state().get("input")).isEqualTo(state(trace, List.of()).get("input"));
    }

    /** The larger field becomes text first, so a short answer beside a huge input is kept. */
    @Test
    void fitTurnsTheLargerFieldIntoTextFirst() {
        var input = NODE_FACTORY.objectNode();
        for (int i = 0; i < 10_000; i++) {
            input.put("key_%05d".formatted(i), i);
        }
        var trace = trace(input.toString(), "{\"reply\":\"The refund for order 123 was approved.\"}");

        var fitted = fitTwice(trace, BUDGET);

        assertThat(fitted.compactionLevel()).isEqualTo("input_as_text");
        assertThat(fitted.state().get("input").textValue()).startsWith("{\"key_00000\":0,");
        assertThat(fitted.state().get("output")).isEqualTo(state(trace, List.of()).get("output"));
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
                assertThat(fitted.compactionLevel()).matches("arrays_capped:\\d+|spans_pruned:\\d+|spans_summarized"
                        + "|history_outputs_dropped:\\d+|history_turns:\\d+|output_truncated:\\d+|input_truncated:\\d+"
                        + "|truncated_arrays_capped:\\d+|output_as_text|input_as_text|fields_as_text");
                assertThat(AiLabelsTickService.levelOf(fitted.compactionLevel())).matches("[a-z_]+");
            }
        }
    }

    /** Fits a fresh state twice: the same bytes both times, within budget and measured exactly. */
    private static Fitted fitTwice(TraceRow trace, long budget) {
        var first = (Fitted) fit(trace, List.of(), null, budget);
        var second = (Fitted) fit(trace, List.of(), null, budget);

        assertThat(AiLabelRenderer.canonicalJson(second.state()))
                .isEqualTo(AiLabelRenderer.canonicalJson(first.state()));
        assertThat(second.compactionLevel()).isEqualTo(first.compactionLevel());
        assertThat(first.bytes()).isLessThanOrEqualTo(budget).isEqualTo(canonicalBytes(first.state()));
        return first;
    }

    private static ArrayNode floats(Random random, int size) {
        var vector = NODE_FACTORY.arrayNode();
        for (int i = 0; i < size; i++) {
            vector.add(random.nextGaussian());
        }
        return vector;
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
        return capOf(fit, prefix, MIN_CAP);
    }

    private static int capOf(Fitted fit, String prefix, int floor) {
        assertThat(fit.compactionLevel()).startsWith(prefix);
        int cap = Integer.parseInt(fit.compactionLevel().substring(prefix.length()));
        assertThat(cap).isGreaterThanOrEqualTo(floor);
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
