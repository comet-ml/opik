package com.comet.opik.domain;

import com.comet.opik.api.SpanBatch;
import com.comet.opik.api.Trace;
import com.comet.opik.utils.AsyncUtils;
import com.google.inject.ImplementedBy;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Instant;

@ImplementedBy(OpenTelemetryServiceImpl.class)
public interface OpenTelemetryService {

    Mono<Long> parseAndStoreSpans(@NonNull ExportTraceServiceRequest traceRequest, @NonNull String projectName,
            @NonNull String userName, @NonNull String workspaceName, @NonNull String workspaceId);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class OpenTelemetryServiceImpl implements OpenTelemetryService {

    private final @NonNull TraceService traceService;
    private final @NonNull SpanService spanService;

    @Override
    public Mono<Long> parseAndStoreSpans(@NonNull ExportTraceServiceRequest traceRequest, @NonNull String projectName,
            @NonNull String userName, @NonNull String workspaceName, @NonNull String workspaceId) {

        var opikSpans = traceRequest.getResourceSpansList().stream()
                .flatMap(resourceSpans -> resourceSpans.getScopeSpansList().stream())
                .flatMap(scopeSpans -> scopeSpans.getSpansList().stream())
                .map(OpenTelemetryMapper::toOpikSpan)
                .map(opikSpan -> opikSpan.toBuilder()
                        .projectName(projectName)
                        .createdBy(userName)
                        .createdAt(Instant.now())
                        .lastUpdatedBy(userName)
                        .lastUpdatedAt(Instant.now())
                        .build())
                .toList();

        // check if there spans without parentId: we will use them as a Trace too
        opikSpans.stream()
                .filter(span -> span.parentSpanId() == null)
                .forEach(rootSpan -> {
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
                            .createdBy(rootSpan.createdBy())
                            .createdAt(rootSpan.createdAt())
                            .lastUpdatedBy(rootSpan.lastUpdatedBy())
                            .lastUpdatedAt(rootSpan.lastUpdatedAt())
                            .build();

                    var traceIdCreated = traceService.create(trace)
                            .contextWrite(
                                    ctx -> AsyncUtils.setRequestContext(ctx, userName, workspaceName, workspaceId))
                            .block();
                    log.info("Created trace with id: '{}'", traceIdCreated);
                });

        var spanBatch = SpanBatch.builder().spans(opikSpans).build();

        log.info("Parsed OpenTelemetry span batch for project '{}' into {} spans", projectName, opikSpans.size());

        return spanService.create(spanBatch)
                .contextWrite(ctx -> AsyncUtils.setRequestContext(ctx, userName, workspaceName, workspaceId));

    }
}
