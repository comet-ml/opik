package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.Alert;
import com.comet.opik.api.AlertCreateRequest;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.AlertService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import com.fasterxml.jackson.annotation.JsonView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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

import java.net.URI;
import java.util.List;
import java.util.UUID;

@Path("/v1/private/alerts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Alerts", description = "Alert resources")
public class AlertsResource {

    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull AlertService alertService;

    @GET
    @Operation(operationId = "getAlerts", summary = "Get alerts", description = "Get alerts", responses = {
            @ApiResponse(responseCode = "200", description = "Alerts resource", content = @Content(schema = @Schema(implementation = Alert.AlertPage.class)))
    })
    @JsonView({Alert.View.Public.class})
    public Response getAlerts(
            @Parameter(description = "Page number") @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @Parameter(description = "Page size") @QueryParam("size") @Min(1) @Max(100) @DefaultValue("25") int size,
            @Parameter(description = "Alert name filter") @QueryParam("name") String name,
            @Parameter(description = "Condition type filter") @QueryParam("condition_type") String conditionType,
            @Parameter(description = "Project ID filter") @QueryParam("project_id") UUID projectId,
            @Parameter(description = "Sorting criteria") @QueryParam("sorting") String sorting) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting alerts on workspace_id '{}'", workspaceId);

        List<Alert> alerts = alertService.findAlerts(page, size, name, conditionType, projectId, sorting);
        long totalItems = alertService.count(name, conditionType, projectId);

        var alertPage = new Alert.AlertPage(page, size, totalItems, alerts,
                List.of("name", "created_at", "condition_type"));

        log.info("Got '{}' alerts on workspace_id '{}'", alerts.size(), workspaceId);

        return Response.ok(alertPage).build();
    }

    @GET
    @Path("{id}")
    @Operation(operationId = "getAlertById", summary = "Get alert by id", description = "Get alert by id", responses = {
            @ApiResponse(responseCode = "200", description = "Alert resource", content = @Content(schema = @Schema(implementation = Alert.class))),
            @ApiResponse(responseCode = "404", description = "Alert not found")
    })
    @JsonView({Alert.View.Public.class})
    public Response getById(@PathParam("id") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting alert by id '{}' on workspace_id '{}'", id, workspaceId);

        Alert alert = alertService.getById(id)
                .orElseThrow(() -> new jakarta.ws.rs.NotFoundException("Alert not found"));

        log.info("Got alert by id '{}' on workspace_id '{}'", id, workspaceId);

        return Response.ok().entity(alert).build();
    }

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
            @RequestBody(content = @Content(schema = @Schema(implementation = AlertCreateRequest.class))) @JsonView(AlertCreateRequest.View.Write.class) @Valid @NotNull AlertCreateRequest alertRequest,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Creating alert with name '{}', on workspace_id '{}'", alertRequest.name(), workspaceId);

        Alert alert = alertRequest.toAlert();
        Alert savedAlert = alertService.create(alert);

        log.info("Created alert with name '{}', id '{}', on workspace_id '{}'",
                savedAlert.name(), savedAlert.id(), workspaceId);

        URI uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(savedAlert.id().toString())).build();
        return Response.created(uri).build();
    }

    @PUT
    @Path("{id}")
    @Operation(operationId = "updateAlert", summary = "Update alert", description = "Update alert", responses = {
            @ApiResponse(responseCode = "204", description = "No Content"),
            @ApiResponse(responseCode = "404", description = "Alert not found"),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RateLimited
    public Response updateAlert(
            @PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = AlertCreateRequest.class))) @JsonView(AlertCreateRequest.View.Write.class) @Valid @NotNull AlertCreateRequest alertRequest) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Updating alert with id '{}' on workspace_id '{}'", id, workspaceId);

        Alert alert = alertRequest.toAlert();
        alertService.update(id, alert);

        log.info("Updated alert with id '{}' on workspace_id '{}'", id, workspaceId);

        return Response.noContent().build();
    }

    @DELETE
    @Path("{id}")
    @Operation(operationId = "deleteAlert", summary = "Delete alert", description = "Delete alert", responses = {
            @ApiResponse(responseCode = "204", description = "No Content"),
            @ApiResponse(responseCode = "404", description = "Alert not found")
    })
    @RateLimited
    public Response deleteAlert(@PathParam("id") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Deleting alert with id '{}' on workspace_id '{}'", id, workspaceId);

        alertService.delete(id);

        log.info("Deleted alert with id '{}' on workspace_id '{}'", id, workspaceId);

        return Response.noContent().build();
    }

    @GET
    @Path("project/{projectId}")
    @Operation(operationId = "getAlertsByProject", summary = "Get alerts by project", description = "Get alerts by project", responses = {
            @ApiResponse(responseCode = "200", description = "Alerts for project", content = @Content(schema = @Schema(implementation = List.class)))
    })
    @JsonView({Alert.View.Public.class})
    public Response getAlertsByProject(@PathParam("projectId") UUID projectId) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting alerts for project '{}' on workspace_id '{}'", projectId, workspaceId);

        List<Alert> alerts = alertService.findByProjectId(projectId);

        log.info("Got '{}' alerts for project '{}' on workspace_id '{}'", alerts.size(), projectId, workspaceId);

        return Response.ok(alerts).build();
    }
}
