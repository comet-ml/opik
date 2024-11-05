package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.CreatePromptVersion;
import com.comet.opik.api.Prompt;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.podam.PodamFactoryUtils;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.http.HttpStatus;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.lifecycle.Startables;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static com.comet.opik.infrastructure.auth.RequestContext.SESSION_COOKIE;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static com.comet.opik.infrastructure.auth.TestHttpClientUtils.UNAUTHORIZED_RESPONSE;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@Testcontainers(parallel = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Prompt Resource Test")
class PromptResourceTest {

    private static final String RESOURCE_PATH = "%s/v1/private/prompts";

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private static final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils.newClickHouseContainer();
    private static final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();

    @RegisterExtension
    private static final TestDropwizardAppExtension app;

    private static final WireMockUtils.WireMockRuntime wireMock;
    private static final String[] IGNORED_FIELDS = {"latestVersion", "template"};

    static {
        Startables.deepStart(REDIS, CLICKHOUSE_CONTAINER, MYSQL).join();
        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE_CONTAINER, DATABASE_NAME);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;

    @BeforeAll
    void setUpAll(ClientSupport client, Jdbi jdbi) throws SQLException {

        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICKHOUSE_CONTAINER.createConnection("")) {
            MigrationUtils.runDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        this.baseURI = "http://localhost:%d".formatted(client.getPort());
        this.client = client;

        ClientSupportUtils.config(client);

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);
    }

    private static void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Nested
    @DisplayName("Api Key Authentication:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ApiKey {

        private final String fakeApikey = UUID.randomUUID().toString();
        private final String okApikey = UUID.randomUUID().toString();

        Stream<Arguments> credentials() {
            return Stream.of(
                    arguments(okApikey, true),
                    arguments(fakeApikey, false),
                    arguments("", false));
        }

        @BeforeEach
        void setUp() {

            wireMock.server().stubFor(
                    post(urlPathEqualTo("/opik/auth"))
                            .withHeader(HttpHeaders.AUTHORIZATION, equalTo(fakeApikey))
                            .withRequestBody(matchingJsonPath("$.workspaceName", matching(".+")))
                            .willReturn(WireMock.unauthorized()));

            wireMock.server().stubFor(
                    post(urlPathEqualTo("/opik/auth"))
                            .withHeader(HttpHeaders.AUTHORIZATION, equalTo(""))
                            .withRequestBody(matchingJsonPath("$.workspaceName", matching(".+")))
                            .willReturn(WireMock.unauthorized()));
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("create prompt: when api key is present, then return proper response")
        void createPrompt__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success) {

            var prompt = factory.manufacturePojo(Prompt.class);

            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.entity(prompt, MediaType.APPLICATION_JSON_TYPE))) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CREATED);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("find prompt: when api key is present, then return proper response")
        void findPrompt__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success) {

            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
                    assertThat(actualResponse.hasEntity()).isTrue();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("update prompt: when api key is present, then return proper response")
        void updatePrompt__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success) {
            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .versionCount(0L)
                    .build();

            UUID promptId = createPrompt(prompt, okApikey, workspaceName);

            try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI) + "/%s".formatted(promptId))
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .put(Entity.json(prompt.toBuilder().description(UUID.randomUUID().toString()).build()))) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("delete prompt: when api key is present, then return proper response")
        void deletePrompt__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success) {
            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .versionCount(0L)
                    .build();

            UUID promptId = createPrompt(prompt, okApikey, workspaceName);

            try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI) + "/%s".formatted(promptId))
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .delete()) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("get prompt by id: when api key is present, then return proper response")
        void getPromptById__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success) {
            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .versionCount(0L)
                    .build();

            UUID promptId = createPrompt(prompt, okApikey, workspaceName);

            try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI) + "/%s".formatted(promptId))
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("Create prompt versions: when api key is present, then return proper response")
        void createPromptVersions__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success) {
            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var promptVersion = factory.manufacturePojo(CreatePromptVersion.class);

            try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI) + "/versions")
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(promptVersion))) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("Get prompt versions by prompt id: when api key is present, then return proper response")
        void getPromptVersionsByPromptId__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success) {
            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .versionCount(0L)
                    .build();

            UUID promptId = createPrompt(prompt, okApikey, workspaceName);

            try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI) + "/%s/versions".formatted(promptId))
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }
    }

    @Nested
    @DisplayName("Session Token Authentication:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class SessionTokenCookie {

        private final String sessionToken = UUID.randomUUID().toString();
        private final String fakeSessionToken = UUID.randomUUID().toString();

        Stream<Arguments> credentials() {
            return Stream.of(
                    arguments(sessionToken, true, "OK_" + UUID.randomUUID()),
                    arguments(fakeSessionToken, false, UUID.randomUUID().toString()));
        }

        @BeforeAll
        void setUp() {
            wireMock.server().stubFor(
                    post(urlPathEqualTo("/opik/auth-session"))
                            .withCookie(SESSION_COOKIE, equalTo(sessionToken))
                            .withRequestBody(matchingJsonPath("$.workspaceName", matching("OK_.+")))
                            .willReturn(okJson(AuthTestUtils.newWorkspaceAuthResponse(USER, WORKSPACE_ID))));

            wireMock.server().stubFor(
                    post(urlPathEqualTo("/opik/auth-session"))
                            .withCookie(SESSION_COOKIE, equalTo(fakeSessionToken))
                            .withRequestBody(matchingJsonPath("$.workspaceName", matching(".+")))
                            .willReturn(WireMock.unauthorized()));
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("create prompt: when session token is present, then return proper response")
        void createPrompt__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean success,
                String workspaceName) {
            var prompt = factory.manufacturePojo(Prompt.class);

            try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI)).request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(prompt))) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CREATED);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("find prompt: when session token is present, then return proper response")
        void findPrompt__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean success,
                String workspaceName) {

            try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI)).request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
                    assertThat(actualResponse.hasEntity()).isTrue();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("update prompt: when session token is present, then return proper response")
        void updatePrompt__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean success,
                String workspaceName) {
            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .versionCount(0L)
                    .build();

            UUID promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI) + "/%s".formatted(promptId))
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .put(Entity.json(prompt.toBuilder().description(UUID.randomUUID().toString()).build()))) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("delete prompt: when session token is present, then return proper response")
        void deletePrompt__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean success,
                String workspaceName) {

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .versionCount(0L)
                    .build();

            UUID promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI) + "/%s".formatted(promptId))
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .delete()) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("get prompt by id: when session token is present, then return proper response")
        void getPromptById__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken, boolean success,
                String workspaceName) {

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .versionCount(0L)
                    .build();

            UUID promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI) + "/%s".formatted(promptId))
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("Create prompt versions: when session token is present, then return proper response")
        void createPromptVersions__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                boolean success,
                String workspaceName) {

            var promptVersion = factory.manufacturePojo(CreatePromptVersion.class);

            try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI) + "/versions")
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(promptVersion))) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("Get prompt versions by prompt id: when session token is present, then return proper response")
        void getPromptVersionsByPromptId__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                boolean success,
                String workspaceName) {

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .versionCount(0L)
                    .build();

            UUID promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI) + "/%s/versions".formatted(promptId))
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }
    }

    private UUID createPrompt(Prompt prompt, String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(prompt))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);

            return TestUtils.getIdFromLocation(response.getLocation());
        }
    }

    @Nested
    @DisplayName("Create Prompt")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CreatePrompt {

        @Test
        @DisplayName("Success: should create prompt")
        void shouldCreatePrompt() {

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .build();

            var promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            assertThat(promptId).isNotNull();
        }

        @Test
        @DisplayName("when prompt contains first version template, then return created prompt")
        void when__promptContainsFirstVersionTemplate__thenReturnCreatedPrompt() {

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .build();

            var promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            assertThat(promptId).isNotNull();
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when prompt state is invalid, then return conflict")
        void when__promptIsInvalid__thenReturnError(Prompt prompt, int expectedStatusCode, Object expectedBody,
                Class<?> expectedResponseClass) {

            try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(RequestContext.WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(prompt))) {

                assertThat(response.getStatus()).isEqualTo(expectedStatusCode);

                var actualBody = response.readEntity(expectedResponseClass);

                assertThat(actualBody).isEqualTo(expectedBody);
            }
        }

        Stream<Arguments> when__promptIsInvalid__thenReturnError() {
            Prompt prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .id(UUID.randomUUID())
                    .build();

            Prompt duplicatedPrompt = factory.manufacturePojo(Prompt.class);
            createPrompt(duplicatedPrompt, API_KEY, TEST_WORKSPACE);

            return Stream.of(
                    Arguments.of(prompt, HttpStatus.SC_BAD_REQUEST,
                            new ErrorMessage(List.of("prompt id must be a version 7 UUID")),
                            ErrorMessage.class),
                    Arguments.of(duplicatedPrompt.toBuilder().name(UUID.randomUUID().toString()).build(),
                            HttpStatus.SC_CONFLICT,
                            new io.dropwizard.jersey.errors.ErrorMessage(HttpStatus.SC_CONFLICT,
                                    "Prompt id or name already exists"),
                            io.dropwizard.jersey.errors.ErrorMessage.class),
                    Arguments.of(duplicatedPrompt.toBuilder().id(factory.manufacturePojo(UUID.class)).build(),
                            HttpStatus.SC_CONFLICT,
                            new io.dropwizard.jersey.errors.ErrorMessage(HttpStatus.SC_CONFLICT,
                                    "Prompt id or name already exists"),
                            io.dropwizard.jersey.errors.ErrorMessage.class),
                    Arguments.of(factory.manufacturePojo(Prompt.class).toBuilder().description("").build(),
                            HttpStatus.SC_UNPROCESSABLE_ENTITY,
                            new ErrorMessage(List.of("description must not be blank")),
                            ErrorMessage.class),
                    Arguments.of(factory.manufacturePojo(Prompt.class).toBuilder().name("").build(),
                            HttpStatus.SC_UNPROCESSABLE_ENTITY,
                            new ErrorMessage(List.of("name must not be blank")), ErrorMessage.class));
        }
    }

    @Nested
    @DisplayName("Update Prompt")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class UpdatePrompt {

        @ParameterizedTest
        @MethodSource
        @DisplayName("Success: prompt update is valid, then return success")
        void when__promptUpdateIsValid__thenReturnSuccess(Function<Prompt, Prompt> promptUpdate) {

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .versionCount(0L)
                    .build();

            UUID promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            var updatedPrompt = promptUpdate.apply(prompt);

            try (var response = client.target(RESOURCE_PATH.formatted(baseURI) + "/%s".formatted(promptId))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(RequestContext.WORKSPACE_HEADER, TEST_WORKSPACE)
                    .put(Entity.json(updatedPrompt))) {

                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
                assertThat(response.hasEntity()).isFalse();
            }

            var actualPrompt = getPrompt(promptId, API_KEY, TEST_WORKSPACE);

            assertThat(actualPrompt)
                    .usingRecursiveComparison(
                            RecursiveComparisonConfiguration.builder()
                                    .withIgnoredFields(IGNORED_FIELDS)
                                    .withComparatorForType(PromptResourceTest.this::comparatorForCreateAtAndUpdatedAt,
                                            Instant.class)
                                    .build())
                    .isEqualTo(updatedPrompt);
        }

        @Test
        @DisplayName("when updating prompt name to an existing one, then return conflict")
        void when__updatingPromptNameToAnExistingOne__thenReturnConflict() {

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .build();

            var prompt2 = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .build();

            UUID promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);
            createPrompt(prompt2, API_KEY, TEST_WORKSPACE);

            var updatedPrompt = prompt.toBuilder()
                    .name(prompt2.name())
                    .build();

            try (var response = client.target(RESOURCE_PATH.formatted(baseURI) + "/%s".formatted(promptId))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(RequestContext.WORKSPACE_HEADER, TEST_WORKSPACE)
                    .put(Entity.json(updatedPrompt))) {

                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CONFLICT);

                var actualBody = response.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);

                assertThat(actualBody)
                        .isEqualTo(
                                new io.dropwizard.jersey.errors.ErrorMessage(HttpStatus.SC_CONFLICT,
                                        "Prompt id or name already exists"));
            }
        }

        Stream<Arguments> when__promptUpdateIsValid__thenReturnSuccess() {
            return Stream.of(
                    arguments((Function<Prompt, Prompt>) prompt -> prompt.toBuilder().name(UUID.randomUUID().toString())
                            .build()),
                    arguments((Function<Prompt, Prompt>) prompt -> prompt.toBuilder()
                            .description(UUID.randomUUID().toString()).build()),
                    arguments((Function<Prompt, Prompt>) prompt -> prompt.toBuilder().description(null).build()));
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when prompt state is invalid, then return conflict")
        void when__promptIsInvalid__thenReturnError(
                Prompt updatedPrompt, int expectedStatusCode, Object expectedBody, Class<?> expectedErrorClass) {

            try (var response = client.target(RESOURCE_PATH.formatted(baseURI) + "/%s".formatted(updatedPrompt.id()))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(RequestContext.WORKSPACE_HEADER, TEST_WORKSPACE)
                    .put(Entity.json(updatedPrompt))) {

                assertThat(response.getStatus()).isEqualTo(expectedStatusCode);

                var actualBody = response.readEntity(expectedErrorClass);

                assertThat(actualBody).isEqualTo(expectedBody);
            }
        }

        Stream<Arguments> when__promptIsInvalid__thenReturnError() {

            return Stream.of(

                    Arguments.of(factory.manufacturePojo(Prompt.class), HttpStatus.SC_NOT_FOUND,
                            new io.dropwizard.jersey.errors.ErrorMessage(HttpStatus.SC_NOT_FOUND, "Prompt not found"),
                            io.dropwizard.jersey.errors.ErrorMessage.class),
                    Arguments.of(factory.manufacturePojo(Prompt.class).toBuilder().name(null).build(),
                            HttpStatus.SC_UNPROCESSABLE_ENTITY,
                            new ErrorMessage(List.of("name must not be blank")),
                            ErrorMessage.class),
                    Arguments.of(factory.manufacturePojo(Prompt.class).toBuilder().name("").build(),
                            HttpStatus.SC_UNPROCESSABLE_ENTITY,
                            new ErrorMessage(List.of("name must not be blank")),
                            ErrorMessage.class),
                    Arguments.of(factory.manufacturePojo(Prompt.class).toBuilder().description("").build(),
                            HttpStatus.SC_UNPROCESSABLE_ENTITY,
                            new ErrorMessage(List.of("description must not be blank")),
                            ErrorMessage.class));
        }
    }

    private Prompt getPrompt(UUID promptId, String apiKey, String workspaceName) {
        Response response = client.target(RESOURCE_PATH.formatted(baseURI) + "/%s".formatted(promptId))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();

        assertThat(response.getStatus()).isEqualTo(200);

        return response.readEntity(Prompt.class);
    }

    private void getPromptAndAssertNotFound(UUID promptId, String apiKey, String workspaceName) {
        Response response = client.target(RESOURCE_PATH.formatted(baseURI) + "/%s".formatted(promptId))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                .isEqualTo(new io.dropwizard.jersey.errors.ErrorMessage(404, "Prompt not found"));
    }

    @Nested
    @DisplayName("Delete Prompt")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class DeletePrompt {

        @Test
        @DisplayName("Success: should delete prompt")
        void shouldDeletePrompt() {

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .build();

            UUID promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            try (var response = client.target(RESOURCE_PATH.formatted(baseURI) + "/%s".formatted(promptId))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(RequestContext.WORKSPACE_HEADER, TEST_WORKSPACE)
                    .delete()) {

                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
                assertThat(response.hasEntity()).isFalse();
            }

            getPromptAndAssertNotFound(promptId, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when prompt does not exist, then return no content")
        void when__promptDoesNotExist__thenReturnNotFound() {

            UUID promptId = UUID.randomUUID();

            getPromptAndAssertNotFound(promptId, API_KEY, TEST_WORKSPACE);

            try (var response = client.target(RESOURCE_PATH.formatted(baseURI) + "/%s".formatted(promptId))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(RequestContext.WORKSPACE_HEADER, TEST_WORKSPACE)
                    .delete()) {

                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
                assertThat(response.hasEntity()).isFalse();
            }

            getPromptAndAssertNotFound(promptId, API_KEY, TEST_WORKSPACE);
        }
    }

    @Nested
    @DisplayName("Find Prompt")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class FindPrompt {

        @Test
        @DisplayName("Success: should find prompt")
        void shouldFindPrompt() {

            String apiKey = UUID.randomUUID().toString();
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .versionCount(1L)
                    .build();

            createPrompt(prompt, apiKey, workspaceName);

            List<Prompt> expectedPrompts = List.of(prompt);

            findPromptsAndAssertPage(expectedPrompts, apiKey, workspaceName, expectedPrompts.size(), 1, null);
        }

        @Test
        @DisplayName("when search by name, then return prompt matching name")
        void when__searchByName__thenReturnPromptMatchingName() {

            String apiKey = UUID.randomUUID().toString();
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .versionCount(1L)
                    .build();

            createPrompt(prompt, apiKey, workspaceName);

            List<Prompt> expectedPrompts = List.of(prompt);

            findPromptsAndAssertPage(expectedPrompts, apiKey, workspaceName, expectedPrompts.size(), 1, prompt.name());
        }

        @Test
        @DisplayName("when search by name with mismatched partial name, then return empty page")
        void when__searchByNameWithMismatchedPartialName__thenReturnEmptyPage() {

            String apiKey = UUID.randomUUID().toString();
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            String name = RandomStringUtils.randomAlphanumeric(10);

            String partialSearch = name.substring(0, 5) + "@" + RandomStringUtils.randomAlphanumeric(2);

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .name(name)
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .versionCount(1L)
                    .build();

            createPrompt(prompt, apiKey, workspaceName);

            List<Prompt> expectedPrompts = List.of();

            findPromptsAndAssertPage(expectedPrompts, apiKey, workspaceName, expectedPrompts.size(), 1, partialSearch);
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when search by partial name, then return prompt matching name")
        void when__searchByPartialName__thenReturnPromptMatchingName(String promptName, String partialSearch) {

            String apiKey = UUID.randomUUID().toString();
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            IntStream.range(0, 4).forEach(i -> {
                var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                        .lastUpdatedBy(USER)
                        .createdBy(USER)
                        .versionCount(0L)
                        .template(null)
                        .build();

                Prompt updatedPrompt = prompt.toBuilder()
                        .name(prompt.name().replaceAll("(?i)" + partialSearch, ""))
                        .build();

                createPrompt(updatedPrompt, apiKey, workspaceName);

            });

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .name(promptName)
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .versionCount(1L)
                    .build();

            createPrompt(prompt, apiKey, workspaceName);

            List<Prompt> expectedPrompts = List.of(prompt);
            findPromptsAndAssertPage(expectedPrompts, apiKey, workspaceName, expectedPrompts.size(), 1, partialSearch);
        }

        Stream<Arguments> when__searchByPartialName__thenReturnPromptMatchingName() {
            return Stream.of(
                    arguments("prompt", "pro"),
                    arguments("prompt", "pt"),
                    arguments("prompt", "om"));
        }

        @Test
        @DisplayName("when fetch prompts, then return prompts sorted by creation time")
        void when__fetchPrompts__thenReturnPromptsSortedByCreationTime() {

            String apiKey = UUID.randomUUID().toString();
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var prompts = PodamFactoryUtils.manufacturePojoList(factory, Prompt.class).stream()
                    .map(prompt -> prompt.toBuilder()
                            .lastUpdatedBy(USER)
                            .createdBy(USER)
                            .versionCount(0L)
                            .template(null)
                            .build())
                    .toList();

            prompts.forEach(prompt -> createPrompt(prompt, apiKey, workspaceName));

            List<Prompt> expectedPrompts = prompts.reversed();

            findPromptsAndAssertPage(expectedPrompts, apiKey, workspaceName, expectedPrompts.size(), 1, null);
        }

        @Test
        @DisplayName("when fetch prompts using pagination, then return prompts paginated")
        void when__fetchPromptsUsingPagination__thenReturnPromptsPaginated() {

            String apiKey = UUID.randomUUID().toString();
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var prompts = IntStream.range(0, 20)
                    .mapToObj(i -> factory.manufacturePojo(Prompt.class).toBuilder()
                            .lastUpdatedBy(USER)
                            .createdBy(USER)
                            .versionCount(1L)
                            .build())
                    .toList();

            prompts.forEach(prompt -> createPrompt(prompt, apiKey, workspaceName));

            List<Prompt> promptPage1 = prompts.reversed().subList(0, 10);
            List<Prompt> promptPage2 = prompts.reversed().subList(10, 20);

            findPromptsAndAssertPage(promptPage1, apiKey, workspaceName, prompts.size(), 1, null);
            findPromptsAndAssertPage(promptPage2, apiKey, workspaceName, prompts.size(), 2, null);
        }

    }

    @Nested
    @DisplayName("Get Prompt by Id")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetPromptById {

        @Test
        @DisplayName("Success: should get prompt by id")
        void shouldGetPromptById() {

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .versionCount(1L)
                    .build();

            UUID promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            getPromptAndAssert(promptId, prompt, API_KEY, TEST_WORKSPACE, Set.of());
        }

        @Test
        @DisplayName("when prompt has multiple versions, then return prompt with latest version")
        void when__promptHasMultipleVersions__thenReturnPromptWithLatestVersion() {

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .versionCount(1L)
                    .build();

            UUID promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            PromptVersion promptVersion = factory.manufacturePojo(PromptVersion.class)
                    .toBuilder()
                    .createdBy(USER)
                    .build();

            promptVersion = createPromptVersion(new CreatePromptVersion(prompt.name(), promptVersion), API_KEY,
                    TEST_WORKSPACE);

            Prompt expectedPrompt = prompt.toBuilder()
                    .template(promptVersion.template())
                    .versionCount(2L)
                    .build();

            getPromptAndAssert(promptId, expectedPrompt, API_KEY, TEST_WORKSPACE, promptVersion.variables());
        }

        @Test
        @DisplayName("when prompt does not exist, then return not found")
        void when__promptDoesNotExist__thenReturnNotFound() {

            UUID promptId = UUID.randomUUID();

            try (var response = client.target(RESOURCE_PATH.formatted(baseURI) + "/%s".formatted(promptId))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(RequestContext.WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NOT_FOUND);
                assertThat(response.hasEntity()).isTrue();
                assertThat(response.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                        .isEqualTo(new io.dropwizard.jersey.errors.ErrorMessage(HttpStatus.SC_NOT_FOUND,
                                "Prompt not found"));
            }
        }
    }

    private void getPromptAndAssert(UUID promptId, Prompt expectedPrompt, String apiKey, String workspaceName,
            Set<String> expectedVariables) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI) + "/%s".formatted(promptId))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);

            var actualPrompt = response.readEntity(Prompt.class);

            assertThat(actualPrompt)
                    .usingRecursiveComparison(
                            RecursiveComparisonConfiguration.builder()
                                    .withIgnoredFields(IGNORED_FIELDS)
                                    .withComparatorForType(
                                            PromptResourceTest.this::comparatorForCreateAtAndUpdatedAt,
                                            Instant.class)
                                    .build())
                    .isEqualTo(expectedPrompt);

            assertLatestVersion(actualPrompt, expectedPrompt, expectedVariables);
        }
    }

    private void assertLatestVersion(Prompt actualPrompt, Prompt expectedPrompt, Set<String> expectedVariables) {
        PromptVersion promptVersion = actualPrompt.latestVersion();

        assertThat(promptVersion).isNotNull();
        assertThat(promptVersion.id()).isNotNull();
        assertThat(promptVersion.commit())
                .isEqualTo(promptVersion.id().toString().substring(promptVersion.id().toString().length() - 8));
        assertThat(promptVersion.template()).isEqualTo(expectedPrompt.template());
        assertThat(promptVersion.variables()).isEqualTo(expectedVariables);
        assertThat(promptVersion.createdBy()).isEqualTo(USER);
        assertThat(promptVersion.createdAt()).isBetween(expectedPrompt.createdAt(), Instant.now());
    }

    @Nested
    @DisplayName("Create Prompt Version")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CreatePromptVersions {

        @Test
        @DisplayName("Success: should create prompt version")
        void shouldCreatePromptVersion() {

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .build();

            UUID promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            var expectedPromptVersion = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .createdBy(USER)
                    .commit(null)
                    .id(null)
                    .build();

            var request = new CreatePromptVersion(prompt.name(), expectedPromptVersion);

            PromptVersion actualPromptVersion = createPromptVersion(request, API_KEY, TEST_WORKSPACE);

            assertPromptVersion(actualPromptVersion, expectedPromptVersion, promptId);
        }

        @Test
        @DisplayName("when prompt version contains commit, then return created prompt version")
        void when__promptVersionContainsCommit__thenReturnCreatedPromptVersion() {

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .build();

            UUID promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            var versionId = factory.manufacturePojo(UUID.class);

            var expectedPromptVersion = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .createdBy(USER)
                    .commit(versionId.toString().substring(versionId.toString().length() - 8))
                    .id(versionId)
                    .build();

            var request = new CreatePromptVersion(prompt.name(), expectedPromptVersion);

            PromptVersion actualPromptVersion = createPromptVersion(request, API_KEY, TEST_WORKSPACE);

            assertPromptVersion(actualPromptVersion, expectedPromptVersion, promptId);
        }

        @Test
        @DisplayName("when prompt doesn't exist, then return created prompt version")
        void when__promptDoesNotExist__thenReturnCreatedPromptVersion() {

            var apiKey = UUID.randomUUID().toString();
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var promptName = UUID.randomUUID().toString();

            var versionId = factory.manufacturePojo(UUID.class);

            var expectedPromptVersion = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .createdBy(USER)
                    .commit(versionId.toString().substring(versionId.toString().length() - 8))
                    .id(versionId)
                    .build();

            var request = new CreatePromptVersion(promptName, expectedPromptVersion);

            PromptVersion actualPromptVersion = createPromptVersion(request, apiKey, workspaceName);

            List<Prompt> prompts = getPrompts(promptName, apiKey, workspaceName);

            assertPromptVersion(actualPromptVersion, expectedPromptVersion, prompts.getFirst().id());
        }

        @Test
        @DisplayName("when prompt version id already exists, then return error")
        void when__promptVersionIdAlreadyExists__thenReturnError() {

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .build();

            var versionId = factory.manufacturePojo(UUID.class);

            var promptVersion = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .createdBy(USER)
                    .id(versionId)
                    .build();

            var request = new CreatePromptVersion(prompt.name(), promptVersion);

            createPromptVersion(request, API_KEY, TEST_WORKSPACE);

            var promptVersion2 = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .createdBy(USER)
                    .id(versionId)
                    .build();

            assertPromptVersionConflict(
                    new CreatePromptVersion(UUID.randomUUID().toString(), promptVersion2),
                    API_KEY, TEST_WORKSPACE, "Prompt version already exists");
        }

        @Test
        @DisplayName("when prompt version commit already exists, then return error")
        void when__promptVersionCommitAlreadyExists__thenReturnError() {

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .build();

            var promptVersion = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .createdBy(USER)
                    .build();

            var request = new CreatePromptVersion(prompt.name(), promptVersion);

            createPromptVersion(request, API_KEY, TEST_WORKSPACE);

            var promptVersion2 = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .createdBy(USER)
                    .commit(promptVersion.commit())
                    .build();

            assertPromptVersionConflict(
                    new CreatePromptVersion(prompt.name(), promptVersion2),
                    API_KEY, TEST_WORKSPACE, "Prompt version already exists");
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when prompt version is invalid, then return error")
        void when__promptVersionIsInvalid__thenReturnError(CreatePromptVersion promptVersion, int expectedStatusCode,
                Object expectedBody, Class<?> expectedResponseClass) {

            try (var response = client.target(RESOURCE_PATH.formatted(baseURI) + "/versions")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(RequestContext.WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(promptVersion))) {

                assertThat(response.getStatus()).isEqualTo(expectedStatusCode);

                var actualBody = response.readEntity(expectedResponseClass);

                assertThat(actualBody).isEqualTo(expectedBody);
            }
        }

        Stream<Arguments> when__promptVersionIsInvalid__thenReturnError() {
            return Stream.of(
                    arguments(new CreatePromptVersion(null, factory.manufacturePojo(PromptVersion.class)),
                            HttpStatus.SC_UNPROCESSABLE_ENTITY, new ErrorMessage(List.of("name must not be blank")),
                            ErrorMessage.class),
                    arguments(new CreatePromptVersion("", factory.manufacturePojo(PromptVersion.class)),
                            HttpStatus.SC_UNPROCESSABLE_ENTITY, new ErrorMessage(List.of("name must not be blank")),
                            ErrorMessage.class),
                    arguments(
                            new CreatePromptVersion(UUID.randomUUID().toString(),
                                    factory.manufacturePojo(PromptVersion.class)
                                            .toBuilder().commit("").build()),
                            HttpStatus.SC_UNPROCESSABLE_ENTITY,
                            new ErrorMessage(List.of(
                                    "version.commit if present, the commit message must be 8 alphanumeric characters long")),
                            ErrorMessage.class),
                    arguments(
                            new CreatePromptVersion(UUID.randomUUID().toString(),
                                    factory.manufacturePojo(PromptVersion.class)
                                            .toBuilder().commit("1234567").build()),
                            HttpStatus.SC_UNPROCESSABLE_ENTITY,
                            new ErrorMessage(List.of(
                                    "version.commit if present, the commit message must be 8 alphanumeric characters long")),
                            ErrorMessage.class),
                    arguments(
                            new CreatePromptVersion(UUID.randomUUID().toString(),
                                    factory.manufacturePojo(PromptVersion.class)
                                            .toBuilder().commit("1234-567").build()),
                            HttpStatus.SC_UNPROCESSABLE_ENTITY,
                            new ErrorMessage(List.of(
                                    "version.commit if present, the commit message must be 8 alphanumeric characters long")),
                            ErrorMessage.class),
                    arguments(
                            new CreatePromptVersion(UUID.randomUUID().toString(),
                                    factory.manufacturePojo(PromptVersion.class)
                                            .toBuilder().id(UUID.randomUUID()).build()),
                            HttpStatus.SC_BAD_REQUEST,
                            new ErrorMessage(List.of("prompt version id must be a version 7 UUID")),
                            ErrorMessage.class),
                    arguments(
                            new CreatePromptVersion(UUID.randomUUID().toString(),
                                    factory.manufacturePojo(PromptVersion.class)
                                            .toBuilder().template("").build()),
                            HttpStatus.SC_UNPROCESSABLE_ENTITY,
                            new ErrorMessage(List.of("version.template must not be blank")),
                            ErrorMessage.class),
                    arguments(
                            new CreatePromptVersion(UUID.randomUUID().toString(),
                                    factory.manufacturePojo(PromptVersion.class)
                                            .toBuilder().template(null).build()),
                            HttpStatus.SC_UNPROCESSABLE_ENTITY,
                            new ErrorMessage(List.of("version.template must not be blank")),
                            ErrorMessage.class));
        }
    }

    @Nested
    @DisplayName("Get Prompt Versions by Prompt Id")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetPromptVersionsByPromptId {

        @Test
        @DisplayName("Success: should get prompt versions by prompt id")
        void shouldGetPromptVersionsByPromptId() {

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .build();

            UUID promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            var prompt2 = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .build();

            createPrompt(prompt2, API_KEY, TEST_WORKSPACE);

            var promptVersion = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .createdBy(USER)
                    .build();

            var request = new CreatePromptVersion(prompt.name(), promptVersion);

            createPromptVersion(request, API_KEY, TEST_WORKSPACE);

            List<PromptVersion> expectedPromptVersions = List.of(promptVersion);

            findPromptVersionsAndAssertPage(expectedPromptVersions, promptId, API_KEY, TEST_WORKSPACE,
                    expectedPromptVersions.size(), 1, expectedPromptVersions.size());
        }

        @Test
        @DisplayName("when prompt version has multiple versions, then return prompt versions sorted by creation time")
        void when__promptVersionHasMultipleVersions__thenReturnPromptVersionsSortedByCreationTime() {

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .build();

            UUID promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            var prompt2 = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .build();

            createPrompt(prompt2, API_KEY, TEST_WORKSPACE);

            var promptVersions = IntStream.range(0, 20)
                    .mapToObj(i -> factory.manufacturePojo(PromptVersion.class).toBuilder()
                            .createdBy(USER)
                            .build())
                    .toList();

            promptVersions
                    .forEach(promptVersion -> createPromptVersion(new CreatePromptVersion(prompt.name(), promptVersion),
                            API_KEY, TEST_WORKSPACE));

            List<PromptVersion> expectedPromptVersionPage1 = promptVersions.reversed().subList(0, 10);
            List<PromptVersion> expectedPromptVersionPage2 = promptVersions.reversed().subList(10, 20);

            findPromptVersionsAndAssertPage(expectedPromptVersionPage1, promptId, API_KEY, TEST_WORKSPACE, 10, 1,
                    promptVersions.size());
            findPromptVersionsAndAssertPage(expectedPromptVersionPage2, promptId, API_KEY, TEST_WORKSPACE, 10, 2,
                    promptVersions.size());
        }

        @Test
        @DisplayName("when fetch prompt versions using pagination, then return prompt versions paginated")
        void when__fetchPromptVersionsUsingPagination__thenReturnPromptVersionsPaginated() {

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .build();

            UUID promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            var promptVersions = IntStream.range(0, 10)
                    .mapToObj(i -> factory.manufacturePojo(PromptVersion.class).toBuilder()
                            .createdBy(USER)
                            .build())
                    .toList();

            promptVersions
                    .forEach(promptVersion -> createPromptVersion(new CreatePromptVersion(prompt.name(), promptVersion),
                            API_KEY, TEST_WORKSPACE));

            List<PromptVersion> promptVersionPage1 = promptVersions.reversed().subList(0, 2);
            List<PromptVersion> promptVersionPage2 = promptVersions.reversed().subList(2, 4);
            List<PromptVersion> promptVersionPage3 = promptVersions.reversed().subList(4, 6);
            List<PromptVersion> promptVersionPage4 = promptVersions.reversed().subList(6, 8);
            List<PromptVersion> promptVersionPage5 = promptVersions.reversed().subList(8, 10);

            findPromptVersionsAndAssertPage(promptVersionPage1, promptId, API_KEY, TEST_WORKSPACE, 2, 1,
                    promptVersions.size());
            findPromptVersionsAndAssertPage(promptVersionPage2, promptId, API_KEY, TEST_WORKSPACE, 2, 2,
                    promptVersions.size());
            findPromptVersionsAndAssertPage(promptVersionPage3, promptId, API_KEY, TEST_WORKSPACE, 2, 3,
                    promptVersions.size());
            findPromptVersionsAndAssertPage(promptVersionPage4, promptId, API_KEY, TEST_WORKSPACE, 2, 4,
                    promptVersions.size());
            findPromptVersionsAndAssertPage(promptVersionPage5, promptId, API_KEY, TEST_WORKSPACE, 2, 5,
                    promptVersions.size());
        }

        @Test
        @DisplayName("when prompt has not versions, then return empty page")
        void when__promptHasNotVersions__thenReturnEmptyPage() {

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .build();

            UUID promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            List<PromptVersion> expectedPromptVersions = List.of();

            findPromptVersionsAndAssertPage(expectedPromptVersions, promptId, API_KEY, TEST_WORKSPACE, null, 1,
                    expectedPromptVersions.size());
        }
    }

    private void findPromptVersionsAndAssertPage(List<PromptVersion> expectedPromptVersions, UUID promptId,
            String apiKey, String workspaceName, Integer size, int page, int total) {
        WebTarget target = client.target(RESOURCE_PATH.formatted(baseURI) + "/%s/versions".formatted(promptId));

        if (page > 1) {
            target = target.queryParam("page", page);
        }

        if (size != null) {
            target = target.queryParam("size", size);
        }

        try (var response = target
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(response.getStatus()).isEqualTo(200);

            var promptVersionPage = response.readEntity(PromptVersion.PromptVersionPage.class);

            assertThat(promptVersionPage.total()).isEqualTo(total);
            assertThat(promptVersionPage.content()).hasSize(expectedPromptVersions.size());
            assertThat(promptVersionPage.page()).isEqualTo(page);
            assertThat(promptVersionPage.size()).isEqualTo(expectedPromptVersions.size());

            assertThat(promptVersionPage.content())
                    .usingRecursiveComparison(
                            RecursiveComparisonConfiguration.builder()
                                    .withIgnoredFields("variables", "promptId")
                                    .withComparatorForType(this::comparatorForCreateAtAndUpdatedAt, Instant.class)
                                    .build())
                    .isEqualTo(expectedPromptVersions);

            assertThat(promptVersionPage.content().stream().map(PromptVersion::promptId).toList())
                    .allMatch(id -> id.equals(promptId));
        }
    }

    private void assertPromptVersionConflict(CreatePromptVersion request, String apiKey, String workspaceName,
            String message) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI) + "/versions")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CONFLICT);

            var errorMessage = response.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class);

            io.dropwizard.jersey.errors.ErrorMessage expectedError = new io.dropwizard.jersey.errors.ErrorMessage(
                    HttpStatus.SC_CONFLICT,
                    message);

            assertThat(errorMessage).isEqualTo(expectedError);
        }
    }

    private List<Prompt> getPrompts(String nameSearch, String apiKey, String workspaceName) {
        WebTarget target = client.target(RESOURCE_PATH.formatted(baseURI));

        if (nameSearch != null) {
            target = target.queryParam("name", nameSearch);
        }

        try (var response = target.request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);

            return response.readEntity(Prompt.PromptPage.class).content();
        }
    }

    private void assertPromptVersion(PromptVersion createdPromptVersion, PromptVersion promptVersion, UUID promptId) {
        assertThat(createdPromptVersion).isNotNull();

        if (promptVersion.commit() == null) {
            assertThat(createdPromptVersion.commit()).isNotNull();
        } else {
            assertThat(createdPromptVersion.commit()).isEqualTo(promptVersion.commit());
        }

        UUID id = createdPromptVersion.id();

        if (promptVersion.id() == null) {
            assertThat(id).isNotNull();
        } else {
            assertThat(id).isEqualTo(promptVersion.id());
        }

        assertThat(id.toString().substring(id.toString().length() - 8))
                .isEqualTo(createdPromptVersion.commit());

        assertThat(createdPromptVersion.promptId()).isEqualTo(promptId);
        assertThat(createdPromptVersion.template()).isEqualTo(promptVersion.template());
        assertThat(createdPromptVersion.variables()).isEqualTo(promptVersion.variables());
        assertThat(createdPromptVersion.createdAt()).isBetween(promptVersion.createdAt(), Instant.now());
        assertThat(createdPromptVersion.createdBy()).isEqualTo(USER);
    }

    private PromptVersion createPromptVersion(CreatePromptVersion promptVersion, String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI) + "/versions")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(promptVersion))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);

            return response.readEntity(PromptVersion.class);
        }
    }

    private void findPromptsAndAssertPage(List<Prompt> expectedPrompts, String apiKey, String workspaceName,
            int expectedTotal, int page, String nameSearch) {

        WebTarget target = client.target(RESOURCE_PATH.formatted(baseURI));

        if (nameSearch != null) {
            target = target.queryParam("name", nameSearch);
        }

        if (page > 1) {
            target = target.queryParam("page", page);
        }

        try (var response = target
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);

            var promptPage = response.readEntity(Prompt.PromptPage.class);

            assertThat(promptPage.total()).isEqualTo(expectedTotal);
            assertThat(promptPage.content()).hasSize(expectedPrompts.size());
            assertThat(promptPage.page()).isEqualTo(page);
            assertThat(promptPage.size()).isEqualTo(expectedPrompts.size());

            assertThat(promptPage.content())
                    .usingRecursiveComparison(
                            RecursiveComparisonConfiguration.builder()
                                    .withIgnoredFields(IGNORED_FIELDS)
                                    .withComparatorForType(this::comparatorForCreateAtAndUpdatedAt, Instant.class)
                                    .build())
                    .isEqualTo(expectedPrompts);
        }
    }

    private int comparatorForCreateAtAndUpdatedAt(Instant actual, Instant expected) {
        var now = Instant.now();

        if (actual.isAfter(now) || actual.equals(now))
            return 1;
        if (actual.isBefore(expected))
            return -1;

        Assertions.assertThat(actual).isBetween(expected, now);
        return 0;
    }
}