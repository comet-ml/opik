package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.CreatePromptVersion;
import com.comet.opik.api.Prompt;
import com.comet.opik.api.PromptType;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.PromptVersionBatchUpdate;
import com.comet.opik.api.PromptVersionRetrieve;
import com.comet.opik.api.PromptVersionUpdate;
import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.api.TemplateStructure;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.PromptField;
import com.comet.opik.api.filter.PromptFilter;
import com.comet.opik.api.filter.PromptVersionField;
import com.comet.opik.api.filter.PromptVersionFilter;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.PromptVersionResourceClient;
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
import org.apache.commons.collections4.SetUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
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
    private PromptVersionResourceClient promptVersionResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {

        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;
        this.promptVersionResourceClient = new PromptVersionResourceClient(client, baseURI);

        ClientSupportUtils.config(client);

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        mockTargetWorkspace(apiKey, workspaceName, workspaceId, USER);
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId, String user) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, user);
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
                    .templateStructure(TemplateStructure.TEXT)
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
                    .templateStructure(TemplateStructure.TEXT)
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
                    .templateStructure(TemplateStructure.TEXT)
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

            var version = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .build();
            var request = CreatePromptVersion.builder()
                    .name(factory.manufacturePojo(String.class))
                    .version(version)
                    .templateStructure(TemplateStructure.TEXT)
                    .build();

            try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI) + "/versions")
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(request))) {

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
                    .templateStructure(TemplateStructure.TEXT)
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
                    .templateStructure(TemplateStructure.TEXT)
                    .build();

            UUID promptId = createPrompt(prompt, okApikey, workspaceName);

            var promptVersion = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .promptId(promptId)
                    .build();

            CreatePromptVersion request = createPromptVersionRequest(prompt.name(), promptVersion,
                    prompt.templateStructure());

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

            var request = createPromptVersionRequest(UUID.randomUUID().toString(), promptVersion,
                    TemplateStructure.TEXT);

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
                    .templateStructure(TemplateStructure.TEXT)
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
                    .templateStructure(TemplateStructure.TEXT)
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
                    .templateStructure(TemplateStructure.TEXT)
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

            var version = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .build();
            var request = CreatePromptVersion.builder()
                    .name(factory.manufacturePojo(String.class))
                    .version(version)
                    .templateStructure(TemplateStructure.TEXT)
                    .build();

            try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI) + "/versions")
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .cookie(SESSION_COOKIE, sessionToken)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(request))) {

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
                    .templateStructure(TemplateStructure.TEXT)
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
                    .templateStructure(TemplateStructure.TEXT)
                    .build();

            UUID promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            var promptVersion = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .promptId(promptId)
                    .build();

            CreatePromptVersion request = createPromptVersionRequest(prompt.name(), promptVersion,
                    prompt.templateStructure());

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

            var request = createPromptVersionRequest(UUID.randomUUID().toString(), promptVersion,
                    TemplateStructure.TEXT);

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

    private CreatePromptVersion createPromptVersionRequest(String name, PromptVersion version,
            TemplateStructure templateStructure) {
        return CreatePromptVersion.builder()
                .name(name)
                .version(version)
                .templateStructure(templateStructure)
                .build();
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
                    .templateStructure(TemplateStructure.TEXT)
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

            var expectedTags = updatedPrompt.tags() == null
                    ? prompt.tags() // if null, keep previous tags
                    : (updatedPrompt.tags().isEmpty() ? null : updatedPrompt.tags()); // if empty, clears tags
            updatedPrompt = updatedPrompt.toBuilder()
                    .tags(expectedTags)
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
                            // Only alphanumeric to avoid flakiness with special characters when sorting by name
                            .name(RandomStringUtils.secure().nextAlphanumeric(10))
                            .lastUpdatedBy(USER)
                            .createdBy(USER)
                            .versionCount(random.nextLong(5))
                            .template(null)
                            .templateStructure(TemplateStructure.TEXT)
                            .build())
                    .toList();

            prompts.forEach(prompt -> {
                createPrompt(prompt, apiKey, workspaceName);
                for (int i = 0; i < prompt.versionCount(); i++) {
                    var promptVersion = factory.manufacturePojo(PromptVersion.class).toBuilder()
                            .createdBy(USER)
                            .build();
                    var request = createPromptVersionRequest(prompt.name(), promptVersion, prompt.templateStructure());
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
                            .templateStructure(TemplateStructure.TEXT)
                            .build())
                    .toList();

            prompts.forEach(prompt -> {
                createPrompt(prompt, apiKey, workspaceName);
                for (int i = 0; i < prompt.versionCount(); i++) {
                    var promptVersion = factory.manufacturePojo(PromptVersion.class).toBuilder()
                            .createdBy(USER)
                            .build();
                    var request = createPromptVersionRequest(prompt.name(), promptVersion, prompt.templateStructure());
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
                    .templateStructure(TemplateStructure.TEXT)
                    .build();

            UUID promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            PromptVersion promptVersion = factory.manufacturePojo(PromptVersion.class)
                    .toBuilder()
                    .createdBy(USER)
                    .build();

            promptVersion = createPromptVersion(
                    createPromptVersionRequest(prompt.name(), promptVersion, prompt.templateStructure()), API_KEY,
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

            var request = createPromptVersionRequest(prompt.name(), expectedPromptVersion, prompt.templateStructure());

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
                    .templateStructure(TemplateStructure.TEXT)
                    .build();

            UUID promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            var versionId = factory.manufacturePojo(UUID.class);

            var expectedPromptVersion = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .createdBy(USER)
                    .commit(versionId.toString().substring(versionId.toString().length() - 8))
                    .id(versionId)
                    .build();

            var request = createPromptVersionRequest(prompt.name(), expectedPromptVersion, prompt.templateStructure());

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

            var request = createPromptVersionRequest(promptName, expectedPromptVersion, TemplateStructure.TEXT);

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
                    .templateStructure(TemplateStructure.TEXT)
                    .build();

            var versionId = factory.manufacturePojo(UUID.class);

            var promptVersion = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .createdBy(USER)
                    .id(versionId)
                    .build();

            var request = createPromptVersionRequest(prompt.name(), promptVersion, prompt.templateStructure());

            createPromptVersion(request, API_KEY, TEST_WORKSPACE);

            var promptVersion2 = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .createdBy(USER)
                    .id(versionId)
                    .build();

            assertPromptVersionConflict(
                    createPromptVersionRequest(UUID.randomUUID().toString(), promptVersion2, TemplateStructure.TEXT),
                    API_KEY, TEST_WORKSPACE, "Prompt version already exists");
        }

        @Test
        @DisplayName("when prompt version commit already exists, then return error")
        void when__promptVersionCommitAlreadyExists__thenReturnError() {

            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .templateStructure(TemplateStructure.TEXT)
                    .build();

            var promptVersion = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .createdBy(USER)
                    .build();

            var request = createPromptVersionRequest(prompt.name(), promptVersion, prompt.templateStructure());

            createPromptVersion(request, API_KEY, TEST_WORKSPACE);

            var promptVersion2 = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .createdBy(USER)
                    .commit(promptVersion.commit())
                    .build();

            assertPromptVersionConflict(
                    createPromptVersionRequest(prompt.name(), promptVersion2, prompt.templateStructure()),
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
                    arguments(CreatePromptVersion.builder()
                            .name(null)
                            .version(factory.manufacturePojo(PromptVersion.class).toBuilder()
                                    .build())
                            .templateStructure(null)
                            .build(),
                            HttpStatus.SC_UNPROCESSABLE_ENTITY, new ErrorMessage(List.of("name must not be blank")),
                            ErrorMessage.class),
                    arguments(CreatePromptVersion.builder()
                            .name("")
                            .version(factory.manufacturePojo(PromptVersion.class).toBuilder()
                                    .build())
                            .templateStructure(null)
                            .build(),
                            HttpStatus.SC_UNPROCESSABLE_ENTITY, new ErrorMessage(List.of("name must not be blank")),
                            ErrorMessage.class),
                    arguments(
                            CreatePromptVersion.builder()
                                    .name(UUID.randomUUID().toString())
                                    .version(factory.manufacturePojo(PromptVersion.class)
                                            .toBuilder()
                                            .commit("")
                                            .build())
                                    .templateStructure(null)
                                    .build(),
                            HttpStatus.SC_UNPROCESSABLE_ENTITY,
                            new ErrorMessage(List.of(
                                    "version.commit if present, the commit message must be 8 alphanumeric characters long")),
                            ErrorMessage.class),
                    arguments(
                            CreatePromptVersion.builder()
                                    .name(UUID.randomUUID().toString())
                                    .version(factory.manufacturePojo(PromptVersion.class)
                                            .toBuilder()
                                            .commit("1234567")
                                            .build())
                                    .templateStructure(null)
                                    .build(),
                            HttpStatus.SC_UNPROCESSABLE_ENTITY,
                            new ErrorMessage(List.of(
                                    "version.commit if present, the commit message must be 8 alphanumeric characters long")),
                            ErrorMessage.class),
                    arguments(
                            CreatePromptVersion.builder()
                                    .name(UUID.randomUUID().toString())
                                    .version(factory.manufacturePojo(PromptVersion.class)
                                            .toBuilder()
                                            .commit("1234-567")
                                            .build())
                                    .templateStructure(null)
                                    .build(),
                            HttpStatus.SC_UNPROCESSABLE_ENTITY,
                            new ErrorMessage(List.of(
                                    "version.commit if present, the commit message must be 8 alphanumeric characters long")),
                            ErrorMessage.class),
                    arguments(
                            CreatePromptVersion.builder()
                                    .name(UUID.randomUUID().toString())
                                    .version(factory.manufacturePojo(PromptVersion.class)
                                            .toBuilder()
                                            .id(UUID.randomUUID())
                                            .build())
                                    .templateStructure(null)
                                    .build(),
                            HttpStatus.SC_BAD_REQUEST,
                            new ErrorMessage(List.of("prompt version id must be a version 7 UUID")),
                            ErrorMessage.class),
                    arguments(
                            CreatePromptVersion.builder()
                                    .name(UUID.randomUUID().toString())
                                    .version(factory.manufacturePojo(PromptVersion.class)
                                            .toBuilder()
                                            .template("")
                                            .build())
                                    .templateStructure(null)
                                    .build(),
                            HttpStatus.SC_UNPROCESSABLE_ENTITY,
                            new ErrorMessage(List.of("version.template must not be blank")),
                            ErrorMessage.class),
                    arguments(
                            CreatePromptVersion.builder()
                                    .name(UUID.randomUUID().toString())
                                    .version(factory.manufacturePojo(PromptVersion.class)
                                            .toBuilder()
                                            .template(null)
                                            .build())
                                    .templateStructure(null)
                                    .build(),
                            HttpStatus.SC_UNPROCESSABLE_ENTITY,
                            new ErrorMessage(List.of("version.template must not be blank")),
                            ErrorMessage.class));
        }

        @ParameterizedTest
        @MethodSource
        @DisplayName("when templateStructure is null, then handle correctly for both new and existing prompts")
        void when__templateStructureIsNull__thenHandleCorrectly(boolean promptExists) {
            // This test verifies backwards compatibility for clients that don't send templateStructure
            // (e.g., TypeScript SDK which doesn't support ChatPrompt yet)
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var promptName = UUID.randomUUID().toString();
            UUID existingPromptId = null;

            if (promptExists) {
                // Create a prompt first with a specific templateStructure
                var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                        .name(promptName)
                        .lastUpdatedBy(USER)
                        .createdBy(USER)
                        .template(null)
                        .templateStructure(TemplateStructure.TEXT)
                        .build();
                existingPromptId = createPrompt(prompt, apiKey, workspaceName);
            }

            // Create a new version with null templateStructure (simulating SDK behavior)
            var expectedPromptVersion = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .createdBy(USER)
                    .commit(null)
                    .id(null)
                    .build();

            // Pass null for templateStructure - this previously caused NPE for existing prompts
            // and should default to TEXT for new prompts
            var request = createPromptVersionRequest(promptName, expectedPromptVersion, null);

            PromptVersion actualPromptVersion = createPromptVersion(request, apiKey, workspaceName);

            // Verify the prompt exists and has correct templateStructure
            List<Prompt> prompts = getPrompts(promptName, apiKey, workspaceName);
            assertThat(prompts).hasSize(1);

            Prompt createdPrompt = prompts.getFirst();
            assertThat(createdPrompt.templateStructure()).isEqualTo(TemplateStructure.TEXT);

            UUID expectedPromptId = promptExists ? existingPromptId : createdPrompt.id();
            assertPromptVersion(actualPromptVersion, expectedPromptVersion, expectedPromptId);
        }

        Stream<Arguments> when__templateStructureIsNull__thenHandleCorrectly() {
            return Stream.of(
                    arguments(true), // prompt exists - tests NPE fix
                    arguments(false) // prompt doesn't exist - tests default to TEXT
            );
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
                    .templateStructure(TemplateStructure.TEXT)
                    .build();

            UUID promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            var prompt2 = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .templateStructure(TemplateStructure.TEXT)
                    .build();

            createPrompt(prompt2, API_KEY, TEST_WORKSPACE);

            var promptVersion = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .createdBy(USER)
                    .build();

            var request = createPromptVersionRequest(prompt.name(), promptVersion, prompt.templateStructure());

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
                    .templateStructure(TemplateStructure.TEXT)
                    .build();

            UUID promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            var prompt2 = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .templateStructure(TemplateStructure.TEXT)
                    .build();

            createPrompt(prompt2, API_KEY, TEST_WORKSPACE);

            var promptVersions = IntStream.range(0, 20)
                    .mapToObj(i -> factory.manufacturePojo(PromptVersion.class).toBuilder()
                            .createdBy(USER)
                            .build())
                    .toList();

            promptVersions
                    .forEach(promptVersion -> createPromptVersion(
                            createPromptVersionRequest(prompt.name(), promptVersion, prompt.templateStructure()),
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
                    .templateStructure(TemplateStructure.TEXT)
                    .build();

            UUID promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            var promptVersions = IntStream.range(0, 10)
                    .mapToObj(i -> factory.manufacturePojo(PromptVersion.class).toBuilder()
                            .createdBy(USER)
                            .build())
                    .toList();

            promptVersions
                    .forEach(promptVersion -> createPromptVersion(
                            createPromptVersionRequest(prompt.name(), promptVersion, prompt.templateStructure()),
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
                    .templateStructure(TemplateStructure.TEXT)
                    .build();

            UUID promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            var promptVersion = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .createdBy(USER)
                    .promptId(promptId)
                    .type(type)
                    .build();

            var request = createPromptVersionRequest(prompt.name(), promptVersion, prompt.templateStructure());

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
                    .templateStructure(TemplateStructure.TEXT)
                    .build();

            UUID promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            var promptVersion = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .createdBy(USER)
                    .promptId(promptId)
                    .build();

            var request = createPromptVersionRequest(prompt.name(), promptVersion, prompt.templateStructure());

            var promptVersion2 = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .createdBy(USER)
                    .promptId(promptId)
                    .build();

            var request2 = createPromptVersionRequest(prompt.name(), promptVersion2, prompt.templateStructure());

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
                    .templateStructure(TemplateStructure.TEXT)
                    .build();

            UUID promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            var promptVersion = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .createdBy(USER)
                    .promptId(promptId)
                    .build();

            var request = createPromptVersionRequest(prompt.name(), promptVersion, prompt.templateStructure());

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

        @Test
        @DisplayName("Success: should retrieve string prompt")
        void shouldRetrieveStringPrompt() {
            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .latestVersion(null)
                    .templateStructure(TemplateStructure.TEXT)
                    .build();

            UUID promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            var promptVersion = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .createdBy(USER)
                    .promptId(promptId)
                    .template("Hello {{name}}")
                    .build();

            var request = CreatePromptVersion.builder()
                    .name(prompt.name())
                    .version(promptVersion)
                    .templateStructure(TemplateStructure.TEXT)
                    .build();
            var createdPromptVersion = createPromptVersion(request, API_KEY, TEST_WORKSPACE);

            var retrieveRequest = new PromptVersionRetrieve(prompt.name(), null);

            retrievePromptVersionAndAssert(retrieveRequest, createdPromptVersion, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("Success: should retrieve chat prompt")
        void shouldRetrieveChatPrompt() {
            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .latestVersion(null)
                    .templateStructure(TemplateStructure.CHAT)
                    .build();

            UUID promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            // Valid JSON array template
            String chatTemplate = "[{\"role\": \"system\", \"content\": \"You are a helpful assistant.\"}, {\"role\": \"user\", \"content\": \"Hello {{name}}!\"}]";

            var promptVersion = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .createdBy(USER)
                    .promptId(promptId)
                    .template(chatTemplate)
                    .build();

            var request = CreatePromptVersion.builder()
                    .name(prompt.name())
                    .version(promptVersion)
                    .templateStructure(TemplateStructure.CHAT)
                    .build();
            var createdPromptVersion = createPromptVersion(request, API_KEY, TEST_WORKSPACE);

            var retrieveRequest = new PromptVersionRetrieve(prompt.name(), null);

            retrievePromptVersionAndAssert(retrieveRequest, createdPromptVersion, API_KEY, TEST_WORKSPACE);
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
                    .templateStructure(TemplateStructure.TEXT)
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

            var createdV1 = createPromptVersion(
                    createPromptVersionRequest(prompt.name(), promptVersion1, prompt.templateStructure()), API_KEY,
                    TEST_WORKSPACE);

            // Create second version
            var promptVersion2 = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .promptId(promptId)
                    .commit(null)
                    .createdBy(USER)
                    .template("Modified template content")
                    .changeDescription("Second version")
                    .build();

            var createdV2 = createPromptVersion(
                    createPromptVersionRequest(prompt.name(), promptVersion2, prompt.templateStructure()), API_KEY,
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
                        .tags(null) // Restored version should not copy tags
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
                    .templateStructure(TemplateStructure.TEXT)
                    .build();

            UUID promptId1 = createPrompt(prompt1, API_KEY, TEST_WORKSPACE);

            var prompt2 = factory.manufacturePojo(Prompt.class).toBuilder()
                    .lastUpdatedBy(USER)
                    .createdBy(USER)
                    .template(null)
                    .versionCount(0L)
                    .latestVersion(null)
                    .templateStructure(TemplateStructure.TEXT)
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

            createPromptVersion(createPromptVersionRequest(prompt1.name(), promptVersion1, prompt1.templateStructure()),
                    API_KEY,
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

            var prompt2V1 = createPromptVersion(
                    createPromptVersionRequest(prompt2.name(), promptVersion2, prompt2.templateStructure()), API_KEY,
                    TEST_WORKSPACE);

            createPromptVersion(
                    createPromptVersionRequest(prompt1.name(), newpPromptVersion1, prompt1.templateStructure()),
                    API_KEY,
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
                                    .withIgnoredFields("variables", "promptId", "templateStructure")
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
        if (promptVersion.tags() != null) {
            assertThat(createdPromptVersion.tags()).containsExactlyInAnyOrderElementsOf(promptVersion.tags());
        } else {
            // When expected tags are null, the actual tags should also be null
            assertThat(createdPromptVersion.tags()).isNull();
        }
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

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class PromptVersionSearchTest {

        Stream<Arguments> searchPromptVersions() {
            return Stream.of(
                    arguments(
                            "contains case insensitive in template",
                            1,
                            (BiFunction<PromptVersion, PromptVersion, String>) (v1, v2) -> v1.template()
                                    .substring(5, v1.template().length() - 5)
                                    .toLowerCase(),
                            (BiFunction<PromptVersion, PromptVersion, Function<PromptVersion, Boolean>>) (
                                    v1, v2) -> v -> v.id().equals(v1.id())),
                    arguments(
                            "contains case insensitive in change description",
                            1,
                            (BiFunction<PromptVersion, PromptVersion, String>) (v1, v2) -> v2.changeDescription()
                                    .substring(5, v2.changeDescription().length() - 5)
                                    .toUpperCase(),
                            (BiFunction<PromptVersion, PromptVersion, Function<PromptVersion, Boolean>>) (
                                    v1, v2) -> v -> v.id().equals(v2.id())),
                    arguments(
                            "contains case insensitive in both template OR change description",
                            2,
                            (BiFunction<PromptVersion, PromptVersion, String>) (v1, v2) -> v1.template()
                                    .substring(5, v1.template().length() - 5),
                            (BiFunction<PromptVersion, PromptVersion, Function<PromptVersion, Boolean>>) (
                                    v1, v2) -> v -> v.id().equals(v1.id()) || v.id().equals(v2.id())));
        }

        @ParameterizedTest(name = "Success: {0}")
        @MethodSource
        @DisplayName("Success: search prompt versions by template or change description")
        void searchPromptVersions(
                String description,
                int expectedSize,
                BiFunction<PromptVersion, PromptVersion, String> getSearch,
                BiFunction<PromptVersion, PromptVersion, Function<PromptVersion, Boolean>> assertion) {
            var prompt = factory.manufacturePojo(Prompt.class).toBuilder().template(null).build();
            var promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            var versions = PodamFactoryUtils.manufacturePojoList(factory, PromptVersion.class).stream()
                    .map(v -> v.toBuilder()
                            .promptId(promptId)
                            .template(RandomStringUtils.secure().nextAlphanumeric(15))
                            .changeDescription(RandomStringUtils.secure().nextAlphanumeric(15))
                            .build())
                    .collect(Collectors.toCollection(ArrayList::new));

            // Create 2 versions with random data
            var version1Pojo = versions.getFirst();
            var version2Pojo = versions.getLast();

            // For OR test (expectedSize == 2): inject v1's template substring into v2's changeDescription
            if (expectedSize == 2) {
                version2Pojo = version2Pojo.toBuilder()
                        .changeDescription(RandomStringUtils.secure().nextAlphanumeric(5) +
                                version1Pojo.template().substring(5, version1Pojo.template().length() - 5).toUpperCase()
                                +
                                RandomStringUtils.secure().nextAlphanumeric(5))
                        .build();
                versions.set(versions.size() - 1, version2Pojo);
            }

            var createdVersions = versions.stream()
                    .map(promptVersion -> promptVersionResourceClient.createPromptVersion(
                            CreatePromptVersion.builder().name(prompt.name()).version(promptVersion).build(),
                            API_KEY, TEST_WORKSPACE))
                    .toList();

            var createdV1 = createdVersions.getFirst();
            var createdV2 = createdVersions.getLast();
            var search = getSearch.apply(createdV1, createdV2);
            var page = promptVersionResourceClient.getPromptVersionsByPromptId(
                    promptId, API_KEY, TEST_WORKSPACE, search, null, null);

            var predicate = assertion.apply(createdV1, createdV2);
            assertThat(page.size()).isEqualTo(expectedSize);
            assertThat(page.total()).isEqualTo(expectedSize);
            assertThat(page.content()).hasSize(expectedSize);
            page.content().forEach(version -> assertThat(predicate.apply(version)).isTrue());
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class PromptVersionFilteringTest {

        Stream<Arguments> filterPromptVersionsByTags() {
            return Stream.of(
                    // CONTAINS operator
                    arguments(
                            Operator.CONTAINS,
                            "contains specific tag",
                            (BiFunction<PromptVersion, PromptVersion, String>) (v1, v2) -> v1.tags().stream().sorted()
                                    .findFirst().map(t -> t.substring(2, t.length() - 2)).orElse(null),
                            (BiFunction<PromptVersion, PromptVersion, Function<PromptVersion, Boolean>>) (v1,
                                    v2) -> v -> v1.tags().containsAll(v.tags())));
        }

        @ParameterizedTest(name = "Success: filter by tags - {1}")
        @MethodSource
        @DisplayName("Success: filter prompt versions by tags with various operators")
        void filterPromptVersionsByTags(
                Operator operator,
                String description,
                BiFunction<PromptVersion, PromptVersion, String> getFilterValue,
                BiFunction<PromptVersion, PromptVersion, Function<PromptVersion, Boolean>> getAssertion) {
            var prompt = factory.manufacturePojo(Prompt.class);
            var promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            var tag1 = RandomStringUtils.secure().nextAlphanumeric(10);
            var tag2 = RandomStringUtils.secure().nextAlphanumeric(10);
            var version1 = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .promptId(promptId)
                    .tags(Set.of(tag1))
                    .build();
            var version2 = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .promptId(promptId)
                    .tags(Set.of(tag2))
                    .build();
            var createdV1 = promptVersionResourceClient.createPromptVersion(
                    CreatePromptVersion.builder().name(prompt.name()).version(version1).build(), API_KEY,
                    TEST_WORKSPACE);
            var createdV2 = promptVersionResourceClient.createPromptVersion(
                    CreatePromptVersion.builder().name(prompt.name()).version(version2).build(), API_KEY,
                    TEST_WORKSPACE);

            var filterValue = getFilterValue.apply(createdV1, createdV2);
            var filters = List.of(PromptVersionFilter.builder()
                    .field(PromptVersionField.TAGS)
                    .operator(operator)
                    .value(filterValue)
                    .build());
            var page = promptVersionResourceClient.getPromptVersionsByPromptId(
                    promptId, API_KEY, TEST_WORKSPACE, filters, null);

            var assertion = getAssertion.apply(createdV1, createdV2);
            assertThat(page.total()).isEqualTo(1);
            assertThat(assertion.apply(page.content().getFirst())).isTrue();
        }

        Stream<Arguments> filterPromptVersionsByMetadata() {
            // Generate random metadata keys for each test case
            var keyEqual = RandomStringUtils.secure().nextAlphanumeric(10);
            var keyNotEqual = RandomStringUtils.secure().nextAlphanumeric(10);
            var keyContains = RandomStringUtils.secure().nextAlphanumeric(10);
            var keyNotContains = RandomStringUtils.secure().nextAlphanumeric(10);
            var keyStartsWith = RandomStringUtils.secure().nextAlphanumeric(10);
            var keyEndsWith = RandomStringUtils.secure().nextAlphanumeric(10);
            var keyGreaterThan = RandomStringUtils.secure().nextAlphanumeric(10);
            var keyLessThan = RandomStringUtils.secure().nextAlphanumeric(10);
            var keyGreaterThanEqual = RandomStringUtils.secure().nextAlphanumeric(10);
            var keyLessThanEqual = RandomStringUtils.secure().nextAlphanumeric(10);

            // Generate random values for string fields
            var value1 = RandomStringUtils.secure().nextAlphanumeric(20);
            var value2 = RandomStringUtils.secure().nextAlphanumeric(20);
            var randomInt1 = RandomUtils.secure().randomInt(1000, 2000);
            var randomInt2 = RandomUtils.secure().randomInt(3000, 4000);
            var randomDouble1 = RandomUtils.secure().randomDouble(1.0, 2.0);
            var randomDouble2 = RandomUtils.secure().randomDouble(3.0, 4.0);

            return Stream.of(
                    // EQUAL operator
                    arguments(
                            keyEqual,
                            value1,
                            value2,
                            Operator.EQUAL,
                            value1,
                            (Function<PromptVersion, Boolean>) v -> v.metadata().get(keyEqual).asText()
                                    .equals(value1)),
                    // NOT_EQUAL operator
                    arguments(
                            keyNotEqual,
                            value1,
                            value2,
                            Operator.NOT_EQUAL,
                            value2,
                            (Function<PromptVersion, Boolean>) v -> !v.metadata().get(keyNotEqual).asText()
                                    .equals(value2)),
                    // CONTAINS operator
                    arguments(
                            keyContains,
                            value1,
                            value2,
                            Operator.CONTAINS,
                            value1.substring(5, 15),
                            (Function<PromptVersion, Boolean>) v -> v.metadata().get(keyContains).asText()
                                    .contains(value1.substring(5, 15))),
                    // NOT_CONTAINS operator
                    arguments(
                            keyNotContains,
                            value1,
                            value2,
                            Operator.NOT_CONTAINS,
                            value2.substring(5, 15),
                            (Function<PromptVersion, Boolean>) v -> !v.metadata().get(keyNotContains).asText()
                                    .contains(value2.substring(5, 15))),
                    // STARTS_WITH operator
                    arguments(
                            keyStartsWith,
                            value1,
                            value2,
                            Operator.STARTS_WITH,
                            value1.substring(0, 10),
                            (Function<PromptVersion, Boolean>) v -> v.metadata().get(keyStartsWith).asText()
                                    .startsWith(value1.substring(0, 10))),
                    // ENDS_WITH operator
                    arguments(
                            keyEndsWith,
                            value1,
                            value2,
                            Operator.ENDS_WITH,
                            value1.substring(10),
                            (Function<PromptVersion, Boolean>) v -> v.metadata().get(keyEndsWith).asText()
                                    .endsWith(value1.substring(10))),
                    // GREATER_THAN operator (numeric)
                    arguments(
                            keyGreaterThan,
                            randomInt1,
                            randomInt2,
                            Operator.GREATER_THAN,
                            String.valueOf((randomInt1 + randomInt2) / 2),
                            (Function<PromptVersion, Boolean>) v -> v.metadata().get(keyGreaterThan)
                                    .asInt() > (randomInt1 + randomInt2) / 2),
                    // LESS_THAN operator (numeric)
                    arguments(
                            keyLessThan,
                            randomDouble1,
                            randomDouble2,
                            Operator.LESS_THAN,
                            String.valueOf((randomDouble1 + randomDouble2) / 2),
                            (Function<PromptVersion, Boolean>) v -> v.metadata().get(keyLessThan)
                                    .asDouble() < (randomDouble1 + randomDouble2) / 2),
                    // GREATER_THAN_EQUAL operator (numeric)
                    arguments(
                            keyGreaterThanEqual,
                            randomInt1,
                            randomInt2,
                            Operator.GREATER_THAN_EQUAL,
                            String.valueOf((randomInt1 + randomInt2) / 2),
                            (Function<PromptVersion, Boolean>) v -> v.metadata().get(keyGreaterThanEqual)
                                    .asInt() >= (randomInt1 + randomInt2) / 2),
                    // LESS_THAN_EQUAL operator (numeric)
                    arguments(
                            keyLessThanEqual,
                            randomInt2,
                            randomInt1,
                            Operator.LESS_THAN_EQUAL,
                            String.valueOf((randomInt1 + randomInt2) / 2),
                            (Function<PromptVersion, Boolean>) v -> v.metadata().get(keyLessThanEqual)
                                    .asInt() <= (randomInt1 + randomInt2) / 2));
        }

        @ParameterizedTest(name = "Success: filter by metadata.{0} - {3}")
        @MethodSource
        @DisplayName("Success: filter prompt versions by metadata fields with various operators")
        void filterPromptVersionsByMetadata(
                String metadataKey,
                Object metadataValue1,
                Object metadataValue2,
                Operator operator,
                String filterValue,
                Function<PromptVersion, Boolean> assertion) {
            var prompt = factory.manufacturePojo(Prompt.class);
            var promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            var version1 = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .promptId(promptId)
                    .metadata(JsonUtils.valueToTree(Map.of(metadataKey, metadataValue1)))
                    .build();
            var version2 = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .promptId(promptId)
                    .metadata(JsonUtils.valueToTree(Map.of(metadataKey, metadataValue2)))
                    .build();
            promptVersionResourceClient.createPromptVersion(
                    CreatePromptVersion.builder().name(prompt.name()).version(version1).build(), API_KEY,
                    TEST_WORKSPACE);
            promptVersionResourceClient.createPromptVersion(
                    CreatePromptVersion.builder().name(prompt.name()).version(version2).build(), API_KEY,
                    TEST_WORKSPACE);

            var filters = List.of(PromptVersionFilter.builder()
                    .field(PromptVersionField.METADATA)
                    .operator(operator)
                    .key(metadataKey)
                    .value(filterValue)
                    .build());
            var page = promptVersionResourceClient.getPromptVersionsByPromptId(
                    promptId, API_KEY, TEST_WORKSPACE, filters, null);

            assertThat(page.total()).isEqualTo(1);
            assertThat(assertion.apply(page.content().getFirst())).isTrue();
        }

        @Test
        @DisplayName("Success: filter by metadata keys with spaces and special characters")
        void filterPromptVersionsByMetadataKeysWithSpaces() {
            var prompt = factory.manufacturePojo(Prompt.class);
            var promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            var uniqueValue1 = RandomStringUtils.secure().nextAlphanumeric(12);
            var uniqueValue2 = RandomStringUtils.secure().nextAlphanumeric(12);
            var uniqueModelPrefix = RandomStringUtils.secure().nextAlphabetic(8);
            var randomTokens = RandomUtils.secure().randomInt(1000, 2000);
            var version1 = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .promptId(promptId)
                    .metadata(JsonUtils.valueToTree(Map.of(
                            "prompt metadata", uniqueValue1,
                            "model name", uniqueModelPrefix + "-turbo",
                            "max tokens", randomTokens)))
                    .build();
            var version2 = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .promptId(promptId)
                    .metadata(JsonUtils.valueToTree(Map.of(
                            "prompt metadata", uniqueValue2,
                            "model name", "other-" + RandomStringUtils.secure().nextAlphabetic(8))))
                    .build();
            promptVersionResourceClient.createPromptVersion(
                    CreatePromptVersion.builder().name(prompt.name()).version(version1).build(), API_KEY,
                    TEST_WORKSPACE);
            promptVersionResourceClient.createPromptVersion(
                    CreatePromptVersion.builder().name(prompt.name()).version(version2).build(), API_KEY,
                    TEST_WORKSPACE);

            // Filter by metadata key with spaces using EQUAL operator
            var filters = List.of(PromptVersionFilter.builder()
                    .field(PromptVersionField.METADATA)
                    .operator(Operator.EQUAL)
                    .key("prompt metadata")
                    .value(uniqueValue1)
                    .build());
            var page = promptVersionResourceClient.getPromptVersionsByPromptId(
                    promptId, API_KEY, TEST_WORKSPACE, filters, null);

            assertThat(page.total()).isEqualTo(1);
            assertThat(page.content().getFirst().metadata().get("prompt metadata").asText()).isEqualTo(uniqueValue1);

            // Filter by another key with spaces using CONTAINS operator
            var filters2 = List.of(PromptVersionFilter.builder()
                    .field(PromptVersionField.METADATA)
                    .operator(Operator.CONTAINS)
                    .key("model name")
                    .value(uniqueModelPrefix.substring(0, 6))
                    .build());
            var page2 = promptVersionResourceClient.getPromptVersionsByPromptId(
                    promptId, API_KEY, TEST_WORKSPACE, filters2, null);

            assertThat(page2.total()).isEqualTo(1);
            assertThat(page2.content().getFirst().metadata().get("model name").asText())
                    .isEqualTo(uniqueModelPrefix + "-turbo");

            // Filter by numeric metadata key with spaces using GREATER_THAN operator
            var filters3 = List.of(PromptVersionFilter.builder()
                    .field(PromptVersionField.METADATA)
                    .operator(Operator.GREATER_THAN)
                    .key("max tokens")
                    .value(String.valueOf(randomTokens - 1))
                    .build());
            var page3 = promptVersionResourceClient.getPromptVersionsByPromptId(
                    promptId, API_KEY, TEST_WORKSPACE, filters3, null);

            assertThat(page3.total()).isEqualTo(1);
            assertThat(page3.content().getFirst().metadata().get("max tokens").asInt())
                    .isEqualTo(randomTokens);
        }

        @Test
        @DisplayName("Success: filter prompt versions by multiple metadata fields")
        void filterPromptVersionsByMultipleMetadataFields() {
            var prompt = factory.manufacturePojo(Prompt.class);
            var promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            var key1 = RandomStringUtils.secure().nextAlphanumeric(10);
            var key2 = RandomStringUtils.secure().nextAlphanumeric(10);
            var value1a = RandomStringUtils.secure().nextAlphanumeric(15);
            var value1b = RandomStringUtils.secure().nextAlphanumeric(15);
            var value2a = RandomStringUtils.secure().nextAlphanumeric(15);
            var value2b = RandomStringUtils.secure().nextAlphanumeric(15);
            var version1 = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .promptId(promptId)
                    .metadata(JsonUtils.valueToTree(Map.of(key1, value1a, key2, value2a)))
                    .build();
            var version2 = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .promptId(promptId)
                    .metadata(JsonUtils.valueToTree(Map.of(key1, value1a, key2, value2b)))
                    .build();
            var version3 = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .promptId(promptId)
                    .metadata(JsonUtils.valueToTree(Map.of(key1, value1b, key2, value2a)))
                    .build();

            promptVersionResourceClient.createPromptVersion(
                    CreatePromptVersion.builder().name(prompt.name()).version(version1).build(), API_KEY,
                    TEST_WORKSPACE);
            promptVersionResourceClient.createPromptVersion(
                    CreatePromptVersion.builder().name(prompt.name()).version(version2).build(), API_KEY,
                    TEST_WORKSPACE);
            promptVersionResourceClient.createPromptVersion(
                    CreatePromptVersion.builder().name(prompt.name()).version(version3).build(), API_KEY,
                    TEST_WORKSPACE);

            // Filter by key1 = "value1a" AND key2 = "value2a"
            var filters = List.of(
                    PromptVersionFilter.builder()
                            .field(PromptVersionField.METADATA)
                            .operator(Operator.EQUAL)
                            .key(key1)
                            .value(value1a)
                            .build(),
                    PromptVersionFilter.builder()
                            .field(PromptVersionField.METADATA)
                            .operator(Operator.EQUAL)
                            .key(key2)
                            .value(value2a)
                            .build());
            var page = promptVersionResourceClient.getPromptVersionsByPromptId(
                    promptId, API_KEY, TEST_WORKSPACE, filters, null);

            assertThat(page.total()).isEqualTo(1);
            assertThat(page.content().getFirst().metadata().get(key1).asText()).isEqualTo(value1a);
            assertThat(page.content().getFirst().metadata().get(key2).asText()).isEqualTo(value2a);
        }

        Stream<Arguments> filterPromptVersionsByCommit() {
            return Stream.of(
                    // EQUAL operator
                    arguments(
                            Operator.EQUAL,
                            "exact commit match",
                            (BiFunction<PromptVersion, PromptVersion, String>) (v1, v2) -> v1
                                    .commit(),
                            (BiFunction<PromptVersion, PromptVersion, Function<PromptVersion, Boolean>>) (
                                    v1, v2) -> v -> v.commit().equals(v1.commit())),
                    // NOT_EQUAL operator
                    arguments(
                            Operator.NOT_EQUAL,
                            "not equal to commit",
                            (BiFunction<PromptVersion, PromptVersion, String>) (v1, v2) -> v1
                                    .commit(),
                            (BiFunction<PromptVersion, PromptVersion, Function<PromptVersion, Boolean>>) (
                                    v1, v2) -> v -> !v.commit().equals(v1.commit())),
                    // CONTAINS operator
                    arguments(
                            Operator.CONTAINS,
                            "contains substring",
                            (BiFunction<PromptVersion, PromptVersion, String>) (v1, v2) -> v1
                                    .commit().substring(1, 7),
                            (BiFunction<PromptVersion, PromptVersion, Function<PromptVersion, Boolean>>) (
                                    v1, v2) -> v -> v.commit().contains(v1.commit().substring(1, 7))),
                    // NOT_CONTAINS operator
                    arguments(
                            Operator.NOT_CONTAINS,
                            "does not contain substring",
                            (BiFunction<PromptVersion, PromptVersion, String>) (v1, v2) -> v1
                                    .commit().substring(1, 7),
                            (BiFunction<PromptVersion, PromptVersion, Function<PromptVersion, Boolean>>) (
                                    v1, v2) -> v -> !v.commit().contains(v1.commit().substring(1, 7))),
                    // STARTS_WITH operator
                    arguments(
                            Operator.STARTS_WITH,
                            "starts with prefix",
                            (BiFunction<PromptVersion, PromptVersion, String>) (v1, v2) -> v1.commit().substring(0, 6),
                            (BiFunction<PromptVersion, PromptVersion, Function<PromptVersion, Boolean>>) (
                                    v1, v2) -> v -> v.commit().startsWith(v1.commit().substring(0, 6))),
                    // ENDS_WITH operator
                    arguments(
                            Operator.ENDS_WITH,
                            "ends with suffix",
                            (BiFunction<PromptVersion, PromptVersion, String>) (v1, v2) -> v1.commit().substring(2),
                            (BiFunction<PromptVersion, PromptVersion, Function<PromptVersion, Boolean>>) (
                                    v1, v2) -> v -> v.commit().endsWith(v1.commit().substring(2))));
        }

        @ParameterizedTest(name = "Success: filter by commit - {1}")
        @MethodSource
        @DisplayName("Success: filter prompt versions by commit field")
        void filterPromptVersionsByCommit(
                Operator operator,
                String description,
                BiFunction<PromptVersion, PromptVersion, String> getFilterValue,
                BiFunction<PromptVersion, PromptVersion, Function<PromptVersion, Boolean>> getAssertion) {
            // Create prompt without template to avoid auto-creating initial version
            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .template(null)
                    .build();
            var promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            var versions = IntStream.range(0, 2)
                    .mapToObj(i -> factory.manufacturePojo(PromptVersion.class).toBuilder()
                            .promptId(promptId)
                            .build())
                    .map(version -> promptVersionResourceClient.createPromptVersion(
                            CreatePromptVersion.builder().name(prompt.name()).version(version).build(),
                            API_KEY,
                            TEST_WORKSPACE))
                    .toList();

            var version1 = versions.get(0);
            var version2 = versions.get(1);
            var filterValue = getFilterValue.apply(version1, version2);
            var filters = List.of(PromptVersionFilter.builder()
                    .field(PromptVersionField.COMMIT)
                    .operator(operator)
                    .value(filterValue)
                    .build());
            var page = promptVersionResourceClient.getPromptVersionsByPromptId(
                    promptId, API_KEY, TEST_WORKSPACE, filters, null);

            var assertion = getAssertion.apply(version1, version2);
            assertThat(page.total()).isEqualTo(1);
            assertThat(assertion.apply(page.content().getFirst())).isTrue();
        }

        Stream<Arguments> filterPromptVersionsByStringFields() {
            return Stream.of(
                    // CONTAINS - ID
                    arguments(
                            PromptVersionField.ID,
                            Operator.CONTAINS,
                            "ID contains substring",
                            (Function<PromptVersion, String>) v -> v.id().toString(),
                            (BiFunction<PromptVersion, String, String>) (v, field) -> field.substring(10, 20),
                            (BiFunction<PromptVersion, String, Boolean>) (v, filterValue) -> v.id().toString()
                                    .contains(filterValue)),
                    // NOT_CONTAINS - TEMPLATE
                    arguments(
                            PromptVersionField.TEMPLATE,
                            Operator.NOT_CONTAINS,
                            "template not contains random string",
                            (Function<PromptVersion, String>) PromptVersion::template,
                            (BiFunction<PromptVersion, String, String>) (v, field) -> field.substring(10, 20),
                            (BiFunction<PromptVersion, String, Boolean>) (v, filterValue) -> !v.template()
                                    .contains(filterValue)),
                    // STARTS_WITH - CHANGE_DESCRIPTION
                    arguments(
                            PromptVersionField.CHANGE_DESCRIPTION,
                            Operator.STARTS_WITH,
                            "change description starts with prefix",
                            (Function<PromptVersion, String>) PromptVersion::changeDescription,
                            (BiFunction<PromptVersion, String, String>) (v, field) -> field.substring(0, 6),
                            (BiFunction<PromptVersion, String, Boolean>) (v, filterValue) -> v.changeDescription()
                                    .startsWith(filterValue)),
                    // ENDS_WITH - CREATED_BY
                    arguments(
                            PromptVersionField.CREATED_BY,
                            Operator.ENDS_WITH,
                            "created_by ends with suffix",
                            (Function<PromptVersion, String>) PromptVersion::createdBy,
                            (BiFunction<PromptVersion, String, String>) (v, field) -> field
                                    .substring(field.length() - 8),
                            (BiFunction<PromptVersion, String, Boolean>) (v, filterValue) -> v.createdBy()
                                    .endsWith(filterValue)),
                    // EQUAL - ID
                    arguments(
                            PromptVersionField.ID,
                            Operator.EQUAL,
                            "exact ID match",
                            (Function<PromptVersion, String>) v -> v.id().toString(),
                            (BiFunction<PromptVersion, String, String>) (v, field) -> field,
                            (BiFunction<PromptVersion, String, Boolean>) (v, filterValue) -> v.id().toString()
                                    .equals(filterValue)),
                    // NOT_EQUAL - TEMPLATE
                    arguments(
                            PromptVersionField.TEMPLATE,
                            Operator.NOT_EQUAL,
                            "not equal to template",
                            (Function<PromptVersion, String>) PromptVersion::template,
                            (BiFunction<PromptVersion, String, String>) (v, field) -> field,
                            (BiFunction<PromptVersion, String, Boolean>) (v, filterValue) -> !v.template()
                                    .equals(filterValue)));
        }

        @ParameterizedTest(name = "Success: filter by {0} - {1}")
        @MethodSource
        @DisplayName("Success: filter prompt versions by string fields")
        void filterPromptVersionsByStringFields(
                PromptVersionField field,
                Operator operator,
                String description,
                Function<PromptVersion, String> getFieldValue,
                BiFunction<PromptVersion, String, String> getFilterValue,
                BiFunction<PromptVersion, String, Boolean> assertion) {
            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .template(null)
                    .build();
            var promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            // Create second API key with different user for version2
            var apiKey2 = UUID.randomUUID().toString();
            var user2 = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey2, TEST_WORKSPACE, WORKSPACE_ID, user2);

            var template1 = RandomStringUtils.secure().nextAlphanumeric(30);
            var template2 = RandomStringUtils.secure().nextAlphanumeric(30);
            var version1 = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .promptId(promptId)
                    .template(template1)
                    .build();
            var createdV1 = promptVersionResourceClient.createPromptVersion(
                    CreatePromptVersion.builder().name(prompt.name()).version(version1).build(), API_KEY,
                    TEST_WORKSPACE);
            var version2 = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .promptId(promptId)
                    .template(template2)
                    .build();
            promptVersionResourceClient.createPromptVersion(
                    CreatePromptVersion.builder().name(prompt.name()).version(version2).build(), apiKey2,
                    TEST_WORKSPACE);

            var fieldValue = getFieldValue.apply(createdV1);
            var filterValue = getFilterValue.apply(createdV1, fieldValue);
            var filters = List.of(PromptVersionFilter.builder()
                    .field(field)
                    .operator(operator)
                    .value(filterValue)
                    .build());
            var page = promptVersionResourceClient.getPromptVersionsByPromptId(
                    promptId, API_KEY, TEST_WORKSPACE, filters, null);

            assertThat(page.total()).isEqualTo(1);
            assertThat(assertion.apply(page.content().getFirst(), filterValue)).isTrue();
        }

        Stream<Arguments> filterPromptVersionsByCreatedAt() {
            return Stream.of(
                    // CREATED_AT - GREATER_THAN
                    arguments(
                            Operator.GREATER_THAN,
                            "created after timestamp",
                            (Function<Instant, Instant>) now -> now.minus(1, ChronoUnit.HOURS),
                            (BiFunction<PromptVersion, Instant, Boolean>) (v, filterValue) -> v.createdAt()
                                    .isAfter(filterValue)),
                    // CREATED_AT - LESS_THAN
                    arguments(
                            Operator.LESS_THAN,
                            "created before timestamp",
                            (Function<Instant, Instant>) now -> now.plus(1, ChronoUnit.HOURS),
                            (BiFunction<PromptVersion, Instant, Boolean>) (v, filterValue) -> v.createdAt()
                                    .isBefore(filterValue)),
                    // CREATED_AT - GREATER_THAN_EQUAL
                    arguments(
                            Operator.GREATER_THAN_EQUAL,
                            "created at or after timestamp",
                            (Function<Instant, Instant>) now -> now.minus(1, ChronoUnit.HOURS),
                            (BiFunction<PromptVersion, Instant, Boolean>) (v,
                                    filterValue) -> v.createdAt().isAfter(filterValue)),
                    // CREATED_AT - LESS_THAN_EQUAL
                    arguments(
                            Operator.LESS_THAN_EQUAL,
                            "created at or before timestamp",
                            (Function<Instant, Instant>) now -> now.plus(1, ChronoUnit.HOURS),
                            (BiFunction<PromptVersion, Instant, Boolean>) (v,
                                    filterValue) -> v.createdAt().isBefore(filterValue)),
                    // CREATED_AT - EQUAL
                    arguments(
                            Operator.EQUAL,
                            "exact created_at match",
                            (Function<Instant, Instant>) now -> now,
                            (BiFunction<PromptVersion, Instant, Boolean>) (v, filterValue) -> v.createdAt()
                                    .equals(filterValue)),
                    // CREATED_AT - NOT_EQUAL
                    arguments(
                            Operator.NOT_EQUAL,
                            "not equal to created_at",
                            (Function<Instant, Instant>) now -> now.plus(1, ChronoUnit.HOURS),
                            (BiFunction<PromptVersion, Instant, Boolean>) (v, filterValue) -> !v.createdAt()
                                    .equals(filterValue)));
        }

        @ParameterizedTest(name = "Success: filter by CREATED_AT - {1}")
        @MethodSource
        @DisplayName("Success: filter prompt versions by CREATED_AT field")
        void filterPromptVersionsByCreatedAt(
                Operator operator,
                String description,
                Function<Instant, Instant> getFilterValue,
                BiFunction<PromptVersion, Instant, Boolean> assertion) {
            var prompt = factory.manufacturePojo(Prompt.class).toBuilder()
                    .template(null)
                    .build();
            var promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            var version = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .promptId(promptId)
                    .build();
            var createdVersion = promptVersionResourceClient.createPromptVersion(
                    CreatePromptVersion.builder().name(prompt.name()).version(version).build(),
                    API_KEY,
                    TEST_WORKSPACE);

            // For EQUAL operator, use the actual createdAt timestamp
            var referenceTime = operator == Operator.EQUAL ? createdVersion.createdAt() : Instant.now();
            var filterValue = getFilterValue.apply(referenceTime);
            var filters = List.of(PromptVersionFilter.builder()
                    .field(PromptVersionField.CREATED_AT)
                    .operator(operator)
                    .value(filterValue.toString())
                    .build());
            var page = promptVersionResourceClient.getPromptVersionsByPromptId(
                    promptId, API_KEY, TEST_WORKSPACE, filters, null);

            assertThat(page.total()).isEqualTo(1);
            assertThat(assertion.apply(page.content().getFirst(), filterValue)).isTrue();
        }

        Stream<Arguments> filterPromptVersionsByType() {
            return Stream.of(
                    // EQUAL operator
                    arguments(
                            Operator.EQUAL,
                            PromptType.MUSTACHE,
                            (Function<PromptVersion, Boolean>) v -> v.type() == PromptType.MUSTACHE),
                    // NOT_EQUAL operator
                    arguments(
                            Operator.NOT_EQUAL,
                            PromptType.MUSTACHE,
                            (Function<PromptVersion, Boolean>) v -> v.type() != PromptType.MUSTACHE));
        }

        @ParameterizedTest(name = "Success: filter by TYPE - {0}")
        @MethodSource
        @DisplayName("Success: filter prompt versions by TYPE field (ENUM)")
        void filterPromptVersionsByType(
                Operator operator,
                PromptType filterType,
                Function<PromptVersion, Boolean> assertion) {
            // Create prompt without template to avoid auto-creating initial version
            var prompt = factory.manufacturePojo(Prompt.class).toBuilder().template(null).build();
            var promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            var version1 = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .promptId(promptId)
                    .type(PromptType.MUSTACHE)
                    .build();
            var version2 = factory.manufacturePojo(PromptVersion.class).toBuilder()
                    .promptId(promptId)
                    .type(PromptType.JINJA2)
                    .build();

            promptVersionResourceClient.createPromptVersion(
                    CreatePromptVersion.builder().name(prompt.name()).version(version1).build(), API_KEY,
                    TEST_WORKSPACE);
            promptVersionResourceClient.createPromptVersion(
                    CreatePromptVersion.builder().name(prompt.name()).version(version2).build(), API_KEY,
                    TEST_WORKSPACE);

            var filters = List.of(PromptVersionFilter.builder()
                    .field(PromptVersionField.TYPE)
                    .operator(operator)
                    .value(filterType.getValue())
                    .build());
            var page = promptVersionResourceClient.getPromptVersionsByPromptId(
                    promptId, API_KEY, TEST_WORKSPACE, filters, null);

            assertThat(page.total()).isEqualTo(1);
            assertThat(assertion.apply(page.content().getFirst())).isTrue();
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class PromptVersionSortingTest {
        Stream<Arguments> sortPromptVersions() {
            var idComparator = Comparator.comparing(PromptVersion::id);
            var commitComparator = Comparator.comparing(
                    PromptVersion::commit, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(PromptVersion::id, Comparator.reverseOrder());
            var templateComparator = Comparator.comparing(
                    PromptVersion::template, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(PromptVersion::id, Comparator.reverseOrder());
            var changeDescriptionComparator = Comparator.comparing(
                    PromptVersion::changeDescription, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(PromptVersion::id, Comparator.reverseOrder());
            var typeComparator = Comparator
                    .comparing((PromptVersion v) -> v.type().ordinal())
                    .thenComparing(PromptVersion::id, Comparator.reverseOrder());
            var typeComparatorReversed = Comparator
                    .comparing((PromptVersion v) -> v.type().ordinal(), Comparator.reverseOrder())
                    .thenComparing(PromptVersion::id, Comparator.reverseOrder());
            var tagsComparator = Comparator
                    .comparing((PromptVersion v) -> v.tags().toString(), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(PromptVersion::id, Comparator.reverseOrder());
            var createdAtComparator = Comparator.comparing(PromptVersion::createdAt)
                    .thenComparing(PromptVersion::id, Comparator.reverseOrder());
            var createdByComparator = Comparator
                    .comparing(PromptVersion::createdBy, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(PromptVersion::id, Comparator.reverseOrder());
            return Stream.of(
                    arguments(
                            idComparator,
                            "sort by ID ASC",
                            List.of(SortingField.builder().field(SortableFields.ID).direction(Direction.ASC).build())),
                    arguments(
                            idComparator.reversed(),
                            "sort by ID DESC",
                            List.of(SortingField.builder().field(SortableFields.ID).direction(Direction.DESC)
                                    .build())),
                    arguments(
                            commitComparator,
                            "sort by COMMIT ASC",
                            List.of(SortingField.builder().field(SortableFields.COMMIT).direction(Direction.ASC)
                                    .build())),
                    arguments(
                            commitComparator.reversed(),
                            "sort by COMMIT DESC",
                            List.of(SortingField.builder().field(SortableFields.COMMIT).direction(Direction.DESC)
                                    .build())),
                    arguments(
                            templateComparator,
                            "sort by TEMPLATE ASC",
                            List.of(SortingField.builder().field(SortableFields.TEMPLATE).direction(Direction.ASC)
                                    .build())),
                    arguments(
                            templateComparator.reversed(),
                            "sort by TEMPLATE DESC",
                            List.of(SortingField.builder().field(SortableFields.TEMPLATE).direction(Direction.DESC)
                                    .build())),
                    arguments(
                            changeDescriptionComparator,
                            "sort by CHANGE_DESCRIPTION ASC",
                            List.of(SortingField.builder().field(SortableFields.CHANGE_DESCRIPTION)
                                    .direction(Direction.ASC)
                                    .build())),
                    arguments(
                            changeDescriptionComparator.reversed(),
                            "sort by CHANGE_DESCRIPTION DESC",
                            List.of(SortingField.builder().field(SortableFields.CHANGE_DESCRIPTION)
                                    .direction(Direction.DESC)
                                    .build())),
                    arguments(
                            typeComparator,
                            "sort by TYPE ASC",
                            List.of(SortingField.builder().field(SortableFields.TYPE).direction(Direction.ASC)
                                    .build())),
                    arguments(
                            typeComparatorReversed,
                            "sort by TYPE DESC",
                            List.of(SortingField.builder().field(SortableFields.TYPE).direction(Direction.DESC)
                                    .build())),
                    arguments(
                            tagsComparator,
                            "sort by TAGS ASC",
                            List.of(SortingField.builder().field(SortableFields.TAGS).direction(Direction.ASC)
                                    .build())),
                    arguments(
                            tagsComparator.reversed(),
                            "sort by TAGS DESC",
                            List.of(SortingField.builder().field(SortableFields.TAGS).direction(Direction.DESC)
                                    .build())),
                    arguments(
                            createdAtComparator,
                            "sort by CREATED_AT ASC",
                            List.of(SortingField.builder().field(SortableFields.CREATED_AT).direction(Direction.ASC)
                                    .build())),
                    arguments(
                            createdAtComparator.reversed(),
                            "sort by CREATED_AT DESC",
                            List.of(SortingField.builder().field(SortableFields.CREATED_AT).direction(Direction.DESC)
                                    .build())),
                    arguments(
                            createdByComparator,
                            "sort by CREATED_BY ASC",
                            List.of(SortingField.builder().field(SortableFields.CREATED_BY).direction(Direction.ASC)
                                    .build())),
                    arguments(
                            createdByComparator.reversed(),
                            "sort by CREATED_BY DESC",
                            List.of(SortingField.builder().field(SortableFields.CREATED_BY).direction(Direction.DESC)
                                    .build())),
                    // Multi-field sorting
                    arguments(
                            Comparator.comparing((PromptVersion v) -> v.type().ordinal())
                                    .thenComparing(PromptVersion::createdAt, Comparator.reverseOrder())
                                    .thenComparing(PromptVersion::id, Comparator.reverseOrder()),
                            "sort by TYPE ASC, then CREATED_AT DESC",
                            List.of(
                                    SortingField.builder().field(SortableFields.TYPE).direction(Direction.ASC).build(),
                                    SortingField.builder().field(SortableFields.CREATED_AT).direction(Direction.DESC)
                                            .build())));
        }

        @ParameterizedTest(name = "{1}")
        @MethodSource
        @DisplayName("Success: sort prompt versions by all sortable fields")
        void sortPromptVersions(
                Comparator<PromptVersion> comparator, String description, List<SortingField> sortingFields) {
            var prompt = factory.manufacturePojo(Prompt.class).toBuilder().template(null).build();
            var promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            var expectedVersions = PodamFactoryUtils.manufacturePojoList(factory, PromptVersion.class).stream()
                    .map(promptVersion -> promptVersion.toBuilder()
                            .promptId(promptId)
                            // Let server generate these fields
                            .id(null)
                            .commit(null)
                            // Only alphanumeric to avoid flakiness with special characters when sorting
                            .template(RandomStringUtils.secure().nextAlphanumeric(10))
                            .changeDescription(RandomStringUtils.secure().nextAlphanumeric(10))
                            .build())
                    .map(promptVersion -> {
                        // With unique users to test sort by created_by
                        var apiKey = "apiKey-" + UUID.randomUUID();
                        var user = RandomStringUtils.secure().nextAlphanumeric(10);
                        mockTargetWorkspace(apiKey, TEST_WORKSPACE, WORKSPACE_ID, user);
                        return promptVersionResourceClient.createPromptVersion(
                                CreatePromptVersion.builder().name(prompt.name()).version(promptVersion).build(),
                                apiKey, TEST_WORKSPACE);
                    })
                    .toList();

            var page = promptVersionResourceClient.getPromptVersionsByPromptId(
                    promptId, API_KEY, TEST_WORKSPACE, null, sortingFields);

            // Verify sort order by comparing IDs in the expected order
            var actualIds = page.content().stream().map(PromptVersion::id).toList();
            var expectedIds = expectedVersions.stream().sorted(comparator).map(PromptVersion::id).toList();

            assertThat(page.content()).hasSize(5);
            assertThat(actualIds).containsExactlyElementsOf(expectedIds);
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class UpdatePromptVersionsTest {

        Stream<Arguments> batchUpdatePromptVersionTags() {
            BiFunction<Set<String>, Set<String>, Set<String>> expectedReplace = (initial, update) -> update;
            BiFunction<Set<String>, Set<String>, Set<String>> expectedPreserve = (initial, update) -> initial;
            return Stream.of(
                    arguments("replace",
                            PodamFactoryUtils.manufacturePojoSet(factory, String.class),
                            false,
                            expectedReplace),
                    arguments("replace is the default",
                            PodamFactoryUtils.manufacturePojoSet(factory, String.class),
                            null,
                            expectedReplace),
                    arguments("replace empty clears",
                            Set.of(),
                            false,
                            (BiFunction<Set<String>, Set<String>, Set<String>>) (initial, update) -> null),
                    arguments("replace null preserves",
                            null,
                            false,
                            expectedPreserve),
                    arguments("merge",
                            PodamFactoryUtils.manufacturePojoSet(factory, String.class),
                            true,
                            (BiFunction<Set<String>, Set<String>, Set<String>>) SetUtils::union),
                    arguments("merge empty has no effect",
                            Set.of(),
                            true,
                            expectedPreserve),
                    arguments("merge null has no effect",
                            null,
                            true,
                            expectedPreserve));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource
        @DisplayName("Success: batch update prompt version tags")
        void batchUpdatePromptVersionTags(
                String testCase,
                Set<String> updateTags,
                Boolean mergeTags,
                BiFunction<Set<String>, Set<String>, Set<String>> expectedTagsFunction) {
            var prompt = factory.manufacturePojo(Prompt.class).toBuilder().template(null).build();
            var promptId = createPrompt(prompt, API_KEY, TEST_WORKSPACE);

            var createdVersions = PodamFactoryUtils.manufacturePojoList(factory, PromptVersion.class).stream()
                    .map(promptVersion -> promptVersion.toBuilder()
                            .promptId(promptId)
                            .build())
                    .map(promptVersion -> promptVersionResourceClient.createPromptVersion(
                            CreatePromptVersion.builder().name(prompt.name()).version(promptVersion).build(),
                            API_KEY, TEST_WORKSPACE))
                    .toList();
            var initialTagsMap = createdVersions.stream()
                    .collect(Collectors.toMap(PromptVersion::id, PromptVersion::tags));

            // Batch update tags
            var update = PromptVersionUpdate.builder().tags(updateTags).build();
            var batchUpdate = PromptVersionBatchUpdate.builder()
                    .ids(initialTagsMap.keySet())
                    .update(update)
                    .mergeTags(mergeTags)
                    .build();
            promptVersionResourceClient.updatePromptVersions(batchUpdate, API_KEY, TEST_WORKSPACE);

            // Retrieve all versions after update
            var page = promptVersionResourceClient.getPromptVersionsByPromptId(
                    promptId, API_KEY, TEST_WORKSPACE, null, null);

            assertThat(page.content()).hasSize(5);
            page.content().forEach(actualVersion -> {
                var initialTags = initialTagsMap.get(actualVersion.id());
                var expectedTags = expectedTagsFunction.apply(initialTags, updateTags);
                assertThat(actualVersion.tags()).isEqualTo(expectedTags);
            });
        }
    }
}