package com.comet.opik.domain.ailabels;

import com.comet.opik.domain.ailabels.AiLabelRenderDAO.SpanRow;
import com.comet.opik.domain.ailabels.AiLabelRenderDAO.TraceRow;
import com.comet.opik.utils.JsonUtils;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AiLabelRendererTest {

    /**
     * The fingerprint of each renderer version's golden files. Released versions are never edited: a change to the
     * output bumps {@link AiLabelRenderer#RENDERER_VERSION}, adds golden files for the new version, and adds its entry
     * here.
     */
    private static final Map<Integer, String> GOLDEN_FINGERPRINTS = Map.of(
            1, "642ab6ecc2e0b9e1b652cc20ef97f1fd7c54bd7bccd01fab5dfd85990246a972");

    private static final String GOLDEN_DIR = "src/test/resources/ai-labels/renderer/v%d"
            .formatted(AiLabelRenderer.RENDERER_VERSION);

    /** Writes the golden files of the current version instead of checking them, for a new renderer version. */
    private static final boolean WRITE_GOLDEN = Boolean.getBoolean("ai-labels.renderer.write-golden");

    private record GoldenCase(String name, TraceRow trace, List<SpanRow> spans, long budget) {
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
                new GoldenCase("small", smallTrace, small.spans(smallTrace, 5, 3, 30), 80_000),
                new GoldenCase("deep", deepTrace, deepSpans, 9_000),
                new GoldenCase("wide", wideTrace, wideSpans, 120_000),
                new GoldenCase("multi_byte", multiByteTrace, multiByte.spans(multiByteTrace, 30, 4, 500), 12_000),
                new GoldenCase("huge_field", hugeTrace, hugeSpans, 8_000));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("goldenCases")
    void renderMatchesTheGoldenFileOfTheCurrentVersion(String name, GoldenCase goldenCase) throws IOException {
        var actual = golden(render(goldenCase));
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

    @ParameterizedTest
    @ValueSource(longs = {1_000_000, 100_000, 60_000})
    void renderIsByteIdenticalForTheSameContent(long budget) {
        var fixtures = new AiLabelRenderFixtures(9, true);
        var trace = fixtures.trace(3_000);
        var spans = fixtures.spans(trace, 400, 12, 200);
        var ref = AiLabelRenderFixtures.refOf(trace);
        var expected = canonical(AiLabelRenderer.render(ref, trace, spans, budget));

        var random = new Random(3);
        for (int i = 0; i < 5; i++) {
            var shuffled = new ArrayList<>(spans);
            Collections.shuffle(shuffled, random);
            assertThat(canonical(AiLabelRenderer.render(ref, trace, shuffled, budget))).isEqualTo(expected);
        }
        assertThat(canonical(AiLabelRenderer.render(ref, trace, spans, budget))).isEqualTo(expected);
    }

    @Test
    void renderReturnsOverBudgetWithoutAState() {
        var fixtures = new AiLabelRenderFixtures(10, false);
        var trace = fixtures.trace(10);
        var spans = fixtures.spans(trace, 2_000, 3, 10);
        var ref = AiLabelRenderFixtures.refOf(trace);

        var outcome = AiLabelRenderer.render(ref, trace, spans, 80_000);

        assertThat(outcome).isInstanceOf(RenderOutcome.OverBudget.class);
        assertThat(outcome.trace()).isEqualTo(ref);
        assertThat(((RenderOutcome.OverBudget) outcome).bytes()).isGreaterThan(80_000);
    }

    /** A 2,000-span trace with about 200 KB of I/O renders in under 20 ms on one core, once the JIT is warm. */
    @Test
    void renderIsFastForALargeTrace() {
        var fixtures = new AiLabelRenderFixtures(12, false);
        var trace = fixtures.trace(20_000);
        var spans = fixtures.spans(trace, 2_000, 10, 0).stream()
                .map(span -> span.toBuilder()
                        .input("{\"query\":\"%s\"}".formatted(fixtures.text(20)))
                        .output("{\"answer\":\"%s\"}".formatted(fixtures.text(30)))
                        .build())
                .toList();
        var ref = AiLabelRenderFixtures.refOf(trace);
        long ioBytes = Stream.concat(Stream.of(trace.input(), trace.output(), trace.metadata()),
                spans.stream().flatMap(span -> Stream.of(span.input(), span.output(), span.metadata())))
                .filter(value -> value != null)
                .mapToLong(value -> value.getBytes(StandardCharsets.UTF_8).length)
                .sum();
        assertThat(ioBytes).isBetween(150_000L, 300_000L);

        for (int i = 0; i < 50; i++) {
            AiLabelRenderer.render(ref, trace, spans, 80_000);
        }
        long[] nanos = LongStream.range(0, 21)
                .map(i -> {
                    long start = System.nanoTime();
                    AiLabelRenderer.render(ref, trace, spans, 80_000);
                    return System.nanoTime() - start;
                })
                .sorted()
                .toArray();

        assertThat(nanos[nanos.length / 2] / 1_000_000.0).as("median render time in ms").isLessThan(20.0);
    }

    private static RenderOutcome render(GoldenCase goldenCase) {
        return AiLabelRenderer.render(AiLabelRenderFixtures.refOf(goldenCase.trace()), goldenCase.trace(),
                goldenCase.spans(), goldenCase.budget());
    }

    private static String canonical(RenderOutcome outcome) {
        return switch (outcome) {
            case RenderOutcome.Rendered rendered -> rendered.compactionLevel() + "\n" + rendered.bytes() + "\n"
                    + AiLabelRenderer.canonicalJson(rendered.state());
            case RenderOutcome.OverBudget overBudget -> "over_budget\n" + overBudget.bytes();
        };
    }

    /** The compaction level and size, then the canonical state, so a diff of the file is a diff of the output. */
    private static String golden(RenderOutcome outcome) {
        return canonical(outcome) + "\n";
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
