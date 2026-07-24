package com.comet.opik.api.resources.v1.events.tools;

import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.attachment.AttachmentInfo;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Mutable per-evaluation cache shared across all tool calls within a single
 * judge invocation. One instance per {@code handleToolCalls} loop; the loop
 * runs sequentially on a single thread, so a plain {@link HashMap} is safe.
 *
 * <p>Both single-trace and thread LLM-as-judge evaluations now run the agentic
 * tool loop — the judge investigates via {@code read} / {@code jq} / {@code search}
 * and pulls media via {@code get_attachment} — so this context backs both. A
 * single-trace eval enters the agentic path when the test-suite-assertion or
 * large-context branch fires, or (OPIK-6555) when the trace has attachments the
 * judge must load to score; thread evals always go agentic.
 *
 * <p>Trace-scoped evaluations (LLM-as-judge on a single trace) carry the active
 * {@link Trace} + its spans, which are also pre-seeded into {@link #fetched} so
 * {@code jq} / {@code search} can target them without an explicit {@code read}.
 * Build these with {@link #forActiveTrace}.
 *
 * <p>Thread-scoped evaluations don't have a single active trace — the model
 * drills into individual traces via {@code read(type=trace, id=X)} on
 * thread-skeleton ids. Build those contexts with {@link #forThread} and check
 * {@link #hasActiveTrace()} from any tool that requires the per-trace shortcut
 * (only {@code GetTraceSpansTool} does today).
 *
 * <p>The media side-channel ({@link #stageMedia} / {@link #drainPendingMedia} and
 * the injection caps below) serves both scopes: any agentic eval whose judge calls
 * {@code get_attachment} stages bytes here for {@link ToolCallLoop} to inject.
 */
@Getter
public final class TraceToolContext {

    private final Trace trace;
    private final List<Span> spans;
    private final String workspaceId;
    private final String userName;
    /**
     * The project the evaluation runs within. Constant for the whole session, so tools that
     * need a container/project id (e.g. {@code get_attachment} looking up attachments) read it
     * from here instead of re-fetching the entity just to learn its project.
     */
    private final UUID projectId;
    private final Map<EntityRef, JsonNode> fetched = new HashMap<>();
    /** Per-trace attachment-list cache. Populated on the first read; subsequent reads of the same
     *  trace skip the DB round-trip. Keyed by trace UUID. */
    private final Map<UUID, List<AttachmentInfo>> attachmentCache = new HashMap<>();
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
     * Default cap used when no explicit limit is provided (e.g., in tests). Production
     * evaluations override this via {@code onlineScoring.agenticToolsMaxInjectedBytes}
     * in the server config (env: {@code ONLINE_SCORING_AGENTIC_TOOLS_MAX_INJECTED_BYTES}).
     *
     * <p>50 MB accommodates several high-resolution images without blowing context.
     * Every injected attachment is re-sent on every follow-up round and on the final
     * structured re-issue, so the cost is multiplicative in the number of tool rounds.
     */
    public static final long DEFAULT_MAX_INJECTED_BYTES = 50L * 1024 * 1024;

    /**
     * Media fetched by {@code get_attachment} this round, awaiting injection.
     * {@link ToolCallLoop} drains this after each round's tool-result messages
     * and appends a single multimodal {@code UserMessage}. Mutated sequentially
     * on the loop thread (same single-thread guarantee as {@link #fetched}).
     */
    private final List<MediaPayload> pendingMedia = new ArrayList<>();
    private final long maxInjectedBytes;
    private long injectedBytes = 0;

    /**
     * Descriptors of every attachment injected during this evaluation, kept for
     * the whole run so the scorer can attribute a provider failure (e.g. a model
     * that rejects the media type) to specific attachments in a user-facing log.
     */
    private final List<InjectedAttachment> injectedAttachments = new ArrayList<>();

    /** Identifies an injected attachment for user-facing error attribution. */
    public record InjectedAttachment(String fileName, MediaCategory category) {
    }

    /**
     * Single constructor with nullable {@code trace} / {@code spans} (final fields,
     * so they're assigned exactly once here regardless of branch). Callers go through
     * {@link #forActiveTrace} or {@link #forThread} rather than constructing directly;
     * the explicit nulls live at the {@code forThread} call site where the absence of
     * an active trace is the point.
     */
    private TraceToolContext(Trace trace, List<Span> spans,
            @NonNull String workspaceId, @NonNull String userName, UUID projectId, long maxInjectedBytes) {
        this.trace = trace;
        this.spans = spans != null ? List.copyOf(spans) : null;
        this.workspaceId = workspaceId;
        this.userName = userName;
        this.projectId = projectId;
        this.maxInjectedBytes = maxInjectedBytes;
    }

    /**
     * Build a context for a trace-scoped evaluation — the model has a single active
     * trace whose spans are already in memory. {@link GetTraceSpansTool} hits this
     * shortcut; other tools fall through to {@code read(type=trace, id=X)} as usual.
     * The session's {@code projectId} is taken from the active trace.
     */
    public static TraceToolContext forActiveTrace(@NonNull Trace trace, @NonNull List<Span> spans,
            @NonNull String workspaceId, @NonNull String userName) {
        return forActiveTrace(trace, spans, workspaceId, userName, DEFAULT_MAX_INJECTED_BYTES);
    }

    public static TraceToolContext forActiveTrace(@NonNull Trace trace, @NonNull List<Span> spans,
            @NonNull String workspaceId, @NonNull String userName, long maxInjectedBytes) {
        return new TraceToolContext(trace, spans, workspaceId, userName, trace.projectId(), maxInjectedBytes);
    }

    /**
     * Build a context for a thread-scoped evaluation — no single "active" trace,
     * just workspace/user and the session {@code projectId} (every trace in the thread
     * belongs to it). The model accesses individual traces via
     * {@code read(type=trace, id=X)} on the thread skeleton's trace ids; those
     * reads land in {@link #fetched} on demand.
     */
    public static TraceToolContext forThread(@NonNull String workspaceId, @NonNull String userName,
            @NonNull UUID projectId) {
        return forThread(workspaceId, userName, projectId, DEFAULT_MAX_INJECTED_BYTES);
    }

    public static TraceToolContext forThread(@NonNull String workspaceId, @NonNull String userName,
            @NonNull UUID projectId, long maxInjectedBytes) {
        return new TraceToolContext(null, null, workspaceId, userName, projectId, maxInjectedBytes);
    }

    /**
     * Build a context for a span-scoped evaluation — the judge scores a single span and may load its
     * attachments via {@code get_attachment(type=span, ...)}. Like {@link #forThread} there is no active
     * <em>trace</em> (so {@link #hasActiveTrace()} stays false and {@code get_trace_spans} is correctly
     * unavailable); the session {@code projectId} is taken from the span. The caller pre-seeds the span
     * JSON into the cache via {@link #cache} under {@code EntityRef(SPAN, spanId)} so
     * {@code read}/{@code jq} resolve it without a re-fetch.
     */
    public static TraceToolContext forActiveSpan(@NonNull Span span,
            @NonNull String workspaceId, @NonNull String userName) {
        return forActiveSpan(span, workspaceId, userName, DEFAULT_MAX_INJECTED_BYTES);
    }

    public static TraceToolContext forActiveSpan(@NonNull Span span,
            @NonNull String workspaceId, @NonNull String userName, long maxInjectedBytes) {
        return new TraceToolContext(null, null, workspaceId, userName, span.projectId(), maxInjectedBytes);
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

    public Optional<List<AttachmentInfo>> getCachedAttachments(@NonNull UUID traceId) {
        return Optional.ofNullable(attachmentCache.get(traceId));
    }

    public void cacheAttachments(@NonNull UUID traceId, @NonNull List<AttachmentInfo> attachments) {
        attachmentCache.put(traceId, attachments);
    }

    // ---------------- Media side-channel (OPIK-6555) ----------------

    /**
     * Whether adding {@code sizeBytes} more bytes would stay within the total
     * injected-bytes cap. Applies identically to the MinIO base64 path and the
     * S3 presigned-URL path — it is the only bound on injected media.
     */
    public boolean canInjectMedia(long sizeBytes) {
        return injectedBytes + sizeBytes <= maxInjectedBytes;
    }

    /**
     * Stages {@code media} for injection by {@link ToolCallLoop} and records it on
     * the error-attribution list.
     */
    public void stageMedia(@NonNull MediaPayload media) {
        pendingMedia.add(media);
        injectedBytes += media.sizeBytes();
        injectedAttachments.add(new InjectedAttachment(media.fileName(), media.category()));
    }

    public boolean hasPendingMedia() {
        return !pendingMedia.isEmpty();
    }

    /** Returns and clears the media staged since the last drain. */
    public List<MediaPayload> drainPendingMedia() {
        var drained = List.copyOf(pendingMedia);
        pendingMedia.clear();
        return drained;
    }

    /** True if any attachment was injected during this evaluation. */
    public boolean hasInjectedMedia() {
        return !injectedAttachments.isEmpty();
    }

    public List<InjectedAttachment> getInjectedAttachments() {
        return List.copyOf(injectedAttachments);
    }
}