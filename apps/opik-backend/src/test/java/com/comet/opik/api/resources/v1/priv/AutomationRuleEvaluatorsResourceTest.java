package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.AuthenticationErrorResponse;
import com.comet.opik.api.AutomationRuleEvaluator;
import com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.AutomationRuleEvaluatorUpdate;
import com.comet.opik.api.BatchDelete;
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
import com.comet.opik.infrastructure.llm.LlmModule;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.google.inject.AbstractModule;
import com.redis.testcontainers.RedisContainer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
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
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
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
class AutomationRuleEvaluatorsResourceTest {

    private static final String URL_TEMPLATE = "%s/v1/private/automations/projects/%s/evaluators/";

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
            .formatted(messageToTest).trim();

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
            """.formatted(summaryStr).trim();
    private static final String output = """
            {
                "output": "%s"
            }
            """.formatted(outputStr).trim();

    private static final String validAiMsgTxt = "{\"Relevance\":{\"score\":5,\"reason\":\"The summary directly addresses the approach taken in the study by mentioning the systematic experimentation with varying data mixtures and the manipulation of proportions and sources.\"},"
            +
            "\"Conciseness\":{\"score\":4,\"reason\":\"The summary is mostly concise but could be slightly more streamlined by removing redundant phrases.\"},"
            +
            "\"Technical Accuracy\":{\"score\":0,\"reason\":\"The summary accurately describes the experimental approach involving data mixtures, proportions, and sources, reflecting the technical details of the study.\"}}";

    private static final String USER = UUID.randomUUID().toString();
    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME = "workspace-" + UUID.randomUUID();

    private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();

    private static final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();

    private static final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer();

    @RegisterExtension
    private static TestDropwizardAppExtension APP;

    private static final WireMockUtils.WireMockRuntime wireMock;

    static {
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

        mockTargetWorkspace(API_KEY, WORKSPACE_NAME, WORKSPACE_ID);

        this.evaluatorsResourceClient = new AutomationRuleEvaluatorResourceClient(this.client, baseURI);
        this.traceResourceClient = new TraceResourceClient(this.client, baseURI);
        this.projectResourceClient = new ProjectResourceClient(this.client, baseURI, factory);
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
                                            new AuthenticationErrorResponse(FAKE_API_KEY_MESSAGE,
                                                    401)))));
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("create evaluator definition: when api key is present, then return proper response")
        void createAutomationRuleEvaluator__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey,
                boolean isAuthorized, io.dropwizard.jersey.errors.ErrorMessage errorMessage) {

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
                            .isEqualTo(errorMessage);
                }
            }

        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("get evaluators by project id: when api key is present, then return proper response")
        void getProjectAutomationRuleEvaluators__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey,
                boolean isAuthorized, io.dropwizard.jersey.errors.ErrorMessage errorMessage) {

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
                            .isEqualTo(errorMessage);
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

            List<AutomationRuleEvaluator<?>> content = actualEntity.content();
            assertThat(content.stream().map(AutomationRuleEvaluator::getName).toList()).contains(name);
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("get evaluator by id: when api key is present, then return proper response")
        void getAutomationRuleEvaluatorById__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey,
                boolean isAuthorized, io.dropwizard.jersey.errors.ErrorMessage errorMessage) {

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
                            .isEqualTo(errorMessage);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("update evaluator: when api key is present, then return proper response")
        void updateAutomationRuleEvaluator__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey,
                boolean isAuthorized, io.dropwizard.jersey.errors.ErrorMessage errorMessage) {

            var evaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder().id(null)
                    .build();

            String workspaceName = "workspace-" + UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();
            UUID projectId = UUID.randomUUID();

            mockTargetWorkspace(okApikey, workspaceName, workspaceId);

            UUID id = evaluatorsResourceClient.createEvaluator(evaluator, projectId, workspaceName, okApikey);

            var updatedEvaluator = factory.manufacturePojo(AutomationRuleEvaluatorUpdate.class);

            evaluatorsResourceClient.updateEvaluator(id, projectId, workspaceName, updatedEvaluator,
                    apiKey, isAuthorized, errorMessage);
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("delete evaluator by id: when api key is present, then return proper response")
        void deleteAutomationRuleEvaluator__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey,
                boolean isAuthorized, io.dropwizard.jersey.errors.ErrorMessage errorMessage) {

            var evaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder().id(null)
                    .build();

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
                            .isEqualTo(errorMessage);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("batch delete evaluators by id: when api key is present, then return proper response")
        void deleteProjectAutomationRuleEvaluators__whenApiKeyIsPresent__thenReturnProperResponse(String apiKey,
                boolean isAuthorized, io.dropwizard.jersey.errors.ErrorMessage errorMessage) {
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
                            .isEqualTo(errorMessage);
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
                            .isEqualTo(errorMessage);
                }
            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("get logs per rule evaluators: when api key is present, then return proper response")
        void getLogsPerRuleEvaluators__whenSessionTokenIsPresent__thenReturnProperResponse(
                String apikey,
                boolean isAuthorized,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage,
                LlmProviderFactory llmProviderFactory) throws JsonProcessingException {

            ChatResponse chatResponse = ChatResponse.builder()
                    .aiMessage(AiMessage.from(validAiMsgTxt))
                    .build();

            when(llmProviderFactory.getLanguageModel(anyString(), any())
                    .chat(any()))
                    .thenAnswer(invocationOnMock -> chatResponse);

            String projectName = UUID.randomUUID().toString();

            String workspaceName = "workspace-" + UUID.randomUUID();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(okApikey, workspaceName, workspaceId);

            ObjectMapper mapper = new ObjectMapper();

            var projectId = projectResourceClient.createProject(projectName, okApikey, workspaceName);

            var evaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                    .id(null)
                    .code(mapper.readValue(testEvaluator, AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode.class))
                    .samplingRate(1f)
                    .build();

            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .input(mapper.readTree(input))
                    .output(mapper.readTree(output))
                    .build();

            var id = evaluatorsResourceClient.createEvaluator(evaluator, projectId, workspaceName, okApikey);

            Instant startTime = Instant.now();
            traceResourceClient.createTrace(trace, okApikey, workspaceName);

            Awaitility.await().untilAsserted(() -> {

                try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI, projectId))
                        .path(id.toString())
                        .path("logs")
                        .request()
                        .accept(MediaType.APPLICATION_JSON_TYPE)
                        .header(HttpHeaders.AUTHORIZATION, apikey)
                        .header(WORKSPACE_HEADER, workspaceName)
                        .get()) {

                    if (isAuthorized) {
                        assertLogResponse(actualResponse, startTime, id, trace);
                    } else {
                        assertThat(actualResponse.getStatusInfo().getStatusCode())
                                .isEqualTo(HttpStatus.SC_UNAUTHORIZED);
                        assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                                .isEqualTo(errorMessage);
                    }
                }
            });
        }
    }

    private static void assertLogResponse(Response actualResponse, Instant startTime, UUID id, Trace trace) {
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
                                            new AuthenticationErrorResponse(FAKE_API_KEY_MESSAGE,
                                                    401)))));
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

            ObjectMapper mapper = new ObjectMapper();

            var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

            var evaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class).toBuilder()
                    .id(null)
                    .code(mapper.readValue(testEvaluator, AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode.class))
                    .samplingRate(1f)
                    .build();

            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .input(mapper.readTree(input))
                    .output(mapper.readTree(output))
                    .build();

            var id = evaluatorsResourceClient.createEvaluator(evaluator, projectId, WORKSPACE_NAME, API_KEY);

            Instant startTime = Instant.now();
            traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);

            Awaitility.await().untilAsserted(() -> {

                try (var actualResponse = client.target(URL_TEMPLATE.formatted(baseURI, projectId))
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
                        assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                                .isEqualTo(UNAUTHORIZED_RESPONSE);
                    }
                }
            });
        }
    }
}
