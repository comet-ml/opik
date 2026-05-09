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
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
 * <p>Errors are returned as {@code {"error": "..."}} JSON strings rather than
 * thrown — the tool-call loop must remain non-fatal.
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

    private static final ToolSpecification SPEC = ToolSpecification.builder()
            .name(NAME)
            .description("Fetch any Opik entity (trace, span, dataset, dataset_item, project) by id."
                    + " Caches the full JSON for follow-up jq/search calls and returns an adaptively"
                    + " compressed view sized to fit a token budget. Truncated string fields include"
                    + " jq-path hints showing how to recover the full value with the jq tool."
                    + " Use this to drill into a specific span at FULL tier, or to inspect"
                    + " an entity that is not the active trace.")
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
    private final TraceCompressor traceCompressor;
    private final DatasetCompressor datasetCompressor;
    private final GenericCompressor genericCompressor;

    @Inject
    public ReadTool(@NonNull TraceService traceService,
            @NonNull SpanService spanService,
            @NonNull DatasetService datasetService,
            @NonNull DatasetItemService datasetItemService,
            @NonNull ProjectService projectService,
            @NonNull TraceCompressor traceCompressor,
            @NonNull DatasetCompressor datasetCompressor,
            @NonNull GenericCompressor genericCompressor) {
        this.traceService = traceService;
        this.spanService = spanService;
        this.datasetService = datasetService;
        this.datasetItemService = datasetItemService;
        this.projectService = projectService;
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
    public String execute(String arguments, TraceToolContext ctx) {
        ParsedArgs args = parseArgs(arguments);
        if (args.error != null) {
            return args.error;
        }

        try {
            return switch (args.type) {
                case TRACE -> readTrace(args, ctx);
                case SPAN -> readGeneric(args, ctx, () -> fetchSpanJson(args.id, ctx));
                case DATASET -> readDataset(args, ctx);
                case DATASET_ITEM -> readGeneric(args, ctx, () -> fetchDatasetItemJson(args.id, ctx));
                case PROJECT -> readGeneric(args, ctx, () -> fetchProjectJson(args.id, ctx));
                case THREAD -> ToolArgs.errorJson("type=thread is not supported by the read tool");
            };
        } catch (NotFoundLikeException e) {
            return ToolArgs.errorJson(e.getMessage());
        } catch (Exception e) {
            // Don't echo the raw exception message to the LLM — it can include ClickHouse
            // query fragments, stack-trace-like detail, or internal paths. Surface a short
            // correlation id instead so an operator can grep the warn log to find the cause.
            String correlationId = UUID.randomUUID().toString();
            log.warn("read tool failed for ref ('{}', '{}'), correlationId='{}'",
                    args.type, args.id, correlationId, e);
            return ToolArgs.errorJson("Failed to fetch entity (ref: " + correlationId + ")");
        }
    }

    // ---------------- Trace ----------------

    private String readTrace(ParsedArgs args, TraceToolContext ctx) throws NotFoundLikeException {
        UUID id = parseUuid(args.id);
        EntityRef ref = new EntityRef(EntityType.TRACE, args.id);

        log.debug("readTrace: id={}, ref={}", id, ref);

        // Cache pre-seeded by OnlineScoringLlmAsJudgeScorer for the active trace; reuse if present.
        Optional<JsonNode> cached = ctx.getCached(ref);

        Trace trace;
        List<Span> spans;
        JsonNode fullJson;
        if (cached.isPresent()) {
            fullJson = cached.get();
            trace = readJson(fullJson.get("trace"), Trace.class);
            spans = readJsonList(fullJson.get("spans"), Span.class);
        } else {
            trace = blockingGet(traceService.get(id), ctx);
            if (trace == null) {
                throw new NotFoundLikeException("Trace not found: " + args.id);
            }
            spans = blockingCollect(spanService.getByTraceIds(Set.of(id)), ctx);
            fullJson = traceCompressor.buildFullJson(trace, spans);
        }

        CacheOutcome outcome = applyCacheCap(fullJson, ref, ctx);
        // Always replace the cache with outcome.cachedNode — even when the cache was
        // pre-seeded (e.g. the active trace from OnlineScoringLlmAsJudgeScorer) we want
        // to swap an oversize seed for the truncated form so JVM heap stays bounded.
        // For under-cap entities outcome.cachedNode is the same node, so this is a no-op.
        ctx.cache(ref, outcome.cachedNode);

        var result = traceCompressor.compress(fullJson, trace, spans, args.tier, suffixStyleFor(ref, ctx));
        return buildResponse(args, result, outcome.warning).toString();
    }

    // ---------------- Dataset ----------------

    private String readDataset(ParsedArgs args, TraceToolContext ctx) throws NotFoundLikeException {
        UUID id = parseUuid(args.id);
        EntityRef ref = new EntityRef(EntityType.DATASET, args.id);

        log.debug("readDataset: id={}, ref={}", id, ref);

        Optional<JsonNode> cached = ctx.getCached(ref);

        Dataset dataset;
        List<DatasetItem> sampleItems;
        JsonNode fullJson;
        if (cached.isPresent()) {
            fullJson = cached.get();
            dataset = readJson(fullJson.get("dataset"), Dataset.class);
            sampleItems = readJsonList(fullJson.get("sample_items"), DatasetItem.class);
        } else {
            dataset = datasetService.getById(id, ctx.getWorkspaceId())
                    .orElseThrow(() -> new NotFoundLikeException("Dataset not found: " + args.id));
            sampleItems = blockingCollect(
                    datasetItemService.getItems(1, DatasetCompressor.SAMPLE_SIZE,
                            DatasetItemSearchCriteria.builder()
                                    .datasetId(id)
                                    .experimentIds(Set.of())
                                    .entityType(com.comet.opik.domain.EntityType.TRACE)
                                    .truncate(false)
                                    .build())
                            .map(page -> page.content() == null ? List.of() : page.content()),
                    ctx);
            fullJson = datasetCompressor.buildFullJson(dataset, sampleItems);
        }

        CacheOutcome outcome = applyCacheCap(fullJson, ref, ctx);
        ctx.cache(ref, outcome.cachedNode);

        var result = datasetCompressor.compress(dataset, sampleItems);
        return buildResponse(args, result, outcome.warning).toString();
    }

    // ---------------- Generic (span / dataset_item / project) ----------------

    private String readGeneric(ParsedArgs args, TraceToolContext ctx, FetchSupplier fetcher)
            throws NotFoundLikeException {
        EntityRef ref = new EntityRef(args.type, args.id);
        JsonNode fullJson = ctx.getCached(ref).orElseGet(fetcher::get);

        log.debug("readGeneric (span / dataset_item / project): id={}, ref={}", args.id, ref);

        CacheOutcome outcome = applyCacheCap(fullJson, ref, ctx);
        ctx.cache(ref, outcome.cachedNode);

        var result = genericCompressor.compress(fullJson, args.tier, suffixStyleFor(ref, ctx));
        return buildResponse(args, result, outcome.warning).toString();
    }

    // ---------------- Fetchers ----------------

    private JsonNode fetchSpanJson(String idStr, TraceToolContext ctx) {
        UUID id = parseUuid(idStr);
        Span span = blockingGet(spanService.getById(id), ctx);
        if (span == null) {
            throw new NotFoundLikeException("Span not found: " + idStr);
        }
        return JsonUtils.getMapper().valueToTree(span);
    }

    private JsonNode fetchDatasetItemJson(String idStr, TraceToolContext ctx) {
        UUID id = parseUuid(idStr);
        DatasetItem item = blockingGet(datasetItemService.get(id), ctx);
        if (item == null) {
            throw new NotFoundLikeException("Dataset item not found: " + idStr);
        }
        return JsonUtils.getMapper().valueToTree(item);
    }

    private JsonNode fetchProjectJson(String idStr, TraceToolContext ctx) {
        UUID id = parseUuid(idStr);
        var project = projectService.get(id, ctx.getWorkspaceId());
        if (project == null) {
            throw new NotFoundLikeException("Project not found: " + idStr);
        }
        return JsonUtils.getMapper().valueToTree(project);
    }

    // ---------------- Helpers ----------------

    private static <T> T blockingGet(reactor.core.publisher.Mono<T> mono, TraceToolContext ctx) {
        return mono
                .contextWrite(rc -> rc.put(RequestContext.WORKSPACE_ID, ctx.getWorkspaceId())
                        .put(RequestContext.USER_NAME, ctx.getUserName()))
                .block();
    }

    private static <T> List<T> blockingCollect(reactor.core.publisher.Flux<T> flux, TraceToolContext ctx) {
        return flux.collectList()
                .contextWrite(rc -> rc.put(RequestContext.WORKSPACE_ID, ctx.getWorkspaceId())
                        .put(RequestContext.USER_NAME, ctx.getUserName()))
                .block();
    }

    private static <T> List<T> blockingCollect(reactor.core.publisher.Mono<List<T>> mono, TraceToolContext ctx) {
        var result = mono
                .contextWrite(rc -> rc.put(RequestContext.WORKSPACE_ID, ctx.getWorkspaceId())
                        .put(RequestContext.USER_NAME, ctx.getUserName()))
                .block();
        return result == null ? List.of() : result;
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
        ObjectNode envelope = JsonUtils.getMapper().createObjectNode();
        envelope.put("tier", effectiveTier(result.tier(), cacheWarning).name());
        envelope.put("type", args.type.name().toLowerCase());
        envelope.put("id", args.id);
        envelope.set("data", result.payload());
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

    @FunctionalInterface
    private interface FetchSupplier {
        JsonNode get();
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
