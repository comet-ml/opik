package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.retention.RetentionRule;
import com.comet.opik.api.retention.RetentionRule.RetentionRulePage;
import com.comet.opik.domain.retention.RetentionRuleService;
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

@Path("/v1/private/retention/rules")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Retention Rules", description = "Data retention rule management")
public class RetentionRulesResource {

    private final @NonNull RetentionRuleService service;
    private final @NonNull Provider<RequestContext> requestContext;

    @POST
    @Operation(operationId = "createRetentionRule", summary = "Create retention rule", description = "Create a new retention rule. Auto-deactivates any existing active rule for the same scope.", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/retention/rules/{ruleId}", schema = @Schema(implementation = String.class))}, content = @Content(schema = @Schema(implementation = RetentionRule.class)))
    })
    @JsonView(RetentionRule.View.Public.class)
    @RateLimited
    public Response createRule(
            @RequestBody(content = @Content(schema = @Schema(implementation = RetentionRule.class))) @JsonView(RetentionRule.View.Write.class) @NotNull @Valid RetentionRule rule,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Creating retention rule for project '{}' in workspace '{}'",
                rule.projectId(), workspaceId);

        RetentionRule created = service.create(rule);

        log.info("Created retention rule '{}' in workspace '{}'", created.id(), workspaceId);

        URI uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(created.id().toString())).build();
        return Response.created(uri).entity(created).build();
    }

    @GET
    @Operation(operationId = "findRetentionRules", summary = "Find retention rules", description = "List retention rules for the caller's workspace. Defaults to active only.", responses = {
            @ApiResponse(responseCode = "200", description = "Retention rules page", content = @Content(schema = @Schema(implementation = RetentionRulePage.class)))
    })
    @JsonView(RetentionRule.View.Public.class)
    public Response findRules(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("include_inactive") @DefaultValue("false") boolean includeInactive) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Finding retention rules in workspace '{}', page '{}', size '{}', includeInactive '{}'",
                workspaceId, page, size, includeInactive);

        RetentionRulePage rulePage = service.find(page, size, includeInactive);

        log.info("Found '{}' retention rules in workspace '{}'", rulePage.total(), workspaceId);
        return Response.ok(rulePage).build();
    }

    @GET
    @Path("/{ruleId}")
    @Operation(operationId = "getRetentionRuleById", summary = "Get retention rule by id", description = "Get a specific retention rule by id", responses = {
            @ApiResponse(responseCode = "200", description = "Retention rule", content = @Content(schema = @Schema(implementation = RetentionRule.class))),
            @ApiResponse(responseCode = "404", description = "Retention rule not found")
    })
    @JsonView(RetentionRule.View.Public.class)
    public Response getRuleById(@PathParam("ruleId") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Finding retention rule '{}' in workspace '{}'", id, workspaceId);

        RetentionRule rule = service.findById(id);

        log.info("Found retention rule '{}' in workspace '{}'", id, workspaceId);
        return Response.ok().entity(rule).build();
    }

    @DELETE
    @Path("/{ruleId}")
    @Operation(operationId = "deactivateRetentionRule", summary = "Deactivate retention rule", description = "Soft-deactivate a retention rule (sets enabled=false). Rules are never hard-deleted for audit trail.", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
            @ApiResponse(responseCode = "404", description = "Retention rule not found")
    })
    public Response deactivateRule(@PathParam("ruleId") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Deactivating retention rule '{}' in workspace '{}'", id, workspaceId);

        service.deactivate(id);

        log.info("Deactivated retention rule '{}' in workspace '{}'", id, workspaceId);
        return Response.noContent().build();
    }
}
