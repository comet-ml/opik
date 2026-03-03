package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.runner.ConnectRequest;
import com.comet.opik.api.runner.ConnectResponse;
import com.comet.opik.api.runner.CreateJobRequest;
import com.comet.opik.api.runner.HeartbeatResponse;
import com.comet.opik.api.runner.JobResultRequest;
import com.comet.opik.api.runner.LocalRunner;
import com.comet.opik.api.runner.LocalRunnerJob;
import com.comet.opik.api.runner.LogEntry;
import com.comet.opik.api.runner.PairResponse;
import com.comet.opik.domain.RunnerService;
import com.comet.opik.infrastructure.RunnerConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import io.swagger.v3.oas.annotations.Operation;
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
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Path("/v1/private/runners")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Runners", description = "Local runner management endpoints")
public class RunnersResource {

    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull RunnerService runnerService;
    private final @NonNull RunnerConfig runnerConfig;

    @POST
    @Path("/pair")
    @RateLimited
    @Operation(operationId = "generatePairingCode", summary = "Generate a pairing code", description = "Generate a pairing code for a local runner in the current workspace", responses = {
            @ApiResponse(responseCode = "200", description = "Pairing code generated", content = @Content(schema = @Schema(implementation = PairResponse.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response generatePairingCode() {
        ensureEnabled();
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        PairResponse response = runnerService.generatePairingCode(workspaceId, userName);
        return Response.ok(response).build();
    }

    @POST
    @Path("/connect")
    @RateLimited
    @Operation(operationId = "connectRunner", summary = "Connect a local runner", description = "Exchange a pairing code or API key for local runner credentials", responses = {
            @ApiResponse(responseCode = "200", description = "Runner connected", content = @Content(schema = @Schema(implementation = ConnectResponse.class))),
            @ApiResponse(responseCode = "400", description = "Bad request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response connect(
            @RequestBody(content = @Content(schema = @Schema(implementation = ConnectRequest.class))) @NotNull @Valid ConnectRequest request) {
        ensureEnabled();
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        ConnectResponse response = runnerService.connect(workspaceId, userName, request);
        return Response.ok(response).build();
    }

    @GET
    @Operation(operationId = "listRunners", summary = "List local runners", description = "List all local runners in the current workspace", responses = {
            @ApiResponse(responseCode = "200", description = "Runners list"),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response listRunners() {
        ensureEnabled();
        String workspaceId = requestContext.get().getWorkspaceId();
        List<LocalRunner> runners = runnerService.listRunners(workspaceId);
        return Response.ok(runners).build();
    }

    @GET
    @Path("/{runnerId}")
    @Operation(operationId = "getRunner", summary = "Get local runner", description = "Get a single local runner with its registered agents", responses = {
            @ApiResponse(responseCode = "200", description = "Runner details", content = @Content(schema = @Schema(implementation = LocalRunner.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response getRunner(@PathParam("runnerId") String runnerId) {
        ensureEnabled();
        String workspaceId = requestContext.get().getWorkspaceId();
        LocalRunner runner = runnerService.getRunner(workspaceId, runnerId);
        return Response.ok(runner).build();
    }

    @PUT
    @Path("/{runnerId}/agents")
    @RateLimited
    @Operation(operationId = "registerAgents", summary = "Register local runner agents", description = "Register or update the local runner's agent list", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
            @ApiResponse(responseCode = "400", description = "Bad request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response registerAgents(@PathParam("runnerId") String runnerId,
            @NotNull Map<String, LocalRunner.Agent> agents) {
        ensureEnabled();
        String workspaceId = requestContext.get().getWorkspaceId();
        runnerService.registerAgents(runnerId, workspaceId, agents);
        return Response.noContent().build();
    }

    @POST
    @Path("/{runnerId}/heartbeat")
    @RateLimited
    @Operation(operationId = "heartbeat", summary = "Local runner heartbeat", description = "Refresh local runner heartbeat", responses = {
            @ApiResponse(responseCode = "200", description = "Heartbeat response", content = @Content(schema = @Schema(implementation = HeartbeatResponse.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "410", description = "Gone", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response heartbeat(@PathParam("runnerId") String runnerId) {
        ensureEnabled();
        String workspaceId = requestContext.get().getWorkspaceId();
        HeartbeatResponse response = runnerService.heartbeat(runnerId, workspaceId);
        return Response.ok(response).build();
    }

    @POST
    @Path("/jobs")
    @RateLimited
    @Operation(operationId = "createJob", summary = "Create local runner job", description = "Create a local runner job and enqueue it for execution", responses = {
            @ApiResponse(responseCode = "201", description = "Job created", content = @Content(schema = @Schema(implementation = LocalRunnerJob.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "Conflict", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response createJob(
            @RequestBody(content = @Content(schema = @Schema(implementation = CreateJobRequest.class))) @NotNull @Valid CreateJobRequest request) {
        ensureEnabled();
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        LocalRunnerJob job = runnerService.createJob(workspaceId, userName, request);
        return Response.status(Response.Status.CREATED).entity(job).build();
    }

    @GET
    @Path("/{runnerId}/jobs")
    @Operation(operationId = "listJobs", summary = "List local runner jobs", description = "List jobs for a local runner", responses = {
            @ApiResponse(responseCode = "200", description = "Jobs list", content = @Content(schema = @Schema(implementation = LocalRunnerJob.LocalRunnerJobPage.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response listJobs(@PathParam("runnerId") String runnerId,
            @QueryParam("project") String project,
            @QueryParam("page") @DefaultValue("0") @Min(0) int page,
            @QueryParam("size") @DefaultValue("25") @Min(1) int size) {
        ensureEnabled();
        String workspaceId = requestContext.get().getWorkspaceId();
        LocalRunnerJob.LocalRunnerJobPage jobPage = runnerService.listJobs(runnerId, project, workspaceId, page, size);
        return Response.ok(jobPage).build();
    }

    @POST
    @Path("/{runnerId}/jobs/next")
    @Operation(operationId = "nextJob", summary = "Next local runner job", description = "Long-poll for the next pending local runner job", responses = {
            @ApiResponse(responseCode = "200", description = "Job available", content = @Content(schema = @Schema(implementation = LocalRunnerJob.class))),
            @ApiResponse(responseCode = "204", description = "No content"),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public void nextJob(@PathParam("runnerId") String runnerId,
            @Suspended AsyncResponse asyncResponse) {
        ensureEnabled();
        asyncResponse.setTimeout(runnerConfig.getNextJobPollTimeoutSeconds() + 5, TimeUnit.SECONDS);
        asyncResponse.setTimeoutHandler(ar -> ar.resume(Response.noContent().build()));
        String workspaceId = requestContext.get().getWorkspaceId();
        runnerService.nextJob(runnerId, workspaceId)
                .thenAccept(job -> {
                    if (job == null) {
                        asyncResponse.resume(Response.noContent().build());
                    } else {
                        asyncResponse.resume(Response.ok(job).build());
                    }
                })
                .exceptionally(e -> {
                    asyncResponse.resume(e);
                    return null;
                });
    }

    @GET
    @Path("/jobs/{jobId}")
    @Operation(operationId = "getJob", summary = "Get local runner job", description = "Get a single local runner job's status and results", responses = {
            @ApiResponse(responseCode = "200", description = "Job details", content = @Content(schema = @Schema(implementation = LocalRunnerJob.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response getJob(@PathParam("jobId") String jobId) {
        ensureEnabled();
        String workspaceId = requestContext.get().getWorkspaceId();
        LocalRunnerJob job = runnerService.getJob(jobId, workspaceId);
        return Response.ok(job).build();
    }

    @GET
    @Path("/jobs/{jobId}/logs")
    @Operation(operationId = "getJobLogs", summary = "Get local runner job logs", description = "Get log entries for a local runner job", responses = {
            @ApiResponse(responseCode = "200", description = "Log entries"),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response getJobLogs(@PathParam("jobId") String jobId,
            @QueryParam("offset") @DefaultValue("0") @Min(0) int offset) {
        ensureEnabled();
        String workspaceId = requestContext.get().getWorkspaceId();
        List<LogEntry> logs = runnerService.getJobLogs(jobId, offset, workspaceId);
        return Response.ok(logs).build();
    }

    @POST
    @Path("/jobs/{jobId}/logs")
    @RateLimited
    @Operation(operationId = "appendJobLogs", summary = "Append local runner job logs", description = "Append log entries for a running local runner job", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
            @ApiResponse(responseCode = "400", description = "Bad request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response appendLogs(@PathParam("jobId") String jobId,
            @NotNull @Valid List<@NotNull LogEntry> entries) {
        ensureEnabled();
        String workspaceId = requestContext.get().getWorkspaceId();
        runnerService.appendLogs(jobId, workspaceId, entries);
        return Response.noContent().build();
    }

    @POST
    @Path("/jobs/{jobId}/result")
    @Operation(operationId = "reportJobResult", summary = "Report local runner job result", description = "Report local runner job completion or failure", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
            @ApiResponse(responseCode = "400", description = "Bad request", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response reportResult(@PathParam("jobId") String jobId,
            @RequestBody(content = @Content(schema = @Schema(implementation = JobResultRequest.class))) @NotNull @Valid JobResultRequest result) {
        ensureEnabled();
        String workspaceId = requestContext.get().getWorkspaceId();
        runnerService.reportResult(jobId, workspaceId, result);
        return Response.noContent().build();
    }

    @POST
    @Path("/jobs/{jobId}/cancel")
    @Operation(operationId = "cancelJob", summary = "Cancel local runner job", description = "Cancel a pending or running local runner job", responses = {
            @ApiResponse(responseCode = "204", description = "No content"),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response cancelJob(@PathParam("jobId") String jobId) {
        ensureEnabled();
        String workspaceId = requestContext.get().getWorkspaceId();
        runnerService.cancelJob(jobId, workspaceId);
        return Response.noContent().build();
    }

    private void ensureEnabled() {
        if (!runnerConfig.isEnabled()) {
            throw new NotFoundException();
        }
    }
}
