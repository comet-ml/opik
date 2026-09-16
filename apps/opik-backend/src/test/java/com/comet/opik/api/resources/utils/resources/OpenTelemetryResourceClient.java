package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
public class OpenTelemetryResourceClient {

    private static final String RESOURCE_PATH = "%s/v1/private/otel/v1/traces";

    private final ClientSupport client;
    private final String baseURI;

    /** Exports a batch and asserts the status, reporting the response body when it does not match. */
    public void exportTraces(Entity<?> payload, String mediaType, String projectName, String workspaceName,
            String apiKey, int expectedStatus) {
        try (var response = post(payload, mediaType, projectName, workspaceName, apiKey)) {
            assertStatus(response, expectedStatus);
        }
    }

    /** Exports with a content type but no entity at all, which is the shape a bodiless POST takes. */
    public void exportWithoutBody(String mediaType, String workspaceName, String apiKey, int expectedStatus) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .header(HttpHeaders.CONTENT_TYPE, mediaType)
                .method("POST")) {

            assertStatus(response, expectedStatus);
        }
    }

    private Response post(Entity<?> payload, String mediaType, String projectName, String workspaceName,
            String apiKey) {
        var requestBuilder = client.target(RESOURCE_PATH.formatted(baseURI))
                .request(mediaType)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName);

        if (StringUtils.isNotEmpty(projectName)) {
            requestBuilder.header(RequestContext.PROJECT_NAME, projectName);
        }

        return requestBuilder.post(payload);
    }

    private void assertStatus(Response response, int expectedStatus) {
        var body = response.hasEntity() ? response.readEntity(String.class) : "";

        assertThat(response.getStatusInfo().getStatusCode())
                .as("response body: %s", body)
                .isEqualTo(expectedStatus);
    }
}
