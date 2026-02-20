package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.evaluators.AutomationRuleEvaluator;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUpdate;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUpdateLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUpdateSpanLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUpdateSpanUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUpdateTraceThreadLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUpdateTraceThreadUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUpdateUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUserDefinedMetricPython;
import com.comet.opik.api.evaluators.ProjectReference;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.SpanField;
import com.comet.opik.api.filter.SpanFilter;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.filter.TraceThreadField;
import com.comet.opik.api.filter.TraceThreadFilter;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.AutomationRuleEvaluatorResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.evaluators.python.PythonEvaluatorRequest;
import com.comet.opik.domain.evaluators.python.PythonEvaluatorResponse;
import com.comet.opik.domain.evaluators.python.TraceThreadPythonEvaluatorRequest;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.llm.LlmModule;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.google.inject.AbstractModule;
import com.redis.testcontainers.RedisContainer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpStatus;
import org.awaitility.Awaitility;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.LogItem.LogLevel;
import static com.comet.opik.api.LogItem.LogPage;
import static com.comet.opik.api.resources.utils.AutomationRuleEvaluatorTestUtils.getPrimaryProjectId;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.FAKE_API_KEY_MESSAGE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.NO_API_KEY_RESPONSE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.UNAUTHORIZED_RESPONSE;
import static com.comet.opik.infrastructure.auth.RequestContext.SESSION_COOKIE;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
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

    private static final String[] AUTOMATION_RULE_EVALUATOR_IGNORED_FIELDS = {
            "createdAt", "lastUpdatedAt", "projectId", "projectName", "projects", "projectIds"};

    private static final String MESSAGE_TO_TEST = "Summary: {{summary}}\\nInstruction: {{instruction}}\\n\\n";
    private static final String LLM_AS_A_JUDGE_EVALUATOR = """
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
            .formatted(MESSAGE_TO_TEST)
            .trim();

    private static final String USER_DEFINED_METRIC = """
            from typing import Any

            from opik.evaluation.metrics import base_metric, score_result


            class UserDefinedEquals(base_metric.BaseMetric):
                def __init__(
                    self,
                    name: str = "user_defined_equals_metric",
                ):
                    super().__init__(
                        name=name,
                        track=False,
                    )

                def score(
                    self, output: str, reference: str, **ignored_kwargs: Any
                ) -> score_result.ScoreResult:
                    value = 1.0 if output == reference else 0.0
                    return score_result.ScoreResult(value=value, name=self.name)
            """;

    private static final String TRACE_THREAD_USER_DEFINED_METRIC = """
            from typing import Union, List, Any

            from opik.evaluation.metrics import base_metric, score_result
            from opik.evaluation.metrics.types import Conversation

            class CustomConversationThreadMetric(base_metric.BaseMetric):
                ""Abstract base class for all conversation thread metrics.""

                def __init__(
                    self,
                    name: str = "custom_conversation_thread_metric",
                ):
                    super().__init__(
                        name=name,
                        track=False,
                    )

                # user -> input
                # assistant -> output
                def score(
                    self, conversation: Conversation, **ignored_kwargs: Any
                ) -> score_result.ScoreResult:
                    has_assistant = any(m["role"] == "assistant" for m in conversation)
                    value = 1.0 if has_assistant else 0.0
                    return score_result.ScoreResult(value=value, name=self.name)
            """;

    private static final String SUMMARY_STR = "What was the approach to experimenting with different data mixtures?";
    private static final String OUTPUT_STR = "The study employed a systematic approach to experiment with varying data mixtures by manipulating the proportions and sources of datasets used for model training.";
    private static final String INPUT = """
            {
                "questions": {
                    "question1": "%s",
                    "question2": "Whatever, we wont use it anyway"
                 },
                "pdf_url": "https://arxiv.org/pdf/2406.04744",
                "title": "CRAG -- Comprehensive RAG Benchmark"
            }
            """.formatted(SUMMARY_STR)
            .trim();

    private static final String OUTPUT = """
            {
                "output": "%s"
            }
            """.formatted(OUTPUT_STR)
            .trim();

    private static final String VALID_AI_MSG_TXT = "{\"Relevance\":{\"score\":5,\"reason\":\"The summary directly addresses the approach taken in the study by mentioning the systematic experimentation with varying data mixtures and the manipulation of proportions and sources.\"},"
            +
            "\"Conciseness\":{\"score\":4,\"reason\":\"The summary is mostly concise but could be slightly more streamlined by removing redundant phrases.\"},"
            +
            "\"Technical Accuracy\":{\"score\":0,\"reason\":\"The summary accurately describes the experimental approach involving data mixtures, proportions, and sources, reflecting the technical details of the study.\"}}";

    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(20);
    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(20);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickhouse = ClickHouseContainerUtils.newClickHouseContainer(zookeeper);

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redis, mysql, clickhouse, zookeeper).join();
        wireMock = WireMockUtils.startWireMock();
        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickhouse, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(mysql);
        MigrationUtils.runClickhouseDbMigration(clickhouse);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(mysql.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(redis.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        .disableModules(List.of(LlmModule.class))
                        .modules(List.of(new AbstractModule() {
                            @Override
                            public void configure() {
                                bind(LlmProviderFactory.class)
                                        .toInstance(Mockito.mock(LlmProviderFactory.class, Mockito.RETURNS_DEEP_STUBS));
                            }
                        }))
                        .customConfigs(List.of(
                                new CustomConfig("pythonEvaluator.url",
                                        wireMock.runtimeInfo().getHttpBaseUrl() + "/pythonBackendMock"),
                                new CustomConfig("serviceToggles.pythonEvaluatorEnabled", "true"),
                                new CustomConfig("serviceToggles.traceThreadPythonEvaluatorEnabled", "true")))
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
    void setUpAll(ClientSupport client) {
        this.baseURI = TestUtils.getBaseUrl(client);
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

        private static final String FAKE_API_KEY = "apiKey-" + UUID.randomUUID();

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
                            .willReturn(WireMock.unauthorized()
                                    .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
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

        private static final String SESSION_TOKEN = "sessionToken-" + UUID.randomUUID();
        private static final String FAKE_SESSION_TOKEN = "sessionToken-" + UUID.randomUUID();

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
                            .willReturn(WireMock.unauthorized()
                                    .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
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
                    .projectIds(Set.of(projectId))
                    .build();
            try (var actualResponse = evaluatorsResourceClient.createEvaluatorWithSessionToken(
                    ruleEvaluator, sessionToken, workspaceName)) {

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
                String sessionToken, boolean isAuthorized, String workspaceName) {
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
                var evaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                        .projectIds(Set.of(projectId))
                        .build();
                evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);
            });

            try (var actualResponse = evaluatorsResourceClient.findEvaluatorWithSessionToken(
                    projectId, samplesToCreate, sessionToken, workspaceName)) {
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
                String sessionToken, boolean isAuthorized, String workspaceName) {
            var evaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class);
            var id = evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);

            try (var actualResponse = evaluatorsResourceClient.getEvaluatorWithSessionToken(id, null, sessionToken,
                    workspaceName)) {
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
            var evaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class);
            var id = evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);

            // Extract project IDs with null-safe handling (support backward compatibility)
            Set<UUID> projectIds = Optional.ofNullable(evaluator.getProjects())
                    .map(projects -> projects.stream()
                            .map(ProjectReference::projectId)
                            .collect(Collectors.toSet()))
                    .orElseGet(() -> Optional.ofNullable(evaluator.getProjectId())
                            .map(Set::of)
                            .orElse(Set.of()));

            var updatedEvaluator = factory.manufacturePojo(AutomationRuleEvaluatorUpdateLlmAsJudge.class).toBuilder()
                    .projectIds(projectIds)
                    .build();
            try (var actualResponse = evaluatorsResourceClient.updateEvaluatorWithSessionToken(
                    id, updatedEvaluator, sessionToken, workspaceName)) {

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
        void deleteAutomationRuleEvaluator__whenSessionTokenIsPresent__thenReturnProperResponse(
                String sessionToken, boolean isAuthorized, String workspaceName) {
            var evaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class);
            var id = evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);

            var deleteMethod = BatchDelete.builder().ids(Collections.singleton(id)).build();
            try (var actualResponse = evaluatorsResourceClient.deleteWithSessionToken(null, deleteMethod, sessionToken,
                    workspaceName)) {

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
                String sessionToken, boolean isAuthorized, String workspaceName) {
            var projectId = projectResourceClient.createProject(UUID.randomUUID().toString(), API_KEY,
                    WORKSPACE_NAME);
            var evaluator1 = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                    .projectIds(Set.of(projectId))
                    .build();
            var evalId1 = evaluatorsResourceClient.createEvaluator(evaluator1, WORKSPACE_NAME, API_KEY);
            var evaluator2 = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                    .projectIds(Set.of(projectId))
                    .build();
            var evalId2 = evaluatorsResourceClient.createEvaluator(evaluator2, WORKSPACE_NAME, API_KEY);
            var evaluator3 = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                    .projectIds(Set.of(projectId))
                    .build();
            evaluatorsResourceClient.createEvaluator(evaluator3, WORKSPACE_NAME, API_KEY);

            var evalIds1and2 = Set.of(evalId1, evalId2);
            var deleteMethod = BatchDelete.builder().ids(evalIds1and2).build();
            try (var actualResponse = evaluatorsResourceClient.deleteWithSessionToken(
                    projectId, deleteMethod, sessionToken, workspaceName)) {

                if (isAuthorized) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
                    assertThat(actualResponse.hasEntity()).isFalse();
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                    assertThat(actualResponse.readEntity(ErrorMessage.class)).isEqualTo(UNAUTHORIZED_RESPONSE);
                }
            }
            // we shall see a single evaluators for the project now
            try (var actualResponse = evaluatorsResourceClient.findEvaluatorWithSessionToken(
                    projectId, null, sessionToken, workspaceName)) {
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
                String sessionToken, boolean isAuthorized, String workspaceName, LlmProviderFactory llmProviderFactory)
                throws JsonProcessingException {
            var chatResponse = ChatResponse.builder().aiMessage(AiMessage.from(VALID_AI_MSG_TXT)).build();
            when(llmProviderFactory.getLanguageModel(anyString(), any())
                    .chat(any(ChatRequest.class)))
                    .thenAnswer(invocationOnMock -> chatResponse);
            var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(36);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
            var evaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                    .code(OBJECT_MAPPER.readValue(LLM_AS_A_JUDGE_EVALUATOR,
                            AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode.class))
                    .samplingRate(1f)
                    .filters(List.of())
                    .projectIds(Set.of(projectId))
                    .build();
            var id = evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);

            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(projectId)
                    .projectName(projectName) // Backend uses projectName, not projectId!
                    .threadId(null) // Must be null for trace-level evaluation
                    .input(OBJECT_MAPPER.readTree(INPUT))
                    .output(OBJECT_MAPPER.readTree(OUTPUT))
                    .build();
            traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);

            Awaitility.await().untilAsserted(() -> {
                try (var actualResponse = evaluatorsResourceClient.getLogsWithSessionToken(
                        id, sessionToken, workspaceName)) {
                    if (isAuthorized) {
                        assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
                        assertThat(actualResponse.hasEntity()).isTrue();
                        var actualEntity = actualResponse.readEntity(LogPage.class);
                        assertTraceLogResponse(actualEntity, id, trace);
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
                    arguments(factory.manufacturePojo(AutomationRuleEvaluatorUserDefinedMetricPython.class), false),
                    arguments(factory.manufacturePojo(AutomationRuleEvaluatorTraceThreadLlmAsJudge.class), true),
                    arguments(factory.manufacturePojo(AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.class),
                            false),
                    arguments(factory.manufacturePojo(AutomationRuleEvaluatorSpanLlmAsJudge.class), true),
                    arguments(factory.manufacturePojo(AutomationRuleEvaluatorSpanUserDefinedMetricPython.class),
                            false));
        }

        @ParameterizedTest
        @MethodSource
        void createAndGet(AutomationRuleEvaluator<?, ?> automationRuleEvaluator, boolean withProjectId) {
            var id = evaluatorsResourceClient.createEvaluator(automationRuleEvaluator, WORKSPACE_NAME, API_KEY);
            // Set projectId (singular, legacy field) to alphabetically-first project for backward compatibility
            var primaryProjectId = getPrimaryProjectId(automationRuleEvaluator.getProjects());
            var expectedAutomationRuleEvaluator = automationRuleEvaluator.toBuilder()
                    .id(id)
                    .projectId(primaryProjectId)
                    .createdBy(USER)
                    .lastUpdatedBy(USER)
                    .build();

            try (var actualResponse = evaluatorsResourceClient.getEvaluator(
                    id,
                    withProjectId
                            ? getPrimaryProjectId(expectedAutomationRuleEvaluator.getProjects())
                            : null,
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

        Stream<Class<? extends AutomationRuleEvaluator<?, ?>>> find() {
            return Stream.of(
                    AutomationRuleEvaluatorLlmAsJudge.class,
                    AutomationRuleEvaluatorUserDefinedMetricPython.class,
                    AutomationRuleEvaluatorTraceThreadLlmAsJudge.class,
                    AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.class,
                    AutomationRuleEvaluatorSpanLlmAsJudge.class,
                    AutomationRuleEvaluatorSpanUserDefinedMetricPython.class);
        }

        @ParameterizedTest
        @MethodSource
        void find(Class<? extends AutomationRuleEvaluator<?, ?>> evaluatorClass) {
            String projectName = factory.manufacturePojo(String.class);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
            var unexpectedProjectId = generator.generate();

            var evaluators = PodamFactoryUtils.manufacturePojoList(factory, evaluatorClass)
                    .stream()
                    .map(evaluator -> evaluator.toBuilder()
                            .projectIds(Set.of(projectId))
                            .build())
                    .map(evaluator -> {
                        var id = evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);
                        var primaryProjectId = getPrimaryProjectId(evaluator.getProjects());
                        return (AutomationRuleEvaluator<?, ?>) evaluator.toBuilder()
                                .id(id)
                                .projectId(primaryProjectId) // Set legacy projectId to alphabetically-first project
                                .createdBy(USER)
                                .lastUpdatedBy(USER)
                                .build();
                    }).toList();

            var unexpectedEvaluators = PodamFactoryUtils.manufacturePojoList(factory, evaluatorClass)
                    .stream()
                    .map(evaluator -> evaluator.toBuilder()
                            .projectIds(Set.of(unexpectedProjectId))
                            .build())
                    .map(evaluator -> {
                        var id = evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);
                        var primaryProjectId = getPrimaryProjectId(evaluator.getProjects());
                        return (AutomationRuleEvaluator<?, ?>) evaluator.toBuilder()
                                .id(id)
                                .projectId(primaryProjectId) // Set legacy projectId to alphabetically-first project
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
                    arguments(AutomationRuleEvaluatorUserDefinedMetricPython.class, false),
                    arguments(AutomationRuleEvaluatorTraceThreadLlmAsJudge.class, true),
                    arguments(AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.class, false),
                    arguments(AutomationRuleEvaluatorSpanLlmAsJudge.class, true),
                    arguments(AutomationRuleEvaluatorSpanUserDefinedMetricPython.class, false));
        }

        @ParameterizedTest
        @MethodSource
        void findByName(Class<? extends AutomationRuleEvaluator<?, ?>> evaluatorClass, boolean withProjectId) {
            String projectName = factory.manufacturePojo(String.class);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            var evaluators = PodamFactoryUtils.manufacturePojoList(factory, evaluatorClass)
                    .stream()
                    .map(evaluator -> evaluator.toBuilder()
                            .projectIds(Set.of(projectId))
                            .build())
                    .map(evaluator -> {
                        var id = evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);
                        var primaryProjectId = getPrimaryProjectId(evaluator.getProjects());
                        return (AutomationRuleEvaluator<?, ?>) evaluator.toBuilder()
                                .id(id)
                                .projectId(primaryProjectId) // Set legacy projectId to alphabetically-first project
                                .createdBy(USER)
                                .lastUpdatedBy(USER)
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
                            factory.manufacturePojo(
                                    AutomationRuleEvaluatorUpdateUserDefinedMetricPython.class)),
                    arguments(
                            factory.manufacturePojo(AutomationRuleEvaluatorTraceThreadLlmAsJudge.class),
                            factory.manufacturePojo(AutomationRuleEvaluatorUpdateTraceThreadLlmAsJudge.class)),
                    arguments(
                            factory.manufacturePojo(AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.class),
                            factory.manufacturePojo(
                                    AutomationRuleEvaluatorUpdateTraceThreadUserDefinedMetricPython.class)),
                    arguments(
                            factory.manufacturePojo(AutomationRuleEvaluatorSpanLlmAsJudge.class),
                            factory.manufacturePojo(AutomationRuleEvaluatorUpdateSpanLlmAsJudge.class)),
                    arguments(
                            factory.manufacturePojo(AutomationRuleEvaluatorSpanUserDefinedMetricPython.class),
                            factory.manufacturePojo(AutomationRuleEvaluatorUpdateSpanUserDefinedMetricPython.class)));
        }

        @ParameterizedTest
        @MethodSource
        void update(AutomationRuleEvaluator<Object, ?> automationRuleEvaluator,
                AutomationRuleEvaluatorUpdate<?, ?> automationRuleEvaluatorUpdate) {
            // Create project and set it on the evaluator
            var projectId = projectResourceClient.createProject(UUID.randomUUID().toString(), API_KEY, WORKSPACE_NAME);
            var evaluatorWithProject = automationRuleEvaluator.toBuilder()
                    .projectIds(Set.of(projectId))
                    .build();
            var id = evaluatorsResourceClient.createEvaluator(evaluatorWithProject, WORKSPACE_NAME, API_KEY);

            // Prepare update with same projectIds
            var updatedEvaluator = automationRuleEvaluatorUpdate.toBuilder()
                    .projectIds(Set.of(projectId))
                    .build();
            try (var actualResponse = evaluatorsResourceClient.updateEvaluator(
                    id, WORKSPACE_NAME, updatedEvaluator, API_KEY, HttpStatus.SC_NO_CONTENT)) {
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            // Build expected result from the evaluator with correct projectIds and projectId
            var primaryProjectId = getPrimaryProjectId(evaluatorWithProject.getProjects());
            var expectedAutomationRuleEvaluator = evaluatorWithProject.toBuilder()
                    .id(id)
                    .projectId(primaryProjectId) // Set legacy projectId to alphabetically-first project
                    .createdBy(USER)
                    .lastUpdatedBy(USER)
                    .name(updatedEvaluator.getName())
                    .samplingRate(updatedEvaluator.getSamplingRate())
                    .enabled(updatedEvaluator.isEnabled())
                    .filters((List) updatedEvaluator.getFilters())
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

        @ParameterizedTest
        @MethodSource
        @DisplayName("Should update enabled status correctly")
        void updateEnabledStatus(boolean initialEnabled, boolean targetEnabled, String scenarioDescription) {
            // Create a rule with initial enabled state
            var projectId = projectResourceClient.createProject(UUID.randomUUID().toString(), API_KEY, WORKSPACE_NAME);
            var automationRuleEvaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class)
                    .toBuilder()
                    .projectIds(Set.of(projectId))
                    .enabled(initialEnabled)
                    .build();
            var id = evaluatorsResourceClient.createEvaluator(automationRuleEvaluator, WORKSPACE_NAME, API_KEY);

            // Update to target enabled state
            var updatedEvaluator = factory.manufacturePojo(AutomationRuleEvaluatorUpdateLlmAsJudge.class)
                    .toBuilder()
                    .projectIds(Set.of(projectId))
                    .enabled(targetEnabled)
                    .build();

            try (var actualResponse = evaluatorsResourceClient.updateEvaluator(
                    id, WORKSPACE_NAME, updatedEvaluator, API_KEY, HttpStatus.SC_NO_CONTENT)) {
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            // Verify the rule has the expected enabled state
            try (var actualResponse = evaluatorsResourceClient.getEvaluator(
                    id, null, WORKSPACE_NAME, API_KEY, HttpStatus.SC_OK)) {
                var actualAutomationRuleEvaluator = actualResponse.readEntity(AutomationRuleEvaluator.class);
                assertThat(actualAutomationRuleEvaluator.isEnabled()).isEqualTo(targetEnabled);
                assertThat(actualAutomationRuleEvaluator.getName()).isEqualTo(updatedEvaluator.getName());
            }
        }

        static Stream<Arguments> updateEnabledStatus() {
            return Stream.of(
                    Arguments.of(true, false, "enabled to disabled"),
                    Arguments.of(false, true, "disabled to enabled"));
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
            var id3 = createGetAndAssertId(AutomationRuleEvaluatorTraceThreadLlmAsJudge.class, projectId);
            var id4 = createGetAndAssertId(AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.class, projectId);
            var id5 = createGetAndAssertId(AutomationRuleEvaluatorSpanLlmAsJudge.class, projectId);
            var id6 = createGetAndAssertId(AutomationRuleEvaluatorSpanUserDefinedMetricPython.class, projectId);
            var id7 = createGetAndAssertId(AutomationRuleEvaluatorLlmAsJudge.class, projectId);
            var id8 = createGetAndAssertId(AutomationRuleEvaluatorUserDefinedMetricPython.class, projectId);

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
                assertThat(actualEntity.size()).isEqualTo(6);
                assertThat(actualEntity.total()).isEqualTo(6);

                var actualAutomationRuleEvaluators = actualEntity.content();
                assertThat(actualAutomationRuleEvaluators).hasSize(6);
                var actualIds = actualAutomationRuleEvaluators.stream()
                        .map(AutomationRuleEvaluator::getId)
                        .collect(Collectors.toSet());
                assertThat(actualIds).containsExactlyInAnyOrder(id3, id4, id5, id6, id7, id8);
            }
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetLogs {

        @Test
        void getLogsLlmAsJudgeScorer(LlmProviderFactory llmProviderFactory) throws JsonProcessingException {
            var chatResponse = ChatResponse.builder().aiMessage(AiMessage.from(VALID_AI_MSG_TXT)).build();
            when(llmProviderFactory.getLanguageModel(anyString(), any())
                    .chat(any(ChatRequest.class)))
                    .thenAnswer(invocationOnMock -> chatResponse);
            var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(36);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
            var evaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                    .code(OBJECT_MAPPER.readValue(LLM_AS_A_JUDGE_EVALUATOR,
                            AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode.class))
                    .samplingRate(1f)
                    .filters(List.of())
                    .projectIds(Set.of(projectId))
                    .build();
            var id = evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);

            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(projectId)
                    .projectName(projectName) // Backend uses projectName, not projectId!
                    .threadId(null) // Must be null for trace-level evaluation
                    .input(OBJECT_MAPPER.readTree(INPUT))
                    .output(OBJECT_MAPPER.readTree(OUTPUT))
                    .build();
            traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);

            Awaitility.await().untilAsserted(() -> {
                var logPage = evaluatorsResourceClient.getLogs(id, WORKSPACE_NAME, API_KEY);
                assertTraceLogResponse(logPage, id, trace);
            });
        }

        @Test
        void getLogsTraceThreadLlmAsJudgeScorer(LlmProviderFactory llmProviderFactory) throws JsonProcessingException {
            var chatResponse = ChatResponse.builder().aiMessage(AiMessage.from(VALID_AI_MSG_TXT)).build();
            when(llmProviderFactory.getLanguageModel(anyString(), any())
                    .chat(any(ChatRequest.class)))
                    .thenAnswer(invocationOnMock -> chatResponse);

            var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(36);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
            var evaluator = factory.manufacturePojo(AutomationRuleEvaluatorTraceThreadLlmAsJudge.class).toBuilder()
                    .code(OBJECT_MAPPER.readValue(LLM_AS_A_JUDGE_EVALUATOR,
                            AutomationRuleEvaluatorTraceThreadLlmAsJudge.TraceThreadLlmAsJudgeCode.class))
                    .samplingRate(1f)
                    .filters(List.of())
                    .projectIds(Set.of(projectId))
                    .build();

            var id = evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);

            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(projectId)
                    .projectName(projectName) // Backend uses projectName, not projectId!
                    .threadId("thread-" + RandomStringUtils.secure().nextAlphanumeric(36))
                    .input(OBJECT_MAPPER.readTree(INPUT))
                    .output(OBJECT_MAPPER.readTree(OUTPUT))
                    .build();

            Instant createdAt = trace.createdAt();
            traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);

            Awaitility.await().untilAsserted(() -> {

                TraceThread traceThread = traceResourceClient.getTraceThread(trace.threadId(), projectId, API_KEY,
                        WORKSPACE_NAME);

                // Wait for threadModelId to be populated by async thread processing
                assertThat(traceThread.threadModelId()).isNotNull();

                String threadModelId = traceThread.threadModelId().toString();

                Map<String, String> markers = Map.of("thread_model_id", threadModelId);
                String message = "The threadModelId '.*' will be sampled for rule: '.*' with sampling rate '.*'";

                var logPage = evaluatorsResourceClient.getLogs(id, WORKSPACE_NAME, API_KEY);
                assertTraceThreadLogResponse(logPage, id, markers, createdAt, message);
            });
        }

        @Test
        void getLogsUserDefinedMetricPythonScorer() throws JsonProcessingException {
            var outputJson = OBJECT_MAPPER.readTree("""
                    {
                        "response": "abc"
                    }
                    """);
            var pythonEvaluatorRequest = PythonEvaluatorRequest.builder()
                    .code(USER_DEFINED_METRIC)
                    .data(Map.of(
                            "output", OBJECT_MAPPER.convertValue(outputJson, Object.class)))
                    .build();
            var pythonEvaluatorResponse = factory.manufacturePojo(PythonEvaluatorResponse.class);
            wireMock.server().stubFor(
                    post(urlPathEqualTo("/pythonBackendMock/v1/private/evaluators/python"))
                            .withRequestBody(equalToJson(OBJECT_MAPPER.writeValueAsString(pythonEvaluatorRequest)))
                            .willReturn(okJson(OBJECT_MAPPER.writeValueAsString(pythonEvaluatorResponse))));
            var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(36);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
            var evaluator = factory.manufacturePojo(AutomationRuleEvaluatorUserDefinedMetricPython.class).toBuilder()
                    .code(AutomationRuleEvaluatorUserDefinedMetricPython.UserDefinedMetricPythonCode.builder()
                            .metric(USER_DEFINED_METRIC)
                            .build())
                    .samplingRate(1f)
                    .filters(List.of())
                    .projectIds(Set.of(projectId))
                    .build();
            var id = evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);

            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(projectId)
                    .projectName(projectName)
                    .threadId(null)
                    .input(null)
                    .metadata(null)
                    .output(outputJson)
                    .build();
            traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);

            Awaitility.await().untilAsserted(() -> {
                var logPage = evaluatorsResourceClient.getLogs(id, WORKSPACE_NAME, API_KEY);
                assertTraceLogResponse(logPage, id, trace);
            });
        }

        @Test
        void getLogsTraceThreadUserDefinedMetricPythonScorer() throws JsonProcessingException {
            //Given
            var pythonEvaluatorRequest = TraceThreadPythonEvaluatorRequest.builder()
                    .code(TRACE_THREAD_USER_DEFINED_METRIC)
                    .data(List.of(
                            TraceThreadPythonEvaluatorRequest.ChatMessage.builder()
                                    .role("user")
                                    .content(TextNode.valueOf("abc"))
                                    .build(),
                            TraceThreadPythonEvaluatorRequest.ChatMessage.builder()
                                    .role("assistant")
                                    .content(TextNode.valueOf("abc"))
                                    .build()))
                    .build();

            var pythonEvaluatorResponse = factory.manufacturePojo(PythonEvaluatorResponse.class);

            // When
            wireMock.server().stubFor(
                    post(urlPathEqualTo("/pythonBackendMock/v1/private/evaluators/python"))
                            .withRequestBody(equalToJson(OBJECT_MAPPER.writeValueAsString(pythonEvaluatorRequest)))
                            .willReturn(okJson(OBJECT_MAPPER.writeValueAsString(pythonEvaluatorResponse))));

            var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(36);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);
            var evaluator = factory.manufacturePojo(AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.class)
                    .toBuilder()
                    .code(TraceThreadUserDefinedMetricPythonCode.builder()
                            .metric(TRACE_THREAD_USER_DEFINED_METRIC)
                            .build())
                    .samplingRate(1f)
                    .filters(List.of())
                    .projectIds(Set.of(projectId))
                    .build();
            var id = evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);

            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(projectId)
                    .projectName(projectName) // Backend uses projectName, not projectId!
                    .threadId("thread-" + RandomStringUtils.secure().nextAlphanumeric(36)) // Must have threadId for thread-level evaluation
                    .output(OBJECT_MAPPER.readTree("""
                            {
                                "response": "abc"
                            }
                            """))
                    .build();

            Instant createdAt = trace.createdAt();
            traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);

            // Then
            Awaitility.await().untilAsserted(() -> {
                TraceThread traceThread = traceResourceClient.getTraceThread(trace.threadId(), projectId, API_KEY,
                        WORKSPACE_NAME);

                // Wait for threadModelId to be populated by async thread processing
                assertThat(traceThread.threadModelId()).isNotNull();

                String threadModelId = traceThread.threadModelId().toString();

                Map<String, String> markers = Map.of("thread_model_id", threadModelId);
                String message = "The threadModelId '.*' will be sampled for rule: '.*' with sampling rate '.*'";

                var logPage = evaluatorsResourceClient.getLogs(id, WORKSPACE_NAME, API_KEY);
                assertTraceThreadLogResponse(logPage, id, markers, createdAt, message);
            });
        }

        @Test
        void getLogsTraceSkipped() {
            // Adding 2 rules to a project, as there was a bug in the sampler causing the logs to fail when having
            // multiple rules. This test should have at least 2 rules to ensure that the bug is fixed.
            var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(36);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            // Added a Python rule with sampling rate 0
            var evaluatorPython = factory.manufacturePojo(AutomationRuleEvaluatorUserDefinedMetricPython.class)
                    .toBuilder()
                    .samplingRate(0f)
                    .filters(List.of())
                    .projectIds(Set.of(projectId))
                    .build();
            var idPython = evaluatorsResourceClient.createEvaluator(evaluatorPython, WORKSPACE_NAME, API_KEY);

            // Added a LLM rule with sampling rate 0
            var evaluatorLlm = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class)
                    .toBuilder()
                    .samplingRate(0f)
                    .filters(List.of())
                    .projectIds(Set.of(projectId))
                    .build();
            var idLlm = evaluatorsResourceClient.createEvaluator(evaluatorLlm, WORKSPACE_NAME, API_KEY);

            // Sending a trace, that shouldn't be sampled as the rate is 0 for both rules
            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(projectId)
                    .projectName(projectName) // Backend uses projectName, not projectId!
                    .threadId(null)
                    .build();
            traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);

            Awaitility.await().untilAsserted(() -> {
                var logPagePython = evaluatorsResourceClient.getLogs(idPython, WORKSPACE_NAME, API_KEY);
                assertLogResponse(logPagePython, idPython, evaluatorPython, trace);
                var logPageLlm = evaluatorsResourceClient.getLogs(idLlm, WORKSPACE_NAME, API_KEY);
                assertLogResponse(logPageLlm, idLlm, evaluatorLlm, trace);
            });
        }

        @Test
        void getLogsTraceThreadSkipped() {
            // Adding 2 rules to a project, as there was a bug in the sampler causing the logs to fail when having
            // multiple rules. This test should have at least 2 rules to ensure that the bug is fixed.
            var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(36);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            // Added a Python rule with sampling rate 0
            var evaluatorPython = Optional.ofNullable(factory
                    .manufacturePojo(AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.class)
                    .toBuilder()
                    .samplingRate(0f)
                    .filters(List.of())
                    .projectIds(Set.of(projectId))
                    .build())
                    .map(evaluator -> evaluator.toBuilder()
                            .id(evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY))
                            .build())
                    .get();

            // Added a LLM rule with sampling rate 0
            var evaluatorLlm = Optional.ofNullable(
                    factory.manufacturePojo(AutomationRuleEvaluatorTraceThreadLlmAsJudge.class)
                            .toBuilder()
                            .samplingRate(0f)
                            .filters(List.of())
                            .projectIds(Set.of(projectId))
                            .build())
                    .map(evaluator -> evaluator.toBuilder()
                            .id(evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY))
                            .build())
                    .get();

            // Sending a trace, that shouldn't be sampled as the rate is 0 for both rules
            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(projectId)
                    .projectName(projectName) // Backend uses projectName, not projectId!
                    .threadId("thread-" + RandomStringUtils.secure().nextAlphanumeric(36)) // Must have threadId for thread-level evaluation
                    .build();

            Instant createdAt = trace.createdAt();
            traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);

            Awaitility.await().untilAsserted(() -> {
                TraceThread traceThread = traceResourceClient.getTraceThread(trace.threadId(), projectId, API_KEY,
                        WORKSPACE_NAME);

                // Wait for threadModelId to be populated
                assertThat(traceThread.threadModelId()).isNotNull();

                var logPagePython = evaluatorsResourceClient.getLogs(evaluatorPython.getId(), WORKSPACE_NAME, API_KEY);
                var logPageLlm = evaluatorsResourceClient.getLogs(evaluatorLlm.getId(), WORKSPACE_NAME, API_KEY);

                String threadModelId = traceThread.threadModelId().toString();

                Map<String, String> markers = Map.of("thread_model_id", threadModelId);
                String message = "The threadModelId '%s' was skipped for rule: '%s' and per the sampling rate '%s'";

                assertTraceThreadLogResponse(logPagePython, threadModelId, evaluatorPython, markers, createdAt,
                        message);
                assertTraceThreadLogResponse(logPageLlm, threadModelId, evaluatorLlm, markers, createdAt, message);
            });
        }

    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class DisabledRulesTest {

        @Test
        void createEvaluatorWithDefaultEnabledTrue() {
            // Create a rule without explicitly setting enabled - should default to true
            var automationRuleEvaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class);
            var id = evaluatorsResourceClient.createEvaluator(automationRuleEvaluator, WORKSPACE_NAME, API_KEY);

            try (var actualResponse = evaluatorsResourceClient.getEvaluator(
                    id, null, WORKSPACE_NAME, API_KEY, HttpStatus.SC_OK)) {
                var actualAutomationRuleEvaluator = actualResponse.readEntity(AutomationRuleEvaluator.class);
                // Should default to enabled if not explicitly set
                assertThat(actualAutomationRuleEvaluator.isEnabled()).isTrue();
            }
        }

        @Test
        void createDisabledEvaluator() {
            // Create a disabled rule explicitly
            var automationRuleEvaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class)
                    .toBuilder()
                    .enabled(false)
                    .build();
            var id = evaluatorsResourceClient.createEvaluator(automationRuleEvaluator, WORKSPACE_NAME, API_KEY);

            try (var actualResponse = evaluatorsResourceClient.getEvaluator(
                    id, null, WORKSPACE_NAME, API_KEY, HttpStatus.SC_OK)) {
                var actualAutomationRuleEvaluator = actualResponse.readEntity(AutomationRuleEvaluator.class);
                assertThat(actualAutomationRuleEvaluator.isEnabled()).isFalse();
            }
        }

        @Test
        void getLogsTraceSkippedDueToDisabledRule() {
            // Test that disabled rules generate appropriate log messages (different from sampling rate messages)
            var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(36);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            // Create a disabled Python rule with high sampling rate (to distinguish from sampling rate issues)
            var evaluatorPython = factory.manufacturePojo(AutomationRuleEvaluatorUserDefinedMetricPython.class)
                    .toBuilder()
                    .samplingRate(1.0f) // High sampling rate, but rule is disabled
                    .enabled(false)
                    .projectIds(Set.of(projectId))
                    .build();
            var idPython = evaluatorsResourceClient.createEvaluator(evaluatorPython, WORKSPACE_NAME, API_KEY);

            // Create a disabled LLM rule with high sampling rate
            var evaluatorLlm = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class)
                    .toBuilder()
                    .samplingRate(1.0f) // High sampling rate, but rule is disabled
                    .enabled(false)
                    .projectIds(Set.of(projectId))
                    .build();
            var idLlm = evaluatorsResourceClient.createEvaluator(evaluatorLlm, WORKSPACE_NAME, API_KEY);

            // Send a trace that should be skipped due to disabled rules (not sampling rate)
            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(projectId)
                    .projectName(projectName) // Backend uses projectName, not projectId!
                    .threadId(null)
                    .build();
            traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);

            Awaitility.await().untilAsserted(() -> {
                var logPagePython = evaluatorsResourceClient.getLogs(idPython, WORKSPACE_NAME, API_KEY);
                assertDisabledRuleLogResponse(logPagePython, idPython, evaluatorPython, trace);
                var logPageLlm = evaluatorsResourceClient.getLogs(idLlm, WORKSPACE_NAME, API_KEY);
                assertDisabledRuleLogResponse(logPageLlm, idLlm, evaluatorLlm, trace);
            });
        }

        @Test
        void getLogsTraceThreadSkippedDueToDisabledRule() {
            // Test trace thread handling for disabled rules
            var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(36);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            // Create disabled trace thread rules with high sampling rates
            var evaluatorPython = Optional.ofNullable(factory
                    .manufacturePojo(AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.class)
                    .toBuilder()
                    .samplingRate(1.0f) // High sampling rate, but rule is disabled
                    .enabled(false)
                    .filters(List.of())
                    .projectIds(Set.of(projectId))
                    .build())
                    .map(evaluator -> evaluator.toBuilder()
                            .id(evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY))
                            .build())
                    .get();

            var evaluatorLlm = Optional.ofNullable(
                    factory.manufacturePojo(AutomationRuleEvaluatorTraceThreadLlmAsJudge.class)
                            .toBuilder()
                            .samplingRate(1.0f) // High sampling rate, but rule is disabled
                            .enabled(false)
                            .filters(List.of())
                            .projectIds(Set.of(projectId))
                            .build())
                    .map(evaluator -> evaluator.toBuilder()
                            .id(evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY))
                            .build())
                    .get();

            // Send a trace that should create a thread but be skipped due to disabled rules
            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(projectId)
                    .projectName(projectName) // Backend uses projectName, not projectId!
                    .threadId("thread-" + RandomStringUtils.secure().nextAlphanumeric(36)) // Must have threadId for thread-level evaluation
                    .build();

            Instant createdAt = trace.createdAt();
            traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);

            Awaitility.await().untilAsserted(() -> {
                TraceThread traceThread = traceResourceClient.getTraceThread(trace.threadId(), projectId, API_KEY,
                        WORKSPACE_NAME);

                // Wait for threadModelId to be populated
                assertThat(traceThread.threadModelId()).isNotNull();

                var logPagePython = evaluatorsResourceClient.getLogs(evaluatorPython.getId(), WORKSPACE_NAME, API_KEY);
                var logPageLlm = evaluatorsResourceClient.getLogs(evaluatorLlm.getId(), WORKSPACE_NAME, API_KEY);

                String threadModelId = traceThread.threadModelId().toString();

                Map<String, String> markers = Map.of("thread_model_id", threadModelId);
                String disabledMessage = "The threadModelId '%s' was skipped for rule: '%s' as the rule is disabled";

                assertTraceThreadDisabledLogResponse(logPagePython, threadModelId, evaluatorPython, markers, createdAt,
                        disabledMessage);
                assertTraceThreadDisabledLogResponse(logPageLlm, threadModelId, evaluatorLlm, markers, createdAt,
                        disabledMessage);
            });
        }

        @Test
        void mixedEnabledAndDisabledRules() {
            // Test scenario with both enabled and disabled rules in same project
            var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(36);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            // Create one enabled rule
            var enabledRule = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class)
                    .toBuilder()
                    .samplingRate(0f) // Low sampling rate to avoid actual processing
                    .enabled(true)
                    .filters(List.of())
                    .projectIds(Set.of(projectId))
                    .build();
            var enabledId = evaluatorsResourceClient.createEvaluator(enabledRule, WORKSPACE_NAME, API_KEY);

            // Create one disabled rule
            var disabledRule = factory.manufacturePojo(AutomationRuleEvaluatorUserDefinedMetricPython.class)
                    .toBuilder()
                    .samplingRate(1.0f) // High sampling rate, but rule is disabled
                    .enabled(false)
                    .filters(List.of())
                    .projectIds(Set.of(projectId))
                    .build();
            var disabledId = evaluatorsResourceClient.createEvaluator(disabledRule, WORKSPACE_NAME, API_KEY);

            // Send a trace
            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectId(projectId)
                    .projectName(projectName) // Backend uses projectName, not projectId!
                    .threadId(null)
                    .build();
            traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);

            Awaitility.await().untilAsserted(() -> {
                // Enabled rule should generate sampling rate message (skipped due to 0% rate)
                var enabledLogPage = evaluatorsResourceClient.getLogs(enabledId, WORKSPACE_NAME, API_KEY);
                assertLogResponse(enabledLogPage, enabledId, enabledRule, trace);

                // Disabled rule should generate disabled message
                var disabledLogPage = evaluatorsResourceClient.getLogs(disabledId, WORKSPACE_NAME, API_KEY);
                assertDisabledRuleLogResponse(disabledLogPage, disabledId, disabledRule, trace);
            });
        }

        @Test
        void disabledRuleDoesNotConsumeResources() {
            // Verify that disabled rules are skipped early and don't go through sampling logic
            var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(36);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            // Create disabled rule with 100% sampling rate
            var disabledRule = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class)
                    .toBuilder()
                    .samplingRate(1.0f) // 100% sampling rate
                    .enabled(false) // But disabled
                    .projectIds(Set.of(projectId))
                    .build();
            var disabledId = evaluatorsResourceClient.createEvaluator(disabledRule, WORKSPACE_NAME, API_KEY);

            // Send multiple traces
            for (int i = 0; i < 5; i++) {
                var trace = factory.manufacturePojo(Trace.class).toBuilder()
                        .projectId(projectId)
                        .projectName(projectName) // Backend uses projectName, not projectId!
                        .threadId(null)
                        .build();
                traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);
            }

            // All traces should be skipped with "disabled" message, none with sampling rate message
            Awaitility.await().untilAsserted(() -> {
                var logPage = evaluatorsResourceClient.getLogs(disabledId, WORKSPACE_NAME, API_KEY);
                assertLogPage(logPage, 5); // Should have 5 log entries

                // All log messages should be about rule being disabled, not sampling rate
                assertThat(logPage.content())
                        .allSatisfy(log -> {
                            assertThat(log.level()).isEqualTo(LogLevel.INFO);
                            assertThat(log.message()).contains("as the rule is disabled");
                            assertThat(log.message()).doesNotContain("per the sampling rate");
                        });
            });
        }
    }

    private void assertTraceThreadLogResponse(LogPage logPage, String threadModelId, AutomationRuleEvaluator<?, ?> rule,
            Map<String, String> markers,
            Instant createdAt, String message) {
        assertLogPage(logPage, 1);

        assertThat(logPage.content())
                .allSatisfy(log -> {
                    assertThat(log.timestamp()).isBetween(createdAt, Instant.now());
                    assertThat(log.ruleId()).isEqualTo(rule.getId());
                    assertThat(log.level()).isEqualTo(LogLevel.INFO);
                    assertThat(log.markers()).isEqualTo(markers);
                });

        assertThat(logPage.content().getFirst().message())
                .isEqualTo(message.formatted(threadModelId, rule.getName(), rule.getSamplingRate()));
    }

    private void assertTraceThreadLogResponse(LogPage logPage, UUID id, Map<String, String> markers,
            Instant createdAt, String message) {
        assertLogPage(logPage, 1);

        assertThat(logPage.content())
                .allSatisfy(log -> {
                    assertThat(log.timestamp()).isBetween(createdAt, Instant.now());
                    assertThat(log.ruleId()).isEqualTo(id);
                    assertThat(log.level()).isEqualTo(LogLevel.INFO);
                    assertThat(log.markers()).isEqualTo(markers);
                });

        assertThat(logPage.content())
                .anyMatch(log -> log.message().matches(message));
    }

    private UUID createGetAndAssertId(Class<? extends AutomationRuleEvaluator<?, ?>> evaluatorClass, UUID projectId) {
        var automationRuleEvaluator = factory.manufacturePojo(evaluatorClass).toBuilder()
                .projectIds(Set.of(projectId))
                .build();
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
            List<? extends AutomationRuleEvaluator<?, ?>> expectedAutomationRuleEvaluators,
            List<? extends AutomationRuleEvaluator<?, ?>> unexpectedAutomationRuleEvaluators) {

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
                    .usingRecursiveFieldByFieldElementComparatorIgnoringFields(AUTOMATION_RULE_EVALUATOR_IGNORED_FIELDS)
                    .containsExactlyElementsOf(expectedAutomationRuleEvaluators);

            for (int i = 0; i < actualAutomationRuleEvaluators.size(); i++) {
                var actualAutomationRuleEvaluator = actualAutomationRuleEvaluators.get(i);
                var expectedAutomationRuleEvaluator = expectedAutomationRuleEvaluators.get(i);
                assertIgnoredFields(actualAutomationRuleEvaluator, expectedAutomationRuleEvaluator);
            }

            assertThat(actualAutomationRuleEvaluators)
                    .usingRecursiveFieldByFieldElementComparatorIgnoringFields(AUTOMATION_RULE_EVALUATOR_IGNORED_FIELDS)
                    .doesNotContainAnyElementsOf(unexpectedAutomationRuleEvaluators);
        }
    }

    private void assertIgnoredFields(AutomationRuleEvaluator<?, ?> actualRuleEvaluator,
            AutomationRuleEvaluator<?, ?> expectedRuleEvaluator) {
        assertThat(actualRuleEvaluator.getCreatedAt()).isAfter(expectedRuleEvaluator.getCreatedAt());
        assertThat(actualRuleEvaluator.getLastUpdatedAt()).isAfter(expectedRuleEvaluator.getLastUpdatedAt());
    }

    private void assertTraceLogResponse(LogPage logPage, UUID id, Trace trace) {
        assertLogPage(logPage, 4);

        assertThat(logPage.content())
                .allSatisfy(log -> {
                    assertThat(log.timestamp()).isBetween(trace.createdAt(), Instant.now());
                    assertThat(log.ruleId()).isEqualTo(id);
                    assertThat(log.level()).isEqualTo(LogLevel.INFO);
                    assertThat(log.markers()).isEqualTo(Map.of("trace_id", trace.id().toString()));
                });

        assertThat(logPage.content())
                .anyMatch(log -> log.message().matches(
                        "Scores for traceId '.*' stored successfully:\\n\\n.*"));
        assertThat(logPage.content())
                .anyMatch(log -> log.message().matches(
                        "Received response for traceId '.*':\\n\\n.*"));
        assertThat(logPage.content())
                .anyMatch(log -> log.message().matches(
                        "(?s)Sending traceId '.*' to .* using the following input:\\n\\n.*"));
        assertThat(logPage.content())
                .anyMatch(log -> log.message().matches(
                        "Evaluating traceId '.*' sampled by rule '.*'"));
    }

    private static void assertLogPage(LogPage logPage, int expectedSize) {
        assertThat(logPage.content()).hasSize(expectedSize);
        assertThat(logPage.total()).isEqualTo(expectedSize);
        assertThat(logPage.size()).isEqualTo(expectedSize);
        assertThat(logPage.page()).isEqualTo(1);
    }

    private void assertLogResponse(LogPage logPage, UUID ruleId, AutomationRuleEvaluator<?, ?> rule, Trace trace) {
        assertLogPage(logPage, 1);

        assertThat(logPage.content())
                .allSatisfy(log -> {
                    assertThat(log.timestamp()).isBetween(trace.createdAt(), Instant.now());
                    assertThat(log.ruleId()).isEqualTo(ruleId);
                    assertThat(log.level()).isEqualTo(LogLevel.INFO);
                    assertThat(log.markers()).isEqualTo(Map.of("trace_id", trace.id().toString()));
                });

        assertThat(logPage.content().getFirst().message())
                .isEqualTo("The traceId '%s' was skipped for rule: '%s' and per the sampling rate '0.0'".formatted(
                        trace.id(), rule.getName()));
    }

    private void assertDisabledRuleLogResponse(LogPage logPage, UUID ruleId, AutomationRuleEvaluator<?, ?> rule,
            Trace trace) {
        assertLogPage(logPage, 1);

        assertThat(logPage.content())
                .allSatisfy(log -> {
                    assertThat(log.timestamp()).isBetween(trace.createdAt(), Instant.now());
                    assertThat(log.ruleId()).isEqualTo(ruleId);
                    assertThat(log.level()).isEqualTo(LogLevel.INFO);
                    assertThat(log.markers()).isEqualTo(Map.of("trace_id", trace.id().toString()));
                });

        // Verify the message specifically mentions rule being disabled
        assertThat(logPage.content().getFirst().message())
                .isEqualTo("The traceId '%s' was skipped for rule: '%s' as the rule is disabled".formatted(
                        trace.id(), rule.getName()));
    }

    private void assertTraceThreadDisabledLogResponse(LogPage logPage, String threadModelId,
            AutomationRuleEvaluator<?, ?> rule,
            Map<String, String> markers, Instant createdAt, String messageTemplate) {
        assertLogPage(logPage, 1);

        assertThat(logPage.content())
                .allSatisfy(log -> {
                    assertThat(log.timestamp()).isBetween(createdAt, Instant.now());
                    assertThat(log.ruleId()).isEqualTo(rule.getId());
                    assertThat(log.level()).isEqualTo(LogLevel.INFO);
                    assertThat(log.markers()).isEqualTo(markers);
                });

        assertThat(logPage.content().getFirst().message())
                .isEqualTo(messageTemplate.formatted(threadModelId, rule.getName()));
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("Sorting Functionality")
    class SortingFunctionality {

        @ParameterizedTest(name = "Sort by {0} {1}")
        @MethodSource("sortingTestCases")
        @DisplayName("Sort evaluators by various fields with ASC/DESC")
        void sortEvaluators(String field, String direction,
                Function<AutomationRuleEvaluator<?, ?>, Comparable> extractor)
                throws JsonProcessingException {
            // Given
            var projectId = projectResourceClient.createProject(UUID.randomUUID().toString(), API_KEY, WORKSPACE_NAME);

            // Create 5 evaluators with different values
            var evaluators = IntStream.range(0, 5)
                    .mapToObj(i -> {
                        var builder = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                                .projectIds(Set.of(projectId));

                        // Set specific values based on field being tested
                        if (field.equals("name")) {
                            builder.name(
                                    "name-" + (char) ('a' + i) + "-" + RandomStringUtils.secure().nextAlphanumeric(5));
                        } else if (field.equals("sampling_rate")) {
                            builder.samplingRate((float) (i + 1) / 10); // 0.1, 0.2, 0.3, 0.4, 0.5
                        } else if (field.equals("enabled")) {
                            builder.enabled(i % 2 == 0); // alternating true/false
                        }

                        return builder.build();
                    })
                    .toList();

            // Create all evaluators
            evaluators.forEach(ev -> evaluatorsResourceClient.createEvaluator(ev, WORKSPACE_NAME, API_KEY));

            // When
            var sortingFields = List.of(Map.of("field", field, "direction", direction));
            String sorting = OBJECT_MAPPER.writeValueAsString(sortingFields);

            // Then - Wait for ClickHouse and verify sorting
            //Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
            var page = evaluatorsResourceClient.findEvaluatorPage(
                    projectId, null, null, sorting, 1, 10, WORKSPACE_NAME, API_KEY);

            assertThat(page.content()).hasSizeGreaterThanOrEqualTo(5);

            // Extract the values and verify they're sorted correctly
            var actualValues = page.content().stream()
                    .filter(e -> evaluators.stream().anyMatch(ev -> ev.getName().equals(e.getName())))
                    .limit(5)
                    .map(extractor)
                    .toList();

            var expectedValues = new ArrayList<>(actualValues);
            if (direction.equals("ASC")) {
                expectedValues.sort(Comparator.naturalOrder());
            } else {
                expectedValues.sort(Comparator.reverseOrder());
            }

            assertThat(actualValues).isEqualTo(expectedValues);
            //});
        }

        static Stream<Arguments> sortingTestCases() {
            return Stream.of(
                    // Name sorting
                    Arguments.of(
                            "name", "ASC",
                            (Function<AutomationRuleEvaluator<?, ?>, Comparable>) e -> e.getName()),
                    Arguments.of(
                            "name", "DESC",
                            (Function<AutomationRuleEvaluator<?, ?>, Comparable>) e -> e.getName()),

                    // Type sorting
                    Arguments.of(
                            "type", "ASC",
                            (Function<AutomationRuleEvaluator<?, ?>, Comparable>) e -> e.getType()
                                    .name()),
                    Arguments.of(
                            "type", "DESC",
                            (Function<AutomationRuleEvaluator<?, ?>, Comparable>) e -> e.getType()
                                    .name()),

                    // Sampling rate sorting
                    Arguments.of(
                            "sampling_rate", "ASC",
                            (Function<AutomationRuleEvaluator<?, ?>, Comparable>) e -> e
                                    .getSamplingRate()),
                    Arguments.of(
                            "sampling_rate", "DESC",
                            (Function<AutomationRuleEvaluator<?, ?>, Comparable>) e -> e
                                    .getSamplingRate()),

                    // Enabled sorting
                    Arguments.of(
                            "enabled", "ASC",
                            (Function<AutomationRuleEvaluator<?, ?>, Comparable>) e -> e.isEnabled()
                                    ? 1
                                    : 0),
                    Arguments.of(
                            "enabled", "DESC",
                            (Function<AutomationRuleEvaluator<?, ?>, Comparable>) e -> e.isEnabled()
                                    ? 1
                                    : 0),

                    // Created at sorting
                    Arguments.of(
                            "created_at", "ASC",
                            (Function<AutomationRuleEvaluator<?, ?>, Comparable>) e -> e.getCreatedAt()
                                    .toEpochMilli()),
                    Arguments.of(
                            "created_at", "DESC",
                            (Function<AutomationRuleEvaluator<?, ?>, Comparable>) e -> e.getCreatedAt()
                                    .toEpochMilli()),

                    // Last updated at sorting
                    Arguments.of(
                            "last_updated_at", "ASC",
                            (Function<AutomationRuleEvaluator<?, ?>, Comparable>) e -> e
                                    .getLastUpdatedAt().toEpochMilli()),
                    Arguments.of(
                            "last_updated_at", "DESC",
                            (Function<AutomationRuleEvaluator<?, ?>, Comparable>) e -> e
                                    .getLastUpdatedAt().toEpochMilli()),

                    // ID sorting
                    Arguments.of(
                            "id", "ASC",
                            (Function<AutomationRuleEvaluator<?, ?>, Comparable>) e -> e.getId()
                                    .toString()),
                    Arguments.of(
                            "id", "DESC",
                            (Function<AutomationRuleEvaluator<?, ?>, Comparable>) e -> e.getId()
                                    .toString()),

                    // Project ID sorting
                    Arguments.of(
                            "project_id", "ASC",
                            (Function<AutomationRuleEvaluator<?, ?>, Comparable>) e -> e.getProjects().stream()
                                    .map(ProjectReference::projectId).collect(Collectors.toSet())
                                    .toString()),
                    Arguments.of(
                            "project_id", "DESC",
                            (Function<AutomationRuleEvaluator<?, ?>, Comparable>) e -> e.getProjects().stream()
                                    .map(ProjectReference::projectId).collect(Collectors.toSet())
                                    .toString()),

                    // Created by sorting
                    Arguments.of(
                            "created_by", "ASC",
                            (Function<AutomationRuleEvaluator<?, ?>, Comparable>) e -> e
                                    .getCreatedBy()),
                    Arguments.of(
                            "created_by", "DESC",
                            (Function<AutomationRuleEvaluator<?, ?>, Comparable>) e -> e
                                    .getCreatedBy()),

                    // Last updated by sorting
                    Arguments.of(
                            "last_updated_by", "ASC",
                            (Function<AutomationRuleEvaluator<?, ?>, Comparable>) e -> e
                                    .getLastUpdatedBy()),
                    Arguments.of(
                            "last_updated_by", "DESC",
                            (Function<AutomationRuleEvaluator<?, ?>, Comparable>) e -> e
                                    .getLastUpdatedBy()));
        }

        @Test
        @DisplayName("Verify sortable_by field is returned")
        void verifySortableByField() {
            // Given
            var projectId = projectResourceClient.createProject(UUID.randomUUID().toString(), API_KEY, WORKSPACE_NAME);

            var evaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                    .projectIds(Set.of(projectId))
                    .build();
            evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);

            // When
            var page = evaluatorsResourceClient.findEvaluatorPage(
                    projectId, null, null, null, 1, 10, WORKSPACE_NAME, API_KEY);

            // Then
            assertThat(page.sortableBy()).isNotNull();
            assertThat(page.sortableBy()).contains(
                    "id", "name", "type", "enabled", "sampling_rate",
                    // Note: project_id and project_name removed - rules can have multiple projects now
                    "created_at", "last_updated_at", "created_by", "last_updated_by");
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("List Filtering Functionality")
    class ListFilteringFunctionality {

        @ParameterizedTest(name = "Filter by {0} with operator {1}")
        @MethodSource("filteringTestCases")
        @DisplayName("Filter automation rules by various fields")
        void filterByField(String fieldName, String operator, String description)
                throws JsonProcessingException, InterruptedException {
            // Given
            var projectId = projectResourceClient.createProject(UUID.randomUUID().toString(), API_KEY, WORKSPACE_NAME);

            final String filterValue;
            final UUID expectedId;

            if (fieldName.equals("id")) {
                // Test ID filtering
                var evaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                        .projectIds(Set.of(projectId))
                        .build();
                var id = evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);
                filterValue = id.toString();
                expectedId = id;

            } else if (fieldName.equals("name")) {
                // Test Name filtering
                var uniqueName = "unique-name-" + RandomStringUtils.secure().nextAlphanumeric(10);
                var evaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                        .name(uniqueName)
                        .projectIds(Set.of(projectId))
                        .build();
                expectedId = evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);
                filterValue = "unique-name-";

            } else if (fieldName.equals("created_by")) {
                // Test Created By filtering
                var evaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                        .projectIds(Set.of(projectId))
                        .build();
                evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);
                filterValue = "test";
                expectedId = null;

            } else if (fieldName.equals("created_at")) {
                // Test Created At filtering
                var evaluator1 = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                        .projectIds(Set.of(projectId))
                        .build();
                evaluatorsResourceClient.createEvaluator(evaluator1, WORKSPACE_NAME, API_KEY);

                var timestamp = Instant.now();
                Thread.sleep(1000);

                var evaluator2 = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                        .projectIds(Set.of(projectId))
                        .build();
                expectedId = evaluatorsResourceClient.createEvaluator(evaluator2, WORKSPACE_NAME, API_KEY);
                filterValue = timestamp.toString();

            } else { // last_updated_at
                // Test Last Updated At filtering
                var timestamp = Instant.now().minusSeconds(10);
                var evaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                        .projectIds(Set.of(projectId))
                        .build();
                expectedId = evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);
                filterValue = timestamp.toString();
            }

            // When
            var filterFields = List.of(Map.of("field", fieldName, "operator", operator, "value", filterValue));
            String filters = OBJECT_MAPPER.writeValueAsString(filterFields);
            var page = evaluatorsResourceClient.findEvaluatorPage(
                    projectId, null, filters, null, 1, 10, WORKSPACE_NAME, API_KEY);

            // Then
            assertThat(page).isNotNull();
            assertThat(page.content()).isNotNull();

            if (fieldName.equals("id") && operator.equals("=")) {
                assertThat(page.content()).hasSize(1);
                assertThat(page.content().get(0).getId()).isEqualTo(expectedId);
            } else if (expectedId != null && !fieldName.equals("created_by")) {
                assertThat(page.content()).hasSizeGreaterThanOrEqualTo(1);
                assertThat(page.content()).anyMatch(e -> e.getId().equals(expectedId));
            } else {
                // For created_by, just verify API accepts the filter
                assertThat(page.sortableBy()).isNotNull();
            }
        }

        static Stream<Arguments> filteringTestCases() {
            return Stream.of(
                    // ID filtering
                    Arguments.of("id", "=", "Filter by exact ID"),

                    // Name filtering
                    Arguments.of("name", "contains", "Filter by name contains"),

                    // Created by filtering
                    Arguments.of("created_by", "contains",
                            "Filter by created_by contains"),

                    // Created at filtering
                    Arguments.of("created_at", ">",
                            "Filter by created_at greater than"),

                    // Last updated at filtering
                    Arguments.of("last_updated_at", ">=",
                            "Filter by last_updated_at greater than or equal"));
        }

        @org.junit.jupiter.api.Disabled("Project name filtering no longer supported - rules can have multiple projects")
        @ParameterizedTest(name = "Filter by project name with operator: {0}")
        @MethodSource("projectNameFilteringTestCases")
        @DisplayName("Filter automation rules by project name with various operators")
        void filterByProjectName(String operator, String matchingPrefix, String nonMatchingPrefix,
                Predicate<String> matchPredicate) throws JsonProcessingException {
            // Given
            var projectName1 = matchingPrefix + RandomStringUtils.secure().nextAlphanumeric(10);
            var projectName2 = nonMatchingPrefix + RandomStringUtils.secure().nextAlphanumeric(10);

            var projectId1 = projectResourceClient.createProject(projectName1, API_KEY, WORKSPACE_NAME);
            var projectId2 = projectResourceClient.createProject(projectName2, API_KEY, WORKSPACE_NAME);

            var evaluator1 = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                    .projectIds(Set.of(projectId1))
                    .build();
            var evaluator2 = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                    .projectIds(Set.of(projectId2))
                    .build();

            var id1 = evaluatorsResourceClient.createEvaluator(evaluator1, WORKSPACE_NAME, API_KEY);
            evaluatorsResourceClient.createEvaluator(evaluator2, WORKSPACE_NAME, API_KEY);

            // When - Filter by project name with given operator
            var filterValue = operator.equals("=") ? projectName1 : matchingPrefix;
            var filterFields = List.of(Map.of(
                    "field", "project_name",
                    "operator", operator,
                    "value", filterValue));
            String filters = OBJECT_MAPPER.writeValueAsString(filterFields);
            var page = evaluatorsResourceClient.findEvaluatorPage(
                    (UUID) null, null, filters, null, 1, 10, WORKSPACE_NAME, API_KEY);

            // Then - Find the matching evaluator
            assertThat(page.content()).hasSizeGreaterThanOrEqualTo(1);
            var matchingEvaluators = page.content().stream()
                    .filter(e -> e.getId().equals(id1))
                    .toList();
            assertThat(matchingEvaluators).hasSize(1);
            assertThat(matchingEvaluators.get(0).getProjects().stream().map(ProjectReference::projectId)
                    .collect(Collectors.toSet())).containsExactly(projectId1);
        }

        static Stream<Arguments> projectNameFilteringTestCases() {
            return Stream.of(
                    Arguments.of(
                            "contains",
                            "test-project-",
                            "other-project-",
                            (Predicate<String>) name -> name.contains("test-project-")),
                    Arguments.of(
                            "=",
                            "exact-match-",
                            "different-name-",
                            (Predicate<String>) name -> name.startsWith("exact-match-")),
                    Arguments.of(
                            "starts_with",
                            "prefix-",
                            "other-",
                            (Predicate<String>) name -> name.startsWith("prefix-")));
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("Pagination Functionality")
    class PaginationFunctionality {

        @ParameterizedTest(name = "Fetch page {0} with page size {1}, expecting {2} items")
        @MethodSource("paginationTestCases")
        @DisplayName("Fetch evaluators using pagination")
        void paginationTest(int pageNumber, int pageSize, int expectedItemsOnPage, String description) {
            // Given
            var projectId = projectResourceClient.createProject(UUID.randomUUID().toString(), API_KEY, WORKSPACE_NAME);

            var totalItems = 25;
            var evaluators = IntStream.range(0, totalItems)
                    .mapToObj(i -> factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                            .name("evaluator-" + i)
                            .projectIds(Set.of(projectId))
                            .build())
                    .toList();

            evaluators.forEach(ev -> evaluatorsResourceClient.createEvaluator(ev, WORKSPACE_NAME, API_KEY));

            // When - Fetch the specified page
            var page = evaluatorsResourceClient.findEvaluatorPage(
                    projectId, null, null, null, pageNumber, pageSize, WORKSPACE_NAME, API_KEY);

            // Then
            assertThat(page.page()).isEqualTo(pageNumber);
            assertThat(page.size()).isEqualTo(expectedItemsOnPage);
            assertThat(page.total()).isEqualTo(totalItems);
            assertThat(page.content()).hasSize(expectedItemsOnPage);
        }

        static Stream<Arguments> paginationTestCases() {
            return Stream.of(
                    // pageNumber, pageSize, expectedItemsOnPage, description
                    Arguments.of(1, 10, 10, "First page with 10 items"),
                    Arguments.of(2, 10, 10, "Second page with 10 items"),
                    Arguments.of(3, 10, 5, "Last page with 5 items"));
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("ProjectId Filtering Functionality")
    class ProjectIdFilteringFunctionality {

        @Test
        @DisplayName("Filter evaluators by single projectId")
        void filterBySingleProjectId() {
            // Given
            var projectId1 = projectResourceClient.createProject(UUID.randomUUID().toString(), API_KEY, WORKSPACE_NAME);
            var projectId2 = projectResourceClient.createProject(UUID.randomUUID().toString(), API_KEY, WORKSPACE_NAME);

            var evaluator1 = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                    .projectIds(Set.of(projectId1))
                    .build();
            var evaluator2 = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                    .projectIds(Set.of(projectId2))
                    .build();

            var id1 = evaluatorsResourceClient.createEvaluator(evaluator1, WORKSPACE_NAME, API_KEY);
            evaluatorsResourceClient.createEvaluator(evaluator2, WORKSPACE_NAME, API_KEY);

            // When - Filter by projectId1
            var page = evaluatorsResourceClient.findEvaluatorPage(
                    projectId1, null, null, null, 1, 10, WORKSPACE_NAME, API_KEY);

            // Then - Only evaluator1 should be returned
            assertThat(page.content()).hasSizeGreaterThanOrEqualTo(1);
            var matchingEvaluators = page.content().stream()
                    .filter(e -> e.getId().equals(id1))
                    .toList();
            assertThat(matchingEvaluators).hasSize(1);
            assertThat(matchingEvaluators.get(0).getProjects().stream().map(ProjectReference::projectId)
                    .collect(Collectors.toSet())).contains(projectId1); // projectIds is a Set, not a single value
        }

        @Test
        @DisplayName("Filter evaluators across all projects when projectId is null")
        void filterAcrossAllProjects() {
            // Given
            var projectId1 = projectResourceClient.createProject(UUID.randomUUID().toString(), API_KEY, WORKSPACE_NAME);
            var projectId2 = projectResourceClient.createProject(UUID.randomUUID().toString(), API_KEY, WORKSPACE_NAME);

            var evaluator1 = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                    .name("unique-eval-1-" + RandomStringUtils.secure().nextAlphanumeric(10))
                    .projectIds(Set.of(projectId1))
                    .build();
            var evaluator2 = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                    .name("unique-eval-2-" + RandomStringUtils.secure().nextAlphanumeric(10))
                    .projectIds(Set.of(projectId2))
                    .build();

            var id1 = evaluatorsResourceClient.createEvaluator(evaluator1, WORKSPACE_NAME, API_KEY);
            var id2 = evaluatorsResourceClient.createEvaluator(evaluator2, WORKSPACE_NAME, API_KEY);

            // When - Query all projects (null projectId)
            var page = evaluatorsResourceClient.findEvaluatorPage(
                    (UUID) null, null, null, null, 1, 100, WORKSPACE_NAME, API_KEY);

            // Then - Both evaluators should be returned
            var evaluatorIds = page.content().stream().map(AutomationRuleEvaluator::getId).toList();
            assertThat(evaluatorIds).contains(id1, id2);
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("Combined Sorting and Filtering Functionality")
    class CombinedSortingAndFilteringFunctionality {

        @org.junit.jupiter.api.Disabled("Project name filtering no longer supported - rules can have multiple projects")
        @Test
        @DisplayName("Filter by project name and sort by name ascending")
        void filterAndSort() throws JsonProcessingException {
            // Given
            var projectName = "test-project-" + RandomStringUtils.secure().nextAlphanumeric(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            var evaluators = IntStream.range(0, 5)
                    .mapToObj(i -> factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                            .name("eval-" + (char) ('e' - i) + "-" + RandomStringUtils.secure().nextAlphanumeric(5)) // e, d, c, b, a
                            .projectIds(Set.of(projectId))
                            .build())
                    .toList();

            evaluators.forEach(ev -> evaluatorsResourceClient.createEvaluator(ev, WORKSPACE_NAME, API_KEY));

            // When - Filter by project name and sort by name ASC
            var filterFields = List.of(Map.of(
                    "field", "project_name",
                    "operator", "contains",
                    "value", "test-project"));
            String filters = OBJECT_MAPPER.writeValueAsString(filterFields);

            var sortingFields = List.of(Map.of("field", "name", "direction", "ASC"));
            String sorting = OBJECT_MAPPER.writeValueAsString(sortingFields);

            var page = evaluatorsResourceClient.findEvaluatorPage(
                    (UUID) null, null, filters, sorting, 1, 10, WORKSPACE_NAME, API_KEY);

            // Then - Results should be filtered and sorted
            assertThat(page.content()).hasSizeGreaterThanOrEqualTo(5);
            var matchingEvaluators = page.content().stream()
                    .filter(e -> evaluators.stream().anyMatch(ev -> ev.getName().equals(e.getName())))
                    .limit(5)
                    .toList();

            assertThat(matchingEvaluators).hasSize(5);
            // Verify they're sorted by name (should be alphabetically: eval-a, eval-b, eval-c, eval-d, eval-e)
            for (int i = 0; i < matchingEvaluators.size() - 1; i++) {
                assertThat(matchingEvaluators.get(i).getName())
                        .isLessThan(matchingEvaluators.get(i + 1).getName());
            }
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("Filter Functionality")
    class FilterFunctionality {

        @Test
        void createAndGetEvaluatorWithFilters() {
            // Given
            var projectId = projectResourceClient.createProject(UUID.randomUUID().toString(), API_KEY, WORKSPACE_NAME);
            List<TraceFilter> filters = List.of(
                    TraceFilter.builder()
                            .field(TraceField.NAME)
                            .operator(Operator.CONTAINS)
                            .value("test")
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.TOTAL_ESTIMATED_COST)
                            .operator(Operator.GREATER_THAN)
                            .value("0.01")
                            .build());

            var automationRuleEvaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class)
                    .toBuilder()
                    .filters(filters)
                    .projectIds(Set.of(projectId))
                    .build();

            // When
            var id = evaluatorsResourceClient.createEvaluator(automationRuleEvaluator, WORKSPACE_NAME, API_KEY);

            // Then
            try (var actualResponse = evaluatorsResourceClient.getEvaluator(
                    id, null, WORKSPACE_NAME, API_KEY, HttpStatus.SC_OK)) {

                AutomationRuleEvaluator<AutomationRuleEvaluatorLlmAsJudge, TraceFilter> actualAutomationRuleEvaluator = actualResponse
                        .readEntity(AutomationRuleEvaluator.class);
                assertThat(actualAutomationRuleEvaluator.getFilters()).hasSize(2);
                assertThat(actualAutomationRuleEvaluator.getFilters())
                        .usingRecursiveFieldByFieldElementComparator()
                        .containsExactlyElementsOf(filters);
            }
        }

        @Test
        void createEvaluatorWithEmptyFilters() {
            // Given
            var projectId = projectResourceClient.createProject(UUID.randomUUID().toString(), API_KEY, WORKSPACE_NAME);
            List<TraceFilter> emptyFilters = List.of();
            @SuppressWarnings("unchecked")
            var automationRuleEvaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class)
                    .toBuilder()
                    .filters(emptyFilters)
                    .projectIds(Set.of(projectId))
                    .build();

            // When
            var id = evaluatorsResourceClient.createEvaluator(automationRuleEvaluator, WORKSPACE_NAME, API_KEY);

            // Then
            try (var actualResponse = evaluatorsResourceClient.getEvaluator(
                    id, null, WORKSPACE_NAME, API_KEY, HttpStatus.SC_OK)) {

                var actualAutomationRuleEvaluator = actualResponse.readEntity(AutomationRuleEvaluator.class);
                assertThat(actualAutomationRuleEvaluator.getFilters()).isEmpty();
            }
        }

        @Test
        void updateEvaluatorWithFilters() {
            // Given
            var projectId = projectResourceClient.createProject(UUID.randomUUID().toString(), API_KEY, WORKSPACE_NAME);
            List<TraceFilter> initialFilters = List.of(
                    TraceFilter.builder()
                            .field(TraceField.NAME)
                            .operator(Operator.EQUAL)
                            .value("old_name")
                            .build());

            @SuppressWarnings("unchecked")
            AutomationRuleEvaluatorLlmAsJudge automationRuleEvaluator = factory
                    .manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class)
                    .toBuilder()
                    .filters(initialFilters)
                    .projectIds(Set.of(projectId))
                    .build();
            UUID id = evaluatorsResourceClient.createEvaluator(automationRuleEvaluator, WORKSPACE_NAME, API_KEY);

            List<TraceFilter> updatedFilters = List.of(
                    TraceFilter.builder()
                            .field(TraceField.NAME)
                            .operator(Operator.CONTAINS)
                            .value("new_name")
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.TAGS)
                            .operator(Operator.CONTAINS)
                            .value("production")
                            .build());

            @SuppressWarnings("unchecked")
            AutomationRuleEvaluatorUpdateLlmAsJudge updateRequest = factory
                    .manufacturePojo(AutomationRuleEvaluatorUpdateLlmAsJudge.class)
                    .toBuilder()
                    .projectIds(Set.of(projectId))
                    .filters(updatedFilters)
                    .build();

            // When
            try (var actualResponse = evaluatorsResourceClient.updateEvaluator(
                    id, WORKSPACE_NAME, updateRequest, API_KEY, HttpStatus.SC_NO_CONTENT)) {
                assertThat(actualResponse.hasEntity()).isFalse();
            }

            // Then
            try (var actualResponse = evaluatorsResourceClient.getEvaluator(
                    id, null, WORKSPACE_NAME, API_KEY, HttpStatus.SC_OK)) {

                var actualAutomationRuleEvaluator = actualResponse.readEntity(AutomationRuleEvaluator.class);
                assertThat(actualAutomationRuleEvaluator.getFilters()).hasSize(2);
                assertThat(actualAutomationRuleEvaluator.getFilters())
                        .usingRecursiveFieldByFieldElementComparator()
                        .containsExactlyElementsOf(updatedFilters);
            }
        }

        Stream<Arguments> evaluatorTypesWithFilters() {
            return Stream.of(
                    Arguments.of(
                            AutomationRuleEvaluatorLlmAsJudge.class,
                            List.of(
                                    TraceFilter.builder()
                                            .field(TraceField.NAME)
                                            .operator(Operator.CONTAINS)
                                            .value("test")
                                            .build(),
                                    TraceFilter.builder()
                                            .field(TraceField.FEEDBACK_SCORES)
                                            .key("relevance")
                                            .operator(Operator.GREATER_THAN)
                                            .value("0.8")
                                            .build())),
                    Arguments.of(
                            AutomationRuleEvaluatorUserDefinedMetricPython.class,
                            List.of(
                                    TraceFilter.builder()
                                            .field(TraceField.NAME)
                                            .operator(Operator.CONTAINS)
                                            .value("test")
                                            .build(),
                                    TraceFilter.builder()
                                            .field(TraceField.FEEDBACK_SCORES)
                                            .key("relevance")
                                            .operator(Operator.GREATER_THAN)
                                            .value("0.8")
                                            .build())),
                    Arguments.of(
                            AutomationRuleEvaluatorTraceThreadLlmAsJudge.class,
                            List.of(
                                    TraceThreadFilter.builder()
                                            .field(TraceThreadField.FIRST_MESSAGE)
                                            .operator(Operator.CONTAINS)
                                            .value("test")
                                            .build(),
                                    TraceThreadFilter.builder()
                                            .field(TraceThreadField.FEEDBACK_SCORES)
                                            .key("relevance")
                                            .operator(Operator.GREATER_THAN)
                                            .value("0.8")
                                            .build())),
                    Arguments.of(
                            AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.class,
                            List.of(
                                    TraceThreadFilter.builder()
                                            .field(TraceThreadField.FIRST_MESSAGE)
                                            .operator(Operator.CONTAINS)
                                            .value("test")
                                            .build(),
                                    TraceThreadFilter.builder()
                                            .field(TraceThreadField.FEEDBACK_SCORES)
                                            .key("relevance")
                                            .operator(Operator.GREATER_THAN)
                                            .value("0.8")
                                            .build())),
                    Arguments.of(
                            AutomationRuleEvaluatorSpanLlmAsJudge.class,
                            List.of(
                                    SpanFilter.builder()
                                            .field(SpanField.NAME)
                                            .operator(Operator.CONTAINS)
                                            .value("test")
                                            .build(),
                                    SpanFilter.builder()
                                            .field(SpanField.FEEDBACK_SCORES)
                                            .key("relevance")
                                            .operator(Operator.GREATER_THAN)
                                            .value("0.8")
                                            .build())));
        }

        @ParameterizedTest
        @MethodSource("evaluatorTypesWithFilters")
        void createEvaluatorWithFiltersForAllTypes(Class<? extends AutomationRuleEvaluator<?, ?>> evaluatorClass,
                List<?> filters) {
            // Given
            var projectId = projectResourceClient.createProject(UUID.randomUUID().toString(), API_KEY, WORKSPACE_NAME);
            AutomationRuleEvaluator<?, ?> automationRuleEvaluator = factory
                    .manufacturePojo(evaluatorClass)
                    .toBuilder()
                    .filters((List) filters)
                    .projectIds(Set.of(projectId))
                    .build();

            // When
            UUID id = evaluatorsResourceClient.createEvaluator(automationRuleEvaluator, WORKSPACE_NAME, API_KEY);

            // Then
            try (var actualResponse = evaluatorsResourceClient.getEvaluator(
                    id, null, WORKSPACE_NAME, API_KEY, HttpStatus.SC_OK)) {

                var actualAutomationRuleEvaluator = actualResponse.readEntity(AutomationRuleEvaluator.class);
                assertThat(actualAutomationRuleEvaluator.getFilters()).hasSize(2);
                assertThat(actualAutomationRuleEvaluator.getFilters())
                        .usingRecursiveFieldByFieldElementComparator()
                        .containsExactlyElementsOf(filters);
            }
        }

        @Test
        void createEvaluatorWithComplexFilters() {
            // Given
            var projectId = projectResourceClient.createProject(UUID.randomUUID().toString(), API_KEY, WORKSPACE_NAME);
            List<TraceFilter> complexFilters = List.of(
                    TraceFilter.builder()
                            .field(TraceField.INPUT)
                            .key("nested.query")
                            .operator(Operator.CONTAINS)
                            .value("machine learning")
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.METADATA)
                            .key("llm_config.model")
                            .operator(Operator.EQUAL)
                            .value("gpt-4")
                            .build(),
                    TraceFilter.builder()
                            .field(TraceField.USAGE_TOTAL_TOKENS)
                            .operator(Operator.LESS_THAN)
                            .value("1000")
                            .build());

            @SuppressWarnings("unchecked")
            AutomationRuleEvaluatorLlmAsJudge automationRuleEvaluator = factory
                    .manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class)
                    .toBuilder()
                    .filters(complexFilters)
                    .projectIds(Set.of(projectId))
                    .build();

            // When
            UUID id = evaluatorsResourceClient.createEvaluator(automationRuleEvaluator, WORKSPACE_NAME, API_KEY);

            // Then
            try (var actualResponse = evaluatorsResourceClient.getEvaluator(
                    id, null, WORKSPACE_NAME, API_KEY, HttpStatus.SC_OK)) {

                var actualAutomationRuleEvaluator = actualResponse.readEntity(AutomationRuleEvaluator.class);
                assertThat(actualAutomationRuleEvaluator.getFilters()).hasSize(3);
                assertThat(actualAutomationRuleEvaluator.getFilters())
                        .usingRecursiveFieldByFieldElementComparator()
                        .containsExactlyElementsOf(complexFilters);
            }
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("Multi-Project Automation Rules:")
    class MultiProjectAutomationRules {

        private UUID projectId1;
        private UUID projectId2;
        private UUID projectId3;

        @BeforeAll
        void setUp() {
            // Create test projects and capture their IDs
            projectId1 = projectResourceClient.createProject(UUID.randomUUID().toString(), API_KEY, WORKSPACE_NAME);
            projectId2 = projectResourceClient.createProject(UUID.randomUUID().toString(), API_KEY, WORKSPACE_NAME);
            projectId3 = projectResourceClient.createProject(UUID.randomUUID().toString(), API_KEY, WORKSPACE_NAME);
        }

        @Test
        @DisplayName("should create automation rule with multiple projects")
        void shouldCreateAutomationRuleWithMultipleProjects(LlmProviderFactory llmProviderFactory) {
            // Given
            var chatResponse = ChatResponse.builder().aiMessage(AiMessage.from(VALID_AI_MSG_TXT)).build();
            when(llmProviderFactory.getLanguageModel(anyString(), any())
                    .chat(any(ChatRequest.class)))
                    .thenAnswer(invocationOnMock -> chatResponse);

            AutomationRuleEvaluatorLlmAsJudge evaluator = factory
                    .manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class)
                    .toBuilder()
                    .projectIds(Set.of(projectId1, projectId2)) // Already UUIDs, no conversion needed
                    .build();

            // When
            UUID ruleId = evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);

            // Then
            try (var actualResponse = evaluatorsResourceClient.getEvaluator(
                    ruleId, null, WORKSPACE_NAME, API_KEY, HttpStatus.SC_OK)) {

                AutomationRuleEvaluator<?, ?> actualEvaluator = actualResponse
                        .readEntity(AutomationRuleEvaluator.class);
                assertThat(actualEvaluator.getProjects().stream().map(ProjectReference::projectId)
                        .collect(Collectors.toSet())).hasSize(2);
                assertThat(actualEvaluator.getProjects().stream().map(ProjectReference::projectId)
                        .collect(Collectors.toSet()))
                        .containsExactlyInAnyOrder(projectId1, projectId2); // Already UUIDs
            }
        }

        @Test
        @DisplayName("should update automation rule to different set of projects")
        void shouldUpdateAutomationRuleToDifferentSetOfProjects(LlmProviderFactory llmProviderFactory) {
            // Given - Create rule with 2 projects
            var chatResponse = ChatResponse.builder().aiMessage(AiMessage.from(VALID_AI_MSG_TXT)).build();
            when(llmProviderFactory.getLanguageModel(anyString(), any())
                    .chat(any(ChatRequest.class)))
                    .thenAnswer(invocationOnMock -> chatResponse);

            AutomationRuleEvaluatorLlmAsJudge evaluator = factory
                    .manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class)
                    .toBuilder()
                    .projectIds(Set.of(projectId1, projectId2))
                    .build();

            UUID ruleId = evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);

            // When - Update to 3 different projects
            AutomationRuleEvaluatorUpdateLlmAsJudge update = AutomationRuleEvaluatorUpdateLlmAsJudge.builder()
                    .name("Updated Rule")
                    .samplingRate(0.5f)
                    .enabled(true)
                    .filters(List.of())
                    .projectIds(Set.of(projectId2, projectId3))
                    .code(evaluator.getCode())
                    .build();

            evaluatorsResourceClient.updateEvaluator(ruleId, WORKSPACE_NAME, update, API_KEY, HttpStatus.SC_NO_CONTENT);

            // Then
            try (var actualResponse = evaluatorsResourceClient.getEvaluator(
                    ruleId, null, WORKSPACE_NAME, API_KEY, HttpStatus.SC_OK)) {

                AutomationRuleEvaluator<?, ?> actualEvaluator = actualResponse
                        .readEntity(AutomationRuleEvaluator.class);
                assertThat(actualEvaluator.getProjects().stream().map(ProjectReference::projectId)
                        .collect(Collectors.toSet())).hasSize(2);
                assertThat(actualEvaluator.getProjects().stream().map(ProjectReference::projectId)
                        .collect(Collectors.toSet()))
                        .containsExactlyInAnyOrder(projectId2, projectId3);
                assertThat(actualEvaluator.getName()).isEqualTo("Updated Rule");
            }
        }

        @Test
        @DisplayName("should allow same projects for different rules")
        void shouldAllowSameProjectsForDifferentRules(LlmProviderFactory llmProviderFactory) {
            // Given - Same project set for both rules
            var chatResponse = ChatResponse.builder().aiMessage(AiMessage.from(VALID_AI_MSG_TXT)).build();
            when(llmProviderFactory.getLanguageModel(anyString(), any())
                    .chat(any(ChatRequest.class)))
                    .thenAnswer(invocationOnMock -> chatResponse);

            Set<UUID> sharedProjects = Set.of(projectId1, projectId2);

            AutomationRuleEvaluatorLlmAsJudge evaluator1 = factory
                    .manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class)
                    .toBuilder()
                    .name("Rule 1")
                    .projectIds(sharedProjects)
                    .build();

            AutomationRuleEvaluatorLlmAsJudge evaluator2 = factory
                    .manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class)
                    .toBuilder()
                    .name("Rule 2")
                    .projectIds(sharedProjects)
                    .build();

            // When - Create both rules
            UUID ruleId1 = evaluatorsResourceClient.createEvaluator(evaluator1, WORKSPACE_NAME, API_KEY);
            UUID ruleId2 = evaluatorsResourceClient.createEvaluator(evaluator2, WORKSPACE_NAME, API_KEY);

            // Then - Both rules should be created successfully
            try (var response1 = evaluatorsResourceClient.getEvaluator(
                    ruleId1, null, WORKSPACE_NAME, API_KEY, HttpStatus.SC_OK)) {

                AutomationRuleEvaluator<?, ?> rule1 = response1.readEntity(AutomationRuleEvaluator.class);
                assertThat(rule1.getProjects().stream()
                        .map(ProjectReference::projectId)
                        .collect(Collectors.toSet()))
                        .containsExactlyInAnyOrderElementsOf(sharedProjects);
            }

            try (var response2 = evaluatorsResourceClient.getEvaluator(
                    ruleId2, null, WORKSPACE_NAME, API_KEY, HttpStatus.SC_OK)) {

                AutomationRuleEvaluator<?, ?> rule2 = response2.readEntity(AutomationRuleEvaluator.class);
                assertThat(rule2.getProjects().stream()
                        .map(ProjectReference::projectId)
                        .collect(Collectors.toSet()))
                        .containsExactlyInAnyOrderElementsOf(sharedProjects);
            }
        }

        @Test
        @DisplayName("should delete rule and remove all project associations")
        void shouldDeleteRuleAndRemoveAllProjectAssociations(LlmProviderFactory llmProviderFactory) {
            // Given - Create rule with multiple projects
            var chatResponse = ChatResponse.builder().aiMessage(AiMessage.from(VALID_AI_MSG_TXT)).build();
            when(llmProviderFactory.getLanguageModel(anyString(), any())
                    .chat(any(ChatRequest.class)))
                    .thenAnswer(invocationOnMock -> chatResponse);

            AutomationRuleEvaluatorLlmAsJudge evaluator = factory
                    .manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class)
                    .toBuilder()
                    .projectIds(Set.of(
                            projectId1,
                            projectId2,
                            projectId3))
                    .build();

            UUID ruleId = evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);

            // When - Delete the rule
            evaluatorsResourceClient.delete(null, WORKSPACE_NAME, API_KEY,
                    BatchDelete.builder().ids(Set.of(ruleId)).build(), HttpStatus.SC_NO_CONTENT);

            // Then - Rule should be deleted
            evaluatorsResourceClient.getEvaluator(ruleId, null, WORKSPACE_NAME, API_KEY, HttpStatus.SC_NOT_FOUND);
        }

        @Test
        @DisplayName("should handle single project case")
        void shouldHandleSingleProjectCase(LlmProviderFactory llmProviderFactory) {
            // Given
            var chatResponse = ChatResponse.builder().aiMessage(AiMessage.from(VALID_AI_MSG_TXT)).build();
            when(llmProviderFactory.getLanguageModel(anyString(), any())
                    .chat(any(ChatRequest.class)))
                    .thenAnswer(invocationOnMock -> chatResponse);

            AutomationRuleEvaluatorLlmAsJudge evaluator = factory
                    .manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class)
                    .toBuilder()
                    .projectIds(Set.of(projectId1))
                    .build();

            // When
            UUID ruleId = evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);

            // Then
            try (var actualResponse = evaluatorsResourceClient.getEvaluator(
                    ruleId, null, WORKSPACE_NAME, API_KEY, HttpStatus.SC_OK)) {

                AutomationRuleEvaluator<?, ?> actualEvaluator = actualResponse
                        .readEntity(AutomationRuleEvaluator.class);
                assertThat(actualEvaluator.getProjects().stream()
                        .map(ProjectReference::projectId)
                        .collect(Collectors.toSet())).hasSize(1);
                assertThat(actualEvaluator.getProjects().stream()
                        .map(ProjectReference::projectId)
                        .collect(Collectors.toSet())).containsExactly(projectId1);
            }
        }
    }
}
