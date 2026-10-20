package com.comet.opik.domain.ailabels;

import com.comet.opik.api.InstantToUUIDMapper;
import com.comet.opik.domain.ailabels.AiLabelRenderDAO.SelectedSpan;
import com.comet.opik.domain.ailabels.AiLabelRenderDAO.SpanRow;
import com.comet.opik.domain.ailabels.AiLabelRenderDAO.ThreadTurnRow;
import com.comet.opik.domain.ailabels.AiLabelRenderDAO.TraceRow;
import com.comet.opik.infrastructure.AiLabelsConfig;
import com.comet.opik.utils.JsonUtils;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.domain.ailabels.AiLabelTaskFixtures.decide;
import static com.comet.opik.domain.ailabels.AiLabelTaskFixtures.generateWithHistory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiLabelRendererTest {

    /**
     * The fingerprint of each renderer version's golden files. Released versions are never edited: a change to the
     * output bumps {@link AiLabelRenderer#RENDERER_VERSION}, adds golden files for the new version, and adds its entry
     * here.
     */
    private static final Map<Integer, String> GOLDEN_FINGERPRINTS = Map.of(
            1, "2e107dc51185d3a04a30a48cd8b1b308ab3054b8ecb19a4dfa9dd86dc3dc788b");

    private static final String GOLDEN_DIR = "src/test/resources/ai-labels/renderer/v%d"
            .formatted(AiLabelRenderer.RENDERER_VERSION);

    /** Writes the golden files of the current version instead of checking them, for a new renderer version. */
    private static final boolean WRITE_GOLDEN = Boolean.getBoolean("ai-labels.renderer.write-golden");

    private static final String WORKSPACE_ID = "workspace-1";
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    /** A chunk: the traces and spans the reads return, and the earlier turns of their threads. */
    private record Chunk(List<TraceRow> traces, List<SpanRow> spans, List<ThreadTurnRow> turns) {
    }

    private record GoldenCase(String name, Chunk chunk, long budget) {
    }

    static Stream<Arguments> goldenCases() {
        return goldenCaseList().stream().map(goldenCase -> Arguments.of(goldenCase.name(), goldenCase));
    }

    private static List<GoldenCase> goldenCaseList() {
        var small = new AiLabelRenderFixtures(101, false);
        var smallTrace = small.trace(40);

        var deep = new AiLabelRenderFixtures(102, false);
        var deepTrace = deep.trace(100);
        var deepSpans = new ArrayList<SpanRow>();
        for (int i = 0; i < 20; i++) {
            deepSpans.add(deep.span(deepTrace, deepSpans.isEmpty() ? null : deepSpans.getLast().id(), 60));
        }

        var wide = new AiLabelRenderFixtures(103, false);
        var wideTrace = wide.trace(60);
        var wideRoot = wide.span(wideTrace, null, 20);
        var wideSpans = new ArrayList<SpanRow>(List.of(wideRoot));
        for (int i = 0; i < 300; i++) {
            wideSpans.add(wide.span(wideTrace, wideRoot.id(), 20));
        }

        var multiByte = new AiLabelRenderFixtures(104, true);
        var multiByteTrace = multiByte.trace(2_000);

        var huge = new AiLabelRenderFixtures(105, true);
        // Input as the SQL guard swaps in an outsized field.
        var hugeTrace = huge.trace(10).toBuilder()
                .input("\"[2097152 bytes omitted]\"")
                .output(JsonUtils.getMapper().valueToTree(huge.text(300_000)).toString())
                .build();
        var hugeSpans = new ArrayList<>(huge.spans(hugeTrace, 3, 2, 50));
        hugeSpans.set(1, hugeSpans.get(1).toBuilder().output("\"[1048577 bytes omitted]\"").build());

        return List.of(
                alone("small", smallTrace, small.spans(smallTrace, 5, 3, 30), 80_000),
                alone("deep", deepTrace, deepSpans, 9_000),
                alone("wide", wideTrace, wideSpans, 120_000),
                alone("multi_byte", multiByteTrace, multiByte.spans(multiByteTrace, 30, 4, 500), 12_000),
                alone("huge_field", hugeTrace, hugeSpans, 8_000),
                new GoldenCase("thread", thread(new AiLabelRenderFixtures(106, true)), 80_000),
                new GoldenCase("thread_outputs_dropped", thread(new AiLabelRenderFixtures(106, true)), 6_000));
    }

    private static GoldenCase alone(String name, TraceRow trace, List<SpanRow> spans, long budget) {
        return new GoldenCase(name, new Chunk(List.of(trace), spans, List.of()), budget);
    }

    /**
     * The last turn of a thread: four earlier turns, one with a multi-byte field over the history cap and one with an
     * outsized field as the SQL guard swaps it in.
     */
    private static Chunk thread(AiLabelRenderFixtures fixtures) {
        var trace = withThread(fixtures.trace(300), "thread-1", 10);
        var turns = List.of(
                turn(trace, 1, fixtures.json(200), fixtures.json(4_000)),
                turn(trace, 2, "\"[2097152 bytes omitted]\"", fixtures.json(300)),
                turn(trace, 3, fixtures.json(100), null),
                turn(trace, 4, fixtures.json(150), fixtures.json(1_000)));
        return new Chunk(List.of(trace), fixtures.spans(trace, 6, 3, 400), turns);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("goldenCases")
    void renderMatchesTheGoldenFileOfTheCurrentVersion(String name, GoldenCase goldenCase) throws IOException {
        var task = goldenCase.chunk().turns().isEmpty() ? decide("a", false, 1) : generateWithHistory("summary");
        var actual = golden(renderChunk(goldenCase.chunk(), goldenCase.budget(), List.of(task)).getLast());
        var file = Path.of(GOLDEN_DIR, name + ".json");
        if (WRITE_GOLDEN) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, actual);
            return;
        }

        assertThat(file).as("golden file of renderer version '%d'", AiLabelRenderer.RENDERER_VERSION).exists();
        assertThat(actual)
                .as("""
                        the rendered output changed: bump AiLabelRenderer.RENDERER_VERSION and add golden files for \
                        the new version (-Dai-labels.renderer.write-golden=true), never edit a released version's""")
                .isEqualTo(Files.readString(file));
    }

    @Test
    void goldenFilesOfTheCurrentVersionAreTheReleasedOnes() {
        var fingerprint = goldenCaseList().stream()
                .map(goldenCase -> Path.of(GOLDEN_DIR, goldenCase.name() + ".json"))
                .map(AiLabelRendererTest::read)
                .reduce("", (left, right) -> DigestUtils.sha256Hex(left + right));

        assertThat(fingerprint)
                .as("""
                        golden files of a released renderer version must not change; a new output needs a new \
                        RENDERER_VERSION and a new fingerprint entry""")
                .isEqualTo(GOLDEN_FINGERPRINTS.get(AiLabelRenderer.RENDERER_VERSION));
    }

    /** The same content renders to the same bytes whatever order the spans and the earlier turns are read in. */
    @ParameterizedTest
    @ValueSource(longs = {1_000_000, 100_000, 20_000})
    void renderIsByteIdenticalForTheSameContent(long budget) {
        var fixtures = new AiLabelRenderFixtures(9, true);
        var trace = withThread(fixtures.trace(3_000), "thread-1", 30);
        var spans = fixtures.spans(trace, 400, 12, 200);
        var turns = new ArrayList<ThreadTurnRow>();
        for (int i = 1; i < 30; i++) {
            turns.add(turn(trace, i, fixtures.json(300), fixtures.json(600)));
        }
        var tasks = List.of(decide("a", false, 1), generateWithHistory("summary"));
        var expected = renderChunk(new Chunk(List.of(trace), spans, turns), budget, tasks).stream()
                .map(AiLabelRendererTest::golden)
                .toList();

        var random = new Random(3);
        for (int i = 0; i < 5; i++) {
            var shuffledSpans = new ArrayList<>(spans);
            var shuffledTurns = new ArrayList<>(turns);
            Collections.shuffle(shuffledSpans, random);
            Collections.shuffle(shuffledTurns, random);
            assertThat(renderChunk(new Chunk(List.of(trace), shuffledSpans, shuffledTurns), budget, tasks).stream()
                    .map(AiLabelRendererTest::golden)
                    .toList()).isEqualTo(expected);
        }
    }

    @Test
    void renderReturnsOverBudgetWithoutAState() {
        var fixtures = new AiLabelRenderFixtures(10, false);
        var trace = fixtures.trace(10).toBuilder().input("[" + "1,".repeat(50_000) + "1]").build();
        var ref = AiLabelRenderFixtures.refOf(trace);

        var outcome = AiLabelRenderer.render(ref, trace, List.of(), null, 80_000);

        assertThat(outcome).isInstanceOf(RenderOutcome.OverBudget.class);
        assertThat(outcome.trace()).isEqualTo(ref);
        assertThat(((RenderOutcome.OverBudget) outcome).bytes()).isGreaterThan(80_000);
    }

    /** Wrapper spans repeat their parent's input or output, which is said once. */
    @Test
    void spanInputOrOutputEqualToItsParentsIsLeftOut() {
        var trace = TraceRow.builder().id(UUID.randomUUID()).projectId(UUID.randomUUID())
                .input("{\"q\":\"question\"}").output("\"answer\"").build();
        var agent = span(trace, "agent", null, "{ \"q\": \"question\" }", "\"answer\"");
        var llm = span(trace, "llm", "agent", "{\"q\":\"question\"}", "\"draft\"");

        var state = ((RenderOutcome.Rendered) AiLabelRenderer.render(AiLabelRenderFixtures.refOf(trace), trace,
                List.of(agent, llm), null, 80_000)).state();

        assertThat(AiLabelRenderer.canonicalJson(state.get("spans"))).isEqualTo(
                "[{\"name\":\"agent\",\"spans\":[{\"name\":\"llm\",\"output\":\"draft\"}]}]");
    }

    /**
     * Of the turns read, a trace gets the earlier ones of its own project and thread within the lookback, the newest
     * K of them, oldest first; the traces of one chunk see each other, and a trace without a thread gets none.
     */
    @Test
    void historyIsTheNewestEarlierTurnsOfTheSameThread() {
        var fixtures = new AiLabelRenderFixtures(11, false);
        var config = config();
        config.getHistory().setTurns(3);
        config.getHistory().setLookback(io.dropwizard.util.Duration.hours(1));
        var turn5 = withThread(fixtures.trace(10), "thread-1", 120);
        var turn6 = withThread(fixtures.trace(10), "thread-1", 121).toBuilder().projectId(turn5.projectId()).build();
        var unthreaded = fixtures.trace(10);
        var otherThread = withThread(fixtures.trace(10), "thread-2", 122).toBuilder()
                .projectId(turn5.projectId()).build();
        var turns = new ArrayList<ThreadTurnRow>();
        // Minutes after NOW: 0 is more than the lookback before turn 5, the rest are within it.
        for (int minute : List.of(0, 90, 100, 110, 115)) {
            turns.add(turn(turn5, minute, "\"turn at " + minute + "\"", null));
        }
        turns.add(turn(turn5, 116, "\"other project\"", null).toBuilder().projectId(UUID.randomUUID()).build());
        turns.add(turn(turn5, 117, "\"other thread\"", null).toBuilder().threadId("thread-2").build());
        turns.add(turn(turn5, 120, "\"turn 5\"", null).toBuilder().id(turn5.id()).build());
        var chunk = new Chunk(List.of(turn5, turn6, unthreaded, otherThread), List.of(), turns);

        var histories = renderChunk(chunk, 80_000, List.of(generateWithHistory("summary")), config).stream()
                .collect(Collectors.toMap(outcome -> outcome.trace().traceId(),
                        outcome -> AiLabelRenderer.canonicalJson(((RenderOutcome.Rendered) outcome).history())));

        assertThat(histories).isEqualTo(Map.of(
                turn5.id(), "[{\"input\":\"\\\"turn at 100\\\"\"},{\"input\":\"\\\"turn at 110\\\"\"},"
                        + "{\"input\":\"\\\"turn at 115\\\"\"}]",
                turn6.id(), "[{\"input\":\"\\\"turn at 110\\\"\"},{\"input\":\"\\\"turn at 115\\\"\"},"
                        + "{\"input\":\"\\\"turn 5\\\"\"}]",
                unthreaded.id(), "[]",
                otherThread.id(), "[{\"input\":\"\\\"other thread\\\"\"}]"));
    }

    @Test
    void eachKindOfTaskGetsItsOwnOutcomeAndHistoryIsReadOnlyForTasksThatUseIt() {
        var fixtures = new AiLabelRenderFixtures(12, false);
        var trace = withThread(fixtures.trace(10), "thread-1", 5);
        var chunk = new Chunk(List.of(trace), List.of(), List.of(turn(trace, 1, "\"hi\"", "\"hello\"")));
        var renderDAO = renderDAO(chunk);
        var renderer = new AiLabelRenderer(renderDAO, config(), new InstantToUUIDMapper());

        var alone = renderer.render(WORKSPACE_ID, refs(chunk), List.of(decide("a", false, 1)))
                .collectList().block();
        verify(renderDAO, never()).findThreadTurns(anyString(), any(), any(), any(), any());
        var both = renderer.render(WORKSPACE_ID, refs(chunk),
                List.of(decide("a", false, 1), generateWithHistory("summary"))).collectList().block();

        assertThat(alone).extracting(RenderOutcome::withHistory).containsExactly(false);
        assertThat(both).extracting(RenderOutcome::withHistory).containsExactlyInAnyOrder(false, true);
        assertThat(both).filteredOn(RenderOutcome::withHistory)
                .extracting(outcome -> ((RenderOutcome.Rendered) outcome).history().size())
                .containsExactly(1);
    }

    static Stream<Arguments> capMiddleCases() {
        return Stream.of(
                Arguments.of("ascii and two-byte", "a".repeat(100) + "é".repeat(500) + "z".repeat(100), 200,
                        "a".repeat(100) + "é".repeat(10) + "…[538 chars truncated]…" + "z".repeat(52)),
                Arguments.of("four-byte", "😀".repeat(100), 64,
                        "😀".repeat(6) + "…[91 chars truncated]…" + "😀".repeat(3)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("capMiddleCases")
    void capMiddleKeepsTheStartAndEndOfAFieldOverTheCap(String name, String text, int maxBytes, String expected) {
        var capped = AiLabelRenderer.capMiddle(text, maxBytes);

        assertThat(capped).isEqualTo(expected);
        assertThat(capped.getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(maxBytes);
    }

    @Test
    void capMiddleLeavesAFieldWithinTheCapAsIs() {
        var text = "a".repeat(100) + "é".repeat(500) + "z".repeat(100);

        assertThat(AiLabelRenderer.capMiddle(text, 1_300)).isSameAs(text);
    }

    private static List<RenderOutcome> renderChunk(Chunk chunk, long budget, Collection<AiLabelTask> tasks) {
        return renderChunk(chunk, budget, tasks, config());
    }

    /** Outcomes in the chunk's trace order, the state alone before the state with history. */
    private static List<RenderOutcome> renderChunk(Chunk chunk, long budget, Collection<AiLabelTask> tasks,
            AiLabelsConfig config) {
        config.setRenderBudgetBytes(budget);
        var order = chunk.traces().stream().map(TraceRow::id).toList();
        return new AiLabelRenderer(renderDAO(chunk), config, new InstantToUUIDMapper())
                .render(WORKSPACE_ID, refs(chunk), tasks)
                .collectList()
                .block()
                .stream()
                .sorted(Comparator.<RenderOutcome>comparingInt(outcome -> order.indexOf(outcome.trace().traceId()))
                        .thenComparing(RenderOutcome::withHistory))
                .toList();
    }

    private static AiLabelRenderDAO renderDAO(Chunk chunk) {
        var renderDAO = mock(AiLabelRenderDAO.class);
        var spansByTrace = chunk.spans().stream().collect(Collectors.groupingBy(SpanRow::traceId));
        when(renderDAO.findTraces(anyString(), any())).thenReturn(Flux.fromIterable(chunk.traces()));
        when(renderDAO.findSelectedSpans(anyString(), any())).thenReturn(Flux.fromIterable(chunk.spans())
                .map(span -> SelectedSpan.builder()
                        .traceId(span.traceId())
                        .key(span.id())
                        .bytes(length(span.input()) + length(span.output()))
                        .build()));
        when(renderDAO.findSpans(anyString(), any(), any())).thenAnswer(invocation -> {
            List<TraceRef> traces = invocation.getArgument(1);
            Set<String> keys = invocation.<List<SelectedSpan>>getArgument(2).stream()
                    .map(SelectedSpan::key)
                    .collect(Collectors.toSet());
            return Flux.fromIterable(traces)
                    .flatMapIterable(ref -> spansByTrace.getOrDefault(ref.traceId(), List.of()))
                    .filter(span -> keys.contains(span.id()));
        });
        when(renderDAO.findThreadTurns(anyString(), any(), any(), any(), any()))
                .thenReturn(Flux.fromIterable(chunk.turns()));
        return renderDAO;
    }

    private static long length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static AiLabelsConfig config() {
        var config = new AiLabelsConfig();
        config.setRenderThreads(2);
        // Several reads per chunk, so batching is covered by every test here.
        config.setRenderReadBytes(50_000);
        return config;
    }

    private static List<TraceRef> refs(Chunk chunk) {
        return chunk.traces().stream().map(AiLabelRenderFixtures::refOf).toList();
    }

    /** The trace with a thread and a UUIDv7 id {@code minutes} after {@link #NOW}. */
    private static TraceRow withThread(TraceRow trace, String threadId, int minutes) {
        return trace.toBuilder().id(uuidV7(NOW.plus(Duration.ofMinutes(minutes)), trace.id())).threadId(threadId)
                .build();
    }

    private static ThreadTurnRow turn(TraceRow trace, int minutes, String input, String output) {
        var bits = UUID.nameUUIDFromBytes(input.getBytes(StandardCharsets.UTF_8));
        return ThreadTurnRow.builder()
                .id(uuidV7(NOW.plus(Duration.ofMinutes(minutes)), bits))
                .projectId(trace.projectId())
                .threadId(trace.threadId())
                .input(input)
                .output(output)
                .build();
    }

    /** A UUIDv7 at {@code at}, with its random bits taken from {@code bits}. */
    private static UUID uuidV7(Instant at, UUID bits) {
        long mostSignificant = (at.toEpochMilli() << 16) | 0x7000L | (bits.getMostSignificantBits() & 0x0FFFL);
        long leastSignificant = (bits.getLeastSignificantBits() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(mostSignificant, leastSignificant);
    }

    private static SpanRow span(TraceRow trace, String id, String parentSpanId, String input, String output) {
        return SpanRow.builder()
                .traceId(trace.id())
                .id(id)
                .parentSpanId(parentSpanId)
                .name(id)
                .type("general")
                .input(input)
                .output(output)
                .startTime(AiLabelRenderFixtures.BASE_TIME)
                .build();
    }

    /** The compaction level and size, then the canonical state and history: a diff of the file is a diff of the output. */
    private static String golden(RenderOutcome outcome) {
        return switch (outcome) {
            case RenderOutcome.Rendered rendered -> rendered.compactionLevel() + "\n" + rendered.bytes() + "\n"
                    + AiLabelRenderer.canonicalJson(rendered.state()) + "\n"
                    + (rendered.history() == null ? "" : AiLabelRenderer.canonicalJson(rendered.history()) + "\n");
            case RenderOutcome.OverBudget overBudget -> "over_budget\n" + overBudget.bytes() + "\n";
        };
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
