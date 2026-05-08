package com.comet.opik.api.resources.v1.events.tools;

import com.google.inject.Inject;
import dev.langchain4j.agent.tool.ToolSpecification;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Central dispatcher for {@link ToolExecutor}s. Tools are bound through
 * {@code ToolsModule}'s multibinder, so adding a new tool is a single binding
 * line. Unknown tool names — and any {@link Exception} thrown by an executor —
 * produce a non-fatal {@code {"error": ...}} string rather than an exception
 * so the judge's tool-call loop can continue. {@link Error}s (OOM,
 * StackOverflowError) propagate.
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

    public String execute(@NonNull String name, String arguments, @NonNull TraceToolContext ctx) {
        ToolExecutor executor = byName.get(name);
        if (executor == null) {
            log.warn("Unknown tool requested by judge: '{}'", name);
            return ToolArgs.errorJson("Unknown tool: " + name);
        }
        try {
            return executor.execute(arguments, ctx);
        } catch (Exception e) {
            // Keep the judge loop alive when a tool throws unexpectedly. Errors (OOM,
            // StackOverflowError) intentionally propagate — the JVM is in no shape to
            // continue scoring after one of those.
            log.warn("Tool '{}' threw; returning error JSON to keep judge loop alive", name, e);
            return ToolArgs.errorJson("Tool '" + name + "' failed: " + e.getMessage());
        }
    }
}