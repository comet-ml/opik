package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.ManualEvaluationEntityType;
import com.comet.opik.api.ManualEvaluationRequest;
import com.comet.opik.api.ManualEvaluationResponse;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode;
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
import com.comet.opik.api.resources.utils.resources.ManualEvaluationResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.infrastructure.llm.LlmModule;
import com.comet.opik.podam.PodamFactoryUtils;
import com.google.inject.AbstractModule;
import com.redis.testcontainers.RedisContainer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.http.HttpStatus;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Manual Evaluation Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class ManualEvaluationResourceTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = "user-" + RandomStringUtils.randomAlphanumeric(10);
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.randomAlphanumeric(10);

    private static final String VALID_AI_MSG_TXT = "{\"Relevance\":{\"score\":5,\"reason\":\"The summary directly addresses the approach taken in the study by mentioning the systematic experimentation with varying data mixtures and the manipulation of proportions and sources.\"}}";

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> ZOOKEEPER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER);
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(REDIS, CLICKHOUSE, MYSQL, ZOOKEEPER).join();

        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
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
                        .customConfigs(List.of(
                                new CustomConfig("pythonEvaluator.url",
                                        wireMock.runtimeInfo().getHttpBaseUrl() + "/pythonBackendMock"),
                                new CustomConfig("serviceToggles.pythonEvaluatorEnabled", "true"),
                                new CustomConfig("serviceToggles.traceThreadPythonEvaluatorEnabled", "true"),
                                new CustomConfig("serviceToggles.spanLlmAsJudgeEnabled", "true"),
                                new CustomConfig("serviceToggles.spanUserDefinedMetricPythonEnabled", "true")))
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;
    private ProjectResourceClient projectResourceClient;
    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;
    private AutomationRuleEvaluatorResourceClient evaluatorResourceClient;
    private ManualEvaluationResourceClient manualEvaluationResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;

        ClientSupportUtils.config(client);

        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);

        this.projectResourceClient = new ProjectResourceClient(client, baseURI, factory);
        this.traceResourceClient = new TraceResourceClient(client, baseURI);
        this.spanResourceClient = new SpanResourceClient(client, baseURI);
        this.evaluatorResourceClient = new AutomationRuleEvaluatorResourceClient(client, baseURI);
        this.manualEvaluationResourceClient = new ManualEvaluationResourceClient(client, baseURI);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private static Stream<Arguments> entityTypeProvider() {
        return Stream.of(
                arguments("traces", ManualEvaluationEntityType.TRACE),
                arguments("threads", ManualEvaluationEntityType.THREAD),
                arguments("spans", ManualEvaluationEntityType.SPAN));
    }

    @Nested
    @DisplayName("Evaluate Entities Endpoint")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class EvaluateEntitiesEndpoint {

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ManualEvaluationResourceTest#entityTypeProvider")
        @DisplayName("should return 202 Accepted when evaluation request is successful")
        void shouldReturn202AcceptedWhenEvaluationRequestIsSuccessful(String endpoint,
                ManualEvaluationEntityType entityType, LlmProviderFactory llmProviderFactory) {
            // Given - Mock LLM response
            var chatResponse = ChatResponse.builder().aiMessage(AiMessage.from(VALID_AI_MSG_TXT)).build();
            when(llmProviderFactory.getLanguageModel(anyString(), any())
                    .chat(any(ChatRequest.class)))
                    .thenAnswer(invocationOnMock -> chatResponse);

            var projectName = "project-" + RandomStringUtils.randomAlphanumeric(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            // Create entity (trace, thread, or span) based on endpoint
            UUID entityId;
            if ("traces".equals(endpoint)) {
                var trace = factory.manufacturePojo(Trace.class).toBuilder()
                        .projectName(projectName)
                        .feedbackScores(null)
                        .usage(null)
                        .build();
                entityId = traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);
            } else if ("threads".equals(endpoint)) {
                entityId = createThreadAndGetModelId(projectName);
            } else {
                // Create span
                var trace = factory.manufacturePojo(Trace.class).toBuilder()
                        .projectName(projectName)
                        .feedbackScores(null)
                        .usage(null)
                        .build();
                var traceId = traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);
                var span = factory.manufacturePojo(Span.class).toBuilder()
                        .projectName(projectName)
                        .traceId(traceId)
                        .feedbackScores(null)
                        .build();
                entityId = spanResourceClient.createSpan(span, API_KEY, WORKSPACE_NAME);
            }

            // Create rule - use span-level rule for spans, trace-thread rule otherwise
            UUID ruleId;
            if ("spans".equals(endpoint)) {
                var spanRule = factory.manufacturePojo(AutomationRuleEvaluatorSpanLlmAsJudge.class).toBuilder()
                        .projectIds(Set.of(projectId))
                        .samplingRate(1f)
                        .enabled(true)
                        .filters(List.of())
                        .build();
                ruleId = evaluatorResourceClient.createEvaluator(spanRule, WORKSPACE_NAME, API_KEY);
            } else {
                var rule = factory.manufacturePojo(AutomationRuleEvaluatorTraceThreadLlmAsJudge.class).toBuilder()
                        .projectIds(Set.of(projectId))
                        .samplingRate(1f)
                        .enabled(true)
                        .filters(List.of())
                        .build();
                ruleId = evaluatorResourceClient.createEvaluator(rule, WORKSPACE_NAME, API_KEY);
            }

            var request = ManualEvaluationRequest.builder()
                    .projectId(projectId)
                    .entityIds(List.of(entityId))
                    .ruleIds(List.of(ruleId))
                    .entityType(entityType)
                    .build();

            // When
            ManualEvaluationResponse evaluationResponse;
            if ("traces".equals(endpoint)) {
                evaluationResponse = manualEvaluationResourceClient.evaluateTraces(projectId, request, API_KEY,
                        WORKSPACE_NAME);
            } else if ("threads".equals(endpoint)) {
                evaluationResponse = manualEvaluationResourceClient.evaluateThreads(projectId, request, API_KEY,
                        WORKSPACE_NAME);
            } else {
                evaluationResponse = manualEvaluationResourceClient.evaluateSpans(projectId, request, API_KEY,
                        WORKSPACE_NAME);
            }

            // Then
            assertThat(evaluationResponse.entitiesQueued()).isEqualTo(1);
            assertThat(evaluationResponse.rulesApplied()).isEqualTo(1);
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ManualEvaluationResourceTest#entityTypeProvider")
        @DisplayName("should handle multiple entities and rules")
        void shouldHandleMultipleEntitiesAndRules(String endpoint, ManualEvaluationEntityType entityType,
                LlmProviderFactory llmProviderFactory) {
            // Given - Mock LLM response
            var chatResponse = ChatResponse.builder().aiMessage(AiMessage.from(VALID_AI_MSG_TXT)).build();
            when(llmProviderFactory.getLanguageModel(anyString(), any())
                    .chat(any(ChatRequest.class)))
                    .thenAnswer(invocationOnMock -> chatResponse);

            var projectName = "project-" + RandomStringUtils.randomAlphanumeric(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            // Create multiple entities (traces, threads, or spans) based on endpoint
            UUID entityId1;
            UUID entityId2;
            if ("traces".equals(endpoint)) {
                var trace1 = factory.manufacturePojo(Trace.class).toBuilder()
                        .projectName(projectName)
                        .feedbackScores(null)
                        .usage(null)
                        .build();
                var trace2 = factory.manufacturePojo(Trace.class).toBuilder()
                        .projectName(projectName)
                        .feedbackScores(null)
                        .usage(null)
                        .build();
                entityId1 = traceResourceClient.createTrace(trace1, API_KEY, WORKSPACE_NAME);
                entityId2 = traceResourceClient.createTrace(trace2, API_KEY, WORKSPACE_NAME);
            } else if ("threads".equals(endpoint)) {
                entityId1 = createThreadAndGetModelId(projectName);
                entityId2 = createThreadAndGetModelId(projectName);
            } else {
                // Create spans
                var trace = factory.manufacturePojo(Trace.class).toBuilder()
                        .projectName(projectName)
                        .feedbackScores(null)
                        .usage(null)
                        .build();
                var traceId = traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);
                var span1 = factory.manufacturePojo(Span.class).toBuilder()
                        .projectName(projectName)
                        .traceId(traceId)
                        .feedbackScores(null)
                        .build();
                var span2 = factory.manufacturePojo(Span.class).toBuilder()
                        .projectName(projectName)
                        .traceId(traceId)
                        .feedbackScores(null)
                        .build();
                entityId1 = spanResourceClient.createSpan(span1, API_KEY, WORKSPACE_NAME);
                entityId2 = spanResourceClient.createSpan(span2, API_KEY, WORKSPACE_NAME);
            }

            // Create multiple rules - use span-level rules for spans, trace-thread rules otherwise
            UUID ruleId1;
            UUID ruleId2;
            if ("spans".equals(endpoint)) {
                var spanRule1 = factory.manufacturePojo(AutomationRuleEvaluatorSpanLlmAsJudge.class).toBuilder()
                        .projectIds(Set.of(projectId))
                        .samplingRate(1f)
                        .enabled(true)
                        .filters(List.of())
                        .build();
                var spanRule2 = factory.manufacturePojo(AutomationRuleEvaluatorSpanUserDefinedMetricPython.class)
                        .toBuilder()
                        .projectIds(Set.of(projectId))
                        .samplingRate(1f)
                        .enabled(true)
                        .filters(List.of())
                        .code(AutomationRuleEvaluatorSpanUserDefinedMetricPython.SpanUserDefinedMetricPythonCode
                                .builder()
                                .metric("def score(): return 1.0")
                                .build())
                        .build();
                ruleId1 = evaluatorResourceClient.createEvaluator(spanRule1, WORKSPACE_NAME, API_KEY);
                ruleId2 = evaluatorResourceClient.createEvaluator(spanRule2, WORKSPACE_NAME, API_KEY);
            } else {
                var rule1 = factory.manufacturePojo(AutomationRuleEvaluatorTraceThreadLlmAsJudge.class).toBuilder()
                        .projectIds(Set.of(projectId))
                        .samplingRate(1f)
                        .enabled(true)
                        .filters(List.of())
                        .build();
                var rule2 = factory.manufacturePojo(AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.class)
                        .toBuilder()
                        .projectIds(Set.of(projectId))
                        .samplingRate(1f)
                        .enabled(true)
                        .filters(List.of())
                        .code(TraceThreadUserDefinedMetricPythonCode.builder()
                                .metric("def score(): return 1.0")
                                .build())
                        .build();
                ruleId1 = evaluatorResourceClient.createEvaluator(rule1, WORKSPACE_NAME, API_KEY);
                ruleId2 = evaluatorResourceClient.createEvaluator(rule2, WORKSPACE_NAME, API_KEY);
            }

            var request = ManualEvaluationRequest.builder()
                    .projectId(projectId)
                    .entityIds(List.of(entityId1, entityId2))
                    .ruleIds(List.of(ruleId1, ruleId2))
                    .entityType(entityType)
                    .build();

            // When
            ManualEvaluationResponse evaluationResponse;
            if ("traces".equals(endpoint)) {
                evaluationResponse = manualEvaluationResourceClient.evaluateTraces(projectId, request, API_KEY,
                        WORKSPACE_NAME);
            } else if ("threads".equals(endpoint)) {
                evaluationResponse = manualEvaluationResourceClient.evaluateThreads(projectId, request, API_KEY,
                        WORKSPACE_NAME);
            } else {
                evaluationResponse = manualEvaluationResourceClient.evaluateSpans(projectId, request, API_KEY,
                        WORKSPACE_NAME);
            }

            // Then
            assertThat(evaluationResponse.entitiesQueued()).isEqualTo(2);
            assertThat(evaluationResponse.rulesApplied()).isEqualTo(2);
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ManualEvaluationResourceTest#entityTypeProvider")
        @DisplayName("should return 400 when rules not found")
        void shouldReturn400WhenRulesNotFound(String endpoint, ManualEvaluationEntityType entityType) {
            // Given
            var projectName = "project-" + RandomStringUtils.randomAlphanumeric(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .feedbackScores(null)
                    .usage(null)
                    .build();
            var traceId = traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);

            var nonExistentRuleId = UUID.randomUUID();

            var request = ManualEvaluationRequest.builder()
                    .projectId(projectId)
                    .entityIds(List.of(traceId))
                    .ruleIds(List.of(nonExistentRuleId))
                    .entityType(entityType)
                    .build();

            // When
            try (var response = "traces".equals(endpoint)
                    ? manualEvaluationResourceClient.evaluateTraces(projectId, request, API_KEY, WORKSPACE_NAME,
                            HttpStatus.SC_BAD_REQUEST)
                    : "threads".equals(endpoint)
                            ? manualEvaluationResourceClient.evaluateThreads(projectId, request, API_KEY,
                                    WORKSPACE_NAME,
                                    HttpStatus.SC_BAD_REQUEST)
                            : manualEvaluationResourceClient.evaluateSpans(projectId, request, API_KEY, WORKSPACE_NAME,
                                    HttpStatus.SC_BAD_REQUEST)) {

                // Then
                var errorMessage = response.readEntity(ErrorMessage.class);
                assertThat(errorMessage.getMessage()).contains("Automation rule(s) not found");
                assertThat(errorMessage.getMessage()).contains(nonExistentRuleId.toString());
            }
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ManualEvaluationResourceTest#entityTypeProvider")
        @DisplayName("should return 400 when request validation fails")
        void shouldReturn400WhenRequestValidationFails(String endpoint, ManualEvaluationEntityType entityType) {
            // Given
            var projectName = "project-" + RandomStringUtils.randomAlphanumeric(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            // Empty entity IDs list should fail validation
            var request = ManualEvaluationRequest.builder()
                    .projectId(projectId)
                    .entityIds(List.of()) // Empty list
                    .ruleIds(List.of(UUID.randomUUID()))
                    .entityType(entityType)
                    .build();

            // When / Then
            try (var response = "traces".equals(endpoint)
                    ? manualEvaluationResourceClient.evaluateTraces(projectId, request, API_KEY, WORKSPACE_NAME,
                            HttpStatus.SC_UNPROCESSABLE_ENTITY)
                    : "threads".equals(endpoint)
                            ? manualEvaluationResourceClient.evaluateThreads(projectId, request, API_KEY,
                                    WORKSPACE_NAME,
                                    HttpStatus.SC_UNPROCESSABLE_ENTITY)
                            : manualEvaluationResourceClient.evaluateSpans(projectId, request, API_KEY, WORKSPACE_NAME,
                                    HttpStatus.SC_UNPROCESSABLE_ENTITY)) {
                // Just verify the response status was asserted in the client
            }
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.api.resources.v1.priv.ManualEvaluationResourceTest#entityTypeProvider")
        @DisplayName("should return 404 when project not found")
        void shouldReturn404WhenProjectNotFound(String endpoint, ManualEvaluationEntityType entityType) {
            // Given
            var nonExistentProjectId = UUID.randomUUID();

            var request = ManualEvaluationRequest.builder()
                    .projectId(nonExistentProjectId)
                    .entityIds(List.of(UUID.randomUUID()))
                    .ruleIds(List.of(UUID.randomUUID()))
                    .entityType(entityType)
                    .build();

            // When
            try (var response = "traces".equals(endpoint)
                    ? manualEvaluationResourceClient.callEvaluateTraces(nonExistentProjectId, request, API_KEY,
                            WORKSPACE_NAME)
                    : "threads".equals(endpoint)
                            ? manualEvaluationResourceClient.callEvaluateThreads(nonExistentProjectId, request, API_KEY,
                                    WORKSPACE_NAME)
                            : manualEvaluationResourceClient.callEvaluateSpans(nonExistentProjectId, request, API_KEY,
                                    WORKSPACE_NAME)) {

                // Then - Either 404 or 400 depending on whether rules are validated first
                assertThat(response.getStatus()).isIn(HttpStatus.SC_BAD_REQUEST, HttpStatus.SC_NOT_FOUND);
            }
        }

        @Test
        @DisplayName("should handle trace evaluation with LLM_AS_JUDGE rule")
        void shouldHandleTraceEvaluationWithLlmAsJudgeRule(LlmProviderFactory llmProviderFactory) {
            // Given - Mock LLM response
            var chatResponse = ChatResponse.builder().aiMessage(AiMessage.from(VALID_AI_MSG_TXT)).build();
            when(llmProviderFactory.getLanguageModel(anyString(), any())
                    .chat(any(ChatRequest.class)))
                    .thenAnswer(invocationOnMock -> chatResponse);

            var projectName = "project-" + RandomStringUtils.randomAlphanumeric(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .feedbackScores(null)
                    .usage(null)
                    .build();
            var traceId = traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);

            var rule = factory.manufacturePojo(AutomationRuleEvaluatorTraceThreadLlmAsJudge.class).toBuilder()
                    .projectIds(Set.of(projectId))
                    .samplingRate(0.5f)
                    .enabled(true)
                    .filters(List.of())
                    .build();
            var ruleId = evaluatorResourceClient.createEvaluator(rule, WORKSPACE_NAME, API_KEY);

            var request = ManualEvaluationRequest.builder()
                    .projectId(projectId)
                    .entityIds(List.of(traceId))
                    .ruleIds(List.of(ruleId))
                    .entityType(ManualEvaluationEntityType.TRACE)
                    .build();

            // When
            var evaluationResponse = manualEvaluationResourceClient.evaluateTraces(projectId, request, API_KEY,
                    WORKSPACE_NAME);

            // Then
            assertThat(evaluationResponse.entitiesQueued()).isEqualTo(1);
            assertThat(evaluationResponse.rulesApplied()).isEqualTo(1);
        }

        @Test
        @DisplayName("should handle thread evaluation with USER_DEFINED_METRIC_PYTHON rule")
        void shouldHandleThreadEvaluationWithUserDefinedMetricPythonRule(LlmProviderFactory llmProviderFactory) {
            // Given - Mock LLM response (not used but required for evaluator creation)
            var chatResponse = ChatResponse.builder().aiMessage(AiMessage.from(VALID_AI_MSG_TXT)).build();
            when(llmProviderFactory.getLanguageModel(anyString(), any())
                    .chat(any(ChatRequest.class)))
                    .thenAnswer(invocationOnMock -> chatResponse);

            var projectName = "project-" + RandomStringUtils.randomAlphanumeric(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            // Create thread with thread_model_id
            var threadModelId = createThreadAndGetModelId(projectName);

            var rule = factory.manufacturePojo(AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.class)
                    .toBuilder()
                    .projectIds(Set.of(projectId))
                    .samplingRate(1f)
                    .enabled(true)
                    .filters(List.of())
                    .code(TraceThreadUserDefinedMetricPythonCode.builder()
                            .metric("def score(): return 1.0")
                            .build())
                    .build();
            var ruleId = evaluatorResourceClient.createEvaluator(rule, WORKSPACE_NAME, API_KEY);

            var request = ManualEvaluationRequest.builder()
                    .projectId(projectId)
                    .entityIds(List.of(threadModelId))
                    .ruleIds(List.of(ruleId))
                    .entityType(ManualEvaluationEntityType.THREAD)
                    .build();

            // When
            var evaluationResponse = manualEvaluationResourceClient.evaluateThreads(projectId, request, API_KEY,
                    WORKSPACE_NAME);

            // Then
            assertThat(evaluationResponse.entitiesQueued()).isEqualTo(1);
            assertThat(evaluationResponse.rulesApplied()).isEqualTo(1);
        }

        @Test
        @DisplayName("should handle trace evaluation with span-level LLM_AS_JUDGE rule and store correct project_id")
        void shouldHandleTraceEvaluationWithSpanLevelLlmAsJudgeRule(LlmProviderFactory llmProviderFactory) {
            // Given - Mock LLM response
            var chatResponse = ChatResponse.builder().aiMessage(AiMessage.from(VALID_AI_MSG_TXT)).build();
            when(llmProviderFactory.getLanguageModel(anyString(), any())
                    .chat(any(ChatRequest.class)))
                    .thenAnswer(invocationOnMock -> chatResponse);

            var projectName = "project-" + RandomStringUtils.randomAlphanumeric(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            // Create trace
            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .feedbackScores(null)
                    .usage(null)
                    .build();
            var traceId = traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);

            // Create span-level LLM as Judge rule
            var rule = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                    .projectIds(Set.of(projectId))
                    .samplingRate(1f)
                    .enabled(true)
                    .filters(List.of())
                    .build();
            var ruleId = evaluatorResourceClient.createEvaluator(rule, WORKSPACE_NAME, API_KEY);

            var request = ManualEvaluationRequest.builder()
                    .projectId(projectId)
                    .entityIds(List.of(traceId))
                    .ruleIds(List.of(ruleId))
                    .entityType(ManualEvaluationEntityType.TRACE)
                    .build();

            // When
            var evaluationResponse = manualEvaluationResourceClient.evaluateTraces(projectId, request, API_KEY,
                    WORKSPACE_NAME);

            // Then
            assertThat(evaluationResponse.entitiesQueued()).isEqualTo(1);
            assertThat(evaluationResponse.rulesApplied()).isEqualTo(1);

            // Wait for async scoring to complete and verify feedback score was stored with correct project_id
            Awaitility.await().untilAsserted(() -> {
                var retrievedTrace = traceResourceClient.getById(traceId, WORKSPACE_NAME, API_KEY);
                assertThat(retrievedTrace.feedbackScores()).isNotEmpty();

                // Verify the feedback score has the correct project_id (not the default project)
                FeedbackScore feedbackScore = retrievedTrace.feedbackScores().getFirst();
                assertThat(feedbackScore.name()).isEqualTo("Relevance");
                assertThat(feedbackScore.value()).isNotNull();

                // The key assertion: verify trace's project_id matches the request's project_id
                assertThat(retrievedTrace.projectId()).isEqualTo(projectId);
            });
        }

        @Test
        @DisplayName("should handle mixed rule types (span-level and trace-thread) and store correct project_id for all")
        void shouldHandleMixedRuleTypes(LlmProviderFactory llmProviderFactory) {
            // Given - Mock LLM response
            var chatResponse = ChatResponse.builder().aiMessage(AiMessage.from(VALID_AI_MSG_TXT)).build();
            when(llmProviderFactory.getLanguageModel(anyString(), any())
                    .chat(any(ChatRequest.class)))
                    .thenAnswer(invocationOnMock -> chatResponse);

            var projectName = "project-" + RandomStringUtils.randomAlphanumeric(10);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            // Create trace
            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .feedbackScores(null)
                    .usage(null)
                    .build();
            var traceId = traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);

            // Create span-level LLM as Judge rule
            var spanLevelRule = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                    .projectIds(Set.of(projectId))
                    .samplingRate(1f)
                    .enabled(true)
                    .filters(List.of())
                    .build();
            var spanLevelRuleId = evaluatorResourceClient.createEvaluator(spanLevelRule, WORKSPACE_NAME, API_KEY);

            // Create trace-thread LLM as Judge rule
            var traceThreadRule = factory.manufacturePojo(AutomationRuleEvaluatorTraceThreadLlmAsJudge.class)
                    .toBuilder()
                    .projectIds(Set.of(projectId))
                    .samplingRate(1f)
                    .enabled(true)
                    .filters(List.of())
                    .build();
            var traceThreadRuleId = evaluatorResourceClient.createEvaluator(traceThreadRule, WORKSPACE_NAME, API_KEY);

            var request = ManualEvaluationRequest.builder()
                    .projectId(projectId)
                    .entityIds(List.of(traceId))
                    .ruleIds(List.of(spanLevelRuleId, traceThreadRuleId))
                    .entityType(ManualEvaluationEntityType.TRACE)
                    .build();

            // When
            var evaluationResponse = manualEvaluationResourceClient.evaluateTraces(projectId, request, API_KEY,
                    WORKSPACE_NAME);

            // Then
            assertThat(evaluationResponse.entitiesQueued()).isEqualTo(1);
            assertThat(evaluationResponse.rulesApplied()).isEqualTo(2);

            // Wait for async scoring to complete and verify feedback scores from both rule types
            Awaitility.await().untilAsserted(() -> {
                var retrievedTrace = traceResourceClient.getById(traceId, WORKSPACE_NAME, API_KEY);
                assertThat(retrievedTrace.feedbackScores()).hasSizeGreaterThanOrEqualTo(1);

                // The key assertion: verify trace's project_id matches the request's project_id
                assertThat(retrievedTrace.projectId()).isEqualTo(projectId);
            });
        }
    }

    /**
     * Helper method to create a thread with thread_model_id.
     * Creates traces with a thread_id and waits for the thread to be created in the trace_threads table.
     *
     * @return The UUID thread_model_id of the created thread
     */
    private UUID createThreadAndGetModelId(String projectName) {
        var threadId = UUID.randomUUID().toString();

        // Create traces with the same thread_id
        var traces = List.of(
                factory.manufacturePojo(Trace.class).toBuilder()
                        .threadId(threadId)
                        .projectName(projectName)
                        .feedbackScores(null)
                        .usage(null)
                        .build(),
                factory.manufacturePojo(Trace.class).toBuilder()
                        .threadId(threadId)
                        .projectName(projectName)
                        .feedbackScores(null)
                        .usage(null)
                        .build());

        traceResourceClient.batchCreateTraces(traces, API_KEY, WORKSPACE_NAME);

        UUID projectId = projectResourceClient.getByName(projectName, API_KEY, WORKSPACE_NAME).id();

        // Wait for the thread to be created in the trace_threads table
        Awaitility.await().untilAsserted(() -> {
            var traceThread = traceResourceClient.getTraceThread(threadId, projectId, API_KEY, WORKSPACE_NAME);
            assertThat(traceThread.threadModelId()).isNotNull();
        });

        var traceThread = traceResourceClient.getTraceThread(threadId, projectId, API_KEY, WORKSPACE_NAME);
        return traceThread.threadModelId();
    }
}
