package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.OllieReport;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.util.UUID;

@RequiredArgsConstructor
public class ReportsResourceClient {

    private static final String RESOURCE_PATH = "%s/v1/private/projects/%s/reports";

    private final ClientSupport client;
    private final String baseURI;

    public Response generateReport(UUID projectId, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI, projectId) + "/generate")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(""));
    }

    public Response completeReport(UUID projectId, UUID reportId, Object body,
            String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI, projectId) + "/" + reportId + "/complete")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(body));
    }

    public OllieReport.OllieReportPage getReports(UUID projectId, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI, projectId))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .get(OllieReport.OllieReportPage.class);
    }

    public Response getPreference(UUID projectId, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI, projectId) + "/preferences")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .get();
    }

    public Response updatePreference(UUID projectId, Object body, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI, projectId) + "/preferences")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .put(Entity.json(body));
    }
}
