package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.Dashboard;
import com.comet.opik.api.Dashboard.DashboardPage;
import com.comet.opik.api.DashboardScope;
import com.comet.opik.api.DashboardUpdate;
import com.comet.opik.api.filter.DashboardFilter;
import com.comet.opik.api.filter.FiltersFactory;
import com.comet.opik.api.sorting.SortingFactoryDashboards;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.DashboardService;
import com.comet.opik.domain.IdGenerator;
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

@Path("/v1/private/insights-views")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Insights Views", description = "Insights View resources")
public class InsightsViewsResource {

    private final @NonNull DashboardService service;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull SortingFactoryDashboards sortingFactory;
    private final @NonNull FiltersFactory filtersFactory;

    @POST
    @Operation(operationId = "createInsightsView", summary = "Create insights view", description = "Create a new insights view in a workspace", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/insights-views/{insightsViewId}", schema = @Schema(implementation = String.class))}, content = @Content(schema = @Schema(implementation = Dashboard.class)))
    })
    @JsonView(Dashboard.View.Public.class)
    @RateLimited
    public Response createInsightsView(
            @RequestBody(content = @Content(schema = @Schema(implementation = Dashboard.class))) @JsonView(Dashboard.View.Write.class) @NotNull @Valid Dashboard dashboard,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Creating insights view with name '{}' in workspace '{}'", dashboard.name(), workspaceId);

        Dashboard savedDashboard = service.create(dashboard, DashboardScope.INSIGHTS);

        log.info("Created insights view with id '{}', name '{}', slug '{}' in workspace '{}'",
                savedDashboard.id(), savedDashboard.name(), savedDashboard.slug(), workspaceId);

        URI uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(savedDashboard.id().toString())).build();
        return Response.created(uri).entity(savedDashboard).build();
    }

    @GET
    @Path("/{insightsViewId}")
    @Operation(operationId = "getInsightsViewById", summary = "Get insights view by id", description = "Get insights view by id", responses = {
            @ApiResponse(responseCode = "200", description = "Insights view resource", content = @Content(schema = @Schema(implementation = Dashboard.class))),
            @ApiResponse(responseCode = "404", description = "Insights view not found")
    })
    @JsonView(Dashboard.View.Public.class)
    public Response getInsightsViewById(@PathParam("insightsViewId") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Finding insights view by id '{}' in workspace '{}'", id, workspaceId);

        Dashboard dashboard = service.findById(id, DashboardScope.INSIGHTS);

        log.info("Found insights view by id '{}', name '{}' in workspace '{}'", id, dashboard.name(), workspaceId);
        return Response.ok().entity(dashboard).build();
    }

    @GET
    @Operation(operationId = "findInsightsViews", summary = "Find insights views", description = "Find insights views in a workspace", responses = {
            @ApiResponse(responseCode = "200", description = "Insights view page", content = @Content(schema = @Schema(implementation = DashboardPage.class)))
    })
    @JsonView(Dashboard.View.Public.class)
    public Response findInsightsViews(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("name") @Schema(description = "Filter insights views by name (partial match, case insensitive)") String name,
            @QueryParam("project_id") UUID projectId,
            @QueryParam("sorting") String sorting,
            @QueryParam("filters") String filters) {

        String workspaceId = requestContext.get().getWorkspaceId();
        List<SortingField> sortingFields = sortingFactory.newSorting(sorting);
        var dashboardFilters = filtersFactory.newFilters(filters, DashboardFilter.LIST_TYPE_REFERENCE);

        log.info("Finding insights views in workspace '{}', page '{}', size '{}', name '{}', sorting '{}'",
                workspaceId, page, size, name, sorting);

        DashboardPage dashboardPage = service.find(page, size, name, projectId, sortingFields, dashboardFilters,
                DashboardScope.INSIGHTS);

        log.info("Found '{}' insights views in workspace '{}'", dashboardPage.total(), workspaceId);

        return Response.ok(dashboardPage).build();
    }

    @PATCH
    @Path("/{insightsViewId}")
    @Operation(operationId = "updateInsightsView", summary = "Update insights view", description = "Update insights view by id. Partial updates are supported - only provided fields will be updated.", responses = {
            @ApiResponse(responseCode = "200", description = "Updated insights view", content = @Content(schema = @Schema(implementation = Dashboard.class))),
            @ApiResponse(responseCode = "404", description = "Insights view not found"),
            @ApiResponse(responseCode = "409", description = "Conflict - insights view with this name already exists")
    })
    @JsonView(Dashboard.View.Public.class)
    @RateLimited
    public Response updateInsightsView(
            @PathParam("insightsViewId") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = DashboardUpdate.class))) @NotNull @Valid DashboardUpdate dashboardUpdate) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Updating insights view by id '{}' in workspace '{}'", id, workspaceId);

        Dashboard updatedDashboard = service.update(id, dashboardUpdate, DashboardScope.INSIGHTS);

        log.info("Updated insights view by id '{}', name '{}' in workspace '{}'",
                id, updatedDashboard.name(), workspaceId);

        return Response.ok().entity(updatedDashboard).build();
    }

    @DELETE
    @Path("/{insightsViewId}")
    @Operation(operationId = "deleteInsightsView", summary = "Delete insights view", description = "Delete insights view by id", responses = {
            @ApiResponse(responseCode = "204", description = "No content")
    })
    public Response deleteInsightsView(@PathParam("insightsViewId") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Deleting insights view by id '{}' in workspace '{}'", id, workspaceId);

        service.delete(id, DashboardScope.INSIGHTS);

        log.info("Deleted insights view by id '{}' in workspace '{}'", id, workspaceId);
        return Response.noContent().build();
    }

    @POST
    @Path("/delete-batch")
    @Operation(operationId = "deleteInsightsViewsBatch", summary = "Delete insights views", description = "Delete insights views batch", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
    })
    public Response deleteInsightsViewsBatch(
            @NotNull @RequestBody(content = @Content(schema = @Schema(implementation = BatchDelete.class))) @Valid BatchDelete batchDelete) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Deleting insights views by ids, count '{}' in workspace '{}'", batchDelete.ids().size(), workspaceId);
        service.delete(batchDelete.ids(), DashboardScope.INSIGHTS);
        log.info("Deleted insights views by ids, count '{}' in workspace '{}'", batchDelete.ids().size(), workspaceId);

        return Response.noContent().build();
    }
}
