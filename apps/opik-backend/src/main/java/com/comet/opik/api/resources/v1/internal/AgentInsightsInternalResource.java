package com.comet.opik.api.resources.v1.internal;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.AgentInsightsReport;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.AgentInsightsIssueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Path("/v1/internal/agent-insights")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Agent Insights Internal", description = "Internal Agent Insights report persistence")
public class AgentInsightsInternalResource {

    private final @NonNull AgentInsightsIssueService agentInsightsIssueService;

    @POST
    @Path("/issues")
    @Operation(operationId = "reportAgentInsightsIssues", summary = "Store agent insights report results", description = "Upserts the detected issues and their per-day metrics for the given report day in a single transaction. Issue status is never modified by this endpoint.", responses = {
            @ApiResponse(responseCode = "204", description = "Report stored"),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Project not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response reportIssues(
            @RequestBody(content = @Content(schema = @Schema(implementation = AgentInsightsReport.class))) @NotNull @Valid AgentInsightsReport report) {

        log.info("Storing agent insights report with '{}' issues for project '{}' on report day '{}'",
                report.issues().size(), report.projectId(), report.reportDay());

        agentInsightsIssueService.reportIssues(report);

        return Response.noContent().build();
    }
}
