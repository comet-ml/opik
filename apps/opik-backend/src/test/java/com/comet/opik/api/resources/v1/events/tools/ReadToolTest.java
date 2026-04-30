package com.comet.opik.api.resources.v1.events.tools;

import com.comet.opik.api.Trace;
import com.comet.opik.domain.DatasetItemService;
import com.comet.opik.domain.DatasetService;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ReadToolTest {

    private final TraceService traceService = mock(TraceService.class);
    private final SpanService spanService = mock(SpanService.class);
    private final DatasetService datasetService = mock(DatasetService.class);
    private final DatasetItemService datasetItemService = mock(DatasetItemService.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final TraceCompressor traceCompressor = new TraceCompressor();
    private final DatasetCompressor datasetCompressor = new DatasetCompressor();
    private final GenericCompressor genericCompressor = new GenericCompressor();

    private final ReadTool tool = new ReadTool(traceService, spanService, datasetService,
            datasetItemService, projectService, traceCompressor, datasetCompressor, genericCompressor);

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
                tool.execute("{\"id\": \"abc\"}", ctx));

        assertThat(result.get("error").asText()).contains("Missing required argument: type");
    }

    @Test
    void unknownTypeReturnsError() {
        var ctx = newContextWithSeededTrace();

        var result = JsonUtils.getJsonNodeFromString(
                tool.execute("{\"type\": \"unicorn\", \"id\": \"abc\"}", ctx));

        assertThat(result.get("error").asText()).contains("Unknown type");
    }

    @Test
    void unknownTierReturnsError() {
        var ctx = newContextWithSeededTrace();
        var trace = ctx.getTrace();

        var result = JsonUtils.getJsonNodeFromString(
                tool.execute("{\"type\": \"trace\", \"id\": \"%s\", \"tier\": \"WHATEVER\"}"
                        .formatted(trace.id()), ctx));

        assertThat(result.get("error").asText()).contains("Unknown tier");
    }

    @Test
    void invalidUuidReturnsError() {
        var ctx = newContextWithSeededTrace();

        var result = JsonUtils.getJsonNodeFromString(
                tool.execute("{\"type\": \"trace\", \"id\": \"not-a-uuid\"}", ctx));

        assertThat(result.get("error").asText()).contains("Invalid id format");
    }

    @Test
    void threadTypeReturnsNotYetSupported() {
        var ctx = newContextWithSeededTrace();

        var result = JsonUtils.getJsonNodeFromString(
                tool.execute("{\"type\": \"thread\", \"id\": \"any-string\"}", ctx));

        assertThat(result.get("error").asText()).contains("not yet supported");
    }

    @Test
    void preSeededTraceReturnsFromCacheWithoutServiceCall() {
        var ctx = newContextWithSeededTrace();
        var trace = ctx.getTrace();

        var json = tool.execute(
                "{\"type\": \"trace\", \"id\": \"%s\"}".formatted(trace.id()), ctx);
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
                        .formatted(trace.id()), ctx));

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
                tool.execute("{\"type\": \"span\", \"id\": \"%s\"}".formatted(spanId), ctx));

        assertThat(result.get("type").asText()).isEqualTo("span");
        assertThat(result.get("tier").asText()).isEqualTo(CompressionTier.FULL.name());
        assertThat(result.get("data").get("name").asText()).isEqualTo("cached");

        verifyNoInteractions(spanService);
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
                tool.execute("{\"type\": \"span\", \"id\": \"%s\"}".formatted(spanId), ctx));

        // First read after replacing oversized cache: still includes the warning since the
        // cap is checked against the cached node which is the over-cap form.
        assertThat(result.has("cache_warning")).isTrue();
        assertThat(result.get("cache_warning").asText()).contains("MEDIUM-tier");
    }

    private static TraceToolContext newContextWithSeededTrace() {
        var trace = Trace.builder()
                .id(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .name("active")
                .startTime(Instant.now())
                .build();
        var ctx = new TraceToolContext(trace, List.of(), "ws", "user");
        // Mirror the production seed: cache the {trace, spans} composite under EntityRef(TRACE, id).
        JsonNode composite = new TraceCompressor().buildFullJson(trace, List.of());
        ctx.cache(new EntityRef(EntityType.TRACE, trace.id().toString()), composite);
        return ctx;
    }
}
