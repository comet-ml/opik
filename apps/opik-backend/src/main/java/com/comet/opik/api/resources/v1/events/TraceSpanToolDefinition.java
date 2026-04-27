package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Span;
import com.comet.opik.utils.JsonUtils;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@UtilityClass
@Slf4j
class TraceSpanToolDefinition {

    static final String OVERVIEW_TOOL_NAME = "get_trace_spans";
    static final String DETAILS_TOOL_NAME = "get_span_details";

    static final ToolSpecification OVERVIEW_TOOL = ToolSpecification.builder()
            .name(OVERVIEW_TOOL_NAME)
            .description("Retrieve a high-level overview of all sub-spans (tool calls, LLM calls, "
                    + "guardrails, intermediate steps) for the trace being evaluated. Returns span names, "
                    + "types, truncated input/output, and hierarchy. Use this first to understand the "
                    + "trace structure before drilling into specific spans.")
            .parameters(JsonObjectSchema.builder().build())
            .build();

    static final ToolSpecification DETAILS_TOOL = ToolSpecification.builder()
            .name(DETAILS_TOOL_NAME)
            .description("Retrieve the full, untruncated details of a specific span by its ID. "
                    + "Use this after calling get_trace_spans to get complete input/output/metadata "
                    + "for a span you need to inspect closely.")
            .parameters(JsonObjectSchema.builder()
                    .addStringProperty("span_id", "The ID of the span (from the get_trace_spans overview)")
                    .required("span_id")
                    .build())
            .build();

    static final List<ToolSpecification> ALL_TOOLS = List.of(OVERVIEW_TOOL, DETAILS_TOOL);

    String executeTool(String toolName, String arguments, List<Span> spans) {
        return switch (toolName) {
            case OVERVIEW_TOOL_NAME ->
                SpanTreeSerializer.serializeOverview(spans);
            case DETAILS_TOOL_NAME -> {
                var spanId = extractSpanId(arguments);
                yield spanId != null
                        ? SpanTreeSerializer.serializeSpanDetails(spans, spanId)
                        : "{\"error\": \"Missing or invalid span_id in arguments\"}";
            }
            default ->
                "{\"error\": \"Unknown tool: %s\"}".formatted(toolName);
        };
    }

    private String extractSpanId(String arguments) {
        try {
            var node = JsonUtils.getJsonNodeFromString(arguments);
            if (node != null && node.has("span_id")) {
                return node.get("span_id").asText();
            }
        } catch (Exception e) {
            log.warn("Failed to parse tool arguments: '{}'", arguments, e);
        }
        return null;
    }
}
