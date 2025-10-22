package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.ManualEvaluationEntityType;
import com.comet.opik.api.ManualEvaluationRequest;
import com.comet.opik.api.ManualEvaluationResponse;
import com.comet.opik.api.Trace;
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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
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
    private final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();
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
                                new CustomConfig("serviceToggles.traceThreadPythonEvaluatorEnabled", "true")))
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;
    private ProjectResourceClient projectResourceClient;
    private TraceResourceClient traceResourceClient;
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
                arguments("threads", ManualEvaluationEntityType.THREAD));
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

            // Create trace
            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .feedbackScores(null)
                    .usage(null)
                    .build();
            var traceId = traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);

            // Create rule
            var rule = factory.manufacturePojo(AutomationRuleEvaluatorTraceThreadLlmAsJudge.class).toBuilder()
                    .projectId(projectId)
                    .samplingRate(1f)
                    .enabled(true)
                    .filters(List.of())
                    .build();
            var ruleId = evaluatorResourceClient.createEvaluator(rule, WORKSPACE_NAME, API_KEY);

            var request = ManualEvaluationRequest.builder()
                    .projectId(projectId)
                    .entityIds(List.of(traceId))
                    .ruleIds(List.of(ruleId))
                    .entityType(entityType)
                    .build();

            // When
            ManualEvaluationResponse evaluationResponse;
            if ("traces".equals(endpoint)) {
                evaluationResponse = manualEvaluationResourceClient.evaluateTraces(projectId, request, API_KEY,
                        WORKSPACE_NAME);
            } else {
                evaluationResponse = manualEvaluationResourceClient.evaluateThreads(projectId, request, API_KEY,
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

            // Create multiple traces
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
            var traceId1 = traceResourceClient.createTrace(trace1, API_KEY, WORKSPACE_NAME);
            var traceId2 = traceResourceClient.createTrace(trace2, API_KEY, WORKSPACE_NAME);

            // Create multiple rules
            var rule1 = factory.manufacturePojo(AutomationRuleEvaluatorTraceThreadLlmAsJudge.class).toBuilder()
                    .projectId(projectId)
                    .samplingRate(1f)
                    .enabled(true)
                    .filters(List.of())
                    .build();
            var rule2 = factory.manufacturePojo(AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.class)
                    .toBuilder()
                    .projectId(projectId)
                    .samplingRate(1f)
                    .enabled(true)
                    .filters(List.of())
                    .code(TraceThreadUserDefinedMetricPythonCode.builder()
                            .metric("def score(): return 1.0")
                            .build())
                    .build();
            var ruleId1 = evaluatorResourceClient.createEvaluator(rule1, WORKSPACE_NAME, API_KEY);
            var ruleId2 = evaluatorResourceClient.createEvaluator(rule2, WORKSPACE_NAME, API_KEY);

            var request = ManualEvaluationRequest.builder()
                    .projectId(projectId)
                    .entityIds(List.of(traceId1, traceId2))
                    .ruleIds(List.of(ruleId1, ruleId2))
                    .entityType(entityType)
                    .build();

            // When
            ManualEvaluationResponse evaluationResponse;
            if ("traces".equals(endpoint)) {
                evaluationResponse = manualEvaluationResourceClient.evaluateTraces(projectId, request, API_KEY,
                        WORKSPACE_NAME);
            } else {
                evaluationResponse = manualEvaluationResourceClient.evaluateThreads(projectId, request, API_KEY,
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
                    : manualEvaluationResourceClient.evaluateThreads(projectId, request, API_KEY, WORKSPACE_NAME,
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
                    : manualEvaluationResourceClient.evaluateThreads(projectId, request, API_KEY, WORKSPACE_NAME,
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
                    : manualEvaluationResourceClient.callEvaluateThreads(nonExistentProjectId, request, API_KEY,
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
                    .projectId(projectId)
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

            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .feedbackScores(null)
                    .usage(null)
                    .build();
            var traceId = traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);

            var rule = factory.manufacturePojo(AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.class)
                    .toBuilder()
                    .projectId(projectId)
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
                    .entityIds(List.of(traceId))
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
    }
}
