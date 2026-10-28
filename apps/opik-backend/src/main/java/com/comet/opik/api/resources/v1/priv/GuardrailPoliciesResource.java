package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.GuardrailPolicy;
import com.comet.opik.api.GuardrailPolicy.GuardrailPolicyPage;
import com.comet.opik.api.GuardrailPolicy.GuardrailPolicyRetrieve;
import com.comet.opik.api.GuardrailPolicy.GuardrailPolicyRetrieveResponse;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.sorting.SortingFactoryGuardrailPolicies;
import com.comet.opik.domain.GuardrailPolicyService;
import com.comet.opik.domain.GuardrailsAccess;
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

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Path("/v1/private/guardrails/policies")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Guardrail policies", description = "Workspace guardrail policy resources")
public class GuardrailPoliciesResource {

    private final @NonNull GuardrailPolicyService service;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull GuardrailsAccess guardrailsAccess;
    private final @NonNull SortingFactoryGuardrailPolicies sortingFactory;

    @POST
    @Operation(operationId = "createGuardrailPolicy", summary = "Create guardrail policy", description = "Create a guardrail policy with its rules in a workspace", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/guardrails/policies/{id}", schema = @Schema(implementation = String.class))}),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "A policy with this name already exists", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "422", description = "Unprocessable Content", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    @RateLimited
    public Response createGuardrailPolicy(
            @RequestBody(content = @Content(schema = @Schema(implementation = GuardrailPolicy.class))) @JsonView(GuardrailPolicy.View.Write.class) @NotNull @Valid GuardrailPolicy policy,
            @Context UriInfo uriInfo) {
        guardrailsAccess.check();

        log.info("Creating guardrail policy with name '{}' in workspace '{}'", policy.name(),
                requestContext.get().getWorkspaceId());
        var id = service.create(policy);
        var uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(id)).build();
        return Response.created(uri).build();
    }

    @GET
    @Path("/{id}")
    @Operation(operationId = "getGuardrailPolicyById", summary = "Get guardrail policy by id", description = "Get guardrail policy by id", responses = {
            @ApiResponse(responseCode = "200", description = "Guardrail policy", content = @Content(schema = @Schema(implementation = GuardrailPolicy.class))),
            @ApiResponse(responseCode = "404", description = "Guardrail policy not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    @JsonView(GuardrailPolicy.View.Public.class)
    public Response getGuardrailPolicyById(@PathParam("id") UUID id) {
        guardrailsAccess.check();

        return Response.ok(service.findById(id)).build();
    }

    @GET
    @Operation(operationId = "findGuardrailPolicies", summary = "Find guardrail policies", description = "Find guardrail policies in a workspace", responses = {
            @ApiResponse(responseCode = "200", description = "Guardrail policy page", content = @Content(schema = @Schema(implementation = GuardrailPolicyPage.class)))})
    @JsonView(GuardrailPolicy.View.Public.class)
    public Response findGuardrailPolicies(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("name") @Schema(description = "Filter policies by name (partial match, case insensitive)") String name,
            @QueryParam("sorting") String sorting) {
        guardrailsAccess.check();

        var sortingFields = sortingFactory.newSorting(sorting);
        return Response.ok(service.find(page, size, name, sortingFields)).build();
    }

    @PUT
    @Path("/{id}")
    @Operation(operationId = "updateGuardrailPolicy", summary = "Update guardrail policy", description = "Replace a guardrail policy, rules included: a rule left out of the request is removed", responses = {
            @ApiResponse(responseCode = "204", description = "No Content"),
            @ApiResponse(responseCode = "400", description = "Bad Request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Guardrail policy not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "A policy with this name already exists", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "422", description = "Unprocessable Content", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    @RateLimited
    public Response updateGuardrailPolicy(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = GuardrailPolicy.class))) @JsonView(GuardrailPolicy.View.Write.class) @NotNull @Valid GuardrailPolicy policy) {
        guardrailsAccess.check();

        log.info("Updating guardrail policy with id '{}' in workspace '{}'", id, requestContext.get().getWorkspaceId());
        service.update(id, policy);
        return Response.noContent().build();
    }

    @POST
    @Path("/delete")
    @Operation(operationId = "deleteGuardrailPoliciesBatch", summary = "Delete guardrail policies", description = "Delete guardrail policies and their rules", responses = {
            @ApiResponse(responseCode = "204", description = "No Content")})
    public Response deleteGuardrailPoliciesBatch(
            @NotNull @RequestBody(content = @Content(schema = @Schema(implementation = BatchDelete.class))) @Valid BatchDelete batchDelete) {
        guardrailsAccess.check();

        log.info("Deleting '{}' guardrail policies in workspace '{}'", batchDelete.ids().size(),
                requestContext.get().getWorkspaceId());
        service.delete(batchDelete.ids());
        return Response.noContent().build();
    }

    @POST
    @Path("/retrieve")
    @Operation(operationId = "retrieveGuardrailPolicies", summary = "Retrieve guardrail policies by name", description = "Return the named policies plus every global policy of the workspace. Fails when a named policy does not exist", responses = {
            @ApiResponse(responseCode = "200", description = "Guardrail policies", content = @Content(schema = @Schema(implementation = GuardrailPolicyRetrieveResponse.class))),
            @ApiResponse(responseCode = "404", description = "A named policy does not exist", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    @JsonView(GuardrailPolicy.View.Public.class)
    public Response retrieveGuardrailPolicies(
            @RequestBody(content = @Content(schema = @Schema(implementation = GuardrailPolicyRetrieve.class))) @NotNull @Valid GuardrailPolicyRetrieve retrieve) {
        guardrailsAccess.check();

        var names = Optional.ofNullable(retrieve.names()).orElse(Set.of());
        return Response.ok(new GuardrailPolicyRetrieveResponse(service.retrieve(names))).build();
    }
}
