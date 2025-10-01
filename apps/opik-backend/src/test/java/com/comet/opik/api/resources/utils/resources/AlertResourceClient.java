package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.Alert;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.resources.utils.TestUtils;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpStatus;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.util.UUID;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

public class AlertResourceClient {

    private static final String RESOURCE_PATH = "%s/v1/private/alerts";

    private final ClientSupport client;
    private final String baseURI;

    public AlertResourceClient(ClientSupport client) {
        this.client = client;
        this.baseURI = TestUtils.getBaseUrl(client);
    }

    public UUID createAlert(Alert alert, String apiKey, String workspaceName, int expectedStatus) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(alert))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);

            if (expectedStatus == HttpStatus.SC_CREATED) {
                assertThat(actualResponse.hasEntity()).isFalse();
                assertThat(actualResponse.getLocation()).isNotNull();

                return TestUtils.getIdFromLocation(actualResponse.getLocation());
            }

            return null;
        }
    }

    public Response createAlertWithResponse(Alert alert, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(alert));
    }

    public Response createAlertWithResponse(String body, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.entity(body, ContentType.APPLICATION_JSON.toString()));
    }

    public Alert getAlertById(UUID id, String apiKey, String workspaceName, int expectedStatus) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(id.toString())
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
            if (expectedStatus == HttpStatus.SC_OK) {
                return response.readEntity(Alert.class);
            }

            return null;
        }
    }

    public void updateAlert(UUID id, Alert alert, String apiKey, String workspaceName, int expectedStatus) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(id.toString())
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .put(Entity.json(alert))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
        }
    }

    public void deleteAlertBatch(BatchDelete batchDelete, String apiKey, String workspaceName, int expectedStatus) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("delete")
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(batchDelete))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
        }
    }
}
