package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.AutomationRuleEvaluator;
import com.comet.opik.api.AutomationRuleEvaluatorUpdate;
import com.comet.opik.api.Page;
import com.comet.opik.domain.AutomationRuleService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import com.fasterxml.jackson.annotation.JsonView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
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
import java.util.UUID;

@Path("/v1/private/automation/evaluator")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Automation rule evaluators", description = "Automation rule evaluators resource")
public class AutomationRuleEvaluatorsResource {

    private final @NonNull AutomationRuleService service;
    private final @NonNull Provider<RequestContext> requestContext;

    @GET
    @Path("/projectId/{projectId}")
    @Operation(operationId = "findEvaluators", summary = "Find Evaluators", description = "Find Evaluators", responses = {
            @ApiResponse(responseCode = "200", description = "Evaluators resource", content = @Content(schema = @Schema(implementation = AutomationRuleEvaluator.AutomationRuleEvaluatorPage.class)))
    })
    @JsonView(AutomationRuleEvaluator.View.Public.class)
    public Response find(@PathParam("projectId") UUID projectId,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Looking for automated evaluators for project id '{}' on workspaceId '{}' (page {})", projectId,
                workspaceId, page);
        Page<AutomationRuleEvaluator> definitionPage = service.find(page, size, projectId);
        log.info("Found {} automated evaluators for project id '{}' on workspaceId '{}' (page {}, total {})",
                definitionPage.size(), projectId, workspaceId, page, definitionPage.total());

        return Response.ok()
                .entity(definitionPage)
                .build();
    }

    @GET
    @Path("/projectId/{projectId}/evaluatorId/{evaluatorId}")
    @Operation(operationId = "getAutomationRulesByProjectId", summary = "Get automation rule evaluator by id", description = "Get dataset by id", responses = {
            @ApiResponse(responseCode = "200", description = "Automation Rule resource", content = @Content(schema = @Schema(implementation = AutomationRuleEvaluator.class)))
    })
    @JsonView(AutomationRuleEvaluator.View.Public.class)
    public Response getEvaluator(@PathParam("projectId") UUID projectId, @PathParam("evaluatorId") UUID evaluatorId) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Finding automated evaluators by id '{}' on project_id '{}'", projectId, workspaceId);
        AutomationRuleEvaluator evaluator = service.findById(evaluatorId, projectId, workspaceId);
        log.info("Found automated evaluators by id '{}' on project_id '{}'", projectId, workspaceId);

        return Response.ok().entity(evaluator).build();
    }

    @POST
    @Operation(operationId = "createAutomationRuleEvaluator", summary = "Create automation rule evaluator", description = "Create automation rule evaluator", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/api/v1/private/automation/evaluator/projectId/{projectId}/evaluatorId/{evaluatorId}", schema = @Schema(implementation = String.class))
            })
    })
    @RateLimited
    public Response createEvaluator(
            @RequestBody(content = @Content(schema = @Schema(implementation = AutomationRuleEvaluator.class))) @JsonView(AutomationRuleEvaluator.View.Write.class) @NotNull @Valid AutomationRuleEvaluator evaluator,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Creating {} evaluator for project_id '{}' on workspace_id '{}'", evaluator.evaluatorType(),
                evaluator.projectId(), workspaceId);
        AutomationRuleEvaluator savedEvaluator = service.save(evaluator, workspaceId);
        log.info("Created {} evaluator '{}' for project_id '{}' on workspace_id '{}'", evaluator.evaluatorType(),
                savedEvaluator.id(), evaluator.projectId(), workspaceId);

        URI uri = uriInfo.getAbsolutePathBuilder()
                .path("/projectId/%s/evaluatorId/%s".formatted(savedEvaluator.projectId().toString(),
                        savedEvaluator.id().toString()))
                .build();
        return Response.created(uri).build();
    }

    @PUT
    @Path("/projectId/{projectId}/evaluatorId/{id}")
    @Operation(operationId = "updateAutomationRuleEvaluator", summary = "update Automation Rule Evaluator by id", description = "update Automation Rule Evaluator by id", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
    })
    @RateLimited
    public Response updateEvaluator(@PathParam("id") UUID id,
            @PathParam("projectId") UUID projectId,
            @RequestBody(content = @Content(schema = @Schema(implementation = AutomationRuleEvaluatorUpdate.class))) @NotNull @Valid AutomationRuleEvaluatorUpdate evaluatorUpdate) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Updating automation rule evaluator by id '{}' and project_id '{}' on workspace_id '{}'", id,
                projectId, workspaceId);
        service.update(id, projectId, workspaceId, evaluatorUpdate);
        log.info("Updated automation rule evaluator by id '{}' and project_id '{}' on workspace_id '{}'", id, projectId,
                workspaceId);

        return Response.noContent().build();
    }

    @DELETE
    @Path("/projectId/{projectId}/evaluatorId/{id}")
    @Operation(operationId = "deleteAutomationRuleEvaluatorById", summary = "Delete Automation Rule Evaluator by id", description = "Delete Automation Rule Evaluator by id", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
    })
    public Response deleteEvaluator(@PathParam("id") UUID id, @PathParam("projectId") UUID projectId) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Deleting dataset by id '{}' on workspace_id '{}'", id, workspaceId);
        service.delete(id, projectId, workspaceId);
        log.info("Deleted dataset by id '{}' on workspace_id '{}'", id, workspaceId);
        return Response.noContent().build();
    }

    @DELETE
    @Path("/projectId/{projectId}")
    @Operation(operationId = "deleteAutomationRuleEvaluatorByProject", summary = "Delete Automation Rule Evaluator by Project id", description = "Delete Automation Rule Evaluator by Project id", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
    })
    public Response deleteProjectEvaluators(@PathParam("projectId") UUID projectId) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Deleting evaluators from project_id '{}' on workspace_id '{}'", projectId, workspaceId);
        service.deleteByProject(projectId, workspaceId);
        log.info("Deleted evaluators from project_id '{}' on workspace_id '{}'", projectId, workspaceId);
        return Response.noContent().build();
    }
}
