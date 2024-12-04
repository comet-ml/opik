package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.CreatePromptVersion;
import com.comet.opik.api.Prompt;
import com.comet.opik.api.Prompt.PromptPage;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.PromptVersion.PromptVersionPage;
import com.comet.opik.api.PromptVersionRetrieve;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.PromptService;
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

import java.util.UUID;

@Path("/v1/private/prompts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Prompts", description = "Prompt resources")
public class PromptResource {

    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull PromptService promptService;

    @POST
    @Operation(operationId = "createPrompt", summary = "Create prompt", description = "Create prompt", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/prompts/{promptId}", schema = @Schema(implementation = String.class))}),
            @ApiResponse(responseCode = "422", description = "Unprocessable Content", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "Conflict", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),

    })
    @RateLimited
    public Response createPrompt(
            @RequestBody(content = @Content(schema = @Schema(implementation = Prompt.class))) @JsonView(Prompt.View.Write.class) @Valid Prompt prompt,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Creating prompt with name '{}', on workspace_id '{}'", prompt.name(), workspaceId);
        prompt = promptService.create(prompt);
        log.info("Prompt created with id '{}' name '{}', on workspace_id '{}'", prompt.id(), prompt.name(),
                workspaceId);

        var resourceUri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(prompt.id())).build();

        return Response.created(resourceUri).build();
    }

    @GET
    @Operation(operationId = "getPrompts", summary = "Get prompts", description = "Get prompts", responses = {
            @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = PromptPage.class))),
    })
    @JsonView({Prompt.View.Public.class})
    public Response getPrompts(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("name") String name) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting prompts by name '{}' on workspace_id '{}', page '{}', size '{}'", name, workspaceId, page,
                size);

        PromptPage promptPage = promptService.find(name, page, size);

        log.info("Got prompts by name '{}', count '{}' on workspace_id '{}', count '{}'", name, promptPage.size(),
                workspaceId, promptPage.size());

        return Response.ok(promptPage).build();
    }

    @GET
    @Path("{id}")
    @Operation(operationId = "getPromptById", summary = "Get prompt by id", description = "Get prompt by id", responses = {
            @ApiResponse(responseCode = "200", description = "Prompt resource", content = @Content(schema = @Schema(implementation = Prompt.class))),
            @ApiResponse(responseCode = "404", description = "Not Found", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
    })
    @JsonView({Prompt.View.Detail.class})
    public Response getPromptById(@PathParam("id") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting prompt by id '{}' on workspace_id '{}'", id, workspaceId);

        Prompt prompt = promptService.getById(id);

        log.info("Got prompt by id '{}' on workspace_id '{}'", id, workspaceId);

        return Response.ok(prompt).build();
    }

    @PUT
    @Path("{id}")
    @Operation(operationId = "updatePrompt", summary = "Update prompt", description = "Update prompt", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
            @ApiResponse(responseCode = "422", description = "Unprocessable Content", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Not Found", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "Conflict", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
    })
    @RateLimited
    public Response updatePrompt(
            @PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = Prompt.class))) @JsonView(Prompt.View.Updatable.class) @Valid Prompt prompt) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Updating prompt with id '{}' on workspace_id '{}'", id, workspaceId);
        promptService.update(id, prompt);
        log.info("Updated prompt with id '{}' on workspace_id '{}'", id, workspaceId);

        return Response.noContent().build();
    }

    @DELETE
    @Path("{id}")
    @Operation(operationId = "deletePrompt", summary = "Delete prompt", description = "Delete prompt", responses = {
            @ApiResponse(responseCode = "204", description = "No content")
    })
    public Response deletePrompt(@PathParam("id") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Deleting prompt by id '{}' on workspace_id '{}'", id, workspaceId);
        promptService.delete(id);
        log.info("Deleted prompt by id '{}' on workspace_id '{}'", id, workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/delete")
    @Operation(operationId = "deletePromptsBatch", summary = "Delete prompts", description = "Delete prompts batch", responses = {
            @ApiResponse(responseCode = "204", description = "No Content"),
    })
    public Response deletePromptsBatch(
            @NotNull @RequestBody(content = @Content(schema = @Schema(implementation = BatchDelete.class))) @Valid BatchDelete batchDelete) {
        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Deleting prompts by ids, count '{}', on workspace_id '{}'", batchDelete.ids().size(), workspaceId);
        promptService.delete(batchDelete.ids());
        log.info("Deleted prompts by ids, count '{}', on workspace_id '{}'", batchDelete.ids().size(), workspaceId);
        return Response.noContent().build();
    }

    @POST
    @Path("/versions")
    @Operation(operationId = "createPromptVersion", summary = "Create prompt version", description = "Create prompt version", responses = {
            @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = PromptVersion.class))),
            @ApiResponse(responseCode = "422", description = "Unprocessable Content", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "Conflict", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class)))
    })
    @RateLimited
    @JsonView({PromptVersion.View.Detail.class})
    public Response createPromptVersion(
            @RequestBody(content = @Content(schema = @Schema(implementation = CreatePromptVersion.class))) @JsonView({
                    PromptVersion.View.Detail.class}) @Valid CreatePromptVersion promptVersion) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Creating prompt version commit '{}' on workspace_id '{}'", promptVersion.version().commit(),
                workspaceId);

        var createdVersion = promptService.createPromptVersion(promptVersion);

        log.info("Created prompt version commit '{}'  with id '{}' on workspace_id '{}'",
                promptVersion.version().commit(), createdVersion.id(), workspaceId);

        return Response.ok(createdVersion).build();
    }

    @GET
    @Path("/{id}/versions")
    @Operation(operationId = "getPromptVersions", summary = "Get prompt versions", description = "Get prompt versions", responses = {
            @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = PromptVersionPage.class))),
    })
    @JsonView({PromptVersion.View.Public.class})
    public Response getPromptVersions(@PathParam("id") UUID id,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting prompt versions by id '{}' on workspace_id '{}', page '{}', size '{}'", id, workspaceId, page,
                size);

        PromptVersionPage promptVersionPage = promptService.getVersionsByPromptId(id, page, size);

        log.info("Got prompt versions by id '{}' on workspace_id '{}', count '{}'", id, workspaceId,
                promptVersionPage.size());

        return Response.ok(promptVersionPage).build();
    }

    @GET
    @Path("/versions/{versionId}")
    @Operation(operationId = "getPromptVersionById", summary = "Get prompt version by id", description = "Get prompt version by id", responses = {
            @ApiResponse(responseCode = "200", description = "Prompt version resource", content = @Content(schema = @Schema(implementation = PromptVersion.class))),
            @ApiResponse(responseCode = "404", description = "Not Found", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
    })
    @JsonView({PromptVersion.View.Detail.class})
    public Response getPromptVersionById(@PathParam("versionId") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting prompt version by id '{}' on workspace_id '{}'", id, workspaceId);

        PromptVersion promptVersion = promptService.getVersionById(id);

        log.info("Got prompt version by id '{}' on workspace_id '{}'", id, workspaceId);

        return Response.ok(promptVersion).build();
    }

    @POST
    @Path("/versions/retrieve")
    @Operation(operationId = "retrievePromptVersion", summary = "Retrieve prompt version", description = "Retrieve prompt version", responses = {
            @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = PromptVersion.class))),
            @ApiResponse(responseCode = "422", description = "Unprocessable Content", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Not Found", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
    })
    @JsonView({PromptVersion.View.Detail.class})
    public Response retrievePromptVersion(
            @RequestBody(content = @Content(schema = @Schema(implementation = PromptVersionRetrieve.class))) @Valid PromptVersionRetrieve request) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Retrieving prompt name '{}'  with commit '{}' on workspace_id '{}'", request.name(),
                request.commit(), workspaceId);

        PromptVersion promptVersion = promptService.retrievePromptVersion(request.name(), request.commit());

        log.info("Retrieved prompt name '{}'  with commit '{}' on workspace_id '{}'", request.name(),
                request.commit(), workspaceId);

        return Response.ok(promptVersion).build();
    }

}
