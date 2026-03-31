package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.AgentConfigCreate;
import com.comet.opik.api.AgentConfigEnvSetByName;
import com.comet.opik.api.AgentConfigEnvUpdate;
import com.comet.opik.api.AgentConfigRemoveValues;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.AgentBlueprint;
import com.comet.opik.domain.AgentConfig;
import com.comet.opik.domain.AgentConfigService;
import com.comet.opik.infrastructure.auth.RequestContext;
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
import jakarta.ws.rs.PATCH;
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
    @Operation(operationId = "createAgentConfig", summary = "Create optimizer config with initial blueprint", description = "Creates a new optimizer config with initial blueprint. Fails if the project already has a config.", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/agent-configs/blueprints/{blueprint_id}", schema = @Schema(implementation = String.class))}),
            @ApiResponse(responseCode = "400", description = "Bad Request (e.g. MASK type not allowed)", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "Conflict (config already exists)", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response createAgentConfig(
            @RequestBody(content = @Content(schema = @Schema(implementation = AgentConfigCreate.class))) @NotNull @Valid AgentConfigCreate request,
            @Context UriInfo uriInfo) {

        log.info("Creating config for project '{}'", request.projectName());

        AgentBlueprint createdBlueprint = agentConfigService.createConfig(request).block();

        log.info("Created config with blueprint '{}' for project '{}'", createdBlueprint.id(), request.projectName());

        URI location = uriInfo.getAbsolutePathBuilder()
                .path(createdBlueprint.id().toString())
                .build();

        return Response.created(location)
                .entity(createdBlueprint)
                .build();
    }

    @POST
    @Path("/blueprints/remove-keys")
    @Operation(operationId = "removeConfigKeys", summary = "Remove configuration parameters", description = "Removes configuration parameters by creating a new blueprint that closes the specified keys. Returns 204 if no changes were needed (idempotent).", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/agent-configs/blueprints/{blueprint_id}", schema = @Schema(implementation = String.class))}),
            @ApiResponse(responseCode = "204", description = "No changes needed (no config or keys already removed)"),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response removeConfigKeys(
            @RequestBody(content = @Content(schema = @Schema(implementation = AgentConfigRemoveValues.class))) @NotNull @Valid AgentConfigRemoveValues request,
            @Context UriInfo uriInfo) {

        String projectIdentifier = request.projectId() != null
                ? request.projectId().toString()
                : request.projectName();
        log.info("Removing config keys for project '{}'", projectIdentifier);

        AgentBlueprint blueprint = agentConfigService.removeConfigKeys(request).block();

        if (blueprint == null) {
            log.info("No config keys to remove for project '{}'", projectIdentifier);
            return Response.noContent().build();
        }

        log.info("Removed config keys, created blueprint '{}' for project '{}'", blueprint.id(),
                projectIdentifier);

        URI location = uriInfo.getBaseUriBuilder()
                .path("v1/private/agent-configs/blueprints")
                .path(blueprint.id().toString())
                .build();

        return Response.created(location)
                .build();
    }

    @PATCH
    @Path("/blueprints")
    @JsonView(AgentConfig.View.Write.class)
    @Operation(operationId = "updateAgentConfig", summary = "Add blueprint to existing config", description = "Adds a new blueprint to an existing optimizer config. Fails if the project has no config yet.", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/agent-configs/blueprints/{blueprint_id}", schema = @Schema(implementation = String.class))}),
            @ApiResponse(responseCode = "404", description = "Not Found (no config for project)", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response updateAgentConfig(
            @RequestBody(content = @Content(schema = @Schema(implementation = AgentConfigCreate.class))) @NotNull @Valid AgentConfigCreate request,
            @Context UriInfo uriInfo) {

        log.info("Adding blueprint to config for project '{}'", request.projectName());

        AgentBlueprint blueprint = agentConfigService.updateConfig(request).block();

        log.info("Added blueprint '{}' to config for project '{}'", blueprint.id(), request.projectName());

        URI location = uriInfo.getAbsolutePathBuilder()
                .path(blueprint.id().toString())
                .build();

        return Response.created(location)
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
            @PathParam("project_id") UUID projectId,
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
    @Path("/blueprints/projects/{project_id}/names/{name}")
    @JsonView(AgentConfig.View.Public.class)
    @Operation(operationId = "getBlueprintByName", summary = "Retrieve blueprint by name", description = "Retrieves a specific blueprint by its name within a project", responses = {
            @ApiResponse(responseCode = "200", description = "Blueprint retrieved", content = @Content(schema = @Schema(implementation = AgentBlueprint.class))),
            @ApiResponse(responseCode = "404", description = "Not Found", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response getBlueprintByName(
            @PathParam("name") String name,
            @PathParam("project_id") UUID projectId,
            @QueryParam("mask_id") UUID maskId) {

        log.info("Retrieving blueprint by name '{}' for project '{}'", name, projectId);

        AgentBlueprint blueprint = agentConfigService.getBlueprintByName(projectId, name, maskId);

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
            @PathParam("project_id") UUID projectId,
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

        agentConfigService.createOrUpdateEnvs(request).block();

        return Response.noContent().build();
    }

    @PUT
    @Path("/blueprints/environments/{env_name}/projects/{project_id}")
    @Operation(operationId = "setEnvByBlueprintName", summary = "Set environment by blueprint name", description = "Sets an environment to point to a blueprint identified by name", responses = {
            @ApiResponse(responseCode = "204", description = "Environment updated"),
            @ApiResponse(responseCode = "404", description = "Not Found", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response setEnvByBlueprintName(
            @PathParam("env_name") String envName,
            @PathParam("project_id") UUID projectId,
            @RequestBody(content = @Content(schema = @Schema(implementation = AgentConfigEnvSetByName.class))) @NotNull @Valid AgentConfigEnvSetByName request) {

        log.info("Setting environment '{}' to blueprint '{}' for project '{}'",
                envName, request.blueprintName(), projectId);

        agentConfigService.setEnvByBlueprintName(projectId, envName, request.blueprintName()).block();

        return Response.noContent().build();
    }

    @DELETE
    @Path("/blueprints/environments/{env_name}/projects/{project_id}")
    @Operation(operationId = "deleteEnv", summary = "Delete environment", description = "Soft-deletes an environment by setting its ended_at timestamp", responses = {
            @ApiResponse(responseCode = "204", description = "Environment deleted"),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response deleteEnv(
            @PathParam("env_name") String envName,
            @PathParam("project_id") UUID projectId) {

        log.info("Deleting environment '{}' for project '{}'", envName, projectId);

        agentConfigService.deleteEnv(projectId, envName);

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
            @PathParam("project_id") UUID projectId,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size) {

        log.info("Retrieving blueprint history for project '{}', page {}, size {}", projectId, page, size);

        AgentBlueprint.BlueprintPage historyPage = agentConfigService.getHistory(projectId, page, size);

        return Response.ok(historyPage).build();
    }
}
