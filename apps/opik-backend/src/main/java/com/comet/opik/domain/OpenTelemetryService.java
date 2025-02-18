package com.comet.opik.domain;

import com.comet.opik.api.SpanBatch;
import com.comet.opik.api.Trace;
import com.google.inject.ImplementedBy;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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

    @Override
    public Mono<Long> parseAndStoreSpans(@NonNull ExportTraceServiceRequest traceRequest, @NonNull String projectName) {

        var opikSpans = traceRequest.getResourceSpansList().stream()
                .flatMap(resourceSpans -> resourceSpans.getScopeSpansList().stream())
                .flatMap(scopeSpans -> scopeSpans.getSpansList().stream())
                .map(OpenTelemetryMapper::toOpikSpan)
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
}
