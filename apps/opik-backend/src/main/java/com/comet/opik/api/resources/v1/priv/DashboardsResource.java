package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.ChartDataRequest;
import com.comet.opik.api.ChartDataResponse;
import com.comet.opik.api.ChartPreviewRequest;
import com.comet.opik.api.Dashboard;
import com.comet.opik.api.Dashboard.DashboardPage;
import com.comet.opik.api.DashboardChart;
import com.comet.opik.api.DashboardType;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.sorting.SortingFactoryDashboards;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.ChartDataService;
import com.comet.opik.domain.DashboardChartService;
import com.comet.opik.domain.DashboardService;
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

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.utils.AsyncUtils.setRequestContext;

@Path("/v1/private/dashboards")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Dashboards", description = "Dashboard management resources")
public class DashboardsResource {

    private static final String PAGE_SIZE = "10";
    private final @NonNull DashboardService dashboardService;
    private final @NonNull DashboardChartService chartService;
    private final @NonNull ChartDataService chartDataService;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull SortingFactoryDashboards sortingFactory;

    @GET
    @Operation(operationId = "findDashboards", summary = "Find dashboards", description = "Find dashboards", responses = {
            @ApiResponse(responseCode = "200", description = "Dashboard list", content = @Content(schema = @Schema(implementation = DashboardPage.class)))
    })
    @JsonView({Dashboard.View.Public.class})
    public Response find(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue(PAGE_SIZE) int size,
            @QueryParam("project_id") UUID projectId,
            @QueryParam("name") String name,
            @QueryParam("type") DashboardType type,
            @QueryParam("sorting") String sorting) {

        String workspaceId = requestContext.get().getWorkspaceId();

        List<SortingField> sortingFields = sortingFactory.newSorting(sorting);

        log.info("Finding dashboards with filters - projectId: '{}', name: '{}', type: '{}' on workspaceId '{}'",
                projectId, name, type, workspaceId);

        DashboardPage dashboardPage = dashboardService.find(page - 1, size, projectId, name, type, sortingFields);

        log.info("Found {} dashboards on workspaceId '{}'", dashboardPage.size(), workspaceId);

        return Response.ok().entity(dashboardPage).build();
    }

    @GET
    @Path("/{id}")
    @Operation(operationId = "getDashboardById", summary = "Get dashboard by id", description = "Get dashboard by id", responses = {
            @ApiResponse(responseCode = "200", description = "Dashboard resource", content = @Content(schema = @Schema(implementation = Dashboard.class)))
    })
    @JsonView({Dashboard.View.Public.class})
    public Response getById(@PathParam("id") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting dashboard by id '{}' on workspace_id '{}'", id, workspaceId);

        Dashboard dashboard = dashboardService.get(id);

        log.info("Got dashboard by id '{}' on workspace_id '{}'", id, workspaceId);

        return Response.ok().entity(dashboard).build();
    }

    @POST
    @Operation(operationId = "createDashboard", summary = "Create dashboard", description = "Create dashboard", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/dashboards/{dashboardId}", schema = @Schema(implementation = String.class))}),
            @ApiResponse(responseCode = "422", description = "Unprocessable Content", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RateLimited
    public Response create(
            @RequestBody(content = @Content(schema = @Schema(implementation = Dashboard.class))) @JsonView(Dashboard.View.Write.class) @Valid Dashboard dashboard,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Creating dashboard with name '{}', on workspace_id '{}'", dashboard.name(), workspaceId);

        var dashboardId = dashboardService.create(dashboard).id();

        log.info("Created dashboard with name '{}', id '{}', on workspace_id '{}'", dashboard.name(), dashboardId,
                workspaceId);

        var uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(dashboardId)).build();

        return Response.created(uri).build();
    }

    @PATCH
    @Path("/{id}")
    @Operation(operationId = "updateDashboard", summary = "Update dashboard by id", description = "Update dashboard by id", responses = {
            @ApiResponse(responseCode = "204", description = "No Content"),
            @ApiResponse(responseCode = "422", description = "Unprocessable Content", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RateLimited
    public Response update(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = Dashboard.class))) @JsonView(Dashboard.View.Write.class) @Valid Dashboard dashboard) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Updating dashboard with id '{}' on workspaceId '{}'", id, workspaceId);
        dashboardService.update(id, dashboard);
        log.info("Updated dashboard with id '{}' on workspaceId '{}'", id, workspaceId);

        return Response.noContent().build();
    }

    @DELETE
    @Path("/{id}")
    @Operation(operationId = "deleteDashboardById", summary = "Delete dashboard by id", description = "Delete dashboard by id", responses = {
            @ApiResponse(responseCode = "204", description = "No Content")
    })
    public Response deleteById(@PathParam("id") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Deleting dashboard by id '{}' on workspaceId '{}'", id, workspaceId);
        dashboardService.delete(id);
        log.info("Deleted dashboard by id '{}' on workspaceId '{}'", id, workspaceId);

        return Response.noContent().build();
    }

    @DELETE
    @Path("/")
    @Operation(operationId = "deleteDashboardsBatch", summary = "Delete dashboards batch", description = "Delete dashboards batch", responses = {
            @ApiResponse(responseCode = "204", description = "No Content")
    })
    public Response deleteBatch(@Valid @NotNull BatchDelete batchDelete) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Deleting dashboards batch, count '{}' on workspaceId '{}'", batchDelete.ids().size(), workspaceId);
        dashboardService.delete(Set.copyOf(batchDelete.ids()));
        log.info("Deleted dashboards batch, count '{}' on workspaceId '{}'", batchDelete.ids().size(), workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/{id}/set-default")
    @Operation(operationId = "setDashboardAsDefault", summary = "Set dashboard as default for project", description = "Set dashboard as default for a specific project", responses = {
            @ApiResponse(responseCode = "204", description = "No Content"),
            @ApiResponse(responseCode = "404", description = "Not Found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response setAsDefault(
            @PathParam("id") UUID dashboardId,
            @QueryParam("project_id") @NotNull UUID projectId) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Setting dashboard '{}' as default for project '{}' on workspaceId '{}'", dashboardId, projectId,
                workspaceId);

        dashboardService.setAsDefault(dashboardId, projectId);

        log.info("Set dashboard '{}' as default for project '{}' on workspaceId '{}'", dashboardId, projectId,
                workspaceId);

        return Response.noContent().build();
    }

    // Chart endpoints (nested under dashboard)

    @GET
    @Path("/{dashboard_id}/charts")
    @Operation(operationId = "getChartsByDashboard", summary = "Get charts by dashboard", description = "Get all charts for a dashboard", responses = {
            @ApiResponse(responseCode = "200", description = "Charts list", content = @Content(schema = @Schema(implementation = DashboardChart.class)))
    })
    @JsonView({DashboardChart.View.Public.class})
    public Response getCharts(@PathParam("dashboard_id") UUID dashboardId) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting charts for dashboard '{}' on workspaceId '{}'", dashboardId, workspaceId);

        List<DashboardChart> charts = chartService.findByDashboardId(dashboardId);

        log.info("Got {} charts for dashboard '{}' on workspaceId '{}'", charts.size(), dashboardId, workspaceId);

        return Response.ok().entity(charts).build();
    }

    @GET
    @Path("/{dashboard_id}/charts/{chart_id}")
    @Operation(operationId = "getChartById", summary = "Get chart by id", description = "Get chart by id", responses = {
            @ApiResponse(responseCode = "200", description = "Chart resource", content = @Content(schema = @Schema(implementation = DashboardChart.class)))
    })
    @JsonView({DashboardChart.View.Public.class})
    public Response getChartById(
            @PathParam("dashboard_id") UUID dashboardId,
            @PathParam("chart_id") UUID chartId) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting chart '{}' for dashboard '{}' on workspaceId '{}'", chartId, dashboardId, workspaceId);

        DashboardChart chart = chartService.get(chartId);

        log.info("Got chart '{}' for dashboard '{}' on workspaceId '{}'", chartId, dashboardId, workspaceId);

        return Response.ok().entity(chart).build();
    }

    @POST
    @Path("/{dashboard_id}/charts")
    @Operation(operationId = "createChart", summary = "Create chart", description = "Create a new chart on dashboard", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, schema = @Schema(implementation = String.class))}),
            @ApiResponse(responseCode = "422", description = "Unprocessable Content", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RateLimited
    public Response createChart(
            @PathParam("dashboard_id") UUID dashboardId,
            @RequestBody(content = @Content(schema = @Schema(implementation = DashboardChart.class))) @JsonView(DashboardChart.View.Write.class) @Valid DashboardChart chart,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Creating chart '{}' for dashboard '{}' on workspace_id '{}'", chart.name(), dashboardId,
                workspaceId);

        // Ensure dashboard ID is set
        DashboardChart chartWithDashboard = chart.toBuilder().dashboardId(dashboardId).build();
        var chartId = chartService.create(chartWithDashboard).id();

        log.info("Created chart '{}', id '{}' for dashboard '{}' on workspace_id '{}'", chart.name(), chartId,
                dashboardId, workspaceId);

        var uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(chartId)).build();

        return Response.created(uri).build();
    }

    @PATCH
    @Path("/{dashboard_id}/charts/{chart_id}")
    @Operation(operationId = "updateChart", summary = "Update chart by id", description = "Update chart by id", responses = {
            @ApiResponse(responseCode = "204", description = "No Content"),
            @ApiResponse(responseCode = "422", description = "Unprocessable Content", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RateLimited
    public Response updateChart(
            @PathParam("dashboard_id") UUID dashboardId,
            @PathParam("chart_id") UUID chartId,
            @RequestBody(content = @Content(schema = @Schema(implementation = DashboardChart.class))) @JsonView(DashboardChart.View.Write.class) @Valid DashboardChart chart) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Updating chart '{}' for dashboard '{}' on workspaceId '{}'", chartId, dashboardId, workspaceId);
        chartService.update(chartId, chart);
        log.info("Updated chart '{}' for dashboard '{}' on workspaceId '{}'", chartId, dashboardId, workspaceId);

        return Response.noContent().build();
    }

    @DELETE
    @Path("/{dashboard_id}/charts/{chart_id}")
    @Operation(operationId = "deleteChartById", summary = "Delete chart by id", description = "Delete chart by id", responses = {
            @ApiResponse(responseCode = "204", description = "No Content")
    })
    public Response deleteChartById(
            @PathParam("dashboard_id") UUID dashboardId,
            @PathParam("chart_id") UUID chartId) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Deleting chart '{}' from dashboard '{}' on workspaceId '{}'", chartId, dashboardId, workspaceId);
        chartService.delete(chartId);
        log.info("Deleted chart '{}' from dashboard '{}' on workspaceId '{}'", chartId, dashboardId, workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/{dashboard_id}/charts/{chart_id}/clone")
    @Operation(operationId = "cloneChart", summary = "Clone chart", description = "Clone a chart to same or different dashboard", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, schema = @Schema(implementation = String.class))}),
            @ApiResponse(responseCode = "404", description = "Not Found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response cloneChart(
            @PathParam("dashboard_id") UUID dashboardId,
            @PathParam("chart_id") UUID chartId,
            @QueryParam("target_dashboard_id") UUID targetDashboardId,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();

        UUID targetId = targetDashboardId != null ? targetDashboardId : dashboardId;

        log.info("Cloning chart '{}' from dashboard '{}' to dashboard '{}' on workspaceId '{}'", chartId, dashboardId,
                targetId, workspaceId);

        DashboardChart clonedChart = chartService.clone(chartId, targetId);

        log.info("Cloned chart '{}' to new chart '{}' on dashboard '{}' on workspaceId '{}'", chartId,
                clonedChart.id(), targetId, workspaceId);

        var uri = uriInfo.getBaseUriBuilder()
                .path(DashboardsResource.class)
                .path("/{dashboard_id}/charts/{chart_id}")
                .build(targetId, clonedChart.id());

        return Response.created(uri).build();
    }

    @POST
    @Path("/{dashboard_id}/charts/{chart_id}/data")
    @Operation(operationId = "getChartData", summary = "Get chart data", description = "Get data points for a chart", responses = {
            @ApiResponse(responseCode = "200", description = "Chart data", content = @Content(schema = @Schema(implementation = ChartDataResponse.class)))
    })
    public Response getChartData(
            @PathParam("dashboard_id") UUID dashboardId,
            @PathParam("chart_id") UUID chartId,
            @RequestBody(content = @Content(schema = @Schema(implementation = ChartDataRequest.class))) @Valid @NotNull ChartDataRequest request) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting data for chart '{}' on dashboard '{}', interval: '{}' on workspaceId '{}'", chartId,
                dashboardId, request.interval(), workspaceId);

        ChartDataResponse chartData = chartDataService.getChartData(chartId, request)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Got data for chart '{}' with {} series on workspaceId '{}'", chartId,
                chartData.series().size(), workspaceId);

        return Response.ok().entity(chartData).build();
    }

    @POST
    @Path("/{dashboard_id}/charts/preview/data")
    @Operation(operationId = "getChartPreviewData", summary = "Get chart preview data", description = "Get preview data for chart configuration without saving", responses = {
            @ApiResponse(responseCode = "200", description = "Chart preview data", content = @Content(schema = @Schema(implementation = ChartDataResponse.class)))
    })
    public Response getChartPreviewData(
            @PathParam("dashboard_id") UUID dashboardId,
            @RequestBody(content = @Content(schema = @Schema(implementation = ChartPreviewRequest.class))) @Valid @NotNull ChartPreviewRequest request) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting preview data for dashboard '{}', interval: '{}' on workspaceId '{}'", dashboardId,
                request.chartDataRequest().interval(), workspaceId);

        ChartDataResponse chartData = chartDataService.getChartPreviewData(request.chart(), request.chartDataRequest())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Got preview data with {} series on workspaceId '{}'", chartData.series().size(), workspaceId);

        return Response.ok().entity(chartData).build();
    }
}
