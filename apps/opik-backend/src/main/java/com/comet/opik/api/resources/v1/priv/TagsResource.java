package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.Tag;
import com.comet.opik.api.TagCreate;
import com.comet.opik.api.TagUpdate;
import com.comet.opik.domain.TagService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import com.comet.opik.utils.AsyncUtils;
import com.fasterxml.jackson.annotation.JsonView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Provider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
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

import static com.comet.opik.utils.AsyncUtils.setRequestContext;

@Path("/v1/private/tags")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @jakarta.inject.Inject)
@io.swagger.v3.oas.annotations.tags.Tag(name = "Tags", description = "Tag management resources")
public class TagsResource {

    private final @NonNull TagService tagService;
    private final @NonNull Provider<RequestContext> requestContext;

    @GET
    @Operation(operationId = "getTags", summary = "Get all tags for the workspace", description = "Retrieve all tags for the current workspace", responses = {
            @ApiResponse(responseCode = "200", description = "List of tags", content = @Content(schema = @Schema(implementation = Tag.class, type = "array")))})
    @RateLimited
    public Response getTags() {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting tags for workspaceId '{}'", workspaceId);

        List<Tag> tags = tagService.getTagsByWorkspaceId(workspaceId)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .retryWhen(AsyncUtils.handleConnectionError())
                .block();

        log.info("Retrieved {} tags for workspaceId '{}'", tags.size(), workspaceId);

        return Response.ok(tags).build();
    }

    @GET
    @Path("/search")
    @Operation(operationId = "searchTags", summary = "Search tags by name", description = "Search tags by name for the current workspace", responses = {
            @ApiResponse(responseCode = "200", description = "List of matching tags", content = @Content(schema = @Schema(implementation = Tag.class, type = "array")))})
    @RateLimited
    public Response searchTags(@QueryParam("q") @NotNull String searchTerm) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Searching tags with term '{}' for workspaceId '{}'", searchTerm, workspaceId);

        List<Tag> tags = tagService.searchTagsByWorkspaceId(workspaceId, searchTerm)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .retryWhen(AsyncUtils.handleConnectionError())
                .block();

        log.info("Found {} matching tags for workspaceId '{}'", tags.size(), workspaceId);

        return Response.ok(tags).build();
    }

    @GET
    @Path("/{id}")
    @Operation(operationId = "getTagById", summary = "Get tag by ID", description = "Retrieve a specific tag by ID", responses = {
            @ApiResponse(responseCode = "200", description = "Tag found", content = @Content(schema = @Schema(implementation = Tag.class))),
            @ApiResponse(responseCode = "404", description = "Tag not found")})
    @RateLimited
    public Response getTagById(@PathParam("id") @NotNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting tag with id '{}' for workspaceId '{}'", id, workspaceId);

        var tag = tagService.getTagByIdAndWorkspaceId(id, workspaceId)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .retryWhen(AsyncUtils.handleConnectionError())
                .block();

        if (tag.isEmpty()) {
            log.warn("Tag with id '{}' not found for workspaceId '{}'", id, workspaceId);
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        log.info("Retrieved tag with id '{}' for workspaceId '{}'", id, workspaceId);

        return Response.ok(tag.get()).build();
    }

    @POST
    @Operation(operationId = "createTag", summary = "Create a new tag", description = "Create a new tag for the current workspace", responses = {
            @ApiResponse(responseCode = "201", description = "Tag created", content = @Content(schema = @Schema(implementation = Tag.class))),
            @ApiResponse(responseCode = "409", description = "Tag with same name already exists")})
    @RateLimited
    public Response createTag(
            @RequestBody(content = @Content(schema = @Schema(implementation = TagCreate.class))) @NotNull @Valid @JsonView(Tag.View.Write.class) TagCreate tagCreate) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Creating tag with name '{}' for workspaceId '{}'", tagCreate.name(), workspaceId);

        Tag createdTag = tagService.createTag(tagCreate, workspaceId)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .retryWhen(AsyncUtils.handleConnectionError())
                .block();

        log.info("Created tag with id '{}' and name '{}' for workspaceId '{}'", createdTag.id(), createdTag.name(),
                workspaceId);

        return Response.status(Response.Status.CREATED).entity(createdTag).build();
    }

    @PUT
    @Path("/{id}")
    @Operation(operationId = "updateTag", summary = "Update an existing tag", description = "Update an existing tag by ID", responses = {
            @ApiResponse(responseCode = "200", description = "Tag updated", content = @Content(schema = @Schema(implementation = Tag.class))),
            @ApiResponse(responseCode = "404", description = "Tag not found"),
            @ApiResponse(responseCode = "409", description = "Tag with same name already exists")})
    @RateLimited
    public Response updateTag(@PathParam("id") @NotNull UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = TagUpdate.class))) @NotNull @Valid @JsonView(Tag.View.Updatable.class) TagUpdate tagUpdate) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Updating tag with id '{}' and name '{}' for workspaceId '{}'", id, tagUpdate.name(), workspaceId);

        Tag updatedTag = tagService.updateTag(id, tagUpdate, workspaceId)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .retryWhen(AsyncUtils.handleConnectionError())
                .block();

        log.info("Updated tag with id '{}' and name '{}' for workspaceId '{}'", id, updatedTag.name(), workspaceId);

        return Response.ok(updatedTag).build();
    }

    @DELETE
    @Path("/{id}")
    @Operation(operationId = "deleteTag", summary = "Delete a tag", description = "Delete a tag by ID", responses = {
            @ApiResponse(responseCode = "204", description = "Tag deleted"),
            @ApiResponse(responseCode = "404", description = "Tag not found")})
    @RateLimited
    public Response deleteTag(@PathParam("id") @NotNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Deleting tag with id '{}' for workspaceId '{}'", id, workspaceId);

        tagService.deleteTag(id, workspaceId)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .retryWhen(AsyncUtils.handleConnectionError())
                .block();

        log.info("Deleted tag with id '{}' for workspaceId '{}'", id, workspaceId);

        return Response.noContent().build();
    }
}