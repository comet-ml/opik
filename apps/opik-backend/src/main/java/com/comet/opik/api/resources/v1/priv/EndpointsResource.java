package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.Endpoint;
import com.comet.opik.api.Endpoint.EndpointPage;
import com.comet.opik.api.EndpointUpdate;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.sorting.SortingFactoryEndpoints;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.EndpointService;
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
import java.util.UUID;

@Path("/v1/private/endpoints")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Endpoints", description = "Agent endpoint configuration resources")
public class EndpointsResource {

    private static final String PAGE_SIZE = "10";
    private final @NonNull EndpointService endpointService;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull SortingFactoryEndpoints sortingFactory;

    @POST
    @Operation(operationId = "createEndpoint", summary = "Create endpoint", description = "Create a new agent endpoint", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/endpoints/{endpointId}", schema = @Schema(implementation = String.class))})
    })
    @RateLimited
    @JsonView({Endpoint.View.Public.class})
    public Response create(
            @RequestBody(content = @Content(schema = @Schema(implementation = Endpoint.class))) @JsonView(Endpoint.View.Write.class) @Valid Endpoint endpoint,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Creating endpoint with name '{}', project '{}' on workspace '{}'", endpoint.name(),
                endpoint.projectId(), workspaceId);

        var created = endpointService.create(endpoint);

        log.info("Created endpoint with name '{}', id '{}' on workspace '{}'", endpoint.name(), created.id(),
                workspaceId);

        var uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(created.id())).build();

        return Response.created(uri).build();
    }

    @GET
    @Path("{id}")
    @Operation(operationId = "getEndpointById", summary = "Get endpoint by id", description = "Get endpoint by id", responses = {
            @ApiResponse(responseCode = "200", description = "Endpoint resource", content = @Content(schema = @Schema(implementation = Endpoint.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @JsonView({Endpoint.View.Public.class})
    public Response getById(@PathParam("id") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting endpoint by id '{}' on workspace '{}'", id, workspaceId);

        Endpoint endpoint = endpointService.get(id);

        log.info("Got endpoint by id '{}' on workspace '{}'", id, workspaceId);

        return Response.ok().entity(endpoint).build();
    }

    @GET
    @Operation(operationId = "findEndpoints", summary = "Find endpoints", description = "Find endpoints", responses = {
            @ApiResponse(responseCode = "200", description = "Endpoint page", content = @Content(schema = @Schema(implementation = EndpointPage.class)))
    })
    @JsonView({Endpoint.View.Public.class})
    public Response find(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue(PAGE_SIZE) int size,
            @QueryParam("project_id") @NotNull UUID projectId,
            @QueryParam("name") String name,
            @QueryParam("sorting") String sorting) {

        String workspaceId = requestContext.get().getWorkspaceId();

        List<SortingField> sortingFields = sortingFactory.newSorting(sorting);

        log.info("Find endpoints by projectId '{}' on workspace '{}'", projectId, workspaceId);
        EndpointPage endpointPage = endpointService.find(page, size, projectId, name, sortingFields);
        log.info("Found endpoints, count '{}' on workspace '{}'", endpointPage.total(), workspaceId);

        return Response.ok().entity(endpointPage).build();
    }

    @PATCH
    @Path("{id}")
    @Operation(operationId = "updateEndpoint", summary = "Update endpoint by id", description = "Update endpoint by id", responses = {
            @ApiResponse(responseCode = "204", description = "No Content"),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RateLimited
    public Response update(
            @PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = EndpointUpdate.class))) @Valid EndpointUpdate endpointUpdate) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Updating endpoint with id '{}' on workspace '{}'", id, workspaceId);
        endpointService.update(id, endpointUpdate);
        log.info("Updated endpoint with id '{}' on workspace '{}'", id, workspaceId);

        return Response.noContent().build();
    }

    @DELETE
    @Path("{id}")
    @Operation(operationId = "deleteEndpointById", summary = "Delete endpoint by id", description = "Delete endpoint by id", responses = {
            @ApiResponse(responseCode = "204", description = "No Content"),
            @ApiResponse(responseCode = "404", description = "Not found")
    })
    public Response deleteById(@PathParam("id") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Deleting endpoint by id '{}' on workspace '{}'", id, workspaceId);
        endpointService.delete(id);
        log.info("Deleted endpoint by id '{}' on workspace '{}'", id, workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/delete")
    @Operation(operationId = "deleteEndpointsBatch", summary = "Delete endpoints batch", description = "Delete endpoints batch", responses = {
            @ApiResponse(responseCode = "204", description = "No Content")
    })
    public Response deleteEndpointsBatch(
            @NotNull @RequestBody(content = @Content(schema = @Schema(implementation = BatchDelete.class))) @Valid BatchDelete batchDelete) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Deleting endpoints by ids, count '{}' on workspace '{}'", batchDelete.ids().size(), workspaceId);
        endpointService.delete(batchDelete.ids());
        log.info("Deleted endpoints by ids, count '{}' on workspace '{}'", batchDelete.ids().size(), workspaceId);

        return Response.noContent().build();
    }
}
