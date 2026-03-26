package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.Dashboard;
import com.comet.opik.api.Dashboard.DashboardPage;
import com.comet.opik.api.DashboardScope;
import com.comet.opik.api.filter.DashboardFilter;
import com.comet.opik.api.filter.FiltersFactory;
import com.comet.opik.api.sorting.SortingFactoryDashboards;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.DashboardService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.auth.RequiredPermissions;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import com.fasterxml.jackson.annotation.JsonView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.validation.constraints.Min;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;

@Path("/v1/private/projects/{projectId}/dashboards")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Projects", description = "Project related resources")
public class ProjectDashboardsResource {

    private final @NonNull DashboardService service;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull SortingFactoryDashboards sortingFactory;
    private final @NonNull FiltersFactory filtersFactory;

    @GET
    @Operation(operationId = "findDashboardsByProject", summary = "Find dashboards by project", description = "Find dashboards scoped to a project", responses = {
            @ApiResponse(responseCode = "200", description = "Dashboard page", content = @Content(schema = @Schema(implementation = DashboardPage.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.DASHBOARD_VIEW)
    @JsonView(Dashboard.View.Public.class)
    public Response findDashboards(
            @PathParam("projectId") UUID projectId,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("name") @Schema(description = "Filter dashboards by name (partial match, case insensitive)") String name,
            @QueryParam("sorting") String sorting,
            @QueryParam("filters") String filters) {

        String workspaceId = requestContext.get().getWorkspaceId();
        List<SortingField> sortingFields = sortingFactory.newSorting(sorting);
        var dashboardFilters = filtersFactory.newFilters(filters, DashboardFilter.LIST_TYPE_REFERENCE);

        log.info("Finding dashboards for project '{}' in workspace '{}', page '{}', size '{}'",
                projectId, workspaceId, page, size);

        DashboardPage dashboardPage = service.find(page, size, name, projectId, sortingFields, dashboardFilters,
                DashboardScope.WORKSPACE);

        log.info("Found '{}' dashboards for project '{}' in workspace '{}'", dashboardPage.total(), projectId,
                workspaceId);
        return Response.ok(dashboardPage).build();
    }

}
