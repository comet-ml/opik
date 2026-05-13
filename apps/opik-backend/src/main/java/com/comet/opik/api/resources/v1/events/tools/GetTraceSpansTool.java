package com.comet.opik.api.resources.v1.events.tools;

import com.comet.opik.api.Span;
import com.comet.opik.domain.SpanService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Singleton
@Slf4j
public class GetTraceSpansTool implements ToolExecutor {

    public static final String NAME = "get_trace_spans";

    private static final ToolSpecification SPEC = ToolSpecification.builder()
            .name(NAME)
            .description("Retrieve a high-level overview of the sub-spans (tool calls, LLM calls, "
                    + "guardrails, intermediate steps) of a trace. Returns span names, types, "
                    + "truncated input/output, and hierarchy. In trace-level scoring the trace is "
                    + "implicit (the trace being evaluated); in trace-thread scoring pass an explicit "
                    + "trace_id from the thread skeleton. Use this first to understand structure "
                    + "before drilling into specific spans.")
            .parameters(JsonObjectSchema.builder()
                    .addStringProperty("trace_id",
                            "Optional. Trace id (UUID) to inspect. Required in trace-thread scoring "
                                    + "(no implicit trace). Optional in single-trace scoring — defaults "
                                    + "to the trace being evaluated.")
                    .build())
            .build();

    private final SpanService spanService;

    @Inject
    public GetTraceSpansTool(@NonNull SpanService spanService) {
        this.spanService = spanService;
    }

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
        UUID resolvedTraceId;
        List<Span> resolvedSpans;
        try {
            ParsedArgs args = parseArgs(arguments);
            if (args.error != null) {
                return args.error;
            }

            if (args.traceId != null) {
                // Explicit arg path. Used by trace-thread scoring (no implicit trace) and also
                // valid in trace-level scoring when the agent wants to inspect a different
                // trace than the one being evaluated.
                resolvedTraceId = args.traceId;
                if (ctx.getTrace() != null && resolvedTraceId.equals(ctx.getTrace().id())) {
                    // Active trace short-circuit — its spans were pre-fetched by the scorer.
                    resolvedSpans = ctx.getSpans();
                } else {
                    resolvedSpans = fetchSpans(resolvedTraceId, ctx);
                }
            } else if (ctx.getTrace() != null) {
                // Backward-compatible default: trace-level scoring with no arg uses the active trace.
                resolvedTraceId = ctx.getTrace().id();
                resolvedSpans = ctx.getSpans();
            } else {
                // Thread scoring with no arg — the model has to pick a trace from the skeleton.
                return ToolArgs.errorJson("trace_id is required in trace-thread scoring — pick a"
                        + " trace id from the thread skeleton (system message) and pass it as"
                        + " trace_id.");
            }
        } catch (Exception e) {
            log.warn("get_trace_spans tool failed to resolve arguments '{}'", arguments, e);
            return ToolArgs.errorJson("Failed to load spans: " + e.getMessage());
        }

        log.debug("get_trace_spans tool call with arguments: '{}' for trace='{}'", arguments, resolvedTraceId);
        String result = SpanTreeSerializer.serializeOverview(resolvedSpans);
        if (log.isDebugEnabled()) {
            log.debug("get_trace_spans summary: traceId='{}', spanCount='{}', outputBytes='{}'",
                    resolvedTraceId, resolvedSpans.size(), result.length());
        }
        return result;
    }

    private List<Span> fetchSpans(UUID traceId, TraceToolContext ctx) {
        List<Span> spans = spanService.getByTraceIds(Set.of(traceId))
                .collectList()
                .contextWrite(rc -> rc.put(RequestContext.WORKSPACE_ID, ctx.getWorkspaceId())
                        .put(RequestContext.USER_NAME, ctx.getUserName()))
                .block();
        return spans == null ? List.of() : spans;
    }

    private static ParsedArgs parseArgs(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return new ParsedArgs(null, null);
        }
        JsonNode node;
        try {
            node = JsonUtils.getJsonNodeFromString(arguments);
        } catch (Exception e) {
            return new ParsedArgs(null, ToolArgs.errorJson("Malformed arguments: " + e.getMessage()));
        }
        if (node == null || !node.isObject()) {
            // The model occasionally calls with `arguments=""` or `arguments="{}"` (no payload).
            // Treat any non-object body as "no arg given" rather than a hard error.
            return new ParsedArgs(null, null);
        }
        String idText = ToolArgs.textOrNull(node.get("trace_id"));
        if (idText == null || idText.isBlank()) {
            return new ParsedArgs(null, null);
        }
        try {
            return new ParsedArgs(UUID.fromString(idText), null);
        } catch (IllegalArgumentException e) {
            return new ParsedArgs(null, ToolArgs.errorJson("Invalid trace_id format: " + idText));
        }
    }

    private record ParsedArgs(UUID traceId, String error) {
    }
}
