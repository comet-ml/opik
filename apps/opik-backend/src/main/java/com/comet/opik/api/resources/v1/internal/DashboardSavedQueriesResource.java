package com.comet.opik.api.resources.v1.internal;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.DashboardSavedQuery;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.DashboardSavedQueryService;
import com.comet.opik.infrastructure.CustomChartsConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.UUID;

/**
 * Queries Ollie pinned to a chart, which {@code ollie_chart} dashboard widgets reference by id. Internal so that only
 * Ollie can create one: the browser can place, move and remove a widget, but not set the SQL it runs. Gated like
 * Custom Charts free-form SQL, on {@code ollieEnabled} and {@code customCharts.enabled}.
 */
@Path("/v1/internal/dashboard-saved-queries")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@Tag(name = "Dashboard saved queries", description = "Internal endpoints for the queries Ollie chart widgets run")
public class DashboardSavedQueriesResource {

    private final DashboardSavedQueryService dashboardSavedQueryService;
    private final ServiceTogglesConfig serviceToggles;
    private final CustomChartsConfig customCharts;

    // Hand-written rather than Lombok's: image builds do not read lombok.config, so a generated constructor loses the
    // @Config qualifiers and customCharts is injected as an empty default instance (OPIK-8548).
    @Inject
    public DashboardSavedQueriesResource(
            @NonNull DashboardSavedQueryService dashboardSavedQueryService,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceToggles,
            @NonNull @Config("customCharts") CustomChartsConfig customCharts) {
        this.dashboardSavedQueryService = dashboardSavedQueryService;
        this.serviceToggles = serviceToggles;
        this.customCharts = customCharts;
    }

    @POST
    @Operation(operationId = "createDashboardSavedQuery", summary = "Save a chart query", description = "Saves the query of an Ollie chart so a dashboard widget can reference it by id.", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/internal/dashboard-saved-queries/{id}", schema = @Schema(implementation = String.class))}, content = @Content(schema = @Schema(implementation = DashboardSavedQuery.class))),
            @ApiResponse(responseCode = "422", description = "Unprocessable Content", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "501", description = "Ollie or Custom Charts is not enabled for this workspace")})
    @RateLimited
    public Response create(
            @RequestBody(content = @Content(schema = @Schema(implementation = DashboardSavedQuery.class))) @NotNull @Valid DashboardSavedQuery query,
            @Context UriInfo uriInfo) {
        requireEnabled();
        var saved = dashboardSavedQueryService.create(query);
        var uri = uriInfo.getAbsolutePathBuilder().path(saved.id().toString()).build();
        return Response.created(uri).entity(saved).build();
    }

    @GET
    @Path("/{id}")
    @Operation(operationId = "getDashboardSavedQuery", summary = "Get a chart query", description = "Gets a dashboard saved query by id.", responses = {
            @ApiResponse(responseCode = "200", description = "Dashboard saved query", content = @Content(schema = @Schema(implementation = DashboardSavedQuery.class))),
            @ApiResponse(responseCode = "404", description = "Not found"),
            @ApiResponse(responseCode = "501", description = "Ollie or Custom Charts is not enabled for this workspace")})
    public DashboardSavedQuery get(@PathParam("id") UUID id) {
        requireEnabled();
        return dashboardSavedQueryService.findById(id)
                .orElseThrow(
                        () -> new NotFoundException("Dashboard saved query '%s' not found".formatted(id)));
    }

    private void requireEnabled() {
        if (!customCharts.isEnabled(serviceToggles)) {
            throw new ServerErrorException(Response.Status.NOT_IMPLEMENTED);
        }
    }
}
