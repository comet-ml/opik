package com.comet.opik.domain;

import com.comet.opik.api.Project;
import com.comet.opik.api.SpanBatch;
import com.comet.opik.api.Trace;
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
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.util.Base64;
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
                            .filter(OpenTelemetryMappingRule::isValidInstrumentation)
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

        // check if there spans without parentId: we will use them as a Trace too
        return Flux.fromStream(opikSpans.stream().filter(span -> span.parentSpanId() == null))
                .flatMap(rootSpan -> {
                    var trace = Trace.builder()
                            .id(rootSpan.traceId())
                            .name(rootSpan.name())
                            .projectName(rootSpan.projectName())
                            .startTime(rootSpan.startTime())
                            .endTime(rootSpan.endTime())
                            .duration(rootSpan.duration())
                            .input(rootSpan.input())
                            .output(rootSpan.output())
                            .metadata(rootSpan.metadata())
                            .build();

                    return traceService.create(trace);
                })
                .doOnNext(traceId -> log.info("TraceId '{}' created", traceId))
                .then(Mono.defer(() -> {
                    var spanBatch = SpanBatch.builder().spans(opikSpans).build();

                    log.info("Parsed OpenTelemetry span batch for project '{}' into {} spans", projectName,
                            opikSpans.size());

                    return spanService.create(spanBatch);
                }));
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
