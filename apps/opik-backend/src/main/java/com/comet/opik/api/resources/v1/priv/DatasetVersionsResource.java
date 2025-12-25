package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.DatasetVersion;
import com.comet.opik.api.DatasetVersion.DatasetVersionPage;
import com.comet.opik.api.DatasetVersionDiff;
import com.comet.opik.api.DatasetVersionRestore;
import com.comet.opik.api.DatasetVersionTag;
import com.comet.opik.api.DatasetVersionUpdate;
import com.comet.opik.domain.DatasetVersionService;
import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import com.fasterxml.jackson.annotation.JsonView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

/**
 * Sub-resource for dataset version operations.
 * Handles all endpoints under /datasets/{id}/versions
 */
@Slf4j
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class DatasetVersionsResource {
    private final @NonNull UUID datasetId;
    private final @NonNull DatasetVersionService versionService;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull FeatureFlags featureFlags;

    @GET
    @Operation(operationId = "listDatasetVersions", summary = "List dataset versions", description = "Get paginated list of versions for a dataset, ordered by creation time (newest first)", responses = {
            @ApiResponse(responseCode = "200", description = "Dataset versions", content = @Content(schema = @Schema(implementation = DatasetVersionPage.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
    })
    @JsonView(DatasetVersion.View.Public.class)
    public Response listVersions(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size) {
        featureFlags.checkDatasetVersioningEnabled();

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Listing versions for dataset '{}', page '{}', size '{}' on workspace '{}'", datasetId, page, size,
                workspaceId);
        DatasetVersionPage versionPage = versionService.getVersions(datasetId, page, size);
        log.info("Found '{}' versions for dataset '{}' on workspace '{}'", versionPage.total(), datasetId,
                workspaceId);

        return Response.ok(versionPage).build();
    }

    @PATCH
    @Path("/hash/{versionHash}")
    @Operation(operationId = "updateDatasetVersion", summary = "Update dataset version", description = "Update a dataset version's change_description and/or add new tags", responses = {
            @ApiResponse(responseCode = "200", description = "Version updated successfully", content = @Content(schema = @Schema(implementation = DatasetVersion.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Not Found - Version not found", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "Conflict - Tag already exists", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class)))
    })
    @RateLimited
    @JsonView(DatasetVersion.View.Public.class)
    public Response updateVersion(
            @PathParam("versionHash") String versionHash,
            @RequestBody(content = @Content(schema = @Schema(implementation = DatasetVersionUpdate.class))) @Valid @NotNull DatasetVersionUpdate request) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Updating version '{}' for dataset '{}' on workspace '{}'", versionHash, datasetId, workspaceId);
        DatasetVersion version = versionService.updateVersion(datasetId, versionHash, request);
        log.info("Updated version '{}' for dataset '{}' on workspace '{}'", versionHash, datasetId, workspaceId);

        return Response.ok(version).build();
    }

    @POST
    @Path("/hash/{versionHash}/tags")
    @Operation(operationId = "createVersionTag", summary = "Create version tag", description = "Add a tag to a specific dataset version for easy reference (e.g., 'baseline', 'v1.0', 'production')", responses = {
            @ApiResponse(responseCode = "204", description = "Tag created successfully"),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Not Found - Version not found", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "Conflict - Tag already exists", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class)))
    })
    @RateLimited
    public Response createTag(
            @PathParam("versionHash") String versionHash,
            @RequestBody(content = @Content(schema = @Schema(implementation = DatasetVersionTag.class))) @Valid @NotNull DatasetVersionTag tag) {
        featureFlags.checkDatasetVersioningEnabled();

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Creating tag '{}' for version '{}' of dataset '{}' on workspace '{}'", tag.tag(), versionHash,
                datasetId, workspaceId);
        versionService.createTag(datasetId, versionHash, tag);
        log.info("Created tag '{}' for version '{}' of dataset '{}' on workspace '{}'", tag.tag(), versionHash,
                datasetId, workspaceId);

        return Response.noContent().build();
    }

    @DELETE
    @Path("/{versionHash}/tags/{tag}")
    @Operation(operationId = "deleteVersionTag", summary = "Delete version tag", description = "Remove a tag from a dataset version. The version itself is not deleted, only the tag reference.", responses = {
            @ApiResponse(responseCode = "204", description = "Tag deleted successfully"),
    })
    @RateLimited
    public Response deleteTag(
            @PathParam("versionHash") String versionHash,
            @PathParam("tag") String tag) {
        featureFlags.checkDatasetVersioningEnabled();

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Deleting tag '{}' for version '{}' from dataset '{}' on workspace '{}'", tag, versionHash, datasetId,
                workspaceId);
        versionService.deleteTag(datasetId, tag);
        log.info("Deleted tag '{}' for version '{}' from dataset '{}' on workspace '{}'", tag, versionHash, datasetId,
                workspaceId);

        return Response.noContent().build();
    }

    @GET
    @Path("/diff")
    @Operation(operationId = "compareDatasetVersions", summary = "Compare latest version with draft", description = "Compare the latest committed dataset version with the current draft state. This endpoint provides insights into changes made since the last version was committed. The comparison calculates additions, modifications, deletions, and unchanged items between the latest version snapshot and current draft.", responses = {
            @ApiResponse(responseCode = "200", description = "Diff computed successfully", content = @Content(schema = @Schema(implementation = DatasetVersionDiff.class))),
            @ApiResponse(responseCode = "404", description = "Version not found")})
    @RateLimited
    public Response compareVersions() {
        featureFlags.checkDatasetVersioningEnabled();

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Comparing latest version with draft for dataset='{}', workspace='{}'",
                datasetId, workspaceId);

        var diff = versionService.compareVersions(datasetId, DatasetVersionService.LATEST_TAG, null);

        log.info(
                "Computed diff for dataset='{}', from='latest', to='draft': stats='{}'", datasetId,
                diff.statistics());

        return Response.ok(diff).build();
    }

    @POST
    @Path("/restore")
    @Operation(operationId = "restoreDatasetVersion", summary = "Restore dataset to a previous version", description = "Restores the dataset to a previous version state by creating a new version with items copied from the specified version. If the version is already the latest, returns it as-is (no-op).", responses = {
            @ApiResponse(responseCode = "200", description = "Version restored successfully", content = @Content(schema = @Schema(implementation = DatasetVersion.class))),
            @ApiResponse(responseCode = "404", description = "Version not found", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class)))})
    @RateLimited
    @JsonView(DatasetVersion.View.Public.class)
    public Response restoreVersion(
            @RequestBody(content = @Content(schema = @Schema(implementation = DatasetVersionRestore.class))) @Valid @NotNull DatasetVersionRestore request) {
        featureFlags.checkDatasetVersioningEnabled();

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        log.info("Restoring dataset '{}' to version '{}' on workspace '{}'", datasetId, request.versionRef(),
                workspaceId);
        DatasetVersion version = versionService.restoreVersion(datasetId, request.versionRef())
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, userName))
                .block();
        log.info("Restored dataset '{}' to version '{}' on workspace '{}'", datasetId, request.versionRef(),
                workspaceId);

        return Response.ok(version).build();
    }
}
