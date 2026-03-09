package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.Dashboard;
import com.comet.opik.api.Dashboard.DashboardPage;
import com.comet.opik.api.DashboardUpdate;
import com.comet.opik.api.sorting.SortingFactoryDashboards;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.DashboardService;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.auth.RequiredPermissions;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
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
import java.util.List;
import java.util.UUID;

@Path("/v1/private/dashboards")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Dashboards", description = "Workspace Dashboard resources")
public class DashboardsResource {

    private final @NonNull DashboardService service;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull SortingFactoryDashboards sortingFactory;

    @POST
    @Operation(operationId = "createDashboard", summary = "Create dashboard", description = "Create a new dashboard in a workspace", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/dashboards/{dashboardId}", schema = @Schema(implementation = String.class))}, content = @Content(schema = @Schema(implementation = Dashboard.class)))
    })
    @JsonView(Dashboard.View.Public.class)
    @RateLimited
    public Response createDashboard(
            @RequestBody(content = @Content(schema = @Schema(implementation = Dashboard.class))) @JsonView(Dashboard.View.Write.class) @NotNull @Valid Dashboard dashboard,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Creating dashboard with name '{}' in workspace '{}'", dashboard.name(), workspaceId);

        Dashboard savedDashboard = service.create(dashboard);

        log.info("Created dashboard with id '{}', name '{}', slug '{}' in workspace '{}'",
                savedDashboard.id(), savedDashboard.name(), savedDashboard.slug(), workspaceId);

        URI uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(savedDashboard.id().toString())).build();
        return Response.created(uri).entity(savedDashboard).build();
    }

    @GET
    @Path("/{dashboardId}")
    @Operation(operationId = "getDashboardById", summary = "Get dashboard by id", description = "Get dashboard by id", responses = {
            @ApiResponse(responseCode = "200", description = "Dashboard resource", content = @Content(schema = @Schema(implementation = Dashboard.class))),
            @ApiResponse(responseCode = "404", description = "Dashboard not found")
    })
    @RequiredPermissions(WorkspaceUserPermission.DASHBOARD_VIEW)
    @JsonView(Dashboard.View.Public.class)
    public Response getDashboardById(@PathParam("dashboardId") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Finding dashboard by id '{}' in workspace '{}'", id, workspaceId);

        Dashboard dashboard = service.findById(id);

        log.info("Found dashboard by id '{}', name '{}' in workspace '{}'", id, dashboard.name(), workspaceId);
        return Response.ok().entity(dashboard).build();
    }

    @GET
    @Operation(operationId = "findDashboards", summary = "Find dashboards", description = "Find dashboards in a workspace", responses = {
            @ApiResponse(responseCode = "200", description = "Dashboard page", content = @Content(schema = @Schema(implementation = DashboardPage.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.DASHBOARD_VIEW)
    @JsonView(Dashboard.View.Public.class)
    public Response findDashboards(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("name") @Schema(description = "Filter dashboards by name (partial match, case insensitive)") String name,
            @QueryParam("sorting") String sorting) {

        String workspaceId = requestContext.get().getWorkspaceId();
        List<SortingField> sortingFields = sortingFactory.newSorting(sorting);

        log.info("Finding dashboards in workspace '{}', page '{}', size '{}', name '{}', sorting '{}'",
                workspaceId, page, size, name, sorting);

        DashboardPage dashboardPage = service.find(page, size, name, sortingFields);

        log.info("Found '{}' dashboards in workspace '{}'", dashboardPage.total(), workspaceId);
        return Response.ok(dashboardPage).build();
    }

    @PATCH
    @Path("/{dashboardId}")
    @Operation(operationId = "updateDashboard", summary = "Update dashboard", description = "Update dashboard by id. Partial updates are supported - only provided fields will be updated.", responses = {
            @ApiResponse(responseCode = "200", description = "Updated dashboard", content = @Content(schema = @Schema(implementation = Dashboard.class))),
            @ApiResponse(responseCode = "404", description = "Dashboard not found"),
            @ApiResponse(responseCode = "409", description = "Conflict - dashboard with this name already exists")
    })
    @JsonView(Dashboard.View.Public.class)
    @RateLimited
    public Response updateDashboard(
            @PathParam("dashboardId") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = DashboardUpdate.class))) @NotNull @Valid DashboardUpdate dashboardUpdate) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Updating dashboard by id '{}' in workspace '{}'", id, workspaceId);

        Dashboard updatedDashboard = service.update(id, dashboardUpdate);

        log.info("Updated dashboard by id '{}', name '{}' in workspace '{}'",
                id, updatedDashboard.name(), workspaceId);

        return Response.ok().entity(updatedDashboard).build();
    }

    @DELETE
    @Path("/{dashboardId}")
    @Operation(operationId = "deleteDashboard", summary = "Delete dashboard", description = "Delete dashboard by id", responses = {
            @ApiResponse(responseCode = "204", description = "No content")
    })
    public Response deleteDashboard(@PathParam("dashboardId") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Deleting dashboard by id '{}' in workspace '{}'", id, workspaceId);

        service.delete(id);

        log.info("Deleted dashboard by id '{}' in workspace '{}'", id, workspaceId);
        return Response.noContent().build();
    }

    @POST
    @Path("/delete-batch")
    @Operation(operationId = "deleteDashboardsBatch", summary = "Delete dashboards", description = "Delete dashboards batch", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
    })
    public Response deleteDashboardsBatch(
            @NotNull @RequestBody(content = @Content(schema = @Schema(implementation = BatchDelete.class))) @Valid BatchDelete batchDelete) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Deleting dashboards by ids, count '{}' in workspace '{}'", batchDelete.ids().size(), workspaceId);
        service.delete(batchDelete.ids());
        log.info("Deleted dashboards by ids, count '{}' in workspace '{}'", batchDelete.ids().size(), workspaceId);

        return Response.noContent().build();
    }
}
