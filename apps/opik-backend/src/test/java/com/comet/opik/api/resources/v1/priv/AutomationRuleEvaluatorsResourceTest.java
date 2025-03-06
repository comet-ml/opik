package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.AutomationRuleEvaluator;
import com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.AutomationRuleEvaluatorUpdate;
import com.comet.opik.api.AutomationRuleEvaluatorUpdateLlmAsJudge;
import com.comet.opik.api.AutomationRuleEvaluatorUpdateUserDefinedMetricPython;
import com.comet.opik.api.AutomationRuleEvaluatorUserDefinedMetricPython;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.AutomationRuleEvaluatorResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.llm.LlmModule;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.google.inject.AbstractModule;
import com.redis.testcontainers.RedisContainer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.http.HttpStatus;
import org.jdbi.v3.core.Jdbi;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.shaded.org.apache.commons.lang3.RandomStringUtils;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.LogItem.LogLevel;
import static com.comet.opik.api.LogItem.LogPage;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.FAKE_API_KEY_MESSAGE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.NO_API_KEY_RESPONSE;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Automation Rule Evaluators Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class AutomationRuleEvaluatorsResourceTest {

    private static final String URL_TEMPLATE = "%s/v1/private/automations/evaluators/";

    private static final String[] AUTOMATION_RULE_EVALUATOR_IGNORED_FIELDS = {"createdAt", "lastUpdatedAt",
            "projectName"};

    private static final String messageToTest = "Summary: {{summary}}\\nInstruction: {{instruction}}\\n\\n";
    private static final String testEvaluator = """
            {
              "model": { "name": "gpt-4o", "temperature": 0.3 },
              "messages": [
                { "role": "USER", "content": "%s" },
                { "role": "SYSTEM", "content": "You're a helpful AI, be cordial." }
              ],
              "variables": {
                  "summary": "input.questions.question1",
                  "instruction": "output.output",
                  "nonUsed": "input.questions.question2",
                  "toFail1": "metadata.nonexistent.path"
              },
              "schema": [
                { "name": "Relevance",           "type": "INTEGER",   "description": "Relevance of the summary" },
                { "name": "Conciseness",         "type": "DOUBLE",    "description": "Conciseness of the summary" },
                { "name": "Technical Accuracy",  "type": "BOOLEAN",   "description": "Technical accuracy of the summary" }
              ]
            }
            """
            .formatted(messageToTest)
            .trim();

    private static final String summaryStr = "What was the approach to experimenting with different data mixtures?";
    private static final String outputStr = "The study employed a systematic approach to experiment with varying data mixtures by manipulating the proportions and sources of datasets used for model training.";
    private static final String input = """
            {
                "questions": {
                    "question1": "%s",
                    "question2": "Whatever, we wont use it anyway"
                 },
                "pdf_url": "https://arxiv.org/pdf/2406.04744",
                "title": "CRAG -- Comprehensive RAG Benchmark"
            }
            """.formatted(summaryStr)
            .trim();

    private static final String output = """
            {
                "output": "%s"
            }
            """.formatted(outputStr)
            .trim();

    private static final String validAiMsgTxt = "{\"Relevance\":{\"score\":5,\"reason\":\"The summary directly addresses the approach taken in the study by mentioning the systematic experimentation with varying data mixtures and the manipulation of proportions and sources.\"},"
            +
            "\"Conciseness\":{\"score\":4,\"reason\":\"The summary is mostly concise but could be slightly more streamlined by removing redundant phrases.\"},"
            +
            "\"Technical Accuracy\":{\"score\":0,\"reason\":\"The summary accurately describes the experimental approach involving data mixtures, proportions, and sources, reflecting the technical details of the study.\"}}";

    private static final String USER = "user-" + RandomStringUtils.randomAlphanumeric(20);
    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.randomAlphanumeric(20);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer();
    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MYSQL, CLICKHOUSE).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(CLICKHOUSE, DATABASE_NAME);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(REDIS.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        .disableModules(List.of(LlmModule.class))
                        .modules(List.of(new AbstractModule() {
                            @Override
                            public void configure() {
                                bind(LlmProviderFactory.class)
                                        .toInstance(Mockito.mock(LlmProviderFactory.class, Mockito.RETURNS_DEEP_STUBS));
                            }
                        }))
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();
    private final TimeBasedEpochGenerator generator = Generators.timeBasedEpochGenerator();

    private String baseURI;
    private ClientSupport client;
    private AutomationRuleEvaluatorResourceClient evaluatorsResourceClient;
    private TraceResourceClient traceResourceClient;
    private ProjectResourceClient projectResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client, Jdbi jdbi) {

        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        this.baseURI = "http://localhost:%d".formatted(client.getPort());
        this.client = client;

        ClientSupportUtils.config(client);

        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);

        this.evaluatorsResourceClient = new AutomationRuleEvaluatorResourceClient(this.client, baseURI);
        this.traceResourceClient = new TraceResourceClient(this.client, baseURI);
        this.projectResourceClient = new ProjectResourceClient(this.client, baseURI, factory);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Nested
    @DisplayName("Api Key Authentication:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ApiKey {

        private static final String FAKE_API_KEY = UUID.randomUUID().toString();

        Stream<Arguments> credentials() {
            return Stream.of(
                    arguments(FAKE_API_KEY, UNAUTHORIZED_RESPONSE),
                    arguments("", NO_API_KEY_RESPONSE));
        }

        @BeforeEach
        void setUp() {
            wireMock.server().stubFor(
                    post(urlPathEqualTo("/opik/auth"))
                            .withHeader(HttpHeaders.AUTHORIZATION, equalTo(FAKE_API_KEY))
                            .withRequestBody(matchingJsonPath("$.workspaceName", matching(".+")))
                            .willReturn(WireMock.unauthorized().withHeader("Content-Type", "application/json")
                                    .withJsonBody(JsonUtils.readTree(
                                            new ReactServiceErrorResponse(FAKE_API_KEY_MESSAGE,
                                                    HttpStatus.SC_UNAUTHORIZED)))));
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void createWhenNotValidApiKey(String apiKey, ErrorMessage errorMessage) {
            var ruleEvaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class);
            try (var actualResponse = evaluatorsResourceClient.createEvaluator(
                    ruleEvaluator, WORKSPACE_NAME, apiKey, HttpStatus.SC_UNAUTHORIZED)) {
                assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(errorMessage);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void getWhenNotValidApiKey(String apiKey, ErrorMessage errorMessage) {
            var projectId = generator.generate();
            try (var actualResponse = evaluatorsResourceClient.findEvaluator(
                    projectId, null, null, null, WORKSPACE_NAME, apiKey, HttpStatus.SC_UNAUTHORIZED)) {
                assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(errorMessage);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void updateWhenNotValidApiKey(String apiKey, ErrorMessage errorMessage) {
            var id = generator.generate();
            var updatedEvaluator = factory.manufacturePojo(AutomationRuleEvaluatorUpdateLlmAsJudge.class);
            try (var actualResponse = evaluatorsResourceClient.updateEvaluator(
                    id, WORKSPACE_NAME, updatedEvaluator, apiKey, HttpStatus.SC_UNAUTHORIZED)) {
                assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(errorMessage);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void deleteWhenNotValidApiKey(String apiKey, ErrorMessage errorMessage) {
            var projectId = generator.generate();
            var batchDelete = factory.manufacturePojo(BatchDelete.class);
            try (var actualResponse = evaluatorsResourceClient.delete(
                    projectId, WORKSPACE_NAME, apiKey, batchDelete, HttpStatus.SC_UNAUTHORIZED)) {
                assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(errorMessage);
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        void getLogsWhenNotValidApiKey(String apikey, ErrorMessage errorMessage) {
            var id = generator.generate();
            try (var actualResponse = evaluatorsResourceClient.getLogs(
                    id, WORKSPACE_NAME, apikey, HttpStatus.SC_UNAUTHORIZED)) {
                assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(errorMessage);
            }
        }
    }

    @Nested
    @DisplayName("Session Token Authentication:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class SessionTokenCookie {

        private static final String SESSION_TOKEN = UUID.randomUUID().toString();
        private static final String FAKE_SESSION_TOKEN = UUID.randomUUID().toString();

        Stream<Arguments> credentials() {
            return Stream.of(
                    arguments(SESSION_TOKEN, true, "OK_" + UUID.randomUUID()),
                    arguments(FAKE_SESSION_TOKEN, false, UUID.randomUUID().toString()));
        }

        @BeforeEach
        void setUp() {
            wireMock.server().stubFor(
                    post(urlPathEqualTo("/opik/auth-session"))
                            .withCookie(SESSION_COOKIE, equalTo(SESSION_TOKEN))
                            .withRequestBody(matchingJsonPath("$.workspaceName", matching("OK_.+")))
                            .willReturn(okJson(AuthTestUtils.newWorkspaceAuthResponse(USER, WORKSPACE_ID))));

            wireMock.server().stubFor(
                    post(urlPathEqualTo("/opik/auth-session"))
                            .withCookie(SESSION_COOKIE, equalTo(FAKE_SESSION_TOKEN))
                            .withRequestBody(matchingJsonPath("$.workspaceName", matching(".+")))
                            .willReturn(WireMock.unauthorized().withHeader("Content-Type", "application/json")
                                    .withJsonBody(JsonUtils.readTree(
                                            new ReactServiceErrorResponse(FAKE_API_KEY_MESSAGE,
                                                    HttpStatus.SC_UNAUTHORIZED)))));
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("create evaluator definition: when api key is present, then return proper response")
        void createAutomationRuleEvaluator__whenSessionTokenIsPresent__thenReturnProperResponse(
                String sessionToken, boolean isAuthorized, String workspaceName) {

            var projectId = UUID.randomUUID();
            var ruleEvaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                    .id(null)
                    .projectId(projectId)
                    .build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(ruleEvaluator))) {

                if (isAuthorized) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(UNAUTHORIZED_RESPONSE);
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

            var projectId = projectResourceClient.createProject(UUID.randomUUID().toString(), API_KEY,
                    WORKSPACE_NAME);

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
                evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);
            });

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("size", samplesToCreate)
                    .queryParam("project_id", projectId)
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (isAuthorized) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
                    assertThat(actualResponse.hasEntity()).isTrue();

                    var actualEntity = actualResponse
                            .readEntity(AutomationRuleEvaluator.AutomationRuleEvaluatorPage.class);
                    assertThat(actualEntity.content()).hasSize(samplesToCreate);
                    assertThat(actualEntity.total()).isEqualTo(samplesToCreate);

                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("get evaluator by id: when api key is present, then return proper response")
        void getAutomationRuleEvaluatorById__whenSessionTokenIsPresent__thenReturnProperResponse(
                String sessionToken,
                boolean isAuthorized,
                String workspaceName) {

            var evaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                    .id(null)
                    .build();

            var id = evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .queryParam("project_id", evaluator.getProjectId())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (isAuthorized) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
                    assertThat(actualResponse.hasEntity()).isTrue();

                    var ruleEvaluator = actualResponse.readEntity(AutomationRuleEvaluator.class);
                    assertThat(ruleEvaluator.getId()).isEqualTo(id);
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("update evaluator: when api key is present, then return proper response")
        void updateAutomationRuleEvaluator__whenSessionTokenIsPresent__thenReturnProperResponse(
                String sessionToken, boolean isAuthorized, String workspaceName) {

            var evaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder().id(null)
                    .build();

            var id = evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);

            var updatedEvaluator = factory.manufacturePojo(AutomationRuleEvaluatorUpdateLlmAsJudge.class).toBuilder()
                    .projectId(evaluator.getProjectId()).build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path(id.toString())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .method(HttpMethod.PATCH, Entity.json(updatedEvaluator))) {

                if (isAuthorized) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(UNAUTHORIZED_RESPONSE);
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

            var id = evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);
            var deleteMethod = BatchDelete.builder().ids(Collections.singleton(id)).build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("delete")
                    .queryParam("project_id", evaluator.getProjectId())
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(deleteMethod))) {

                if (isAuthorized) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(UNAUTHORIZED_RESPONSE);
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

            var projectId = projectResourceClient.createProject(UUID.randomUUID().toString(), API_KEY,
                    WORKSPACE_NAME);

            var evaluator1 = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                    .projectId(projectId).build();
            var evalId1 = evaluatorsResourceClient.createEvaluator(evaluator1, WORKSPACE_NAME, API_KEY);

            var evaluator2 = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                    .projectId(projectId).build();
            var evalId2 = evaluatorsResourceClient.createEvaluator(evaluator2, WORKSPACE_NAME, API_KEY);

            var evaluator3 = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                    .projectId(projectId).build();
            evaluatorsResourceClient.createEvaluator(evaluator3, WORKSPACE_NAME, API_KEY);

            var evalIds1and2 = Set.of(evalId1, evalId2);
            var deleteMethod = BatchDelete.builder().ids(evalIds1and2).build();

            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .path("delete")
                    .queryParam("project_id", projectId)
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(deleteMethod))) {

                if (isAuthorized) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }

            // we shall see a single evaluators for the project now
            try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                    .queryParam("project_id", projectId)
                    .request()
                    .cookie(SESSION_COOKIE, sessionToken)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .get()) {

                if (isAuthorized) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
                    assertThat(actualResponse.hasEntity()).isTrue();

                    var actualEntity = actualResponse
                            .readEntity(AutomationRuleEvaluator.AutomationRuleEvaluatorPage.class);
                    assertThat(actualEntity.content()).hasSize(1);
                    assertThat(actualEntity.total()).isEqualTo(1);

                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("get logs per rule evaluators: when api key is present, then return proper response")
        void getLogsPerRuleEvaluators__whenSessionTokenIsPresent__thenReturnProperResponse(
                String sessionToken,
                boolean isAuthorized,
                String workspaceName,
                LlmProviderFactory llmProviderFactory) throws JsonProcessingException {

            ChatResponse chatResponse = ChatResponse.builder()
                    .aiMessage(AiMessage.from(validAiMsgTxt))
                    .build();

            when(llmProviderFactory.getLanguageModel(anyString(), any())
                    .chat(any()))
                    .thenAnswer(invocationOnMock -> chatResponse);

            String projectName = UUID.randomUUID().toString();

            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            var evaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                    .id(null)
                    .code(OBJECT_MAPPER.readValue(testEvaluator,
                            AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode.class))
                    .samplingRate(1f)
                    .projectId(projectId)
                    .build();

            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .input(OBJECT_MAPPER.readTree(input))
                    .output(OBJECT_MAPPER.readTree(output))
                    .build();

            var id = evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);

            Instant startTime = Instant.now();
            traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);

            Awaitility.await().untilAsserted(() -> {

                try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                        .path(id.toString())
                        .path("logs")
                        .request()
                        .cookie(SESSION_COOKIE, sessionToken)
                        .accept(MediaType.APPLICATION_JSON_TYPE)
                        .header(WORKSPACE_HEADER, workspaceName)
                        .get()) {

                    if (isAuthorized) {
                        assertLogResponse(actualResponse, startTime, id, trace);
                    } else {
                        assertThat(actualResponse.getStatusInfo().getStatusCode())
                                .isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                        assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(UNAUTHORIZED_RESPONSE);
                    }
                }
            });
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CreateAndGetEvaluator {

        Stream<Arguments> createAndGet() {
            return Stream.of(
                    arguments(factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class), true),
                    arguments(factory.manufacturePojo(AutomationRuleEvaluatorUserDefinedMetricPython.class), false));
        }

        @ParameterizedTest
        @MethodSource
        void createAndGet(AutomationRuleEvaluator<?> automationRuleEvaluator, boolean withProjectId) {
            var id = evaluatorsResourceClient.createEvaluator(automationRuleEvaluator, WORKSPACE_NAME, API_KEY);
            var expectedAutomationRuleEvaluator = automationRuleEvaluator.toBuilder()
                    .id(id)
                    .createdBy(USER)
                    .lastUpdatedBy(USER)
                    .build();

            try (var actualResponse = evaluatorsResourceClient.getEvaluator(
                    id,
                    withProjectId ? expectedAutomationRuleEvaluator.getProjectId() : null,
                    WORKSPACE_NAME,
                    API_KEY,
                    HttpStatus.SC_OK)) {

                var actualAutomationRuleEvaluator = actualResponse.readEntity(AutomationRuleEvaluator.class);
                assertThat(actualAutomationRuleEvaluator)
                        .usingRecursiveComparison()
                        .ignoringFields(AUTOMATION_RULE_EVALUATOR_IGNORED_FIELDS)
                        .isEqualTo(expectedAutomationRuleEvaluator);
                assertIgnoredFields(actualAutomationRuleEvaluator, expectedAutomationRuleEvaluator);
            }
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class FindEvaluator {

        Stream<Class<? extends AutomationRuleEvaluator<?>>> find() {
            return Stream.of(
                    AutomationRuleEvaluatorLlmAsJudge.class,
                    AutomationRuleEvaluatorUserDefinedMetricPython.class);
        }

        @ParameterizedTest
        @MethodSource
        void find(Class<? extends AutomationRuleEvaluator<?>> evaluatorClass) {
            String projectName = factory.manufacturePojo(String.class);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
            var unexpectedProjectId = generator.generate();

            var evaluators = PodamFactoryUtils.manufacturePojoList(factory, evaluatorClass)
                    .stream()
                    .map(evaluator -> evaluator.toBuilder()
                            .projectId(projectId)
                            .build())
                    .map(evaluator -> {
                        var id = evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);
                        return evaluator.toBuilder()
                                .id(id)
                                .createdBy(USER)
                                .lastUpdatedBy(USER)
                                .projectName(projectName)
                                .build();
                    }).toList();

            var unexpectedEvaluators = PodamFactoryUtils.manufacturePojoList(factory, evaluatorClass)
                    .stream()
                    .map(evaluator -> evaluator.toBuilder()
                            .projectId(unexpectedProjectId)
                            .build())
                    .map(evaluator -> {
                        var id = evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);
                        return evaluator.toBuilder()
                                .id(id)
                                .createdBy(USER)
                                .lastUpdatedBy(USER)
                                .build();
                    }).toList();

            var pageSize = evaluators.size() / 2 + 1;
            var expectedEvaluators1 = evaluators.reversed().subList(0, pageSize);
            var expectedEvaluators2 = evaluators.reversed().subList(pageSize, evaluators.size());

            findAndAssertPage(
                    projectId,
                    null,
                    1,
                    pageSize,
                    evaluators.size(),
                    expectedEvaluators1,
                    unexpectedEvaluators);
            findAndAssertPage(
                    projectId,
                    null,
                    2,
                    pageSize,
                    evaluators.size(),
                    expectedEvaluators2,
                    unexpectedEvaluators);
        }

        Stream<Arguments> findByName() {
            return Stream.of(
                    arguments(AutomationRuleEvaluatorLlmAsJudge.class, true),
                    arguments(AutomationRuleEvaluatorUserDefinedMetricPython.class, false));
        }

        @ParameterizedTest
        @MethodSource
        void findByName(Class<? extends AutomationRuleEvaluator<?>> evaluatorClass, boolean withProjectId) {
            String projectName = factory.manufacturePojo(String.class);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            var evaluators = PodamFactoryUtils.manufacturePojoList(factory, evaluatorClass)
                    .stream()
                    .map(evaluator -> evaluator.toBuilder()
                            .projectId(projectId)
                            .build())
                    .map(evaluator -> {
                        var id = evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);
                        return evaluator.toBuilder()
                                .id(id)
                                .createdBy(USER)
                                .lastUpdatedBy(USER)
                                .projectName(projectName)
                                .build();
                    }).toList();

            var expectedEvaluators = List.of(evaluators.getFirst());
            var unexpectedEvaluators = evaluators.subList(1, evaluators.size());

            findAndAssertPage(
                    withProjectId ? projectId : null,
                    evaluators.getFirst().getName().substring(2, evaluators.getFirst().getName().length() - 3)
                            .toUpperCase(),
                    1,
                    evaluators.size(),
                    1,
                    expectedEvaluators,
                    unexpectedEvaluators);
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class UpdateEvaluator {

        Stream<Arguments> update() {
            return Stream.of(
                    arguments(
                            factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class),
                            factory.manufacturePojo(AutomationRuleEvaluatorUpdateLlmAsJudge.class)),
                    arguments(
                            factory.manufacturePojo(AutomationRuleEvaluatorUserDefinedMetricPython.class),
                            factory.manufacturePojo(AutomationRuleEvaluatorUpdateUserDefinedMetricPython.class)));
        }

        @ParameterizedTest
        @MethodSource
        void update(
                AutomationRuleEvaluator<Object> automationRuleEvaluator,
                AutomationRuleEvaluatorUpdate<Object> automationRuleEvaluatorUpdate) {
            var id = evaluatorsResourceClient.createEvaluator(automationRuleEvaluator, WORKSPACE_NAME, API_KEY);

            var updatedEvaluator = automationRuleEvaluatorUpdate.toBuilder()
                    .projectId(automationRuleEvaluator.getProjectId())
                    .build();
            try (var actualResponse = evaluatorsResourceClient.updateEvaluator(
                    id, WORKSPACE_NAME, updatedEvaluator, API_KEY, HttpStatus.SC_NO_CONTENT)) {
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            var expectedAutomationRuleEvaluator = automationRuleEvaluator.toBuilder()
                    .id(id)
                    .createdBy(USER)
                    .lastUpdatedBy(USER)
                    .name(updatedEvaluator.getName())
                    .samplingRate(updatedEvaluator.getSamplingRate())
                    .code(updatedEvaluator.getCode())
                    .build();
            try (var actualResponse = evaluatorsResourceClient.getEvaluator(
                    id, null, WORKSPACE_NAME, API_KEY, HttpStatus.SC_OK)) {
                var actualAutomationRuleEvaluator = actualResponse.readEntity(AutomationRuleEvaluator.class);
                assertThat(actualAutomationRuleEvaluator)
                        .usingRecursiveComparison()
                        .ignoringFields(AUTOMATION_RULE_EVALUATOR_IGNORED_FIELDS)
                        .isEqualTo(expectedAutomationRuleEvaluator);
                assertIgnoredFields(actualAutomationRuleEvaluator, expectedAutomationRuleEvaluator);
            }
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class DeleteEvaluator {

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void delete(boolean includeProjectId) {
            var projectId = projectResourceClient.createProject(UUID.randomUUID().toString(), API_KEY,
                    WORKSPACE_NAME);
            var id1 = createGetAndAssertId(AutomationRuleEvaluatorLlmAsJudge.class, projectId);
            var id2 = createGetAndAssertId(AutomationRuleEvaluatorUserDefinedMetricPython.class, projectId);
            var id3 = createGetAndAssertId(AutomationRuleEvaluatorLlmAsJudge.class, projectId);
            var id4 = createGetAndAssertId(AutomationRuleEvaluatorUserDefinedMetricPython.class, projectId);

            var batchDelete = BatchDelete.builder().ids(Set.of(id1, id2)).build();
            try (var actualResponse = evaluatorsResourceClient.delete(
                    includeProjectId ? projectId : null, WORKSPACE_NAME, API_KEY, batchDelete,
                    HttpStatus.SC_NO_CONTENT)) {
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            try (var actualResponse = evaluatorsResourceClient.getEvaluator(
                    id1, null, WORKSPACE_NAME, API_KEY, HttpStatus.SC_NOT_FOUND)) {
                var errorMessage = actualResponse.readEntity(com.comet.opik.api.error.ErrorMessage.class);
                assertThat(errorMessage)
                        .isEqualTo((new com.comet.opik.api.error.ErrorMessage(
                                List.of("AutomationRuleEvaluator not found"))));
            }

            try (var actualResponse = evaluatorsResourceClient.findEvaluator(
                    projectId, null, 1, 10, WORKSPACE_NAME, API_KEY, HttpStatus.SC_OK)) {
                assertThat(actualResponse.hasEntity()).isTrue();

                var actualEntity = actualResponse.readEntity(AutomationRuleEvaluator.AutomationRuleEvaluatorPage.class);
                assertThat(actualEntity.page()).isEqualTo(1);
                assertThat(actualEntity.size()).isEqualTo(2);
                assertThat(actualEntity.total()).isEqualTo(2);

                var actualAutomationRuleEvaluators = actualEntity.content();
                assertThat(actualAutomationRuleEvaluators).hasSize(2);
                var actualIds = actualAutomationRuleEvaluators.stream()
                        .map(AutomationRuleEvaluator::getId)
                        .collect(Collectors.toSet());
                assertThat(actualIds).containsExactlyInAnyOrder(id3, id4);
            }
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetLogs {

        @Test
        void getLogs(LlmProviderFactory llmProviderFactory) throws JsonProcessingException {
            var chatResponse = ChatResponse.builder()
                    .aiMessage(AiMessage.from(validAiMsgTxt))
                    .build();

            when(llmProviderFactory.getLanguageModel(anyString(), any())
                    .chat(any()))
                    .thenAnswer(invocationOnMock -> chatResponse);

            var projectName = "project-" + RandomStringUtils.randomAlphanumeric(36);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            var evaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                    .id(null)
                    .code(OBJECT_MAPPER.readValue(testEvaluator,
                            AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode.class))
                    .samplingRate(1f)
                    .projectId(projectId)
                    .build();

            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .input(OBJECT_MAPPER.readTree(input))
                    .output(OBJECT_MAPPER.readTree(output))
                    .build();

            var id = evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);

            var startTime = Instant.now();
            traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);

            Awaitility.await().untilAsserted(() -> {

                try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI))
                        .path(id.toString())
                        .path("logs")
                        .request()
                        .accept(MediaType.APPLICATION_JSON_TYPE)
                        .header(HttpHeaders.AUTHORIZATION, API_KEY)
                        .header(WORKSPACE_HEADER, WORKSPACE_NAME)
                        .get()) {

                    assertLogResponse(actualResponse, startTime, id, trace);
                }
            });
        }
    }

    private UUID createGetAndAssertId(Class<? extends AutomationRuleEvaluator<?>> evaluatorClass, UUID projectId) {
        var automationRuleEvaluator = factory.manufacturePojo(evaluatorClass).toBuilder().projectId(projectId).build();
        var id = evaluatorsResourceClient.createEvaluator(automationRuleEvaluator, WORKSPACE_NAME, API_KEY);
        try (var actualResponse = evaluatorsResourceClient.getEvaluator(
                id, null, WORKSPACE_NAME, API_KEY, HttpStatus.SC_OK)) {
            var actualAutomationRuleEvaluator = actualResponse.readEntity(AutomationRuleEvaluator.class);
            assertThat(actualAutomationRuleEvaluator.getId()).isEqualTo(id);
            return id;
        }
    }

    private void findAndAssertPage(
            UUID projectId,
            String evaluatorName,
            int page,
            int size,
            int expectedTotal,
            List<? extends AutomationRuleEvaluator<?>> expectedAutomationRuleEvaluators,
            List<? extends AutomationRuleEvaluator<?>> unexpectedAutomationRuleEvaluators) {

        try (var actualResponse = evaluatorsResourceClient.findEvaluator(
                projectId, evaluatorName, page, size, WORKSPACE_NAME, API_KEY, HttpStatus.SC_OK)) {
            assertThat(actualResponse.hasEntity()).isTrue();

            var actualEntity = actualResponse.readEntity(AutomationRuleEvaluator.AutomationRuleEvaluatorPage.class);
            assertThat(actualEntity.page()).isEqualTo(page);
            assertThat(actualEntity.size()).isEqualTo(expectedAutomationRuleEvaluators.size());
            assertThat(actualEntity.total()).isEqualTo(expectedTotal);

            var actualAutomationRuleEvaluators = actualEntity.content();
            assertThat(actualAutomationRuleEvaluators).hasSize(expectedAutomationRuleEvaluators.size());
            assertThat(actualAutomationRuleEvaluators)
                    .usingRecursiveFieldByFieldElementComparatorIgnoringFields(
                            AUTOMATION_RULE_EVALUATOR_IGNORED_FIELDS)
                    .containsExactlyElementsOf(expectedAutomationRuleEvaluators);

            for (int i = 0; i < actualAutomationRuleEvaluators.size(); i++) {
                var actualAutomationRuleEvaluator = actualAutomationRuleEvaluators.get(i);
                var expectedAutomationRuleEvaluator = expectedAutomationRuleEvaluators.get(i);
                assertIgnoredFields(actualAutomationRuleEvaluator, expectedAutomationRuleEvaluator);
                assertThat(actualAutomationRuleEvaluator.getProjectName())
                        .isEqualTo(expectedAutomationRuleEvaluator.getProjectName());
            }

            assertThat(actualAutomationRuleEvaluators)
                    .usingRecursiveFieldByFieldElementComparatorIgnoringFields(
                            AUTOMATION_RULE_EVALUATOR_IGNORED_FIELDS)
                    .doesNotContainAnyElementsOf(unexpectedAutomationRuleEvaluators);
        }
    }

    private void assertIgnoredFields(AutomationRuleEvaluator<?> actualRuleEvaluator,
            AutomationRuleEvaluator<?> expectedRuleEvaluator) {
        assertThat(actualRuleEvaluator.getCreatedAt()).isAfter(expectedRuleEvaluator.getCreatedAt());
        assertThat(actualRuleEvaluator.getLastUpdatedAt()).isAfter(expectedRuleEvaluator.getLastUpdatedAt());
    }

    private void assertLogResponse(Response actualResponse, Instant startTime, UUID id, Trace trace) {
        assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
        assertThat(actualResponse.hasEntity()).isTrue();

        var actualEntity = actualResponse.readEntity(LogPage.class);

        assertThat(actualEntity.content()).hasSize(4);
        assertThat(actualEntity.total()).isEqualTo(4);
        assertThat(actualEntity.size()).isEqualTo(4);
        assertThat(actualEntity.page()).isEqualTo(1);

        assertThat(actualEntity.content())
                .allSatisfy(log -> {
                    assertThat(log.timestamp()).isBetween(startTime, Instant.now());
                    assertThat(log.ruleId()).isEqualTo(id);
                    assertThat(log.markers()).isEqualTo(Map.of("trace_id", trace.id().toString()));
                    assertThat(log.level()).isEqualTo(LogLevel.INFO);
                });

        assertThat(actualEntity.content())
                .anyMatch(log -> log.message()
                        .matches("Scores for traceId '.*' stored successfully:\\n\\n.*"));

        assertThat(actualEntity.content())
                .anyMatch(log -> log.message().matches("Received response for traceId '.*':\\n\\n.*"));

        assertThat(actualEntity.content())
                .anyMatch(log -> log.message().matches(
                        "(?s)Sending traceId '([^']*)' to LLM using the following input:\\n\\n.*"));

        assertThat(actualEntity.content())
                .anyMatch(log -> log.message().matches("Evaluating traceId '.*' sampled by rule '.*'"));
    }
}
