package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.AutomationRuleEvaluator;
import com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.AutomationRuleEvaluatorUpdate;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.AutomationRuleEvaluatorResourceClient;
import com.comet.opik.podam.PodamFactoryUtils;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.TestHttpClientUtils.UNAUTHORIZED_RESPONSE;
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
@DisplayName("Automation Rule Evaluators Resource Test")
class AutomationRuleEvaluatorsResourceTest {

    private static final String URL_TEMPLATE = "%s/v1/private/automations/projects/%s/evaluators/";

    private static final String USER = UUID.randomUUID().toString();
    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME = "workspace-" + UUID.randomUUID();

    private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();

    private static final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();

    @RegisterExtension
    private static final TestDropwizardAppExtension app;

    private static final WireMockUtils.WireMockRuntime wireMock;

    static {
        Startables.deepStart(REDIS, MYSQL).join();

        wireMock = WireMockUtils.startWireMock();

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(MYSQL.getJdbcUrl(), null,
                wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;
    private AutomationRuleEvaluatorResourceClient evaluatorsResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client, Jdbi jdbi) {

        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        this.baseURI = "http://localhost:%d".formatted(client.getPort());
        this.client = client;

        ClientSupportUtils.config(client);

        mockTargetWorkspace(API_KEY, WORKSPACE_NAME, WORKSPACE_ID);

        this.evaluatorsResourceClient = new AutomationRuleEvaluatorResourceClient(this.client, baseURI);
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
        @DisplayName("create evaluator definition: when api key is present, then return proper response")
        void createAutomationRuleEvaluator__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey,
                boolean isAuthorized) {

            var ruleEvaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder().id(null)
                    .build();

            mockTargetWorkspace(okApikey, WORKSPACE_NAME, WORKSPACE_ID);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI, ruleEvaluator.getProjectId()))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, WORKSPACE_NAME)
                    .post(Entity.json(ruleEvaluator))) {

                if (isAuthorized) {
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
        @DisplayName("get evaluators by project id: when api key is present, then return proper response")
        void getProjectAutomationRuleEvaluators__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey,
                boolean isAuthorized) {

            final String workspaceName = "workspace-" + UUID.randomUUID();
            final String workspaceId = UUID.randomUUID().toString();
            final UUID projectId = UUID.randomUUID();

            mockTargetWorkspace(okApikey, workspaceName, workspaceId);

            int samplesToCreate = 15;

            IntStream.range(0, samplesToCreate).forEach(i -> {
                var evaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class)
                        .toBuilder().id(null).projectId(null).build();

                evaluatorsResourceClient.createEvaluator(evaluator, projectId, workspaceName, okApikey);
            });

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI, projectId))
                    .queryParam("size", samplesToCreate)
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (isAuthorized) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();

                    var actualEntity = actualResponse
                            .readEntity(AutomationRuleEvaluator.AutomationRuleEvaluatorPage.class);
                    assertThat(actualEntity.content()).hasSize(samplesToCreate);
                    assertThat(actualEntity.total()).isEqualTo(samplesToCreate);

                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @Test
        @DisplayName("search project evaluators: when searching by name, then return evaluators")
        void find__whenSearchingByName__thenReturnEvaluators() {

            var workspaceName = "workspace-" + UUID.randomUUID();
            var workspaceId = UUID.randomUUID().toString();
            var projectId = UUID.randomUUID();
            var apiKey = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);
            var name = "Evaluator Name: " + UUID.randomUUID();

            var evaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class)
                    .toBuilder().id(null)
                    .projectId(null)
                    .name(name)
                    .build();

            evaluatorsResourceClient.createEvaluator(evaluator, projectId, workspaceName, apiKey);

            var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI, projectId))
                    .queryParam("name", "aluator")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get();

            var actualEntity = actualResponse.readEntity(AutomationRuleEvaluator.AutomationRuleEvaluatorPage.class);

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            assertThat(actualEntity.page()).isEqualTo(1);
            assertThat(actualEntity.size()).isEqualTo(1);
            assertThat(actualEntity.total()).isEqualTo(1);

            List<AutomationRuleEvaluatorLlmAsJudge> content = actualEntity.content();
            assertThat(content.stream().map(AutomationRuleEvaluator::getName).toList()).contains(name);
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("get evaluator by id: when api key is present, then return proper response")
        void getAutomationRuleEvaluatorById__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey,
                boolean isAuthorized) {

            var evaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class)
                    .toBuilder().id(null).projectId(null).build();

            String workspaceName = "workspace-" + UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            UUID projectId = UUID.randomUUID();

            mockTargetWorkspace(okApikey, workspaceName, workspaceId);

            UUID id = evaluatorsResourceClient.createEvaluator(evaluator, projectId, workspaceName, okApikey);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI, projectId))
                    .path(id.toString())
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (isAuthorized) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();

                    var ruleEvaluator = actualResponse.readEntity(AutomationRuleEvaluator.class);
                    assertThat(ruleEvaluator.getId()).isEqualTo(id);
                    assertThat(ruleEvaluator.getProjectId()).isEqualTo(projectId);
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("update evaluator: when api key is present, then return proper response")
        void updateAutomationRuleEvaluator__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey,
                boolean isAuthorized) {

            var evaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder().id(null)
                    .build();

            String workspaceName = "workspace-" + UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            UUID projectId = UUID.randomUUID();

            mockTargetWorkspace(okApikey, workspaceName, workspaceId);

            UUID id = evaluatorsResourceClient.createEvaluator(evaluator, projectId, workspaceName, okApikey);

            var updatedEvaluator = factory.manufacturePojo(AutomationRuleEvaluatorUpdate.class);

            evaluatorsResourceClient.updateEvaluator(id, projectId, workspaceName, updatedEvaluator,
                    apiKey, isAuthorized);
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("delete evaluator by id: when api key is present, then return proper response")
        void deleteAutomationRuleEvaluator__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey,
                boolean isAuthorized) {

            var evaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder().id(null)
                    .build();;

            String workspaceName = "workspace-" + UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            UUID projectId = UUID.randomUUID();

            mockTargetWorkspace(okApikey, workspaceName, workspaceId);

            UUID id = evaluatorsResourceClient.createEvaluator(evaluator, projectId, workspaceName, okApikey);

            var deleteMethod = BatchDelete.builder().ids(Collections.singleton(id)).build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI, projectId))
                    .path("delete")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(deleteMethod))) {

                if (isAuthorized) {
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
        @DisplayName("batch delete evaluators by id: when api key is present, then return proper response")
        void deleteProjectAutomationRuleEvaluators__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey,
                boolean isAuthorized) {
            var projectId = UUID.randomUUID();
            var workspaceName = "workspace-" + UUID.randomUUID();
            var workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, workspaceId);

            var evaluator1 = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class)
                    .toBuilder().id(null).build();
            var evalId1 = evaluatorsResourceClient.createEvaluator(evaluator1, projectId, workspaceName, okApikey);

            var evaluator2 = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class)
                    .toBuilder().id(null).build();
            var evalId2 = evaluatorsResourceClient.createEvaluator(evaluator2, projectId, workspaceName, okApikey);

            var evaluator3 = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class)
                    .toBuilder().id(null).build();
            evaluatorsResourceClient.createEvaluator(evaluator3, projectId, workspaceName, okApikey);

            var evalIds1and2 = Set.of(evalId1, evalId2);
            var deleteMethod = BatchDelete.builder().ids(evalIds1and2).build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI, projectId))
                    .path("delete")
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(deleteMethod))) {

                if (isAuthorized) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }

            // we shall see a single evaluators for the project now
            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI, projectId))
                    .request()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (isAuthorized) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();

                    var actualEntity = actualResponse
                            .readEntity(AutomationRuleEvaluator.AutomationRuleEvaluatorPage.class);
                    assertThat(actualEntity.content()).hasSize(1);
                    assertThat(actualEntity.total()).isEqualTo(1);

                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
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
                            .willReturn(WireMock.unauthorized()));
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("create evaluator definition: when api key is present, then return proper response")
        void createAutomationRuleEvaluator__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                boolean isAuthorized,
                String workspaceName) {

            var projectId = UUID.randomUUID();
            var ruleEvaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder().id(null)
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI, projectId))
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(ruleEvaluator))) {

                if (isAuthorized) {
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
        @DisplayName("get evaluators by project id: when api key is present, then return proper response")
        void getProjectAutomationRuleEvaluators__whenSessionTokenIsPresent__thenReturnProperResponse(
                String sessionToken,
                boolean isAuthorized,
                String workspaceName) {

            var projectId = UUID.randomUUID();

            int samplesToCreate = 15;
            var newWorkspaceName = "workspace-" + UUID.randomUUID();
            var newWorkspaceId = UUID.randomUUID().toString();

            wireMock.server().stubFor(
                    post(urlPathEqualTo("/opik/auth-session"))
                            .withCookie(SESSION_COOKIE, equalTo(sessionToken))
                            .withRequestBody(matchingJsonPath("$.workspaceName", equalTo(newWorkspaceName)))
                            .willReturn(okJson(AuthTestUtils.newWorkspaceAuthResponse(USER, newWorkspaceId))));

            IntStream.range(0, samplesToCreate).forEach(i -> {
                var evaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class)
                        .toBuilder().id(null).projectId(projectId).build();
                evaluatorsResourceClient.createEvaluator(evaluator, projectId, WORKSPACE_NAME, API_KEY);
            });

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI, projectId))
                    .queryParam("size", samplesToCreate)
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (isAuthorized) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();

                    var actualEntity = actualResponse
                            .readEntity(AutomationRuleEvaluator.AutomationRuleEvaluatorPage.class);
                    assertThat(actualEntity.content()).hasSize(samplesToCreate);
                    assertThat(actualEntity.total()).isEqualTo(samplesToCreate);

                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("get evaluator by id: when api key is present, then return proper response")
        void getAutomationRuleEvaluatorById__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                boolean isAuthorized,
                String workspaceName) {

            var evaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder().id(null)
                    .build();

            var projectId = UUID.randomUUID();
            UUID id = evaluatorsResourceClient.createEvaluator(evaluator, projectId, WORKSPACE_NAME, API_KEY);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI, projectId))
                    .path(id.toString())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (isAuthorized) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();

                    var ruleEvaluator = actualResponse.readEntity(AutomationRuleEvaluator.class);
                    assertThat(ruleEvaluator.getId()).isEqualTo(id);
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("update evaluator: when api key is present, then return proper response")
        void updateAutomationRuleEvaluator__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                boolean isAuthorized,
                String workspaceName) {

            var evaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder().id(null)
                    .build();

            UUID projectId = UUID.randomUUID();
            UUID id = evaluatorsResourceClient.createEvaluator(evaluator, projectId, WORKSPACE_NAME, API_KEY);

            var updatedEvaluator = factory.manufacturePojo(AutomationRuleEvaluatorUpdate.class);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI, projectId))
                    .path(id.toString())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .method(HttpMethod.PATCH, Entity.json(updatedEvaluator))) {

                if (isAuthorized) {
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
        @DisplayName("delete evaluator by id: when api key is present, then return proper response")
        void deleteAutomationRuleEvaluator__whenSessionTokenIsPresent__thenReturnProperResponse(String sessionToken,
                boolean isAuthorized,
                String workspaceName) {

            var evaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder().id(null)
                    .build();

            var projectId = UUID.randomUUID();
            var id = evaluatorsResourceClient.createEvaluator(evaluator, projectId, WORKSPACE_NAME, API_KEY);
            var deleteMethod = BatchDelete.builder().ids(Collections.singleton(id)).build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI, projectId))
                    .path("delete")
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(deleteMethod))) {

                if (isAuthorized) {
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
        @DisplayName("batch delete evaluators by id: when api key is present, then return proper response")
        void deleteProjectAutomationRuleEvaluators__whenSessionTokenIsPresent__thenReturnProperResponse(
                String sessionToken,
                boolean isAuthorized,
                String workspaceName) {

            var projectId = UUID.randomUUID();

            var evaluator1 = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                    .projectId(projectId).build();
            var evalId1 = evaluatorsResourceClient.createEvaluator(evaluator1, projectId, WORKSPACE_NAME, API_KEY);

            var evaluator2 = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                    .projectId(projectId).build();
            var evalId2 = evaluatorsResourceClient.createEvaluator(evaluator2, projectId, WORKSPACE_NAME, API_KEY);

            var evaluator3 = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                    .projectId(projectId).build();
            evaluatorsResourceClient.createEvaluator(evaluator3, projectId, WORKSPACE_NAME, API_KEY);

            var evalIds1and2 = Set.of(evalId1, evalId2);
            var deleteMethod = BatchDelete.builder().ids(evalIds1and2).build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI, projectId))
                    .path("delete")
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(deleteMethod))) {

                if (isAuthorized) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }

            // we shall see a single evaluators for the project now
            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI, projectId))
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (isAuthorized) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
                    assertThat(actualResponse.hasEntity()).isTrue();

                    var actualEntity = actualResponse
                            .readEntity(AutomationRuleEvaluator.AutomationRuleEvaluatorPage.class);
                    assertThat(actualEntity.content()).hasSize(1);
                    assertThat(actualEntity.total()).isEqualTo(1);

                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }
    }

}
