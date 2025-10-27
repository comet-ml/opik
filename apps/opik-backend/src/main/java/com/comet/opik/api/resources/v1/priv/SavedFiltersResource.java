package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.FilterType;
import com.comet.opik.api.SavedFilter;
import com.comet.opik.api.SavedFilter.SavedFilterPage;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.SavedFilterService;
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

@Path("/v1/private/projects/{project_id}/filters")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Saved Filters", description = "Saved filter management resources")
public class SavedFiltersResource {

    private static final String PAGE_SIZE = "10";
    private final @NonNull SavedFilterService savedFilterService;
    private final @NonNull Provider<RequestContext> requestContext;

    @GET
    @Operation(operationId = "findSavedFilters", summary = "Find saved filters", description = "Find saved filters for a project", responses = {
            @ApiResponse(responseCode = "200", description = "Saved filters list", content = @Content(schema = @Schema(implementation = SavedFilterPage.class)))
    })
    @JsonView({SavedFilter.View.Public.class})
    public Response find(
            @PathParam("project_id") UUID projectId,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue(PAGE_SIZE) int size,
            @QueryParam("name") String name,
            @QueryParam("type") FilterType type,
            @QueryParam("sorting") String sorting) {

        String workspaceId = requestContext.get().getWorkspaceId();

        // Parse sorting if provided
        List<SortingField> sortingFields = List.of();

        log.info("Finding saved filters for project '{}' with filters - name: '{}', type: '{}' on workspaceId '{}'",
                projectId, name, type, workspaceId);

        SavedFilterPage filterPage = savedFilterService.find(page - 1, size, projectId, name, type, sortingFields);

        log.info("Found {} saved filters for project '{}' on workspaceId '{}'", filterPage.size(), projectId,
                workspaceId);

        return Response.ok().entity(filterPage).build();
    }

    @GET
    @Path("/{id}")
    @Operation(operationId = "getSavedFilterById", summary = "Get saved filter by id", description = "Get saved filter by id", responses = {
            @ApiResponse(responseCode = "200", description = "Saved filter resource", content = @Content(schema = @Schema(implementation = SavedFilter.class)))
    })
    @JsonView({SavedFilter.View.Public.class})
    public Response getById(
            @PathParam("project_id") UUID projectId,
            @PathParam("id") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting saved filter '{}' for project '{}' on workspace_id '{}'", id, projectId, workspaceId);

        SavedFilter filter = savedFilterService.get(id);

        log.info("Got saved filter '{}' for project '{}' on workspace_id '{}'", id, projectId, workspaceId);

        return Response.ok().entity(filter).build();
    }

    @POST
    @Operation(operationId = "createSavedFilter", summary = "Create saved filter", description = "Create a new saved filter", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/projects/{projectId}/filters/{filterId}", schema = @Schema(implementation = String.class))}),
            @ApiResponse(responseCode = "422", description = "Unprocessable Content", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RateLimited
    public Response create(
            @PathParam("project_id") UUID projectId,
            @RequestBody(content = @Content(schema = @Schema(implementation = SavedFilter.class))) @JsonView(SavedFilter.View.Write.class) @Valid SavedFilter savedFilter,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Creating saved filter '{}' for project '{}' on workspace_id '{}'", savedFilter.name(), projectId,
                workspaceId);

        // Ensure project ID is set
        SavedFilter filterWithProject = savedFilter.toBuilder().projectId(projectId).build();
        var filterId = savedFilterService.create(filterWithProject).id();

        log.info("Created saved filter '{}', id '{}' for project '{}' on workspace_id '{}'", savedFilter.name(),
                filterId, projectId, workspaceId);

        var uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(filterId)).build();

        return Response.created(uri).build();
    }

    @PATCH
    @Path("/{id}")
    @Operation(operationId = "updateSavedFilter", summary = "Update saved filter by id", description = "Update saved filter by id", responses = {
            @ApiResponse(responseCode = "204", description = "No Content"),
            @ApiResponse(responseCode = "422", description = "Unprocessable Content", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RateLimited
    public Response update(
            @PathParam("project_id") UUID projectId,
            @PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = SavedFilter.class))) @JsonView(SavedFilter.View.Write.class) @Valid SavedFilter savedFilter) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Updating saved filter '{}' for project '{}' on workspaceId '{}'", id, projectId, workspaceId);
        savedFilterService.update(id, savedFilter);
        log.info("Updated saved filter '{}' for project '{}' on workspaceId '{}'", id, projectId, workspaceId);

        return Response.noContent().build();
    }

    @DELETE
    @Path("/{id}")
    @Operation(operationId = "deleteSavedFilterById", summary = "Delete saved filter by id", description = "Delete saved filter by id", responses = {
            @ApiResponse(responseCode = "204", description = "No Content")
    })
    public Response deleteById(
            @PathParam("project_id") UUID projectId,
            @PathParam("id") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Deleting saved filter '{}' for project '{}' on workspaceId '{}'", id, projectId, workspaceId);
        savedFilterService.delete(id);
        log.info("Deleted saved filter '{}' for project '{}' on workspaceId '{}'", id, projectId, workspaceId);

        return Response.noContent().build();
    }

    @DELETE
    @Path("/")
    @Operation(operationId = "deleteSavedFiltersBatch", summary = "Delete saved filters batch", description = "Delete saved filters batch", responses = {
            @ApiResponse(responseCode = "204", description = "No Content")
    })
    public Response deleteBatch(
            @PathParam("project_id") UUID projectId,
            @Valid @NotNull BatchDelete batchDelete) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Deleting saved filters batch, count '{}' for project '{}' on workspaceId '{}'",
                batchDelete.ids().size(), projectId, workspaceId);

        savedFilterService.delete(Set.copyOf(batchDelete.ids()));

        log.info("Deleted saved filters batch, count '{}' for project '{}' on workspaceId '{}'",
                batchDelete.ids().size(), projectId, workspaceId);

        return Response.noContent().build();
    }
}
