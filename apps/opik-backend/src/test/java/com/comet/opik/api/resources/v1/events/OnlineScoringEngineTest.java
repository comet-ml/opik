package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import com.comet.opik.api.LogItem.LogLevel;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge.TraceThreadLlmAsJudgeCode;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.evaluators.LlmAsJudgeMessage;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.AutomationRuleEvaluatorResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.domain.llm.structuredoutput.InstructionStrategy;
import com.comet.opik.domain.llm.structuredoutput.ToolCallingStrategy;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
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
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("LlmAsJudge Message Render")
@ExtendWith(MockitoExtension.class)
@ExtendWith(DropwizardAppExtensionProvider.class)
class OnlineScoringEngineTest {

    private final ChatCompletionService aiProxyService;
    private final FeedbackScoreService feedbackScoreService;
    private final EventBus eventBus;

    private static final String API_KEY = "apiKey" + UUID.randomUUID();
    private static final String PROJECT_NAME = "project-" + RandomStringUtils.secure().nextAlphanumeric(36);
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(36);
    private static final String WORKSPACE_ID = "wid-" + UUID.randomUUID();
    private static final String USER_NAME = "user-" + RandomStringUtils.secure().nextAlphanumeric(36);

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();
    private final TimeBasedEpochGenerator generator = Generators.timeBasedEpochGenerator();

    private final String MESSAGE_TO_TEST = "Summary: {{summary}}\\nInstruction: {{instruction}}\\n\\nLiteral: {{literal}}\\nNonexistent: {{nonexistent}}";
    private final String TEST_EVALUATOR = """
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

    private final String TRACE_THREAD_PROMPT = """
            Based on the given list of message exchanges between a user and an LLM, generate a JSON object to indicate whether the LAST `assistant` message is relevant to context in messages.

            ** Guidelines: **
            - Make sure to only return in JSON format.
            - The JSON must have only 2 fields: 'verdict' and 'reason'.
            - The 'verdict' key should STRICTLY be either 'yes' or 'no', which states whether the last `assistant` message is relevant according to the context in messages.
            - Provide a 'reason' ONLY if the answer is 'no'.
            - You DON'T have to provide a reason if the answer is 'yes'.
            - You MUST USE the previous messages (if any) provided in the list of messages to make an informed judgement on relevancy.
            - You MUST ONLY provide a verdict for the LAST message on the list but MUST USE context from the previous messages.
            - ONLY provide a 'no' answer if the LLM response is COMPLETELY irrelevant to the user's input message.
            - Vague LLM responses to vague inputs, such as greetings DOES NOT count as irrelevancies!
            - You should mention LLM response instead of `assistant`, and User instead of `user`.

            {{context}}
            """;

    private final String unquoted;

    {
        try {
            String jsonEncodedPrompt = JsonUtils.MAPPER.writeValueAsString(TRACE_THREAD_PROMPT);
            unquoted = jsonEncodedPrompt.substring(1, jsonEncodedPrompt.length() - 1);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private final String TEST_TRACE_THREAD_EVALUATOR = """
            {
              "model": { "name": "gpt-4o", "temperature": 0.3 },
              "messages": [
                { "role": "USER", "content": "%s" },
                { "role": "SYSTEM", "content": "You're a helpful AI, be cordial." }
              ],
              "schema": [
                { "name": "Relevance",           "type": "INTEGER",   "description": "Relevance of the summary" },
                { "name": "Conciseness",         "type": "DOUBLE",    "description": "Conciseness of the summary" },
                { "name": "Technical Accuracy",  "type": "BOOLEAN",   "description": "Technical accuracy of the summary" }
              ]
            }
            """
            .formatted(unquoted).trim();

    private final String SUMMARY_STR = "What was the approach to experimenting with different data mixtures?";
    private final String OUTPUT_STR = "The study employed a systematic approach to experiment with varying data mixtures by manipulating the proportions and sources of datasets used for model training.";
    private final String INPUT = """
            {
                "questions": {
                    "question1": "%s",
                    "question2": "Whatever, we wont use it anyway"
                 },
                "pdf_url": "https://arxiv.org/pdf/2406.04744",
                "title": "CRAG -- Comprehensive RAG Benchmark"
            }
            """.formatted(SUMMARY_STR).trim();
    private final String OUTPUT = """
            {
                "output": "%s"
            }
            """.formatted(OUTPUT_STR).trim();

    private final String EDGE_CASE_TEMPLATE = "Summary: {{summary}}\\nInstruction: {{ instruction     }}\\n\\nLiteral: {{literal}}\\nNonexistent: {{nonexistent}}";
    private final String TEST_EVALUATOR_EDGE_CASE = """
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

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer ZOOKEEPER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER);

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    private final WireMockUtils.WireMockRuntime wireMock;

    {
        Startables.deepStart(REDIS, MYSQL, CLICKHOUSE, ZOOKEEPER).join();

        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE, ClickHouseContainerUtils.DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);

        aiProxyService = Mockito.mock(ChatCompletionService.class);
        feedbackScoreService = Mockito.mock(FeedbackScoreService.class);
        eventBus = Mockito.mock(EventBus.class);

        APP = newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(REDIS.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        .mockEventBus(eventBus)
                        .modules(List.of(
                                new AbstractModule() {
                                    @Override
                                    protected void configure() {
                                        bind(ChatCompletionService.class).toInstance(aiProxyService);
                                        bind(FeedbackScoreService.class).toInstance(feedbackScoreService);
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
    void setUpAll(ClientSupport client) {
        var baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);

        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER_NAME);

        this.projectResourceClient = new ProjectResourceClient(client, baseURI, factory);
        this.evaluatorsResourceClient = new AutomationRuleEvaluatorResourceClient(client, baseURI);

        Mockito.reset(aiProxyService, feedbackScoreService, eventBus);
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

        Mockito.doNothing().when(eventBus).register(Mockito.any());

        var aiMessage = "{\"Relevance\":{\"score\":4,\"reason\":\"The summary addresses the instruction by covering the main points and themes. However, it could have included a few more specific details to fully align with the instruction.\"},"
                +
                "\"Technical Accuracy\":{\"score\":4.5,\"reason\":\"The summary accurately conveys the technical details, but there is a slight room for improvement in the precision of certain terms or concepts.\"},"
                +
                "\"Conciseness\":{\"score\":true,\"reason\":\"The summary is concise and effectively captures the essence of the content without unnecessary elaboration.\"}}";

        // mocked response from AI, reused from dev tests
        var aiResponse = ChatResponse.builder().aiMessage(AiMessage.aiMessage(aiMessage)).build();

        ArgumentCaptor<List<FeedbackScoreBatchItem>> captor = ArgumentCaptor.forClass(List.class);
        Mockito.doReturn(Mono.empty()).when(feedbackScoreService).scoreBatchOfTraces(Mockito.any());
        Mockito.doReturn(aiResponse).when(aiProxyService).scoreTrace(Mockito.any(), Mockito.any(), Mockito.any());

        onlineScoringSampler.onTracesCreated(event);

        Mono.delay(Duration.ofMillis(300)).block();

        Mockito.verify(feedbackScoreService, Mockito.times(1)).scoreBatchOfTraces(captor.capture());

        // check which feedback scores would be stored in Clickhouse by our process
        List<FeedbackScoreBatchItem> processed = captor.getValue();

        assertThat(processed).hasSize(event.traces().size() * 3);

        // test if all 3 feedbacks are generated with the expected value
        var resultMap = processed.stream().collect(Collectors.toMap(FeedbackScoreItem::name, Function.identity()));
        assertThat(resultMap.get("Relevance").value()).isEqualTo(new BigDecimal(4));
        assertThat(resultMap.get("Technical Accuracy").value()).isEqualTo(new BigDecimal("4.5"));
        assertThat(resultMap.get("Conciseness").value()).isEqualTo(BigDecimal.ONE);
    }

    @Test
    @DisplayName("test User Facing log error when AI provider fails")
    void testUserFacingLogErrorWhenAIProviderFails(OnlineScoringSampler onlineScoringSampler) throws Exception {
        Mockito.reset(feedbackScoreService, aiProxyService, eventBus);

        var projectName = "project" + RandomStringUtils.secure().nextAlphanumeric(36);

        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

        var evaluatorCode = JsonUtils.MAPPER.readValue(TEST_EVALUATOR, LlmAsJudgeCode.class);

        var evaluator = createRule(projectId, evaluatorCode);

        var id = evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);

        var traceId = generator.generate();
        var trace = createTrace(traceId, projectId);

        var event = new TracesCreated(List.of(trace), WORKSPACE_ID, USER_NAME);

        Mockito.doNothing().when(eventBus).register(Mockito.any());

        var captor = ArgumentCaptor.forClass(List.class);
        Mockito.doReturn(Mono.empty()).when(feedbackScoreService).scoreBatchOfTraces(Mockito.any());
        var providerErrorMessage = "LLM provider XXXXX";

        Mockito.doThrow(
                new InternalServerErrorException(ChatCompletionService.UNEXPECTED_ERROR_CALLING_LLM_PROVIDER,
                        new RuntimeException(providerErrorMessage)))
                .when(aiProxyService).scoreTrace(Mockito.any(), Mockito.any(), Mockito.any());

        onlineScoringSampler.onTracesCreated(event);

        Mono.delay(Duration.ofMillis(600)).block();

        Mockito.verify(feedbackScoreService, Mockito.never()).scoreBatchOfTraces(captor.capture());

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
                .enabled(true)
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
    @DisplayName("render message templates with a trace thread")
    void testRenderTemplateWithTraceThread() throws JsonProcessingException {
        var evaluatorCode = JsonUtils.MAPPER.readValue(TEST_TRACE_THREAD_EVALUATOR, TraceThreadLlmAsJudgeCode.class);
        var traceId = generator.generate();
        var projectId = generator.generate();
        var trace = createTrace(traceId, projectId).toBuilder()
                .threadId("thread-" + RandomStringUtils.secure().nextAlphanumeric(36))
                .build();

        var renderedMessages = OnlineScoringEngine.renderThreadMessages(
                evaluatorCode.messages(), Map.of(TraceThreadLlmAsJudgeCode.CONTEXT_VARIABLE_NAME, ""), List.of(trace));

        assertThat(renderedMessages).hasSize(2);

        var userMessage = renderedMessages.getFirst();
        assertThat(userMessage.getClass()).isEqualTo(UserMessage.class);
        assertThat(((UserMessage) userMessage).singleText()).contains(SUMMARY_STR);
        assertThat(((UserMessage) userMessage).singleText()).contains(OUTPUT_STR);

        var systemMessage = renderedMessages.get(1);
        assertThat(systemMessage.getClass()).isEqualTo(SystemMessage.class);
    }

    @Test
    @DisplayName("prepare trace thread LLM request with tool-calling strategy")
    void testPrepareTraceThreadLlmRequestWithToolCallingStrategy() throws JsonProcessingException {
        var evaluatorCode = JsonUtils.MAPPER.readValue(TEST_TRACE_THREAD_EVALUATOR, TraceThreadLlmAsJudgeCode.class);
        var trace = createTrace(generator.generate(), generator.generate()).toBuilder()
                .threadId("thread-" + RandomStringUtils.secure().nextAlphanumeric(36))
                .build();

        var request = OnlineScoringEngine.prepareThreadLlmRequest(evaluatorCode, List.of(trace),
                new ToolCallingStrategy());

        assertThat(request.responseFormat()).isNotNull();
        var expectedSchema = createTestSchema();
        assertThat(request.responseFormat().jsonSchema().rootElement()).isEqualTo(expectedSchema);
    }

    @Test
    @DisplayName("prepare LLM request with tool-calling strategy")
    void testPrepareLlmRequestWithToolCallingStrategy() throws JsonProcessingException {
        var evaluatorCode = JsonUtils.MAPPER.readValue(TEST_EVALUATOR, LlmAsJudgeCode.class);
        var trace = createTrace(generator.generate(), generator.generate());

        var request = OnlineScoringEngine.prepareLlmRequest(evaluatorCode, trace, new ToolCallingStrategy());

        assertThat(request.responseFormat()).isNotNull();
        var expectedSchema = createTestSchema();
        assertThat(request.responseFormat().jsonSchema().rootElement()).isEqualTo(expectedSchema);
    }

    @Test
    @DisplayName("prepare LLM request with instruction strategy")
    void testPrepareLlmRequestWithInstructionStrategy() throws JsonProcessingException {
        var evaluatorCode = JsonUtils.MAPPER.readValue(TEST_EVALUATOR, LlmAsJudgeCode.class);
        var trace = createTrace(generator.generate(), generator.generate());

        var request = OnlineScoringEngine.prepareLlmRequest(
                evaluatorCode, trace, new InstructionStrategy());

        assertThat(request.responseFormat()).isNull();

        var messages = request.messages();
        assertThat(messages).hasSize(2);

        var lastMessage = messages.get(1);
        assertThat(lastMessage).isInstanceOf(UserMessage.class);

        var userMessage = (UserMessage) lastMessage;
        assertThat(userMessage.singleText()).contains("IMPORTANT:");
        assertThat(userMessage.singleText()).contains("You must respond with ONLY a single valid JSON object");

        // Verify original content is preserved
        assertThat(userMessage.singleText()).contains("Summary: " + SUMMARY_STR);
        assertThat(userMessage.singleText()).contains("Instruction: " + OUTPUT_STR);
        assertThat(userMessage.singleText()).contains("Literal: some literal value");
    }

    private static Stream<Arguments> feedbackParsingArguments() {
        var validAiMsgTxt = "{\"Relevance\":{\"score\":5,\"reason\":\"The summary directly addresses the approach taken in the study by mentioning the systematic experimentation with varying data mixtures and the manipulation of proportions and sources.\"},"
                + "\"Conciseness\":{\"score\":4.0,\"reason\":\"The summary is mostly concise but could be slightly more streamlined by removing redundant phrases.\"},"
                + "\"Technical Accuracy\":{\"score\":false,\"reason\":\"The summary accurately describes the experimental approach involving data mixtures, proportions, and sources, reflecting the technical details of the study.\"}}";
        var invalidAiMsgTxt = "a" + validAiMsgTxt;
        var emptyAiMsgTxt = "{}";
        var emptyJson = "";
        var flatAiMsgTxt = "{\"user_satisfaction_score\":75.0,\"reason\":\"why\",\"chat_summary\":\"summary\"}";

        return Stream.of(
                arguments(validAiMsgTxt, 3),
                arguments(invalidAiMsgTxt, 0),
                arguments(emptyAiMsgTxt, 0),
                arguments(emptyJson, 0),
                arguments(flatAiMsgTxt, 0));
    }

    @ParameterizedTest
    @MethodSource("feedbackParsingArguments")
    @DisplayName("parse feedback scores from AI response")
    void testToFeedbackScores(String aiMessage, int expectedSize) {
        var chatResponse = ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage(aiMessage))
                .build();

        var feedbackScores = OnlineScoringEngine.toFeedbackScores(chatResponse);

        assertThat(feedbackScores).hasSize(expectedSize);

        if (expectedSize > 0) {
            var scoresMap = feedbackScores.stream()
                    .collect(Collectors.toMap(FeedbackScoreBatchItem::name, Function.identity()));

            var relevance = scoresMap.get("Relevance");
            assertThat(relevance.value()).isEqualTo(BigDecimal.valueOf(5));
            assertThat(relevance.source()).isEqualTo(ScoreSource.ONLINE_SCORING);

            var conciseness = scoresMap.get("Conciseness");
            assertThat(conciseness.value()).isEqualTo(new BigDecimal("4.0"));
            assertThat(conciseness.source()).isEqualTo(ScoreSource.ONLINE_SCORING);

            var techAccuracy = scoresMap.get("Technical Accuracy");
            assertThat(techAccuracy.value()).isEqualTo(BigDecimal.ZERO);
            assertThat(techAccuracy.source()).isEqualTo(ScoreSource.ONLINE_SCORING);
        }
    }

    private JsonObjectSchema createTestSchema() {
        return JsonObjectSchema.builder()
                .addProperty("Relevance", JsonObjectSchema.builder()
                        .description("Relevance of the summary")
                        .required("score", "reason")
                        .addProperty("score",
                                JsonIntegerSchema.builder().description("the score for Relevance").build())
                        .addProperty("reason",
                                JsonStringSchema.builder().description("the reason for the score for Relevance")
                                        .build())
                        .build())
                .addProperty("Conciseness", JsonObjectSchema.builder()
                        .description("Conciseness of the summary")
                        .required("score", "reason")
                        .addProperty("score",
                                JsonNumberSchema.builder().description("the score for Conciseness").build())
                        .addProperty("reason",
                                JsonStringSchema.builder().description("the reason for the score for Conciseness")
                                        .build())
                        .build())
                .addProperty("Technical Accuracy", JsonObjectSchema.builder()
                        .description("Technical accuracy of the summary")
                        .required("score", "reason")
                        .addProperty("score",
                                JsonBooleanSchema.builder().description("the score for Technical Accuracy").build())
                        .addProperty("reason",
                                JsonStringSchema.builder()
                                        .description("the reason for the score for Technical Accuracy").build())
                        .build())
                .required("Relevance", "Technical Accuracy", "Conciseness")
                .build();
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

    @ParameterizedTest
    @MethodSource
    @DisplayName("renderMessages should support keys with dots in their names")
    void testExtractFromJsonWithDotKey(String key, String jsonBody) throws Exception {
        // variable mapping: variable 'testVar' maps to 'input.' + key
        var variables = Map.of("testVar", "input." + key);
        var trace = Trace.builder()
                .id(UUID.randomUUID())
                .projectName(PROJECT_NAME)
                .projectId(UUID.randomUUID())
                .createdBy(USER_NAME)
                .input(JsonUtils.MAPPER.readTree(jsonBody))
                .build();

        // Render a message using the variable
        var template = List.of(new LlmAsJudgeMessage(ChatMessageType.USER, "Test value: {{testVar}}"));
        var rendered = OnlineScoringEngine.renderMessages(template, variables, trace);
        assertThat(rendered).hasSize(1);
        var userMessage = rendered.getFirst();
        assertThat(userMessage).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) userMessage).singleText()).contains("Test value: expected-value");
    }

    private static Stream<Arguments> testExtractFromJsonWithDotKey() {
        return Stream.of(
                arguments("key.with.dot", "{\"key.with.dot\":\"expected-value\"}"),
                arguments("regularKey", "{\"regularKey\":\"expected-value\"}"),
                arguments("subObject.nestedKey", "{\"subObject\":{\"nestedKey\":\"expected-value\"}}"));
    }
}
