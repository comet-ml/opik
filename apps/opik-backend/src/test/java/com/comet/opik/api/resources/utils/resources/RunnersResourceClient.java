package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.runner.ConnectRequest;
import com.comet.opik.api.runner.CreateJobRequest;
import com.comet.opik.api.runner.HeartbeatResponse;
import com.comet.opik.api.runner.JobResultRequest;
import com.comet.opik.api.runner.LocalRunner;
import com.comet.opik.api.runner.LocalRunnerJob;
import com.comet.opik.api.runner.LogEntry;
import com.comet.opik.api.runner.PairResponse;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.apache.http.HttpStatus;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
public class RunnersResourceClient {

    private static final String RESOURCE_PATH = "%s/v1/private/runners";

    private final ClientSupport client;
    private final String baseURI;

    public PairResponse generatePairingCode(String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("pairs")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(""))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);
            return response.readEntity(PairResponse.class);
        }
    }

    public UUID connect(ConnectRequest request, String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("connections")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);
            String location = response.getHeaderString("Location");
            assertThat(location).isNotBlank();
            return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
        }
    }

    public LocalRunner.LocalRunnerPage listRunners(String apiKey, String workspaceName) {
        return listRunners(0, 25, apiKey, workspaceName);
    }

    public LocalRunner.LocalRunnerPage listRunners(int page, int size, String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .queryParam("page", page)
                .queryParam("size", size)
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(LocalRunner.LocalRunnerPage.class);
        }
    }

    public LocalRunner getRunner(UUID runnerId, String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(runnerId.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(LocalRunner.class);
        }
    }

    public void registerAgents(UUID runnerId, Map<String, LocalRunner.Agent> agents,
            String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(runnerId.toString())
                .path("agents")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .put(Entity.json(agents))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }

    public HeartbeatResponse heartbeat(UUID runnerId, String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(runnerId.toString())
                .path("heartbeats")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(""))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(HeartbeatResponse.class);
        }
    }

    public UUID createJob(CreateJobRequest request, String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("jobs")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);
            String location = response.getHeaderString("Location");
            assertThat(location).isNotBlank();
            return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
        }
    }

    public LocalRunnerJob getJob(UUID jobId, String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("jobs")
                .path(jobId.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(LocalRunnerJob.class);
        }
    }

    public LocalRunnerJob.LocalRunnerJobPage listJobs(UUID runnerId, String project,
            int page, int size, String apiKey, String workspaceName) {
        var target = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(runnerId.toString())
                .path("jobs")
                .queryParam("page", page)
                .queryParam("size", size);
        if (project != null) {
            target = target.queryParam("project", project);
        }
        try (var response = target.request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(LocalRunnerJob.LocalRunnerJobPage.class);
        }
    }

    public List<LogEntry> getJobLogs(UUID jobId, int offset, String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("jobs")
                .path(jobId.toString())
                .path("logs")
                .queryParam("offset", offset)
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(new GenericType<>() {
            });
        }
    }

    public void appendLogs(UUID jobId, List<LogEntry> entries, String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("jobs")
                .path(jobId.toString())
                .path("logs")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(entries))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }

    public void reportResult(UUID jobId, JobResultRequest result, String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("jobs")
                .path(jobId.toString())
                .path("results")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(result))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }

    public void cancelJob(UUID jobId, String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("jobs")
                .path(jobId.toString())
                .path("cancel")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(""))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }

    public Response callNextJob(UUID runnerId, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path(runnerId.toString())
                .path("jobs")
                .path("next")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(""));
    }
}
