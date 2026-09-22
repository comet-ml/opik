package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.v1.events.tools.ToolRegistry;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.utils.JsonUtils;
import dev.langchain4j.model.chat.request.ToolChoice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgenticScoringServiceTest {

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    @Mock
    private OnlineScoringConfig onlineScoringConfig;

    private AgenticScoringService agenticScoringService;

    @BeforeEach
    void setUp() {
        agenticScoringService = new AgenticScoringServiceImpl(onlineScoringConfig, new ToolRegistry(Set.of()));
    }

    @Test
    @DisplayName("estimateThreadContextTokens counts the trace bodies plus the supplied span byte size")
    void estimateThreadContextTokensCountsTraceBodiesAndSpans() {
        var trace = Trace.builder()
                .id(ID_GENERATOR.generateId())
                .projectId(ID_GENERATOR.generateId())
                .input(JsonUtils.getJsonNodeFromString("{\"q\":\"" + "x".repeat(400) + "\"}"))
                .output(JsonUtils.getJsonNodeFromString("{\"a\":\"ok\"}"))
                .build();
        when(onlineScoringConfig.getAgenticToolsCharsPerToken()).thenReturn(4);

        var traceOnly = agenticScoringService.estimateThreadContextTokens(List.of(trace), 0L);
        var withSpans = agenticScoringService.estimateThreadContextTokens(List.of(trace), 4_000L);

        // Trace bodies alone contribute to the estimate, and adding 4 KB of span bytes adds 4000/4 tokens.
        assertThat(traceOnly).isPositive();
        assertThat(withSpans).isEqualTo(traceOnly + 1_000);
    }

    @Test
    @DisplayName("preloadThreadSpansBounded keeps all spans and does not overflow when under the byte cap")
    void preloadUnderCapReturnsAllSpans() {
        var span1 = spanWithInput("a".repeat(100));
        var span2 = spanWithInput("b".repeat(100));

        var result = agenticScoringService
                .preloadThreadSpansBounded(Flux.just(span1, span2), 10_000L)
                .block();

        assertThat(result).isNotNull();
        assertThat(result.overflowed()).isFalse();
        assertThat(result.spans()).containsExactly(span1, span2);
    }

    @Test
    @DisplayName("preloadThreadSpansBounded overflows and drops the buffer when spans exceed the byte cap")
    void preloadOverCapOverflowsWithEmptyBuffer() {
        // Each span input is ~2 KB; a 1 KB cap is crossed by the first span.
        var big1 = spanWithInput("x".repeat(2000));
        var big2 = spanWithInput("y".repeat(2000));

        var result = agenticScoringService
                .preloadThreadSpansBounded(Flux.just(big1, big2), 1_000L)
                .block();

        assertThat(result).isNotNull();
        assertThat(result.overflowed()).isTrue();
        // Buffer dropped on overflow — the agentic-tools path re-fetches per-trace on demand.
        assertThat(result.spans()).isEmpty();
    }

    @Test
    @DisplayName("preloadThreadSpansBounded cancels the upstream once the cap is crossed (never drains the whole thread)")
    void preloadCancelsUpstreamOnOverflow() {
        var emitted = new AtomicInteger();
        var big = spanWithInput("x".repeat(2000));
        // Unbounded source: without early cancellation this would emit forever. The bounded preload
        // must stop (cancel) as soon as the running size crosses the cap — this is the OOM fix.
        var unbounded = Flux.<Span>generate(sink -> sink.next(big))
                .doOnNext(span -> emitted.incrementAndGet());

        var result = agenticScoringService.preloadThreadSpansBounded(unbounded, 1_000L).block();

        assertThat(result).isNotNull();
        assertThat(result.overflowed()).isTrue();
        assertThat(result.spans()).isEmpty();
        // Cancelled almost immediately — a handful of elements at most, not the unbounded stream.
        assertThat(emitted.get()).isLessThan(5);
    }

    /**
     * One row per {@link LlmProvider} — deliberately explicit rather than derived from
     * {@code supportsToolCalling}, so adding a provider fails
     * {@link #firstRoundToolChoiceCoversEveryProvider()} until its tool choice is decided here
     * instead of silently inheriting a default.
     */
    static Stream<Arguments> firstRoundToolChoices() {
        return Stream.of(
                // langchain4j's ChatRequestValidationUtils.validate throws
                // UnsupportedFeatureException for any tool choice other than AUTO, and Vertex's
                // model calls it — a forced choice failed the evaluation instead of scoring it.
                arguments(LlmProvider.VERTEX_AI, ToolChoice.AUTO),
                arguments(LlmProvider.OPEN_AI, ToolChoice.REQUIRED),
                arguments(LlmProvider.ANTHROPIC, ToolChoice.REQUIRED),
                arguments(LlmProvider.GEMINI, ToolChoice.REQUIRED),
                arguments(LlmProvider.OPEN_ROUTER, ToolChoice.REQUIRED),
                arguments(LlmProvider.BEDROCK, ToolChoice.REQUIRED),
                // No tool support at all: callers gate these out, and AUTO keeps a caller that
                // forgets the gate on the harmless side.
                arguments(LlmProvider.OLLAMA, ToolChoice.AUTO),
                arguments(LlmProvider.CUSTOM_LLM, ToolChoice.AUTO),
                arguments(LlmProvider.OPIK_FREE, ToolChoice.AUTO));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("firstRoundToolChoices")
    @DisplayName("firstRoundToolChoice forces a tool call except where the provider rejects one")
    void firstRoundToolChoicePerProvider(LlmProvider provider, ToolChoice expected) {
        assertThat(agenticScoringService.firstRoundToolChoice(provider)).isEqualTo(expected);
    }

    @Test
    @DisplayName("firstRoundToolChoice has a case for every LlmProvider")
    void firstRoundToolChoiceCoversEveryProvider() {
        var covered = firstRoundToolChoices()
                .map(arguments -> (LlmProvider) arguments.get()[0])
                .collect(Collectors.toSet());

        assertThat(covered).containsExactlyInAnyOrder(LlmProvider.values());
    }

    private static Span spanWithInput(String payload) {
        return Span.builder()
                .id(ID_GENERATOR.generateId())
                .name("tool-call")
                .type(SpanType.tool)
                .startTime(Instant.now())
                .traceId(ID_GENERATOR.generateId())
                .projectId(ID_GENERATOR.generateId())
                .input(JsonUtils.readTree("{\"payload\":\"" + payload + "\"}"))
                .build();
    }
}
