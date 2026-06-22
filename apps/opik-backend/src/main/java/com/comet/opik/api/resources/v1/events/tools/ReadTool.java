package com.comet.opik.api.resources.v1.events.tools;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.domain.DatasetItemSearchCriteria;
import com.comet.opik.domain.DatasetItemService;
import com.comet.opik.domain.DatasetService;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.attachment.AttachmentService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Fetches an Opik entity by {@code (type, id)}, caches the FULL JSON form in
 * {@link TraceToolContext} (with the 10 MB cap behavior described below), and
 * returns an adaptively compressed view to the LLM.
 *
 * <p>Args: {@code {type, id, tier?}}.
 * <ul>
 *   <li>{@code type} ∈ {@code trace, span, dataset, dataset_item, project}.
 *       {@code thread} is not supported in this phase.</li>
 *   <li>{@code id} entity id (UUID).</li>
 *   <li>{@code tier} is optional. When absent, the matching compressor auto-picks.
 *       For fixed-tier compressors (dataset → SUMMARY) the arg is ignored.</li>
 * </ul>
 *
 * <p>Cache invariant: regardless of the tier returned to the LLM, this tool
 * caches the full JSON-serialized form of the fetched entity so future
 * {@code jq} / {@code search} calls operate on full content. If the FULL form
 * exceeds {@link #CACHE_CAP_CHARS} (≈ 10 MB), the MEDIUM-tier truncated form is
 * cached instead and a {@code cache_warning} field is inlined in the response.
 *
 * <p>Errors are emitted as {@code {"error": "..."}} JSON strings (via the
 * returned {@link Mono}) rather than failing the Mono — the tool-call loop must
 * remain non-fatal.
 */
@Singleton
@Slf4j
public class ReadTool implements ToolExecutor {

    public static final String NAME = "read";

    /**
     * ~ 10 MB. The bound is on the JSON string {@code length()} (UTF-16 char
     * units in the JVM); UTF-8 byte count for any string is at least the char
     * count, so this also bounds on-wire byte size. JVM heap impact per cached
     * entity is roughly 2× this since {@code String} stores UTF-16 internally.
     */
    static final int CACHE_CAP_CHARS = 10 * 1024 * 1024;

    /**
     * Per-string truncation thresholds the cap fallback walks in order. We try
     * the largest first and drop down only if the result is still over
     * {@link #CACHE_CAP_CHARS}. The largest sits well above the LLM-facing
     * MEDIUM-tier truncation length on purpose: the cap is a JVM-memory
     * safety net, not a context-window budget — most strings can stay near
     * full fidelity in cache and still fit in 10 MB.
     */
    static final int[] CAP_FALLBACK_LIMITS = {100_000, 10_000, 1_000};

    static final String CACHE_WARNING_MESSAGE = "Entity exceeded 10 MB; only MEDIUM-tier form was cached."
            + " jq queries against this entity will reflect truncated content.";

    /**
     * Per-call cap on serialized tool-output size returned to the model. When a compressed
     * payload exceeds this many characters the tool downgrades the tier one step
     * ({@code FULL → MEDIUM → SKELETON}) and recompresses, so a {@code tier=FULL} request
     * on a multi-MB trace still fits in the model's window. At ~2 chars/token worst case,
     * this caps any single read at ~20K tokens.
     *
     * <p>This is a <strong>best-effort heuristic, not a hard boundary</strong>. If the
     * payload is still over the cap once the smallest tier (SKELETON) is reached,
     * {@link #guardOutput} logs a WARN and returns the over-cap result anyway — hard-
     * truncating the JSON payload would corrupt the envelope and break the agent's tool
     * round. Additionally, {@link #buildResponse} wraps the payload in an envelope with
     * a few extra fields ({@code tier}, {@code type}, {@code id}, optional
     * {@code cache_warning}), so the final response can exceed {@code OUTPUT_SAFETY_CHARS}
     * by a few hundred chars even in the common path. Operators investigating the WARN
     * should tighten the per-tier limits in {@link TraceCompressor} or
     * {@link GenericCompressor} rather than raising this cap.
     */
    static final int OUTPUT_SAFETY_CHARS = 40_000;

    private static final ToolSpecification SPEC = ToolSpecification.builder()
            .name(NAME)
            .description("Fetch any Opik entity (trace, span, dataset, dataset_item, project) by id."
                    + " Caches the full JSON for follow-up jq/search calls and returns an adaptively"
                    + " compressed view sized to fit a token budget. Truncated string fields include"
                    + " jq-path hints showing how to recover the full value with the jq tool."
                    + " Use this to drill into a specific span at FULL tier, or to inspect"
                    + " an entity that is not the active trace. For traces, the response includes an"
                    + " `attachments` list (file_name, mime_type, media_type); load an image/audio/video"
                    + " attachment as viewable media with the get_attachment tool.")
            .parameters(JsonObjectSchema.builder()
                    .addStringProperty("type",
                            "Entity type: one of trace, span, dataset, dataset_item, project.")
                    .addStringProperty("id", "Entity id (UUID).")
                    .addStringProperty("tier",
                            "Optional. Force a specific tier: FULL, MEDIUM, SKELETON, or SUMMARY."
                                    + " Defaults to the compressor's auto-pick (size-driven for adaptive"
                                    + " compressors; ignored for fixed-tier ones such as dataset).")
                    .required("type", "id")
                    .build())
            .build();

    private final TraceService traceService;
    private final SpanService spanService;
    private final DatasetService datasetService;
    private final DatasetItemService datasetItemService;
    private final ProjectService projectService;
    private final AttachmentService attachmentService;
    private final TraceCompressor traceCompressor;
    private final DatasetCompressor datasetCompressor;
    private final GenericCompressor genericCompressor;

    @Inject
    public ReadTool(@NonNull TraceService traceService,
            @NonNull SpanService spanService,
            @NonNull DatasetService datasetService,
            @NonNull DatasetItemService datasetItemService,
            @NonNull ProjectService projectService,
            @NonNull AttachmentService attachmentService,
            @NonNull TraceCompressor traceCompressor,
            @NonNull DatasetCompressor datasetCompressor,
            @NonNull GenericCompressor genericCompressor) {
        this.traceService = traceService;
        this.spanService = spanService;
        this.datasetService = datasetService;
        this.datasetItemService = datasetItemService;
        this.projectService = projectService;
        this.attachmentService = attachmentService;
        this.traceCompressor = traceCompressor;
        this.datasetCompressor = datasetCompressor;
        this.genericCompressor = genericCompressor;
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
    public Mono<String> execute(String arguments, @NonNull TraceToolContext ctx) {
        ParsedArgs args = parseArgs(arguments);
        if (args.error != null) {
            return Mono.just(args.error);
        }

        // Mono.defer so any synchronous throw from parseUuid / cache lookups is captured as
        // a Mono error and rendered as a `{"error": "..."}` JSON string by onErrorResume.
        Mono<String> dispatch = Mono.defer(() -> switch (args.type) {
            case TRACE -> readTrace(args, ctx);
            case SPAN -> readGeneric(args, ctx, fetchSpanJsonReactive(args.id, ctx));
            case DATASET -> readDataset(args, ctx);
            case DATASET_ITEM -> readGeneric(args, ctx, fetchDatasetItemJsonReactive(args.id, ctx));
            case PROJECT -> readGeneric(args, ctx, fetchProjectJsonReactive(args.id, ctx));
            case THREAD -> Mono.just(ToolArgs.errorJson("type=thread is not supported by the read tool"));
        });

        return dispatch
                .onErrorResume(NotFoundLikeException.class, e -> Mono.just(ToolArgs.errorJson(e.getMessage())))
                .onErrorResume(Exception.class, e -> {
                    // Don't echo the raw exception message to the LLM — it can include ClickHouse
                    // query fragments, stack-trace-like detail, or internal paths. Surface a short
                    // correlation id instead so an operator can grep the warn log to find the cause.
                    String correlationId = UUID.randomUUID().toString();
                    log.warn("read tool failed for ref ('{}', '{}'), correlationId='{}'",
                            args.type, args.id, correlationId, e);
                    return Mono.just(ToolArgs.errorJson("Failed to fetch entity (ref: " + correlationId + ")"));
                });
    }

    // ---------------- Trace ----------------

    private Mono<String> readTrace(ParsedArgs args, TraceToolContext ctx) {
        UUID id = parseUuid(args.id);
        EntityRef ref = new EntityRef(EntityType.TRACE, args.id);

        log.debug("readTrace: id={}, ref={}", id, ref);

        // Cache pre-seeded by OnlineScoringLlmAsJudgeScorer for the active trace; reuse if present.
        Optional<JsonNode> cached = ctx.getCached(ref);

        Mono<TraceWithSpans> dataMono;
        if (cached.isPresent()) {
            JsonNode fullJson = cached.get();
            Trace trace = readJson(fullJson.get("trace"), Trace.class);
            List<Span> spans = readJsonList(fullJson.get("spans"), Span.class);
            dataMono = Mono.just(new TraceWithSpans(fullJson, trace, spans));
        } else {
            dataMono = withRequestContext(traceService.get(id), ctx)
                    .switchIfEmpty(Mono.error(new NotFoundLikeException("Trace not found: " + args.id)))
                    .flatMap(trace -> collectWithRequestContext(spanService.getByTraceIds(Set.of(id)), ctx)
                            .map(spans -> new TraceWithSpans(
                                    traceCompressor.buildFullJson(trace, spans), trace, spans)));
        }

        return dataMono.flatMap(data -> {
            CacheOutcome outcome = applyCacheCap(data.fullJson(), ref, ctx);
            // Always replace the cache with outcome.cachedNode — even when the cache was
            // pre-seeded (e.g. the active trace from OnlineScoringLlmAsJudgeScorer) we want
            // to swap an oversize seed for the truncated form so JVM heap stays bounded.
            // For under-cap entities outcome.cachedNode is the same node, so this is a no-op.
            ctx.cache(ref, outcome.cachedNode());

            PathAwareTruncator.SuffixStyle suffix = suffixStyleFor(ref, ctx);
            var result = guardOutput(
                    traceCompressor.compress(data.fullJson(), data.trace(), data.spans(), args.tier, suffix),
                    tier -> traceCompressor.compress(data.fullJson(), data.trace(), data.spans(), tier, suffix));
            // Surface the trace's attachments so the agent can discover them here (the structure
            // tool) and fetch one via get_attachment, rather than needing a separate list call.
            UUID projectId = data.trace() != null ? data.trace().projectId() : null;
            return listTraceAttachments(id, projectId, ctx)
                    .map(attachments -> buildResponse(args, result, outcome.warning(), attachments).toString());
        });
    }

    /**
     * Lists the trace's attachments as a compact {@code [{file_name, mime_type, media_type}]}
     * array for the read response. Best-effort: an attachment-store failure must not fail the
     * read, so errors degrade to an empty array. Returns an empty array when {@code projectId}
     * is unknown (cached trace that failed to deserialize).
     */
    private Mono<ArrayNode> listTraceAttachments(UUID traceId, UUID projectId, TraceToolContext ctx) {
        if (projectId == null) {
            return Mono.just(JsonUtils.getMapper().createArrayNode());
        }
        return withRequestContext(attachmentService.getAttachmentInfoByEntity(traceId,
                com.comet.opik.api.attachment.EntityType.TRACE, projectId), ctx)
                .map(AttachmentSummaries::toJsonArray)
                .onErrorResume(e -> {
                    log.warn("read tool failed to list attachments for trace '{}'", traceId, e);
                    return Mono.just(JsonUtils.getMapper().createArrayNode());
                });
    }

    // ---------------- Dataset ----------------

    private Mono<String> readDataset(ParsedArgs args, TraceToolContext ctx) {
        UUID id = parseUuid(args.id);
        EntityRef ref = new EntityRef(EntityType.DATASET, args.id);

        log.debug("readDataset: id={}, ref={}", id, ref);

        Optional<JsonNode> cached = ctx.getCached(ref);

        Mono<DatasetWithItems> dataMono;
        if (cached.isPresent()) {
            JsonNode fullJson = cached.get();
            Dataset dataset = readJson(fullJson.get("dataset"), Dataset.class);
            List<DatasetItem> sampleItems = readJsonList(fullJson.get("sample_items"), DatasetItem.class);
            dataMono = Mono.just(new DatasetWithItems(fullJson, dataset, sampleItems));
        } else {
            // datasetService.getById is already synchronous (returns Optional<Dataset>).
            Dataset dataset = datasetService.getById(id, ctx.getWorkspaceId())
                    .orElseThrow(() -> new NotFoundLikeException("Dataset not found: " + args.id));
            dataMono = withRequestContext(
                    datasetItemService.getItems(1, DatasetCompressor.SAMPLE_SIZE,
                            DatasetItemSearchCriteria.builder()
                                    .datasetId(id)
                                    .experimentIds(Set.of())
                                    .entityType(com.comet.opik.domain.EntityType.TRACE)
                                    .truncate(false)
                                    .build())
                            .map(page -> page.content() == null ? List.<DatasetItem>of() : page.content()),
                    ctx)
                    .map(sampleItems -> new DatasetWithItems(
                            datasetCompressor.buildFullJson(dataset, sampleItems), dataset, sampleItems));
        }

        return dataMono.map(data -> {
            CacheOutcome outcome = applyCacheCap(data.fullJson(), ref, ctx);
            ctx.cache(ref, outcome.cachedNode());

            var result = datasetCompressor.compress(data.dataset(), data.sampleItems());
            return buildResponse(args, result, outcome.warning()).toString();
        });
    }

    // ---------------- Generic (span / dataset_item / project) ----------------

    private Mono<String> readGeneric(ParsedArgs args, TraceToolContext ctx, Mono<JsonNode> fetcher) {
        EntityRef ref = new EntityRef(args.type, args.id);
        // Cold Mono: fetcher is built but not subscribed; only the cache-miss branch subscribes it.
        Mono<JsonNode> jsonMono = ctx.getCached(ref)
                .map(Mono::just)
                .orElse(fetcher);

        log.debug("readGeneric (span / dataset_item / project): id={}, ref={}", args.id, ref);

        return jsonMono.map(fullJson -> {
            CacheOutcome outcome = applyCacheCap(fullJson, ref, ctx);
            ctx.cache(ref, outcome.cachedNode());

            PathAwareTruncator.SuffixStyle suffix = suffixStyleFor(ref, ctx);
            var result = guardOutput(
                    genericCompressor.compress(fullJson, args.tier, suffix),
                    tier -> genericCompressor.compress(fullJson, tier, suffix));
            return buildResponse(args, result, outcome.warning()).toString();
        });
    }

    // ---------------- Fetchers ----------------

    private Mono<JsonNode> fetchSpanJsonReactive(String idStr, TraceToolContext ctx) {
        return Mono.defer(() -> {
            UUID id = parseUuid(idStr);
            return withRequestContext(spanService.getById(id), ctx)
                    .switchIfEmpty(Mono.error(new NotFoundLikeException("Span not found: " + idStr)))
                    .map(span -> JsonUtils.getMapper().valueToTree(span));
        });
    }

    private Mono<JsonNode> fetchDatasetItemJsonReactive(String idStr, TraceToolContext ctx) {
        return Mono.defer(() -> {
            UUID id = parseUuid(idStr);
            return withRequestContext(datasetItemService.get(id), ctx)
                    .switchIfEmpty(Mono.error(new NotFoundLikeException("Dataset item not found: " + idStr)))
                    .map(item -> JsonUtils.getMapper().valueToTree(item));
        });
    }

    private Mono<JsonNode> fetchProjectJsonReactive(String idStr, TraceToolContext ctx) {
        // projectService.get is already synchronous; fromCallable so a throw is captured.
        return Mono.fromCallable(() -> {
            UUID id = parseUuid(idStr);
            var project = projectService.get(id, ctx.getWorkspaceId());
            if (project == null) {
                throw new NotFoundLikeException("Project not found: " + idStr);
            }
            return JsonUtils.getMapper().valueToTree(project);
        });
    }

    // ---------------- Helpers ----------------

    private static <T> Mono<T> withRequestContext(Mono<T> mono, TraceToolContext ctx) {
        return mono.contextWrite(rc -> rc.put(RequestContext.WORKSPACE_ID, ctx.getWorkspaceId())
                .put(RequestContext.USER_NAME, ctx.getUserName()));
    }

    private static <T> Mono<List<T>> collectWithRequestContext(Flux<T> flux, TraceToolContext ctx) {
        return flux.collectList()
                .contextWrite(rc -> rc.put(RequestContext.WORKSPACE_ID, ctx.getWorkspaceId())
                        .put(RequestContext.USER_NAME, ctx.getUserName()));
    }

    private static UUID parseUuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new NotFoundLikeException("Invalid id format: " + id);
        }
    }

    private static <T> T readJson(JsonNode node, Class<T> type) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return JsonUtils.getMapper().treeToValue(node, type);
        } catch (Exception e) {
            log.warn("Failed to deserialize cached node to '{}: {}'", type.getSimpleName(), e.getMessage(), e);
            return null;
        }
    }

    private static <T> List<T> readJsonList(JsonNode node, Class<T> type) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<T> result = new java.util.ArrayList<>(node.size());
        node.forEach(child -> {
            T value = readJson(child, type);
            if (value != null) {
                result.add(value);
            }
        });
        return result;
    }

    /**
     * Decides what the cache should hold for {@code ref} on this call and whether
     * to surface {@code cache_warning} on the response. Sticky: once an entity
     * has been capped (either now or on a prior read in this context), every
     * subsequent read keeps emitting the warning so the LLM doesn't silently
     * see {@code tier=FULL} again on a previously-truncated cache entry.
     *
     * <p>The cached form always uses {@link PathAwareTruncator.SuffixStyle#BARE}
     * because the cache itself is the source of truth for follow-up jq queries —
     * a {@code "use jq to see full"} pointer in the cache would lead the agent
     * back into the same truncated value.
     */
    private static CacheOutcome applyCacheCap(JsonNode fullJson, EntityRef ref, TraceToolContext ctx) {
        // Input size drives the cap decision purely; the truncation flag drives the warning surfacing
        // purely. Keeping these independent prevents a future caller
        // from silently bypassing the cap by passing an uncapped node for a ref whose flag
        // was set elsewhere (or vice versa).
        boolean alreadyTruncated = ctx.isTruncated(ref);
        int size = fullJson.toString().length();
        if (size <= CACHE_CAP_CHARS) {
            return CacheOutcome.builder()
                    .cachedNode(fullJson)
                    .warning(alreadyTruncated ? CACHE_WARNING_MESSAGE : null)
                    .build();
        }
        log.debug("Entity '{}' exceeds '{}' chars; capping to maximum of '{}' chars before caching", ref, size,
                CACHE_CAP_CHARS);
        ctx.markTruncated(ref);
        return CacheOutcome.builder()
                .cachedNode(fitWithinCap(fullJson))
                .warning(CACHE_WARNING_MESSAGE)
                .build();
    }

    /**
     * Walks {@link #CAP_FALLBACK_LIMITS} from largest to smallest, returning
     * the first truncation result whose serialized size fits in
     * {@link #CACHE_CAP_CHARS}. If even the tightest limit doesn't fit (truly
     * pathological data: thousands of long strings), returns the tightest
     * attempt anyway — over-cap by a small margin is better than not caching
     * at all, since the alternative is making the agent error out.
     */
    private static JsonNode fitWithinCap(JsonNode fullJson) {
        JsonNode last = null;
        for (int limit : CAP_FALLBACK_LIMITS) {
            JsonNode candidate = PathAwareTruncator.truncate(fullJson, limit,
                    PathAwareTruncator.SuffixStyle.BARE);
            int candidateSize = candidate.toString().length();
            if (candidateSize <= CACHE_CAP_CHARS) {
                log.debug("Entity capped to {} chars with ceiling at {} limit", candidateSize, limit);
                return candidate;
            }
            last = candidate;
        }
        log.debug("Entity exceeds {} chars with ceiling at {} limit; returning tightest limit", CACHE_CAP_CHARS,
                CAP_FALLBACK_LIMITS[0]);
        return last;
    }

    /**
     * Picks the truncation suffix style for compression output: {@code BARE}
     * when the cache holds a capped form (the {@code use jq to see full}
     * pointer would be a lie), otherwise {@code WITH_JQ_HINT} so the agent
     * can drill into recoverable cached values.
     */
    private static PathAwareTruncator.SuffixStyle suffixStyleFor(EntityRef ref, TraceToolContext ctx) {
        return ctx.isTruncated(ref)
                ? PathAwareTruncator.SuffixStyle.BARE
                : PathAwareTruncator.SuffixStyle.WITH_JQ_HINT;
    }

    private static ObjectNode buildResponse(ParsedArgs args, CompressionResult result, String cacheWarning) {
        return buildResponse(args, result, cacheWarning, null);
    }

    private static ObjectNode buildResponse(ParsedArgs args, CompressionResult result, String cacheWarning,
            ArrayNode attachments) {
        ObjectNode envelope = JsonUtils.getMapper().createObjectNode();
        envelope.put("tier", effectiveTier(result.tier(), cacheWarning).name());
        envelope.put("type", args.type.name().toLowerCase());
        envelope.put("id", args.id);
        envelope.set("data", result.payload());
        if (attachments != null) {
            envelope.set("attachments", attachments);
        }
        if (cacheWarning != null) {
            envelope.put("cache_warning", cacheWarning);
        }
        if (log.isDebugEnabled()) {
            log.debug(
                    "read summary: type={}, id={}, requestedTier={}, chosenTier={}, renderedBytes={}, cacheWarning={}",
                    args.type.name().toLowerCase(), args.id, args.tier,
                    envelope.get("tier").asText(), envelope.toString().length(), cacheWarning != null);
        }
        return envelope;
    }

    /**
     * When the cache holds a capped form, the compressor is operating on
     * already-truncated input — so a {@code FULL} autopick would lie about
     * fidelity. Downgrade {@code FULL} → {@code MEDIUM} (the honest "string
     * fields path-aware-truncated" tier) when {@code cache_warning} is set.
     * {@code MEDIUM}, {@code SKELETON}, {@code SUMMARY} are already accurate
     * "less than FULL" labels and pass through unchanged.
     */
    private static CompressionTier effectiveTier(CompressionTier tier, String cacheWarning) {
        if (cacheWarning != null && tier == CompressionTier.FULL) {
            return CompressionTier.MEDIUM;
        }
        return tier;
    }

    /**
     * Recompresses at a smaller tier if the initial output exceeds {@link #OUTPUT_SAFETY_CHARS}.
     * Walks {@code FULL → MEDIUM → SKELETON}, stopping at the first tier under the cap or when
     * no smaller tier exists.
     *
     * <p>The walk drives off the LAST REQUESTED tier, not {@code current.tier()}, because
     * some compressors collapse multiple request tiers to the same returned tier (e.g.
     * {@link GenericCompressor} returns {@code tier=MEDIUM} for both {@code MEDIUM} and
     * {@code SKELETON} requests). Tracking the request prevents an infinite loop in that
     * case — once we've asked for SKELETON, the next call returns SKELETON regardless of
     * the {@code tier} field on the result.
     *
     * <p><strong>Best-effort cap:</strong> if the smallest tier is still over
     * {@link #OUTPUT_SAFETY_CHARS}, the over-cap result is returned anyway (with a WARN
     * log carrying the actual size) — see {@link #OUTPUT_SAFETY_CHARS} for why we don't
     * hard-truncate.
     */
    private static CompressionResult guardOutput(
            CompressionResult initial,
            Function<CompressionTier, CompressionResult> recompress) {
        CompressionResult current = initial;
        CompressionTier lastRequested = current.tier();
        // Bounded by the tier ladder (FULL → MEDIUM → SKELETON terminal), but capped explicitly
        // at the enum's cardinality so a future tier change can't accidentally turn this into
        // an unbounded loop even if downgradeTierOrSame's contract drifts. Cached locally
        // because Enum.values() allocates a fresh array on each call.
        int maxAttempts = CompressionTier.values().length;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            int currentSize = current.payload().toString().length();
            if (currentSize <= OUTPUT_SAFETY_CHARS) {
                return current;
            }
            CompressionTier next = downgradeTierOrSame(lastRequested);
            if (next == lastRequested) {
                // Single warn per call: we've exhausted the ladder. Emitted at most once even if
                // the agent issues many read() calls, since this is the terminal branch.
                // currentSize gives operators the actual overshoot so they can decide whether to
                // tighten the per-tier limits in TraceCompressor / GenericCompressor.
                log.warn(
                        "Read tool output '{}' chars exceeded cap '{}' at smallest tier '{}' — returning anyway (cap is best-effort, not hard)",
                        currentSize, OUTPUT_SAFETY_CHARS, lastRequested);
                return current;
            }
            // debug, not info: a many-rounds-per-evaluation agent could trigger one downgrade per
            // read call, and 2-3 downgrades per guard. Keeping this at info would multiply into
            // tens of lines per evaluation without operator value — the warn above catches the
            // case that actually matters.
            log.debug("Read tool output exceeded '{}' chars at tier '{}', downgrading to '{}'",
                    OUTPUT_SAFETY_CHARS, lastRequested, next);
            current = recompress.apply(next);
            lastRequested = next;
        }
        // Defensive: the for loop's iteration cap should never be hit in practice because the
        // tier ladder reaches a terminal state in at most 3 steps. If it does, log loudly and
        // return what we have rather than spinning.
        log.warn("Read tool guardOutput exhausted '{}' iterations without reaching a terminal tier",
                maxAttempts);
        return current;
    }

    /**
     * Returns the next-smaller tier, or the same tier when there is nothing smaller to
     * fall back to. Callers must check {@code result == input} to detect "can't downgrade
     * any further" rather than rely on a sentinel like {@code null} or {@code Optional}.
     */
    private static CompressionTier downgradeTierOrSame(CompressionTier tier) {
        return switch (tier) {
            case FULL -> CompressionTier.MEDIUM;
            case MEDIUM -> CompressionTier.SKELETON;
            case SKELETON, SUMMARY -> tier;
        };
    }

    // ---------------- Argument parsing ----------------

    private static ParsedArgs parseArgs(String arguments) {
        if (arguments == null) {
            return ParsedArgs.error(ToolArgs.errorJson("Missing arguments"));
        }
        try {
            JsonNode node = JsonUtils.getJsonNodeFromString(arguments);
            if (node == null || !node.isObject()) {
                return ParsedArgs.error(ToolArgs.errorJson("Arguments must be a JSON object"));
            }
            var typeRes = ToolArgs.parseType(node, NAME);
            if (typeRes.isError()) {
                return ParsedArgs.error(typeRes.error());
            }
            var idRes = ToolArgs.requireString(node, "id");
            if (idRes.isError()) {
                return ParsedArgs.error(idRes.error());
            }
            CompressionTier tier = null;
            String tierStr = ToolArgs.textOrNull(node.get("tier"));
            if (tierStr != null && !tierStr.isBlank()) {
                try {
                    tier = CompressionTier.valueOf(tierStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    return ParsedArgs.error(ToolArgs.errorJson("Unknown tier: " + tierStr));
                }
            }
            return ParsedArgs.builder()
                    .type(typeRes.value())
                    .id(idRes.value())
                    .tier(tier)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse read tool arguments: '{}'", arguments, e);
            return ParsedArgs.error(ToolArgs.errorJson("Malformed arguments: " + e.getMessage()));
        }
    }

    private record TraceWithSpans(JsonNode fullJson, Trace trace, List<Span> spans) {
    }

    private record DatasetWithItems(JsonNode fullJson, Dataset dataset, List<DatasetItem> sampleItems) {
    }

    @Builder(toBuilder = true)
    private record CacheOutcome(@NonNull JsonNode cachedNode, String warning) {
    }

    @Builder(toBuilder = true)
    private record ParsedArgs(EntityType type, String id, CompressionTier tier, String error) {
        static ParsedArgs error(String err) {
            return ParsedArgs.builder().error(err).build();
        }
    }

    /** Internal sentinel converted to a {@code {"error": ...}} response by {@link #execute}. */
    private static final class NotFoundLikeException extends RuntimeException {
        NotFoundLikeException(String message) {
            super(message);
        }
    }
}
