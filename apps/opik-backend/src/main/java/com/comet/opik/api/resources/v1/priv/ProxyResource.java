package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.api.ProviderApiKeyUpdate;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.ProxyService;
import com.comet.opik.infrastructure.auth.RequestContext;
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
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Path("/v1/private/proxy")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Proxy", description = "LLM Provider Proxy")
public class ProxyResource {

    private final @NonNull ProxyService proxyService;
    private final @NonNull Provider<RequestContext> requestContext;

    @GET
    @Path("/api_key/{id}")
    @Operation(operationId = "getProviderApiKeyById", summary = "Get Provider's ApiKey by id", description = "Get Provider's ApiKey by id", responses = {
            @ApiResponse(responseCode = "200", description = "ProviderApiKey resource", content = @Content(schema = @Schema(implementation = ProviderApiKey.class)))})
    @JsonView({ProviderApiKey.View.Public.class})
    public Response getById(@PathParam("id") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting Provider's ApiKey by id '{}' on workspace_id '{}'", id, workspaceId);

        ProviderApiKey providerApiKey = proxyService.get(id);

        log.info("Got Provider's ApiKey by id '{}' on workspace_id '{}'", id, workspaceId);

        return Response.ok().entity(providerApiKey).build();
    }

    @POST
    @Path("/api_key")
    @Operation(operationId = "storeApiKey", summary = "Store Provider's ApiKey", description = "Store Provider's ApiKey", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/proxy/api_key/{apiKeyId}", schema = @Schema(implementation = String.class))}),
            @ApiResponse(responseCode = "401", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "403", description = "Access forbidden", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response saveApiKey(
            @RequestBody(content = @Content(schema = @Schema(implementation = ProviderApiKey.class))) @JsonView(ProviderApiKey.View.Write.class) @Valid ProviderApiKey providerApiKey,
            @Context UriInfo uriInfo) {
        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Save api key for provider '{}', on workspace_id '{}'", providerApiKey.provider(), workspaceId);
        var providerApiKeyId = proxyService.saveApiKey(providerApiKey).id();
        log.info("Saved api key for provider '{}', on workspace_id '{}'", providerApiKey.provider(), workspaceId);

        var uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(providerApiKeyId)).build();

        return Response.created(uri).build();
    }

    @PATCH
    @Path("/api_key/{id}")
    @Operation(operationId = "storeApiKey", summary = "Store Provider's ApiKey", description = "Store Provider's ApiKey", responses = {
            @ApiResponse(responseCode = "204", description = "No Content"),
            @ApiResponse(responseCode = "401", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "403", description = "Access forbidden", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response updateApiKey(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = ProviderApiKeyUpdate.class))) @Valid ProviderApiKeyUpdate providerApiKeyUpdate) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Updating api key for provider with id '{}' on workspaceId '{}'", id, workspaceId);
        proxyService.updateApiKey(id, providerApiKeyUpdate);
        log.info("Updated api key for provider with id '{}' on workspaceId '{}'", id, workspaceId);

        return Response.noContent().build();
    }
}
