package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.OllieReport.OllieReportPage;
import com.comet.opik.api.OllieReport.ReportCompleteRequest;
import com.comet.opik.api.ReportPreference;
import com.comet.opik.domain.ReportService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.auth.RequiredPermissions;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.UUID;

import static com.comet.opik.utils.AsyncUtils.setRequestContext;

@Path("/v1/private/projects/{projectId}/reports")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Reports", description = "Ollie daily report management")
public class ReportsResource {

    private final @NonNull ReportService reportService;
    private final @NonNull Provider<RequestContext> requestContext;

    @POST
    @Path("/generate")
    @Operation(operationId = "generateReport", summary = "Trigger report generation", description = "Creates a pending report and triggers asynchronous generation via the orchestrator.", responses = {
            @ApiResponse(responseCode = "202", description = "Report generation triggered", content = @Content(schema = @Schema(implementation = GenerateReportResponse.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden")
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response generateReport(@PathParam("projectId") UUID projectId) {
        UUID reportId = reportService.generateReport(projectId)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        if (reportId == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "Report generation is not configured"))
                    .build();
        }

        return Response.accepted(new GenerateReportResponse(reportId)).build();
    }

    @POST
    @Path("/{reportId}/complete")
    @Operation(operationId = "completeReport", summary = "Complete report generation", description = "Callback from Ollie to update report status and content after generation.", responses = {
            @ApiResponse(responseCode = "204", description = "Report updated"),
            @ApiResponse(responseCode = "404", description = "Report not found")
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response completeReport(
            @PathParam("projectId") UUID projectId,
            @PathParam("reportId") UUID reportId,
            @Valid ReportCompleteRequest request) {

        reportService.updateReport(projectId, reportId, request.status(), request.content(),
                request.sessionId(), request.recommendedActions())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        return Response.noContent().build();
    }

    @GET
    @Operation(operationId = "getReports", summary = "Get reports for a project", description = "Returns a paginated list of reports, newest first.", responses = {
            @ApiResponse(responseCode = "200", description = "Reports page", content = @Content(schema = @Schema(implementation = OllieReportPage.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response getReports(
            @PathParam("projectId") UUID projectId,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @Max(100) @DefaultValue("10") int size) {

        var reports = reportService.getReports(projectId, page, size)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        return Response.ok(reports).build();
    }

    @GET
    @Path("/preferences")
    @Operation(operationId = "getReportPreference", summary = "Get report preferences", description = "Returns report preferences for a project, or null if none have been set.", responses = {
            @ApiResponse(responseCode = "200", description = "Report preferences or null", content = @Content(schema = @Schema(implementation = ReportPreference.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response getPreference(@PathParam("projectId") UUID projectId) {
        var preference = reportService.getPreference(projectId)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        return Response.ok(preference).build();
    }

    @PUT
    @Path("/preferences")
    @Operation(operationId = "updateReportPreference", summary = "Update report preferences", description = "Enable or disable daily report generation for a project.", responses = {
            @ApiResponse(responseCode = "200", description = "Updated preferences", content = @Content(schema = @Schema(implementation = ReportPreference.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response updatePreference(
            @PathParam("projectId") UUID projectId,
            @Valid ReportPreference preference) {

        var updated = reportService.updatePreference(projectId, preference)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        return Response.ok(updated).build();
    }

    @Schema(description = "Response for report generation trigger")
    private record GenerateReportResponse(UUID reportId) {
    }
}
