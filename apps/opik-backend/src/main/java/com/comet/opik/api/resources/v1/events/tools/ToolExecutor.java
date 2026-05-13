package com.comet.opik.api.resources.v1.events.tools;

import dev.langchain4j.agent.tool.ToolSpecification;
import reactor.core.publisher.Mono;

/**
 * SPI for tools the LLM-as-judge agent can invoke. Each tool returns a JSON string
 * (wrapped in a {@link Mono} so any I/O can be reactive) that is fed back to the
 * model as a tool result. Errors are emitted as {@code {"error": "..."}} JSON
 * strings rather than as Mono.error — the tool-call loop must remain non-fatal so
 * the judge can recover.
 */
public interface ToolExecutor {

    String name();

    ToolSpecification spec();

    Mono<String> execute(String arguments, TraceToolContext ctx);
}
