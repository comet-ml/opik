package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.domain.OpenTelemetryService;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.AsyncUtils;
import com.fasterxml.jackson.databind.JsonNode;
import io.dropwizard.jersey.errors.ErrorMessage;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Path("/v1/private/otel/v1")
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "OpenTelemetry Ingestion", description = "Resource to ingest Traces and Spans via OpenTelemetry")
public class OpenTelemetryResource {

    private final @NonNull OpenTelemetryService openTelemetryService;
    private final @NonNull Provider<RequestContext> requestContext;

    @Path("/traces")
    @POST
    @Consumes("application/x-protobuf")
    public Response receiveProtobufTraces(
            @Schema(implementation = JsonNode.class, ref = "JsonNode") ExportTraceServiceRequest request) {
        return handleOtelTraceRequest(request);
    }

    @Path("/traces")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response receiveJsonTraces(
            @Schema(implementation = JsonNode.class, ref = "JsonNode") ExportTraceServiceRequest request) {
        return handleOtelTraceRequest(request);
    }

    @Path("/metrics")
    @POST
    @Consumes("application/x-protobuf")
    public Response receiveProtobufMetrics() {
        return notImplementedMetricsResponse();
    }

    @Path("/metrics")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response receiveJsonMetrics() {
        return notImplementedMetricsResponse();
    }

    private Response notImplementedMetricsResponse() {
        return Response.status(Response.Status.NOT_IMPLEMENTED)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorMessage(Response.Status.NOT_IMPLEMENTED.getStatusCode(),
                        "OpenTelemetry metrics ingestion is not yet supported"))
                .build();
    }

    private Response handleOtelTraceRequest(ExportTraceServiceRequest traceRequest) {
        var projectName = requestContext.get().getHeaders()
                .getOrDefault(RequestContext.PROJECT_NAME, List.of(ProjectService.DEFAULT_PROJECT))
                .getFirst();
        var userName = requestContext.get().getUserName();
        var workspaceId = requestContext.get().getWorkspaceId();

        log.info("Received spans batch via OpenTelemetry for project '{}' in workspaceId '{}'", projectName,
                workspaceId);

        Long stored = openTelemetryService
                .parseAndStoreSpans(traceRequest, projectName)
                .contextWrite(ctx -> AsyncUtils.setRequestContext(ctx, userName, workspaceId))
                .block();

        log.info("Stored {} spans via OpenTelemetry for project '{}' in workspaceId '{}'", stored, projectName,
                workspaceId);

        // Return a successful HTTP response
        return Response.ok().build();
    }
}
