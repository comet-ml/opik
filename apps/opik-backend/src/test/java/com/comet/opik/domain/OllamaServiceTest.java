package com.comet.opik.domain;

import com.comet.opik.api.OllamaConnectionTestResponse;
import com.comet.opik.api.OllamaModel;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Ollama Service Test")
class OllamaServiceTest {

    private static WireMockServer wireMockServer;
    private static Client httpClient;
    private static OllamaService ollamaService;
    private static String baseUrl;

    @BeforeAll
    static void beforeAll() {
        wireMockServer = new WireMockServer(0); // Random port
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());

        baseUrl = "http://localhost:" + wireMockServer.port();
        httpClient = ClientBuilder.newClient();

        ollamaService = new OllamaService(httpClient);
    }

    @AfterAll
    static void afterAll() {
        if (httpClient != null) {
            httpClient.close();
        }
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @BeforeEach
    void setUp() {
        wireMockServer.resetAll();
    }

    @Test
    @DisplayName("Should successfully test connection to Ollama instance")
    void testConnection__success() {
        // Given
        String versionResponse = "{\"version\":\"0.1.27\"}";
        wireMockServer.stubFor(get(urlEqualTo("/api/version"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(versionResponse)));

        // When
        OllamaConnectionTestResponse response = ollamaService.testConnection(baseUrl);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.connected()).isTrue();
        assertThat(response.version()).isEqualTo("0.1.27");
        assertThat(response.errorMessage()).isNull();
    }

    @Test
    @DisplayName("Should handle connection failure with HTTP error")
    void testConnection__httpError() {
        // Given
        wireMockServer.stubFor(get(urlEqualTo("/api/version"))
                .willReturn(aResponse()
                        .withStatus(404)));

        // When
        OllamaConnectionTestResponse response = ollamaService.testConnection(baseUrl);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.connected()).isFalse();
        assertThat(response.version()).isNull();
        assertThat(response.errorMessage()).contains("Failed to connect to Ollama: HTTP 404");
    }

    @Test
    @DisplayName("Should handle connection failure when Ollama is unreachable")
    void testConnection__unreachable() {
        // Given - Use a URL that will fail to connect
        String unreachableUrl = "http://localhost:99999";

        // When
        OllamaConnectionTestResponse response = ollamaService.testConnection(unreachableUrl);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.connected()).isFalse();
        assertThat(response.version()).isNull();
        assertThat(response.errorMessage()).isNotNull();
        assertThat(response.errorMessage()).contains("Failed to connect to Ollama");
    }

    @Test
    @DisplayName("Should normalize URL by removing trailing slash")
    void testConnection__normalizeUrl() {
        // Given
        String versionResponse = "{\"version\":\"0.1.27\"}";
        wireMockServer.stubFor(get(urlEqualTo("/api/version"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(versionResponse)));

        // When - URL with trailing slash
        OllamaConnectionTestResponse response = ollamaService.testConnection(baseUrl + "/");

        // Then
        assertThat(response.connected()).isTrue();
        assertThat(response.version()).isEqualTo("0.1.27");
    }

    @Test
    @DisplayName("Should reject Ollama version incompatible with API v1")
    void testConnection__incompatibleVersion() {
        // Given - Old version before API v1 (0.0.9)
        String versionResponse = "{\"version\":\"0.0.9\"}";
        wireMockServer.stubFor(get(urlEqualTo("/api/version"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(versionResponse)));

        // When
        OllamaConnectionTestResponse response = ollamaService.testConnection(baseUrl);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.connected()).isFalse();
        assertThat(response.version()).isEqualTo("0.0.9");
        assertThat(response.errorMessage()).isNotNull();
        assertThat(response.errorMessage()).contains("not compatible with API v1");
        assertThat(response.errorMessage()).contains("0.1.0 or higher");
    }

    @Test
    @DisplayName("Should accept Ollama version 1.0.0 and higher")
    void testConnection__v1AndHigher() {
        // Given - Version 1.0.0
        String versionResponse = "{\"version\":\"1.0.0\"}";
        wireMockServer.stubFor(get(urlEqualTo("/api/version"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(versionResponse)));

        // When
        OllamaConnectionTestResponse response = ollamaService.testConnection(baseUrl);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.connected()).isTrue();
        assertThat(response.version()).isEqualTo("1.0.0");
    }

    @Test
    @DisplayName("Should handle invalid version format gracefully")
    void testConnection__invalidVersionFormat() {
        // Given - Invalid version format
        String versionResponse = "{\"version\":\"invalid-version\"}";
        wireMockServer.stubFor(get(urlEqualTo("/api/version"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(versionResponse)));

        // When
        OllamaConnectionTestResponse response = ollamaService.testConnection(baseUrl);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.connected()).isFalse();
        assertThat(response.version()).isEqualTo("invalid-version");
        assertThat(response.errorMessage()).isNotNull();
        assertThat(response.errorMessage()).contains("not compatible with API v1");
    }

    @Test
    @DisplayName("Should successfully list models from Ollama instance")
    void listModels__success() {
        // Given - Stub version check first (listModels now validates version)
        String versionResponse = "{\"version\":\"0.1.27\"}";
        wireMockServer.stubFor(get(urlEqualTo("/api/version"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(versionResponse)));

        String modelsResponse = """
                {
                  "models": [
                    {
                      "name": "llama2:latest",
                      "size": 3826793677,
                      "digest": "sha256:78e26419b4469263f75331927a00a0284ef6544c1975b826b15abdaef17bb962",
                      "modified_at": "2024-01-15T10:30:00Z"
                    },
                    {
                      "name": "codellama:13b",
                      "size": 7365960935,
                      "digest": "sha256:9f438cb9cd581fc025612d27f7c1a6669ff83a8bb0ed86c94fcf4c5440555697",
                      "modified_at": "2024-01-16T14:20:00Z"
                    }
                  ]
                }
                """;

        wireMockServer.stubFor(get(urlEqualTo("/api/tags"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(modelsResponse)));

        // When
        List<OllamaModel> models = ollamaService.listModels(baseUrl);

        // Then
        assertThat(models).isNotNull();
        assertThat(models).hasSize(2);

        OllamaModel firstModel = models.get(0);
        assertThat(firstModel.name()).isEqualTo("llama2:latest");
        assertThat(firstModel.size()).isEqualTo(3826793677L);
        assertThat(firstModel.digest())
                .isEqualTo("sha256:78e26419b4469263f75331927a00a0284ef6544c1975b826b15abdaef17bb962");
        assertThat(firstModel.modifiedAt()).isNotNull();

        OllamaModel secondModel = models.get(1);
        assertThat(secondModel.name()).isEqualTo("codellama:13b");
        assertThat(secondModel.size()).isEqualTo(7365960935L);
    }

    @Test
    @DisplayName("Should return empty list when model listing fails")
    void listModels__httpError() {
        // Given - Stub version check first
        String versionResponse = "{\"version\":\"0.1.27\"}";
        wireMockServer.stubFor(get(urlEqualTo("/api/version"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(versionResponse)));

        wireMockServer.stubFor(get(urlEqualTo("/api/tags"))
                .willReturn(aResponse()
                        .withStatus(500)));

        // When
        List<OllamaModel> models = ollamaService.listModels(baseUrl);

        // Then
        assertThat(models).isNotNull();
        assertThat(models).isEmpty();
    }

    @Test
    @DisplayName("Should return empty list when Ollama is unreachable")
    void listModels__unreachable() {
        // Given - Use a URL that will fail to connect
        String unreachableUrl = "http://localhost:99999";

        // When
        List<OllamaModel> models = ollamaService.listModels(unreachableUrl);

        // Then
        assertThat(models).isNotNull();
        assertThat(models).isEmpty();
    }

    @Test
    @DisplayName("Should handle empty model list")
    void listModels__emptyList() {
        // Given - Stub version check first
        String versionResponse = "{\"version\":\"0.1.27\"}";
        wireMockServer.stubFor(get(urlEqualTo("/api/version"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(versionResponse)));

        String modelsResponse = "{\"models\": []}";
        wireMockServer.stubFor(get(urlEqualTo("/api/tags"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(modelsResponse)));

        // When
        List<OllamaModel> models = ollamaService.listModels(baseUrl);

        // Then
        assertThat(models).isNotNull();
        assertThat(models).isEmpty();
    }

    @Test
    @DisplayName("Should return empty list when Ollama version is incompatible with API v1")
    void listModels__incompatibleVersion() {
        // Given - Old version before API v1
        String versionResponse = "{\"version\":\"0.0.9\"}";
        wireMockServer.stubFor(get(urlEqualTo("/api/version"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(versionResponse)));

        // When
        List<OllamaModel> models = ollamaService.listModels(baseUrl);

        // Then
        assertThat(models).isNotNull();
        assertThat(models).isEmpty();
    }
}
