package com.comet.opik.api.resources.v1.events.tools;

import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.NonNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Mutable per-evaluation cache shared across all tool calls within a single
 * judge invocation. One instance per {@code handleToolCalls} loop; the loop
 * runs sequentially on a single thread, so a plain {@link HashMap} is safe.
 *
 * <p>The active {@link Trace} and its spans are accessors; once {@code ReadTool}
 * lands (phase 2), they will also be pre-seeded into {@link #fetched} so
 * subsequent {@code jq} / {@code search} calls can target them without an
 * explicit {@code read}. Phase 1 only exposes them as plain accessors —
 * {@link #fetched} stays empty until later phases populate it.
 */
@Getter
public final class TraceToolContext {

    private final Trace trace;
    private final List<Span> spans;
    private final String workspaceId;
    private final String userName;
    private final Map<EntityRef, JsonNode> fetched = new HashMap<>();

    public TraceToolContext(@NonNull Trace trace,
            @NonNull List<Span> spans,
            @NonNull String workspaceId,
            @NonNull String userName) {
        this.trace = trace;
        this.spans = List.copyOf(spans);
        this.workspaceId = workspaceId;
        this.userName = userName;
    }

    public Optional<JsonNode> getCached(@NonNull EntityRef ref) {
        return Optional.ofNullable(fetched.get(ref));
    }

    public void cache(@NonNull EntityRef ref, @NonNull JsonNode fullJson) {
        fetched.put(ref, fullJson);
    }

    public Map<EntityRef, JsonNode> snapshot() {
        return Collections.unmodifiableMap(fetched);
    }
}