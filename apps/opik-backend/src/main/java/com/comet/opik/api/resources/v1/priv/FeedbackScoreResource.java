package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.FeedbackDefinition;
import com.comet.opik.api.FeedbackScoreNames;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.fasterxml.jackson.annotation.JsonView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

import static com.comet.opik.api.FeedbackScoreNames.ScoreName;
import static com.comet.opik.utils.AsyncUtils.setRequestContext;

@Path("/v1/private/feedback-scores")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Feedback-scores", description = "Feedback scores related resources")
public class FeedbackScoreResource {

    private final @NonNull FeedbackScoreService feedbackScoreService;
    private final @NonNull Provider<RequestContext> requestContext;

    @GET
    @Path("/names")
    @Operation(operationId = "findFeedbackScoreNames", summary = "Find Feedback Score names", description = "Find Feedback Score names", responses = {
            @ApiResponse(responseCode = "200", description = "Feedback Scores resource", content = @Content(array = @ArraySchema(schema = @Schema(implementation = String.class))))
    })
    @JsonView({FeedbackDefinition.View.Public.class})
    public Response findFeedbackScoreNames(
            @QueryParam("project_id") UUID projectId,
            @QueryParam("with_experiments_only") boolean withExperimentsOnly) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Find feedback score names by  project_id '{}' and with_experiments_only '{}', on workspaceId '{}'",
                projectId, withExperimentsOnly, workspaceId);
        FeedbackScoreNames feedbackScoreNames = feedbackScoreService
                .getFeedbackScoreNames(projectId, withExperimentsOnly)
                .map(names -> names.stream().map(ScoreName::new).toList())
                .map(FeedbackScoreNames::new)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Found feedback score names by project_id '{}' and with_experiments_only '{}', on workspaceId '{}'",
                projectId, withExperimentsOnly, workspaceId);

        return Response.ok(feedbackScoreNames).build();
    }

}
