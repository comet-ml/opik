package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.ManualEvaluationRequest;
import com.comet.opik.api.ManualEvaluationResponse;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.apache.http.HttpStatus;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.util.UUID;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

public class ManualEvaluationResourceClient {

    private static final String TRACES_RESOURCE_PATH = "%s/v1/private/manual-evaluation/traces";
    private static final String THREADS_RESOURCE_PATH = "%s/v1/private/manual-evaluation/threads";
    private static final String SPANS_RESOURCE_PATH = "%s/v1/private/manual-evaluation/spans";

    private final ClientSupport client;
    private final String baseURI;

    public ManualEvaluationResourceClient(ClientSupport client, String baseURI) {
        this.client = client;
        this.baseURI = baseURI;
    }

    public ManualEvaluationResponse evaluateTraces(UUID projectId, ManualEvaluationRequest request, String apiKey,
            String workspaceName) {
        try (var response = evaluateTraces(projectId, request, apiKey, workspaceName, HttpStatus.SC_ACCEPTED)) {
            return response.readEntity(ManualEvaluationResponse.class);
        }
    }

    public Response evaluateTraces(UUID projectId, ManualEvaluationRequest request, String apiKey,
            String workspaceName, int expectedStatus) {
        var response = callEvaluateTraces(projectId, request, apiKey, workspaceName);

        assertThat(response.getStatus()).isEqualTo(expectedStatus);

        return response;
    }

    public Response callEvaluateTraces(UUID projectId, ManualEvaluationRequest request, String apiKey,
            String workspaceName) {
        return client.target(TRACES_RESOURCE_PATH.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request));
    }

    public ManualEvaluationResponse evaluateThreads(UUID projectId, ManualEvaluationRequest request, String apiKey,
            String workspaceName) {
        try (var response = evaluateThreads(projectId, request, apiKey, workspaceName, HttpStatus.SC_ACCEPTED)) {
            return response.readEntity(ManualEvaluationResponse.class);
        }
    }

    public Response evaluateThreads(UUID projectId, ManualEvaluationRequest request, String apiKey,
            String workspaceName, int expectedStatus) {
        var response = callEvaluateThreads(projectId, request, apiKey, workspaceName);

        assertThat(response.getStatus()).isEqualTo(expectedStatus);

        return response;
    }

    public Response callEvaluateThreads(UUID projectId, ManualEvaluationRequest request, String apiKey,
            String workspaceName) {
        return client.target(THREADS_RESOURCE_PATH.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request));
    }

    public ManualEvaluationResponse evaluateSpans(UUID projectId, ManualEvaluationRequest request, String apiKey,
            String workspaceName) {
        try (var response = evaluateSpans(projectId, request, apiKey, workspaceName, HttpStatus.SC_ACCEPTED)) {
            return response.readEntity(ManualEvaluationResponse.class);
        }
    }

    public Response evaluateSpans(UUID projectId, ManualEvaluationRequest request, String apiKey,
            String workspaceName, int expectedStatus) {
        var response = callEvaluateSpans(projectId, request, apiKey, workspaceName);

        assertThat(response.getStatus()).isEqualTo(expectedStatus);

        return response;
    }

    public Response callEvaluateSpans(UUID projectId, ManualEvaluationRequest request, String apiKey,
            String workspaceName) {
        return client.target(SPANS_RESOURCE_PATH.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request));
    }
}
