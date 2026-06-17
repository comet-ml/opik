package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.AgentInsightsJob;
import com.comet.opik.api.AgentInsightsJobRequest;
import com.comet.opik.api.AgentInsightsJobUpdate;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.util.UUID;

@RequiredArgsConstructor
public class AgentInsightsJobResourceClient {

    private static final String RESOURCE_PATH = "%s/v1/private/agent-insights/jobs";

    private final ClientSupport client;
    private final String baseURI;

    public Response create(UUID projectId, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(AgentInsightsJobRequest.builder().projectId(projectId).build()));
    }

    public Response createRaw(Object body, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(body));
    }

    public Response get(UUID projectId, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .queryParam("project_id", projectId)
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .get();
    }

    public Response update(UUID projectId, AgentInsightsJob.Status status, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .queryParam("project_id", projectId)
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .method("PATCH", Entity.json(AgentInsightsJobUpdate.builder().status(status).build()));
    }

    public Response trigger(UUID projectId, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI) + "/trigger")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(AgentInsightsJobRequest.builder().projectId(projectId).build()));
    }
}
