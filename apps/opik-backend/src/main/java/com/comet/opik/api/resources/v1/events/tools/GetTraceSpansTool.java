package com.comet.opik.api.resources.v1.events.tools;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

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
    public String execute(String arguments, TraceToolContext ctx) {
        log.debug("get_trace_spans tool call with arguments: '{}' for trace={}", arguments, ctx.getTrace().id());
        String result = SpanTreeSerializer.serializeOverview(ctx.getSpans());
        if (log.isDebugEnabled()) {
            log.debug("get_trace_spans summary: traceId='{}', spanCount='{}', outputBytes='{}'",
                    ctx.getTrace().id(), ctx.getSpans().size(), result.length());
        }
        return result;
    }
}
