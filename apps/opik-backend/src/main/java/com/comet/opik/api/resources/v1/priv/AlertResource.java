package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.Alert;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.AlertService;
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
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
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

@Path("/v1/private/alerts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Alerts", description = "Alert resources")
public class AlertResource {

    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull AlertService alertService;

    @POST
    @Operation(operationId = "createAlert", summary = "Create alert", description = "Create alert", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/alerts/{alertId}", schema = @Schema(implementation = String.class))}),
            @ApiResponse(responseCode = "422", description = "Unprocessable Content", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "Conflict", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class)))
    })
    @RateLimited
    public Response createAlert(
            @RequestBody(content = @Content(schema = @Schema(implementation = Alert.class))) @JsonView(Alert.View.Write.class) @Valid @NotNull Alert alert,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Creating alert with name '{}', on workspace_id '{}'", alert.name(), workspaceId);

        var alertId = alertService.create(alert);

        log.info("Created alert with name '{}', id '{}', on workspace_id '{}'", alert.name(), alertId,
                workspaceId);

        var uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(alertId)).build();

        return Response.created(uri).build();
    }

    @PUT
    @Path("{id}")
    @Operation(operationId = "updateAlert", summary = "Update alert", description = "Update alert", responses = {
            @ApiResponse(responseCode = "204", description = "No Content"),
            @ApiResponse(responseCode = "422", description = "Unprocessable Content", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "Conflict", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class)))
    })
    @RateLimited
    public Response updateAlert(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = Alert.class))) @JsonView(Alert.View.Write.class) @Valid @NotNull Alert alert) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Updating alert with id '{}', on workspace_id '{}'", id, workspaceId);

        alertService.update(id, alert);

        log.info("Updated alert with id '{}', on workspace_id '{}'", id, workspaceId);

        return Response.noContent().build();
    }

    @GET
    @Path("/{id}")
    @Operation(operationId = "getAlertById", summary = "Get Alert by id", description = "Get Alert by id", responses = {
            @ApiResponse(responseCode = "200", description = "Alert resource", content = @Content(schema = @Schema(implementation = Alert.class))),
            @ApiResponse(responseCode = "404", description = "Not Found", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class)))
    })
    @JsonView(Alert.View.Public.class)
    public Response getAlertById(@PathParam("id") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Finding Alert by id '{}' on workspaceId '{}'", id, workspaceId);

        var alert = alertService.getById(id);

        log.info("Found Alert by id '{}' on workspaceId '{}'", id, workspaceId);

        return Response.ok().entity(alert).build();
    }

    @POST
    @Path("/delete")
    @Operation(operationId = "deleteAlertBatch", summary = "Delete alert batch", description = "Delete multiple alerts by their IDs", responses = {
            @ApiResponse(responseCode = "204", description = "No Content"),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class)))
    })
    public Response deleteAlertBatch(
            @RequestBody(content = @Content(schema = @Schema(implementation = BatchDelete.class))) @Valid @NotNull BatchDelete batch) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Deleting alert batch with '{}' items, workspaceId '{}'",
                batch.ids().size(), workspaceId);

        alertService.deleteBatch(batch.ids());

        log.info("Deleted alert batch with '{}' items deleted, workspaceId '{}'",
                batch.ids().size(), workspaceId);

        return Response.noContent().build();
    }
}
