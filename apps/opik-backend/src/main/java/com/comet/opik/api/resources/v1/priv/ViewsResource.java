package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.view.View;
import com.comet.opik.api.view.ViewCreate;
import com.comet.opik.api.view.ViewCurrentVersion;
import com.comet.opik.api.view.ViewUpdate;
import com.comet.opik.api.view.ViewVersion;
import com.comet.opik.api.view.ViewVersionCreate;
import com.comet.opik.api.view.ViewVersionPage;
import com.comet.opik.domain.view.ViewService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.auth.RequiredPermissions;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
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
import jakarta.validation.constraints.Max;
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

import java.util.UUID;

@Path("/v1/private/views")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Views", description = "Generated view resources")
public class ViewsResource {

    private final @NonNull ViewService service;
    private final @NonNull Provider<RequestContext> requestContext;

    @POST
    @Operation(operationId = "createView", summary = "Create view", description = "Create a view with its first version", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/views/{viewId}", schema = @Schema(implementation = String.class))}, content = @Content(schema = @Schema(implementation = View.class))),
            @ApiResponse(responseCode = "422", description = "Unprocessable Content", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RateLimited
    @RequiredPermissions(WorkspaceUserPermission.GENERATIVE_UI_CREATE)
    public Response createView(
            @RequestBody(content = @Content(schema = @Schema(implementation = ViewCreate.class))) @NotNull @Valid ViewCreate request,
            @Context UriInfo uriInfo) {

        log.info("Creating view, area '{}', workspaceId '{}'", request.area(), requestContext.get().getWorkspaceId());
        View view = service.create(request);
        var uri = uriInfo.getAbsolutePathBuilder().path(view.id().toString()).build();
        return Response.created(uri).entity(view).build();
    }

    @POST
    @Path("/{id}/versions")
    @Operation(operationId = "createViewVersion", summary = "Create view version", description = "Add a version to a view and make it current", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/views/{viewId}/versions/{version}", schema = @Schema(implementation = String.class))}, content = @Content(schema = @Schema(implementation = ViewVersion.class))),
            @ApiResponse(responseCode = "404", description = "View not found"),
            @ApiResponse(responseCode = "422", description = "Unprocessable Content", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RateLimited
    @RequiredPermissions(WorkspaceUserPermission.GENERATIVE_UI_EDIT)
    public Response createViewVersion(
            @PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = ViewVersionCreate.class))) @NotNull @Valid ViewVersionCreate request,
            @Context UriInfo uriInfo) {

        log.info("Creating view version, id '{}', workspaceId '{}'", id, requestContext.get().getWorkspaceId());
        ViewVersion version = service.createVersion(id, request);
        var uri = uriInfo.getAbsolutePathBuilder().path(String.valueOf(version.version())).build();
        return Response.created(uri).entity(version).build();
    }

    @GET
    @Path("/{id}")
    @Operation(operationId = "getViewById", summary = "Get view by id", description = "Get a view with its current version", responses = {
            @ApiResponse(responseCode = "200", description = "View resource", content = @Content(schema = @Schema(implementation = View.class))),
            @ApiResponse(responseCode = "404", description = "View not found")
    })
    @RequiredPermissions(WorkspaceUserPermission.GENERATIVE_UI_VIEW)
    public Response getViewById(@PathParam("id") UUID id) {
        return Response.ok(service.get(id)).build();
    }

    @GET
    @Path("/{id}/versions")
    @Operation(operationId = "getViewVersions", summary = "Get view versions", description = "List the versions of a view, newest first", responses = {
            @ApiResponse(responseCode = "200", description = "View versions", content = @Content(schema = @Schema(implementation = ViewVersionPage.class))),
            @ApiResponse(responseCode = "404", description = "View not found")
    })
    @RequiredPermissions(WorkspaceUserPermission.GENERATIVE_UI_VIEW)
    public Response getViewVersions(
            @PathParam("id") UUID id,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @Max(100) @DefaultValue("10") int size) {
        return Response.ok(service.getVersions(id, page, size)).build();
    }

    @GET
    @Path("/{id}/versions/{version}")
    @Operation(operationId = "getViewVersion", summary = "Get view version", description = "Get one version of a view", responses = {
            @ApiResponse(responseCode = "200", description = "View version", content = @Content(schema = @Schema(implementation = ViewVersion.class))),
            @ApiResponse(responseCode = "404", description = "View or version not found")
    })
    @RequiredPermissions(WorkspaceUserPermission.GENERATIVE_UI_VIEW)
    public Response getViewVersion(@PathParam("id") UUID id, @PathParam("version") @Min(1) int version) {
        return Response.ok(service.getVersion(id, version)).build();
    }

    @DELETE
    @Path("/{id}/versions/{version}")
    @Operation(operationId = "deleteViewVersion", summary = "Delete view version", description = "Delete one version of a view. Refused for the current version, and so for the only version; version numbers are never reused.", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
            @ApiResponse(responseCode = "404", description = "View or version not found"),
            @ApiResponse(responseCode = "409", description = "The version is the current one")
    })
    @RequiredPermissions(WorkspaceUserPermission.GENERATIVE_UI_DELETE)
    public Response deleteViewVersion(@PathParam("id") UUID id, @PathParam("version") @Min(1) int version) {
        log.info("Deleting view version, id '{}', version '{}', workspaceId '{}'", id, version,
                requestContext.get().getWorkspaceId());
        service.deleteVersion(id, version);
        return Response.noContent().build();
    }

    @PUT
    @Path("/{id}/current")
    @Operation(operationId = "setViewCurrentVersion", summary = "Select view version", description = "Make an existing version the one that renders. Also used for rollback; writes no version.", responses = {
            @ApiResponse(responseCode = "200", description = "View resource", content = @Content(schema = @Schema(implementation = View.class))),
            @ApiResponse(responseCode = "404", description = "View or version not found")
    })
    @RequiredPermissions(WorkspaceUserPermission.GENERATIVE_UI_EDIT)
    public Response setViewCurrentVersion(
            @PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = ViewCurrentVersion.class))) @NotNull @Valid ViewCurrentVersion request) {

        log.info("Selecting view version, id '{}', version '{}', workspaceId '{}'", id, request.version(),
                requestContext.get().getWorkspaceId());
        return Response.ok(service.setCurrentVersion(id, request.version())).build();
    }

    @PATCH
    @Path("/{id}")
    @Operation(operationId = "updateView", summary = "Update view", description = "Update the name and description of a view", responses = {
            @ApiResponse(responseCode = "200", description = "View resource", content = @Content(schema = @Schema(implementation = View.class))),
            @ApiResponse(responseCode = "404", description = "View not found")
    })
    @RateLimited
    @RequiredPermissions(WorkspaceUserPermission.GENERATIVE_UI_EDIT)
    public Response updateView(
            @PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = ViewUpdate.class))) @NotNull @Valid ViewUpdate update) {

        log.info("Updating view, id '{}', workspaceId '{}'", id, requestContext.get().getWorkspaceId());
        return Response.ok(service.update(id, update)).build();
    }

    @DELETE
    @Path("/{id}")
    @Operation(operationId = "deleteView", summary = "Delete view", description = "Soft delete a view.", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
            @ApiResponse(responseCode = "404", description = "View not found")
    })
    @RequiredPermissions(WorkspaceUserPermission.GENERATIVE_UI_DELETE)
    public Response deleteView(@PathParam("id") UUID id) {

        log.info("Deleting view, id '{}', workspaceId '{}'", id, requestContext.get().getWorkspaceId());
        service.delete(id);
        return Response.noContent().build();
    }

}
