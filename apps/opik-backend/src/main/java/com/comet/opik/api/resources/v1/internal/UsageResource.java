package com.comet.opik.api.resources.v1.internal;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.BiInformationResponse;
import com.comet.opik.api.SpansCountResponse;
import com.comet.opik.api.TraceCountResponse;
import com.comet.opik.domain.DatasetService;
import com.comet.opik.domain.ExperimentService;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.TraceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Path("/v1/internal/usage")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @jakarta.inject.Inject)
@Tag(name = "System usage", description = "System usage related resource")
public class UsageResource {

    private final @NonNull TraceService traceService;
    private final @NonNull SpanService spanService;
    private final @NonNull ExperimentService experimentService;
    private final @NonNull DatasetService datasetService;

    @GET
    @Path("/workspace-trace-counts")
    @Operation(operationId = "getTracesCountForWorkspaces", summary = "Get traces count on previous day for all available workspaces", description = "Get traces count on previous day for all available workspaces", responses = {
            @ApiResponse(responseCode = "200", description = "TraceCountResponse resource", content = @Content(schema = @Schema(implementation = TraceCountResponse.class)))})
    public Response getTracesCountForWorkspaces() {
        return traceService.countTracesPerWorkspace()
                .map(tracesCountResponse -> Response.ok(tracesCountResponse).build())
                .block();
    }

    @GET
    @Path("/workspace-span-counts")
    @Operation(operationId = "getSpansCountForWorkspaces", summary = "Get spans count on previous day for all available workspaces", description = "Get spans count on previous day for all available workspaces", responses = {
            @ApiResponse(responseCode = "200", description = "SpanCountResponse resource", content = @Content(schema = @Schema(implementation = SpansCountResponse.class)))})
    public Response getSpansCountForWorkspaces() {
        return spanService.countSpansPerWorkspace()
                .map(spansCountResponse -> Response.ok(spansCountResponse).build())
                .block();
    }

    @GET
    @Path("/bi-traces")
    @Operation(operationId = "getTracesBiInfo", summary = "Get traces information for BI events", description = "Get traces information for BI events per user per workspace", responses = {
            @ApiResponse(responseCode = "200", description = "Traces BiInformationResponse resource", content = @Content(schema = @Schema(implementation = BiInformationResponse.class)))})
    public Response getTracesBiInfo() {
        return traceService.getTraceBIInformation()
                .map(traceBiInfoResponse -> Response.ok(traceBiInfoResponse).build())
                .block();
    }

    @GET
    @Path("/bi-experiments")
    @Operation(operationId = "getExperimentBiInfo", summary = "Get experiments information for BI events", description = "Get experiments information for BI events per user per workspace", responses = {
            @ApiResponse(responseCode = "200", description = "Experiments BiInformationResponse resource", content = @Content(schema = @Schema(implementation = BiInformationResponse.class)))})
    public Response getExperimentBiInfo() {
        return experimentService.getExperimentBIInformation()
                .map(experimentBiInfoResponse -> Response.ok(experimentBiInfoResponse).build())
                .block();
    }

    @GET
    @Path("/bi-datasets")
    @Operation(operationId = "getDatasetBiInfo", summary = "Get datasets information for BI events", description = "Get datasets information for BI events per user per workspace", responses = {
            @ApiResponse(responseCode = "200", description = "Datasets BiInformationResponse resource", content = @Content(schema = @Schema(implementation = BiInformationResponse.class)))})
    public Response getDatasetBiInfo() {
        return Response.ok(datasetService.getDatasetBIInformation()).build();
    }

    @GET
    @Path("/bi-spans")
    @Operation(operationId = "getSpansBiInfo", summary = "Get spans information for BI events", description = "Get spans information for BI events per user per workspace", responses = {
            @ApiResponse(responseCode = "200", description = "Spans BiInformationResponse resource", content = @Content(schema = @Schema(implementation = BiInformationResponse.class)))})
    public Response getSpansBiInfo() {
        return spanService.getSpanBIInformation()
                .map(spanBiInfoResponse -> Response.ok(spanBiInfoResponse).build())
                .block();
    }
}
