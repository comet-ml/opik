package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.v1.events.tools.ToolRegistry;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.utils.JsonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
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
