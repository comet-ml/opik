package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.Environment;
import com.comet.opik.api.EnvironmentUpdate;
import com.comet.opik.domain.EnvironmentService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import com.fasterxml.jackson.annotation.JsonView;
import io.dropwizard.jersey.errors.ErrorMessage;
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
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Path("/v1/private/environments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Environments", description = "Environment related resources")
public class EnvironmentsResource {

    private final @NonNull EnvironmentService service;
    private final @NonNull Provider<RequestContext> requestContext;

    @GET
    @Operation(operationId = "findEnvironments", summary = "Find environments", description = "Find environments for the workspace. Capped at the workspace limit (default 20).", responses = {
            @ApiResponse(responseCode = "200", description = "Environments page", content = @Content(schema = @Schema(implementation = Environment.EnvironmentPage.class)))
    })
    @JsonView({Environment.View.Public.class})
    public Response find() {
        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Finding environments on workspaceId '{}'", workspaceId);
        Environment.EnvironmentPage page = service.find();
        log.info("Found '{}' environments on workspaceId '{}'", page.total(), workspaceId);

        return Response.ok().entity(page).build();
    }

    @GET
    @Path("{id}")
    @Operation(operationId = "getEnvironmentById", summary = "Get environment by id", description = "Get environment by id", responses = {
            @ApiResponse(responseCode = "200", description = "Environment", content = @Content(schema = @Schema(implementation = Environment.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @JsonView({Environment.View.Public.class})
    public Response getById(@PathParam("id") @NotNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Getting environment by id '{}' on workspaceId '{}'", id, workspaceId);
        Environment environment = service.get(id);
        log.info("Got environment by id '{}' on workspaceId '{}'", id, workspaceId);

        return Response.ok().entity(environment).build();
    }

    @POST
    @Operation(operationId = "createEnvironment", summary = "Create environment", description = "Create environment", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/environments/{environmentId}", schema = @Schema(implementation = String.class))}),
            @ApiResponse(responseCode = "409", description = "Conflict", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RateLimited
    public Response create(
            @RequestBody(content = @Content(schema = @Schema(implementation = Environment.class))) @JsonView({
                    Environment.View.Write.class}) @NotNull @Valid Environment environment,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Creating environment with name '{}' on workspaceId '{}'", environment.name(), workspaceId);
        Environment created = service.create(environment);
        log.info("Created environment with id '{}', name '{}' on workspaceId '{}'", created.id(), created.name(),
                workspaceId);

        var uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(created.id())).build();

        return Response.created(uri).build();
    }

    @PATCH
    @Path("{id}")
    @Operation(operationId = "updateEnvironment", summary = "Update environment by id", description = "Update environment by id", responses = {
            @ApiResponse(responseCode = "204", description = "No Content"),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "Conflict", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RateLimited
    public Response update(@PathParam("id") @NotNull UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = EnvironmentUpdate.class))) @NotNull @Valid EnvironmentUpdate environmentUpdate) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Updating environment with id '{}' on workspaceId '{}'", id, workspaceId);
        service.update(id, environmentUpdate);
        log.info("Updated environment with id '{}' on workspaceId '{}'", id, workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/delete")
    @Operation(operationId = "deleteEnvironmentsBatch", summary = "Delete environments", description = "Delete environments batch. Idempotent — missing ids are silently ignored.", responses = {
            @ApiResponse(responseCode = "204", description = "No Content")
    })
    public Response deleteEnvironmentsBatch(
            @NotNull @RequestBody(content = @Content(schema = @Schema(implementation = BatchDelete.class))) @Valid BatchDelete batchDelete) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Deleting environments by ids, count '{}', on workspaceId '{}'", batchDelete.ids().size(),
                workspaceId);
        service.delete(batchDelete.ids());
        log.info("Deleted environments by ids, count '{}', on workspaceId '{}'", batchDelete.ids().size(), workspaceId);

        return Response.noContent().build();
    }
}
