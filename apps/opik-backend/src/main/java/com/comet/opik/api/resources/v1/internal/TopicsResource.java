package com.comet.opik.api.resources.v1.internal;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.error.ConflictException;
import com.comet.opik.domain.topics.TopicsFirstBuildService;
import com.comet.opik.domain.topics.TopicsNightlyService;
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
 * Starts topics work by hand: a project's first build instead of waiting for the first-build job, or a whole night of
 * the nightly update. Internal and cross-workspace, so it is reachable only from inside the cluster: like the other
 * {@code /v1/internal} resources it is not authenticated by {@code AuthFilter}. For the runbook.
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

    private final @NonNull TopicsFirstBuildService firstBuildService;
    private final @NonNull TopicsNightlyService nightlyService;
    private final @NonNull @Config("topics") TopicsConfig config;

    @POST
    @Path("/projects/{projectId}/run")
    @Operation(operationId = "runProjectTopics", summary = "Run a project's topics now", description = "Starts the project's first build in the background and returns its run id; the run's outcome is its topic_runs row. The window ends at the start of the night date (UTC; default today, at most tomorrow, at least 365 days ago). The workspace is opted in to topics, the window holds topics.build.minVectors vectors of the project, and one run by hand goes at a time. The project's vectors backfill must have drained: ignore_backfill=true builds it from the vectors it has so far. A failed first build's retry wait does not apply.", responses = {
            @ApiResponse(responseCode = "202", description = "The run started"),
            @ApiResponse(responseCode = "400", description = "The night is not a date, is after tomorrow, or is more than 365 days ago"),
            @ApiResponse(responseCode = "409", description = "The workspace is not opted in, the window is too small, a run is in progress, the project has a list, or its vectors backfill has not drained"),
            @ApiResponse(responseCode = "503", description = "Redis failed or did not answer while the run took its locks; retry after the Retry-After delay"),
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
        var runId = firstBuildService.runNow(workspaceId, projectId, night(night), ignoreBackfill);
        return Response.accepted(Map.of("run_id", runId)).build();
    }

    @POST
    @Path("/nights/run")
    @Operation(operationId = "runTopicsNight", summary = "Run the topics night now", description = "Runs the whole night of the nightly update (default today, UTC; at most tomorrow, at least 365 days ago) in the background, as the scheduled job does and under the same lock, which is released when it ends. Each project's outcome is its topic_runs row.", responses = {
            @ApiResponse(responseCode = "202", description = "The night started"),
            @ApiResponse(responseCode = "400", description = "The night is not a date, is after tomorrow, or is more than 365 days ago"),
            @ApiResponse(responseCode = "409", description = "The nightly job is off, or a night is in progress"),
            @ApiResponse(responseCode = "503", description = "Redis failed or did not answer while the night took its lock; retry after the Retry-After delay"),
            @ApiResponse(responseCode = "501", description = "Topics are off")})
    public Response runNight(
            @QueryParam("night") @Schema(description = "The night to run, yyyy-MM-dd (UTC). Default today's date in UTC; at most tomorrow, at least 365 days ago") String night) {
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
