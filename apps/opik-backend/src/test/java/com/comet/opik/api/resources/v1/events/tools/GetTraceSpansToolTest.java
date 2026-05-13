package com.comet.opik.api.resources.v1.events.tools;

import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.SpanType;
import com.comet.opik.utils.JsonUtils;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GetTraceSpansToolTest {

    private final SpanService spanService = mock(SpanService.class);
    private final GetTraceSpansTool tool = new GetTraceSpansTool(spanService);

    @Test
    void specHasExpectedNameAndOptionalTraceIdArg() {
        var spec = tool.spec();

        assertThat(spec.name()).isEqualTo(GetTraceSpansTool.NAME);
        // trace_id is optional (single-trace mode defaults to the active trace),
        // so the required list is empty.
        assertThat(spec.parameters().required()).isEmpty();
    }

    @Test
    void noArgInSingleTraceModeUsesPreFetchedSpans() {
        // Trace-level scoring: ctx carries the active trace + its pre-fetched spans.
        // get_trace_spans with no args must short-circuit to ctx.getSpans() (no DB hit).
        UUID traceId = UUID.randomUUID();
        var ctx = newSingleTraceContext(traceId, List.of(span("llm-call", traceId)));

        String output = tool.execute("{}", ctx);

        assertThat(output).contains("llm-call");
        verifyNoInteractions(spanService);
    }

    @Test
    void noArgInSingleTraceModeAlsoAcceptsNullAndEmptyArguments() {
        // Some providers emit null or empty arguments when the tool spec has no required fields.
        // Both should resolve to the default single-trace path (active trace's spans).
        UUID traceId = UUID.randomUUID();
        var ctx = newSingleTraceContext(traceId, List.of(span("llm-call", traceId)));

        assertThat(tool.execute(null, ctx)).contains("llm-call");
        assertThat(tool.execute("", ctx)).contains("llm-call");
        verifyNoInteractions(spanService);
    }

    @Test
    void explicitTraceIdMatchingActiveTraceShortCircuitsToPreFetchedSpans() {
        // When the model passes the active trace's id explicitly, we still avoid the DB hit.
        UUID traceId = UUID.randomUUID();
        var ctx = newSingleTraceContext(traceId, List.of(span("guardrail", traceId)));

        String output = tool.execute("{\"trace_id\":\"" + traceId + "\"}", ctx);

        assertThat(output).contains("guardrail");
        verifyNoInteractions(spanService);
    }

    @Test
    void explicitTraceIdDifferentFromActiveTraceFetchesViaSpanService() {
        // Trace-level scoring where the agent decides to inspect a DIFFERENT trace than the
        // active one — fetch via SpanService rather than (incorrectly) using ctx.getSpans().
        UUID activeTraceId = UUID.randomUUID();
        UUID otherTraceId = UUID.randomUUID();
        var ctx = newSingleTraceContext(activeTraceId, List.of(span("active-span", activeTraceId)));
        when(spanService.getByTraceIds(eq(Set.of(otherTraceId))))
                .thenReturn(Flux.just(span("other-trace-span", otherTraceId)));

        String output = tool.execute("{\"trace_id\":\"" + otherTraceId + "\"}", ctx);

        assertThat(output).contains("other-trace-span").doesNotContain("active-span");
    }

    @Test
    void threadModeNoArgReturnsErrorPointingToThreadSkeleton() {
        // No active trace → tool can't guess which thread trace to inspect. Surface a
        // helpful error so the model picks a trace_id from the system prompt instead of
        // retrying the same empty call.
        var ctx = TraceToolContext.forThread(List.of(sampleTrace(UUID.randomUUID())), "ws", "user");

        var result = JsonUtils.getJsonNodeFromString(tool.execute("{}", ctx));

        assertThat(result.get("error").asText())
                .contains("trace_id is required")
                .contains("thread skeleton");
        verifyNoInteractions(spanService);
    }

    @Test
    void threadModeWithTraceIdFetchesViaSpanService() {
        UUID threadTraceId = UUID.randomUUID();
        var ctx = TraceToolContext.forThread(List.of(sampleTrace(threadTraceId)), "ws", "user");
        when(spanService.getByTraceIds(eq(Set.of(threadTraceId))))
                .thenReturn(Flux.just(span("thread-trace-span", threadTraceId)));

        String output = tool.execute("{\"trace_id\":\"" + threadTraceId + "\"}", ctx);

        assertThat(output).contains("thread-trace-span");
    }

    @Test
    void invalidTraceIdUuidReturnsError() {
        var ctx = TraceToolContext.forThread(List.of(sampleTrace(UUID.randomUUID())), "ws", "user");

        var result = JsonUtils.getJsonNodeFromString(
                tool.execute("{\"trace_id\":\"not-a-uuid\"}", ctx));

        assertThat(result.get("error").asText())
                .contains("Invalid trace_id format")
                .contains("not-a-uuid");
        verifyNoInteractions(spanService);
    }

    @Test
    void malformedJsonArgumentsReturnsError() {
        var ctx = newSingleTraceContext(UUID.randomUUID(), List.of());

        var result = JsonUtils.getJsonNodeFromString(tool.execute("{not valid", ctx));

        assertThat(result.get("error").asText()).contains("Malformed arguments");
        verifyNoInteractions(spanService);
    }

    @Test
    void nonObjectArgumentsBodyIsTreatedAsNoArg() {
        // Some models pass "[]" or "\"foo\"" instead of an object. Rather than rejecting,
        // we treat it as no-arg (the same as "{}") so the call still succeeds in single-trace mode.
        UUID traceId = UUID.randomUUID();
        var ctx = newSingleTraceContext(traceId, List.of(span("llm-call", traceId)));

        assertThat(tool.execute("[]", ctx)).contains("llm-call");
        assertThat(tool.execute("\"foo\"", ctx)).contains("llm-call");
        verifyNoInteractions(spanService);
    }

    // ---------------- Helpers ----------------

    private static TraceToolContext newSingleTraceContext(UUID traceId, List<Span> spans) {
        return new TraceToolContext(sampleTrace(traceId), spans, "ws", "user");
    }

    private static Trace sampleTrace(UUID traceId) {
        return Trace.builder()
                .id(traceId)
                .projectId(UUID.randomUUID())
                .name("trace-" + traceId)
                .startTime(Instant.now())
                .build();
    }

    private static Span span(String name, UUID traceId) {
        return Span.builder()
                .id(UUID.randomUUID())
                .traceId(traceId)
                .projectId(UUID.randomUUID())
                .name(name)
                .type(SpanType.general)
                .startTime(Instant.now())
                .build();
    }
}
