package com.comet.opik.api.resources.v1.events.tools;

import com.google.inject.Inject;
import dev.langchain4j.agent.tool.ToolSpecification;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Central dispatcher for {@link ToolExecutor}s. Tools are bound through
 * {@code ToolsModule}'s multibinder, so adding a new tool is a single binding
 * line. Unknown tool names — and any {@link Exception} thrown by (or emitted
 * from) an executor — produce a non-fatal {@code {"error": ...}} string rather
 * than failing the Mono so the judge's tool-call loop can continue.
 * {@link Error}s (OOM, StackOverflowError) propagate.
 */
@Singleton
@Slf4j
public class ToolRegistry {

    private final Map<String, ToolExecutor> byName;
    private final List<ToolSpecification> specs;

    @Inject
    public ToolRegistry(@NonNull Set<ToolExecutor> tools) {
        this.byName = tools.stream()
                .collect(Collectors.toUnmodifiableMap(ToolExecutor::name, t -> t));
        this.specs = tools.stream()
                .sorted(Comparator.comparing(ToolExecutor::name))
                .map(ToolExecutor::spec)
                .toList();
    }

    public List<ToolSpecification> specs() {
        return specs;
    }

    public Mono<String> execute(@NonNull String name, String arguments, @NonNull TraceToolContext ctx) {
        ToolExecutor executor = byName.get(name);
        if (executor == null) {
            log.warn("Unknown tool requested by judge: '{}'", name);
            return Mono.just(ToolArgs.errorJson("Unknown tool: " + name));
        }
        // Mono.defer so a synchronous throw inside executor.execute is captured as a
        // Mono error and run through onErrorResume — matches the prior try/catch
        // semantics for tools that haven't migrated to deferring their own errors.
        return Mono.defer(() -> executor.execute(arguments, ctx))
                .onErrorResume(Exception.class, e -> {
                    // Keep the judge loop alive when a tool fails unexpectedly. Errors (OOM,
                    // StackOverflowError) intentionally propagate — the JVM is in no shape to
                    // continue scoring after one of those.
                    //
                    // Don't echo the raw exception message to the LLM — it can include ClickHouse
                    // query fragments, internal paths, secrets in stack-trace-like detail, or any
                    // other data the unknown tool implementation may have surfaced. Surface a short
                    // correlation id instead so an operator can grep the warn log to find the cause.
                    // Mirrors the ReadTool exception path so both surfaces leak the same (minimal)
                    // info to the model.
                    String correlationId = UUID.randomUUID().toString();
                    log.warn(
                            "Tool '{}' threw; returning error JSON to keep judge loop alive, correlationId='{}'",
                            name, correlationId, e);
                    return Mono.just(ToolArgs.errorJson(
                            "Tool '%s' failed (ref: %s)".formatted(name, correlationId)));
                });
    }
}