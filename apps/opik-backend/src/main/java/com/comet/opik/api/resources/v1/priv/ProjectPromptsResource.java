package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.Prompt;
import com.comet.opik.api.Prompt.PromptPage;
import com.comet.opik.api.filter.FiltersFactory;
import com.comet.opik.api.filter.PromptFilter;
import com.comet.opik.api.sorting.SortingFactoryPrompts;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.PromptService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.fasterxml.jackson.annotation.JsonView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.validation.constraints.Min;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
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

@Path("/v1/private/projects/{projectId}/prompts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Projects", description = "Project related resources")
public class ProjectPromptsResource {

    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull PromptService promptService;
    private final @NonNull SortingFactoryPrompts sortingFactory;
    private final @NonNull FiltersFactory filtersFactory;

    @GET
    @Operation(operationId = "getPromptsByProject", summary = "Get prompts by project", description = "Get prompts scoped to a project", responses = {
            @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = PromptPage.class))),
    })
    @JsonView(Prompt.View.Public.class)
    public Response getPrompts(
            @PathParam("projectId") UUID projectId,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("name") @Schema(description = "Filter prompts by name (partial match, case insensitive)") String name,
            @QueryParam("sorting") String sorting,
            @QueryParam("filters") String filters) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting prompts by project '{}' on workspace_id '{}', page '{}', size '{}'", projectId, workspaceId,
                page, size);

        List<SortingField> sortingFields = sortingFactory.newSorting(sorting);
        var promptFilters = filtersFactory.newFilters(filters, PromptFilter.LIST_TYPE_REFERENCE);
        PromptPage promptPage = promptService.find(name, projectId, page, size, sortingFields, promptFilters);

        log.info("Got prompts by project '{}', count '{}' on workspace_id '{}'", projectId, promptPage.size(),
                workspaceId);

        return Response.ok(promptPage).build();
    }

}
