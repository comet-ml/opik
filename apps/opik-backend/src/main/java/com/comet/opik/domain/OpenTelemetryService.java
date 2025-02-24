package com.comet.opik.domain;

import com.comet.opik.api.SpanBatch;
import com.comet.opik.api.Trace;
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

import java.time.Duration;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ImplementedBy(OpenTelemetryServiceImpl.class)
public interface OpenTelemetryService {

    Mono<Long> parseAndStoreSpans(@NonNull ExportTraceServiceRequest traceRequest, @NonNull String projectName);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class OpenTelemetryServiceImpl implements OpenTelemetryService {

    private final @NonNull TraceService traceService;
    private final @NonNull SpanService spanService;
    private final @NonNull ProjectService projectService;
    private final @NonNull RedissonReactiveClient redisson;

    private static final Duration REDIS_TTL = Duration.ofDays(1L);

    @Override
    public Mono<Long> parseAndStoreSpans(@NonNull ExportTraceServiceRequest traceRequest, @NonNull String projectName) {

        // make sure project exists before starting processing
        return Mono.deferContextual(ctx -> {
            String userName = ctx.get(RequestContext.USER_NAME);
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            return Mono.just(projectService.getOrCreate(workspaceId, projectName, userName).id());
        }).flatMap(projectId -> Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            // extracts all otel spans in the batch, sorted by start time
            var otelSpans = traceRequest.getResourceSpansList().stream()
                    .flatMap(resourceSpans -> resourceSpans.getScopeSpansList().stream())
                    .flatMap(scopeSpans -> scopeSpans.getSpansList().stream())
                    .sorted(Comparator.comparing(Span::getStartTimeUnixNano))
                    .toList();

            // get or create a mapping of otel trace id -> opik trace id
            final Map<String, UUID> traceIdMapper = otelToOpikTraceIdMapper(otelSpans, projectId, workspaceId);

            return doStoreSpans(otelSpans, traceIdMapper, projectName);
        }));
    }

    private String base64OtelId(ByteString idBytes) {
        return Base64.getEncoder().encodeToString(idBytes.toByteArray());
    }

    private String redisKey(String workspaceId, UUID projectId, String otelId) {
        return workspaceId + ":" + projectId + ":" + otelId;
    }

    private Mono<Long> doStoreSpans(List<Span> otelSpans, Map<String, UUID> traceIdMapper, String projectName) {

        // converts otel spans into opik spans, using the mapped opik trace id
        var opikSpans = otelSpans.stream()
                .map(otelSpan -> {
                    var otelTraceIdBase64 = base64OtelId(otelSpan.getTraceId());

                    var opikTraceId = traceIdMapper.get(otelTraceIdBase64);
                    log.info("'{}' -> '{}'", otelTraceIdBase64, opikTraceId);

                    return OpenTelemetryMapper.toOpikSpan(otelSpan, opikTraceId);
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

    private Map<String, UUID> otelToOpikTraceIdMapper(List<Span> otelSpans, UUID projectId, String workspaceId) {
        // checks Redis for the otel traceIds in the batch; have we seen them before?
        // maps (base64 otel id -> UUIDv7 opik id)
        final Map<String, UUID> traceIdMapper = new HashMap<>();

        otelSpans.forEach(otelSpan -> {
            var otelTraceId = otelSpan.getTraceId();
            var otelTraceIdBase64 = base64OtelId(otelTraceId);

            // do we know this traceId? if we do, skip step
            if (traceIdMapper.containsKey(otelTraceIdBase64)) {
                return;
            }

            // checks if this key is mapped in redis
            var otelTraceIdRedisKey = redisKey(workspaceId, projectId, otelTraceIdBase64);
            var optOpikTraceId = Optional
                    .ofNullable((String) redisson.getBucket(otelTraceIdRedisKey).getAndExpire(REDIS_TTL).block());

            final UUID opikTraceId;
            if (optOpikTraceId.isPresent()) {
                // its a known otel trace id from a previous batch, lets just reuse it
                opikTraceId = UUID.fromString(optOpikTraceId.get());
            } else {
                // its an unknown otel trace id, lets create an opik trace id with this span timestamp as we sorted otel
                // spans by time on previous step, it will be the closest time possible for the actual trace start
                var startTimeMs = Duration.ofNanos(otelSpan.getStartTimeUnixNano()).toMillis();
                opikTraceId = OpenTelemetryMapper.convertOtelIdToUUIDv7(otelTraceId.toByteArray(), startTimeMs);

                log.info("Creating mapping in Redis for otel trace id '{}' -> opik trace id '{}'", otelTraceIdRedisKey,
                        opikTraceId);
                redisson.getBucket(otelTraceIdRedisKey).set(opikTraceId.toString(), REDIS_TTL).block();
            }

            traceIdMapper.put(otelTraceIdBase64, opikTraceId);
        });

        return traceIdMapper;
    }
}
