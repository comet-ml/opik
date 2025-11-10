package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.CreatePromptVersion;
import com.comet.opik.api.Prompt;
import com.comet.opik.api.PromptType;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.PromptVersionRetrieve;
import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.PromptField;
import com.comet.opik.api.filter.PromptFilter;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortableFields;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.TemplateParseUtils;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.function.TriFunction;
import org.apache.hc.core5.http.HttpStatus;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.FAKE_API_KEY_MESSAGE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.NO_API_KEY_RESPONSE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.UNAUTHORIZED_RESPONSE;
import static com.comet.opik.api.resources.utils.TestUtils.toURLEncodedQueryParam;
import static com.comet.opik.api.sorting.SortableFields.CREATED_AT;
import static com.comet.opik.api.sorting.SortableFields.CREATED_BY;
import static com.comet.opik.api.sorting.SortableFields.DESCRIPTION;
import static com.comet.opik.api.sorting.SortableFields.ID;
import static com.comet.opik.api.sorting.SortableFields.LAST_UPDATED_AT;
import static com.comet.opik.api.sorting.SortableFields.LAST_UPDATED_BY;
import static com.comet.opik.api.sorting.SortableFields.NAME;
import static com.comet.opik.api.sorting.SortableFields.TAGS;
import static com.comet.opik.infrastructure.auth.RequestContext.SESSION_COOKIE;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Prompt Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class PromptResourceTest {

    private static final String RESOURCE_PATH = "%s/v1/private/prompts";
    public static final String[] PROMPT_IGNORED_FIELDS = {"latestVersion", "template", "metadata", "changeDescription",
            "type"};

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    private final WireMockUtils.WireMockRuntime wireMock;

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

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;

    @BeforeAll
    void setUpAll(ClientSupport client) {

        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;

        ClientSupportUtils.config(client);

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
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
                    arguments(okApikey, true, null),
                    arguments(fakeApikey, false, UNAUTHORIZED_RESPONSE),
                    arguments("", false, NO_API_KEY_RESPONSE));
        }

        @BeforeEach
        void setUp() {

            wireMock.server().stubFor(
                    post(urlPathEqualTo("/opik/auth"))
                            .withHeader(HttpHeaders.AUTHORIZATION, equalTo(fakeApikey))
                            .withRequestBody(matchingJsonPath("$.workspaceName", matching(".+")))
                            .willReturn(WireMock.unauthorized().withHeader("Content-Type", "application/json")
                                    .withJsonBody(JsonUtils.readTree(
                                            new ReactServiceErrorResponse(FAKE_API_KEY_MESSAGE,
                                                    401)))));
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("create prompt: when api key is present, then return proper response")
        void createPrompt__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {

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
                            .isEqualTo(errorMessage);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("find prompt: when api key is present, then return proper response")
        void findPrompt__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {

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
                            .isEqualTo(errorMessage);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("update prompt: when api key is present, then return proper response")
        void updatePrompt__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {
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
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(errorMessage);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("delete prompt: when api key is present, then return proper response")
        void deletePrompt__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {
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
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(errorMessage);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("get prompt by id: when api key is present, then return proper response")
        void getPromptById__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {
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
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
                    assertThat(actualResponse.hasEntity()).isTrue();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(errorMessage);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("Create prompt versions: when api key is present, then return proper response")
        void createPromptVersions__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {
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
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
                    assertThat(actualResponse.hasEntity()).isTrue();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(errorMessage);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("Get prompt versions by prompt id: when api key is present, then return proper response")
        void getPromptVersionsByPromptId__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey,
                boolean success, io.dropwizard.jersey.errors.ErrorMessage errorMessage) {
            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .versionCount(0L)
                    .build();

            UUID promptId = createPrompt(prompt, okApikey, workspaceName);

            try (var actualResponse = client
                    .target(RESOURCE_PATH.formatted(baseURI) + "/%s/versions".formatted(promptId))
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
                            .isEqualTo(errorMessage);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("Get prompt versions by id: when api key is present, then return proper response")
        void getPromptVersionsById__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean success,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {
            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .versionCount(0L)
                    .build();

            UUID promptId = createPrompt(prompt, okApikey, workspaceName);

            var promptVersion = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .promptId(promptId)
                    .build();

            CreatePromptVersion request = new CreatePromptVersion(prompt.name(), promptVersion);

            promptVersion = createPromptVersion(request, okApikey, workspaceName);

            try (var actualResponse = client
                    .target(RESOURCE_PATH.formatted(baseURI) + "/%s/versions".formatted(promptVersion.promptId()))
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
                            .isEqualTo(errorMessage);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("Retrieve prompt versions by name and commit: when api key is present, then return proper response")
        void retrievePromptVersionsByNameAndCommit__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey,
                boolean success, io.dropwizard.jersey.errors.ErrorMessage errorMessage) {
            String workspaceName = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var promptVersion = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .createdBy(USER)
                    .build();

            var request = new CreatePromptVersion(UUID.randomUUID().toString(), promptVersion);

            promptVersion = createPromptVersion(request, okApikey, workspaceName);

            var promptVersionRetrieve = new PromptVersionRetrieve(request.name(), promptVersion.commit());

            try (var actualResponse = client
                    .target(RESOURCE_PATH.formatted(baseURI) + "/versions/retrieve")
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(promptVersionRetrieve))) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
                    assertThat(actualResponse.hasEntity()).isTrue();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.hasEntity()).isTrue();
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(errorMessage);
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
                            .willReturn(WireMock.unauthorized().withHeader("Content-Type", "application/json")
                                    .withJsonBody(JsonUtils.readTree(
                                            new ReactServiceErrorResponse(FAKE_API_KEY_MESSAGE,
                                                    401)))));
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
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
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
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
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

            try (var actualResponse = client
                    .target(RESOURCE_PATH.formatted(baseURI) + "/%s/versions".formatted(promptId))
                    .request()
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
        @DisplayName("Get prompt versions by id: when session token is present, then return proper response")
        void getPromptVersionsById__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                boolean success,
                String workspaceName) {

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .versionCount(0L)
                    .build();

            UUID promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            var promptVersion = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .promptId(promptId)
                    .build();

            CreatePromptVersion request = new CreatePromptVersion(prompt.name(), promptVersion);

            promptVersion = createPromptVersion(request, API_KEY, TEST_WORKSPACE);

            try (var actualResponse = client
                    .target(RESOURCE_PATH.formatted(baseURI) + "/versions/%s".formatted(promptVersion.id()))
                    .request()
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
        @DisplayName("Retrieve prompt versions by name and commit: when session token is present, then return proper response")
        void retrievePromptVersionsByNameAndCommit__whenSessionTokenIsPresent__thenReturnProperResponse(
                String sessionToken,
                boolean success,
                String workspaceName) {

            var promptVersion = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .createdBy(USER)
                    .build();

            var request = new CreatePromptVersion(UUID.randomUUID().toString(), promptVersion);

            promptVersion = createPromptVersion(request, API_KEY, TEST_WORKSPACE);

            var promptVersionRetrieve = new PromptVersionRetrieve(request.name(), promptVersion.commit());

            try (var actualResponse = client
                    .target(RESOURCE_PATH.formatted(baseURI) + "/versions/retrieve")
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(promptVersionRetrieve))) {

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

        @ParameterizedTest
        @NullSource
        @EnumSource(PromptType.class)
        @DisplayName("Success: should create prompt")
        void shouldCreatePrompt(PromptType type) {

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .type(type)
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
                    Arguments.of(factory.manufacturePojo(Prompt.class).toBuilder().description("a".repeat(256)).build(),
                            HttpStatus.SC_UNPROCESSABLE_ENTITY,
                            new ErrorMessage(List.of("description cannot exceed 255 characters")),
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

            updatedPrompt = updatedPrompt.toBuilder()
                    .tags(updatedPrompt.tags() == null ? prompt.tags() : updatedPrompt.tags())
                    .build();

            assertThat(actualPrompt)
                    .usingRecursiveComparison(
                            RecursiveComparisonConfiguration.builder()
                                    .withIgnoredFields(PROMPT_IGNORED_FIELDS)
                                    .withComparatorForType(PromptResourceTest::comparatorForCreateAtAndUpdatedAt,
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
                            .description(UUID.randomUUID().toString())
                            .tags(null)
                            .build()),
                    arguments((Function<Prompt, Prompt>) prompt -> prompt.toBuilder().description(null).build()),
                    arguments((Function<Prompt, Prompt>) prompt -> prompt.toBuilder()
                            .tags(Set.of()).build()),
                    arguments((Function<Prompt, Prompt>) prompt -> prompt.toBuilder()
                            .tags(PodamFactoryUtils.manufacturePojoSet(factory, String.class)).build()));
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
                            ErrorMessage.class),
                    Arguments.of(factory.manufacturePojo(Prompt.class).toBuilder().description("a".repeat(256)).build(),
                            HttpStatus.SC_UNPROCESSABLE_ENTITY,
                            new ErrorMessage(List.of("description cannot exceed 255 characters")),
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
        @DisplayName("when prompt does not exist, then return not found")
        void when__promptDoesNotExist__thenReturnNotFound() {

            UUID promptId = UUID.randomUUID();

            getPromptAndAssertNotFound(promptId, API_KEY, TEST_WORKSPACE);

            try (var response = client.target(RESOURCE_PATH.formatted(baseURI) + "/%s".formatted(promptId))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(RequestContext.WORKSPACE_HEADER, TEST_WORKSPACE)
                    .delete()) {

                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NOT_FOUND);
                assertThat(response.hasEntity()).isTrue();
                assertThat(response.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                        .isEqualTo(new io.dropwizard.jersey.errors.ErrorMessage(HttpStatus.SC_NOT_FOUND,
                                "Prompt not found"));
            }

            getPromptAndAssertNotFound(promptId, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("delete batch of prompts")
        void deleteBatch() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var ids = PodamFactoryUtils.manufacturePojoList(factory,
                    Prompt.class).stream()
                    .map(prompt -> createPrompt(prompt.toBuilder()
                            .lastUpdatedBy(USER)
                            .createdBy(USER)
                            .build(), apiKey, workspaceName))
                    .toList();
            var idsToDelete = ids.subList(0, 3);
            var notDeletedIds = ids.subList(3, ids.size());

            try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                    .path("delete")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(new BatchDelete(new HashSet<>(idsToDelete))))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                    .queryParam("size", ids.size())
                    .queryParam("page", 1)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            var actualEntity = actualResponse.readEntity(Prompt.PromptPage.class);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
            assertThat(actualEntity.size()).isEqualTo(notDeletedIds.size());
            assertThat(actualEntity.content().stream().map(Prompt::id).toList())
                    .usingRecursiveComparison()
                    .ignoringCollectionOrder()
                    .isEqualTo(notDeletedIds);
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

            findPromptsAndAssertPage(expectedPrompts, apiKey, workspaceName, expectedPrompts.size(), 1, null, null,
                    null);
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

            findPromptsAndAssertPage(expectedPrompts, apiKey, workspaceName, expectedPrompts.size(), 1, prompt.name(),
                    null, null);
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

            findPromptsAndAssertPage(expectedPrompts, apiKey, workspaceName, expectedPrompts.size(), 1, partialSearch,
                    null, null);
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
            findPromptsAndAssertPage(expectedPrompts, apiKey, workspaceName, expectedPrompts.size(), 1, partialSearch,
                    null, null);
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

            findPromptsAndAssertPage(expectedPrompts, apiKey, workspaceName, expectedPrompts.size(), 1, null, null,
                    null);
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

            findPromptsAndAssertPage(promptPage1, apiKey, workspaceName, prompts.size(), 1, null, null, null);
            findPromptsAndAssertPage(promptPage2, apiKey, workspaceName, prompts.size(), 2, null, null, null);
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when sorting prompts by valid fields, then return sorted prompts")
        void getPrompts__whenSortingByValidFields__thenReturnTracePromptsSorted(Comparator<Prompt> comparator,
                SortingField sorting) {

            String apiKey = UUID.randomUUID().toString();
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var random = new Random();

            var prompts = PodamFactoryUtils.manufacturePojoList(factory, Prompt.class).stream()
                    .map(prompt -> prompt.toBuilder()
                            .lastUpdatedBy(USER)
                            .createdBy(USER)
                            .versionCount(random.nextLong(5))
                            .template(null)
                            .build())
                    .toList();

            prompts.forEach(prompt -> {
                createPrompt(prompt, apiKey, workspaceName);
                for (int i = 0; i < prompt.versionCount(); i++) {
                    var promptVersion = factory.manufacturePojo(PromptVersion.class).toBuilder()
                            .createdBy(USER)
                            .build();
                    var request = new CreatePromptVersion(prompt.name(), promptVersion);
                    createPromptVersion(request, apiKey, workspaceName);
                }
            });

            List<Prompt> expectedPrompts = prompts.stream().sorted(comparator).toList();

            findPromptsAndAssertPage(expectedPrompts, apiKey, workspaceName, expectedPrompts.size(), 1, null,
                    List.of(sorting), null);
        }

        private Stream<Arguments> getPrompts__whenSortingByValidFields__thenReturnTracePromptsSorted() {
            // Comparators for all sortable fields
            Comparator<Prompt> idComparator = Comparator.comparing(Prompt::id);
            Comparator<Prompt> nameComparator = Comparator.comparing(Prompt::name, String.CASE_INSENSITIVE_ORDER);
            Comparator<Prompt> descriptionComparator = Comparator.comparing(
                    prompt -> prompt.description() != null ? prompt.description().toLowerCase() : "",
                    String.CASE_INSENSITIVE_ORDER);
            Comparator<Prompt> createdAtComparator = Comparator.comparing(Prompt::createdAt);
            Comparator<Prompt> lastUpdatedAtComparator = Comparator.comparing(Prompt::lastUpdatedAt);
            Comparator<Prompt> createdByComparator = Comparator.comparing(Prompt::createdBy,
                    String.CASE_INSENSITIVE_ORDER);
            Comparator<Prompt> lastUpdatedByComparator = Comparator.comparing(Prompt::lastUpdatedBy,
                    String.CASE_INSENSITIVE_ORDER);
            Comparator<Prompt> tagsComparator = Comparator.comparing(prompt -> prompt.tags().toString().toLowerCase());
            Comparator<Prompt> versionCountComparator = Comparator.comparing(Prompt::versionCount);

            Comparator<Prompt> idComparatorReversed = Comparator.comparing(Prompt::id).reversed();

            return Stream.of(
                    // ID field sorting
                    Arguments.of(
                            idComparator,
                            SortingField.builder().field(SortableFields.ID).direction(Direction.ASC).build()),
                    Arguments.of(
                            idComparator.reversed(),
                            SortingField.builder().field(SortableFields.ID).direction(Direction.DESC).build()),

                    // NAME field sorting
                    Arguments.of(
                            nameComparator,
                            SortingField.builder().field(SortableFields.NAME).direction(Direction.ASC).build()),
                    Arguments.of(
                            nameComparator.reversed(),
                            SortingField.builder().field(SortableFields.NAME).direction(Direction.DESC).build()),

                    // DESCRIPTION field sorting
                    Arguments.of(
                            descriptionComparator,
                            SortingField.builder().field(SortableFields.DESCRIPTION).direction(Direction.ASC).build()),
                    Arguments.of(
                            descriptionComparator.reversed(),
                            SortingField.builder().field(SortableFields.DESCRIPTION).direction(Direction.DESC).build()),

                    // CREATED_AT field sorting
                    Arguments.of(
                            createdAtComparator,
                            SortingField.builder().field(SortableFields.CREATED_AT).direction(Direction.ASC).build()),
                    Arguments.of(
                            createdAtComparator.reversed(),
                            SortingField.builder().field(SortableFields.CREATED_AT).direction(Direction.DESC).build()),

                    // LAST_UPDATED_AT field sorting
                    Arguments.of(
                            lastUpdatedAtComparator,
                            SortingField.builder().field(SortableFields.LAST_UPDATED_AT).direction(Direction.ASC)
                                    .build()),
                    Arguments.of(
                            lastUpdatedAtComparator.reversed(),
                            SortingField.builder().field(SortableFields.LAST_UPDATED_AT).direction(Direction.DESC)
                                    .build()),

                    // CREATED_BY field sorting
                    Arguments.of(
                            createdByComparator.thenComparing(Prompt::lastUpdatedAt).reversed(),
                            SortingField.builder().field(SortableFields.CREATED_BY).direction(Direction.ASC).build()),
                    Arguments.of(
                            createdByComparator.reversed().thenComparing(Prompt::lastUpdatedAt).reversed(),
                            SortingField.builder().field(SortableFields.CREATED_BY).direction(Direction.DESC).build()),

                    // LAST_UPDATED_BY field sorting
                    Arguments.of(
                            lastUpdatedByComparator.thenComparing(Prompt::lastUpdatedAt).reversed(),
                            SortingField.builder().field(SortableFields.LAST_UPDATED_BY).direction(Direction.ASC)
                                    .build()),
                    Arguments.of(
                            lastUpdatedByComparator.reversed().thenComparing(Prompt::lastUpdatedAt).reversed(),
                            SortingField.builder().field(SortableFields.LAST_UPDATED_BY).direction(Direction.DESC)
                                    .build()),

                    // VERSION_COUNT field sorting
                    Arguments.of(
                            versionCountComparator.thenComparing(idComparatorReversed),
                            SortingField.builder().field(SortableFields.VERSION_COUNT).direction(Direction.ASC)
                                    .build()),
                    Arguments.of(
                            versionCountComparator.reversed().thenComparing(idComparatorReversed),
                            SortingField.builder().field(SortableFields.VERSION_COUNT).direction(Direction.DESC)
                                    .build()),

                    // TAGS field sorting
                    Arguments.of(
                            tagsComparator,
                            SortingField.builder().field(SortableFields.TAGS).direction(Direction.ASC).build()),
                    Arguments.of(
                            tagsComparator.reversed(),
                            SortingField.builder().field(SortableFields.TAGS).direction(Direction.DESC).build()));
        }

        @ParameterizedTest
        @MethodSource("getValidFilters")
        @DisplayName("when filter prompts by valid fields, then return filtered prompts")
        void whenFilterPrompts__thenReturnPromptsFiltered(Function<List<Prompt>, PromptFilter> getFilter,
                Function<List<Prompt>, List<Prompt>> getExpectedPrompts) {

            String apiKey = UUID.randomUUID().toString();
            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var random = new Random();

            var prompts = PodamFactoryUtils.manufacturePojoList(factory, Prompt.class).stream()
                    .map(prompt -> prompt.toBuilder()
                            .lastUpdatedBy(USER)
                            .createdBy(USER)
                            .versionCount(random.nextLong(5))
                            .template(null)
                            .build())
                    .toList();

            prompts.forEach(prompt -> {
                createPrompt(prompt, apiKey, workspaceName);
                for (int i = 0; i < prompt.versionCount(); i++) {
                    var promptVersion = factory.manufacturePojo(PromptVersion.class).toBuilder()
                            .createdBy(USER)
                            .build();
                    var request = new CreatePromptVersion(prompt.name(), promptVersion);
                    createPromptVersion(request, apiKey, workspaceName);
                }
            });

            List<Prompt> expectedPrompts = getExpectedPrompts.apply(prompts);
            PromptFilter filter = getFilter.apply(prompts);

            findPromptsAndAssertPage(expectedPrompts.reversed(), apiKey, workspaceName, expectedPrompts.size(), 1, null,
                    null, List.of(filter));
        }

        private Stream<Arguments> getValidFilters() {
            Integer random = new Random().nextInt(5);
            return Stream.of(
                    Arguments.of(
                            (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                    .field(PromptField.TAGS)
                                    .operator(Operator.CONTAINS)
                                    .value(prompts.getFirst().tags().iterator().next())
                                    .build(),
                            (Function<List<Prompt>, List<Prompt>>) prompts -> List.of(prompts.getFirst())),
                    Arguments.of(
                            (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                    .field(PromptField.TAGS)
                                    .operator(Operator.NOT_CONTAINS)
                                    .value(prompts.getFirst().tags().iterator().next())
                                    .build(),
                            (Function<List<Prompt>, List<Prompt>>) prompts -> prompts.subList(1, prompts.size())),
                    Arguments.of(
                            (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                    .field(PromptField.ID)
                                    .operator(Operator.EQUAL)
                                    .value(prompts.getFirst().id().toString())
                                    .build(),
                            (Function<List<Prompt>, List<Prompt>>) prompts -> List.of(prompts.getFirst())),
                    Arguments.of(
                            (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                    .field(PromptField.ID)
                                    .operator(Operator.NOT_EQUAL)
                                    .value(prompts.getFirst().id().toString())
                                    .build(),
                            (Function<List<Prompt>, List<Prompt>>) prompts -> prompts.subList(1, prompts.size())),
                    Arguments.of(
                            (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                    .field(PromptField.NAME)
                                    .operator(Operator.STARTS_WITH)
                                    .value(prompts.getFirst().name().substring(0, 3))
                                    .build(),
                            (Function<List<Prompt>, List<Prompt>>) prompts -> List.of(prompts.getFirst())),
                    Arguments.of(
                            (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                    .field(PromptField.NAME)
                                    .operator(Operator.ENDS_WITH)
                                    .value(prompts.getFirst().name().substring(3))
                                    .build(),
                            (Function<List<Prompt>, List<Prompt>>) prompts -> List.of(prompts.getFirst())),
                    Arguments.of(
                            (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                    .field(PromptField.VERSION_COUNT)
                                    .operator(Operator.GREATER_THAN_EQUAL)
                                    .value(String.valueOf(random))
                                    .build(),
                            (Function<List<Prompt>, List<Prompt>>) prompts -> prompts.stream()
                                    .filter(prompt -> prompt.versionCount() >= random)
                                    .toList()),
                    Arguments.of(
                            (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                    .field(PromptField.VERSION_COUNT)
                                    .operator(Operator.LESS_THAN_EQUAL)
                                    .value(String.valueOf(random))
                                    .build(),
                            (Function<List<Prompt>, List<Prompt>>) prompts -> prompts.stream()
                                    .filter(prompt -> prompt.versionCount() <= random)
                                    .toList()),
                    Arguments.of(
                            (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                    .field(PromptField.CREATED_BY)
                                    .operator(Operator.STARTS_WITH)
                                    .value(USER.substring(0, 3))
                                    .build(),
                            (Function<List<Prompt>, List<Prompt>>) prompts -> prompts),
                    Arguments.of(
                            (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                    .field(PromptField.CREATED_BY)
                                    .operator(Operator.EQUAL)
                                    .value(USER)
                                    .build(),
                            (Function<List<Prompt>, List<Prompt>>) prompts -> prompts),
                    Arguments.of(
                            (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                    .field(PromptField.LAST_UPDATED_BY)
                                    .operator(Operator.NOT_EQUAL)
                                    .value(USER)
                                    .build(),
                            (Function<List<Prompt>, List<Prompt>>) prompts -> List.of()),
                    Arguments.of(
                            (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                    .field(PromptField.LAST_UPDATED_BY)
                                    .operator(Operator.CONTAINS)
                                    .value(USER.substring(0, 3))
                                    .build(),
                            (Function<List<Prompt>, List<Prompt>>) prompts -> prompts),
                    Arguments.of(
                            (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                    .field(PromptField.DESCRIPTION)
                                    .operator(Operator.EQUAL)
                                    .value(prompts.getFirst().description())
                                    .build(),
                            (Function<List<Prompt>, List<Prompt>>) prompts -> List.of(prompts.getFirst())),
                    Arguments.of(
                            (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                    .field(PromptField.DESCRIPTION)
                                    .operator(Operator.NOT_EQUAL)
                                    .value(prompts.getFirst().description())
                                    .build(),
                            (Function<List<Prompt>, List<Prompt>>) prompts -> prompts.subList(1, prompts.size())),
                    Arguments.of(
                            (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                    .field(PromptField.CREATED_AT)
                                    .operator(Operator.EQUAL)
                                    .value(prompts.getFirst().createdAt().toString())
                                    .build(),
                            (Function<List<Prompt>, List<Prompt>>) prompts -> List.of()),
                    Arguments.of(
                            (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                    .field(PromptField.CREATED_AT)
                                    .operator(Operator.NOT_EQUAL)
                                    .value(Instant.now().toString())
                                    .build(),
                            (Function<List<Prompt>, List<Prompt>>) prompts -> prompts),
                    Arguments.of(
                            (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                    .field(PromptField.CREATED_AT)
                                    .operator(Operator.GREATER_THAN)
                                    .value(Instant.now().minus(5, ChronoUnit.SECONDS).toString())
                                    .build(),
                            (Function<List<Prompt>, List<Prompt>>) prompts -> prompts),
                    Arguments.of(
                            (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                    .field(PromptField.LAST_UPDATED_AT)
                                    .operator(Operator.GREATER_THAN_EQUAL)
                                    .value(Instant.now().toString())
                                    .build(),
                            (Function<List<Prompt>, List<Prompt>>) prompts -> List.of()),
                    Arguments.of(
                            (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                    .field(PromptField.LAST_UPDATED_AT)
                                    .operator(Operator.LESS_THAN)
                                    .value(Instant.now().toString())
                                    .build(),
                            (Function<List<Prompt>, List<Prompt>>) prompts -> prompts),
                    Arguments.of(
                            (Function<List<Prompt>, PromptFilter>) prompts -> PromptFilter.builder()
                                    .field(PromptField.LAST_UPDATED_AT)
                                    .operator(Operator.LESS_THAN_EQUAL)
                                    .value(Instant.now().minus(5, ChronoUnit.SECONDS).toString())
                                    .build(),
                            (Function<List<Prompt>, List<Prompt>>) prompts -> List.of()));
        }
    }

    @Nested
    @DisplayName("Get Prompt by Id")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetPromptById {

        @ParameterizedTest
        @NullSource
        @EnumSource(PromptType.class)
        @DisplayName("Success: should get prompt by id")
        void shouldGetPromptById(PromptType type) {

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .versionCount(1L)
                    .type(type)
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
                    .metadata(promptVersion.metadata())
                    .changeDescription(promptVersion.changeDescription())
                    .type(promptVersion.type())
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
                                    .withIgnoredFields(PROMPT_IGNORED_FIELDS)
                                    .withComparatorForType(
                                            PromptResourceTest::comparatorForCreateAtAndUpdatedAt,
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
        assertThat(promptVersion.metadata()).isEqualTo(expectedPrompt.metadata());
        assertThat(promptVersion.changeDescription()).isEqualTo(expectedPrompt.changeDescription());
        assertThat(promptVersion.type()).isEqualTo(expectedPrompt.type());
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

    @Nested
    @DisplayName("Get Prompt Version by Id")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetPromptVersionById {

        @ParameterizedTest
        @NullSource
        @EnumSource(PromptType.class)
        @DisplayName("Success: should get prompt version by id")
        void shouldGetPromptVersionById(PromptType type) {

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .build();

            UUID promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            var promptVersion = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .createdBy(USER)
                    .promptId(promptId)
                    .type(type)
                    .build();

            var request = new CreatePromptVersion(prompt.name(), promptVersion);

            var createdPromptVersion = createPromptVersion(request, API_KEY, TEST_WORKSPACE);

            getPromptVersionAndAssert(createdPromptVersion.id(), createdPromptVersion, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("when prompt version does not exist, then return not found")
        void when__promptVersionDoesNotExist__thenReturnNotFound() {

            UUID promptVersionId = UUID.randomUUID();

            try (var response = client
                    .target(RESOURCE_PATH.formatted(baseURI) + "/versions/%s".formatted(promptVersionId))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(RequestContext.WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NOT_FOUND);
                assertThat(response.hasEntity()).isTrue();
                assertThat(response.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                        .isEqualTo(new io.dropwizard.jersey.errors.ErrorMessage(404, "Prompt version not found"));
            }
        }
    }

    @Nested
    @DisplayName("Retrieve Prompt Version")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class RetrievePromptVersions {

        @ParameterizedTest
        @MethodSource
        @DisplayName("Success: should retrieve prompt version by prompt name and version commit")
        void shouldRetrievePromptVersion(
                TriFunction<PromptVersion, PromptVersion, String, PromptVersionRetrieve> retrievePrompt,
                BiFunction<PromptVersion, PromptVersion, PromptVersion> getPromptVersion) {

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .latestVersion(null)
                    .build();

            UUID promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            var promptVersion = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .createdBy(USER)
                    .promptId(promptId)
                    .build();

            var request = new CreatePromptVersion(prompt.name(), promptVersion);

            var promptVersion2 = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .createdBy(USER)
                    .promptId(promptId)
                    .build();

            var request2 = new CreatePromptVersion(prompt.name(), promptVersion2);

            var createdPromptVersion = createPromptVersion(request, API_KEY, TEST_WORKSPACE);
            var createdPromptVersion2 = createPromptVersion(request2, API_KEY, TEST_WORKSPACE);

            var retrieveRequest = retrievePrompt.apply(createdPromptVersion, createdPromptVersion2, prompt.name());
            var expectedPromptVersion = getPromptVersion.apply(createdPromptVersion, createdPromptVersion2);

            retrievePromptVersionAndAssert(retrieveRequest, expectedPromptVersion, API_KEY, TEST_WORKSPACE);
        }

        public Stream<Arguments> shouldRetrievePromptVersion() {
            return Stream.of(
                    // Retrieve by prompt name and commit null
                    arguments(
                            (TriFunction<PromptVersion, PromptVersion, String, PromptVersionRetrieve>) (promptVersion,
                                    promptVersion2, promptName) -> new PromptVersionRetrieve(promptName, null),
                            (BiFunction<PromptVersion, PromptVersion, PromptVersion>) (promptVersion,
                                    promptVersion2) -> promptVersion2),
                    // Retrieve by prompt name and first commit
                    arguments(
                            (TriFunction<PromptVersion, PromptVersion, String, PromptVersionRetrieve>) (promptVersion,
                                    promptVersion2,
                                    promptName) -> new PromptVersionRetrieve(promptName, promptVersion.commit()),
                            (BiFunction<PromptVersion, PromptVersion, PromptVersion>) (promptVersion,
                                    promptVersion2) -> promptVersion),
                    // Retrieve by prompt name and last commit
                    arguments(
                            (TriFunction<PromptVersion, PromptVersion, String, PromptVersionRetrieve>) (promptVersion,
                                    promptVersion2,
                                    promptName) -> new PromptVersionRetrieve(promptName, promptVersion2.commit()),
                            (BiFunction<PromptVersion, PromptVersion, PromptVersion>) (promptVersion,
                                    promptVersion2) -> promptVersion2));
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when prompt version retrieve request does not exist, then return not found")
        void when__promptVersionDoesNotExist__thenReturnNotFound(
                BiFunction<PromptVersion, Prompt, PromptVersionRetrieve> retrievePrompt, String message) {

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .latestVersion(null)
                    .build();

            UUID promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            var promptVersion = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .createdBy(USER)
                    .promptId(promptId)
                    .build();

            var request = new CreatePromptVersion(prompt.name(), promptVersion);

            var createdPromptVersion = createPromptVersion(request, API_KEY, TEST_WORKSPACE);

            var retrieveRequest = retrievePrompt.apply(createdPromptVersion, prompt);

            try (var response = client
                    .target(RESOURCE_PATH.formatted(baseURI) + "/versions/retrieve")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(RequestContext.WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(retrieveRequest))) {

                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NOT_FOUND);
                assertThat(response.hasEntity()).isTrue();
                assertThat(response.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                        .isEqualTo(new io.dropwizard.jersey.errors.ErrorMessage(HttpStatus.SC_NOT_FOUND, message));
            }
        }

        Stream<Arguments> when__promptVersionDoesNotExist__thenReturnNotFound() {
            return Stream.of(
                    arguments(
                            (BiFunction<PromptVersion, Prompt, PromptVersionRetrieve>) (promptVersion,
                                    prompt) -> new PromptVersionRetrieve(prompt.name(),
                                            RandomStringUtils.randomAlphanumeric(8)),
                            "Prompt version not found"),
                    arguments(
                            (BiFunction<PromptVersion, Prompt, PromptVersionRetrieve>) (promptVersion,
                                    prompt) -> new PromptVersionRetrieve(RandomStringUtils.randomAlphanumeric(10),
                                            promptVersion.commit()),
                            "Prompt not found"),
                    arguments(
                            (BiFunction<PromptVersion, Prompt, PromptVersionRetrieve>) (promptVersion,
                                    prompt) -> new PromptVersionRetrieve(RandomStringUtils.randomAlphanumeric(10),
                                            null),
                            "Prompt not found"));
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when prompt version retrieve request is invalid, then return error")
        void when__promptVersionRetrieveRequestIsInvalid__thenReturnError(PromptVersionRetrieve retrieveRequest,
                int expectedStatus, Class<?> messageClass, Object message) {

            try (var response = client.target(RESOURCE_PATH.formatted(baseURI) + "/versions/retrieve")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(RequestContext.WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(retrieveRequest))) {

                assertThat(response.getStatus()).isEqualTo(expectedStatus);
                assertThat(response.hasEntity()).isTrue();
                assertThat(response.readEntity(messageClass))
                        .isEqualTo(message);
            }
        }

        public Stream<Arguments> when__promptVersionRetrieveRequestIsInvalid__thenReturnError() {
            return Stream.of(
                    arguments(
                            new PromptVersionRetrieve(null, null),
                            HttpStatus.SC_UNPROCESSABLE_ENTITY,
                            ErrorMessage.class,
                            new ErrorMessage(List.of("name must not be blank"))),
                    arguments(
                            new PromptVersionRetrieve("", null),
                            HttpStatus.SC_UNPROCESSABLE_ENTITY,
                            ErrorMessage.class,
                            new ErrorMessage(List.of("name must not be blank"))));
        }
    }

    @Nested
    @DisplayName("Restore Prompt Version")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class RestorePromptVersionTests {

        @Test
        @DisplayName("Success: should restore a prompt version and create a new version from it")
        void shouldRestorePromptVersion() {
            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .versionCount(0L)
                    .latestVersion(null)
                    .build();

            UUID promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            // Create first version to restore from
            var promptVersion1 = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .id(null)
                    .promptId(promptId)
                    .commit(null)
                    .createdBy(USER)
                    .variables(null)
                    .template("Original template content")
                    .changeDescription("First version")
                    .build();

            var createdV1 = createPromptVersion(new CreatePromptVersion(prompt.name(), promptVersion1), API_KEY,
                    TEST_WORKSPACE);

            // Create second version
            var promptVersion2 = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .promptId(promptId)
                    .commit(null)
                    .createdBy(USER)
                    .template("Modified template content")
                    .changeDescription("Second version")
                    .build();

            var createdV2 = createPromptVersion(new CreatePromptVersion(prompt.name(), promptVersion2), API_KEY,
                    TEST_WORKSPACE);

            // Now restore the first version
            try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI) + "/%s/versions/%s/restore"
                    .formatted(promptId, createdV1.id()))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(RequestContext.WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(""))) {

                assertThat(actualResponse.getStatus()).isEqualTo(HttpStatus.SC_OK);

                var restoredVersion = actualResponse.readEntity(PromptVersion.class);

                // Use helper to validate restored content matches original content where applicable
                var expectedFromV1 = createdV1.toBuilder()
                        .id(null)
                        .commit(null)
                        .createdAt(createdV1.createdAt())
                        .build();

                assertPromptVersion(restoredVersion, expectedFromV1, promptId);

                // Additional checks specific to restore semantics
                assertThat(restoredVersion.changeDescription())
                        .isEqualTo("Restored from version " + createdV1.commit());
                assertThat(restoredVersion.id()).isNotEqualTo(createdV1.id());
                assertThat(restoredVersion.id()).isNotEqualTo(createdV2.id());
                assertThat(restoredVersion.commit()).isNotEqualTo(createdV1.commit());
                assertThat(restoredVersion.commit()).isNotEqualTo(createdV2.commit());
            }
        }

        @Test
        @DisplayName("when trying to restore prompt version from a different prompt, then return not found")
        void when__tryingToRestorePromptVersionFromDifferentPrompt__thenReturnNotFound() {
            var prompt1 = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .versionCount(0L)
                    .latestVersion(null)
                    .build();

            UUID promptId1 = createPrompt(prompt1, API_KEY, TEST_WORKSPACE);

            var prompt2 = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .versionCount(0L)
                    .latestVersion(null)
                    .build();

            UUID promptId2 = createPrompt(prompt2, API_KEY, TEST_WORKSPACE);

            // Create first version to restore from
            var promptVersion1 = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .id(null)
                    .promptId(promptId1)
                    .commit(null)
                    .createdBy(USER)
                    .variables(null)
                    .template("Original template content")
                    .changeDescription("First version")
                    .build();

            createPromptVersion(new CreatePromptVersion(prompt1.name(), promptVersion1), API_KEY,
                    TEST_WORKSPACE);

            // Create second version
            var promptVersion2 = promptVersion1.toBuilder()
                    .promptId(promptId2)
                    .build();

            var newpPromptVersion1 = promptVersion1.toBuilder()
                    .commit(null)
                    .createdBy(USER)
                    .template("Modified template content")
                    .changeDescription("Second version")
                    .build();

            var prompt2V1 = createPromptVersion(new CreatePromptVersion(prompt2.name(), promptVersion2), API_KEY,
                    TEST_WORKSPACE);

            createPromptVersion(new CreatePromptVersion(prompt1.name(), newpPromptVersion1), API_KEY,
                    TEST_WORKSPACE);

            // Now restore the first version
            try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI) + "/%s/versions/%s/restore"
                    .formatted(promptId1, prompt2V1.id()))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(RequestContext.WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(""))) {

                assertThat(actualResponse.getStatus()).isEqualTo(HttpStatus.SC_NOT_FOUND);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                        .isEqualTo(new io.dropwizard.jersey.errors.ErrorMessage(404,
                                "Prompt version not found for the specified prompt"));
            }
        }
    }

    private void retrievePromptVersionAndAssert(PromptVersionRetrieve retrieveRequest,
            PromptVersion expectedPromptVersion, String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI) + "/versions/retrieve")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(retrieveRequest))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);

            var actualPromptVersion = response.readEntity(PromptVersion.class);

            assertThat(actualPromptVersion)
                    .usingRecursiveComparison(
                            RecursiveComparisonConfiguration.builder()
                                    .withComparatorForType(PromptResourceTest::comparatorForCreateAtAndUpdatedAt,
                                            Instant.class)
                                    .build())
                    .isEqualTo(expectedPromptVersion);
        }
    }

    private void getPromptVersionAndAssert(UUID id, PromptVersion createdPromptVersion, String apiKey,
            String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI) + "/versions/%s".formatted(id))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);

            var actualPromptVersion = response.readEntity(PromptVersion.class);

            assertThat(actualPromptVersion)
                    .usingRecursiveComparison(
                            RecursiveComparisonConfiguration.builder()
                                    .withComparatorForType(PromptResourceTest::comparatorForCreateAtAndUpdatedAt,
                                            Instant.class)
                                    .build())
                    .isEqualTo(createdPromptVersion);
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

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);

            var promptVersionPage = response.readEntity(PromptVersion.PromptVersionPage.class);

            assertThat(promptVersionPage.total()).isEqualTo(total);
            assertThat(promptVersionPage.content()).hasSize(expectedPromptVersions.size());
            assertThat(promptVersionPage.page()).isEqualTo(page);
            assertThat(promptVersionPage.size()).isEqualTo(expectedPromptVersions.size());

            assertThat(promptVersionPage.content())
                    .usingRecursiveComparison(
                            RecursiveComparisonConfiguration.builder()
                                    .withIgnoredFields("variables", "promptId")
                                    .withComparatorForType(PromptResourceTest::comparatorForCreateAtAndUpdatedAt,
                                            Instant.class)
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
        assertThat(createdPromptVersion.variables())
                .isEqualTo(TemplateParseUtils.extractVariables(promptVersion.template(), promptVersion.type()));
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
            int expectedTotal, int page, String nameSearch, List<SortingField> sortingFields,
            List<PromptFilter> filters) {

        WebTarget target = client.target(RESOURCE_PATH.formatted(baseURI));

        if (nameSearch != null) {
            target = target.queryParam("name", nameSearch);
        }

        if (page > 1) {
            target = target.queryParam("page", page);
        }

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
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);

            var promptPage = response.readEntity(Prompt.PromptPage.class);

            assertThat(promptPage.total()).isEqualTo(expectedTotal);
            assertThat(promptPage.content()).hasSize(expectedPrompts.size());
            assertThat(promptPage.page()).isEqualTo(page);
            assertThat(promptPage.size()).isEqualTo(expectedPrompts.size());

            assertSortableFields(promptPage);

            assertThat(promptPage.content())
                    .usingRecursiveComparison(
                            RecursiveComparisonConfiguration.builder()
                                    .withIgnoredFields(PROMPT_IGNORED_FIELDS)
                                    .withComparatorForType(PromptResourceTest::comparatorForCreateAtAndUpdatedAt,
                                            Instant.class)
                                    .build())
                    .isEqualTo(expectedPrompts);
        }
    }

    private static void assertSortableFields(Prompt.PromptPage promptPage) {
        assertThat(promptPage.sortableBy()).contains(
                ID,
                NAME,
                DESCRIPTION,
                CREATED_AT,
                LAST_UPDATED_AT,
                CREATED_BY,
                LAST_UPDATED_BY,
                TAGS);
    }

    public static int comparatorForCreateAtAndUpdatedAt(Instant actual, Instant expected) {
        var now = Instant.now();

        if (actual.isAfter(now) || actual.equals(now))
            return 1;
        if (actual.isBefore(expected))
            return -1;

        Assertions.assertThat(actual).isBetween(expected, now);
        return 0;
    }
}