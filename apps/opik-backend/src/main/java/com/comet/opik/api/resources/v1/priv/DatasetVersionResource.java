package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.DatasetVersion;
import com.comet.opik.api.DatasetVersion.DatasetVersionPage;
import com.comet.opik.api.DatasetVersionCreate;
import com.comet.opik.api.DatasetVersionTag;
import com.comet.opik.domain.DatasetVersionService;
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
import java.util.UUID;

@Path("/v1/private/datasets")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Dataset Versions", description = "Dataset version management resources")
public class DatasetVersionResource {

    private final @NonNull DatasetVersionService versionService;
    private final @NonNull Provider<RequestContext> requestContext;

    @POST
    @Path("/{id}/versions")
    @Operation(operationId = "createDatasetVersion", summary = "Create dataset version", description = "Create a new immutable version of the dataset by snapshotting the current state", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/datasets/{datasetId}/versions", schema = @Schema(implementation = String.class))}),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Not Found", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "Conflict - Tag already exists", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class)))
    })
    @RateLimited
    @JsonView(DatasetVersion.View.Public.class)
    public Response createVersion(
            @PathParam("id") UUID datasetId,
            @RequestBody(content = @Content(schema = @Schema(implementation = DatasetVersionCreate.class))) @Valid @NotNull DatasetVersionCreate request,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Creating version for dataset '{}' on workspace '{}'", datasetId, workspaceId);
        DatasetVersion version = versionService.commitVersion(datasetId, request);
        log.info("Created version '{}' for dataset '{}' on workspace '{}'", version.id(), datasetId, workspaceId);

        URI uri = uriInfo.getAbsolutePathBuilder()
                .build();

        return Response.created(uri)
                .entity(version)
                .build();
    }

    @GET
    @Path("/{id}/versions")
    @Operation(operationId = "listDatasetVersions", summary = "List dataset versions", description = "Get paginated list of versions for a dataset, ordered by creation time (newest first)", responses = {
            @ApiResponse(responseCode = "200", description = "Dataset versions", content = @Content(schema = @Schema(implementation = DatasetVersionPage.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Not Found", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class)))
    })
    @JsonView(DatasetVersion.View.Public.class)
    public Response listVersions(
            @PathParam("id") UUID datasetId,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Listing versions for dataset '{}', page '{}', size '{}' on workspace '{}'", datasetId, page, size,
                workspaceId);
        DatasetVersionPage versionPage = versionService.getVersions(datasetId, page, size);
        log.info("Found '{}' versions for dataset '{}' on workspace '{}'", versionPage.total(), datasetId,
                workspaceId);

        return Response.ok(versionPage).build();
    }

    @POST
    @Path("/{id}/versions/{versionHash}/tags")
    @Operation(operationId = "createVersionTag", summary = "Create version tag", description = "Add a tag to a specific dataset version for easy reference (e.g., 'baseline', 'v1.0', 'production')", responses = {
            @ApiResponse(responseCode = "204", description = "Tag created successfully"),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Not Found - Version not found", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "Conflict - Tag already exists", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class)))
    })
    @RateLimited
    public Response createTag(
            @PathParam("id") UUID datasetId,
            @PathParam("versionHash") String versionHash,
            @RequestBody(content = @Content(schema = @Schema(implementation = DatasetVersionTag.class))) @Valid @NotNull DatasetVersionTag tag) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Creating tag '{}' for version '{}' of dataset '{}' on workspace '{}'", tag.tag(), versionHash,
                datasetId, workspaceId);
        versionService.createTag(datasetId, versionHash, tag);
        log.info("Created tag '{}' for version '{}' of dataset '{}' on workspace '{}'", tag.tag(), versionHash,
                datasetId, workspaceId);

        return Response.noContent().build();
    }

    @DELETE
    @Path("/{id}/tags/{tag}")
    @Operation(operationId = "deleteVersionTag", summary = "Delete version tag", description = "Remove a tag from a dataset version. The version itself is not deleted, only the tag reference.", responses = {
            @ApiResponse(responseCode = "204", description = "Tag deleted successfully"),
            @ApiResponse(responseCode = "404", description = "Not Found - Tag not found", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class)))
    })
    @RateLimited
    public Response deleteTag(
            @PathParam("id") UUID datasetId,
            @PathParam("tag") String tag) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Deleting tag '{}' from dataset '{}' on workspace '{}'", tag, datasetId, workspaceId);
        versionService.deleteTag(datasetId, tag);
        log.info("Deleted tag '{}' from dataset '{}' on workspace '{}'", tag, datasetId, workspaceId);

        return Response.noContent().build();
    }

}
