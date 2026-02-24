package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.OptimizerConfigCreate;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.OptimizerBlueprint;
import com.comet.opik.domain.OptimizerConfigService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.fasterxml.jackson.annotation.JsonView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
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

@Path("/v1/private/optimizer-configs")
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Optimizer Configs", description = "Optimizer configuration management")
public class OptimizerConfigResource {

    private final @NonNull OptimizerConfigService optimizerConfigService;
    private final @NonNull Provider<RequestContext> requestContext;

    @POST
    @Path("/")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @JsonView(com.comet.opik.domain.OptimizerConfig.View.Write.class)
    @Operation(operationId = "createOptimizerConfig", summary = "Create optimizer config or add blueprint", description = "Creates a new optimizer config with initial blueprint, or adds a new blueprint to existing config", responses = {
            @ApiResponse(responseCode = "201", description = "Blueprint created", content = @Content(schema = @Schema(implementation = OptimizerBlueprint.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response createOptimizerConfig(
            @RequestBody(content = @Content(schema = @Schema(implementation = OptimizerConfigCreate.class))) @Valid OptimizerConfigCreate request,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        log.info("Creating blueprint for project '{}' in workspace '{}'",
                request.projectName(), workspaceId);

        OptimizerBlueprint createdBlueprint = optimizerConfigService.createOrUpdateConfig(request);

        log.info("Created blueprint '{}' for project '{}' in workspace '{}'",
                createdBlueprint.id(), request.projectName(), workspaceId);

        URI location = uriInfo.getBaseUriBuilder()
                .path("v1/private/optimizer-configs/blueprint/{blueprint_id}")
                .build(createdBlueprint.id());

        return Response.created(location)
                .entity(createdBlueprint)
                .build();
    }

    @GET
    @Path("/blueprint/retrieve")
    @Produces(MediaType.APPLICATION_JSON)
    @JsonView(com.comet.opik.domain.OptimizerConfig.View.Public.class)
    @Operation(operationId = "getLatestBlueprint", summary = "Retrieve latest blueprint", description = "Retrieves the latest blueprint for a project", responses = {
            @ApiResponse(responseCode = "200", description = "Blueprint retrieved", content = @Content(schema = @Schema(implementation = OptimizerBlueprint.class))),
            @ApiResponse(responseCode = "404", description = "Not Found", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response getLatestBlueprint(
            @QueryParam("project_id") UUID projectId,
            @QueryParam("mask_id") UUID maskId) {

        log.info("Retrieving latest blueprint for project '{}'", projectId);

        OptimizerBlueprint blueprint = optimizerConfigService.getLatestBlueprint(projectId, maskId);

        return Response.ok(blueprint).build();
    }

    @GET
    @Path("/blueprint/{blueprint_id}")
    @Produces(MediaType.APPLICATION_JSON)
    @JsonView(com.comet.opik.domain.OptimizerConfig.View.Public.class)
    @Operation(operationId = "getBlueprintById", summary = "Retrieve blueprint by ID", description = "Retrieves a specific blueprint by its ID", responses = {
            @ApiResponse(responseCode = "200", description = "Blueprint retrieved", content = @Content(schema = @Schema(implementation = OptimizerBlueprint.class))),
            @ApiResponse(responseCode = "404", description = "Not Found", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response getBlueprintById(
            @PathParam("blueprint_id") UUID blueprintId,
            @QueryParam("mask_id") UUID maskId) {

        log.info("Retrieving blueprint '{}'", blueprintId);

        OptimizerBlueprint blueprint = optimizerConfigService.getBlueprintById(blueprintId, maskId);

        return Response.ok(blueprint).build();
    }

    @GET
    @Path("/blueprint/env/{env_name}")
    @Produces(MediaType.APPLICATION_JSON)
    @JsonView(com.comet.opik.domain.OptimizerConfig.View.Public.class)
    @Operation(operationId = "getBlueprintByEnv", summary = "Retrieve blueprint by environment", description = "Retrieves the blueprint associated with a specific environment", responses = {
            @ApiResponse(responseCode = "200", description = "Blueprint retrieved", content = @Content(schema = @Schema(implementation = OptimizerBlueprint.class))),
            @ApiResponse(responseCode = "404", description = "Not Found", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response getBlueprintByEnv(
            @PathParam("env_name") String envName,
            @QueryParam("project_id") UUID projectId,
            @QueryParam("mask_id") UUID maskId) {

        log.info("Retrieving blueprint by environment '{}' for project '{}'", envName, projectId);

        OptimizerBlueprint blueprint = optimizerConfigService.getBlueprintByEnv(projectId, envName, maskId);

        return Response.ok(blueprint).build();
    }

    @GET
    @Path("/delta/{blueprint_id}")
    @Produces(MediaType.APPLICATION_JSON)
    @JsonView(com.comet.opik.domain.OptimizerConfig.View.Public.class)
    @Operation(operationId = "getDeltaById", summary = "Retrieve delta by blueprint ID", description = "Retrieves only the changes (delta) introduced in a specific blueprint", responses = {
            @ApiResponse(responseCode = "200", description = "Delta retrieved", content = @Content(schema = @Schema(implementation = OptimizerBlueprint.class))),
            @ApiResponse(responseCode = "404", description = "Not Found", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response getDeltaById(@PathParam("blueprint_id") UUID blueprintId) {

        log.info("Retrieving delta for blueprint '{}'", blueprintId);

        OptimizerBlueprint delta = optimizerConfigService.getDeltaById(blueprintId);

        return Response.ok(delta).build();
    }
}
