package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.evaluators.AutomationRuleEvaluator;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUpdate;
import com.comet.opik.api.resources.utils.TestUtils;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.apache.http.HttpStatus;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static com.comet.opik.api.LogItem.LogPage;
import static com.comet.opik.infrastructure.auth.RequestContext.SESSION_COOKIE;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
public class AutomationRuleEvaluatorResourceClient {

    private static final String RESOURCE_PATH = "%s/v1/private/automations/evaluators/";

    private final ClientSupport client;
    private final String baseURI;

    public UUID createEvaluator(AutomationRuleEvaluator<?, ?> evaluator, String workspaceName, String apiKey) {
        try (var actualResponse = createEvaluator(evaluator, workspaceName, apiKey, HttpStatus.SC_CREATED)) {
            assertThat(actualResponse.hasEntity()).isFalse();
            return TestUtils.getIdFromLocation(actualResponse.getLocation());
        }
    }

    public Response createEvaluator(
            AutomationRuleEvaluator<?, ?> evaluator, String workspaceName, String apiKey, int expectedStatus) {
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

    public AutomationRuleEvaluator.AutomationRuleEvaluatorPage findEvaluatorPage(
            UUID projectId,
            String name,
            String filters,
            String sorting,
            Integer page,
            Integer size,
            String workspaceName,
            String apiKey) {
        var target = client.target(RESOURCE_PATH.formatted(baseURI));
        if (projectId != null) {
            target = target.queryParam("project_id", projectId);
        }
        if (name != null) {
            target = target.queryParam("name", name);
        }
        if (filters != null) {
            target = target.queryParam("filters", URLEncoder.encode(filters, StandardCharsets.UTF_8));
        }
        if (sorting != null) {
            target = target.queryParam("sorting", URLEncoder.encode(sorting, StandardCharsets.UTF_8));
        }
        if (page != null) {
            target = target.queryParam("page", page);
        }
        if (size != null) {
            target = target.queryParam("size", size);
        }

        var actualResponse = target
                .request()
                .header(WORKSPACE_HEADER, workspaceName)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .get();

        assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);

        return actualResponse.readEntity(AutomationRuleEvaluator.AutomationRuleEvaluatorPage.class);
    }

    public Response updateEvaluator(
            UUID evaluatorId,
            String workspaceName,
            AutomationRuleEvaluatorUpdate<?, ?> updatedEvaluator,
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

    // Session token authentication methods

    public Response createEvaluatorWithSessionToken(
            AutomationRuleEvaluator<?, ?> evaluator, String sessionToken, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .cookie(SESSION_COOKIE, sessionToken)
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(evaluator));
    }

    public Response findEvaluatorWithSessionToken(
            UUID projectId, Integer size, String sessionToken, String workspaceName) {
        var target = client.target(RESOURCE_PATH.formatted(baseURI));
        if (projectId != null) {
            target = target.queryParam("project_id", projectId);
        }
        if (size != null) {
            target = target.queryParam("size", size);
        }
        return target
                .request()
                .cookie(SESSION_COOKIE, sessionToken)
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();
    }

    public Response getEvaluatorWithSessionToken(
            UUID id, UUID projectId, String sessionToken, String workspaceName) {
        var target = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(id.toString());
        if (projectId != null) {
            target = target.queryParam("project_id", projectId);
        }
        return target
                .request()
                .cookie(SESSION_COOKIE, sessionToken)
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();
    }

    public Response updateEvaluatorWithSessionToken(
            UUID evaluatorId, AutomationRuleEvaluatorUpdate<?, ?> updatedEvaluator,
            String sessionToken, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path(evaluatorId.toString())
                .request()
                .cookie(SESSION_COOKIE, sessionToken)
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(WORKSPACE_HEADER, workspaceName)
                .method(HttpMethod.PATCH, Entity.json(updatedEvaluator));
    }

    public Response deleteWithSessionToken(
            UUID projectId, BatchDelete request, String sessionToken, String workspaceName) {
        var target = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("delete");
        if (projectId != null) {
            target = target.queryParam("project_id", projectId);
        }
        return target
                .request()
                .cookie(SESSION_COOKIE, sessionToken)
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request));
    }

    public Response getLogsWithSessionToken(
            UUID evaluatorId, String sessionToken, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path(evaluatorId.toString())
                .path("logs")
                .request()
                .cookie(SESSION_COOKIE, sessionToken)
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();
    }
}
