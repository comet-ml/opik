package com.comet.opik.domain;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.comet.opik.api.OllamaConnectionTestResponse;
import com.comet.opik.api.OllamaModel;
import com.comet.opik.api.resources.utils.TestHttpClientUtils;
import com.comet.opik.infrastructure.llm.customllm.ProviderBaseUrlGuard;
import com.comet.opik.infrastructure.net.DestinationGuard;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Ollama Service Test")
class OllamaServiceTest {

    private static WireMockServer wireMockServer;
    private static OllamaService ollamaService;
    private static String baseUrl;

    @BeforeAll
    static void beforeAll() {
        wireMockServer = new WireMockServer(0); // Random port
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());

        baseUrl = "http://localhost:" + wireMockServer.port();
        var httpClient = TestHttpClientUtils.client();

        // the mock server is on loopback, which strict mode refuses
        ollamaService = new OllamaService(httpClient, ProviderBaseUrlGuard.builder()
                .mode(DestinationGuard.Mode.RELAXED)
                .scheme(DestinationGuard.Scheme.PLAINTEXT_OR_TLS)
                .build());
    }

    @AfterAll
    static void afterAll() {
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
        String version = RandomStringUtils.secure().nextAlphanumeric(5) + "."
                + RandomUtils.secure().randomInt(0, 10) + "."
                + RandomUtils.secure().randomInt(0, 100);
        String versionResponse = "{\"version\":\"" + version + "\"}";
        wireMockServer.stubFor(get(urlEqualTo("/api/version"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(versionResponse)));

        // When
        OllamaConnectionTestResponse response = ollamaService.testConnection(baseUrl, null).block();

        // Then
        assertThat(response).isNotNull();
        assertThat(response.connected()).isTrue();
        assertThat(response.version()).isEqualTo(version);
        assertThat(response.errorMessage()).isNull();
    }

    @Test
    @DisplayName("Should handle connection failure with HTTP error")
    void testConnection__httpError() {
        // Given
        wireMockServer.stubFor(get(urlEqualTo("/api/version"))
                .willReturn(aResponse()
                        .withStatus(404)));
        var expectedResponse = OllamaConnectionTestResponse.builder()
                .connected(false)
                .errorMessage("Failed to connect to Ollama: HTTP 404")
                .build();

        // When
        OllamaConnectionTestResponse actualResponse = ollamaService.testConnection(baseUrl, null).block();

        // Then: the URL the caller gave is not echoed back
        assertThat(actualResponse).isEqualTo(expectedResponse);
    }

    /** The service's log events while {@code call} runs, debug included. */
    private static List<ILoggingEvent> eventsOf(Runnable call) {
        var logger = (Logger) LoggerFactory.getLogger(OllamaService.class);
        var appender = new ListAppender<ILoggingEvent>();
        var previousLevel = logger.getLevel();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            call.run();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }
        return List.copyOf(appender.list);
    }

    private static List<String> logsOf(Runnable call) {
        return eventsOf(call).stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** Each event as logged: its message, then its throwable's class, message and causes, stack included. */
    private static List<String> renderedEventsOf(Runnable call) {
        return eventsOf(call).stream()
                .map(event -> event.getThrowableProxy() == null
                        ? event.getFormattedMessage()
                        : "%s%n%s".formatted(event.getFormattedMessage(),
                                ThrowableProxyUtil.asString(event.getThrowableProxy())))
                .toList();
    }

    @Test
    @DisplayName("A connection test logs the URL without its path, query or fragment, port kept")
    void testConnection__logsUrlWithoutQueryOrFragment() {
        var secret = RandomStringUtils.secure().nextAlphanumeric(16);
        var url = "http://localhost:%d/?token=%s#%s".formatted(wireMockServer.port(), secret, secret);
        var expectedMessages = List.of(
                "Testing Ollama connection at: 'http://localhost:%d'".formatted(wireMockServer.port()),
                "Failed to connect to Ollama at 'http://localhost:%d': HTTP '404'".formatted(wireMockServer.port()));

        var actualMessages = logsOf(() -> ollamaService.testConnection(url, null).block());

        assertThat(actualMessages).isEqualTo(expectedMessages);
    }

    /** The HTTP client refuses a URL with userinfo before any request, so only the URL logged first carries it. */
    @Test
    @DisplayName("A connection test logs the URL without its userinfo")
    void testConnection__logsUrlWithoutUserinfo() {
        var username = "user-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(16));
        var password = RandomStringUtils.secure().nextAlphanumeric(16);
        var url = "http://%s:%s@localhost:%d/".formatted(username, password, wireMockServer.port());
        var expectedMessages = List.of(
                "Testing Ollama connection at: 'http://localhost:%d'".formatted(wireMockServer.port()),
                "Failed to connect to Ollama");

        var actualEvents = renderedEventsOf(() -> ollamaService.testConnection(url, null).block());

        assertThat(actualEvents).hasSize(expectedMessages.size());
        for (int i = 0; i < expectedMessages.size(); i++) {
            assertThat(actualEvents.get(i)).startsWith(expectedMessages.get(i))
                    .doesNotContain(username)
                    .doesNotContain(password);
        }
    }

    @Test
    @DisplayName("Listing models logs the URL without its path, query or fragment, port kept")
    void listModels__logsUrlWithoutQueryOrFragment() {
        var secret = RandomStringUtils.secure().nextAlphanumeric(16);
        var url = "http://localhost:%d/?token=%s#%s".formatted(wireMockServer.port(), secret, secret);
        var expectedMessages = List.of(
                "Fetching models from Ollama instance at: 'http://localhost:%d'".formatted(wireMockServer.port()),
                "Failed to fetch models from Ollama at 'http://localhost:%d': HTTP '404'"
                        .formatted(wireMockServer.port()));

        var actualMessages = logsOf(() -> ollamaService.listModels(url, null).block());

        assertThat(actualMessages).isEqualTo(expectedMessages);
    }

    /** As for the connection test, the client refuses the URL before any request. */
    @Test
    @DisplayName("Listing models logs the URL without its userinfo")
    void listModels__logsUrlWithoutUserinfo() {
        var username = "user-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(16));
        var password = RandomStringUtils.secure().nextAlphanumeric(16);
        var url = "http://%s:%s@localhost:%d/".formatted(username, password, wireMockServer.port());
        var expectedMessages = List.of(
                "Fetching models from Ollama instance at: 'http://localhost:%d'"
                        .formatted(wireMockServer.port()),
                "Failed to fetch models from Ollama");

        var actualEvents = renderedEventsOf(() -> ollamaService.listModels(url, null).block());

        assertThat(actualEvents).hasSize(expectedMessages.size());
        for (int i = 0; i < expectedMessages.size(); i++) {
            assertThat(actualEvents.get(i)).startsWith(expectedMessages.get(i))
                    .doesNotContain(username)
                    .doesNotContain(password);
        }
    }

    @Test
    @DisplayName("Should handle connection failure when Ollama is unreachable")
    void testConnection__unreachable() {
        // Given - Use a URL that will fail to connect
        String unreachableUrl = "http://localhost:99999";

        // When
        OllamaConnectionTestResponse response = ollamaService.testConnection(unreachableUrl, null).block();

        // Then
        assertThat(response).isNotNull();
        assertThat(response.connected()).isFalse();
        assertThat(response.version()).isNull();
        assertThat(response.errorMessage()).isNotNull();
        assertThat(response.errorMessage()).contains("Failed to connect to Ollama");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/v1", "/v1/"})
    @DisplayName("Should normalize URL by removing trailing slash and /v1 suffix")
    void testConnection__normalizeUrl(String urlSuffix) {
        // Given
        String version = RandomStringUtils.secure().nextAlphanumeric(5) + "."
                + RandomUtils.secure().randomInt(0, 10) + "."
                + RandomUtils.secure().randomInt(0, 100);
        String versionResponse = "{\"version\":\"" + version + "\"}";
        wireMockServer.stubFor(get(urlEqualTo("/api/version"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(versionResponse)));

        // When - URL with various suffixes
        OllamaConnectionTestResponse response = ollamaService.testConnection(baseUrl + urlSuffix, null).block();

        // Then
        assertThat(response.connected()).isTrue();
        assertThat(response.version()).isEqualTo(version);
    }

    @Test
    @DisplayName("Should successfully list models from Ollama instance")
    void listModels__success() {
        // Given
        String model1Name = RandomStringUtils.secure().nextAlphanumeric(10) + ":"
                + RandomStringUtils.secure().nextAlphanumeric(5);
        long model1Size = RandomUtils.secure().randomLong(1000L, 10000000000L);
        String model1Digest = "sha256:" + RandomStringUtils.secure().nextAlphanumeric(64).toLowerCase();
        Instant model1ModifiedAt = Instant.now().minusSeconds(RandomUtils.secure().randomLong(0, 86400));

        String model2Name = RandomStringUtils.secure().nextAlphanumeric(10) + ":"
                + RandomStringUtils.secure().nextAlphanumeric(5);
        long model2Size = RandomUtils.secure().randomLong(1000L, 10000000000L);
        String model2Digest = "sha256:" + RandomStringUtils.secure().nextAlphanumeric(64).toLowerCase();
        Instant model2ModifiedAt = Instant.now().minusSeconds(RandomUtils.secure().randomLong(0, 86400));

        String modelsResponse = String.format("""
                {
                  "models": [
                    {
                      "name": "%s",
                      "size": %d,
                      "digest": "%s",
                      "modified_at": "%s"
                    },
                    {
                      "name": "%s",
                      "size": %d,
                      "digest": "%s",
                      "modified_at": "%s"
                    }
                  ]
                }
                """, model1Name, model1Size, model1Digest, model1ModifiedAt.toString(),
                model2Name, model2Size, model2Digest, model2ModifiedAt.toString());

        wireMockServer.stubFor(get(urlEqualTo("/api/tags"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(modelsResponse)));

        // When
        List<OllamaModel> models = ollamaService.listModels(baseUrl, null).block();

        // Then
        assertThat(models).isNotNull();
        assertThat(models).hasSize(2);

        OllamaModel firstModel = models.get(0);
        assertThat(firstModel.name()).isEqualTo(model1Name);
        assertThat(firstModel.size()).isEqualTo(model1Size);
        assertThat(firstModel.digest()).isEqualTo(model1Digest);
        assertThat(firstModel.modifiedAt()).isNotNull();

        OllamaModel secondModel = models.get(1);
        assertThat(secondModel.name()).isEqualTo(model2Name);
        assertThat(secondModel.size()).isEqualTo(model2Size);
    }

    @Test
    @DisplayName("Should return empty list when model listing fails")
    void listModels__httpError() {
        // Given
        wireMockServer.stubFor(get(urlEqualTo("/api/tags"))
                .willReturn(aResponse()
                        .withStatus(500)));

        // When
        List<OllamaModel> models = ollamaService.listModels(baseUrl, null).block();

        // Then
        assertThat(models).isNotNull();
        assertThat(models).isEmpty();
    }

    @Test
    @DisplayName("Should normalize URL by removing /v1 suffix when listing models")
    void listModels__normalizeUrlWithV1() {
        // Given
        String modelName = RandomStringUtils.secure().nextAlphanumeric(10) + ":"
                + RandomStringUtils.secure().nextAlphanumeric(5);
        long modelSize = RandomUtils.secure().randomLong(1000L, 10000000000L);
        String modelDigest = "sha256:" + RandomStringUtils.secure().nextAlphanumeric(64).toLowerCase();
        Instant modelModifiedAt = Instant.now().minusSeconds(RandomUtils.secure().randomLong(0, 86400));

        String modelsResponse = String.format("""
                {
                  "models": [
                    {
                      "name": "%s",
                      "size": %d,
                      "digest": "%s",
                      "modified_at": "%s"
                    }
                  ]
                }
                """, modelName, modelSize, modelDigest, modelModifiedAt.toString());

        wireMockServer.stubFor(get(urlEqualTo("/api/tags"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(modelsResponse)));

        // When - URL with /v1 suffix (as frontend sends)
        List<OllamaModel> models = ollamaService.listModels(baseUrl + "/v1", null).block();

        // Then
        assertThat(models).isNotNull();
        assertThat(models).hasSize(1);
        assertThat(models.get(0).name()).isEqualTo(modelName);
    }

    @Test
    @DisplayName("Should return empty list when Ollama is unreachable")
    void listModels__unreachable() {
        // Given - Use a URL that will fail to connect
        String unreachableUrl = "http://localhost:99999";

        // When
        List<OllamaModel> models = ollamaService.listModels(unreachableUrl, null).block();

        // Then
        assertThat(models).isNotNull();
        assertThat(models).isEmpty();
    }

    @Test
    @DisplayName("Should handle empty model list")
    void listModels__emptyList() {
        // Given
        String modelsResponse = "{\"models\": []}";
        wireMockServer.stubFor(get(urlEqualTo("/api/tags"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(modelsResponse)));

        // When
        List<OllamaModel> models = ollamaService.listModels(baseUrl, null).block();

        // Then
        assertThat(models).isNotNull();
        assertThat(models).isEmpty();
    }

    @Test
    @DisplayName("Should send Authorization header when API key is provided for connection test")
    void testConnection__withApiKey() {
        // Given
        String version = RandomStringUtils.secure().nextAlphanumeric(5) + "."
                + RandomUtils.secure().randomInt(0, 10) + "."
                + RandomUtils.secure().randomInt(0, 100);
        String apiKey = RandomStringUtils.secure().nextAlphanumeric(32);
        String versionResponse = "{\"version\":\"" + version + "\"}";

        wireMockServer.stubFor(get(urlEqualTo("/api/version"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(versionResponse)));

        // When
        OllamaConnectionTestResponse response = ollamaService.testConnection(baseUrl, apiKey).block();

        // Then
        assertThat(response).isNotNull();
        assertThat(response.connected()).isTrue();
        assertThat(response.version()).isEqualTo(version);

        // Verify Authorization header was sent
        wireMockServer.verify(getRequestedFor(urlEqualTo("/api/version"))
                .withHeader("Authorization", equalTo("Bearer " + apiKey)));
    }

    @Test
    @DisplayName("Should send Authorization header when API key is provided for model listing")
    void listModels__withApiKey() {
        // Given
        String apiKey = RandomStringUtils.secure().nextAlphanumeric(32);
        String modelsResponse = "{\"models\": []}";

        wireMockServer.stubFor(get(urlEqualTo("/api/tags"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(modelsResponse)));

        // When
        List<OllamaModel> models = ollamaService.listModels(baseUrl, apiKey).block();

        // Then
        assertThat(models).isNotNull();

        // Verify Authorization header was sent
        wireMockServer.verify(getRequestedFor(urlEqualTo("/api/tags"))
                .withHeader("Authorization", equalTo("Bearer " + apiKey)));
    }

}
