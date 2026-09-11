package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.RecentActivity;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.util.UUID;

@RequiredArgsConstructor
public class RecentActivityResourceClient {

    private static final String RESOURCE_PATH = "%s/v1/private/projects/%s/activities";

    private final ClientSupport client;
    private final String baseURI;

    public RecentActivity.RecentActivityPage getActivities(UUID projectId, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI, projectId))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .get(RecentActivity.RecentActivityPage.class);
    }

    public Response callGetActivities(UUID projectId, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI, projectId))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .get();
    }
}
