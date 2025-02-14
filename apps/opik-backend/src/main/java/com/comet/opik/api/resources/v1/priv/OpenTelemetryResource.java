package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.domain.OpenTelemetryService;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.auth.RequestContext;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
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
    public Response receiveTraces(InputStream in) {
        var projectName = requestContext.get().getHeaders()
                .getOrDefault(RequestContext.PROJECT_NAME, List.of(ProjectService.DEFAULT_PROJECT))
                .getFirst();
        var userName = requestContext.get().getUserName();
        var workspaceName = requestContext.get().getWorkspaceName();
        var workspaceId = requestContext.get().getWorkspaceId();

        try {
            // Parse the incoming Protobuf message
            ExportTraceServiceRequest traceRequest = ExportTraceServiceRequest.parseFrom(in);

            log.info("Received spans batch via OpenTelemetry for project '{}' in workspace '{}'", projectName,
                    workspaceName);

            Long stored = openTelemetryService
                    .parseAndStoreSpans(traceRequest, projectName, userName, workspaceName, workspaceId).block();

            log.info("Stored {} spans via OpenTelemetry for project '{}' in workspace '{}'", stored, projectName,
                    workspaceName);

            // Return a successful HTTP response
            return Response.ok().build();
        } catch (IOException e) {
            // Log the error and return a 400 Bad Request response
            log.error("Error parsing Protobuf payload", e);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Invalid Protobuf payload")
                    .build();
        }
    }
}
