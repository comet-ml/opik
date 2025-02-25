package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.AutomationRuleEvaluator;
import com.comet.opik.api.AutomationRuleEvaluatorUpdate;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.resources.utils.TestUtils;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.apache.http.HttpStatus;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.util.UUID;

import static com.comet.opik.api.LogItem.LogPage;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
public class AutomationRuleEvaluatorResourceClient {

    private static final String RESOURCE_PATH = "%s/v1/private/automations/evaluators/";

    private final ClientSupport client;
    private final String baseURI;

    public UUID createEvaluator(AutomationRuleEvaluator<?> evaluator, String workspaceName, String apiKey) {
        try (var actualResponse = createEvaluator(evaluator, workspaceName, apiKey, HttpStatus.SC_CREATED)) {
            assertThat(actualResponse.hasEntity()).isFalse();
            return TestUtils.getIdFromLocation(actualResponse.getLocation());
        }
    }

    public Response createEvaluator(
            AutomationRuleEvaluator<?> evaluator, String workspaceName, String apiKey, int expectedStatus) {
        var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(evaluator));

        assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);

        return actualResponse;
    }

    public Response getEvaluator(UUID id, UUID projectId, String workspaceName, String apiKey, int expectedStatus) {
        var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(id.toString())
                .queryParam("project_id", projectId)
                .request()
                .header(WORKSPACE_HEADER, workspaceName)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .get();

        assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);

        return actualResponse;
    }

    public Response findEvaluator(
            UUID projectId,
            String name,
            Integer page,
            Integer size,
            String workspaceName,
            String apiKey,
            int expectedStatus) {
        var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .queryParam("project_id", projectId)
                .queryParam("name", name)
                .queryParam("page", page)
                .queryParam("size", size)
                .request()
                .header(WORKSPACE_HEADER, workspaceName)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .get();

        assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);

        return actualResponse;
    }

    public Response updateEvaluator(
            UUID evaluatorId,
            String workspaceName,
            AutomationRuleEvaluatorUpdate<?> updatedEvaluator,
            String apiKey,
            int expectedStatus) {
        var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(evaluatorId.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(WORKSPACE_HEADER, workspaceName)
                .method(HttpMethod.PATCH, Entity.json(updatedEvaluator));
        assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
        return actualResponse;
    }

    public Response delete(
            UUID projectId, String workspaceName, String apiKey, BatchDelete request, int expectedStatus) {
        var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("delete")
                .queryParam("project_id", projectId)
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request));
        assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
        return actualResponse;
    }

    public LogPage getLogs(UUID evaluatorId, String workspaceName, String apiKey) {
        try (var actualResponse = getLogs(evaluatorId, workspaceName, apiKey, HttpStatus.SC_OK)) {
            assertThat(actualResponse.hasEntity()).isTrue();
            return actualResponse.readEntity(LogPage.class);
        }
    }

    public Response getLogs(UUID evaluatorId, String workspaceName, String apiKey, int expectedStatus) {
        var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(evaluatorId.toString())
                .path("logs")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();

        assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);

        return actualResponse;
    }
}
