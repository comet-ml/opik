package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.ReportFailure;
import com.comet.opik.api.resources.utils.TestUtils;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.hc.core5.http.HttpStatus;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.util.UUID;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

public class ReportFailureResourceClient {

    private static final String PATH = "%s/v1/private/report-failures";

    private final ClientSupport client;
    private final String baseURI;

    public ReportFailureResourceClient(ClientSupport client) {
        this.client = client;
        this.baseURI = TestUtils.getBaseUrl(client);
    }

    public void create(ReportFailure failure, String apiKey, String workspaceName, int expectedStatus) {
        try (var response = client.target(PATH.formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(failure))) {

            assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
        }
    }

    // Raw JSON body so tests can send values the typed DTO can't express (e.g. an unknown enum `type`).
    public void createRaw(String jsonBody, String apiKey, String workspaceName, int expectedStatus) {
        try (var response = client.target(PATH.formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.entity(jsonBody, MediaType.APPLICATION_JSON_TYPE))) {

            assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
        }
    }

    public ReportFailure.ReportFailurePage find(String type, UUID projectId, String apiKey, String workspaceName) {
        try (Response response = client.target(PATH.formatted(baseURI))
                .queryParam("type", type)
                .queryParam("project_id", projectId)
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(ReportFailure.ReportFailurePage.class);
        }
    }
}
