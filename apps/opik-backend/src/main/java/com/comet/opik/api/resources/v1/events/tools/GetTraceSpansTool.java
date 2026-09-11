package com.comet.opik.api.resources.v1.events.tools;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Singleton
@Slf4j
public class GetTraceSpansTool implements ToolExecutor {

    public static final String NAME = "get_trace_spans";

    private static final ToolSpecification SPEC = ToolSpecification.builder()
            .name(NAME)
            .description("Retrieve a high-level overview of all sub-spans (tool calls, LLM calls, "
                    + "guardrails, intermediate steps) for the trace being evaluated. Returns span names, "
                    + "types, truncated input/output, and hierarchy. Use this first to understand the "
                    + "trace structure before drilling into specific spans.")
            .parameters(JsonObjectSchema.builder().build())
            .build();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolSpecification spec() {
        return SPEC;
    }

    @Override
    public Mono<String> execute(String arguments, @NonNull TraceToolContext ctx) {
        // fromCallable so any throw from SpanTreeSerializer is captured as a Mono error
        // and reported via ToolRegistry's onErrorResume — matches the legacy throw path.
        return Mono.fromCallable(() -> {
            if (!ctx.hasActiveTrace()) {
                // Thread-scoped evaluations have no single active trace. Redirect the model
                // to the per-trace ReadTool path rather than NPEing on ctx.getTrace().
                log.debug("get_trace_spans called with no active trace (thread-scoped evaluation)");
                return ToolArgs.errorJson("get_trace_spans is only available for single-trace"
                        + " evaluations. This is a thread-scoped evaluation — pick a trace from the"
                        + " thread skeleton and call read(type=trace, id=<uuid>) to inspect its"
                        + " spans instead.");
            }
            log.debug("get_trace_spans tool call with arguments: '{}' for trace={}", arguments,
                    ctx.getTrace().id());
            String result = SpanTreeSerializer.serializeOverview(ctx.getSpans());
            log.debug("get_trace_spans summary: traceId='{}', spanCount='{}', outputBytes='{}'",
                    ctx.getTrace().id(), ctx.getSpans().size(), result.length());
            return result;
        });
    }
}
