package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.DashboardPanel;
import com.comet.opik.api.ReusablePanelTemplate;
import com.comet.opik.api.ReusablePanelTemplateUpdate;
import com.comet.opik.domain.ReusablePanelTemplateService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import com.fasterxml.jackson.annotation.JsonView;
import io.dropwizard.jersey.errors.ErrorMessage;
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

@Path("/v1/private/panel-templates")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Panel Templates", description = "Reusable panel template resources")
public class ReusablePanelTemplateResource {

    private final @NonNull ReusablePanelTemplateService service;
    private final @NonNull Provider<RequestContext> requestContext;

    @GET
    @Operation(operationId = "findReusablePanelTemplates", summary = "Find reusable panel templates", description = "Find reusable panel templates", responses = {
            @ApiResponse(responseCode = "200", description = "Panel templates list", content = @Content(schema = @Schema(implementation = ReusablePanelTemplate.class)))
    })
    @JsonView(ReusablePanelTemplate.View.Public.class)
    public Response findPanelTemplates(@QueryParam("type") String type) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Finding panel templates on workspaceId '{}' with type filter '{}'", workspaceId, type);

        List<ReusablePanelTemplate> templates;
        if (type != null && !type.isEmpty()) {
            try {
                DashboardPanel.PanelType panelType = DashboardPanel.PanelType.valueOf(type.toUpperCase());
                templates = service.findByType(panelType);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid panel type provided: {}", type);
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(new ErrorMessage(400, "Invalid panel type: " + type))
                        .build();
            }
        } else {
            templates = service.findAll();
        }

        log.info("Found {} panel templates on workspaceId '{}'", templates.size(), workspaceId);

        return Response.ok().entity(templates).build();
    }

    @GET
    @Path("/{id}")
    @Operation(operationId = "getReusablePanelTemplateById", summary = "Get reusable panel template by id", description = "Get reusable panel template by id", responses = {
            @ApiResponse(responseCode = "200", description = "Panel template resource", content = @Content(schema = @Schema(implementation = ReusablePanelTemplate.class)))
    })
    @JsonView(ReusablePanelTemplate.View.Public.class)
    public Response getPanelTemplateById(@PathParam("id") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Finding panel template by id '{}' on workspaceId '{}'", id, workspaceId);
        ReusablePanelTemplate template = service.findById(id);
        log.info("Found panel template by id '{}' on workspaceId '{}'", id, workspaceId);

        return Response.ok().entity(template).build();
    }

    @POST
    @Operation(operationId = "createReusablePanelTemplate", summary = "Create reusable panel template", description = "Create reusable panel template", responses = {
            @ApiResponse(responseCode = "201", description = "Created", content = @Content(schema = @Schema(implementation = ReusablePanelTemplate.class)), headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/api/v1/private/panel-templates/{id}", schema = @Schema(implementation = String.class))
            })
    })
    @RateLimited
    @JsonView(ReusablePanelTemplate.View.Public.class)
    public Response createPanelTemplate(
            @RequestBody(content = @Content(schema = @Schema(implementation = ReusablePanelTemplate.class))) @JsonView(ReusablePanelTemplate.View.Write.class) @NotNull @Valid ReusablePanelTemplate template,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Creating panel template with name '{}', on workspace_id '{}'", template.name(), workspaceId);
        ReusablePanelTemplate savedTemplate = service.create(template);
        log.info("Created panel template with name '{}', id '{}', on workspace_id '{}'", savedTemplate.name(),
                savedTemplate.id(), workspaceId);

        URI uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(savedTemplate.id().toString())).build();
        return Response.created(uri).entity(savedTemplate).build();
    }

    @PUT
    @Path("/{id}")
    @Operation(operationId = "updateReusablePanelTemplate", summary = "Update reusable panel template by id", description = "Update reusable panel template by id", responses = {
            @ApiResponse(responseCode = "200", description = "Updated", content = @Content(schema = @Schema(implementation = ReusablePanelTemplate.class)))
    })
    @RateLimited
    @JsonView(ReusablePanelTemplate.View.Public.class)
    public Response updatePanelTemplate(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = ReusablePanelTemplateUpdate.class))) @NotNull @Valid ReusablePanelTemplateUpdate templateUpdate) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Updating panel template by id '{}' on workspace_id '{}'", id, workspaceId);
        ReusablePanelTemplate updatedTemplate = service.update(id, templateUpdate);
        log.info("Updated panel template by id '{}' on workspace_id '{}'", id, workspaceId);

        return Response.ok().entity(updatedTemplate).build();
    }

    @DELETE
    @Path("/{id}")
    @Operation(operationId = "deleteReusablePanelTemplate", summary = "Delete reusable panel template by id", description = "Delete reusable panel template by id", responses = {
            @ApiResponse(responseCode = "204", description = "No content")
    })
    public Response deletePanelTemplate(@PathParam("id") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Deleting panel template by id '{}' on workspace_id '{}'", id, workspaceId);
        service.delete(id);
        log.info("Deleted panel template by id '{}' on workspace_id '{}'", id, workspaceId);
        return Response.noContent().build();
    }

    @DELETE
    @Operation(operationId = "deleteReusablePanelTemplates", summary = "Delete reusable panel templates", description = "Delete reusable panel templates", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
            @ApiResponse(responseCode = "400", description = "Bad request", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response deletePanelTemplates(
            @RequestBody(content = @Content(schema = @Schema(implementation = BatchDelete.class))) @Valid BatchDelete batchDelete) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Deleting panel templates by ids '{}' on workspace_id '{}'", batchDelete.ids(), workspaceId);
        service.delete(batchDelete.ids());
        log.info("Deleted panel templates by ids '{}' on workspace_id '{}'", batchDelete.ids(), workspaceId);

        return Response.noContent().build();
    }
}