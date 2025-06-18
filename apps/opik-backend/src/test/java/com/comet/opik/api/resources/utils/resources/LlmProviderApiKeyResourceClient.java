package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.Page;
import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.api.ProviderApiKeyUpdate;
import com.comet.opik.api.resources.utils.TestUtils;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.hc.core5.http.ContentType;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

public class LlmProviderApiKeyResourceClient {
    private static final String RESOURCE_PATH = "%s/v1/private/llm-provider-key";

    private final ClientSupport client;
    private final String baseURI;

    public LlmProviderApiKeyResourceClient(ClientSupport client) {
        this.client = client;
        this.baseURI = TestUtils.getBaseUrl(client);
    }

    public ProviderApiKey createProviderApiKey(
            ProviderApiKey providerApiKey, String apiKey, String workspaceName, int expectedStatus) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(providerApiKey))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
            if (expectedStatus == 201) {
                return providerApiKey.toBuilder()
                        .id(TestUtils.getIdFromLocation(actualResponse.getLocation()))
                        .build();
            }

            return null;
        }
    }

    public Response createProviderApiKey(
            String body, String apiKey, String workspaceName, int expectedStatus) {

        var request = Entity.entity(body, ContentType.APPLICATION_JSON.toString());

        var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(request);

        assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);

        return actualResponse;
    }

    public ProviderApiKey createProviderApiKey(
            String providerApiKey, LlmProvider llmProvider, String apiKey, String workspaceName, int expectedStatus) {
        ProviderApiKey body = ProviderApiKey.builder().provider(llmProvider).apiKey(providerApiKey).build();

        return createProviderApiKey(body, apiKey, workspaceName, expectedStatus);
    }

    public void updateProviderApiKey(UUID id, ProviderApiKeyUpdate providerApiKeyUpdate, String apiKey,
            String workspaceName,
            int expectedStatus) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(id.toString())
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .method(HttpMethod.PATCH, Entity.json(providerApiKeyUpdate))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
        }
    }

    public void batchDeleteProviderApiKey(Set<UUID> ids, String apiKey, String workspaceName) {

        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("delete")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(new BatchDelete(ids)))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
        }
    }

    public ProviderApiKey getById(UUID id, String workspaceName, String apiKey, int expectedStatus) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
            if (expectedStatus == 200) {
                assertThat(actualResponse.hasEntity()).isTrue();

                return actualResponse.readEntity(ProviderApiKey.class);
            }

            return null;
        }
    }

    public Page<ProviderApiKey> getAll(String workspaceName, String apiKey) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            assertThat(actualResponse.hasEntity()).isTrue();

            var actualEntity = actualResponse.readEntity(ProviderApiKey.ProviderApiKeyPage.class);

            return actualEntity;
        }
    }
}
