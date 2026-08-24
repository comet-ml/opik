package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.api.ProviderApiKeyUpdate;
import com.comet.opik.api.ProviderAuthCheck;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.LlmProviderApiKeyService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.auth.RequiredPermissions;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
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
import jakarta.validation.constraints.NotNull;
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

import static com.comet.opik.infrastructure.EncryptionUtils.decrypt;
import static com.comet.opik.infrastructure.EncryptionUtils.maskApiKey;

@Path("/v1/private/llm-provider-key")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "LlmProviderKey", description = "LLM Provider Key")
public class LlmProviderApiKeyResource {

    private final @NonNull LlmProviderApiKeyService llmProviderApiKeyService;
    private final @NonNull Provider<RequestContext> requestContext;

    @GET
    @Operation(operationId = "findLlmProviderKeys", summary = "Find LLM Provider's ApiKeys", description = "Find LLM Provider's ApiKeys", responses = {
            @ApiResponse(responseCode = "200", description = "LLMProviderApiKey resource", content = @Content(schema = @Schema(implementation = ProviderApiKey.ProviderApiKeyPage.class)))
    })
    @JsonView({ProviderApiKey.View.Public.class})
    public Response find() {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Find LLM Provider's ApiKeys for workspaceId '{}'", workspaceId);
        ProviderApiKey.ProviderApiKeyPage providerApiKeyPage = llmProviderApiKeyService.find(workspaceId);
        log.info("Found LLM Provider's ApiKeys for workspaceId '{}'", workspaceId);

        var maskedContent = providerApiKeyPage.content().stream()
                .map(providerApiKey -> providerApiKey.toBuilder()
                        .apiKey(providerApiKey.apiKey() != null
                                ? maskApiKey(decrypt(providerApiKey.apiKey()))
                                : "null")
                        .authConfig(providerApiKey.authConfig() != null
                                ? providerApiKey.authConfig().mask()
                                : null)
                        .build())
                .toList();

        return Response.ok().entity(
                providerApiKeyPage.toBuilder()
                        .content(maskedContent)
                        .size(maskedContent.size())
                        .total(maskedContent.size())
                        .build())
                .build();
    }

    @GET
    @Path("{id}")
    @Operation(operationId = "getLlmProviderApiKeyById", summary = "Get LLM Provider's ApiKey by id", description = "Get LLM Provider's ApiKey by id", responses = {
            @ApiResponse(responseCode = "200", description = "LLMProviderApiKey resource", content = @Content(schema = @Schema(implementation = ProviderApiKey.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    @JsonView({ProviderApiKey.View.Public.class})
    public Response getById(@PathParam("id") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting LLM Provider's ApiKey by id '{}' on workspace_id '{}'", id, workspaceId);

        ProviderApiKey providerApiKey = llmProviderApiKeyService.find(id, workspaceId);

        log.info("Got LLM Provider's ApiKey by id '{}' on workspace_id '{}'", id, workspaceId);

        return Response.ok().entity(providerApiKey.toBuilder()
                .apiKey(providerApiKey.apiKey() != null ? maskApiKey(decrypt(providerApiKey.apiKey())) : null)
                .authConfig(providerApiKey.authConfig() != null ? providerApiKey.authConfig().mask() : null)
                .build()).build();
    }

    @POST
    @RequiredPermissions(WorkspaceUserPermission.AI_PROVIDER_UPDATE)
    @Operation(operationId = "storeLlmProviderApiKey", summary = "Store LLM Provider's ApiKey", description = "Store LLM Provider's ApiKey", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/proxy/api_key/{apiKeyId}", schema = @Schema(implementation = String.class))}),
            @ApiResponse(responseCode = "401", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "403", description = "Access forbidden", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response saveApiKey(
            @RequestBody(content = @Content(schema = @Schema(implementation = ProviderApiKey.class))) @JsonView(ProviderApiKey.View.Write.class) @Valid ProviderApiKey providerApiKey,
            @Context UriInfo uriInfo) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        log.info("Save api key for LLM provider '{}', on workspace_id '{}'", providerApiKey.provider(), workspaceId);
        var providerApiKeyId = llmProviderApiKeyService.saveApiKey(providerApiKey, userName, workspaceId).id();
        log.info("Saved api key for LLM provider '{}', on workspace_id '{}'", providerApiKey.provider(), workspaceId);

        var uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(providerApiKeyId)).build();

        return Response.created(uri).build();
    }

    @PATCH
    @Path("{id}")
    @RequiredPermissions(WorkspaceUserPermission.AI_PROVIDER_UPDATE)
    @Operation(operationId = "updateLlmProviderApiKey", summary = "Update LLM Provider's ApiKey", description = "Update LLM Provider's ApiKey. api_key and auth_config are mutually exclusive: setting a valid auth_config on a provider that holds a static api_key clears the stored key; send auth_config as an empty object to clear the recipe and switch back to a static key", responses = {
            @ApiResponse(responseCode = "204", description = "No Content"),
            @ApiResponse(responseCode = "401", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "403", description = "Access forbidden", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response updateApiKey(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = ProviderApiKeyUpdate.class))) @Valid ProviderApiKeyUpdate providerApiKeyUpdate) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        log.info("Updating api key for LLM provider with id '{}' on workspaceId '{}'", id, workspaceId);
        llmProviderApiKeyService.updateApiKey(id, providerApiKeyUpdate, userName, workspaceId);
        log.info("Updated api key for LLM provider with id '{}' on workspaceId '{}'", id, workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/auth-config/test")
    @RateLimited
    @RequiredPermissions(WorkspaceUserPermission.AI_PROVIDER_UPDATE)
    @Operation(operationId = "testLlmProviderAuthConfig", summary = "Test a provider's dynamic token auth", description = "Runs the token fetch once, backend-side, and reports the token lifetime. The token itself is never returned. "
            +
            "Send provider_id to test the stored config, auth_config to test submitted values, or both to resolve secret sentinels against the stored config.", responses = {
                    @ApiResponse(responseCode = "200", description = "Token fetched", content = @Content(schema = @Schema(implementation = ProviderAuthCheck.Result.class))),
                    @ApiResponse(responseCode = "400", description = "Bad Request — the token fetch itself failed (unreachable URL, rejected credentials, malformed reply)", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
                    @ApiResponse(responseCode = "422", description = "Unprocessable Content — the request is invalid (neither provider_id nor auth_config, or an invalid auth_config)", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
                    @ApiResponse(responseCode = "403", description = "Access forbidden", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
                    @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
            })
    public Response testAuthConfig(
            @NotNull @RequestBody(content = @Content(schema = @Schema(implementation = ProviderAuthCheck.class))) @Valid ProviderAuthCheck providerAuthTest) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Testing LLM provider auth config on workspace_id '{}'", workspaceId);
        ProviderAuthCheck.Result result = llmProviderApiKeyService.testAuthConfig(providerAuthTest, workspaceId);
        log.info("Tested LLM provider auth config on workspace_id '{}': token received, lifetime '{}'s",
                workspaceId, result.lifetimeSeconds());

        return Response.ok(result).build();
    }

    @POST
    @Path("/delete")
    @RequiredPermissions(WorkspaceUserPermission.AI_PROVIDER_UPDATE)
    @Operation(operationId = "deleteLlmProviderApiKeysBatch", summary = "Delete LLM Provider's ApiKeys", description = "Delete LLM Provider's ApiKeys batch", responses = {
            @ApiResponse(responseCode = "204", description = "No Content"),
    })
    public Response deleteApiKeys(
            @NotNull @RequestBody(content = @Content(schema = @Schema(implementation = BatchDelete.class))) @Valid BatchDelete batchDelete) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Deleting api keys for LLM provider by ids, count '{}', on workspace_id '{}'",
                batchDelete.ids().size(), workspaceId);
        llmProviderApiKeyService.delete(batchDelete.ids(), workspaceId);
        log.info("Deleted api keys for LLM provider by ids, count '{}', on workspace_id '{}'", batchDelete.ids().size(),
                workspaceId);
        return Response.noContent().build();
    }
}
