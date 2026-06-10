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
 * <p>Trace-scoped evaluations (LLM-as-judge on a single trace) carry the active
 * {@link Trace} + its spans, which are also pre-seeded into {@link #fetched} so
 * {@code jq} / {@code search} can target them without an explicit {@code read}.
 *
 * <p>Thread-scoped evaluations don't have a single active trace — the model
 * drills into individual traces via {@code read(type=trace, id=X)} on
 * thread-skeleton ids. Build those contexts with {@link #forThread} and check
 * {@link #hasActiveTrace()} from any tool that requires the per-trace shortcut
 * (only {@code GetTraceSpansTool} does today).
 */
@Getter
public final class TraceToolContext {

    private final Trace trace;
    private final List<Span> spans;
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

    /**
     * Single constructor with nullable {@code trace} / {@code spans} (final fields,
     * so they're assigned exactly once here regardless of branch). Callers go through
     * {@link #forActiveTrace} or {@link #forThread} rather than constructing directly;
     * the explicit nulls live at the {@code forThread} call site where the absence of
     * an active trace is the point.
     */
    private TraceToolContext(Trace trace, List<Span> spans,
            @NonNull String workspaceId, @NonNull String userName) {
        this.trace = trace;
        this.spans = spans != null ? List.copyOf(spans) : null;
        this.workspaceId = workspaceId;
        this.userName = userName;
    }

    /**
     * Build a context for a trace-scoped evaluation — the model has a single active
     * trace whose spans are already in memory. {@link GetTraceSpansTool} hits this
     * shortcut; other tools fall through to {@code read(type=trace, id=X)} as usual.
     */
    public static TraceToolContext forActiveTrace(@NonNull Trace trace, @NonNull List<Span> spans,
            @NonNull String workspaceId, @NonNull String userName) {
        return new TraceToolContext(trace, spans, workspaceId, userName);
    }

    /**
     * Build a context for a thread-scoped evaluation — no single "active" trace,
     * just workspace/user. The model accesses individual traces via
     * {@code read(type=trace, id=X)} on the thread skeleton's trace ids; those
     * reads land in {@link #fetched} on demand.
     */
    public static TraceToolContext forThread(@NonNull String workspaceId, @NonNull String userName) {
        return new TraceToolContext(null, null, workspaceId, userName);
    }

    /**
     * True for trace-scoped evaluations (built via the public constructor), false
     * for thread-scoped ones (built via {@link #forThread}). Tools that need the
     * active trace ({@link GetTraceSpansTool}) should branch on this rather than
     * NPEing on {@link #getTrace()}.
     */
    public boolean hasActiveTrace() {
        return trace != null;
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