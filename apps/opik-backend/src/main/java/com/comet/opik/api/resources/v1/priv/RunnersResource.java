package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.runner.ConnectRequest;
import com.comet.opik.api.runner.ConnectResponse;
import com.comet.opik.api.runner.CreateJobRequest;
import com.comet.opik.api.runner.HeartbeatResponse;
import com.comet.opik.api.runner.JobResultRequest;
import com.comet.opik.api.runner.LogEntry;
import com.comet.opik.api.runner.PairResponse;
import com.comet.opik.api.runner.Runner;
import com.comet.opik.api.runner.RunnerJob;
import com.comet.opik.domain.RunnerService;
import com.comet.opik.infrastructure.RunnerConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
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
    @Operation(operationId = "generatePairingCode", summary = "Generate a pairing code for the current workspace")
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
    @Operation(operationId = "connectRunner", summary = "Exchange a pairing code or API key for runner credentials")
    public Response connect(@NotNull @Valid ConnectRequest request) {
        ensureEnabled();
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        ConnectResponse response = runnerService.connect(workspaceId, userName, request);
        return Response.ok(response).build();
    }

    @GET
    @Operation(operationId = "listRunners", summary = "List all runners in the current workspace")
    public Response listRunners() {
        ensureEnabled();
        String workspaceId = requestContext.get().getWorkspaceId();
        List<Runner> runners = runnerService.listRunners(workspaceId);
        return Response.ok(runners).build();
    }

    @GET
    @Path("/{runnerId}")
    @Operation(operationId = "getRunner", summary = "Get a single runner with its registered agents")
    public Response getRunner(@PathParam("runnerId") String runnerId) {
        ensureEnabled();
        String workspaceId = requestContext.get().getWorkspaceId();
        Runner runner = runnerService.getRunner(workspaceId, runnerId);
        return Response.ok(runner).build();
    }

    @PUT
    @Path("/{runnerId}/agents")
    @RateLimited
    @Operation(operationId = "registerAgents", summary = "Register or update the runner's agent list")
    public Response registerAgents(@PathParam("runnerId") String runnerId,
            @NotNull Map<String, JsonNode> agents) {
        ensureEnabled();
        String workspaceId = requestContext.get().getWorkspaceId();
        runnerService.registerAgents(runnerId, workspaceId, agents);
        return Response.noContent().build();
    }

    @POST
    @Path("/{runnerId}/heartbeat")
    @RateLimited
    @Operation(operationId = "heartbeat", summary = "Refresh runner heartbeat")
    public Response heartbeat(@PathParam("runnerId") String runnerId) {
        ensureEnabled();
        String workspaceId = requestContext.get().getWorkspaceId();
        HeartbeatResponse response = runnerService.heartbeat(runnerId, workspaceId);
        return Response.ok(response).build();
    }

    @POST
    @Path("/jobs")
    @Operation(operationId = "createJob", summary = "Create a job and enqueue it for execution")
    @RateLimited
    public Response createJob(@NotNull @Valid CreateJobRequest request) {
        ensureEnabled();
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        RunnerJob job = runnerService.createJob(workspaceId, userName, request);
        return Response.status(Response.Status.CREATED).entity(job).build();
    }

    @GET
    @Path("/{runnerId}/jobs")
    @Operation(operationId = "listJobs", summary = "List jobs for a runner")
    public Response listJobs(@PathParam("runnerId") String runnerId,
            @QueryParam("project") String project,
            @QueryParam("page") @DefaultValue("0") @Min(0) int page,
            @QueryParam("size") @DefaultValue("25") @Min(1) int size) {
        ensureEnabled();
        String workspaceId = requestContext.get().getWorkspaceId();
        RunnerJob.RunnerJobPage jobPage = runnerService.listJobs(runnerId, project, workspaceId, page, size);
        return Response.ok(jobPage).build();
    }

    @POST
    @Path("/{runnerId}/jobs/next")
    @Operation(operationId = "nextJob", summary = "Long-poll for the next pending job")
    public void nextJob(@PathParam("runnerId") String runnerId,
            @Suspended AsyncResponse asyncResponse) {
        ensureEnabled();
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
    @Operation(operationId = "getJob", summary = "Get a single job's status and results")
    public Response getJob(@PathParam("jobId") String jobId) {
        ensureEnabled();
        String workspaceId = requestContext.get().getWorkspaceId();
        RunnerJob job = runnerService.getJob(jobId, workspaceId);
        return Response.ok(job).build();
    }

    @GET
    @Path("/jobs/{jobId}/logs")
    @Operation(operationId = "getJobLogs", summary = "Get log entries for a job")
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
    @Operation(operationId = "appendJobLogs", summary = "Append log entries for a running job")
    public Response appendLogs(@PathParam("jobId") String jobId,
            @NotNull @Valid List<LogEntry> entries) {
        ensureEnabled();
        String workspaceId = requestContext.get().getWorkspaceId();
        runnerService.appendLogs(jobId, workspaceId, entries);
        return Response.noContent().build();
    }

    @POST
    @Path("/jobs/{jobId}/result")
    @Operation(operationId = "reportJobResult", summary = "Report job completion or failure")
    public Response reportResult(@PathParam("jobId") String jobId,
            @NotNull @Valid JobResultRequest result) {
        ensureEnabled();
        String workspaceId = requestContext.get().getWorkspaceId();
        runnerService.reportResult(jobId, workspaceId, result);
        return Response.noContent().build();
    }

    @POST
    @Path("/jobs/{jobId}/cancel")
    @Operation(operationId = "cancelJob", summary = "Cancel a pending or running job")
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
