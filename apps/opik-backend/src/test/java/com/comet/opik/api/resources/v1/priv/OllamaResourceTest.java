package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.OllamaConnectionTestResponse;
import com.comet.opik.api.OllamaInstanceBaseUrlRequest;
import com.comet.opik.api.OllamaModel;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.core.HttpHeaders;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Ollama Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class OllamaResourceTest {

    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String API_KEY = UUID.randomUUID().toString();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    private final WireMockUtils.WireMockRuntime wireMock;
    private static WireMockServer ollamaWireMock;
    private String ollamaBaseUrl;

    {
        Startables.deepStart(REDIS, CLICKHOUSE_CONTAINER, MYSQL, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE_CONTAINER);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    @BeforeAll
    void beforeAll(ClientSupport client) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, "test-workspace", WORKSPACE_ID, USER);

        // Start mock Ollama server
        ollamaWireMock = new WireMockServer(0);
        ollamaWireMock.start();
        WireMock.configureFor("localhost", ollamaWireMock.port());
        ollamaBaseUrl = "http://localhost:" + ollamaWireMock.port();
    }

    @BeforeEach
    void setUp() {
        ollamaWireMock.resetAll();
    }

    @AfterAll
    void afterAll() {
        if (ollamaWireMock != null) {
            ollamaWireMock.stop();
        }
    }

    static Stream<Arguments> baseUrlVariants() {
        return Stream.of(
                Arguments.of("", "base URL without suffix"),
                Arguments.of("/v1", "base URL with /v1 suffix"));
    }

    @ParameterizedTest
    @MethodSource("baseUrlVariants")
    @DisplayName("Should successfully test connection to Ollama instance")
    void testConnection__success(String urlSuffix, String description, ClientSupport client) {
        // Given
        String version = RandomStringUtils.secure().nextAlphanumeric(5) + "."
                + RandomUtils.secure().randomInt(0, 10) + "."
                + RandomUtils.secure().randomInt(0, 100);
        String versionResponse = "{\"version\":\"" + version + "\"}";
        ollamaWireMock.stubFor(get(urlEqualTo("/api/version"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(versionResponse)));

        OllamaInstanceBaseUrlRequest request = OllamaInstanceBaseUrlRequest.builder()
                .baseUrl(ollamaBaseUrl + urlSuffix)
                .build();

        // When
        var response = client.target("/v1/private/ollama/test-connection")
                .request()
                .header(RequestContext.WORKSPACE_HEADER, "test-workspace")
                .header(HttpHeaders.AUTHORIZATION, API_KEY)
                .post(jakarta.ws.rs.client.Entity.json(request));

        // Then
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);

        OllamaConnectionTestResponse result = response.readEntity(OllamaConnectionTestResponse.class);
        assertThat(result).isNotNull();
        assertThat(result.connected()).isTrue();
        assertThat(result.version()).isEqualTo(version);
        assertThat(result.errorMessage()).isNull();
    }

    @Test
    @DisplayName("Should handle connection failure")
    void testConnection__failure(ClientSupport client) {
        // Given
        ollamaWireMock.stubFor(get(urlEqualTo("/api/version"))
                .willReturn(aResponse()
                        .withStatus(500)));

        OllamaInstanceBaseUrlRequest request = OllamaInstanceBaseUrlRequest.builder()
                .baseUrl(ollamaBaseUrl)
                .build();

        // When
        var response = client.target("/v1/private/ollama/test-connection")
                .request()
                .header(RequestContext.WORKSPACE_HEADER, "test-workspace")
                .header(HttpHeaders.AUTHORIZATION, API_KEY)
                .post(jakarta.ws.rs.client.Entity.json(request));

        // Then
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_GATEWAY);

        OllamaConnectionTestResponse result = response.readEntity(OllamaConnectionTestResponse.class);
        assertThat(result).isNotNull();
        assertThat(result.connected()).isFalse();
        assertThat(result.errorMessage()).isNotNull();
    }

    @Test
    @DisplayName("Should require authentication for test connection")
    void testConnection__unauthorized(ClientSupport client) {
        OllamaInstanceBaseUrlRequest request = OllamaInstanceBaseUrlRequest.builder()
                .baseUrl(ollamaBaseUrl)
                .build();

        // When - No auth headers
        var response = client.target("/v1/private/ollama/test-connection")
                .request()
                .post(jakarta.ws.rs.client.Entity.json(request));

        // Then - Expecting 403 Forbidden (not 401 Unauthorized)
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_FORBIDDEN);
    }

    @Test
    @DisplayName("Should validate required fields for test connection")
    void testConnection__missingBaseUrl(ClientSupport client) {
        // Given - Empty request
        OllamaInstanceBaseUrlRequest request = OllamaInstanceBaseUrlRequest.builder()
                .baseUrl("")
                .build();

        // When
        var response = client.target("/v1/private/ollama/test-connection")
                .request()
                .header(RequestContext.WORKSPACE_HEADER, "test-workspace")
                .header(HttpHeaders.AUTHORIZATION, API_KEY)
                .post(jakarta.ws.rs.client.Entity.json(request));

        // Then
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("Should successfully list models from Ollama instance")
    void listModels__success(ClientSupport client) {
        // Given - Using actual Ollama API response format with RFC3339 timestamps
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
                      "model": "%s",
                      "size": %d,
                      "digest": "%s",
                      "modified_at": "%s"
                    },
                    {
                      "name": "%s",
                      "model": "%s",
                      "size": %d,
                      "digest": "%s",
                      "modified_at": "%s"
                    }
                  ]
                }
                """, model1Name, model1Name, model1Size, model1Digest, model1ModifiedAt.toString(),
                model2Name, model2Name, model2Size, model2Digest, model2ModifiedAt.toString());

        ollamaWireMock.stubFor(get(urlEqualTo("/api/tags"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(modelsResponse)));

        OllamaInstanceBaseUrlRequest request = OllamaInstanceBaseUrlRequest.builder()
                .baseUrl(ollamaBaseUrl)
                .build();

        // When
        var response = client.target("/v1/private/ollama/models")
                .request()
                .header(RequestContext.WORKSPACE_HEADER, "test-workspace")
                .header(HttpHeaders.AUTHORIZATION, API_KEY)
                .post(jakarta.ws.rs.client.Entity.json(request));

        // Then
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);

        List<OllamaModel> models = response.readEntity(new jakarta.ws.rs.core.GenericType<List<OllamaModel>>() {
        });
        assertThat(models).isNotNull();
        assertThat(models).hasSize(2);

        // Verify first model
        OllamaModel firstModel = models.get(0);
        assertThat(firstModel.name()).isEqualTo(model1Name);
        assertThat(firstModel.size()).isEqualTo(model1Size);
        assertThat(firstModel.digest()).isEqualTo(model1Digest);
        assertThat(firstModel.modifiedAt()).isNotNull();

        // Verify second model
        OllamaModel secondModel = models.get(1);
        assertThat(secondModel.name()).isEqualTo(model2Name);
        assertThat(secondModel.size()).isEqualTo(model2Size);
    }

    @Test
    @DisplayName("Should handle empty model list")
    void listModels__emptyList(ClientSupport client) {
        // Given
        String modelsResponse = "{\"models\": []}";

        ollamaWireMock.stubFor(get(urlEqualTo("/api/tags"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(modelsResponse)));

        OllamaInstanceBaseUrlRequest request = OllamaInstanceBaseUrlRequest.builder()
                .baseUrl(ollamaBaseUrl)
                .build();

        // When
        var response = client.target("/v1/private/ollama/models")
                .request()
                .header(RequestContext.WORKSPACE_HEADER, "test-workspace")
                .header(HttpHeaders.AUTHORIZATION, API_KEY)
                .post(jakarta.ws.rs.client.Entity.json(request));

        // Then
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);

        List<OllamaModel> models = response.readEntity(new jakarta.ws.rs.core.GenericType<List<OllamaModel>>() {
        });
        assertThat(models).isNotNull();
        assertThat(models).isEmpty();
    }

    @Test
    @DisplayName("Should require authentication for list models")
    void listModels__unauthorized(ClientSupport client) {
        OllamaInstanceBaseUrlRequest request = OllamaInstanceBaseUrlRequest.builder()
                .baseUrl(ollamaBaseUrl)
                .build();

        // When - No auth headers
        var response = client.target("/v1/private/ollama/models")
                .request()
                .post(jakarta.ws.rs.client.Entity.json(request));

        // Then - Expecting 403 Forbidden (not 401 Unauthorized)
        assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_FORBIDDEN);
    }
}
