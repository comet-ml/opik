package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.automations.Automation;
import com.comet.opik.api.automations.AutomationDryRun;
import com.comet.opik.api.automations.AutomationRun;
import com.comet.opik.api.automations.AutomationTestRequest;
import com.comet.opik.api.automations.AutomationUpdate;
import com.comet.opik.api.automations.Recommendation;
import com.comet.opik.api.automations.Workflow;
import com.comet.opik.domain.automations.AutomationExecutor;
import com.comet.opik.domain.automations.AutomationRunService;
import com.comet.opik.domain.automations.AutomationService;
import com.comet.opik.domain.automations.RecommendationService;
import com.comet.opik.domain.automations.WorkflowService;
import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.utils.AsyncUtils.setRequestContext;

/**
 * Automations: when an event happens on an Opik object, run ordered steps. Sits beside the evaluator
 * rules at {@code /automations/evaluators}; the longer literal path wins, so the two never collide.
 */
@Path("/v1/private/automations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Automations", description = "Event-triggered automations, workflows and recommendations")
public class AutomationsResource {

    private final @NonNull AutomationService automationService;
    private final @NonNull AutomationRunService runService;
    private final @NonNull AutomationExecutor executor;
    private final @NonNull WorkflowService workflowService;
    private final @NonNull RecommendationService recommendationService;
    private final @NonNull FeatureFlags featureFlags;
    private final @NonNull Provider<RequestContext> requestContext;

    @GET
    @Operation(operationId = "findAutomations", summary = "Find automations", description = "Find automations", responses = {
            @ApiResponse(responseCode = "200", description = "Automations page", content = @Content(schema = @Schema(implementation = Automation.AutomationPage.class)))})
    public Response find(@QueryParam("project_id") UUID projectId,
            @QueryParam("enabled") Boolean enabled,
            @QueryParam("recipe") String recipe,
            @QueryParam("event") String event,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size) {
        checkEnabled();
        var found = automationService.find(workspaceId(), projectId, enabled, recipe, event, page, size);
        return Response.ok(found).build();
    }

    @GET
    @Path("/{id}")
    @Operation(operationId = "getAutomationById", summary = "Get automation by id", description = "Get automation by id", responses = {
            @ApiResponse(responseCode = "200", description = "Automation", content = @Content(schema = @Schema(implementation = Automation.class)))})
    public Response get(@PathParam("id") UUID id) {
        checkEnabled();
        return Response.ok(automationService.get(workspaceId(), id)).build();
    }

    @POST
    @Operation(operationId = "createAutomation", summary = "Create automation", description = "Create automation; with dry_run=true, validate and estimate without writing", responses = {
            @ApiResponse(responseCode = "201", description = "Created"),
            @ApiResponse(responseCode = "200", description = "Dry run", content = @Content(schema = @Schema(implementation = AutomationDryRun.class)))})
    @RateLimited
    public Response create(@NotNull @Valid Automation automation,
            @QueryParam("dry_run") @DefaultValue("false") boolean dryRun, @Context UriInfo uriInfo) {
        checkEnabled();
        String workspaceId = workspaceId();
        if (dryRun) {
            var preview = automationService.dryRun(workspaceId, automation)
                    .contextWrite(ctx -> setRequestContext(ctx, requestContext)).block();
            return Response.ok(preview).build();
        }
        var created = automationService.create(workspaceId, userName(), automation)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext)).block();
        URI uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(created.id())).build();
        return Response.created(uri).entity(created).build();
    }

    @PATCH
    @Path("/{id}")
    @Operation(operationId = "updateAutomation", summary = "Update automation", description = "Update name, enabled, triggers, steps or limits; with dry_run=true, validate and estimate without writing", responses = {
            @ApiResponse(responseCode = "204", description = "Updated"),
            @ApiResponse(responseCode = "200", description = "Dry run", content = @Content(schema = @Schema(implementation = AutomationDryRun.class)))})
    @RateLimited
    public Response update(@PathParam("id") UUID id, @NotNull @Valid AutomationUpdate update,
            @QueryParam("dry_run") @DefaultValue("false") boolean dryRun) {
        checkEnabled();
        String workspaceId = workspaceId();
        if (dryRun) {
            var preview = automationService.dryRunUpdate(workspaceId, id, update)
                    .contextWrite(ctx -> setRequestContext(ctx, requestContext)).block();
            return Response.ok(preview).build();
        }
        automationService.update(workspaceId, userName(), id, update)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext)).block();
        return Response.noContent().build();
    }

    @POST
    @Path("/delete")
    @Operation(operationId = "deleteAutomations", summary = "Delete automations", description = "Delete automations; run history is kept", responses = {
            @ApiResponse(responseCode = "204", description = "Deleted")})
    public Response delete(@NotNull @Valid BatchDelete batchDelete) {
        checkEnabled();
        automationService.delete(workspaceId(), batchDelete.ids());
        return Response.noContent().build();
    }

    @POST
    @Path("/{id}/test")
    @Operation(operationId = "testAutomation", summary = "Test automation on one trace or thread", description = "Runs the steps in shadow mode on one trace or thread: outputs returned, nothing written", responses = {
            @ApiResponse(responseCode = "200", description = "Shadow run", content = @Content(schema = @Schema(implementation = AutomationRun.class)))})
    public Response test(@PathParam("id") UUID id, @NotNull @Valid AutomationTestRequest request) {
        checkEnabled();
        String workspaceId = workspaceId();
        var automation = automationService.get(workspaceId, id);
        var run = executor.test(workspaceId, automation, request)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext)).block();
        return Response.ok(run).build();
    }

    @GET
    @Path("/runs")
    @Operation(operationId = "findAutomationRuns", summary = "Find automation runs", description = "Find automation runs by project or automation", responses = {
            @ApiResponse(responseCode = "200", description = "Runs page", content = @Content(schema = @Schema(implementation = AutomationRun.AutomationRunPage.class)))})
    public Response findRuns(@QueryParam("project_id") UUID projectId,
            @QueryParam("automation_id") UUID automationId,
            @QueryParam("status") AutomationRun.Status status,
            @QueryParam("since") Instant since,
            @QueryParam("parent_run_id") UUID parentRunId,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size) {
        checkEnabled();
        if (projectId == null && automationId == null) {
            throw new jakarta.ws.rs.BadRequestException("project_id or automation_id is required");
        }
        var found = runService.find(workspaceId(), projectId, automationId, status, since, parentRunId, page, size);
        return Response.ok(found).build();
    }

    @GET
    @Path("/runs/{run_id}")
    @Operation(operationId = "getAutomationRunById", summary = "Get automation run by id", description = "Get automation run by id", responses = {
            @ApiResponse(responseCode = "200", description = "Run", content = @Content(schema = @Schema(implementation = AutomationRun.class)))})
    public Response getRun(@PathParam("run_id") UUID runId) {
        checkEnabled();
        var run = runService.get(workspaceId(), runId)
                .orElseThrow(() -> new NotFoundException("Automation run not found: '%s'".formatted(runId)));
        return Response.ok(run).build();
    }

    @GET
    @Path("/workflows")
    @Operation(operationId = "findWorkflows", summary = "Find workflows", description = "The recipes for a project: on, partly on or off, with the automations they would create and a 14-day estimate", responses = {
            @ApiResponse(responseCode = "200", description = "Workflows", content = @Content(schema = @Schema(implementation = Workflow.class)))})
    public Response findWorkflows(@QueryParam("project_id") @NotNull UUID projectId) {
        checkEnabled();
        List<Workflow> workflows = workflowService.list(workspaceId(), projectId)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext)).block();
        return Response.ok(workflows).build();
    }

    @POST
    @Path("/workflows/enable")
    @Operation(operationId = "enableWorkflow", summary = "Enable workflow", description = "Creates a recipe's automations for a project; with dry_run=true, returns what it would create and the estimate", responses = {
            @ApiResponse(responseCode = "200", description = "Enabled", content = @Content(schema = @Schema(implementation = Workflow.Enabled.class)))})
    @RateLimited
    public Response enableWorkflow(@NotNull @Valid Workflow.EnableRequest request,
            @QueryParam("dry_run") @DefaultValue("false") boolean dryRun) {
        checkEnabled();
        var enabled = workflowService.enable(workspaceId(), userName(), request, dryRun)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext)).block();
        return Response.ok(enabled).build();
    }

    @POST
    @Path("/workflows/disable")
    @Operation(operationId = "disableWorkflow", summary = "Disable workflow", description = "Turns off every automation a recipe created for a project", responses = {
            @ApiResponse(responseCode = "200", description = "Disabled", content = @Content(schema = @Schema(implementation = Workflow.Disabled.class)))})
    public Response disableWorkflow(@NotNull @Valid Workflow.DisableRequest request) {
        checkEnabled();
        return Response.ok(workflowService.disable(workspaceId(), userName(), request)).build();
    }

    @GET
    @Path("/recommendations")
    @Operation(operationId = "findRecommendations", summary = "Find recommendations", description = "Ranked next steps for a project, each with the write call that applies it", responses = {
            @ApiResponse(responseCode = "200", description = "Recommendations", content = @Content(schema = @Schema(implementation = Recommendation.class)))})
    public Response findRecommendations(@QueryParam("project_id") @NotNull UUID projectId) {
        checkEnabled();
        List<Recommendation> recommendations = recommendationService.list(workspaceId(), projectId)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext)).block();
        return Response.ok(recommendations).build();
    }

    @POST
    @Path("/recommendations/{id}/dismiss")
    @Operation(operationId = "dismissRecommendation", summary = "Dismiss recommendation", description = "Hides a recommendation for 30 days", responses = {
            @ApiResponse(responseCode = "204", description = "Dismissed")})
    public Response dismissRecommendation(@PathParam("id") UUID id, @NotNull Map<String, UUID> body) {
        checkEnabled();
        UUID projectId = body.get("project_id");
        if (projectId == null) {
            throw new jakarta.ws.rs.BadRequestException("project_id is required");
        }
        recommendationService.dismiss(workspaceId(), userName(), projectId, id);
        return Response.noContent().build();
    }

    private void checkEnabled() {
        if (!featureFlags.isAutomationsEnabled()) {
            throw new NotFoundException("Automations are not enabled on this deployment");
        }
    }

    private String workspaceId() {
        return requestContext.get().getWorkspaceId();
    }

    private String userName() {
        return requestContext.get().getUserName();
    }
}
