package com.comet.opik.domain;

import com.comet.opik.api.Project;
import com.comet.opik.api.SpanBatch;
import com.comet.opik.api.Trace;
import com.comet.opik.domain.mapping.OpenTelemetryMappingRuleFactory;
import com.comet.opik.infrastructure.OpenTelemetryConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.ImplementedBy;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.trace.v1.Span;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@ImplementedBy(OpenTelemetryServiceImpl.class)
public interface OpenTelemetryService {

    Mono<Long> parseAndStoreSpans(@NonNull ExportTraceServiceRequest traceRequest, @NonNull String projectName);
}

@Singleton
@RequiredArgsConstructor
@Slf4j
class OpenTelemetryServiceImpl implements OpenTelemetryService {

    private final @NonNull TraceService traceService;
    private final @NonNull SpanService spanService;
    private final @NonNull ProjectService projectService;
    private final @NonNull RedissonReactiveClient redisson;
    private final @NonNull OpenTelemetryConfig config;

    @Inject
    public OpenTelemetryServiceImpl(@NonNull @Config("openTelemetry") OpenTelemetryConfig config,
            @NonNull TraceService traceService,
            @NonNull SpanService spanService,
            @NonNull ProjectService projectService,
            @NonNull RedissonReactiveClient redisson) {
        this.config = config;
        this.traceService = traceService;
        this.spanService = spanService;
        this.projectService = projectService;
        this.redisson = redisson;
    }

    @Override
    public Mono<Long> parseAndStoreSpans(@NonNull ExportTraceServiceRequest traceRequest, @NonNull String projectName) {

        // make sure project exists before starting processing
        return projectService.getOrCreate(projectName)
                .map(Project::id)
                .flatMap(projectId -> Mono.deferContextual(ctx -> {
                    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

                    // extracts all otel spans in the batch, sorted by start time
                    var otelSpans = traceRequest.getResourceSpansList().stream()
                            .flatMap(resourceSpans -> resourceSpans.getScopeSpansList().stream())
                            .flatMap(scopeSpans -> scopeSpans.getSpansList().stream())
                            .toList();

                    // find out whats the name of the integration library
                    var integrationName = traceRequest.getResourceSpansList().stream()
                            .flatMap(resourceSpans -> resourceSpans.getScopeSpansList().stream())
                            .map(scopeSpans -> scopeSpans.getScope().getName())
                            .distinct()
                            .filter(OpenTelemetryMappingRuleFactory::isValidInstrumentation)
                            .findFirst()
                            .orElse(null);

                    // otelTraceId -> minimum timestamp seen with that traceId
                    var otelTracesAndMinTimestamp = otelSpans.stream()
                            .collect(Collectors.toMap(Span::getTraceId, Span::getStartTimeUnixNano, Math::min));

                    // get or create a mapping of otel trace id -> opik trace id
                    return otelToOpikTraceIdMapper(otelTracesAndMinTimestamp, projectId, workspaceId)
                            .flatMap(traceMapper -> doStoreSpans(otelSpans, traceMapper, projectName, integrationName));
                })).subscribeOn(Schedulers.boundedElastic());
    }

    private String base64OtelId(ByteString idBytes) {
        return Base64.getEncoder().encodeToString(idBytes.toByteArray());
    }

    private String redisKey(String workspaceId, UUID projectId, String otelId) {
        return "otelTraceId:" + workspaceId + ":" + projectId + ":" + otelId;
    }

    private Mono<Long> doStoreSpans(List<Span> otelSpans, Map<String, UUID> traceIdMapper, String projectName,
            String integrationName) {

        // converts otel spans into opik spans, using the mapped opik trace id
        var opikSpans = otelSpans.stream()
                .map(otelSpan -> {
                    var otelTraceIdBase64 = base64OtelId(otelSpan.getTraceId());

                    var opikTraceId = traceIdMapper.get(otelTraceIdBase64);

                    return OpenTelemetryMapper.toOpikSpan(otelSpan, opikTraceId, integrationName);
                })
                .map(opikSpan -> opikSpan.toBuilder()
                        .projectName(projectName)
                        .build())
                .toList();

        // Deduplicate usage: if a parent span's usage equals the sum of its children's usage,
        // zero out the parent's usage to avoid double-counting
        var deduplicatedSpans = deduplicateParentSpanUsage(opikSpans);

        // check if there spans without parentId: we will use them as a Trace too
        return Flux.fromStream(deduplicatedSpans.stream().filter(span -> span.parentSpanId() == null))
                .flatMap(rootSpan -> {
                    // Extract thread_id from root span metadata if present
                    String threadId = null;
                    if (rootSpan.metadata() != null && rootSpan.metadata().has("thread_id")) {
                        threadId = rootSpan.metadata().get("thread_id").asText();
                    }

                    var traceBuilder = Trace.builder()
                            .id(rootSpan.traceId())
                            .name(rootSpan.name())
                            .projectName(rootSpan.projectName())
                            .startTime(rootSpan.startTime())
                            .endTime(rootSpan.endTime())
                            .duration(rootSpan.duration())
                            .input(rootSpan.input())
                            .output(rootSpan.output())
                            .metadata(rootSpan.metadata());

                    if (StringUtils.isNotBlank(threadId)) {
                        traceBuilder.threadId(threadId);
                    }

                    return traceService.create(traceBuilder.build());
                })
                .doOnNext(traceId -> log.info("TraceId '{}' created", traceId))
                .then(Mono.defer(() -> {
                    var spanBatch = SpanBatch.builder().spans(deduplicatedSpans).build();

                    log.info("Parsed OpenTelemetry span batch for project '{}' into {} spans", projectName,
                            deduplicatedSpans.size());

                    return spanService.create(spanBatch);
                }));
    }

    /**
     * Deduplicates usage in parent spans when they exactly match the sum of their children's usage.
     * This handles cases where instrumentation libraries (e.g., LangGraph wrapping Pydantic AI)
     * aggregate child span usage into parent spans, causing double-counting.
     *
     * @param spans the list of spans to deduplicate
     * @return the list of spans with deduplicated usage
     */
    private List<com.comet.opik.api.Span> deduplicateParentSpanUsage(List<com.comet.opik.api.Span> spans) {
        // Build a map of span ID to span for quick lookup
        var spanMap = spans.stream()
                .collect(Collectors.toMap(com.comet.opik.api.Span::id, span -> span));

        // Build a map of parent span ID to list of child spans
        var childrenByParent = spans.stream()
                .filter(span -> span.parentSpanId() != null)
                .collect(Collectors.groupingBy(com.comet.opik.api.Span::parentSpanId));

        // For each parent span, check if its usage equals the sum of its children's usage
        return spans.stream()
                .map(span -> {
                    var children = childrenByParent.get(span.id());
                    if (children == null || children.isEmpty()) {
                        // No children, keep the span as is
                        return span;
                    }

                    // Calculate the sum of children's usage
                    Map<String, Integer> childrenUsageSum = new java.util.HashMap<>();
                    for (var child : children) {
                        if (child.usage() != null) {
                            child.usage().forEach((key, value) -> childrenUsageSum.merge(key, value, Integer::sum));
                        }
                    }

                    // If parent has no usage, keep it as is
                    if (span.usage() == null || span.usage().isEmpty()) {
                        return span;
                    }

                    // If children have no usage, keep parent as is
                    if (childrenUsageSum.isEmpty()) {
                        return span;
                    }

                    // Check if parent usage exactly matches children usage sum
                    boolean usageMatches = span.usage().size() == childrenUsageSum.size() &&
                            span.usage().entrySet().stream()
                                    .allMatch(entry -> entry.getValue().equals(childrenUsageSum.get(entry.getKey())));

                    if (usageMatches) {
                        // Parent usage is just aggregated from children, zero it out
                        log.info("Deduplicating usage for parent span '{}' (name: '{}') - usage matches sum of {} children",
                                span.id(), span.name(), children.size());
                        return span.toBuilder()
                                .usage(null)
                                .build();
                    }

                    // Parent has different usage, keep it as is
                    return span;
                })
                .toList();
    }

    private Mono<Map<String, UUID>> otelToOpikTraceIdMapper(Map<ByteString, Long> otelTraceIds, UUID projectId,
            String workspaceId) {
        // checks Redis for the otel traceIds in the batch; have we seen them before?
        // maps (base64 otel id -> UUIDv7 opik id)
        return Flux.fromIterable(otelTraceIds.entrySet()).flatMap(otelPack -> {
            var otelTraceId = otelPack.getKey();
            var otelTimestamp = Duration.ofNanos(otelPack.getValue()).toMillis();

            var otelTraceIdBase64 = base64OtelId(otelTraceId);

            // checks if this key is mapped in redis
            var otelTraceIdRedisKey = redisKey(workspaceId, projectId, otelTraceIdBase64);
            var checkId = redisson.getBucket(otelTraceIdRedisKey).getAndExpire(config.getTtl().toJavaDuration());

            return checkId.switchIfEmpty(Mono.defer(() -> {
                // its an unknown otel trace id, lets create an opik trace id with this span timestamp as we sorted otel
                // spans by time on previous step, it will be the closest time possible for the actual trace start
                var opikTraceId = OpenTelemetryMapper.convertOtelIdToUUIDv7(otelTraceId.toByteArray(), otelTimestamp);

                log.info("Creating mapping in Redis for otel trace id '{}' -> opik trace id '{}'", otelTraceIdRedisKey,
                        opikTraceId);
                return redisson.getBucket(otelTraceIdRedisKey)
                        .set(opikTraceId.toString(), config.getTtl().toJavaDuration())
                        .then(Mono.just(opikTraceId.toString()));
            })).map(opikTraceId -> Map.entry(otelTraceIdBase64, opikTraceId));
        }).collectMap(Map.Entry::getKey, entry -> UUID.fromString((String) entry.getValue()));
    }
}
