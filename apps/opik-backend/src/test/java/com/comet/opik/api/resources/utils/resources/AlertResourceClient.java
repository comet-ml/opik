package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.Alert;
import com.comet.opik.api.AlertType;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.WebhookExamples;
import com.comet.opik.api.WebhookTestResult;
import com.comet.opik.api.filter.AlertFilter;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.utils.JsonUtils;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpStatus;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.TestUtils.toURLEncodedQueryParam;
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

    public Alert.AlertPage findAlerts(String apiKey, String workspaceName, int page, int size,
            List<SortingField> sortingFields, List<AlertFilter> filters, int expectedStatus) {
        WebTarget target = client.target(RESOURCE_PATH.formatted(baseURI));

        if (page > 1) {
            target = target.queryParam("page", page);
        }

        target = target.queryParam("size", size);

        if (CollectionUtils.isNotEmpty(sortingFields)) {
            target = target.queryParam("sorting",
                    URLEncoder.encode(JsonUtils.writeValueAsString(sortingFields), StandardCharsets.UTF_8));
        }

        if (CollectionUtils.isNotEmpty(filters)) {
            target = target.queryParam("filters", toURLEncodedQueryParam(filters));
        }

        try (var response = target
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);

            if (expectedStatus == HttpStatus.SC_OK) {
                return response.readEntity(Alert.AlertPage.class);
            }

            return null;
        }
    }

    public WebhookTestResult testWebhook(Alert alert, String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("webhooks")
                .path("tests")
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(alert))) {
            // Then
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);

            var result = response.readEntity(WebhookTestResult.class);
            assertThat(result.requestBody()).isNotNull();

            return result;
        }
    }

    public WebhookExamples getWebhookExamples(String apiKey, String workspaceName, AlertType alertType,
            int expectedStatus) {
        WebTarget target = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("webhooks")
                .path("examples");

        if (alertType != null) {
            target = target.queryParam("alert_type", alertType.getValue());
        }

        try (var response = target
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);

            if (expectedStatus == HttpStatus.SC_OK) {
                return response.readEntity(WebhookExamples.class);
            }

            return null;
        }
    }
}
