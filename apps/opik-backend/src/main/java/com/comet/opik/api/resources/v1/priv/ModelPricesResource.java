package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.modelprice.CustomModelPriceHistoryPage;
import com.comet.opik.api.modelprice.CustomModelPriceList;
import com.comet.opik.api.modelprice.CustomModelPriceProblems;
import com.comet.opik.api.modelprice.CustomModelPriceReplace;
import com.comet.opik.api.modelprice.CustomModelPriceReplaceResult;
import com.comet.opik.api.modelprice.CustomModelPriceResolution;
import com.comet.opik.api.modelprice.CustomModelPriceResolveRequest;
import com.comet.opik.domain.cost.ModelPriceService;
import com.comet.opik.domain.cost.ModelPriceValidationException;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.OrganizationResolver;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.auth.RequiredPermissions;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import io.dropwizard.jersey.errors.ErrorMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

import static com.comet.opik.utils.AsyncUtils.setRequestContext;

@Path("/v1/private/model-prices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Model Prices", description = "Custom model prices of the workspace's organization")
public class ModelPricesResource {

    static final String DISABLED = "Custom model prices are disabled";

    private final @NonNull ModelPriceService modelPriceService;
    private final @NonNull OrganizationResolver organizationResolver;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull OpikConfiguration config;

    @GET
    @Operation(operationId = "findModelPrices", summary = "Find model prices", description = "The organization's price rows and the list version; an empty list at version 0 when the organization has none or is unknown", responses = {
            @ApiResponse(responseCode = "200", description = "Model prices", content = @Content(schema = @Schema(implementation = CustomModelPriceList.class))),
            @ApiResponse(responseCode = "503", description = "Custom model prices are disabled", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response find() {
        return whenEnabled(() -> {
            String workspaceId = requestContext.get().getWorkspaceId();
            log.info("Finding model prices, workspace '{}'", workspaceId);
            var list = organizationResolver.organizationId()
                    .flatMap(modelPriceService::findRows)
                    .defaultIfEmpty(CustomModelPriceList.EMPTY)
                    .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                    .block();
            log.info("Found model prices, workspace '{}', version '{}'", workspaceId, list.version());
            return Response.ok(list).build();
        });
    }

    @PUT
    @RequiredPermissions(WorkspaceUserPermission.WORKSPACE_SETTINGS_CONFIGURE)
    @Operation(operationId = "replaceModelPrices", summary = "Replace model prices", description = "Replaces the organization's whole list in one go. With dry_run=true it returns what would change and saves nothing", responses = {
            @ApiResponse(responseCode = "200", description = "What changed, or would change", content = @Content(schema = @Schema(implementation = CustomModelPriceReplaceResult.class))),
            @ApiResponse(responseCode = "403", description = "Not an admin of the organization, or the organization is unknown", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "The list changed since expected_version", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "422", description = "Invalid rows, each problem listed", content = @Content(schema = @Schema(implementation = CustomModelPriceProblems.class))),
            @ApiResponse(responseCode = "503", description = "Custom model prices are disabled", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response replace(
            @QueryParam("dry_run") @DefaultValue("false") boolean dryRun,
            @RequestBody(content = @Content(schema = @Schema(implementation = CustomModelPriceReplace.class))) @NotNull @Valid CustomModelPriceReplace replace) {
        return whenEnabled(() -> {
            String workspaceId = requestContext.get().getWorkspaceId();
            String userName = requestContext.get().getUserName();
            var request = new ModelPriceService.ReplaceRequest(replace.rows(), replace.expectedVersion());
            log.info("Replacing model prices, workspace '{}', rows '{}', dry run '{}'", workspaceId,
                    replace.rows().size(), dryRun);
            try {
                var result = organizationResolver.requireOrganizationAdmin()
                        .flatMap(organizationId -> dryRun
                                ? modelPriceService.previewReplaceAll(organizationId, request)
                                : modelPriceService.replaceAll(organizationId, request, userName))
                        .map(CustomModelPriceReplaceResult::from)
                        .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                        .block();
                log.info("{} model prices, workspace '{}', version '{}', saved '{}'",
                        dryRun ? "Previewed" : "Replaced", workspaceId, result.version(), result.saved());
                return Response.ok(result).build();
            } catch (ModelPriceValidationException exception) {
                log.info("Refused model prices, workspace '{}', errors '{}'", workspaceId,
                        exception.getResult().errors().size());
                return Response.status(422)
                        .entity(CustomModelPriceProblems.builder()
                                .errors(exception.getResult().errors())
                                .warnings(exception.getResult().warnings())
                                .build())
                        .build();
            }
        });
    }

    @GET
    @Path("/history")
    @Operation(operationId = "findModelPriceHistory", summary = "Find model price history", description = "Every saved change, newest first", responses = {
            @ApiResponse(responseCode = "200", description = "Model price history", content = @Content(schema = @Schema(implementation = CustomModelPriceHistoryPage.class))),
            @ApiResponse(responseCode = "503", description = "Custom model prices are disabled", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response findHistory(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @Max(1000) @DefaultValue("100") int size) {
        return whenEnabled(() -> {
            String workspaceId = requestContext.get().getWorkspaceId();
            log.info("Finding model price history, workspace '{}', page '{}'", workspaceId, page);
            var history = organizationResolver.organizationId()
                    .flatMap(organizationId -> modelPriceService.findHistory(organizationId, page, size))
                    .defaultIfEmpty(CustomModelPriceHistoryPage.empty(page, size))
                    .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                    .block();
            log.info("Found model price history, workspace '{}', total '{}'", workspaceId, history.total());
            return Response.ok(history).build();
        });
    }

    @POST
    @Path("/resolve")
    @Operation(operationId = "resolveModelPrice", summary = "Resolve a model price", description = "Which price applies to a model in this workspace, and what a span with the given usage would cost", responses = {
            @ApiResponse(responseCode = "200", description = "The price that applies", content = @Content(schema = @Schema(implementation = CustomModelPriceResolution.class))),
            @ApiResponse(responseCode = "503", description = "Custom model prices are disabled", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response resolve(
            @RequestBody(content = @Content(schema = @Schema(implementation = CustomModelPriceResolveRequest.class))) @NotNull @Valid CustomModelPriceResolveRequest request) {
        return whenEnabled(() -> {
            String workspaceId = requestContext.get().getWorkspaceId();
            log.info("Resolving model price, workspace '{}', model '{}', provider '{}'", workspaceId,
                    request.model(), request.provider());
            var resolution = modelPriceService.resolve(request)
                    .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                    .block();
            log.info("Resolved model price, workspace '{}', model '{}', source '{}'", workspaceId, request.model(),
                    resolution.costSource());
            return Response.ok(resolution).build();
        });
    }

    private Response whenEnabled(Supplier<Response> endpoint) {
        if (config.getServiceToggles() == null || !config.getServiceToggles().isModelPricesEnabled()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(new ErrorMessage(Response.Status.SERVICE_UNAVAILABLE.getStatusCode(), DISABLED))
                    .build();
        }
        return endpoint.get();
    }
}
