package com.comet.opik.api.resources.v1.internal;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.domain.topics.TopicsRunPlanner;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;

/**
 * Queues a project's discovery run by hand instead of waiting for the leader's next plan, and forgets a project's list
 * so the next discovery run builds it again. Internal and cross-workspace, so it is reachable only from inside the
 * cluster: like the other {@code /v1/internal} resources it is not authenticated by {@code AuthFilter}. For the runbook.
 */
@Path("/v1/internal/topics")
@Produces(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Topics", description = "Internal topics operations")
public class TopicsResource {

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TopicsRunPlanner planner;

    @POST
    @Path("/projects/{projectId}/run")
    @Operation(operationId = "runProjectTopics", summary = "Run a project's topics now", description = "Queues the project's discovery run and returns its run id; any pod runs it. The run's outcome is on the project's topic_projects row while it is the project's latest run, and in the run's logs. The window ends at the start of the night date (UTC; default today, at most tomorrow, at least the vectors backfill window ago). The workspace is opted in to topics at stage build or later, the window holds topics.run.minVectors vectors of the project, and the project has no list and no run in flight. The project's vectors backfill must have drained: ignore_backfill=true builds it from the vectors it has so far. A failed discovery run's retry wait and topics.runs.maxInFlight do not apply.", responses = {
            @ApiResponse(responseCode = "202", description = "The run is queued"),
            @ApiResponse(responseCode = "400", description = "The night is not a date, is after tomorrow, or is longer ago than the vectors backfill window"),
            @ApiResponse(responseCode = "409", description = "The workspace is not opted in at stage build or later, or topics are off, the window is too small, a run is in flight, the project has a list, or its vectors backfill has not drained"),
            @ApiResponse(responseCode = "503", description = "The run could not be started; nothing is queued, try again")})
    public Response runProject(@PathParam("projectId") UUID projectId,
            @QueryParam("workspace_id") @NotBlank String workspaceId,
            @QueryParam("night") String night,
            @QueryParam("ignore_backfill") @DefaultValue("false") boolean ignoreBackfill) {
        if (ignoreBackfill) {
            log.warn("Topics run by hand ignores the vectors backfill gate, workspace '{}', project '{}'", workspaceId,
                    projectId);
        }
        var runId = planner.runByHand(workspaceId, projectId, night(night), ignoreBackfill).block();
        return Response.accepted(Map.of("run_id", runId)).build();
    }

    @POST
    @Path("/projects/{projectId}/reset")
    @Operation(operationId = "resetProjectTopics", summary = "Forget a project's topic list", description = "Deletes the project's topics and clears the list on its topic_projects row, in one transaction, so its next discovery run, planned or by hand, builds a new list. The row's run and vectors backfill state stay. The workspace is opted in to topics at stage build or later, and the project has a topic_projects row and no run in flight.", responses = {
            @ApiResponse(responseCode = "204", description = "The list is forgotten"),
            @ApiResponse(responseCode = "409", description = "The workspace is not opted in at stage build or later, or topics are off, or the project has no topic_projects row or a run in flight; nothing changed")})
    public Response resetProject(@PathParam("projectId") UUID projectId,
            @QueryParam("workspace_id") @NotBlank String workspaceId) {
        planner.resetByHand(projectId).contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId)).block();
        return Response.noContent().build();
    }

    /** Default today (UTC). Tomorrow's window ends at tomorrow's start, so it holds today's traces so far; a later
     * night holds nothing more. */
    private LocalDate night(String night) {
        var today = LocalDate.now(ZoneOffset.UTC);
        LocalDate runNight;
        try {
            runNight = night == null ? today : LocalDate.parse(night);
        } catch (DateTimeParseException exception) {
            throw new BadRequestException("night must be a date, yyyy-MM-dd, night '%s'".formatted(night));
        }
        if (runNight.isAfter(today.plusDays(1))) {
            throw new BadRequestException("night must not be after tomorrow, night '%s'".formatted(runNight));
        }
        // Older than the vectors backfill reaches, so the window holds no vectors.
        var maxAgeDays = config.getVectors().backfillOrLongWindow().toDays();
        if (runNight.isBefore(today.minusDays(maxAgeDays))) {
            throw new BadRequestException("night must be within '%s' days before today, night '%s'"
                    .formatted(maxAgeDays, runNight));
        }
        return runNight;
    }
}
