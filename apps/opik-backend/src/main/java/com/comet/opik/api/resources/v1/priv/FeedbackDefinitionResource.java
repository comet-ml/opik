package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.FeedbackDefinition;
import com.comet.opik.api.FeedbackDefinitionCriteria;
import com.comet.opik.domain.FeedbackDefinitionService;
import com.fasterxml.jackson.annotation.JsonView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
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

import static com.comet.opik.domain.FeedbackDefinitionModel.FeedbackType;

@Path("/v1/private/feedback-definitions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Feedback-definitions", description = "Feedback definitions related resources")
public class FeedbackDefinitionResource {

    private final @NonNull FeedbackDefinitionService service;

    @GET
    @Operation(operationId = "findFeedbackDefinitions", summary = "Find Feedback definitions", description = "Find Feedback definitions", responses = {
            @ApiResponse(responseCode = "200", description = "Feedback definitions resource", content = @Content(schema = @Schema(implementation = FeedbackDefinition.FeedbackDefinitionPage.class)))
    })
    @JsonView({FeedbackDefinition.View.Public.class})
    public Response find(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("name") String name,
            @QueryParam("type") FeedbackType type) {

        var criteria = FeedbackDefinitionCriteria.builder()
                .name(name)
                .type(type)
                .build();

        return Response.ok()
                .entity(service.find(page, size, criteria))
                .build();
    }

    @GET
    @Path("{id}")
    @Operation(operationId = "getFeedbackDefinitionById", summary = "Get feedback definition by id", description = "Get feedback definition by id", responses = {
            @ApiResponse(responseCode = "200", description = "Feedback definition resource", content = @Content(schema = @Schema(implementation = FeedbackDefinition.class)))
    })
    @JsonView({FeedbackDefinition.View.Public.class})
    public Response getById(@PathParam("id") @NotNull UUID id) {
        return Response.ok().entity(service.get(id)).build();
    }

    @POST
    @Operation(operationId = "createFeedbackDefinition", summary = "Create feedback definition", description = "Get feedback definition", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/feedback-definitions/{feedbackId}", schema = @Schema(implementation = String.class))})
    })
    public Response create(
            @RequestBody(content = @Content(schema = @Schema(implementation = FeedbackDefinition.class))) @JsonView({
                    FeedbackDefinition.View.Create.class}) @NotNull @Valid FeedbackDefinition<?> feedbackDefinition,
            @Context UriInfo uriInfo) {

        final var createdFeedbackDefinitions = service.create(feedbackDefinition);
        final var uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(createdFeedbackDefinitions.getId()))
                .build();

        return Response.created(uri).build();
    }

    @PUT
    @Path("{id}")
    @Operation(operationId = "updateFeedbackDefinition", summary = "Update feedback definition by id", description = "Update feedback definition by id", responses = {
            @ApiResponse(responseCode = "204", description = "No Content")
    })
    public Response update(final @PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = FeedbackDefinition.class))) @JsonView({
                    FeedbackDefinition.View.Update.class}) @NotNull @Valid FeedbackDefinition<?> feedbackDefinition) {

        service.update(id, feedbackDefinition);
        return Response.noContent().build();
    }

    @DELETE
    @Path("{id}")
    @Operation(operationId = "deleteFeedbackDefinitionById", summary = "Delete feedback definition by id", description = "Delete feedback definition by id", responses = {
            @ApiResponse(responseCode = "204", description = "No Content")
    })
    public Response deleteById(@PathParam("id") UUID id) {

        var workspace = service.getWorkspaceId(id);

        if (workspace == null) {
            return Response.noContent().build();
        }

        service.delete(id);
        return Response.noContent().build();
    }

}
