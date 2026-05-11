package com.comet.opik.api.resources.v1.events.tools;

import dev.langchain4j.agent.tool.ToolSpecification;

/**
 * SPI for tools the LLM-as-judge agent can invoke. Each tool returns a JSON string
 * that is fed back to the model as a tool result. Errors are returned as
 * {@code {"error": "..."}} JSON strings rather than thrown — the tool-call loop
 * must remain non-fatal so the judge can recover.
 */
public interface ToolExecutor {

    String name();

    ToolSpecification spec();

    String execute(String arguments, TraceToolContext ctx);
}