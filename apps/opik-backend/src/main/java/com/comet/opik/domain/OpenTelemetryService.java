package com.comet.opik.domain;

import com.comet.opik.api.Span;
import com.comet.opik.api.SpanBatch;
import com.comet.opik.api.Trace;
import com.google.inject.ImplementedBy;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final @NonNull RedissonReactiveClient redisson;

    private static final Duration REDIS_TTL = Duration.ofDays(1L);
    private static final String UNKNOWN_TRACE_ID = "";

    private String base64OtelId(ByteString idBytes) {
        return Base64.getEncoder().encodeToString(idBytes.toByteArray());
    }

    @Override
    public Mono<Long> parseAndStoreSpans(@NonNull ExportTraceServiceRequest traceRequest, @NonNull String projectName) {

        var otelSpans = traceRequest.getResourceSpansList().stream()
                .flatMap(resourceSpans -> resourceSpans.getScopeSpansList().stream())
                .flatMap(scopeSpans -> scopeSpans.getSpansList().stream())
                .toList();

        // we expect a single trace per batch, but lets protect ourselves anyway
        var traceIdMapper = otelSpans.stream()
                .map(io.opentelemetry.proto.trace.v1.Span::getTraceId)
                .map(this::base64OtelId)
                .distinct()
                .map(otelBase64TraceId -> {
                    var opikTraceId = (String) redisson.getBucket(otelBase64TraceId).getAndExpire(REDIS_TTL).block();
                    log.info("Redis lookup: {} -> {}", otelBase64TraceId, opikTraceId);

                    return Map.entry(otelBase64TraceId, Optional.ofNullable(opikTraceId));
                })
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        // converts otel spans into opik spans, sorting the result list by start time
        var opikSpans = otelSpans.stream()
                .map(otelSpan -> {
                    var otelTraceId = otelSpan.getTraceId();
                    var otelBase64TraceId = base64OtelId(otelTraceId);
                    var optTraceId = traceIdMapper.get(otelBase64TraceId);

                    final UUID opikTraceId;
                    if (optTraceId.isPresent()) {
                        // its a known otel trace id, lets just reuse it
                        opikTraceId = UUID.fromString(optTraceId.get());
                        log.info("Found {} in local cache: {}", otelBase64TraceId, opikTraceId);
                    }
                    else {
                        // its an unknown otel trace id, lets create an opik trace id with this span timestamp
                        var startTimeMs = Duration.ofNanos(otelSpan.getStartTimeUnixNano()).toMillis();
                        opikTraceId = OpenTelemetryMapper.convertOtelIdToUUIDv7(otelTraceId.toByteArray(), startTimeMs);
                        log.info("Creating mapping for otel id {} -> {}", otelBase64TraceId, opikTraceId);
                        // set on redis: otelId -> opikId
                        redisson.getBucket(otelBase64TraceId).set(opikTraceId, REDIS_TTL).block();
                        // update the mapper so next span can use it
                        traceIdMapper.put(otelBase64TraceId, Optional.of(opikTraceId.toString()));
                    }

                    return OpenTelemetryMapper.toOpikSpan(otelSpan, opikTraceId);
                })
                .map(opikSpan -> {
                    return opikSpan.toBuilder()
                            .projectName(projectName)
                            .build();
                })
                .sorted(Comparator.comparing(Span::startTime))
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
}
