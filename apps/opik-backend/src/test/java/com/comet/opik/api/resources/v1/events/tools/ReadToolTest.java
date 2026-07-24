package com.comet.opik.api.resources.v1.events.tools;

import com.comet.opik.api.Trace;
import com.comet.opik.api.attachment.AttachmentInfo;
import com.comet.opik.domain.DatasetItemService;
import com.comet.opik.domain.DatasetService;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.attachment.AttachmentService;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReadToolTest {

    private final TraceService traceService = mock(TraceService.class);
    private final SpanService spanService = mock(SpanService.class);
    private final DatasetService datasetService = mock(DatasetService.class);
    private final DatasetItemService datasetItemService = mock(DatasetItemService.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final AttachmentService attachmentService = mock(AttachmentService.class);
    private final TraceCompressor traceCompressor = new TraceCompressor();
    private final DatasetCompressor datasetCompressor = new DatasetCompressor();
    private final GenericCompressor genericCompressor = new GenericCompressor();

    private final ReadTool tool = new ReadTool(traceService, spanService, datasetService,
            datasetItemService, projectService, attachmentService, traceCompressor, datasetCompressor,
            genericCompressor);

    @BeforeEach
    void stubAttachments() {
        // Trace reads now list the trace's attachments; default to none so the existing
        // assertions (which don't concern attachments) are unaffected.
        when(attachmentService.getAttachmentInfoByEntity(any(), any(), any()))
                .thenReturn(Mono.just(List.<AttachmentInfo>of()));
    }

    @Test
    void specHasExpectedNameAndRequiredFields() {
        var spec = tool.spec();

        assertThat(spec.name()).isEqualTo(ReadTool.NAME);
        assertThat(spec.parameters().required()).contains("type", "id");
    }

    @Test
    void missingArgumentsReturnsError() {
        var ctx = newContextWithSeededTrace();

        var result = JsonUtils.getJsonNodeFromString(
                tool.execute("{\"id\": \"abc\"}", ctx).block());

        assertThat(result.get("error").asText()).contains("Missing required argument: type");
    }

    @Test
    void unknownTypeReturnsError() {
        var ctx = newContextWithSeededTrace();

        var result = JsonUtils.getJsonNodeFromString(
                tool.execute("{\"type\": \"unicorn\", \"id\": \"abc\"}", ctx).block());

        assertThat(result.get("error").asText()).contains("Unknown type");
    }

    @Test
    void unknownTierReturnsError() {
        var ctx = newContextWithSeededTrace();
        var trace = ctx.getTrace();

        var result = JsonUtils.getJsonNodeFromString(
                tool.execute("{\"type\": \"trace\", \"id\": \"%s\", \"tier\": \"WHATEVER\"}"
                        .formatted(trace.id()), ctx).block());

        assertThat(result.get("error").asText()).contains("Unknown tier");
    }

    @Test
    void invalidUuidReturnsError() {
        var ctx = newContextWithSeededTrace();

        var result = JsonUtils.getJsonNodeFromString(
                tool.execute("{\"type\": \"trace\", \"id\": \"not-a-uuid\"}", ctx).block());

        assertThat(result.get("error").asText()).contains("Invalid id format");
    }

    @Test
    void threadTypeIsRejected() {
        var ctx = newContextWithSeededTrace();

        var result = JsonUtils.getJsonNodeFromString(
                tool.execute("{\"type\": \"thread\", \"id\": \"any-string\"}", ctx).block());

        assertThat(result.get("error").asText()).contains("type=thread is not supported");
    }

    @Test
    void preSeededTraceReturnsFromCacheWithoutServiceCall() {
        var ctx = newContextWithSeededTrace();
        var trace = ctx.getTrace();

        var json = tool.execute(
                "{\"type\": \"trace\", \"id\": \"%s\"}".formatted(trace.id()), ctx).block();
        var result = JsonUtils.getJsonNodeFromString(json);

        assertThat(result.get("type").asText()).isEqualTo("trace");
        assertThat(result.get("id").asText()).isEqualTo(trace.id().toString());
        assertThat(result.get("tier").asText()).isEqualTo(CompressionTier.FULL.name());
        assertThat(result.has("data")).isTrue();
        assertThat(result.get("data").has("trace")).isTrue();
        assertThat(result.get("data").has("spans")).isTrue();

        verifyNoInteractions(traceService, spanService);
    }

    @Test
    void preSeededTraceWithForcedSkeletonReturnsSkeleton() {
        var ctx = newContextWithSeededTrace();
        var trace = ctx.getTrace();

        var result = JsonUtils.getJsonNodeFromString(
                tool.execute("{\"type\": \"trace\", \"id\": \"%s\", \"tier\": \"SKELETON\"}"
                        .formatted(trace.id()), ctx).block());

        assertThat(result.get("tier").asText()).isEqualTo(CompressionTier.SKELETON.name());
        assertThat(result.get("data").has("span_count")).isTrue();
        assertThat(result.get("data").has("span_tree")).isTrue();
    }

    @Test
    void cacheHitForGenericEntityReturnsCachedView() {
        var ctx = newContextWithSeededTrace();
        var spanId = UUID.randomUUID();
        var spanJson = JsonUtils.getJsonNodeFromString("{\"id\":\"%s\",\"name\":\"cached\"}".formatted(spanId));
        ctx.cache(new EntityRef(EntityType.SPAN, spanId.toString()), spanJson);

        var result = JsonUtils.getJsonNodeFromString(
                tool.execute("{\"type\": \"span\", \"id\": \"%s\"}".formatted(spanId), ctx).block());

        assertThat(result.get("type").asText()).isEqualTo("span");
        assertThat(result.get("tier").asText()).isEqualTo(CompressionTier.FULL.name());
        assertThat(result.get("data").get("name").asText()).isEqualTo("cached");

        verifyNoInteractions(spanService);
    }

    @Test
    void capFallbackPrefersLargestThresholdWhenItFits() {
        // Single huge string blows the cap. The 100K (largest) threshold easily
        // brings the cache form well under 10 MB, so we expect the cached
        // string to be at the 100K limit, not 10K or 1K.
        var spanId = UUID.randomUUID();
        var ref = new EntityRef(EntityType.SPAN, spanId.toString());
        var ctx = newContextWithSeededTrace();

        String huge = "x".repeat(ReadTool.CACHE_CAP_CHARS + 100); // > 10 MB
        var spanJson = JsonUtils.getMapper().createObjectNode();
        spanJson.put("id", spanId.toString());
        spanJson.put("input", huge);
        ctx.cache(ref, spanJson);

        // Trigger the cap path.
        tool.execute("{\"type\": \"span\", \"id\": \"%s\"}".formatted(spanId), ctx).block();

        assertThat(ctx.isTruncated(ref)).isTrue();
        var cached = ctx.getCached(ref).orElseThrow();
        var cachedInput = cached.get("input").asText();
        // 100K kept + bare suffix, no jq hint.
        assertThat(cachedInput).hasSizeBetween(100_000, 100_100);
        assertThat(cachedInput).contains("[TRUNCATED").doesNotContain("use jq");
    }

    @Test
    void capFallbackTightensWhenLargestThresholdStillOverflows() {
        // Build many strings that fit through the 100K threshold without
        // truncation (each is 60K, < 100K) but together remain over the 10 MB
        // cap → ladder must drop to 10K. 250 fields × 60K chars each = ~15 MB
        // raw; at 100K each, no truncation fires and the cache is still ~15 MB
        // (over); at 10K each, ~2.5 MB (under).
        var spanId = UUID.randomUUID();
        var ref = new EntityRef(EntityType.SPAN, spanId.toString());
        var ctx = newContextWithSeededTrace();

        var spanJson = JsonUtils.getMapper().createObjectNode();
        spanJson.put("id", spanId.toString());
        String filler = "y".repeat(60_000);
        for (int i = 0; i < 250; i++) {
            spanJson.put("field_" + i, filler);
        }
        ctx.cache(ref, spanJson);

        tool.execute("{\"type\": \"span\", \"id\": \"%s\"}".formatted(spanId), ctx).block();

        var cached = ctx.getCached(ref).orElseThrow();
        var sampleField = cached.get("field_0").asText();
        // 100K threshold would have kept ~100K — but the resulting cache was still
        // over 10 MB, so the ladder must have dropped to 10K.
        assertThat(sampleField).hasSizeBetween(10_000, 10_100);
        assertThat(cached.toString().length())
                .as("after multi-tier fallback the cached form must fit under the cap")
                .isLessThanOrEqualTo(ReadTool.CACHE_CAP_CHARS);
    }

    @Test
    void responseIncludesCacheWarningWhenFullExceedsCap() {
        var ctx = newContextWithSeededTrace();
        var spanId = UUID.randomUUID();

        // Build a JSON whose stringified size exceeds the 10MB char cap.
        String huge = "x".repeat(ReadTool.CACHE_CAP_CHARS + 100);
        var spanJson = JsonUtils.getJsonNodeFromString(
                "{\"id\":\"%s\",\"input\":\"%s\"}".formatted(spanId, huge));
        ctx.cache(new EntityRef(EntityType.SPAN, spanId.toString()), spanJson);

        var result = JsonUtils.getJsonNodeFromString(
                tool.execute("{\"type\": \"span\", \"id\": \"%s\"}".formatted(spanId), ctx).block());

        // First read after replacing oversized cache: still includes the warning since the
        // cap is checked against the cached node which is the over-cap form.
        assertThat(result.has("cache_warning")).isTrue();
        assertThat(result.get("cache_warning").asText()).contains("MEDIUM-tier");
    }

    @Test
    void cacheWarningPersistsOnSubsequentReadAfterTruncation() {
        var ctx = newContextWithSeededTrace();
        var spanId = UUID.randomUUID();
        var ref = new EntityRef(EntityType.SPAN, spanId.toString());

        // Empty cache. First read of an oversized fetched entity caches the
        // truncated form and emits the warning.
        String huge = "x".repeat(ReadTool.CACHE_CAP_CHARS + 100);
        var oversized = JsonUtils.getJsonNodeFromString(
                "{\"id\":\"%s\",\"input\":\"%s\"}".formatted(spanId, huge));
        // Pre-populate the cache as if the (uncapped) fetch already completed,
        // so we don't need to wire SpanService just to drive the cap path.
        ctx.cache(ref, oversized);

        var first = JsonUtils.getJsonNodeFromString(
                tool.execute("{\"type\": \"span\", \"id\": \"%s\"}".formatted(spanId), ctx).block());
        assertThat(first.has("cache_warning")).isTrue();
        assertThat(ctx.isTruncated(ref))
                .as("cap must be sticky after first triggering call")
                .isTrue();

        // Simulate the cache now holding the truncated (under-cap) form, as it
        // would after a real fetch path: ReadTool would have replaced the cache
        // with outcome.cachedNode. We mirror that here.
        ctx.cache(ref, JsonUtils.getJsonNodeFromString(
                "{\"id\":\"%s\",\"input\":\"x[TRUNCATED ...]\"}".formatted(spanId)));

        var second = JsonUtils.getJsonNodeFromString(
                tool.execute("{\"type\": \"span\", \"id\": \"%s\"}".formatted(spanId), ctx).block());
        assertThat(second.has("cache_warning"))
                .as("warning must persist on subsequent reads of a truncated cache")
                .isTrue();
        assertThat(second.get("cache_warning").asText()).contains("MEDIUM-tier");
        // The compressor sees a small (truncated) cache and would autopick FULL;
        // ReadTool must downgrade the reported tier to MEDIUM so the LLM doesn't
        // see "tier=FULL" alongside a cache_warning.
        assertThat(second.get("tier").asText())
                .as("tier=FULL would lie about fidelity when cache is truncated")
                .isEqualTo(CompressionTier.MEDIUM.name());
    }

    @Test
    void jqHintsAreStrippedFromTruncationSuffixesWhenCacheIsCapped() {
        var ctx = newContextWithSeededTrace();
        var spanId = UUID.randomUUID();
        var ref = new EntityRef(EntityType.SPAN, spanId.toString());

        // Pre-seed the cache with a value containing a long string AND already
        // mark the entity as truncated. The compressor will produce truncation
        // suffixes embedding `— use jq('.input') to see full`; ReadTool should
        // strip that segment because the cache can't actually deliver the full
        // value (cache_warning is the authoritative note).
        String longInput = "y".repeat(2_000); // > GenericCompressor 1000-char threshold for MEDIUM
        var json = JsonUtils.getMapper().createObjectNode();
        json.put("id", spanId.toString());
        json.put("input", longInput);
        ctx.cache(ref, json);
        ctx.markTruncated(ref);

        var result = JsonUtils.getJsonNodeFromString(
                tool.execute("{\"type\": \"span\", \"id\": \"%s\", \"tier\": \"MEDIUM\"}"
                        .formatted(spanId), ctx).block());

        var inputText = result.get("data").get("input").asText();
        assertThat(inputText)
                .as("truncation suffix must keep the bare [TRUNCATED N chars] form")
                .containsPattern("\\[TRUNCATED [0-9,]+ chars]");
        assertThat(inputText)
                .as("dishonest 'use jq to see full' segment must be stripped")
                .doesNotContain("use jq")
                .doesNotContain("to see full");
        assertThat(result.has("cache_warning")).isTrue();
    }

    @Test
    void jqHintsAreKeptWhenCacheIsNotCapped() {
        // Counterpart to the above: when the cache is healthy, the per-string
        // hints DO point at recoverable values and must be preserved so the
        // agent knows where to drill in.
        var trace = Trace.builder()
                .id(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .name("trace-" + RandomStringUtils.secure().nextAlphanumeric(8))
                .startTime(Instant.now())
                .build();
        var ctx = TraceToolContext.forActiveTrace(trace, List.of(),
                "ws-" + RandomStringUtils.secure().nextAlphanumeric(8),
                "user-" + RandomStringUtils.secure().nextAlphanumeric(8));

        var spanId = UUID.randomUUID();
        var ref = new EntityRef(EntityType.SPAN, spanId.toString());
        String longInput = "z".repeat(2_000);
        var json = JsonUtils.getMapper().createObjectNode();
        json.put("id", spanId.toString());
        json.put("input", longInput);
        ctx.cache(ref, json);
        // Note: NO markTruncated — cache is healthy.

        var result = JsonUtils.getJsonNodeFromString(
                tool.execute("{\"type\": \"span\", \"id\": \"%s\", \"tier\": \"MEDIUM\"}"
                        .formatted(spanId), ctx).block());

        var inputText = result.get("data").get("input").asText();
        assertThat(inputText).contains("use jq('.input') to see full");
        assertThat(result.has("cache_warning")).isFalse();
    }

    @Test
    void oversizedFullSpanDowngradesToMedium() {
        // A span whose FULL-tier serialization exceeds OUTPUT_SAFETY_CHARS must be
        // auto-downgraded by guardOutput. MEDIUM truncates each string > 1000 chars,
        // so the resulting payload comfortably fits the safety cap.
        var ctx = newContextWithSeededTrace();
        var spanId = UUID.randomUUID();
        // Single string field much larger than the per-call cap; cache cap (10 MB)
        // is not in play, so there is no `cache_warning` to confound the assertion.
        String fat = "x".repeat(ReadTool.OUTPUT_SAFETY_CHARS + 10_000);
        var spanJson = JsonUtils.getMapper().createObjectNode();
        spanJson.put("id", spanId.toString());
        spanJson.put("input", fat);
        ctx.cache(new EntityRef(EntityType.SPAN, spanId.toString()), spanJson);

        var result = JsonUtils.getJsonNodeFromString(
                tool.execute("{\"type\": \"span\", \"id\": \"%s\", \"tier\": \"FULL\"}"
                        .formatted(spanId), ctx).block());

        assertThat(result.get("tier").asText())
                .as("tier=FULL must downgrade to MEDIUM when output exceeds safety cap")
                .isEqualTo(CompressionTier.MEDIUM.name());
        assertThat(result.get("data").get("input").asText()).contains("[TRUNCATED");
        assertThat(result.has("cache_warning")).isFalse();
    }

    @Test
    void guardOutputTerminatesEvenWhenCompressorCollapsesTiers() {
        // Regression for an infinite-loop bug: GenericCompressor.pickTier collapses
        // MEDIUM, SKELETON and SUMMARY requests all to MEDIUM. If guardOutput drove
        // its walk off `current.tier()` (which is always MEDIUM after the first
        // downgrade), it would keep asking for SKELETON, getting MEDIUM back, and
        // looping forever. Many fields × moderate size each is the data shape that
        // surfaces it: MEDIUM truncation per-string still leaves the whole entity
        // larger than OUTPUT_SAFETY_CHARS.
        var ctx = newContextWithSeededTrace();
        var spanId = UUID.randomUUID();
        var spanJson = JsonUtils.getMapper().createObjectNode();
        spanJson.put("id", spanId.toString());
        // 60 fields × 5 KB each = 300 KB raw → MEDIUM truncates each to 1 KB =
        // ~60 KB, still > 40 K cap, would loop without the fix.
        String filler = "k".repeat(5_000);
        for (int i = 0; i < 60; i++) {
            spanJson.put("field_" + i, filler);
        }
        ctx.cache(new EntityRef(EntityType.SPAN, spanId.toString()), spanJson);

        // The bug manifested as a non-terminating loop, so the assertion here is
        // primarily that the call returns at all (no timeout). Sanity-check the
        // response shape.
        var result = JsonUtils.getJsonNodeFromString(
                tool.execute("{\"type\": \"span\", \"id\": \"%s\", \"tier\": \"FULL\"}"
                        .formatted(spanId), ctx).block());

        assertThat(result.has("data")).isTrue();
        // After exhausting tier downgrades, the tier reported back is the smallest
        // the compressor would produce — for GenericCompressor that's MEDIUM.
        assertThat(result.get("tier").asText()).isEqualTo(CompressionTier.MEDIUM.name());
    }

    @Test
    void traceReadListsAttachmentsAsCompactSummaries() {
        var traceId = UUID.randomUUID();
        var projectId = UUID.randomUUID();
        String fileName = "img-" + RandomStringUtils.secure().nextAlphanumeric(8) + ".png";
        var trace = Trace.builder()
                .id(traceId)
                .projectId(projectId)
                .name("trace-" + RandomStringUtils.secure().nextAlphanumeric(8))
                .startTime(Instant.now())
                .build();
        var ctx = TraceToolContext.forActiveTrace(trace, List.of(),
                "ws-" + RandomStringUtils.secure().nextAlphanumeric(8),
                "user-" + RandomStringUtils.secure().nextAlphanumeric(8));
        ctx.cache(new EntityRef(EntityType.TRACE, traceId.toString()),
                traceCompressor.buildFullJson(trace, List.of()));

        // Override the @BeforeEach empty default: this trace has one image attachment. The read
        // response must surface it so the judge can call get_attachment(fileName) without a
        // separate list round-trip.
        when(attachmentService.getAttachmentInfoByEntity(any(), any(), any()))
                .thenReturn(Mono.just(List.of(AttachmentInfo.builder()
                        .fileName(fileName)
                        .mimeType("image/png")
                        .entityType(com.comet.opik.api.attachment.EntityType.TRACE)
                        .entityId(traceId)
                        .containerId(projectId)
                        .fileSize(0L)
                        .build())));

        var result = JsonUtils.getJsonNodeFromString(
                tool.execute("{\"type\": \"trace\", \"id\": \"%s\"}".formatted(traceId), ctx).block());

        var attachments = result.get("attachments");
        assertThat(attachments).isNotNull();
        assertThat(attachments.isArray()).isTrue();
        assertThat(attachments).hasSize(1);
        var entry = attachments.get(0);
        // Self-describing: type + id identify the owner so get_attachment args copy verbatim.
        assertThat(entry.get("type").asText()).isEqualTo("trace");
        assertThat(entry.get("id").asText()).isEqualTo(traceId.toString());
        assertThat(entry.get("file_name").asText()).isEqualTo(fileName);
        assertThat(entry.get("mime_type").asText()).isEqualTo("image/png");
        assertThat(entry.get("media_type").asText()).isEqualTo("image");
    }

    @Test
    void attachmentListCachedAfterFirstRead_serviceNotCalledOnReread() {
        var traceId = UUID.randomUUID();
        var projectId = UUID.randomUUID();
        String fileName = "img-" + RandomStringUtils.secure().nextAlphanumeric(8) + ".png";
        var trace = Trace.builder()
                .id(traceId)
                .projectId(projectId)
                .name("trace-" + RandomStringUtils.secure().nextAlphanumeric(8))
                .startTime(Instant.now())
                .build();
        var ctx = TraceToolContext.forActiveTrace(trace, List.of(),
                "ws-" + RandomStringUtils.secure().nextAlphanumeric(8),
                "user-" + RandomStringUtils.secure().nextAlphanumeric(8));
        ctx.cache(new EntityRef(EntityType.TRACE, traceId.toString()),
                traceCompressor.buildFullJson(trace, List.of()));

        when(attachmentService.getAttachmentInfoByEntity(any(), any(), any()))
                .thenReturn(Mono.just(List.of(AttachmentInfo.builder()
                        .fileName(fileName)
                        .mimeType("image/png")
                        .entityType(com.comet.opik.api.attachment.EntityType.TRACE)
                        .entityId(traceId)
                        .containerId(projectId)
                        .fileSize(1024L)
                        .build())));

        String args = "{\"type\": \"trace\", \"id\": \"%s\"}".formatted(traceId);
        var firstResult = JsonUtils.getJsonNodeFromString(tool.execute(args, ctx).block());
        var secondResult = JsonUtils.getJsonNodeFromString(tool.execute(args, ctx).block());

        // Service must have been called exactly once — the second read hits the cache.
        verify(attachmentService, times(1)).getAttachmentInfoByEntity(any(), any(), any());

        // Both reads must expose the same attachment.
        assertThat(firstResult.get("attachments").get(0).get("file_name").asText()).isEqualTo(fileName);
        assertThat(secondResult.get("attachments").get(0).get("file_name").asText()).isEqualTo(fileName);
    }

    @Test
    void traceReadRendersEmptyAttachmentsArrayWhenTraceHasNone() {
        // The @BeforeEach default stubs an empty list; assert the envelope still carries an (empty)
        // attachments array so the judge sees "no attachments" explicitly rather than the key being
        // absent.
        var traceId = UUID.randomUUID();
        var trace = Trace.builder()
                .id(traceId)
                .projectId(UUID.randomUUID())
                .name("trace-" + RandomStringUtils.secure().nextAlphanumeric(8))
                .startTime(Instant.now())
                .build();
        var ctx = TraceToolContext.forActiveTrace(trace, List.of(),
                "ws-" + RandomStringUtils.secure().nextAlphanumeric(8),
                "user-" + RandomStringUtils.secure().nextAlphanumeric(8));
        ctx.cache(new EntityRef(EntityType.TRACE, traceId.toString()),
                traceCompressor.buildFullJson(trace, List.of()));

        var result = JsonUtils.getJsonNodeFromString(
                tool.execute("{\"type\": \"trace\", \"id\": \"%s\"}".formatted(traceId), ctx).block());

        var attachments = result.get("attachments");
        assertThat(attachments).isNotNull();
        assertThat(attachments.isArray()).isTrue();
        assertThat(attachments).isEmpty();
    }

    private static TraceToolContext newContextWithSeededTrace() {
        var trace = Trace.builder()
                .id(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .name("trace-" + RandomStringUtils.secure().nextAlphanumeric(8))
                .startTime(Instant.now())
                .build();
        var ctx = TraceToolContext.forActiveTrace(trace, List.of(),
                "ws-" + RandomStringUtils.secure().nextAlphanumeric(8),
                "user-" + RandomStringUtils.secure().nextAlphanumeric(8));
        // Mirror the production seed: cache the {trace, spans} composite under EntityRef(TRACE, id).
        JsonNode composite = new TraceCompressor().buildFullJson(trace, List.of());
        ctx.cache(new EntityRef(EntityType.TRACE, trace.id().toString()), composite);
        return ctx;
    }
}
