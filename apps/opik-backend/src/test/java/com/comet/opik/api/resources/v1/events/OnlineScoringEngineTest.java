package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.AutomationRuleEvaluatorType;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.LlmAsJudgeOutputSchemaType;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Trace;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.AutomationRuleEvaluatorResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import com.github.dockerjava.api.exception.InternalServerErrorException;
import com.google.common.eventbus.EventBus;
import com.google.inject.AbstractModule;
import com.redis.testcontainers.RedisContainer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.apache.commons.lang3.RandomStringUtils;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode;
import static com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeOutputSchema;
import static com.comet.opik.api.LogItem.LogLevel;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("LlmAsJudge Message Render")
@ExtendWith(MockitoExtension.class)
class OnlineScoringEngineTest {

    private static final ChatCompletionService AI_PROXY_SERVICE;
    private static final FeedbackScoreService FEEDBACK_SCORE_SERVICE;
    private static final EventBus EVENT_BUS;

    private static final String API_KEY = "apiKey" + UUID.randomUUID();
    private static final String PROJECT_NAME = "project-" + RandomStringUtils.secure().nextAlphanumeric(36);
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(36);
    private static final String WORKSPACE_ID = "wid-" + UUID.randomUUID();
    private static final String USER_NAME = "user-" + RandomStringUtils.secure().nextAlphanumeric(36);

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();
    private final TimeBasedEpochGenerator generator = Generators.timeBasedEpochGenerator();

    private static final String MESSAGE_TO_TEST = "Summary: {{summary}}\\nInstruction: {{instruction}}\\n\\nLiteral: {{literal}}\\nNonexistent: {{nonexistent}}";
    private static final String TEST_EVALUATOR = """
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
                  "toFail1": "metadata.nonexistent.path",
                  "nonexistent": "some.nonexistent.path",
                  "literal": "some literal value"
              },
              "schema": [
                { "name": "Relevance",           "type": "INTEGER",   "description": "Relevance of the summary" },
                { "name": "Conciseness",         "type": "DOUBLE",    "description": "Conciseness of the summary" },
                { "name": "Technical Accuracy",  "type": "BOOLEAN",   "description": "Technical accuracy of the summary" }
              ]
            }
            """
            .formatted(MESSAGE_TO_TEST).trim();
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
            """.formatted(SUMMARY_STR).trim();
    private static final String OUTPUT = """
            {
                "output": "%s"
            }
            """.formatted(OUTPUT_STR).trim();

    private static final String EDGE_CASE_TEMPLATE = "Summary: {{summary}}\\nInstruction: {{ instruction     }}\\n\\nLiteral: {{literal}}\\nNonexistent: {{nonexistent}}";
    private static final String TEST_EVALUATOR_EDGE_CASE = """
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
                  "toFail1": "metadata.nonexistent.path",
                    "nonexistent": "some.nonexistent.path",
                  "literal": "some literal value"
              },
              "schema": [
                { "name": "Relevance",           "type": "INTEGER",   "description": "Relevance of the summary" },
                { "name": "Conciseness",         "type": "DOUBLE",    "description": "Conciseness of the summary" },
                { "name": "Technical Accuracy",  "type": "BOOLEAN",   "description": "Technical accuracy of the summary" }
              ]
            }
            """
            .formatted(EDGE_CASE_TEMPLATE).trim();

    private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private static final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();
    private static final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer();

    @RegisterExtension
    private static final TestDropwizardAppExtension APP;

    private static final WireMockUtils.WireMockRuntime WIRE_MOCK;

    static {
        Startables.deepStart(REDIS, MYSQL, CLICKHOUSE).join();

        WIRE_MOCK = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE, ClickHouseContainerUtils.DATABASE_NAME);

        AI_PROXY_SERVICE = Mockito.mock(ChatCompletionService.class);
        FEEDBACK_SCORE_SERVICE = Mockito.mock(FeedbackScoreService.class);
        EVENT_BUS = Mockito.mock(EventBus.class);

        APP = newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(REDIS.getRedisURI())
                        .runtimeInfo(WIRE_MOCK.runtimeInfo())
                        .mockEventBus(EVENT_BUS)
                        .modules(List.of(
                                new AbstractModule() {
                                    @Override
                                    protected void configure() {
                                        bind(ChatCompletionService.class).toInstance(AI_PROXY_SERVICE);
                                        bind(FeedbackScoreService.class).toInstance(FEEDBACK_SCORE_SERVICE);
                                    }
                                }))
                        .customConfigs(List.of(
                                new CustomConfig("onlineScoring.consumerGroupName", "test-group"),
                                new CustomConfig("onlineScoring.consumerBatchSize", "1"),
                                new CustomConfig("onlineScoring.poolingInterval", "100ms"),
                                new CustomConfig("onlineScoring.streams[0].streamName", "test-stream"),
                                new CustomConfig("onlineScoring.streams[0].scorer",
                                        AutomationRuleEvaluatorType.Constants.LLM_AS_JUDGE),
                                new CustomConfig("onlineScoring.streams[0].codec", "java")))
                        .build());
    }

    private AutomationRuleEvaluatorResourceClient evaluatorsResourceClient;
    private ProjectResourceClient projectResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client, Jdbi jdbi) throws SQLException {
        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());
        try (var connection = CLICKHOUSE.createConnection("")) {
            MigrationUtils.runClickhouseDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }
        var baseURI = "http://localhost:%d".formatted(client.getPort());

        ClientSupportUtils.config(client);

        AuthTestUtils.mockTargetWorkspace(WIRE_MOCK.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER_NAME);

        projectResourceClient = new ProjectResourceClient(client, baseURI, factory);
        evaluatorsResourceClient = new AutomationRuleEvaluatorResourceClient(client, baseURI);

        Mockito.reset(AI_PROXY_SERVICE, FEEDBACK_SCORE_SERVICE, EVENT_BUS);
    }

    @Test
    @DisplayName("test Redis producer and consumer base flow")
    void testRedisProducerAndConsumerBaseFlow(OnlineScoringSampler onlineScoringSampler) throws Exception {
        var projectId = projectResourceClient.createProject(PROJECT_NAME, API_KEY, WORKSPACE_NAME);

        var evaluatorCode = JsonUtils.MAPPER.readValue(TEST_EVALUATOR, LlmAsJudgeCode.class);

        var evaluator = createRule(projectId, evaluatorCode); // Let's make sure all traces are expected to be scored

        evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);

        var traceId = generator.generate();
        var trace = createTrace(traceId, projectId);
        var event = new TracesCreated(List.of(trace), WORKSPACE_ID, USER_NAME);

        Mockito.doNothing().when(EVENT_BUS).register(Mockito.any());

        var aiMessage = "{\"Relevance\":{\"score\":4,\"reason\":\"The summary addresses the instruction by covering the main points and themes. However, it could have included a few more specific details to fully align with the instruction.\"},"
                +
                "\"Technical Accuracy\":{\"score\":4.5,\"reason\":\"The summary accurately conveys the technical details, but there is a slight room for improvement in the precision of certain terms or concepts.\"},"
                +
                "\"Conciseness\":{\"score\":true,\"reason\":\"The summary is concise and effectively captures the essence of the content without unnecessary elaboration.\"}}";

        // mocked response from AI, reused from dev tests
        var aiResponse = ChatResponse.builder().aiMessage(AiMessage.aiMessage(aiMessage)).build();

        var captor = ArgumentCaptor.forClass(List.class);
        Mockito.doReturn(Mono.empty()).when(FEEDBACK_SCORE_SERVICE).scoreBatchOfTraces(Mockito.any());
        Mockito.doReturn(aiResponse).when(AI_PROXY_SERVICE).scoreTrace(Mockito.any(), Mockito.any(), Mockito.any());

        onlineScoringSampler.onTracesCreated(event);

        Mono.delay(Duration.ofMillis(300)).block();

        Mockito.verify(FEEDBACK_SCORE_SERVICE, Mockito.times(1)).scoreBatchOfTraces(captor.capture());

        // check which feedback scores would be stored in Clickhouse by our process
        List<FeedbackScoreBatchItem> processed = captor.getValue();

        assertThat(processed).hasSize(event.traces().size() * 3);

        // test if all 3 feedbacks are generated with the expected value
        var resultMap = processed.stream().collect(Collectors.toMap(FeedbackScoreBatchItem::name, Function.identity()));
        assertThat(resultMap.get("Relevance").value()).isEqualTo(new BigDecimal(4));
        assertThat(resultMap.get("Technical Accuracy").value()).isEqualTo(new BigDecimal("4.5"));
        assertThat(resultMap.get("Conciseness").value()).isEqualTo(BigDecimal.ONE);
    }

    @Test
    @DisplayName("test User Facing log error when AI provider fails")
    void testUserFacingLogErrorWhenAIProviderFails(OnlineScoringSampler onlineScoringSampler) throws Exception {
        Mockito.reset(FEEDBACK_SCORE_SERVICE, AI_PROXY_SERVICE, EVENT_BUS);

        var projectName = "project" + RandomStringUtils.secure().nextAlphanumeric(36);

        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

        var evaluatorCode = JsonUtils.MAPPER.readValue(TEST_EVALUATOR, LlmAsJudgeCode.class);

        var evaluator = createRule(projectId, evaluatorCode);

        var id = evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);

        var traceId = generator.generate();
        var trace = createTrace(traceId, projectId);

        var event = new TracesCreated(List.of(trace), WORKSPACE_ID, USER_NAME);

        Mockito.doNothing().when(EVENT_BUS).register(Mockito.any());

        var captor = ArgumentCaptor.forClass(List.class);
        Mockito.doReturn(Mono.empty()).when(FEEDBACK_SCORE_SERVICE).scoreBatchOfTraces(Mockito.any());
        var providerErrorMessage = "LLM provider XXXXX";

        Mockito.doThrow(
                new InternalServerErrorException(ChatCompletionService.UNEXPECTED_ERROR_CALLING_LLM_PROVIDER,
                        new RuntimeException(providerErrorMessage)))
                .when(AI_PROXY_SERVICE).scoreTrace(Mockito.any(), Mockito.any(), Mockito.any());

        onlineScoringSampler.onTracesCreated(event);

        Mono.delay(Duration.ofMillis(600)).block();

        Mockito.verify(FEEDBACK_SCORE_SERVICE, Mockito.never()).scoreBatchOfTraces(captor.capture());

        //Check user facing logs
        var logPage = evaluatorsResourceClient.getLogs(id, WORKSPACE_NAME, API_KEY);
        var logMessage = "Unexpected error while scoring traceId '%s' with rule '%s': %n%n%s".formatted(traceId,
                evaluator.getName(), providerErrorMessage);

        assertThat(logPage.content())
                .anyMatch(logItem -> logItem.level().equals(LogLevel.ERROR) && logItem.message().contains(logMessage));
    }

    private Trace createTrace(UUID traceId, UUID projectId) throws JsonProcessingException {
        return Trace.builder()
                .id(traceId)
                .projectName(PROJECT_NAME)
                .projectId(projectId)
                .createdBy(USER_NAME)
                .input(JsonUtils.MAPPER.readTree(INPUT))
                .output(JsonUtils.MAPPER.readTree(OUTPUT)).build();
    }

    private AutomationRuleEvaluatorLlmAsJudge createRule(UUID projectId, LlmAsJudgeCode evaluatorCode) {
        return AutomationRuleEvaluatorLlmAsJudge.builder()
                .projectId(projectId)
                .name("evaluator-test-" + RandomStringUtils.secure().nextAlphanumeric(36))
                .createdBy(USER_NAME)
                .code(evaluatorCode)
                .samplingRate(1.0f)
                .build();
    }

    @Test
    @DisplayName("parse variable mapping into a usable one")
    void testVariableMapping() throws JsonProcessingException {
        var evaluatorCode = JsonUtils.MAPPER.readValue(TEST_EVALUATOR, LlmAsJudgeCode.class);
        var variableMappings = OnlineScoringEngine.toVariableMapping(evaluatorCode.variables());

        assertThat(variableMappings).hasSize(6);

        var varSummary = variableMappings.getFirst();
        assertThat(varSummary.traceSection()).isEqualTo(OnlineScoringEngine.TraceSection.INPUT);
        assertThat(varSummary.jsonPath()).isEqualTo("$.questions.question1");

        var varInstruction = variableMappings.get(1);
        assertThat(varInstruction.traceSection()).isEqualTo(OnlineScoringEngine.TraceSection.OUTPUT);
        assertThat(varInstruction.jsonPath()).isEqualTo("$.output");

        var varNonUsed = variableMappings.get(2);
        assertThat(varNonUsed.traceSection()).isEqualTo(OnlineScoringEngine.TraceSection.INPUT);
        assertThat(varNonUsed.jsonPath()).isEqualTo("$.questions.question2");

        var varToFail = variableMappings.get(3);
        assertThat(varToFail.traceSection()).isEqualTo(OnlineScoringEngine.TraceSection.METADATA);
        assertThat(varToFail.jsonPath()).isEqualTo("$.nonexistent.path");

        var actualVarNonexistent = variableMappings.get(4);
        var expectedVarNonexistent = OnlineScoringEngine.MessageVariableMapping.builder()
                .variableName("nonexistent")
                .valueToReplace("some.nonexistent.path")
                .build();
        assertThat(actualVarNonexistent).isEqualTo(expectedVarNonexistent);

        var actualVarLiteral = variableMappings.get(5);
        var expectedVarLiteral = OnlineScoringEngine.MessageVariableMapping.builder()
                .variableName("literal")
                .valueToReplace("some literal value")
                .build();
        assertThat(actualVarLiteral).isEqualTo(expectedVarLiteral);
    }

    @Test
    @DisplayName("render message templates with a trace")
    void testRenderTemplate() throws JsonProcessingException {
        var evaluatorCode = JsonUtils.MAPPER.readValue(TEST_EVALUATOR, LlmAsJudgeCode.class);
        var traceId = generator.generate();
        var projectId = generator.generate();
        var trace = createTrace(traceId, projectId);
        var renderedMessages = OnlineScoringEngine.renderMessages(
                evaluatorCode.messages(), evaluatorCode.variables(), trace);

        assertThat(renderedMessages).hasSize(2);

        var userMessage = renderedMessages.getFirst();
        assertThat(userMessage.getClass()).isEqualTo(UserMessage.class);
        assertThat(((UserMessage) userMessage).singleText()).contains(SUMMARY_STR);
        assertThat(((UserMessage) userMessage).singleText()).contains(OUTPUT_STR);
        assertThat(((UserMessage) userMessage).singleText()).contains("some.nonexistent.path");
        assertThat(((UserMessage) userMessage).singleText()).contains("some literal value");

        var systemMessage = renderedMessages.get(1);
        assertThat(systemMessage.getClass()).isEqualTo(SystemMessage.class);
    }

    @Test
    @DisplayName("create a structured output response format given an Automation Rule Evaluator schema input")
    void testToResponseFormat() {
        // creates an entry for each possible output schema type
        var inputIntSchema = factory.manufacturePojo(LlmAsJudgeOutputSchema.class)
                .toBuilder().type(LlmAsJudgeOutputSchemaType.INTEGER).build();
        var inputBoolSchema = factory.manufacturePojo(LlmAsJudgeOutputSchema.class)
                .toBuilder().type(LlmAsJudgeOutputSchemaType.BOOLEAN).build();
        var inputDoubleSchema = factory.manufacturePojo(LlmAsJudgeOutputSchema.class)
                .toBuilder().type(LlmAsJudgeOutputSchemaType.DOUBLE).build();
        var schema = List.of(inputIntSchema, inputBoolSchema, inputDoubleSchema);

        var responseFormat = OnlineScoringEngine.toResponseFormat(schema);

        var schemaRoot = (JsonObjectSchema) responseFormat.jsonSchema().rootElement();
        assertThat(schemaRoot.properties()).hasSize(schema.size());
        assertThat(schemaRoot.required()).containsOnly(inputBoolSchema.name(), inputDoubleSchema.name(),
                inputIntSchema.name());

        var parsedIntSchema = (JsonObjectSchema) schemaRoot.properties().get(inputIntSchema.name());
        assertThat(parsedIntSchema.description()).isEqualTo(inputIntSchema.description());
        assertThat(parsedIntSchema.required()).containsOnly(OnlineScoringEngine.SCORE_FIELD_NAME,
                OnlineScoringEngine.REASON_FIELD_NAME);
        assertThat(parsedIntSchema.properties().get(OnlineScoringEngine.SCORE_FIELD_NAME).getClass())
                .isEqualTo(JsonIntegerSchema.class);

        var parsedBoolSchema = (JsonObjectSchema) schemaRoot.properties().get(inputBoolSchema.name());
        assertThat(parsedBoolSchema.description()).isEqualTo(inputBoolSchema.description());
        assertThat(parsedBoolSchema.required()).containsOnly(OnlineScoringEngine.SCORE_FIELD_NAME,
                OnlineScoringEngine.REASON_FIELD_NAME);
        assertThat(parsedBoolSchema.properties().get(OnlineScoringEngine.SCORE_FIELD_NAME).getClass())
                .isEqualTo(JsonBooleanSchema.class);

        var parsedDoubleSchema = (JsonObjectSchema) schemaRoot.properties().get(inputDoubleSchema.name());
        assertThat(parsedDoubleSchema.description()).isEqualTo(inputDoubleSchema.description());
        assertThat(parsedDoubleSchema.required()).containsOnly(OnlineScoringEngine.SCORE_FIELD_NAME,
                OnlineScoringEngine.REASON_FIELD_NAME);
        assertThat(parsedDoubleSchema.properties().get(OnlineScoringEngine.SCORE_FIELD_NAME).getClass())
                .isEqualTo(JsonNumberSchema.class);

    }

    private static Stream<Arguments> feedbackParsingArguments() {
        var validAiMsgTxt = "{\"Relevance\":{\"score\":5,\"reason\":\"The summary directly addresses the approach taken in the study by mentioning the systematic experimentation with varying data mixtures and the manipulation of proportions and sources.\"},"
                +
                "\"Conciseness\":{\"score\":4,\"reason\":\"The summary is mostly concise but could be slightly more streamlined by removing redundant phrases.\"},"
                +
                "\"Technical Accuracy\":{\"score\":0,\"reason\":\"The summary accurately describes the experimental approach involving data mixtures, proportions, and sources, reflecting the technical details of the study.\"}}";
        var invalidAiMsgTxt = "a" + validAiMsgTxt;

        var validJson = arguments(validAiMsgTxt, 3);
        var invalidJson = arguments(invalidAiMsgTxt, 0);
        var emptyJson = arguments("", 0);

        return Stream.of(validJson, invalidJson, emptyJson);
    }

    @ParameterizedTest
    @MethodSource("feedbackParsingArguments")
    @DisplayName("parse a OnlineScoring ChatResponse into Feedback Scores")
    void testParseResponseIntoFeedbacks(String aiMessage, Integer expectedResults) {
        var chatResponse = ChatResponse.builder().aiMessage(AiMessage.from(aiMessage)).build();
        var feedbackScores = OnlineScoringEngine.toFeedbackScores(chatResponse);

        assertThat(feedbackScores).hasSize(expectedResults);

        if (expectedResults > 0) {
            var relevanceScore = feedbackScores.getFirst();
            assertThat(relevanceScore.name()).isEqualTo("Relevance");
            assertThat(relevanceScore.value()).isEqualTo(new BigDecimal(5));
            assertThat(relevanceScore.reason()).startsWith("The summary directly ");
            assertThat(relevanceScore.source()).isEqualTo(ScoreSource.ONLINE_SCORING);

            var concisenessScore = feedbackScores.get(1);
            assertThat(concisenessScore.name()).isEqualTo("Conciseness");
            assertThat(concisenessScore.value()).isEqualTo(new BigDecimal(4));
            assertThat(concisenessScore.reason()).startsWith("The summary is mostly ");
            assertThat(concisenessScore.source()).isEqualTo(ScoreSource.ONLINE_SCORING);

            var techAccScore = feedbackScores.get(2);
            assertThat(techAccScore.name()).isEqualTo("Technical Accuracy");
            assertThat(techAccScore.value()).isEqualTo(new BigDecimal(0));
            assertThat(techAccScore.reason()).startsWith("The summary accurately ");
            assertThat(techAccScore.source()).isEqualTo(ScoreSource.ONLINE_SCORING);

        }
    }

    @Test
    @DisplayName("render a message template with edge cases")
    void testRenderEdgeCaseTemplate() throws JsonProcessingException {
        var evaluatorEdgeCase = JsonUtils.MAPPER.readValue(TEST_EVALUATOR_EDGE_CASE,
                AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode.class);
        var traceId = generator.generate();
        var projectId = generator.generate();
        var trace = createTrace(traceId, projectId);
        var renderedMessages = OnlineScoringEngine.renderMessages(
                evaluatorEdgeCase.messages(), evaluatorEdgeCase.variables(), trace);

        assertThat(renderedMessages).hasSize(2);

        var userMessage = renderedMessages.getFirst();
        assertThat(userMessage.getClass()).isEqualTo(UserMessage.class);
        assertThat(((UserMessage) userMessage).singleText()).contains(SUMMARY_STR);
        assertThat(((UserMessage) userMessage).singleText()).contains(OUTPUT_STR);
        assertThat(((UserMessage) userMessage).singleText()).contains("some.nonexistent.path");
        assertThat(((UserMessage) userMessage).singleText()).contains("some literal value");

        var systemMessage = renderedMessages.get(1);
        assertThat(systemMessage.getClass()).isEqualTo(SystemMessage.class);
    }
}
