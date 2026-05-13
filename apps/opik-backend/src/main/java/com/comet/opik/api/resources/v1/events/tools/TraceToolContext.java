package com.comet.opik.api.resources.v1.events.tools;

import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.NonNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Mutable per-evaluation cache shared across all tool calls within a single
 * judge invocation. One instance per {@code handleToolCalls} loop; the loop
 * runs sequentially on a single thread, so a plain {@link HashMap} is safe.
 *
 * <p>Two construction modes:
 * <ul>
 *   <li><strong>Single-trace</strong> ({@code new TraceToolContext(trace, spans, ...)} — the
 *       original trace-level LLM-as-judge path). {@link #trace} and {@link #spans} are populated;
 *       {@link #threadTraces} is empty.</li>
 *   <li><strong>Thread</strong> ({@link #forThread(List, String, String)}) — the trace-thread
 *       LLM-as-judge path. {@link #threadTraces} holds the thread's sorted-by-id traces;
 *       {@link #trace} is {@code null} and {@link #spans} is empty (spans for any of those
 *       traces are fetched on-demand by the {@code get_trace_spans} tool).</li>
 * </ul>
 * Tools that only need {@link EntityRef} cache access (read / jq / search) work in both modes
 * uniformly. {@code get_trace_spans} branches on whether {@link #trace} is present (active-trace
 * default) versus accepting an explicit {@code trace_id} (thread mode).
 */
@Getter
public final class TraceToolContext {

    private final Trace trace;
    private final List<Span> spans;
    private final List<Trace> threadTraces;
    private final String workspaceId;
    private final String userName;
    private final Map<EntityRef, JsonNode> fetched = new HashMap<>();
    /**
     * Refs whose cached form has already been capped at least once (10 MB cache
     * cap, see {@code ReadTool#applyCacheCap}). The cap is sticky for the
     * lifetime of the context: once a {@code read} has had to truncate, every
     * subsequent {@code read} of the same entity must keep emitting
     * {@code cache_warning} so the LLM doesn't silently switch from
     * "warned-once" to "looks like FULL again" on the second call.
     */
    private final Set<EntityRef> truncated = new HashSet<>();

    public TraceToolContext(@NonNull Trace trace,
            @NonNull List<Span> spans,
            @NonNull String workspaceId,
            @NonNull String userName) {
        this.trace = trace;
        this.spans = List.copyOf(spans);
        this.threadTraces = List.of();
        this.workspaceId = workspaceId;
        this.userName = userName;
    }

    private TraceToolContext(@NonNull List<Trace> threadTraces,
            @NonNull String workspaceId,
            @NonNull String userName) {
        this.trace = null;
        this.spans = List.of();
        this.threadTraces = List.copyOf(threadTraces);
        this.workspaceId = workspaceId;
        this.userName = userName;
    }

    /**
     * Constructs a context for the trace-thread LLM-as-judge path. No "active" trace — the
     * thread's traces are listed in the system prompt by id, and ReadTool populates the
     * cache lazily on first hit (no upfront span fetch for every thread trace, which would
     * be wasteful if the LLM only inspects one). {@code get_trace_spans} requires an explicit
     * {@code trace_id} argument in this mode.
     */
    public static TraceToolContext forThread(@NonNull List<Trace> threadTraces,
            @NonNull String workspaceId,
            @NonNull String userName) {
        return new TraceToolContext(threadTraces, workspaceId, userName);
    }

    public Optional<JsonNode> getCached(@NonNull EntityRef ref) {
        return Optional.ofNullable(fetched.get(ref));
    }

    public void cache(@NonNull EntityRef ref, @NonNull JsonNode fullJson) {
        fetched.put(ref, fullJson);
    }

    public void markTruncated(@NonNull EntityRef ref) {
        truncated.add(ref);
    }

    public boolean isTruncated(@NonNull EntityRef ref) {
        return truncated.contains(ref);
    }

    public Map<EntityRef, JsonNode> snapshot() {
        return Collections.unmodifiableMap(fetched);
    }
}
