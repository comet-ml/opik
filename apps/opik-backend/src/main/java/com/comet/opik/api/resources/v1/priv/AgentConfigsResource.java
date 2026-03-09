package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.AgentConfigCreate;
import com.comet.opik.api.AgentConfigEnvUpdate;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.AgentBlueprint;
import com.comet.opik.domain.AgentConfig;
import com.comet.opik.domain.AgentConfigService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.fasterxml.jackson.annotation.JsonView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
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
import java.util.UUID;

@Path("/v1/private/agent-configs")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Agent Configs", description = "Agent configuration management")
public class AgentConfigsResource {

    private final @NonNull AgentConfigService agentConfigService;
    private final @NonNull Provider<RequestContext> requestContext;

    @POST
    @Path("/blueprints")
    @JsonView(AgentConfig.View.Write.class)
    @Operation(operationId = "createAgentConfig", summary = "Create optimizer config or add blueprint", description = "Creates a new optimizer config with initial blueprint, or adds a new blueprint to existing config", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/agent-configs/blueprints/{blueprint_id}", schema = @Schema(implementation = String.class))}),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response createAgentConfig(
            @RequestBody(content = @Content(schema = @Schema(implementation = AgentConfigCreate.class))) @NotNull @Valid AgentConfigCreate request,
            @Context UriInfo uriInfo) {

        log.info("Creating blueprint for project '{}'", request.projectName());

        AgentBlueprint createdBlueprint = agentConfigService.createOrUpdateConfig(request);

        log.info("Created blueprint '{}' for project '{}'", createdBlueprint.id(), request.projectName());

        URI location = uriInfo.getAbsolutePathBuilder()
                .path(createdBlueprint.id().toString())
                .build();

        return Response.created(location)
                .entity(createdBlueprint)
                .build();
    }

    @GET
    @Path("/blueprints/latest/projects/{project_id}")
    @JsonView(AgentConfig.View.Public.class)
    @Operation(operationId = "getLatestBlueprint", summary = "Retrieve latest blueprint", description = "Retrieves the latest blueprint for a project", responses = {
            @ApiResponse(responseCode = "200", description = "Blueprint retrieved", content = @Content(schema = @Schema(implementation = AgentBlueprint.class))),
            @ApiResponse(responseCode = "404", description = "Not Found", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response getLatestBlueprint(
            @Parameter(required = true) @PathParam("project_id") UUID projectId,
            @QueryParam("mask_id") UUID maskId) {

        log.info("Retrieving latest blueprint for project '{}'", projectId);

        AgentBlueprint blueprint = agentConfigService.getLatestBlueprint(projectId, maskId);

        return Response.ok(blueprint).build();
    }

    @GET
    @Path("/blueprints/{blueprint_id}")
    @JsonView(AgentConfig.View.Public.class)
    @Operation(operationId = "getBlueprintById", summary = "Retrieve blueprint by ID", description = "Retrieves a specific blueprint by its ID", responses = {
            @ApiResponse(responseCode = "200", description = "Blueprint retrieved", content = @Content(schema = @Schema(implementation = AgentBlueprint.class))),
            @ApiResponse(responseCode = "404", description = "Not Found", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response getBlueprintById(
            @PathParam("blueprint_id") UUID blueprintId,
            @QueryParam("mask_id") UUID maskId) {

        log.info("Retrieving blueprint '{}'", blueprintId);

        AgentBlueprint blueprint = agentConfigService.getBlueprintById(blueprintId, maskId);

        return Response.ok(blueprint).build();
    }

    @GET
    @Path("/blueprints/environments/{env_name}/projects/{project_id}")
    @JsonView(AgentConfig.View.Public.class)
    @Operation(operationId = "getBlueprintByEnv", summary = "Retrieve blueprint by environment", description = "Retrieves the blueprint associated with a specific environment", responses = {
            @ApiResponse(responseCode = "200", description = "Blueprint retrieved", content = @Content(schema = @Schema(implementation = AgentBlueprint.class))),
            @ApiResponse(responseCode = "404", description = "Not Found", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response getBlueprintByEnv(
            @PathParam("env_name") String envName,
            @Parameter(required = true) @PathParam("project_id") UUID projectId,
            @QueryParam("mask_id") UUID maskId) {

        log.info("Retrieving blueprint by environment '{}' for project '{}'", envName, projectId);

        AgentBlueprint blueprint = agentConfigService.getBlueprintByEnv(projectId, envName, maskId);

        return Response.ok(blueprint).build();
    }

    @GET
    @Path("/blueprints/{blueprint_id}/deltas")
    @JsonView(AgentConfig.View.Public.class)
    @Operation(operationId = "getDeltaById", summary = "Retrieve delta by blueprint ID", description = "Retrieves only the changes (delta) introduced in a specific blueprint", responses = {
            @ApiResponse(responseCode = "200", description = "Delta retrieved", content = @Content(schema = @Schema(implementation = AgentBlueprint.class))),
            @ApiResponse(responseCode = "404", description = "Not Found", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response getDeltaById(@PathParam("blueprint_id") UUID blueprintId) {

        log.info("Retrieving delta for blueprint '{}'", blueprintId);

        AgentBlueprint delta = agentConfigService.getDeltaById(blueprintId);

        return Response.ok(delta).build();
    }

    @POST
    @Path("/blueprints/environments")
    @Operation(operationId = "createOrUpdateEnvs", summary = "Create or update environments", description = "Creates or updates environment-to-blueprint mappings", responses = {
            @ApiResponse(responseCode = "204", description = "Environments updated"),
            @ApiResponse(responseCode = "404", description = "Not Found", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response createOrUpdateEnvs(
            @RequestBody(content = @Content(schema = @Schema(implementation = AgentConfigEnvUpdate.class))) @NotNull @Valid AgentConfigEnvUpdate request) {

        log.info("Creating or updating environments for project '{}'", request.projectId());

        agentConfigService.createOrUpdateEnvs(request);

        return Response.noContent().build();
    }

    @GET
    @Path("/blueprints/history/projects/{project_id}")
    @JsonView(AgentConfig.View.History.class)
    @Operation(operationId = "getBlueprintHistory", summary = "Get blueprint history", description = "Retrieves paginated blueprint history for a project", responses = {
            @ApiResponse(responseCode = "200", description = "History retrieved", content = @Content(schema = @Schema(implementation = AgentBlueprint.BlueprintPage.class))),
            @ApiResponse(responseCode = "404", description = "Not Found", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response getBlueprintHistory(
            @Parameter(required = true) @PathParam("project_id") UUID projectId,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size) {

        log.info("Retrieving blueprint history for project '{}', page {}, size {}", projectId, page, size);

        AgentBlueprint.BlueprintPage historyPage = agentConfigService.getHistory(projectId, page, size);

        return Response.ok(historyPage).build();
    }
}
