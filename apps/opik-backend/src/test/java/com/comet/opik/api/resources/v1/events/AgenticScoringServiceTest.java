package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.v1.events.tools.ToolRegistry;
import com.comet.opik.domain.SpanType;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.utils.JsonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class AgenticScoringServiceTest {

    @Mock
    private OnlineScoringConfig onlineScoringConfig;

    private AgenticScoringService agenticScoringService;

    @BeforeEach
    void setUp() {
        agenticScoringService = new AgenticScoringServiceImpl(onlineScoringConfig, new ToolRegistry(Set.of()));
    }

    @Test
    @DisplayName("estimateThreadContextTokens reflects spans size, so big enriched threads route to agentic-tools")
    void estimateThreadContextTokensFactorsInSpansSize() {
        var traceId = UUID.randomUUID();
        var projectId = UUID.randomUUID();
        var trace = Trace.builder()
                .id(traceId)
                .projectId(projectId)
                .input(JsonUtils.getJsonNodeFromString("{\"q\":\"hi\"}"))
                .output(JsonUtils.getJsonNodeFromString("{\"a\":\"hello\"}"))
                .endTime(Instant.now())
                .build();
        var bigSpan = Span.builder()
                .id(UUID.randomUUID()).name("huge-tool-call").type(SpanType.tool)
                .startTime(Instant.now()).traceId(traceId).projectId(projectId)
                .input(JsonUtils.readTree("{\"payload\":\"" + "x".repeat(2000) + "\"}"))
                .build();

        int estimateNoSpans = agenticScoringService.estimateThreadContextTokens(
                List.of(trace), List.of(), 4);
        int estimateWithSpans = agenticScoringService.estimateThreadContextTokens(
                List.of(trace), List.of(bigSpan), 4);

        // Adding ~2KB of span payload must move the estimate up — otherwise the agentic-tools
        // routing gate would underestimate and inline-render an oversized prompt.
        assertThat(estimateWithSpans).isGreaterThan(estimateNoSpans);
    }
}
