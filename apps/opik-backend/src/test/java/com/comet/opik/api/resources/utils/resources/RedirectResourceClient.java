package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.resources.utils.TestUtils;
import org.glassfish.jersey.client.ClientProperties;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.util.UUID;

import static com.comet.opik.infrastructure.auth.RequestContext.SESSION_COOKIE;
import static org.assertj.core.api.Assertions.assertThat;

public class RedirectResourceClient {
    private static final String RESOURCE_PATH = "%s/v1/session/redirect";

    private final ClientSupport client;
    private final String baseURI;

    public RedirectResourceClient(ClientSupport client) {
        this.client = client;
        this.baseURI = TestUtils.getBaseUrl(client);
    }

    public String projectsRedirect(UUID traceId, String sessionToken, String workspaceName, String path,
            int expectedStatus) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .property(ClientProperties.FOLLOW_REDIRECTS, false)
                .path("projects")
                .queryParam("trace_id", traceId)
                .queryParam("workspace_name", workspaceName)
                .queryParam("path", path)
                .request()
                .cookie(SESSION_COOKIE, sessionToken)
                .get()) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
            if (expectedStatus == 303) {
                return actualResponse.getHeaderString("Location");
            }

            return null;
        }
    }

    public String datasetsRedirect(UUID datasetId, String sessionToken, String workspaceName, String path,
            int expectedStatus) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .property(ClientProperties.FOLLOW_REDIRECTS, false)
                .path("datasets")
                .queryParam("dataset_id", datasetId)
                .queryParam("workspace_name", workspaceName)
                .queryParam("path", path)
                .request()
                .cookie(SESSION_COOKIE, sessionToken)
                .get()) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
            if (expectedStatus == 303) {
                return actualResponse.getHeaderString("Location");
            }

            return null;
        }
    }

    public String experimentsRedirect(UUID datasetId, UUID experimentId, String sessionToken, String workspaceName,
            String path,
            int expectedStatus) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .property(ClientProperties.FOLLOW_REDIRECTS, false)
                .path("experiments")
                .queryParam("dataset_id", datasetId)
                .queryParam("experiment_id", experimentId)
                .queryParam("workspace_name", workspaceName)
                .queryParam("path", path)
                .request()
                .cookie(SESSION_COOKIE, sessionToken)
                .get()) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
            if (expectedStatus == 303) {
                return actualResponse.getHeaderString("Location");
            }

            return null;
        }
    }

    public String optimizationsRedirect(UUID datasetId, UUID optimizationId, String sessionToken, String workspaceName,
            String path,
            int expectedStatus) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .property(ClientProperties.FOLLOW_REDIRECTS, false)
                .path("optimizations")
                .queryParam("dataset_id", datasetId)
                .queryParam("optimization_id", optimizationId)
                .queryParam("workspace_name", workspaceName)
                .queryParam("path", path)
                .request()
                .cookie(SESSION_COOKIE, sessionToken)
                .get()) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
            if (expectedStatus == 303) {
                return actualResponse.getHeaderString("Location");
            }

            return null;
        }
    }
}
