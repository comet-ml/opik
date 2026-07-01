package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.ReportFailure;
import com.comet.opik.api.ReportFailureType;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.ReportFailureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.net.URI;
import java.util.UUID;

/**
 * Failure log for reports/jobs. A failure is recorded by POSTing with the failing {@code project_id} and a
 * {@code type} discriminator, and listed via GET. Agent Insights uses {@code type=agent_insights}; whether a job
 * is "currently failed" is decided server-side (the agent-insights job query compares the latest failure against
 * the last successful scan).
 */
@Path("/v1/private/report-failures")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Timed
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Report Failures", description = "Generic failure log for reports/jobs")
public class ReportFailuresResource {

    private final @NonNull ReportFailureService reportFailureService;

    @POST
    @Operation(operationId = "createReportFailure", summary = "Record a report/job failure", description = "Appends a failure row keyed by (project_id, type). Append-only — never overwrites earlier failures.", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @io.swagger.v3.oas.annotations.headers.Header(name = "Location", schema = @Schema(implementation = String.class))}),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response create(
            @RequestBody(content = @Content(schema = @Schema(implementation = ReportFailure.class))) @NotNull @Valid ReportFailure failure,
            @Context UriInfo uriInfo) {

        UUID id = reportFailureService.create(failure);

        URI uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(id)).build();

        return Response.created(uri).build();
    }

    @GET
    @Operation(operationId = "findReportFailures", summary = "List failures for a project", description = "Returns failures for the given project id and type, most recent first.", responses = {
            @ApiResponse(responseCode = "200", description = "Failures page", content = @Content(schema = @Schema(implementation = ReportFailure.ReportFailurePage.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response find(
            @QueryParam("type") @NotNull ReportFailureType type,
            @QueryParam("project_id") @NotNull UUID projectId,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @Max(100) @DefaultValue("10") int size) {

        return Response.ok(reportFailureService.find(type, projectId, page, size)).build();
    }
}
