package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.AgentInsightsIssue;
import com.comet.opik.api.AgentInsightsIssueStatus;
import com.comet.opik.api.AgentInsightsIssueUpdate;
import com.comet.opik.api.AgentInsightsIssueWithDetails;
import com.comet.opik.api.AgentInsightsReport;
import com.comet.opik.api.AgentInsightsSortBy;
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
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.UUID;

@Path("/v1/private/agent-insights")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Agent Insights", description = "Agent Insights report results")
public class AgentInsightsResource {

    private final @NonNull AgentInsightsIssueService agentInsightsIssueService;

    @GET
    @Path("/issues")
    @Operation(operationId = "findAgentInsightsIssues", summary = "Find agent insights issues", description = "Returns a paginated list of issues that have at least one detail row within the requested time window, with metrics aggregated over the window", responses = {
            @ApiResponse(responseCode = "200", description = "Issues page", content = @Content(schema = @Schema(implementation = AgentInsightsIssue.AgentInsightsIssuePage.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response findIssues(
            @QueryParam("project_id") @NotNull UUID projectId,
            @QueryParam("from_date") LocalDate fromDate,
            @QueryParam("to_date") LocalDate toDate,
            @QueryParam("status") AgentInsightsIssueStatus status,
            @QueryParam("sort_by") AgentInsightsSortBy sortBy,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size) {

        log.info("Finding agent insights issues for project '{}', window '{}'..'{}', page {}",
                projectId, fromDate, toDate, page);

        AgentInsightsIssue.AgentInsightsIssuePage issuesPage = agentInsightsIssueService.findIssues(
                projectId, fromDate, toDate, status, sortBy, page, size);

        return Response.ok(issuesPage).build();
    }

    @GET
    @Path("/issues/{issue_id}")
    @Operation(operationId = "getAgentInsightsIssueById", summary = "Get agent insights issue by id", description = "Returns the issue together with its per-day breakdown within the requested time window", responses = {
            @ApiResponse(responseCode = "200", description = "Issue with details", content = @Content(schema = @Schema(implementation = AgentInsightsIssueWithDetails.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Not Found", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response getIssueById(
            @PathParam("issue_id") UUID issueId,
            @QueryParam("project_id") @NotNull UUID projectId,
            @QueryParam("from_date") LocalDate fromDate,
            @QueryParam("to_date") LocalDate toDate) {

        log.info("Retrieving agent insights issue '{}' for project '{}', window '{}'..'{}'",
                issueId, projectId, fromDate, toDate);

        AgentInsightsIssueWithDetails issue = agentInsightsIssueService.getIssue(issueId, projectId, fromDate,
                toDate);

        return Response.ok(issue).build();
    }

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

    @PATCH
    @Path("/issues/{issue_id}")
    @Operation(operationId = "updateAgentInsightsIssue", summary = "Update agent insights issue status", description = "Moves an issue through its lifecycle: open, resolved or closed", responses = {
            @ApiResponse(responseCode = "204", description = "Issue updated"),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Not Found", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response updateIssue(
            @PathParam("issue_id") UUID issueId,
            @RequestBody(content = @Content(schema = @Schema(implementation = AgentInsightsIssueUpdate.class))) @NotNull @Valid AgentInsightsIssueUpdate update) {

        log.info("Updating agent insights issue '{}' status to '{}' for project '{}'",
                issueId, update.status(), update.projectId());

        agentInsightsIssueService.updateStatus(issueId, update);

        return Response.noContent().build();
    }
}
