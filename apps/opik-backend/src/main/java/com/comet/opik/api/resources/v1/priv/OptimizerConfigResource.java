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
    @Path("/{config_id}/blueprint/retrieve")
    @Produces(MediaType.APPLICATION_JSON)
    @JsonView(com.comet.opik.domain.OptimizerConfig.View.Public.class)
    @Operation(operationId = "getLatestBlueprint", summary = "Retrieve latest blueprint", description = "Retrieves the latest blueprint for a configuration", responses = {
            @ApiResponse(responseCode = "200", description = "Blueprint retrieved", content = @Content(schema = @Schema(implementation = OptimizerBlueprint.class))),
            @ApiResponse(responseCode = "404", description = "Not Found", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response getLatestBlueprint(
            @PathParam("config_id") UUID configId,
            @QueryParam("maskid") UUID maskId) {

        log.info("Retrieving latest blueprint for config '{}'", configId);

        OptimizerBlueprint blueprint = optimizerConfigService.getLatestBlueprint(configId);

        return Response.ok(blueprint).build();
    }

    @GET
    @Path("/{config_id}/blueprint/{blueprint_id}")
    @Produces(MediaType.APPLICATION_JSON)
    @JsonView(com.comet.opik.domain.OptimizerConfig.View.Public.class)
    @Operation(operationId = "getBlueprintById", summary = "Retrieve blueprint by ID", description = "Retrieves a specific blueprint by its ID", responses = {
            @ApiResponse(responseCode = "200", description = "Blueprint retrieved", content = @Content(schema = @Schema(implementation = OptimizerBlueprint.class))),
            @ApiResponse(responseCode = "404", description = "Not Found", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response getBlueprintById(
            @PathParam("config_id") UUID configId,
            @PathParam("blueprint_id") UUID blueprintId,
            @QueryParam("maskid") UUID maskId) {

        log.info("Retrieving blueprint '{}' for config '{}'", blueprintId, configId);

        OptimizerBlueprint blueprint = optimizerConfigService.getBlueprintById(configId, blueprintId);

        return Response.ok(blueprint).build();
    }

    @GET
    @Path("/{config_id}/blueprint/tag/{tag}")
    @Produces(MediaType.APPLICATION_JSON)
    @JsonView(com.comet.opik.domain.OptimizerConfig.View.Public.class)
    @Operation(operationId = "getBlueprintByTag", summary = "Retrieve blueprint by environment tag", description = "Retrieves the blueprint associated with a specific environment tag", responses = {
            @ApiResponse(responseCode = "200", description = "Blueprint retrieved", content = @Content(schema = @Schema(implementation = OptimizerBlueprint.class))),
            @ApiResponse(responseCode = "404", description = "Not Found", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response getBlueprintByTag(
            @PathParam("config_id") UUID configId,
            @PathParam("tag") String tag,
            @QueryParam("maskid") UUID maskId) {

        log.info("Retrieving blueprint by tag '{}' for config '{}'", tag, configId);

        OptimizerBlueprint blueprint = optimizerConfigService.getBlueprintByTag(configId, tag);

        return Response.ok(blueprint).build();
    }
}
