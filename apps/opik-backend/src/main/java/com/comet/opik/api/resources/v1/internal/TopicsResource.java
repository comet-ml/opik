package com.comet.opik.api.resources.v1.internal;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.error.ConflictException;
import com.comet.opik.domain.topics.TopicsNightlyService;
import com.comet.opik.infrastructure.TopicsConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.BadRequestException;
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
 * Starts topics work by hand instead of waiting for the night: one project's run, or a whole night. Internal and
 * cross-workspace, so it is reachable only from inside the cluster: like the other {@code /v1/internal} resources it is
 * not authenticated by {@code AuthFilter}. For the runbook.
 */
@Path("/v1/internal/topics")
@Produces(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Topics", description = "Internal topics operations")
public class TopicsResource {

    /** The oldest night a run may take, in days before today. */
    private static final int MAX_NIGHT_AGE_DAYS = 365;

    private final @NonNull TopicsNightlyService nightlyService;
    private final @NonNull @Config("topics") TopicsConfig config;

    @POST
    @Path("/projects/{projectId}/run")
    @Operation(operationId = "runProjectTopics", summary = "Run a project's topics now", description = "Starts the project's first build for the night (default today, UTC; at most tomorrow, at least 365 days ago) in the background and returns its run id; the run's outcome is its topic_runs row. The night's rules apply: the workspace is opted in to topics, the window holds topics.nightly.minVectors vectors of the project, one run by hand at a time.", responses = {
            @ApiResponse(responseCode = "202", description = "The run started"),
            @ApiResponse(responseCode = "400", description = "The night is not a date, or is after tomorrow"),
            @ApiResponse(responseCode = "409", description = "The nightly job is off, the workspace is not opted in, the window is too small, a run is in progress, or the project has a list"),
            @ApiResponse(responseCode = "501", description = "Topics are off")})
    public Response runProject(@PathParam("projectId") UUID projectId,
            @QueryParam("workspace_id") @NotBlank String workspaceId,
            @QueryParam("night") String night) {
        if (!config.isEnabled()) {
            return Response.status(Response.Status.NOT_IMPLEMENTED).build();
        }
        var runId = nightlyService.runNow(workspaceId, projectId, night(night));
        return Response.accepted(Map.of("run_id", runId)).build();
    }

    @POST
    @Path("/nights/run")
    @Operation(operationId = "runTopicsNight", summary = "Run the topics night now", description = "Runs the whole night (default today, UTC; at most tomorrow, at least 365 days ago) in the background, as the scheduled job does and under the same lock, which is released when it ends. Each project's outcome is its topic_runs row.", responses = {
            @ApiResponse(responseCode = "202", description = "The night started"),
            @ApiResponse(responseCode = "400", description = "The night is not a date, or is after tomorrow"),
            @ApiResponse(responseCode = "409", description = "The nightly job is off, or a night is in progress"),
            @ApiResponse(responseCode = "501", description = "Topics are off")})
    public Response runNight(@QueryParam("night") String night) {
        if (!config.isEnabled()) {
            return Response.status(Response.Status.NOT_IMPLEMENTED).build();
        }
        var runNight = night(night);
        if (!nightlyService.runNightNow(runNight)) {
            throw new ConflictException("A topics night is in progress");
        }
        return Response.accepted(Map.of("night", runNight.toString())).build();
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
