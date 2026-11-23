package com.comet.opik.api.resources.v1.internal;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.domain.topics.TopicsRunPlanner;
import com.comet.opik.infrastructure.TopicsConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
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
 * Queues a project's topics run by hand instead of waiting for the leader's next plan: its first build, or its nightly
 * update for one night. Internal and cross-workspace, so it is reachable only from inside the cluster: like the other {@code /v1/internal} resources it is
 * not authenticated by {@code AuthFilter}. For the runbook.
 */
@Path("/v1/internal/topics")
@Produces(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Topics", description = "Project topics and internal topics operations")
public class TopicsResource {

    /** The oldest window end a run may take, in days before today. */
    private static final int MAX_NIGHT_AGE_DAYS = 365;

    private final @NonNull TopicsRunPlanner planner;
    private final @NonNull @Config("topics") TopicsConfig config;

    @POST
    @Path("/projects/{projectId}/run")
    @Operation(operationId = "runProjectTopics", summary = "Run a project's topics now", description = "Queues the project's first build and returns its run id; any pod runs it. The run's outcome is on the project's topic_projects row while it is the project's latest run, and in the run's logs. The window ends at the start of the night date (UTC; default today, at most tomorrow, at least 365 days ago). The workspace is opted in to topics, the window holds topics.build.minVectors vectors of the project, and the project has no list and no run in flight. The project's vectors backfill must have drained: ignore_backfill=true builds it from the vectors it has so far. A failed first build's retry wait and topics.runs.maxInFlight do not apply.", responses = {
            @ApiResponse(responseCode = "202", description = "The run is queued"),
            @ApiResponse(responseCode = "400", description = "The night is not a date, is after tomorrow, or is more than 365 days ago"),
            @ApiResponse(responseCode = "409", description = "The workspace is not opted in, the window is too small, a run is in flight, the project has a list, or its vectors backfill has not drained"),
            @ApiResponse(responseCode = "503", description = "The run could not be started; nothing is queued, try again"),
            @ApiResponse(responseCode = "501", description = "Topics are off")})
    public Response runProject(@PathParam("projectId") UUID projectId,
            @QueryParam("workspace_id") @NotBlank String workspaceId,
            @QueryParam("night") String night,
            @QueryParam("ignore_backfill") @DefaultValue("false") boolean ignoreBackfill) {
        if (!config.isEnabled()) {
            return Response.status(Response.Status.NOT_IMPLEMENTED).build();
        }
        if (ignoreBackfill) {
            log.warn("Topics run by hand ignores the vectors backfill gate, workspace '{}', project '{}'", workspaceId,
                    projectId);
        }
        var runId = planner.runByHand(workspaceId, projectId, night(night), ignoreBackfill).block();
        return Response.accepted(Map.of("run_id", runId)).build();
    }

    @POST
    @Path("/projects/{projectId}/night")
    @Operation(operationId = "runProjectTopicsNight", summary = "Run a project's nightly update now", description = "Queues the project's nightly update for the night date (UTC; default today, at most tomorrow, at least 365 days ago) and returns its run id; any pod runs it. Its window ends at the night's start. The run's outcome is on the project's topic_projects row while it is the project's latest run, and in the run's logs. The nightly update is on (topics.nightly.enabled), the workspace is opted in to topics, the project has a list whose window ends before the night, its window holds topics.build.minVectors vectors, and it has no run in flight. topics.runs.maxInFlight and the night's budget do not apply, and the project's night fields are not written.", responses = {
            @ApiResponse(responseCode = "202", description = "The run is queued"),
            @ApiResponse(responseCode = "400", description = "The night is not a date, is after tomorrow, or is more than 365 days ago"),
            @ApiResponse(responseCode = "409", description = "The nightly update is off, the workspace is not opted in, the project has no list or its list already covers the night, the window is too small, or a run is in flight"),
            @ApiResponse(responseCode = "501", description = "Topics are off")})
    public Response runProjectNight(@PathParam("projectId") UUID projectId,
            @QueryParam("workspace_id") @NotBlank String workspaceId,
            @QueryParam("night") @Schema(description = "The night, yyyy-MM-dd (UTC). Default today's date in UTC; at most tomorrow, at least 365 days ago") String night) {
        if (!config.isEnabled()) {
            return Response.status(Response.Status.NOT_IMPLEMENTED).build();
        }
        var runId = planner.nightByHand(workspaceId, projectId, night(night)).block();
        return Response.accepted(Map.of("run_id", runId)).build();
    }

    /** Default today (UTC). Tomorrow's window ends at tomorrow's start, so it holds today's traces so far; a later
     * night holds nothing more. */
    private static LocalDate night(String night) {
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
        // Older than any window the vectors keep: the backfill reaches 365 days back at most.
        if (runNight.isBefore(today.minusDays(MAX_NIGHT_AGE_DAYS))) {
            throw new BadRequestException("night must be within '%s' days before today, night '%s'"
                    .formatted(MAX_NIGHT_AGE_DAYS, runNight));
        }
        return runNight;
    }
}
