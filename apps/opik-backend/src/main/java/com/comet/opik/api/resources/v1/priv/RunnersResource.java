package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.runner.ConnectRequest;
import com.comet.opik.api.runner.ConnectResponse;
import com.comet.opik.api.runner.CreateJobRequest;
import com.comet.opik.api.runner.LogEntry;
import com.comet.opik.api.runner.PairResponse;
import com.comet.opik.api.runner.Runner;
import com.comet.opik.api.runner.RunnerJob;
import com.comet.opik.domain.RunnerService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.Inject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Provider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
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

import java.util.List;

@Path("/v1/private/runners")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Runners", description = "Local runner management endpoints")
public class RunnersResource {

    private final @NonNull RunnerService runnerService;
    private final @NonNull Provider<RequestContext> requestContext;

    @POST
    @Path("/pair")
    @Operation(operationId = "generatePairingCode", summary = "Generate a pairing code", description = "Generate a 6-character pairing code for connecting a local runner", responses = {
            @ApiResponse(responseCode = "200", description = "Pairing code generated")
    })
    public Response generatePairingCode() {
        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Generating pairing code for workspace '{}'", workspaceId);

        PairResponse response = runnerService.generatePairingCode(workspaceId);

        return Response.ok(response).build();
    }

    @POST
    @Path("/connect")
    @Operation(operationId = "connectRunner", summary = "Connect a runner", description = "Connect a local runner using a pairing code", responses = {
            @ApiResponse(responseCode = "200", description = "Runner connected"),
            @ApiResponse(responseCode = "404", description = "Invalid or expired pairing code")
    })
    public Response connectRunner(@NotNull @Valid ConnectRequest request) {
        log.info("Runner '{}' connecting with pairing code", request.runnerName());

        ConnectResponse response = runnerService.connect(request);

        log.info("Runner '{}' connected with id '{}'", request.runnerName(), response.runnerId());
        return Response.ok(response).build();
    }

    @GET
    @Operation(operationId = "listRunners", summary = "List connected runners", description = "List all connected runners for the current workspace", responses = {
            @ApiResponse(responseCode = "200", description = "Runners list")
    })
    public Response listRunners() {
        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Listing runners for workspace '{}'", workspaceId);

        List<Runner> runners = runnerService.listRunners(workspaceId);

        return Response.ok(runners).build();
    }

    @GET
    @Path("/{runnerId}")
    @Operation(operationId = "getRunnerById", summary = "Get runner by id", description = "Get runner details including registered agents", responses = {
            @ApiResponse(responseCode = "200", description = "Runner detail"),
            @ApiResponse(responseCode = "404", description = "Runner not found")
    })
    public Response getRunnerById(@PathParam("runnerId") String runnerId) {
        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Getting runner '{}' in workspace '{}'", runnerId, workspaceId);

        Runner runner = runnerService.getRunner(runnerId, workspaceId);

        return Response.ok(runner).build();
    }

    @POST
    @Path("/jobs")
    @Operation(operationId = "createRunnerJob", summary = "Create a runner job", description = "Enqueue a job for execution by a connected runner", responses = {
            @ApiResponse(responseCode = "200", description = "Job created"),
            @ApiResponse(responseCode = "404", description = "No runners available for project")
    })
    public Response createJob(@NotNull @Valid CreateJobRequest request) {
        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Creating job for agent '{}' in project '{}' workspace '{}'",
                request.agentName(), request.project(), workspaceId);

        RunnerJob job = runnerService.createJob(request, workspaceId);

        return Response.ok(job).build();
    }

    @GET
    @Path("/{runnerId}/jobs")
    @Operation(operationId = "listRunnerJobs", summary = "List jobs for a runner", description = "List all jobs for a specific runner session", responses = {
            @ApiResponse(responseCode = "200", description = "Jobs list")
    })
    public Response listJobs(
            @PathParam("runnerId") String runnerId,
            @QueryParam("project") String project) {
        log.info("Listing jobs for runner '{}' project '{}'", runnerId, project);

        List<RunnerJob> jobs = runnerService.listJobs(runnerId, project);

        return Response.ok(jobs).build();
    }

    @GET
    @Path("/jobs/{jobId}")
    @Operation(operationId = "getRunnerJob", summary = "Get job by id", description = "Get a single job status and details", responses = {
            @ApiResponse(responseCode = "200", description = "Job detail"),
            @ApiResponse(responseCode = "404", description = "Job not found")
    })
    public Response getJob(@PathParam("jobId") String jobId) {
        log.info("Getting job '{}'", jobId);

        RunnerJob job = runnerService.getJob(jobId);

        return Response.ok(job).build();
    }

    @GET
    @Path("/jobs/{jobId}/logs")
    @Operation(operationId = "getRunnerJobLogs", summary = "Get job logs", description = "Get streaming log output for a job", responses = {
            @ApiResponse(responseCode = "200", description = "Log entries")
    })
    public Response getJobLogs(
            @PathParam("jobId") String jobId,
            @QueryParam("offset") @DefaultValue("0") int offset) {
        List<LogEntry> logs = runnerService.getJobLogs(jobId, offset);

        return Response.ok(logs).build();
    }
}
