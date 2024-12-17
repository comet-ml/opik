package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.Page;
import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.api.ProviderApiKeyUpdate;
import com.comet.opik.api.resources.utils.TestUtils;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import uk.co.jemos.podam.api.PodamUtils;

import java.util.UUID;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

public class LlmProviderApiKeyResourceClient {
    private static final String RESOURCE_PATH = "%s/v1/private/llm-provider-key";

    private final ClientSupport client;
    private final String baseURI;

    public LlmProviderApiKeyResourceClient(ClientSupport client) {
        this.client = client;
        this.baseURI = "http://localhost:%d".formatted(client.getPort());
    }

    public ProviderApiKey createProviderApiKey(
            String providerApiKey, String apiKey, String workspaceName, int expectedStatus) {
        return createProviderApiKey(providerApiKey, randomLlmProvider(), apiKey, workspaceName, expectedStatus);
    }

    public ProviderApiKey createProviderApiKey(
            String providerApiKey, LlmProvider llmProvider, String apiKey, String workspaceName, int expectedStatus) {
        ProviderApiKey body = ProviderApiKey.builder().provider(llmProvider).apiKey(providerApiKey).build();
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(body))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
            if (expectedStatus == 201) {
                return body.toBuilder()
                        .id(TestUtils.getIdFromLocation(actualResponse.getLocation()))
                        .build();
            }

            return null;
        }
    }

    public void updateProviderApiKey(UUID id, String providerApiKey, String apiKey, String workspaceName,
            int expectedStatus) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(id.toString())
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .method(HttpMethod.PATCH, Entity.json(ProviderApiKeyUpdate.builder().apiKey(providerApiKey).build()))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
        }
    }

    public void deleteProviderApiKey(UUID id, String apiKey, String workspaceName, int expectedStatus) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .delete()) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
        }
    }

    public ProviderApiKey getById(UUID id, String workspaceName, String apiKey) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            assertThat(actualResponse.hasEntity()).isTrue();

            var actualEntity = actualResponse.readEntity(ProviderApiKey.class);
            assertThat(actualEntity.apiKey()).isBlank();

            return actualEntity;
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
            actualEntity.content().forEach(providerApiKey -> assertThat(providerApiKey.apiKey()).isBlank());

            return actualEntity;
        }
    }

    public LlmProvider randomLlmProvider() {
        return LlmProvider.values()[PodamUtils.getIntegerInRange(0, LlmProvider.values().length - 1)];
    }
}
