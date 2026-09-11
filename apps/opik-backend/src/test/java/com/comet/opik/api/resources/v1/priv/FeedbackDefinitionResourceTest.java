package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.FeedbackDefinition;
import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.FeedbackScoreDefinitionAssertions;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.FeedbackDefinitionResourceClient;
import com.comet.opik.domain.FeedbackDefinitionService;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.apache.hc.core5.http.HttpStatus;
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
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.FeedbackDefinition.CategoricalFeedbackDefinition;
import static com.comet.opik.api.FeedbackDefinition.CategoricalFeedbackDefinition.CategoricalFeedbackDetail;
import static com.comet.opik.api.FeedbackDefinition.FeedbackDefinitionPage;
import static com.comet.opik.api.FeedbackDefinition.NumericalFeedbackDefinition;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.FAKE_API_KEY_MESSAGE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.NO_API_KEY_RESPONSE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.UNAUTHORIZED_RESPONSE;
import static com.comet.opik.domain.FeedbackDefinitionModel.FeedbackType;
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
@DisplayName("Feedback Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class FeedbackDefinitionResourceTest {

    private static final String URL_PATTERN = "http://.*/v1/private/feedback-definitions/.{8}-.{4}-.{4}-.{4}-.{12}";
    private static final String URL_TEMPLATE = "%s/v1/private/feedback-definitions";
    private static final String[] IGNORED_FIELDS = new String[]{"createdAt", "lastUpdatedAt", "id", "lastUpdatedBy",
            "createdBy"};

    private static final String USER = UUID.randomUUID().toString();
    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MYSQL).join();

        wireMock = WireMockUtils.startWireMock();

        MigrationUtils.runMysqlDbMigration(MYSQL);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(MYSQL.getJdbcUrl(), null,
                wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();
    private final TimeBasedEpochGenerator generator = Generators.timeBasedEpochGenerator();

    private String baseURI;
    private ClientSupport client;
    private FeedbackDefinitionResourceClient feedbackDefinitionResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;

        ClientSupportUtils.config(client);

        feedbackDefinitionResourceClient = new FeedbackDefinitionResourceClient(client, baseURI);

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private UUID create(final FeedbackDefinition<?> feedback, String apiKey, String workspaceName) {

        try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(feedback))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);

            return TestUtils.getIdFromLocation(actualResponse.getLocation());
        }
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
        @DisplayName("create feedback definition: when api key is present, then return proper response")
        void createFeedbackDefinition__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey,
                boolean isAuthorized, io.dropwizard.jersey.errors.ErrorMessage errorMessage) {

            var feedbackDefinition = factory.manufacturePojo(NumericalFeedbackDefinition.class);

            mockTargetWorkspace(okApikey, TEST_WORKSPACE, WORKSPACE_ID);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(feedbackDefinition))) {

                if (isAuthorized) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(errorMessage);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("get feedback definition: when api key is present, then return proper response")
        void getFeedbackDefinition__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey, boolean isAuthorized,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, workspaceId);

            int size = 15;

            IntStream.range(0, size).forEach(i -> {
                create(i % 2 == 0
                        ? factory.manufacturePojo(FeedbackDefinition.CategoricalFeedbackDefinition.class)
                        : factory.manufacturePojo(FeedbackDefinition.NumericalFeedbackDefinition.class),
                        okApikey,
                        workspaceName);
            });

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("size", size)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (isAuthorized) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();

                    var actualEntity = actualResponse.readEntity(FeedbackDefinitionPage.class);
                    assertThat(actualEntity.content()).hasSize(size);

                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(errorMessage);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("get feedback definition by id: when api key is present, then return proper response")
        void getFeedbackDefinitionById__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey,
                boolean isAuthorized, io.dropwizard.jersey.errors.ErrorMessage errorMessage) {

            var feedback = factory.manufacturePojo(FeedbackDefinition.NumericalFeedbackDefinition.class);

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, workspaceId);

            UUID id = create(feedback, okApikey, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (isAuthorized) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();

                    var feedbackDefinition = actualResponse
                            .readEntity(FeedbackDefinition.NumericalFeedbackDefinition.class);
                    assertThat(feedbackDefinition.getId()).isEqualTo(id);
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(errorMessage);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("update feedback definition: when api key is present, then return proper response")
        void updateFeedbackDefinition__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey,
                boolean isAuthorized, io.dropwizard.jersey.errors.ErrorMessage errorMessage) {

            var feedback = factory.manufacturePojo(FeedbackDefinition.NumericalFeedbackDefinition.class);

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, workspaceId);

            UUID id = create(feedback, okApikey, workspaceName);

            var updatedFeedback = feedback.toBuilder()
                    .name(UUID.randomUUID().toString())
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .put(Entity.json(updatedFeedback))) {

                if (isAuthorized) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(errorMessage);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("delete feedback definition: when api key is present, then return proper response")
        void deleteFeedbackDefinition__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey,
                boolean isAuthorized, io.dropwizard.jersey.errors.ErrorMessage errorMessage) {

            var feedback = factory.manufacturePojo(FeedbackDefinition.NumericalFeedbackDefinition.class);

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, workspaceId);

            UUID id = create(feedback, okApikey, workspaceName);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .delete()) {

                if (isAuthorized) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
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

        @BeforeEach
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
        @DisplayName("create feedback definition: when session token is present, then return proper response")
        void createFeedbackDefinition__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                boolean success, String workspaceName) {

            var feedbackDefinition = factory.manufacturePojo(NumericalFeedbackDefinition.class);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(feedbackDefinition))) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("get feedback definitions: when session token is present, then return proper response")
        void getFeedbackDefinitions__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                boolean success, String workspaceName) {

            int size = 15;
            var newWorkspaceName = UUID.randomUUID().toString();
            var newWorkspaceId = UUID.randomUUID().toString();

            wireMock.server().stubFor(
                    post(urlPathEqualTo("/opik/auth-session"))
                            .withCookie(SESSION_COOKIE, equalTo(sessionToken))
                            .withRequestBody(matchingJsonPath("$.workspaceName", equalTo(newWorkspaceName)))
                            .willReturn(okJson(AuthTestUtils.newWorkspaceAuthResponse(USER, newWorkspaceId))));

            IntStream.range(0, size).forEach(i -> {
                create(i % 2 == 0
                        ? factory.manufacturePojo(FeedbackDefinition.CategoricalFeedbackDefinition.class)
                        : factory.manufacturePojo(FeedbackDefinition.NumericalFeedbackDefinition.class), API_KEY,
                        TEST_WORKSPACE);
            });

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("workspace_name", workspaceName)
                    .queryParam("size", size)
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();

                    var actualEntity = actualResponse.readEntity(FeedbackDefinitionPage.class);
                    assertThat(actualEntity.content()).hasSize(size);
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("get feedback definition by id: when session token is present, then return proper response")
        void getFeedbackDefinitionById__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                boolean success, String workspaceName) {

            var feedback = factory.manufacturePojo(FeedbackDefinition.NumericalFeedbackDefinition.class);

            UUID id = create(feedback, API_KEY, TEST_WORKSPACE);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();

                    var feedbackDefinition = actualResponse
                            .readEntity(FeedbackDefinition.NumericalFeedbackDefinition.class);
                    assertThat(feedbackDefinition.getId()).isEqualTo(id);
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }

        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("update feedback definition: when session token is present, then return proper response")
        void updateFeedbackDefinition__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                boolean success, String workspaceName) {

            var feedback = factory.manufacturePojo(FeedbackDefinition.NumericalFeedbackDefinition.class);

            UUID id = create(feedback, API_KEY, TEST_WORKSPACE);

            var updatedFeedback = feedback.toBuilder()
                    .name(UUID.randomUUID().toString())
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .put(Entity.json(updatedFeedback))) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("delete feedback definition: when session token is present, then return proper response")
        void deleteFeedbackDefinition__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                boolean success, String workspaceName) {

            var feedback = factory.manufacturePojo(FeedbackDefinition.NumericalFeedbackDefinition.class);

            UUID id = create(feedback, API_KEY, TEST_WORKSPACE);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .delete()) {

                if (success) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }
    }

    @Nested
    @DisplayName("Get:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetAllFeedbackDefinition {

        @Test
        @DisplayName("Success")
        void find() {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            IntStream.range(0, 15).forEach(i -> {
                create(i % 2 == 0
                        ? factory.manufacturePojo(FeedbackDefinition.CategoricalFeedbackDefinition.class)
                        : factory.manufacturePojo(FeedbackDefinition.NumericalFeedbackDefinition.class),
                        apiKey,
                        workspaceName);
            });

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("workspace_name", workspaceName)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            var actualEntity = actualResponse.readEntity(FeedbackDefinitionPage.class);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            assertThat(actualEntity.page()).isEqualTo(1);
            assertThat(actualEntity.size()).isEqualTo(10);
            assertThat(actualEntity.content()).hasSize(10);
            assertThat(actualEntity.total()).isGreaterThanOrEqualTo(15);
        }

        @Test
        @DisplayName("when searching by name, then return feedbacks")
        void find__whenSearchingByName__thenReturnFeedbacks() {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);
            String name = "My Feedback:" + UUID.randomUUID();

            var feedback = factory.manufacturePojo(FeedbackDefinition.NumericalFeedbackDefinition.class)
                    .toBuilder()
                    .name(name)
                    .build();

            create(feedback, apiKey, workspaceName);

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("name", "eedback")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            var actualEntity = actualResponse.readEntity(FeedbackDefinitionPage.class);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            assertThat(actualEntity.page()).isEqualTo(1);
            assertThat(actualEntity.size()).isEqualTo(1);
            assertThat(actualEntity.total()).isEqualTo(1);

            List<FeedbackDefinition<?>> content = actualEntity.content();
            assertThat(content.stream().map(FeedbackDefinition::getName).toList()).contains(name);
        }

        @Test
        @DisplayName("when searching by type, then return feedbacks")
        void find__whenSearchingByType__thenReturnFeedbacks() {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var feedback1 = factory.manufacturePojo(FeedbackDefinition.NumericalFeedbackDefinition.class);
            var feedback2 = factory.manufacturePojo(FeedbackDefinition.CategoricalFeedbackDefinition.class);

            create(feedback1, apiKey, workspaceName);
            create(feedback2, apiKey, workspaceName);

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("type", FeedbackType.NUMERICAL.getType())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            var actualEntity = actualResponse.readEntity(FeedbackDefinitionPage.class);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            assertThat(actualEntity.page()).isEqualTo(1);
            assertThat(actualEntity.size()).isEqualTo(1);
            assertThat(actualEntity.total()).isEqualTo(1);

            List<FeedbackDefinition<?>> content = actualEntity.content();
            assertThat(
                    content.stream().map(FeedbackDefinition::getType).allMatch(type -> FeedbackType.NUMERICAL == type))
                    .isTrue();
        }

        @Test
        @DisplayName("when searching by workspace name, then return feedbacks")
        void find__whenSearchingByWorkspaceName__thenReturnFeedbacks() {

            String workspaceName = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();

            String workspaceName2 = UUID.randomUUID().toString();
            String workspaceId2 = UUID.randomUUID().toString();
            String apiKey2 = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);
            mockTargetWorkspace(apiKey2, workspaceName2, workspaceId2);

            var feedback1 = factory.manufacturePojo(FeedbackDefinition.NumericalFeedbackDefinition.class);

            var feedback2 = factory.manufacturePojo(FeedbackDefinition.CategoricalFeedbackDefinition.class);

            create(feedback1, apiKey, workspaceName);
            create(feedback2, apiKey2, workspaceName2);

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey2)
                    .header(WORKSPACE_HEADER, workspaceName2)
                    .get();

            var actualEntity = actualResponse.readEntity(FeedbackDefinitionPage.class);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            assertThat(actualEntity.page()).isEqualTo(1);
            assertThat(actualEntity.size()).isEqualTo(1);
            assertThat(actualEntity.total()).isEqualTo(1);
            assertThat(actualEntity.content()).hasSize(1);

            FeedbackDefinition<CategoricalFeedbackDetail> actual = (FeedbackDefinition<CategoricalFeedbackDetail>) actualEntity
                    .content().get(0);

            assertThat(actual.getName()).isEqualTo(feedback2.getName());
            assertThat(actual.getDetails().getCategories()).isEqualTo(feedback2.getDetails().getCategories());
            assertThat(actual.getType()).isEqualTo(feedback2.getType());
        }

        @Test
        @DisplayName("when searching by name and workspace, then return feedbacks")
        void find__whenSearchingByNameAndWorkspace__thenReturnFeedbacks() {

            var name = UUID.randomUUID().toString();

            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();

            var workspaceName2 = UUID.randomUUID().toString();
            var workspaceId2 = UUID.randomUUID().toString();

            var apiKey = UUID.randomUUID().toString();
            var apiKey2 = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);
            mockTargetWorkspace(apiKey2, workspaceName2, workspaceId2);

            var feedback1 = factory.manufacturePojo(FeedbackDefinition.NumericalFeedbackDefinition.class).toBuilder()
                    .name(name)
                    .build();

            var feedback2 = factory.manufacturePojo(FeedbackDefinition.CategoricalFeedbackDefinition.class).toBuilder()
                    .name(name)
                    .build();

            create(feedback1, apiKey, workspaceName);
            create(feedback2, apiKey2, workspaceName2);

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("name", name)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey2)
                    .header(WORKSPACE_HEADER, workspaceName2)
                    .get();

            var actualEntity = actualResponse.readEntity(FeedbackDefinitionPage.class);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            assertThat(actualEntity.page()).isEqualTo(1);
            assertThat(actualEntity.size()).isEqualTo(1);
            assertThat(actualEntity.total()).isEqualTo(1);
            assertThat(actualEntity.content()).hasSize(1);

            FeedbackDefinition<CategoricalFeedbackDetail> actual = (FeedbackDefinition<CategoricalFeedbackDetail>) actualEntity
                    .content().get(0);

            assertThat(actual.getName()).isEqualTo(feedback2.getName());
            assertThat(actual.getDetails().getCategories()).isEqualTo(feedback2.getDetails().getCategories());
            assertThat(actual.getType()).isEqualTo(feedback2.getType());
        }

    }

    @Nested
    @DisplayName("Get {id}:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetFeedbackDefinition {

        private Stream<Arguments> feedbackDefinitionProvider() {
            return Stream.of(
                    arguments("Numerical", factory.manufacturePojo(NumericalFeedbackDefinition.class),
                            FeedbackType.NUMERICAL),
                    arguments("Categorical",
                            factory.manufacturePojo(CategoricalFeedbackDefinition.class)
                                    .toBuilder()
                                    .details(CategoricalFeedbackDetail.builder()
                                            .categories(Map.of("good", 1.0, "bad", 0.0))
                                            .build())
                                    .build(),
                            FeedbackType.CATEGORICAL),
                    arguments("Boolean",
                            factory.manufacturePojo(FeedbackDefinition.BooleanFeedbackDefinition.class)
                                    .toBuilder()
                                    .details(
                                            FeedbackDefinition.BooleanFeedbackDefinition.BooleanFeedbackDetail.builder()
                                                    .trueLabel("Pass")
                                                    .falseLabel("Fail")
                                                    .build())
                                    .build(),
                            FeedbackType.BOOLEAN));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("feedbackDefinitionProvider")
        @DisplayName("Success - Get feedback definition by ID")
        void getById(String typeName, FeedbackDefinition<?> feedback, FeedbackType expectedType) {

            var id = create(feedback, API_KEY, TEST_WORKSPACE);

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get();

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            var actualEntity = actualResponse.readEntity(FeedbackDefinition.class);

            assertThat(actualEntity)
                    .usingRecursiveComparison(RecursiveComparisonConfiguration.builder()
                            .withIgnoredFields(IGNORED_FIELDS)
                            .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                            .build())
                    .isEqualTo(feedback);

            assertThat(actualEntity.getType()).isEqualTo(expectedType);
            assertThat(actualEntity.getLastUpdatedBy()).isEqualTo(USER);
            assertThat(actualEntity.getCreatedBy()).isEqualTo(USER);
            assertThat(actualEntity.getCreatedAt()).isNotNull();
            assertThat(actualEntity.getCreatedAt()).isInstanceOf(Instant.class);
            assertThat(actualEntity.getLastUpdatedAt()).isNotNull();
            assertThat(actualEntity.getLastUpdatedAt()).isInstanceOf(Instant.class);

            assertThat(actualEntity.getCreatedAt()).isAfter(feedback.getCreatedAt());
            assertThat(actualEntity.getLastUpdatedAt()).isAfter(feedback.getLastUpdatedAt());
        }

        @Test
        @DisplayName("when feedback does not exist, then return not found")
        void getById__whenFeedbackDoesNotExist__thenReturnNotFound() {

            var id = generator.generate();

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get();

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(404);
            var actualEntity = actualResponse.readEntity(ErrorMessage.class);

            assertThat(actualEntity.errors()).containsExactly("Feedback definition not found");
        }

    }

    @Nested
    @DisplayName("Create:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CreateFeedbackDefinition {

        private Stream<Arguments> feedbackDefinitionProvider() {
            return Stream.of(
                    arguments("Numerical", factory.manufacturePojo(NumericalFeedbackDefinition.class),
                            FeedbackType.NUMERICAL),
                    arguments("Categorical",
                            factory.manufacturePojo(CategoricalFeedbackDefinition.class)
                                    .toBuilder()
                                    .details(CategoricalFeedbackDetail.builder()
                                            .categories(Map.of("good", 1.0, "bad", 0.0))
                                            .build())
                                    .build(),
                            FeedbackType.CATEGORICAL),
                    arguments("Boolean",
                            factory.manufacturePojo(FeedbackDefinition.BooleanFeedbackDefinition.class)
                                    .toBuilder()
                                    .details(
                                            FeedbackDefinition.BooleanFeedbackDefinition.BooleanFeedbackDetail.builder()
                                                    .trueLabel("Pass")
                                                    .falseLabel("Fail")
                                                    .build())
                                    .build(),
                            FeedbackType.BOOLEAN));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("feedbackDefinitionProvider")
        @DisplayName("Success - Create feedback definition")
        void create(String typeName, FeedbackDefinition<?> feedbackDefinition, FeedbackType expectedType) {
            UUID id;

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(feedbackDefinition))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
                assertThat(actualResponse.hasEntity()).isFalse();
                assertThat(actualResponse.getHeaderString("Location")).matches(Pattern.compile(URL_PATTERN));

                id = TestUtils.getIdFromLocation(actualResponse.getLocation());
            }

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get();

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

            var actualEntity = actualResponse.readEntity(FeedbackDefinition.class);

            assertThat(actualEntity.getId()).isEqualTo(id);
            assertThat(actualEntity.getType()).isEqualTo(expectedType);
        }

        @Test
        @DisplayName("when feedback already exists, then return error")
        void create__whenFeedbackAlreadyExists__thenReturnError() {

            NumericalFeedbackDefinition feedback = factory
                    .manufacturePojo(FeedbackDefinition.NumericalFeedbackDefinition.class);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(feedback))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(feedback))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(409);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors())
                        .containsExactly("Feedback already exists");
            }
        }

        @Test
        @DisplayName("when details is null, then return bad request")
        void create__whenDetailsIsNull__thenReturnBadRequest() {

            var feedbackDefinition = factory.manufacturePojo(NumericalFeedbackDefinition.class).toBuilder()
                    .details(null)
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(feedbackDefinition))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors())
                        .containsExactly("details must not be null");

            }
        }

        @Test
        @DisplayName("when name is null, then return bad request")
        void create__whenNameIsNull__thenReturnBadRequest() {

            var feedbackDefinition = factory.manufacturePojo(CategoricalFeedbackDefinition.class).toBuilder()
                    .name(null)
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(feedbackDefinition))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors())
                        .containsExactly("name must not be blank");
            }
        }

        @Test
        @DisplayName("when categoryName is null, then return bad request")
        void create__whenCategoryIsNull__thenReturnBadRequest() {

            var feedbackDefinition = factory.manufacturePojo(CategoricalFeedbackDefinition.class).toBuilder()
                    .details(CategoricalFeedbackDetail
                            .builder()
                            .build())
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(feedbackDefinition))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors())
                        .containsExactly("details.categories must not be null");
            }
        }

        @Test
        @DisplayName("when categoryName is empty, then return bad request")
        void create__whenCategoryIsEmpty__thenReturnBadRequest() {

            var feedbackDefinition = factory.manufacturePojo(CategoricalFeedbackDefinition.class).toBuilder()
                    .details(CategoricalFeedbackDetail.builder().categories(Map.of()).build())
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(feedbackDefinition))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors())
                        .containsExactly("details.categories size must be between 2 and 2147483647");
            }
        }

        @Test
        @DisplayName("when categoryName has one key pair, then return bad request")
        void create__whenCategoryHasOneKeyPair__thenReturnBadRequest() {

            var feedbackDefinition = factory.manufacturePojo(CategoricalFeedbackDefinition.class).toBuilder()
                    .details(
                            CategoricalFeedbackDetail.builder()
                                    .categories(Map.of("yes", 1.0))
                                    .build())
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(feedbackDefinition))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors())
                        .containsExactly("details.categories size must be between 2 and 2147483647");
            }
        }

        @Test
        @DisplayName("when numerical min is null, then return bad request")
        void create__whenNumericalMinIsNull__thenReturnBadRequest() {

            var feedbackDefinition = factory.manufacturePojo(NumericalFeedbackDefinition.class).toBuilder()
                    .details(NumericalFeedbackDefinition.NumericalFeedbackDetail
                            .builder()
                            .max(BigDecimal.valueOf(10))
                            .build())
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(feedbackDefinition))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors())
                        .containsExactly("details.min must not be null");
            }
        }

        @Test
        @DisplayName("when numerical max is null, then return bad request")
        void create__whenNumericalMaxIsNull__thenReturnBadRequest() {

            var feedbackDefinition = factory.manufacturePojo(NumericalFeedbackDefinition.class).toBuilder()
                    .details(NumericalFeedbackDefinition.NumericalFeedbackDetail
                            .builder()
                            .min(BigDecimal.valueOf(10))
                            .build())
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(feedbackDefinition))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors())
                        .containsExactly("details.max must not be null");
            }
        }

        @Test
        @DisplayName("when boolean true label is blank, then return bad request")
        void create__whenBooleanTrueLabelIsBlank__thenReturnBadRequest() {

            var feedbackDefinition = factory.manufacturePojo(FeedbackDefinition.BooleanFeedbackDefinition.class)
                    .toBuilder()
                    .details(FeedbackDefinition.BooleanFeedbackDefinition.BooleanFeedbackDetail.builder()
                            .trueLabel("")
                            .falseLabel("Fail")
                            .build())
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(feedbackDefinition))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors())
                        .containsExactly("details.trueLabel must not be blank");
            }
        }

        @Test
        @DisplayName("when boolean false label is blank, then return bad request")
        void create__whenBooleanFalseLabelIsBlank__thenReturnBadRequest() {

            var feedbackDefinition = factory.manufacturePojo(FeedbackDefinition.BooleanFeedbackDefinition.class)
                    .toBuilder()
                    .details(FeedbackDefinition.BooleanFeedbackDefinition.BooleanFeedbackDetail.builder()
                            .trueLabel("Pass")
                            .falseLabel("")
                            .build())
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(feedbackDefinition))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors())
                        .containsExactly("details.falseLabel must not be blank");
            }
        }

        @Test
        @DisplayName("when numerical max is smaller than min, then return bad request")
        void create__whenNumericalMaxIsSmallerThanMin__thenReturnBadRequest() {

            var feedbackDefinition = factory.manufacturePojo(NumericalFeedbackDefinition.class).toBuilder()
                    .details(NumericalFeedbackDefinition.NumericalFeedbackDetail
                            .builder()
                            .min(BigDecimal.valueOf(10))
                            .max(BigDecimal.valueOf(1))
                            .build())
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .post(Entity.json(feedbackDefinition))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors())
                        .containsExactly("details.min has to be smaller than details.max");
            }
        }
    }

    @Nested
    @DisplayName("Update:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class UpdateFeedbackDefinition {

        @Test

        void notfound() {

            UUID id = generator.generate();

            var feedbackDefinition = factory.manufacturePojo(CategoricalFeedbackDefinition.class).toBuilder()
                    .details(CategoricalFeedbackDetail
                            .builder()
                            .categories(Map.of("yes", 1., "no", 0.))
                            .build())
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .put(Entity.json(feedbackDefinition))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(404);
                assertThat(actualResponse.hasEntity()).isTrue();

                var actualEntity = actualResponse.readEntity(ErrorMessage.class);
                assertThat(actualEntity.errors()).containsExactly("Feedback definition not found");
            }
        }

        private Stream<Arguments> feedbackDefinitionProvider() {
            return Stream.of(
                    arguments("Numerical", factory.manufacturePojo(NumericalFeedbackDefinition.class)),
                    arguments("Categorical",
                            factory.manufacturePojo(CategoricalFeedbackDefinition.class)
                                    .toBuilder()
                                    .details(CategoricalFeedbackDetail.builder()
                                            .categories(Map.of("good", 1.0, "bad", 0.0))
                                            .build())
                                    .build()),
                    arguments("Boolean",
                            factory.manufacturePojo(FeedbackDefinition.BooleanFeedbackDefinition.class)
                                    .toBuilder()
                                    .details(
                                            FeedbackDefinition.BooleanFeedbackDefinition.BooleanFeedbackDetail.builder()
                                                    .trueLabel("Pass")
                                                    .falseLabel("Fail")
                                                    .build())
                                    .build()));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("feedbackDefinitionProvider")
        @DisplayName("Success - Update feedback definition")
        void update(String typeName, FeedbackDefinition<?> feedbackDefinition) {

            String nameUpdated = UUID.randomUUID().toString();
            String descriptionUpdated = UUID.randomUUID().toString();

            UUID id = create(feedbackDefinition, API_KEY, TEST_WORKSPACE);

            FeedbackDefinition<?> feedbackDefinition1 = switch (feedbackDefinition) {
                case NumericalFeedbackDefinition numerical -> numerical.toBuilder()
                        .name(nameUpdated)
                        .description(descriptionUpdated)
                        .build();
                case CategoricalFeedbackDefinition categorical -> categorical.toBuilder()
                        .name(nameUpdated)
                        .description(descriptionUpdated)
                        .build();
                case FeedbackDefinition.BooleanFeedbackDefinition booleanDef -> booleanDef.toBuilder()
                        .name(nameUpdated)
                        .description(descriptionUpdated)
                        .build();
            };

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .put(Entity.json(feedbackDefinition1))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get();

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            var actualEntity = actualResponse.readEntity(FeedbackDefinition.class);

            assertThat(actualEntity.getName()).isEqualTo(nameUpdated);
            assertThat(actualEntity.getDescription()).isEqualTo(descriptionUpdated);
        }

    }

    @Nested
    @DisplayName("Delete:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class DeleteFeedbackDefinition {

        @Test
        @DisplayName("Success")
        void deleteById() {
            final UUID id = create(factory.manufacturePojo(FeedbackDefinition.CategoricalFeedbackDefinition.class),
                    API_KEY, TEST_WORKSPACE);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .delete()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .get()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(404);
                assertThat(actualResponse.hasEntity()).isTrue();
                assertThat(actualResponse.readEntity(ErrorMessage.class).errors())
                        .containsExactly("Feedback definition not found");
            }
        }

        @Test
        @DisplayName("delete batch feedback definitions")
        void deleteBatch() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var ids = PodamFactoryUtils.manufacturePojoList(factory,
                    FeedbackDefinition.CategoricalFeedbackDefinition.class).stream()
                    .map(feedbackDefinition -> create(feedbackDefinition, apiKey, workspaceName))
                    .toList();
            var idsToDelete = ids.subList(0, 3);
            var notDeletedIds = ids.subList(3, ids.size());

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("delete")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(new BatchDelete(new HashSet<>(idsToDelete))))) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("size", ids.size())
                    .queryParam("page", 1)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            var actualEntity = actualResponse.readEntity(FeedbackDefinitionPage.class);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
            assertThat(actualEntity.size()).isEqualTo(notDeletedIds.size());
            assertThat(actualEntity.content().stream().map(FeedbackDefinition::getId).toList())
                    .usingRecursiveComparison()
                    .ignoringCollectionOrder()
                    .isEqualTo(notDeletedIds);
        }

        @Test
        @DisplayName("when id found, then return no content")
        void deleteById__whenIdNotFound__thenReturnNoContent() {
            UUID id = UUID.randomUUID();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, API_KEY)
                    .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                    .delete()) {

                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
                assertThat(actualResponse.hasEntity()).isFalse();
            }
        }

        @Test
        @DisplayName("when trying to delete the user feedback, then return conflict")
        void deleteById__whenTryingToDeleteUserFeedback__thenReturnConflict() {

            var apiKey = UUID.randomUUID().toString();
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var feedback = CategoricalFeedbackDefinition.builder()
                    .name("User feedback")
                    .details(CategoricalFeedbackDetail.builder()
                            .categories(Map.of(" \uD83D\uDC4D", 1.0, " \uD83D\uDC4E", 0.0))
                            .build())
                    .build();

            UUID id = create(feedback, apiKey, workspaceName);

            try (var actualResponse = feedbackDefinitionResourceClient.deleteFeedbackDefinition(id, apiKey,
                    workspaceName)) {
                FeedbackScoreDefinitionAssertions.assertErrorResponse(actualResponse, FeedbackDefinitionService.MESSAGE,
                        HttpStatus.SC_CONFLICT);
            }
        }

        @Test
        @DisplayName("when trying to batch delete the user feedback, then return conflict")
        void deleteBatch__whenTryingToDeleteUserFeedback__thenReturnConflict() {
            var apiKey = UUID.randomUUID().toString();
            var workspaceName = UUID.randomUUID().toString();
            var workspaceId = UUID.randomUUID().toString();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var feedback = CategoricalFeedbackDefinition.builder()
                    .name("User feedback")
                    .details(CategoricalFeedbackDetail.builder()
                            .categories(Map.of(" \uD83D\uDC4D", 1.0, " \uD83D\uDC4E", 0.0))
                            .build())
                    .build();

            UUID id = create(feedback, apiKey, workspaceName);

            try (var actualResponse = feedbackDefinitionResourceClient.deleteBatchFeedbackDefinition(Set.of(id), apiKey,
                    workspaceName)) {
                FeedbackScoreDefinitionAssertions.assertErrorResponse(actualResponse, FeedbackDefinitionService.MESSAGE,
                        HttpStatus.SC_CONFLICT);
            }
        }

    }
}