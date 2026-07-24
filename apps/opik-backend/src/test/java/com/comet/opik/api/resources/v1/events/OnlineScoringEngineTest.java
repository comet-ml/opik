package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import com.comet.opik.api.LogItem.LogLevel;
import com.comet.opik.api.PromptType;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Source;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge.TraceThreadLlmAsJudgeCode;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.evaluators.LlmAsJudgeMessage;
import com.comet.opik.api.evaluators.LlmAsJudgeMessageContent;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.api.events.TracesUpdated;
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
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.domain.llm.structuredoutput.InstructionStrategy;
import com.comet.opik.domain.llm.structuredoutput.ToolCallingStrategy;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import com.github.dockerjava.api.exception.InternalServerErrorException;
import com.google.common.eventbus.EventBus;
import com.google.inject.AbstractModule;
import com.redis.testcontainers.RedisContainer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private static final String TRACE_THREAD_PROMPT = """
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
        String jsonEncodedPrompt = JsonUtils.writeValueAsString(TRACE_THREAD_PROMPT);
        unquoted = jsonEncodedPrompt.substring(1, jsonEncodedPrompt.length() - 1);
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

    private static final String MOCK_AI_RESPONSE = "{\"Relevance\":{\"score\":4,\"reason\":\"Test\"},"
            + "\"Technical Accuracy\":{\"score\":4.5,\"reason\":\"Test\"},"
            + "\"Conciseness\":{\"score\":true,\"reason\":\"Test\"}}";

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

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER = ClickHouseContainerUtils.newZookeeperContainer();
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
    private TraceResourceClient traceResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        var baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);

        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER_NAME);

        this.projectResourceClient = new ProjectResourceClient(client, baseURI, factory);
        this.evaluatorsResourceClient = new AutomationRuleEvaluatorResourceClient(client, baseURI);
        this.traceResourceClient = new TraceResourceClient(client, baseURI);

        Mockito.reset(aiProxyService, feedbackScoreService, eventBus);
    }

    @ParameterizedTest
    @EnumSource(value = Source.class, names = {"SDK"})
    @NullSource
    @DisplayName("test Redis producer and consumer base flow")
    void testRedisProducerAndConsumerBaseFlow(Source source, OnlineScoringSampler onlineScoringSampler) {
        Mockito.reset(feedbackScoreService, aiProxyService, eventBus);

        var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(32);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

        var evaluatorCode = JsonUtils.readValue(TEST_EVALUATOR, LlmAsJudgeCode.class);

        var evaluator = createRule(projectId, evaluatorCode); // Let's make sure all traces are expected to be scored

        evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);

        var traceId = generator.generate();
        var trace = createTrace(traceId, projectId, source);
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
    @DisplayName("test filtering evaluators by trace metadata")
    void testFilteringEvaluatorsByTraceMetadata(OnlineScoringSampler onlineScoringSampler) {
        Mockito.reset(feedbackScoreService, aiProxyService, eventBus);

        var projectName = "project" + RandomStringUtils.secure().nextAlphanumeric(36);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

        // Create three evaluators
        var evaluatorCode = JsonUtils.readValue(TEST_EVALUATOR, LlmAsJudgeCode.class);

        var evaluator1 = createRule(projectId, evaluatorCode);
        var evaluator2 = createRule(projectId, evaluatorCode);
        var evaluator3 = createRule(projectId, evaluatorCode);

        var evaluatorId1 = evaluatorsResourceClient.createEvaluator(evaluator1, WORKSPACE_NAME, API_KEY);
        var evaluatorId2 = evaluatorsResourceClient.createEvaluator(evaluator2, WORKSPACE_NAME, API_KEY);
        evaluatorsResourceClient.createEvaluator(evaluator3, WORKSPACE_NAME, API_KEY); // Create evaluator3 but don't select it

        // Create trace with metadata specifying only evaluator1 and evaluator2
        var traceId = generator.generate();
        var metadata = JsonUtils.createObjectNode();
        metadata.putArray("selected_rule_ids")
                .add(evaluatorId1.toString())
                .add(evaluatorId2.toString());

        // non-SDK traces, such as playground with "selected_rule_ids" must still be scored
        // by the explicitly selected evaluators.
        var trace = createTrace(traceId, projectId, Source.PLAYGROUND).toBuilder()
                .metadata(metadata)
                .build();

        var event = new TracesCreated(List.of(trace), WORKSPACE_ID, USER_NAME);

        Mockito.doNothing().when(eventBus).register(Mockito.any());

        var aiMessage = "{\"Relevance\":{\"score\":4,\"reason\":\"Test\"},"
                + "\"Technical Accuracy\":{\"score\":4.5,\"reason\":\"Test\"},"
                + "\"Conciseness\":{\"score\":true,\"reason\":\"Test\"}}";

        var aiResponse = ChatResponse.builder().aiMessage(AiMessage.aiMessage(aiMessage)).build();

        ArgumentCaptor<List<FeedbackScoreBatchItem>> captor = ArgumentCaptor.forClass(List.class);
        Mockito.doReturn(Mono.empty()).when(feedbackScoreService).scoreBatchOfTraces(Mockito.any());
        Mockito.doReturn(aiResponse).when(aiProxyService).scoreTrace(Mockito.any(), Mockito.any(), Mockito.any());

        onlineScoringSampler.onTracesCreated(event);

        // Wait longer for async processing to complete (both evaluators need to process)
        Awaitility.await().untilAsserted(() -> {
            // Verify that evaluators were processed
            // We should have at least 1 call to scoreBatchOfTraces
            Mockito.verify(feedbackScoreService, Mockito.atLeastOnce()).scoreBatchOfTraces(captor.capture());

            // Collect all scores from all calls
            var allScores = captor.getAllValues().stream()
                    .flatMap(List::stream)
                    .toList();

            // Verify that only 2 evaluators were processed (evaluator1 and evaluator2)
            // Each evaluator produces 3 feedback scores, so we expect 2 * 3 = 6 scores total
            assertThat(allScores).hasSize(6);

            // Verify all scores are from online scoring
            var scoresBySource = allScores.stream()
                    .collect(Collectors.groupingBy(FeedbackScoreItem::source));

            assertThat(scoresBySource).containsOnlyKeys(ScoreSource.ONLINE_SCORING);
            assertThat(scoresBySource.get(ScoreSource.ONLINE_SCORING)).hasSize(6);
        });
    }

    @Test
    @DisplayName("test User Facing log error when AI provider fails")
    void testUserFacingLogErrorWhenAIProviderFails(OnlineScoringSampler onlineScoringSampler) {
        Mockito.reset(feedbackScoreService, aiProxyService, eventBus);

        var projectName = "project" + RandomStringUtils.secure().nextAlphanumeric(36);

        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

        var evaluatorCode = JsonUtils.readValue(TEST_EVALUATOR, LlmAsJudgeCode.class);

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

    @Test
    @DisplayName("test partial trace without end_time is skipped, complete trace is scored once")
    void testPartialTraceThenCompleteTraceScoresOnlyOnce(OnlineScoringSampler onlineScoringSampler) {
        Mockito.reset(feedbackScoreService, aiProxyService, eventBus);

        var projectName = "project" + RandomStringUtils.secure().nextAlphanumeric(36);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

        var evaluatorCode = JsonUtils.readValue(TEST_EVALUATOR, LlmAsJudgeCode.class);
        var evaluator = createRule(projectId, evaluatorCode);
        evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);

        var traceId = generator.generate();

        // First event: partial trace (no end_time, no output) — simulates SDK "start" call
        var partialTrace = Trace.builder()
                .id(traceId)
                .projectName(projectName)
                .projectId(projectId)
                .createdBy(USER_NAME)
                .input(JsonUtils.getJsonNodeFromString(INPUT))
                .build();
        var partialEvent = new TracesCreated(List.of(partialTrace), WORKSPACE_ID, USER_NAME);

        Mockito.doNothing().when(eventBus).register(Mockito.any());

        var aiMessage = "{\"Relevance\":{\"score\":4,\"reason\":\"Test\"},"
                + "\"Technical Accuracy\":{\"score\":4.5,\"reason\":\"Test\"},"
                + "\"Conciseness\":{\"score\":true,\"reason\":\"Test\"}}";
        var aiResponse = ChatResponse.builder().aiMessage(AiMessage.aiMessage(aiMessage)).build();

        Mockito.doReturn(Mono.empty()).when(feedbackScoreService).scoreBatchOfTraces(Mockito.any());
        Mockito.doReturn(aiResponse).when(aiProxyService).scoreTrace(Mockito.any(), Mockito.any(), Mockito.any());

        // Send partial trace — should be skipped
        onlineScoringSampler.onTracesCreated(partialEvent);

        Mono.delay(Duration.ofSeconds(2)).block();

        // Verify no scoring happened for the partial trace
        Mockito.verify(feedbackScoreService, Mockito.never()).scoreBatchOfTraces(Mockito.any());

        // Second event: complete trace (with end_time and output) — simulates SDK "end" call
        var completeTrace = createTrace(traceId, projectId);
        var completeEvent = new TracesCreated(List.of(completeTrace), WORKSPACE_ID, USER_NAME);

        ArgumentCaptor<List<FeedbackScoreBatchItem>> captor = ArgumentCaptor.forClass(List.class);

        onlineScoringSampler.onTracesCreated(completeEvent);

        Mono.delay(Duration.ofMillis(300)).block();

        // Verify scoring happened exactly once
        Mockito.verify(feedbackScoreService, Mockito.times(1)).scoreBatchOfTraces(captor.capture());

        List<FeedbackScoreBatchItem> processed = captor.getValue();
        assertThat(processed).hasSize(3); // 3 scores from one evaluator
    }

    @Test
    @DisplayName("onTracesUpdated when endTime is set triggers scoring")
    void onTracesUpdatedWhenEndTimeSetTriggersScoring(OnlineScoringSampler onlineScoringSampler) {
        Mockito.reset(feedbackScoreService, aiProxyService, eventBus);

        var projectName = "project" + RandomStringUtils.secure().nextAlphanumeric(36);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

        var evaluatorCode = JsonUtils.readValue(TEST_EVALUATOR, LlmAsJudgeCode.class);
        var evaluator = createRule(projectId, evaluatorCode);
        evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);

        var traceId = generator.generate();

        // Insert a complete trace into the DB via REST API (simulates SDK POST + PATCH lifecycle)
        var trace = factory.manufacturePojo(Trace.class).toBuilder()
                .id(traceId)
                .projectName(projectName)
                .projectId(projectId)
                .createdBy(USER_NAME)
                .startTime(java.time.Instant.now())
                .input(JsonUtils.getJsonNodeFromString(INPUT))
                .output(JsonUtils.getJsonNodeFromString(OUTPUT))
                .endTime(java.time.Instant.now())
                .source(null)
                .feedbackScores(null)
                .build();
        traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);

        Mockito.doNothing().when(eventBus).register(Mockito.any());

        var aiResponse = ChatResponse.builder().aiMessage(AiMessage.aiMessage(MOCK_AI_RESPONSE)).build();

        ArgumentCaptor<List<FeedbackScoreBatchItem>> captor = ArgumentCaptor.forClass(List.class);
        Mockito.doReturn(Mono.empty()).when(feedbackScoreService).scoreBatchOfTraces(Mockito.any());
        Mockito.doReturn(aiResponse).when(aiProxyService).scoreTrace(Mockito.any(), Mockito.any(), Mockito.any());

        // Fire TracesUpdated with endTime set — should trigger scoring
        var traceUpdate = TraceUpdate.builder().endTime(java.time.Instant.now()).build();
        var event = new TracesUpdated(Set.of(projectId), Set.of(traceId), WORKSPACE_ID, USER_NAME, traceUpdate);
        onlineScoringSampler.onTracesUpdated(event);

        Awaitility.await().untilAsserted(() -> {
            Mockito.verify(feedbackScoreService, Mockito.atLeastOnce()).scoreBatchOfTraces(captor.capture());

            var allScores = captor.getAllValues().stream()
                    .flatMap(List::stream)
                    .toList();

            assertThat(allScores).hasSize(3);

            var resultMap = allScores.stream()
                    .collect(Collectors.toMap(FeedbackScoreItem::name, java.util.function.Function.identity()));
            assertThat(resultMap.get("Relevance").value()).isEqualTo(new BigDecimal(4));
            assertThat(resultMap.get("Technical Accuracy").value()).isEqualTo(new BigDecimal("4.5"));
            assertThat(resultMap.get("Conciseness").value()).isEqualTo(BigDecimal.ONE);
        });
    }

    @Test
    @DisplayName("onTracesUpdated when endTime is null does NOT trigger scoring")
    void onTracesUpdatedWithoutEndTimeDoesNotTriggerScoring(OnlineScoringSampler onlineScoringSampler) {
        Mockito.reset(feedbackScoreService, aiProxyService, eventBus);

        var projectName = "project" + RandomStringUtils.secure().nextAlphanumeric(36);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

        var evaluatorCode = JsonUtils.readValue(TEST_EVALUATOR, LlmAsJudgeCode.class);
        var evaluator = createRule(projectId, evaluatorCode);
        evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);

        var traceId = generator.generate();

        Mockito.doNothing().when(eventBus).register(Mockito.any());

        // Fire TracesUpdated without endTime (e.g., tag addition) — should NOT score
        var traceUpdate = TraceUpdate.builder().name("updated-name").build();
        var event = new TracesUpdated(Set.of(projectId), Set.of(traceId), WORKSPACE_ID, USER_NAME, traceUpdate);
        onlineScoringSampler.onTracesUpdated(event);

        TestUtils.waitForMillis(1000);

        // Verify no scoring happened
        Mockito.verify(feedbackScoreService, Mockito.never()).scoreBatchOfTraces(Mockito.any());
    }

    private Trace createTrace(UUID traceId, UUID projectId) {
        return createTrace(traceId, projectId, null);
    }

    private Trace createTrace(UUID traceId, UUID projectId, Source source) {
        return Trace.builder()
                .id(traceId)
                .projectName(PROJECT_NAME)
                .projectId(projectId)
                .createdBy(USER_NAME)
                .input(JsonUtils.getJsonNodeFromString(INPUT))
                .output(JsonUtils.getJsonNodeFromString(OUTPUT))
                .endTime(java.time.Instant.now())
                .source(source)
                .build();
    }

    private AutomationRuleEvaluatorLlmAsJudge createRule(UUID projectId, LlmAsJudgeCode evaluatorCode) {
        return AutomationRuleEvaluatorLlmAsJudge.builder()
                .projectIds(Set.of(projectId))
                .name("evaluator-test-" + RandomStringUtils.secure().nextAlphanumeric(36))
                .createdBy(USER_NAME)
                .code(evaluatorCode)
                .samplingRate(1.0f)
                .enabled(true)
                .build();
    }

    @Test
    @DisplayName("parse variable mapping into a usable one")
    void testVariableMapping() {
        var evaluatorCode = JsonUtils.readValue(TEST_EVALUATOR, LlmAsJudgeCode.class);
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
    @DisplayName("parse variable mapping with root objects (input, output, metadata)")
    void testVariableMappingWithRootObjects() {
        // Given
        var variables = Map.of(
                "full_input", "input",
                "full_output", "output",
                "full_metadata", "metadata",
                "subtree", "input.questions",
                "nested_field", "input.questions.question1");

        // When
        var variableMappings = OnlineScoringEngine.toVariableMapping(variables);

        // Then
        assertThat(variableMappings).hasSize(5);

        // Test "input" maps to root "$"
        var inputMapping = variableMappings.stream()
                .filter(mapping -> "full_input".equals(mapping.variableName()))
                .findFirst()
                .orElseThrow();
        assertThat(inputMapping.traceSection()).isEqualTo(OnlineScoringEngine.TraceSection.INPUT);
        assertThat(inputMapping.jsonPath()).isEqualTo("$");

        // Test "output" maps to root "$"
        var outputMapping = variableMappings.stream()
                .filter(mapping -> "full_output".equals(mapping.variableName()))
                .findFirst()
                .orElseThrow();
        assertThat(outputMapping.traceSection()).isEqualTo(OnlineScoringEngine.TraceSection.OUTPUT);
        assertThat(outputMapping.jsonPath()).isEqualTo("$");

        // Test "metadata" maps to root "$"
        var metadataMapping = variableMappings.stream()
                .filter(mapping -> "full_metadata".equals(mapping.variableName()))
                .findFirst()
                .orElseThrow();
        assertThat(metadataMapping.traceSection()).isEqualTo(OnlineScoringEngine.TraceSection.METADATA);
        assertThat(metadataMapping.jsonPath()).isEqualTo("$");

        // Test subtree works correctly
        var subtreeMapping = variableMappings.stream()
                .filter(mapping -> "subtree".equals(mapping.variableName()))
                .findFirst()
                .orElseThrow();
        assertThat(subtreeMapping.traceSection()).isEqualTo(OnlineScoringEngine.TraceSection.INPUT);
        assertThat(subtreeMapping.jsonPath()).isEqualTo("$.questions");

        // Test nested field still works correctly
        var nestedMapping = variableMappings.stream()
                .filter(mapping -> "nested_field".equals(mapping.variableName()))
                .findFirst()
                .orElseThrow();
        assertThat(nestedMapping.traceSection()).isEqualTo(OnlineScoringEngine.TraceSection.INPUT);
        assertThat(nestedMapping.jsonPath()).isEqualTo("$.questions.question1");
    }

    @Test
    @DisplayName("toReplacements should produce valid JSON for complex objects (Python evaluator path)")
    void testToReplacementsProducesValidJsonForComplexObjects() {
        // Given - a trace with a complex nested object in output (similar to the customer's sql_template case)
        var complexOutput = """
                {
                    "sql_template": {
                        "template": "SELECT * FROM table WHERE date >= '{start_date}'",
                        "parameters": {
                            "start_date": "2026-01-08",
                            "end_date": "2026-01-09",
                            "timezone": "UTC"
                        }
                    },
                    "simple_field": "just a string"
                }
                """;

        var trace = Trace.builder()
                .id(UUID.randomUUID())
                .projectName(PROJECT_NAME)
                .projectId(UUID.randomUUID())
                .createdBy(USER_NAME)
                .output(JsonUtils.getJsonNodeFromString(complexOutput))
                .build();

        // Variable mapping like a Python evaluator would use
        var variables = Map.of(
                "output", "output.sql_template",
                "simple", "output.simple_field");

        // When - toReplacements is called (this is what Python evaluators use directly)
        var replacements = OnlineScoringEngine.toReplacements(variables, trace);

        // Then - the complex object should be valid JSON that can be parsed
        assertThat(replacements).containsKey("output");
        assertThat(replacements).containsKey("simple");

        // Simple string should remain as-is
        assertThat(replacements.get("simple")).isEqualTo("just a string");

        // Complex object should be valid JSON (not Java's toString format like {key=value})
        var complexValue = replacements.get("output");
        assertThat(complexValue)
                .as("Complex object should be serialized as JSON, not Java toString()")
                .startsWith("{")
                .contains("\"template\"")
                .contains("\"parameters\"")
                .doesNotContain("template=") // Java toString format
                .doesNotContain("parameters="); // Java toString format

        // Verify it's actually parseable as JSON
        var parsedJson = JsonUtils.getJsonNodeFromString(complexValue);
        assertThat(parsedJson.has("template")).isTrue();
        assertThat(parsedJson.has("parameters")).isTrue();
        assertThat(parsedJson.get("parameters").has("start_date")).isTrue();
        assertThat(parsedJson.get("parameters").get("timezone").asText()).isEqualTo("UTC");
    }

    @Test
    @DisplayName("render message templates with root object variables")
    void testRenderTemplateWithRootObjects() {
        // Given
        var messageTemplate = "Full input: {{full_input}}\\nNested field: {{nested_field}}";
        var evaluatorJson = """
                {
                  "model": { "name": "gpt-4o", "temperature": 0.3 },
                  "messages": [
                    { "role": "USER", "content": "%s" }
                  ],
                  "variables": {
                      "full_input": "input",
                      "nested_field": "input.questions.question1"
                  },
                  "schema": [
                    { "name": "Relevance", "type": "INTEGER", "description": "Test" }
                  ]
                }
                """.formatted(messageTemplate).trim();

        var evaluatorCode = JsonUtils.readValue(evaluatorJson, LlmAsJudgeCode.class);
        var traceId = generator.generate();
        var projectId = generator.generate();
        var trace = createTrace(traceId, projectId);

        // When
        var renderedMessages = OnlineScoringEngine.renderMessages(
                evaluatorCode.messages(), evaluatorCode.variables(), trace);

        // Then
        assertThat(renderedMessages).hasSize(1);
        var userMessage = (UserMessage) renderedMessages.getFirst();
        var messageText = userMessage.singleText();

        // Should contain the entire input JSON object (HTML-encoded)
        assertThat(messageText).contains("Full input: {");
        assertThat(messageText).containsAnyOf("\"questions\"", "&quot;questions&quot;");
        assertThat(messageText).containsAnyOf("\"question1\"", "&quot;question1&quot;");
        assertThat(messageText).containsAnyOf("\"pdf_url\"", "&quot;pdf_url&quot;");

        // Should also contain the nested field value
        assertThat(messageText).contains("Nested field: " + SUMMARY_STR);
    }

    @Test
    @DisplayName("render message templates with output root object")
    void testRenderTemplateWithOutputRootObject() {
        // Given
        var messageTemplate = "Full output: {{full_output}}";
        var evaluatorJson = """
                {
                  "model": { "name": "gpt-4o", "temperature": 0.3 },
                  "messages": [
                    { "role": "USER", "content": "%s" }
                  ],
                  "variables": {
                      "full_output": "output"
                  },
                  "schema": [
                    { "name": "Relevance", "type": "INTEGER", "description": "Test" }
                  ]
                }
                """.formatted(messageTemplate).trim();

        var evaluatorCode = JsonUtils.readValue(evaluatorJson, LlmAsJudgeCode.class);
        var traceId = generator.generate();
        var projectId = generator.generate();
        var trace = createTrace(traceId, projectId);

        // When
        var renderedMessages = OnlineScoringEngine.renderMessages(
                evaluatorCode.messages(), evaluatorCode.variables(), trace);

        // Then
        assertThat(renderedMessages).hasSize(1);
        var userMessage = (UserMessage) renderedMessages.getFirst();
        var messageText = userMessage.singleText();

        // Should contain the entire output JSON object (HTML-encoded)
        assertThat(messageText).contains("Full output: {");
        assertThat(messageText).containsAnyOf("\"output\"", "&quot;output&quot;");
        assertThat(messageText).contains(OUTPUT_STR);
    }

    @Test
    @DisplayName("render span message templates with root object variables")
    void testRenderSpanTemplateWithRootObjects() {
        // Given
        var messageTemplate = "Full input: {{full_input}}\\nNested field: {{nested_field}}";
        var evaluatorJson = """
                {
                  "model": { "name": "gpt-4o", "temperature": 0.3 },
                  "messages": [
                    { "role": "USER", "content": "%s" }
                  ],
                  "variables": {
                      "full_input": "input",
                      "nested_field": "input.questions.question1"
                  },
                  "schema": [
                    { "name": "Relevance", "type": "INTEGER", "description": "Test" }
                  ]
                }
                """.formatted(messageTemplate).trim();

        var evaluatorCode = JsonUtils.readValue(evaluatorJson,
                AutomationRuleEvaluatorSpanLlmAsJudge.SpanLlmAsJudgeCode.class);
        var spanId = generator.generate();
        var projectId = generator.generate();
        var span = createSpan(spanId, projectId);

        // When
        var renderedMessages = OnlineScoringEngine.renderMessages(
                evaluatorCode.messages(), evaluatorCode.variables(), span);

        // Then
        assertThat(renderedMessages).hasSize(1);
        var userMessage = (UserMessage) renderedMessages.getFirst();
        var messageText = userMessage.singleText();

        // Should contain the entire input JSON object (HTML-encoded)
        assertThat(messageText).contains("Full input: {");
        assertThat(messageText).containsAnyOf("\"questions\"", "&quot;questions&quot;");

        // Should also contain the nested field value
        assertThat(messageText).contains("Nested field: " + SUMMARY_STR);
    }

    Stream<String> testRenderTemplate() {
        return Stream.of(TEST_EVALUATOR, TEST_EVALUATOR_EDGE_CASE);
    }

    @ParameterizedTest
    @MethodSource
    @DisplayName("render message templates with a trace")
    void testRenderTemplate(String evaluator) {
        var evaluatorCode = JsonUtils.readValue(evaluator, LlmAsJudgeCode.class);
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
    void testRenderTemplateWithTraceThread() {
        var evaluatorCode = JsonUtils.readValue(TEST_TRACE_THREAD_EVALUATOR, TraceThreadLlmAsJudgeCode.class);
        var traceId = generator.generate();
        var projectId = generator.generate();
        var trace = createTrace(traceId, projectId).toBuilder()
                .threadId("thread-" + RandomStringUtils.secure().nextAlphanumeric(36))
                .build();

        var renderedMessages = OnlineScoringEngine.renderThreadMessages(
                evaluatorCode.messages(), Map.of(TraceThreadLlmAsJudgeCode.CONTEXT_VARIABLE_NAME, ""), List.of(trace),
                List.of());

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
    void testPrepareTraceThreadLlmRequestWithToolCallingStrategy() {
        var evaluatorCode = JsonUtils.readValue(TEST_TRACE_THREAD_EVALUATOR, TraceThreadLlmAsJudgeCode.class);
        var trace = createTrace(generator.generate(), generator.generate()).toBuilder()
                .threadId("thread-" + RandomStringUtils.secure().nextAlphanumeric(36))
                .build();

        var request = OnlineScoringEngine.prepareThreadLlmRequest(evaluatorCode, List.of(trace),
                new ToolCallingStrategy(), List.of());

        assertThat(request.responseFormat()).isNotNull();
        var expectedSchema = createTestSchema();
        assertThat(request.responseFormat().jsonSchema().rootElement()).isEqualTo(expectedSchema);
    }

    @Test
    @DisplayName("thread {{context}} is wire-identical to today's shape when no spans are passed")
    void prepareThreadLlmRequestKeepsLegacyContextShapeWhenSpansAreEmpty() {
        var evaluatorCode = JsonUtils.readValue(TEST_TRACE_THREAD_EVALUATOR, TraceThreadLlmAsJudgeCode.class);
        var traceId = generator.generate();
        var trace = createTrace(traceId, generator.generate()).toBuilder()
                .threadId("thread-" + RandomStringUtils.secure().nextAlphanumeric(36))
                .build();

        var request = OnlineScoringEngine.prepareThreadLlmRequest(evaluatorCode, List.of(trace),
                new InstructionStrategy(), List.of());

        // Empty spans → enriched serializer omits the `spans` field via @JsonInclude(NON_NULL).
        // The substituted {{context}} JSON renders user/assistant entries from the trace but
        // never the `spans` key. Mustache HTML-escapes the substituted JSON (`&quot;`), so
        // we match on bare field names rather than full quoted-string fragments.
        var allText = request.messages().stream()
                .map(Object::toString)
                .collect(Collectors.joining("\n"));
        assertThat(allText).contains("role");
        assertThat(allText).contains("user");
        assertThat(allText).contains("assistant");
        // The TEST_TRACE_THREAD_EVALUATOR system prompt does not mention "spans", so a bare
        // substring check is sufficient to prove the field isn't in the substituted JSON.
        assertThat(allText).doesNotContain("spans");
    }

    @Test
    @DisplayName("thread {{context}} attaches sorted spans under the assistant entry when toggle on")
    void prepareThreadLlmRequestAttachesSpansToAssistantEntryWhenProvided() {
        var evaluatorCode = JsonUtils.readValue(TEST_TRACE_THREAD_EVALUATOR, TraceThreadLlmAsJudgeCode.class);
        var traceId = generator.generate();
        var projectId = generator.generate();
        var trace = createTrace(traceId, projectId).toBuilder()
                .threadId("thread-" + RandomStringUtils.secure().nextAlphanumeric(36))
                .build();
        // Two spans on the same trace, out-of-order on input — the helper sorts by start_time
        // so the wire order tracks call order regardless of how the caller hands them in.
        var spanLate = Span.builder()
                .id(generator.generate()).name("tool-late").type(SpanType.tool)
                .startTime(java.time.Instant.now().plusMillis(10)).traceId(traceId).projectId(projectId)
                .build();
        var spanEarly = Span.builder()
                .id(generator.generate()).name("tool-early").type(SpanType.tool)
                .startTime(java.time.Instant.now()).traceId(traceId).projectId(projectId)
                .build();

        var request = OnlineScoringEngine.prepareThreadLlmRequest(evaluatorCode, List.of(trace),
                new InstructionStrategy(), List.of(spanLate, spanEarly));

        var allText = request.messages().stream()
                .map(Object::toString)
                .collect(Collectors.joining("\n"));
        // The `spans` array shows up nested under the assistant entry, with the earlier-started
        // span first — the LLM sees call order matching the trace's actual execution order.
        // Mustache HTML-escapes the substituted JSON, so we match on field/value substrings
        // rather than full quoted-string fragments.
        assertThat(allText).contains("spans");
        assertThat(allText).contains("tool-early");
        assertThat(allText).contains("tool-late");
        assertThat(allText.indexOf("tool-early")).isLessThan(allText.indexOf("tool-late"));
    }

    @Test
    @DisplayName("prepare LLM request with tool-calling strategy")
    void testPrepareLlmRequestWithToolCallingStrategy() {
        var evaluatorCode = JsonUtils.readValue(TEST_EVALUATOR, LlmAsJudgeCode.class);
        var trace = createTrace(generator.generate(), generator.generate());

        var request = OnlineScoringEngine.prepareLlmRequest(evaluatorCode, trace, new ToolCallingStrategy(),
                List.of());

        assertThat(request.responseFormat()).isNotNull();
        var expectedSchema = createTestSchema();
        assertThat(request.responseFormat().jsonSchema().rootElement()).isEqualTo(expectedSchema);
    }

    @Test
    @DisplayName("prepare LLM request with instruction strategy")
    void testPrepareLlmRequestWithInstructionStrategy() {
        var evaluatorCode = JsonUtils.readValue(TEST_EVALUATOR, LlmAsJudgeCode.class);
        var trace = createTrace(generator.generate(), generator.generate());

        var request = OnlineScoringEngine.prepareLlmRequest(
                evaluatorCode, trace, new InstructionStrategy(), List.of());

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

    @Test
    @DisplayName("templateReferencesSpans detects the 'spans' sentinel anywhere in the variables map")
    void templateReferencesSpansDetectsSentinelInVariablesMap() {
        var noMessages = List.<com.comet.opik.api.evaluators.LlmAsJudgeMessage>of();
        var mustache = com.comet.opik.api.PromptType.MUSTACHE;
        assertThat(OnlineScoringEngine.templateReferencesSpans(noMessages, Map.of(), mustache)).isFalse();
        assertThat(OnlineScoringEngine.templateReferencesSpans(noMessages, Map.of(
                "summary", "input.foo",
                "out", "output.bar"), mustache)).isFalse();
        // Sentinel matches the bare value "spans" — not "input.spans", not "Spans", not "$.spans".
        assertThat(OnlineScoringEngine.templateReferencesSpans(noMessages,
                Map.of("mySpans", "spans"), mustache)).isTrue();
        assertThat(OnlineScoringEngine.templateReferencesSpans(noMessages, Map.of(
                "summary", "input.foo",
                "spansList", "spans"), mustache)).isTrue();
        // Case-sensitive: only the lowercase bare sentinel triggers injection.
        assertThat(OnlineScoringEngine.templateReferencesSpans(noMessages, Map.of("x", "Spans"), mustache)).isFalse();
        assertThat(OnlineScoringEngine.templateReferencesSpans(noMessages,
                Map.of("x", "input.spans"), mustache)).isFalse();
    }

    @Test
    @DisplayName("templateReferencesSpans detects {{spans}} inside a multimodal message's contentArray text part")
    void templateReferencesSpansDetectsSpansInMultimodalContentArrayText() {
        var mustache = com.comet.opik.api.PromptType.MUSTACHE;
        var multimodalMessage = List.of(com.comet.opik.api.evaluators.LlmAsJudgeMessage.builder()
                .role(dev.langchain4j.data.message.ChatMessageType.USER)
                .contentArray(List.of(
                        com.comet.opik.api.evaluators.LlmAsJudgeMessageContent.builder()
                                .type("text")
                                .text("Spans: {{spans}}")
                                .build(),
                        com.comet.opik.api.evaluators.LlmAsJudgeMessageContent.builder()
                                .type("image_url")
                                .imageUrl(com.comet.opik.api.evaluators.LlmAsJudgeMessageContent.ImageUrl.builder()
                                        .url("https://example.com/x.png").build())
                                .build()))
                .build());
        // The text part inside contentArray references {{spans}} — detection must walk
        // structured-content messages, not just the simple `content` string field.
        assertThat(OnlineScoringEngine.templateReferencesSpans(multimodalMessage, Map.of(), mustache)).isTrue();

        // Variables explicitly maps "spans" to something else — explicit override wins
        // even when {{spans}} sits in a multimodal text part.
        assertThat(OnlineScoringEngine.templateReferencesSpans(multimodalMessage,
                Map.of("spans", "input.spans"), mustache)).isFalse();

        // No text part references {{spans}} — false.
        var multimodalNoSpans = List.of(com.comet.opik.api.evaluators.LlmAsJudgeMessage.builder()
                .role(dev.langchain4j.data.message.ChatMessageType.USER)
                .contentArray(List.of(
                        com.comet.opik.api.evaluators.LlmAsJudgeMessageContent.builder()
                                .type("text")
                                .text("Summary: {{summary}}")
                                .build()))
                .build());
        assertThat(OnlineScoringEngine.templateReferencesSpans(multimodalNoSpans, Map.of(), mustache)).isFalse();
    }

    @Test
    @DisplayName("templateReferencesSpans detects implicit {{spans}} references in messages when variables omits the binding")
    void templateReferencesSpansDetectsImplicitMessageTemplateReference() {
        var mustache = com.comet.opik.api.PromptType.MUSTACHE;
        var spansMessage = List.of(com.comet.opik.api.evaluators.LlmAsJudgeMessage.builder()
                .role(dev.langchain4j.data.message.ChatMessageType.USER)
                .content("Spans: {{spans}}")
                .build());
        var noSpansMessage = List.of(com.comet.opik.api.evaluators.LlmAsJudgeMessage.builder()
                .role(dev.langchain4j.data.message.ChatMessageType.USER)
                .content("Summary: {{summary}}")
                .build());
        // Implicit reference: prompt has {{spans}}, variables map doesn't bind "spans" at all.
        assertThat(OnlineScoringEngine.templateReferencesSpans(spansMessage, Map.of(), mustache)).isTrue();
        // Variables explicitly map "spans" to something else (e.g. a JSONPath) — respect that
        // mapping and don't claim the template references the sentinel.
        assertThat(OnlineScoringEngine.templateReferencesSpans(spansMessage,
                Map.of("spans", "input.spans"), mustache)).isFalse();
        // No {{spans}} in template, no sentinel in variables — false.
        assertThat(OnlineScoringEngine.templateReferencesSpans(noSpansMessage, Map.of(), mustache)).isFalse();
    }

    @Test
    @DisplayName("templateReferencesTrace detects the 'trace' sentinel and implicit {{trace}} references")
    void templateReferencesTraceDetectsSentinelAndImplicitReference() {
        var mustache = PromptType.MUSTACHE;
        var noMessages = List.<LlmAsJudgeMessage>of();
        var traceMessage = List.of(LlmAsJudgeMessage.builder()
                .role(ChatMessageType.USER)
                .content("Trace: {{trace}}")
                .build());

        // Sentinel-valued variable (bare "trace") anywhere in the map.
        assertThat(OnlineScoringEngine.templateReferencesTraceStructure(noMessages, Map.of("t", "trace"), mustache))
                .isTrue();
        // Implicit reference: prompt has {{trace}}, variables map doesn't bind "trace".
        assertThat(OnlineScoringEngine.templateReferencesTraceStructure(traceMessage, Map.of(), mustache)).isTrue();
        // Explicit override to a JSONPath wins — don't treat it as the sentinel.
        assertThat(OnlineScoringEngine.templateReferencesTraceStructure(traceMessage,
                Map.of("trace", "input.trace"), mustache)).isFalse();
        // Case-sensitive, and "input.trace" is not the bare sentinel.
        assertThat(OnlineScoringEngine.templateReferencesTraceStructure(noMessages, Map.of("x", "Trace"), mustache))
                .isFalse();
        assertThat(OnlineScoringEngine.templateReferencesTraceStructure(noMessages,
                Map.of("x", "input.trace"), mustache)).isFalse();
    }

    @Test
    @DisplayName("prepareLlmRequest substitutes a {{trace}}-referencing variable with the supplied structure JSON")
    void prepareLlmRequestInjectsTraceStructure() {
        var evaluatorCode = LlmAsJudgeCode.builder()
                .model(LlmAsJudgeModelParameters.builder()
                        .name("gpt-4o").temperature(0.3).build())
                .messages(List.of(
                        LlmAsJudgeMessage.builder()
                                .role(ChatMessageType.USER)
                                .content("Inspect: {{trace}}")
                                .build()))
                .variables(new LinkedHashMap<>(Map.of("trace", "trace")))
                .schema(List.of())
                .build();
        var trace = createTrace(generator.generate(), generator.generate());
        var traceId = "trace-" + RandomStringUtils.secure().nextAlphanumeric(12);
        var structure = "{\"trace_id\":\"%s\",\"span_tree\":[]}".formatted(traceId);

        var request = OnlineScoringEngine.prepareLlmRequest(evaluatorCode, trace, new InstructionStrategy(),
                PromptType.MUSTACHE, List.of(), structure);

        var allText = request.messages().stream().map(Object::toString).collect(Collectors.joining("\n"));
        assertThat(allText).contains(traceId);
        // Sentinel literal must not leak into the rendered prompt.
        assertThat(allText).doesNotContain("{{trace}}");
    }

    @Test
    @DisplayName("prepareLlmRequest substitutes {{spans}}-referencing variables with the serialized spans list")
    void prepareLlmRequestInjectsSpansFromSentinelVariable() {
        var evaluatorCode = LlmAsJudgeCode.builder()
                .model(com.comet.opik.api.evaluators.LlmAsJudgeModelParameters.builder()
                        .name("gpt-4o").temperature(0.3).build())
                .messages(List.of(
                        com.comet.opik.api.evaluators.LlmAsJudgeMessage.builder()
                                .role(dev.langchain4j.data.message.ChatMessageType.USER)
                                .content("Count the spans: {{mySpans}}")
                                .build()))
                .variables(new java.util.LinkedHashMap<>(Map.of("mySpans", "spans")))
                .schema(List.of())
                .build();
        var trace = createTrace(generator.generate(), generator.generate());
        var span1 = Span.builder()
                .id(generator.generate()).name("span-a").type(SpanType.general)
                .startTime(java.time.Instant.now()).traceId(trace.id()).projectId(trace.projectId())
                .build();
        var span2 = Span.builder()
                .id(generator.generate()).name("span-b").type(SpanType.general)
                .startTime(java.time.Instant.now().plusMillis(1)).traceId(trace.id()).projectId(trace.projectId())
                .build();

        var request = OnlineScoringEngine.prepareLlmRequest(evaluatorCode, trace, new InstructionStrategy(),
                List.of(span2, span1));

        // The {{mySpans}} variable substitution should be the JSON-serialized spans list (sorted
        // by start_time so the wire order is stable). Smoke-check: both span names appear and
        // earliest-start span comes first.
        var allText = request.messages().stream()
                .map(Object::toString)
                .collect(Collectors.joining("\n"));
        assertThat(allText).contains("span-a");
        assertThat(allText).contains("span-b");
        assertThat(allText.indexOf("span-a")).isLessThan(allText.indexOf("span-b"));
        // Sentinel literal should NOT leak into the rendered prompt.
        assertThat(allText).doesNotContain("{{mySpans}}");
    }

    @Test
    @DisplayName("buildSpanTree reconstructs parent/child hierarchy, promotes orphans, sorts siblings by start_time")
    void buildSpanTreeReconstructsHierarchyAndPromotesOrphans() {
        var traceId = generator.generate();
        var projectId = generator.generate();
        var rootId = generator.generate();
        var childA = generator.generate();
        var childB = generator.generate();
        var grandchild = generator.generate();
        var orphanParent = generator.generate(); // intentionally NOT in the input set
        var orphan = generator.generate();
        var now = java.time.Instant.parse("2026-05-19T10:00:00Z");

        // Layout:
        //   root (no parent)
        //     ├── child-A          (sibling, earlier start)
        //     │     └── grandchild
        //     └── child-B          (sibling, later start)
        //   orphan (parent not in input — promoted to root)
        // Input order is shuffled to prove buildSpanTree doesn't rely on it.
        var spans = List.of(
                Span.builder().id(childB).parentSpanId(rootId).name("child-B").type(SpanType.tool)
                        .startTime(now.plusMillis(200)).traceId(traceId).projectId(projectId).build(),
                Span.builder().id(orphan).parentSpanId(orphanParent).name("orphan").type(SpanType.general)
                        .startTime(now.plusMillis(500)).traceId(traceId).projectId(projectId).build(),
                Span.builder().id(grandchild).parentSpanId(childA).name("grandchild").type(SpanType.tool)
                        .startTime(now.plusMillis(150)).traceId(traceId).projectId(projectId).build(),
                Span.builder().id(rootId).parentSpanId(null).name("root").type(SpanType.general)
                        .startTime(now).traceId(traceId).projectId(projectId).build(),
                Span.builder().id(childA).parentSpanId(rootId).name("child-A").type(SpanType.tool)
                        .startTime(now.plusMillis(100)).traceId(traceId).projectId(projectId).build());

        var tree = OnlineScoringEngine.buildSpanTree(spans);

        // Two roots — the real root + the orphan (promoted because its parent isn't in the set).
        // root sorts before orphan because root.startTime < orphan.startTime.
        assertThat(tree).hasSize(2);
        assertThat(tree.get(0).name()).isEqualTo("root");
        assertThat(tree.get(1).name()).isEqualTo("orphan");
        // root has two direct children in start_time order: child-A then child-B.
        assertThat(tree.get(0).spans()).hasSize(2);
        assertThat(tree.get(0).spans().get(0).name()).isEqualTo("child-A");
        assertThat(tree.get(0).spans().get(1).name()).isEqualTo("child-B");
        // child-A has one nested child; child-B is a leaf (spans omitted via NON_NULL).
        assertThat(tree.get(0).spans().get(0).spans()).hasSize(1);
        assertThat(tree.get(0).spans().get(0).spans().get(0).name()).isEqualTo("grandchild");
        assertThat(tree.get(0).spans().get(1).spans()).isNull();
        // Orphan is a leaf with no children.
        assertThat(tree.get(1).spans()).isNull();
    }

    @Test
    @DisplayName("substituted {{spans}} carries only the LLM-relevant fields — not feedback scores, comments, audit metadata")
    void prepareLlmRequestRendersSpansWithLeanProjection() {
        // Build a Span with the full kitchen sink — every field that SpanForLlm intentionally
        // drops gets populated here so the assertion can verify they're absent from the JSON.
        var evaluatorCode = LlmAsJudgeCode.builder()
                .model(com.comet.opik.api.evaluators.LlmAsJudgeModelParameters.builder()
                        .name("gpt-4o").temperature(0.3).build())
                .messages(List.of(
                        com.comet.opik.api.evaluators.LlmAsJudgeMessage.builder()
                                .role(dev.langchain4j.data.message.ChatMessageType.USER)
                                .content("Inspect: {{mySpans}}")
                                .build()))
                .variables(new java.util.LinkedHashMap<>(Map.of("mySpans", "spans")))
                .schema(List.of())
                .build();
        var trace = createTrace(generator.generate(), generator.generate());
        var feedback = com.comet.opik.api.FeedbackScore.builder()
                .name("relevance").value(java.math.BigDecimal.valueOf(0.9)).source(ScoreSource.UI).build();
        var comment = com.comet.opik.api.Comment.builder()
                .id(generator.generate()).text("audit-noise").build();
        var fatSpan = Span.builder()
                .id(generator.generate())
                .name("inspect-tool")
                .type(SpanType.tool)
                .startTime(java.time.Instant.now())
                .traceId(trace.id())
                .projectId(trace.projectId())
                .projectName("audit-project-name")
                .createdBy("audit-creator")
                .lastUpdatedBy("audit-updater")
                .createdAt(java.time.Instant.now())
                .lastUpdatedAt(java.time.Instant.now())
                .feedbackScores(List.of(feedback))
                .comments(List.of(comment))
                .totalEstimatedCost(java.math.BigDecimal.valueOf(0.0042))
                .totalEstimatedCostVersion("v2")
                .tags(java.util.Set.of("audit-tag"))
                .usage(Map.of("prompt_tokens", 42))
                .environment("audit-env")
                .build();

        var request = OnlineScoringEngine.prepareLlmRequest(evaluatorCode, trace, new InstructionStrategy(),
                List.of(fatSpan));

        var allText = request.messages().stream()
                .map(Object::toString)
                .collect(Collectors.joining("\n"));
        // Kept fields: name + type land in the rendered JSON.
        assertThat(allText).contains("inspect-tool");
        assertThat(allText).contains("tool");
        // Dropped fields: not a single audit/score/cost field leaks through. These substring
        // checks are deliberately tight against unique tokens the test set above so they can't
        // accidentally collide with template/system-prompt text.
        assertThat(allText).doesNotContain("audit-noise");
        assertThat(allText).doesNotContain("audit-creator");
        assertThat(allText).doesNotContain("audit-updater");
        assertThat(allText).doesNotContain("audit-project-name");
        assertThat(allText).doesNotContain("audit-tag");
        assertThat(allText).doesNotContain("audit-env");
        assertThat(allText).doesNotContain("feedback_scores");
        assertThat(allText).doesNotContain("comments");
        assertThat(allText).doesNotContain("total_estimated_cost");
        assertThat(allText).doesNotContain("prompt_tokens");
    }

    @Test
    @DisplayName("prepareLlmRequest renders {{spans}} as [] when the trace has no spans, not the literal sentinel")
    void prepareLlmRequestRendersEmptySpansAsJsonArrayWhenTraceHasNoChildren() {
        var evaluatorCode = LlmAsJudgeCode.builder()
                .model(com.comet.opik.api.evaluators.LlmAsJudgeModelParameters.builder()
                        .name("gpt-4o").temperature(0.3).build())
                .messages(List.of(
                        com.comet.opik.api.evaluators.LlmAsJudgeMessage.builder()
                                .role(dev.langchain4j.data.message.ChatMessageType.USER)
                                .content("Spans for this trace: {{mySpans}}")
                                .build()))
                .variables(new java.util.LinkedHashMap<>(Map.of("mySpans", "spans")))
                .schema(List.of())
                .build();
        var trace = createTrace(generator.generate(), generator.generate());

        var request = OnlineScoringEngine.prepareLlmRequest(evaluatorCode, trace, new InstructionStrategy(),
                List.of());

        var allText = request.messages().stream()
                .map(Object::toString)
                .collect(Collectors.joining("\n"));
        // Empty list renders as [], not the literal sentinel "spans" — guards against
        // the bare "spans" value leaking through toVariableMapping when the trace has
        // no children.
        assertThat(allText).contains("Spans for this trace: []");
        assertThat(allText).doesNotContain("Spans for this trace: spans");
        assertThat(allText).doesNotContain("{{mySpans}}");
    }

    @Test
    @DisplayName("prepareLlmRequest substitutes {{spans}} from a template-only reference (no sentinel in variables)")
    void prepareLlmRequestInjectsSpansFromImplicitTemplateReference() {
        // No "spans" key in variables. Mirrors an API-created rule where the caller put
        // {{spans}} in the prompt but didn't (or didn't know to) set the sentinel mapping.
        var evaluatorCode = LlmAsJudgeCode.builder()
                .model(com.comet.opik.api.evaluators.LlmAsJudgeModelParameters.builder()
                        .name("gpt-4o").temperature(0.3).build())
                .messages(List.of(
                        com.comet.opik.api.evaluators.LlmAsJudgeMessage.builder()
                                .role(dev.langchain4j.data.message.ChatMessageType.USER)
                                .content("How many spans? {{spans}}")
                                .build()))
                .variables(new java.util.LinkedHashMap<>(Map.of()))
                .schema(List.of())
                .build();
        var trace = createTrace(generator.generate(), generator.generate());
        var span = Span.builder()
                .id(generator.generate()).name("template-only-span").type(SpanType.general)
                .startTime(java.time.Instant.now()).traceId(trace.id()).projectId(trace.projectId())
                .build();

        var request = OnlineScoringEngine.prepareLlmRequest(evaluatorCode, trace, new InstructionStrategy(),
                List.of(span));

        var allText = request.messages().stream()
                .map(Object::toString)
                .collect(Collectors.joining("\n"));
        assertThat(allText).contains("template-only-span");
        assertThat(allText).doesNotContain("{{spans}}");
    }

    @Test
    @DisplayName("prepareLlmRequest respects an explicit variables mapping over the template-only spans injection")
    void prepareLlmRequestRespectsExplicitSpansMappingOverImplicitInjection() {
        // The user explicitly mapped "spans" to input.foo. Their intent overrides the
        // template-only fallback: substitute the JSONPath value, not the spans list.
        var evaluatorCode = LlmAsJudgeCode.builder()
                .model(com.comet.opik.api.evaluators.LlmAsJudgeModelParameters.builder()
                        .name("gpt-4o").temperature(0.3).build())
                .messages(List.of(
                        com.comet.opik.api.evaluators.LlmAsJudgeMessage.builder()
                                .role(dev.langchain4j.data.message.ChatMessageType.USER)
                                .content("Custom: {{spans}}")
                                .build()))
                .variables(new java.util.LinkedHashMap<>(Map.of("spans", "input.foo")))
                .schema(List.of())
                .build();
        var trace = createTrace(generator.generate(), generator.generate());
        var span = Span.builder()
                .id(generator.generate()).name("should-not-appear").type(SpanType.general)
                .startTime(java.time.Instant.now()).traceId(trace.id()).projectId(trace.projectId())
                .build();

        var request = OnlineScoringEngine.prepareLlmRequest(evaluatorCode, trace, new InstructionStrategy(),
                List.of(span));

        var allText = request.messages().stream()
                .map(Object::toString)
                .collect(Collectors.joining("\n"));
        // The spans list should NOT leak in — the user mapped "spans" to a JSONPath.
        assertThat(allText).doesNotContain("should-not-appear");
    }

    @Test
    @DisplayName("prepareLlmRequest leaves non-spans variables alone when spans are passed")
    void prepareLlmRequestLeavesNonSpansVariablesAloneWhenSpansArePassed() {
        var evaluatorCode = JsonUtils.readValue(TEST_EVALUATOR, LlmAsJudgeCode.class);
        var trace = createTrace(generator.generate(), generator.generate());
        var span = Span.builder()
                .id(generator.generate()).name("ignored-span").type(SpanType.general)
                .startTime(java.time.Instant.now()).traceId(trace.id()).projectId(trace.projectId())
                .build();

        var request = OnlineScoringEngine.prepareLlmRequest(evaluatorCode, trace, new InstructionStrategy(),
                List.of(span));

        // Template variables don't include the "spans" sentinel, so the span shouldn't appear
        // in the rendered prompt — even though spans were available. Stringify the whole message
        // list so the assertion catches the span name wherever it might have landed.
        var allText = request.messages().stream()
                .map(Object::toString)
                .collect(Collectors.joining("\n"));
        assertThat(allText).doesNotContain("ignored-span");
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

        var feedbackScores = OnlineScoringEngine.toFeedbackScores(chatResponse).scores();

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

    @Test
    @DisplayName("skip feedback scores whose value is null in the AI response")
    void whenScoreValueIsNull_thenSkipsThatScoreAndReportsItAsSkipped() {
        var aiMessage = "{\"Relevance\":{\"score\":5,\"reason\":\"applies\"},"
                + "\"Conciseness\":{\"score\":null,\"reason\":\"not applicable to this turn\"},"
                + "\"Technical Accuracy\":{\"score\":4.0,\"reason\":\"good\"}}";
        var chatResponse = ChatResponse.builder()
                .aiMessage(AiMessage.aiMessage(aiMessage))
                .build();

        var parsed = OnlineScoringEngine.toFeedbackScores(chatResponse);

        assertThat(parsed.scores()).hasSize(2);
        assertThat(parsed.scores()).extracting(FeedbackScoreBatchItem::name)
                .containsExactlyInAnyOrder("Relevance", "Technical Accuracy");
        assertThat(parsed.nullScoreNames()).containsExactly("Conciseness");
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

    @ParameterizedTest
    @MethodSource
    @DisplayName("renderMessages should support keys with dots in their names")
    void testExtractFromJsonWithDotKey(String key, String jsonBody) {
        // variable mapping: variable 'testVar' maps to 'input.' + key
        var variables = Map.of("testVar", "input." + key);
        var trace = Trace.builder()
                .id(UUID.randomUUID())
                .projectName(PROJECT_NAME)
                .projectId(UUID.randomUUID())
                .createdBy(USER_NAME)
                .input(JsonUtils.getJsonNodeFromString(jsonBody))
                .build();

        // Render a message using the variable
        var template = List.of(LlmAsJudgeMessage.builder()
                .role(ChatMessageType.USER)
                .content("Test value: {{testVar}}")
                .build());
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

    private static Stream<Arguments> testExtractFromJsonWithDifferentValueTypes() {
        // Note: After Mustache rendering, double quotes in JSON become HTML-encoded as &quot;
        // Complex objects and arrays are now serialized as proper JSON strings (not Java toString())
        return Stream.of(
                // ===== TOP-LEVEL KEYS =====
                // JSON scalars: Strings, Numbers (Integers and Decimals), Booleans, Null
                arguments("key", "{\"key\":\"text\"}", "text"),
                arguments("key", "{\"key\":123}", "123"),
                arguments("key", "{\"key\":1.23}", "1.23"),
                arguments("key", "{\"key\":true}", "true"),
                arguments("key", "{\"key\":null}", ""),
                // JSON objects - now serialized as proper JSON (with HTML-encoded quotes)
                arguments("key", "{\"key\":{\"object\":\"text\"}}", "{&quot;object&quot;:&quot;text&quot;}"),
                arguments("key", "{\"key\":{\"object\":123}}", "{&quot;object&quot;:123}"),
                arguments("key", "{\"key\":{\"object\":1.23}}", "{&quot;object&quot;:1.23}"),
                arguments("key", "{\"key\":{\"object\":true}}", "{&quot;object&quot;:true}"),
                arguments("key", "{\"key\":{\"object\":null}}", "{}"),
                arguments("key", "{\"key\":{\"a\":1,\"b\":2}}", "{&quot;a&quot;:1,&quot;b&quot;:2}"),
                arguments("key", "{\"key\":{}}", "{}"),
                // JSON Arrays - now serialized as proper JSON
                arguments("key", "{\"key\":[\"a\",\"b\"]}", "[&quot;a&quot;,&quot;b&quot;]"),
                arguments("key", "{\"key\":[1,2]}", "[1,2]"),
                arguments("key", "{\"key\":[1.2,3.4]}", "[1.2,3.4]"),
                arguments("key", "{\"key\":[true,false]}", "[true,false]"),
                arguments("key", "{\"key\":[null,null]}", "[null,null]"),
                arguments("key", "{\"key\":[]}", "[]"),

                // ===== NESTED KEYS =====
                // JSON scalars: Strings, Numbers (Integers and Decimals), Booleans, Null
                // Types other than String under nested keys were causing the original production bug
                arguments("nested.key", "{\"nested\":{\"key\":\"text\"}}", "text"),
                arguments("nested.key", "{\"nested\":{\"key\":123}}", "123"),
                arguments("nested.key", "{\"nested\":{\"key\":1.23}}", "1.23"),
                arguments("nested.key", "{\"nested\":{\"key\":true}}", "true"),
                arguments("nested.key", "{\"nested\":{\"key\":null}}", ""),
                // Objects (with all scalar types inside) - now serialized as proper JSON
                arguments("nested.key", "{\"nested\":{\"key\":{\"object\":\"text\"}}}",
                        "{&quot;object&quot;:&quot;text&quot;}"),
                arguments("nested.key", "{\"nested\":{\"key\":{\"object\":123}}}", "{&quot;object&quot;:123}"),
                arguments("nested.key", "{\"nested\":{\"key\":{\"object\":1.23}}}", "{&quot;object&quot;:1.23}"),
                arguments("nested.key", "{\"nested\":{\"key\":{\"object\":true}}}", "{&quot;object&quot;:true}"),
                arguments("nested.key", "{\"nested\":{\"key\":{\"object\":null}}}", "{}"),
                arguments("nested.key", "{\"nested\":{\"key\":{\"x\":10,\"y\":20}}}",
                        "{&quot;x&quot;:10,&quot;y&quot;:20}"),
                arguments("nested.key", "{\"nested\":{\"key\":{}}}", "{}"),
                // Arrays (with all scalar types) - now serialized as proper JSON
                arguments("nested.key", "{\"nested\":{\"key\":[\"a\",\"b\"]}}", "[&quot;a&quot;,&quot;b&quot;]"),
                arguments("nested.key", "{\"nested\":{\"key\":[1,2]}}", "[1,2]"),
                arguments("nested.key", "{\"nested\":{\"key\":[1.2,3.4]}}", "[1.2,3.4]"),
                arguments("nested.key", "{\"nested\":{\"key\":[true,false]}}", "[true,false]"),
                arguments("nested.key", "{\"nested\":{\"key\":[null,null]}}", "[null,null]"),
                arguments("nested.key", "{\"nested\":{\"key\":[]}}", "[]"));
    }

    @ParameterizedTest
    @MethodSource
    @DisplayName("renderMessages should handle different JSON value types without ClassCastException")
    void testExtractFromJsonWithDifferentValueTypes(String key, String jsonBody, String expectedValue) {
        var variables = Map.of("testVar", "input." + key);
        var trace = factory.manufacturePojo(Trace.class).toBuilder()
                .input(JsonUtils.getJsonNodeFromString(jsonBody))
                .build();

        // Render a message using the variable
        var template = List.of(LlmAsJudgeMessage.builder()
                .role(ChatMessageType.USER)
                .content("Test value: {{testVar}}")
                .build());
        var rendered = OnlineScoringEngine.renderMessages(template, variables, trace);
        assertThat(rendered).hasSize(1);
        var userMessage = rendered.getFirst();
        assertThat(userMessage).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) userMessage).singleText()).contains("Test value: " + expectedValue);
    }

    @Test
    @DisplayName("should handle multimodal content with multiple images")
    void shouldHandleMultimodalContent_whenMultipleImages() {
        var expectedUrl1 = "https://example.com/image1.png";
        var expectedUrl2 = "https://example.com/image2.png?width=200&height=300";

        // Use new array-based multimodal format
        var contentParts = List.of(
                LlmAsJudgeMessageContent.builder()
                        .type("text")
                        .text("First image: ")
                        .build(),
                LlmAsJudgeMessageContent.builder()
                        .type("image_url")
                        .imageUrl(LlmAsJudgeMessageContent.ImageUrl.builder()
                                .url(expectedUrl1)
                                .build())
                        .build(),
                LlmAsJudgeMessageContent.builder()
                        .type("text")
                        .text("Second image: ")
                        .build(),
                LlmAsJudgeMessageContent.builder()
                        .type("image_url")
                        .imageUrl(LlmAsJudgeMessageContent.ImageUrl.builder()
                                .url(expectedUrl2)
                                .build())
                        .build());

        var messages = List.of(
                LlmAsJudgeMessage.builder()
                        .role(ChatMessageType.USER)
                        .contentArray(contentParts)
                        .build());

        var renderedMessages = OnlineScoringEngine.renderMessages(
                messages, Map.of(), Trace.builder().build());

        assertThat(renderedMessages).hasSize(1);
        var userMessage = renderedMessages.getFirst();
        assertThat(userMessage).isInstanceOf(UserMessage.class);
        var parts = ((UserMessage) userMessage).contents();
        assertThat(parts).hasSize(4);
        assertThat(parts.get(0)).isInstanceOf(TextContent.class);
        assertThat(((TextContent) parts.get(0)).text()).isEqualTo("First image: ");
        assertThat(parts.get(1)).isInstanceOf(ImageContent.class);
        assertThat(((ImageContent) parts.get(1)).image().url().toString()).isEqualTo(expectedUrl1);
        assertThat(parts.get(2)).isInstanceOf(TextContent.class);
        assertThat(((TextContent) parts.get(2)).text()).isEqualTo("Second image: ");
        assertThat(parts.get(3)).isInstanceOf(ImageContent.class);
        assertThat(((ImageContent) parts.get(3)).image().url().toString()).isEqualTo(expectedUrl2);
    }

    @Test
    @DisplayName("should render template variables in multimodal content with image URL")
    void shouldRenderTemplateVariables_whenMultimodalContentWithImageUrl() {
        var expectedUrl = "https://example.com/image.png?width=200&height=300";

        // Use new array-based multimodal format with Mustache variables
        var contentParts = List.of(
                LlmAsJudgeMessageContent.builder()
                        .type("text")
                        .text("My image: ")
                        .build(),
                LlmAsJudgeMessageContent.builder()
                        .type("image_url")
                        .imageUrl(LlmAsJudgeMessageContent.ImageUrl.builder()
                                .url("{{image_url}}")
                                .build())
                        .build());

        var messages = List.of(
                LlmAsJudgeMessage.builder()
                        .role(ChatMessageType.USER)
                        .contentArray(contentParts)
                        .build());

        var rendered = OnlineScoringEngine.renderMessages(
                messages, Map.of("image_url", expectedUrl), Trace.builder().build());

        assertThat(rendered).hasSize(1);
        assertThat(rendered.getFirst()).isInstanceOf(UserMessage.class);
        var userMessage = (UserMessage) rendered.getFirst();
        var parts = userMessage.contents();
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0)).isInstanceOf(TextContent.class);
        assertThat(((TextContent) parts.get(0)).text()).isEqualTo("My image: ");
        assertThat(parts.get(1)).isInstanceOf(ImageContent.class);
        assertThat(((ImageContent) parts.get(1)).image().url().toString()).isEqualTo(expectedUrl);
    }

    // ========================================
    // Array Message Format Tests
    // ========================================

    private static final String INPUT_WITH_VIDEO_URL = """
            {
              "video_url": "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
              "description": "Sample marketing video"
            }
            """;

    private static final String INPUT_WITH_BASE64_VIDEO = """
            {
              "video_data": "data:video/mp4;base64,AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAAIZnJlZQAACKBtZGF0",
              "description": "Base64 encoded video"
            }
            """;

    private static final String INPUT_WITH_IMAGE_AND_VIDEO = """
            {
              "image_url": "http://example.com/image.jpg",
              "video_url": "http://example.com/video.mp4",
              "description": "Mixed media content"
            }
            """;

    private static final String OUTPUT_SIMPLE_VIDEO = """
            {
              "result": "Video analyzed successfully"
            }
            """;

    private static final String INPUT_SIMPLE_VIDEO = """
            {
              "prompt": "Generate a video"
            }
            """;

    @Test
    @DisplayName("should handle string content with text only")
    void shouldHandleStringContent_whenTextOnly() {
        // Given
        var message = LlmAsJudgeMessage.builder()
                .role(ChatMessageType.USER)
                .content("Analyze this video")
                .build();

        // When
        var isString = message.isStringContent();
        var isArray = message.isStructuredContent();

        // Then
        assertThat(isString).isTrue();
        assertThat(isArray).isFalse();
        assertThat(message.asString()).isEqualTo("Analyze this video");
    }

    @Test
    @DisplayName("should handle array content with text only")
    void shouldHandleArrayContent_whenTextOnly() {
        // Given
        var contentParts = List.of(
                LlmAsJudgeMessageContent.builder()
                        .type("text")
                        .text("Analyze this video")
                        .build());

        var message = LlmAsJudgeMessage.builder()
                .role(ChatMessageType.USER)
                .contentArray(contentParts)
                .build();

        // When
        var isString = message.isStringContent();
        var isArray = message.isStructuredContent();

        // Then
        assertThat(isString).isFalse();
        assertThat(isArray).isTrue();
        assertThat(message.asContentList()).hasSize(1);
        assertThat(message.asContentList().getFirst().type()).isEqualTo("text");
        assertThat(message.asContentList().getFirst().text()).isEqualTo("Analyze this video");
    }

    @Test
    @DisplayName("should handle array content with video URL")
    void shouldHandleArrayContent_whenVideoUrl() {
        // Given
        var videoUrl = "http://example.com/video.mp4";
        var contentParts = List.of(
                LlmAsJudgeMessageContent.builder()
                        .type("text")
                        .text("Summarize this video")
                        .build(),
                LlmAsJudgeMessageContent.builder()
                        .type("video_url")
                        .videoUrl(LlmAsJudgeMessageContent.VideoUrl.builder()
                                .url(videoUrl)
                                .build())
                        .build());

        var message = LlmAsJudgeMessage.builder()
                .role(ChatMessageType.USER)
                .contentArray(contentParts)
                .build();

        // When & Then
        assertThat(message.isStructuredContent()).isTrue();
        assertThat(message.asContentList()).hasSize(2);

        var textPart = message.asContentList().get(0);
        assertThat(textPart.type()).isEqualTo("text");
        assertThat(textPart.text()).isEqualTo("Summarize this video");

        var videoPart = message.asContentList().get(1);
        assertThat(videoPart.type()).isEqualTo("video_url");
        assertThat(videoPart.videoUrl()).isNotNull();
        assertThat(videoPart.videoUrl().url()).isEqualTo(videoUrl);
    }

    @Test
    @DisplayName("should handle array content with base64 video")
    void shouldHandleArrayContent_whenBase64Video() {
        // Given
        var base64Video = "data:video/mp4;base64,AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAAIZnJlZQAACKBtZGF0";
        var contentParts = List.of(
                LlmAsJudgeMessageContent.builder()
                        .type("video_url")
                        .videoUrl(LlmAsJudgeMessageContent.VideoUrl.builder()
                                .url(base64Video)
                                .build())
                        .build());

        var message = LlmAsJudgeMessage.builder()
                .role(ChatMessageType.USER)
                .contentArray(contentParts)
                .build();

        // When & Then
        assertThat(message.isStructuredContent()).isTrue();
        var videoPart = message.asContentList().getFirst();
        assertThat(videoPart.videoUrl().url()).startsWith("data:video/mp4;base64,");
    }

    @Test
    @DisplayName("should handle array content with mixed media")
    void shouldHandleArrayContent_whenMixedMedia() {
        // Given
        var contentParts = List.of(
                LlmAsJudgeMessageContent.builder()
                        .type("text")
                        .text("Compare these:")
                        .build(),
                LlmAsJudgeMessageContent.builder()
                        .type("image_url")
                        .imageUrl(LlmAsJudgeMessageContent.ImageUrl.builder()
                                .url("http://example.com/image.jpg")
                                .detail("auto")
                                .build())
                        .build(),
                LlmAsJudgeMessageContent.builder()
                        .type("video_url")
                        .videoUrl(LlmAsJudgeMessageContent.VideoUrl.builder()
                                .url("http://example.com/video.mp4")
                                .build())
                        .build());

        var message = LlmAsJudgeMessage.builder()
                .role(ChatMessageType.USER)
                .contentArray(contentParts)
                .build();

        // When & Then
        assertThat(message.isStructuredContent()).isTrue();
        assertThat(message.asContentList()).hasSize(3);
        assertThat(message.asContentList().get(0).type()).isEqualTo("text");
        assertThat(message.asContentList().get(1).type()).isEqualTo("image_url");
        assertThat(message.asContentList().get(2).type()).isEqualTo("video_url");
    }

    @Test
    @DisplayName("should render template variables in array content with video URL from input")
    void shouldRenderTemplateVariables_whenVideoUrlFromInput() {
        // Given
        var trace = Trace.builder()
                .id(UUID.randomUUID())
                .input(JsonUtils.getJsonNodeFromString(INPUT_WITH_VIDEO_URL))
                .output(JsonUtils.getJsonNodeFromString(OUTPUT_SIMPLE_VIDEO))
                .build();

        var contentParts = List.of(
                LlmAsJudgeMessageContent.builder()
                        .type("text")
                        .text("Analyze this video: {{description}}")
                        .build(),
                LlmAsJudgeMessageContent.builder()
                        .type("video_url")
                        .videoUrl(LlmAsJudgeMessageContent.VideoUrl.builder()
                                .url("{{video_url}}")
                                .build())
                        .build());

        var message = LlmAsJudgeMessage.builder()
                .role(ChatMessageType.USER)
                .contentArray(contentParts)
                .build();

        var variables = Map.of(
                "video_url", "input.video_url",
                "description", "input.description");

        // When
        var renderedMessages = OnlineScoringEngine.renderMessages(
                List.of(message),
                variables,
                trace);

        // Then
        assertThat(renderedMessages).hasSize(1);
        var userMessage = (UserMessage) renderedMessages.getFirst();
        assertThat(userMessage.contents()).hasSize(2);

        var textContent = (TextContent) userMessage.contents().get(0);
        assertThat(textContent.text()).isEqualTo("Analyze this video: Sample marketing video");

        var videoContent = (VideoContent) userMessage.contents().get(1);
        assertThat(videoContent.video().url().toString())
                .isEqualTo("http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4");
    }

    @Test
    @DisplayName("should render template variables with base64 video from input")
    void shouldRenderTemplateVariables_whenBase64VideoFromInput() {
        // Given
        var trace = Trace.builder()
                .id(UUID.randomUUID())
                .input(JsonUtils.getJsonNodeFromString(INPUT_WITH_BASE64_VIDEO))
                .output(JsonUtils.getJsonNodeFromString(OUTPUT_SIMPLE_VIDEO))
                .build();

        var contentParts = List.of(
                LlmAsJudgeMessageContent.builder()
                        .type("text")
                        .text("Process: {{description}}")
                        .build(),
                LlmAsJudgeMessageContent.builder()
                        .type("video_url")
                        .videoUrl(LlmAsJudgeMessageContent.VideoUrl.builder()
                                .url("{{video_data}}")
                                .build())
                        .build());

        var message = LlmAsJudgeMessage.builder()
                .role(ChatMessageType.USER)
                .contentArray(contentParts)
                .build();

        var variables = Map.of(
                "video_data", "input.video_data",
                "description", "input.description");

        // When
        var renderedMessages = OnlineScoringEngine.renderMessages(
                List.of(message),
                variables,
                trace);

        // Then
        assertThat(renderedMessages).hasSize(1);
        var userMessage = (UserMessage) renderedMessages.getFirst();
        assertThat(userMessage.contents()).hasSize(2);

        var videoContent = (VideoContent) userMessage.contents().get(1);
        assertThat(videoContent.video().url().toString()).startsWith("data:video/mp4;base64,");
    }

    @Test
    @DisplayName("should render mixed media with template variables")
    void shouldRenderTemplateVariables_whenMixedMedia() {
        // Given
        var trace = Trace.builder()
                .id(UUID.randomUUID())
                .input(JsonUtils.getJsonNodeFromString(INPUT_WITH_IMAGE_AND_VIDEO))
                .output(JsonUtils.getJsonNodeFromString(OUTPUT_SIMPLE_VIDEO))
                .build();

        var contentParts = List.of(
                LlmAsJudgeMessageContent.builder()
                        .type("text")
                        .text("Compare: {{description}}")
                        .build(),
                LlmAsJudgeMessageContent.builder()
                        .type("image_url")
                        .imageUrl(LlmAsJudgeMessageContent.ImageUrl.builder()
                                .url("{{image_url}}")
                                .build())
                        .build(),
                LlmAsJudgeMessageContent.builder()
                        .type("video_url")
                        .videoUrl(LlmAsJudgeMessageContent.VideoUrl.builder()
                                .url("{{video_url}}")
                                .build())
                        .build());

        var message = LlmAsJudgeMessage.builder()
                .role(ChatMessageType.USER)
                .contentArray(contentParts)
                .build();

        var variables = Map.of(
                "image_url", "input.image_url",
                "video_url", "input.video_url",
                "description", "input.description");

        // When
        var renderedMessages = OnlineScoringEngine.renderMessages(
                List.of(message),
                variables,
                trace);

        // Then
        assertThat(renderedMessages).hasSize(1);
        var userMessage = (UserMessage) renderedMessages.getFirst();
        assertThat(userMessage.contents()).hasSize(3);

        var textContent = (TextContent) userMessage.contents().get(0);
        assertThat(textContent.text()).isEqualTo("Compare: Mixed media content");

        var imageContent = (ImageContent) userMessage.contents().get(1);
        assertThat(imageContent.image().url().toString()).isEqualTo("http://example.com/image.jpg");

        var videoContent = (VideoContent) userMessage.contents().get(2);
        assertThat(videoContent.video().url().toString()).isEqualTo("http://example.com/video.mp4");
    }

    @Test
    @DisplayName("should render template variables in multimodal content with video URL")
    void shouldRenderTemplateVariables_whenMultimodalContentWithVideoUrl() {
        // Given
        var trace = Trace.builder()
                .id(UUID.randomUUID())
                .input(JsonUtils.getJsonNodeFromString(INPUT_WITH_VIDEO_URL))
                .output(JsonUtils.getJsonNodeFromString(OUTPUT_SIMPLE_VIDEO))
                .build();

        // Use new array-based multimodal format
        var contentParts = List.of(
                LlmAsJudgeMessageContent.builder()
                        .type("text")
                        .text("Analyze: {{description}} ")
                        .build(),
                LlmAsJudgeMessageContent.builder()
                        .type("video_url")
                        .videoUrl(LlmAsJudgeMessageContent.VideoUrl.builder()
                                .url("{{video_url}}")
                                .build())
                        .build());

        var message = LlmAsJudgeMessage.builder()
                .role(ChatMessageType.USER)
                .contentArray(contentParts)
                .build();

        var variables = Map.of(
                "video_url", "input.video_url",
                "description", "input.description");

        // When
        var renderedMessages = OnlineScoringEngine.renderMessages(
                List.of(message),
                variables,
                trace);

        // Then
        assertThat(renderedMessages).hasSize(1);
        var userMessage = (UserMessage) renderedMessages.getFirst();
        assertThat(userMessage.contents()).hasSize(2);

        var textContent = (TextContent) userMessage.contents().get(0);
        assertThat(textContent.text()).isEqualTo("Analyze: Sample marketing video ");

        var videoContent = (VideoContent) userMessage.contents().get(1);
        assertThat(videoContent.video().url().toString())
                .isEqualTo("http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4");
    }

    @Test
    @DisplayName("should handle empty content parts list")
    void shouldHandleEmptyContentParts() {
        // Given
        var message = LlmAsJudgeMessage.builder()
                .role(ChatMessageType.USER)
                .contentArray(List.of())
                .build();

        // When
        var isArray = message.isStructuredContent();

        // Then
        assertThat(isArray).isTrue();
        assertThat(message.asContentList()).isEmpty();
    }

    @Test
    @DisplayName("should render video URL from output field")
    void shouldRenderTemplateVariables_whenVideoUrlFromOutput() {
        // Given
        var outputWithVideo = """
                {
                  "video_result": "http://example.com/generated-video.mp4",
                  "status": "completed"
                }
                """;

        var trace = Trace.builder()
                .id(UUID.randomUUID())
                .input(JsonUtils.getJsonNodeFromString(INPUT_SIMPLE_VIDEO))
                .output(JsonUtils.getJsonNodeFromString(outputWithVideo))
                .build();

        var contentParts = List.of(
                LlmAsJudgeMessageContent.builder()
                        .type("text")
                        .text("Review this generated video:")
                        .build(),
                LlmAsJudgeMessageContent.builder()
                        .type("video_url")
                        .videoUrl(LlmAsJudgeMessageContent.VideoUrl.builder()
                                .url("{{video_result}}")
                                .build())
                        .build());

        var message = LlmAsJudgeMessage.builder()
                .role(ChatMessageType.USER)
                .contentArray(contentParts)
                .build();

        var variables = Map.of(
                "video_result", "output.video_result");

        // When
        var renderedMessages = OnlineScoringEngine.renderMessages(
                List.of(message),
                variables,
                trace);

        // Then
        assertThat(renderedMessages).hasSize(1);
        var userMessage = (UserMessage) renderedMessages.getFirst();
        assertThat(userMessage.contents()).hasSize(2);

        var videoContent = (VideoContent) userMessage.contents().get(1);
        assertThat(videoContent.video().url().toString()).isEqualTo("http://example.com/generated-video.mp4");
    }

    @Test
    @DisplayName("render span message templates")
    void testRenderSpanTemplate() {
        var evaluatorCode = JsonUtils.readValue(TEST_EVALUATOR,
                AutomationRuleEvaluatorSpanLlmAsJudge.SpanLlmAsJudgeCode.class);
        var spanId = generator.generate();
        var projectId = generator.generate();
        var span = createSpan(spanId, projectId);
        var renderedMessages = OnlineScoringEngine.renderMessages(
                evaluatorCode.messages(), evaluatorCode.variables(), span);

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
    @DisplayName("prepare span LLM request with tool-calling strategy")
    void testPrepareSpanLlmRequestWithToolCallingStrategy() {
        var evaluatorCode = JsonUtils.readValue(TEST_EVALUATOR,
                AutomationRuleEvaluatorSpanLlmAsJudge.SpanLlmAsJudgeCode.class);
        var span = createSpan(generator.generate(), generator.generate());

        var request = OnlineScoringEngine.prepareSpanLlmRequest(evaluatorCode, span, new ToolCallingStrategy());

        assertThat(request.responseFormat()).isNotNull();
        var expectedSchema = createTestSchema();
        assertThat(request.responseFormat().jsonSchema().rootElement()).isEqualTo(expectedSchema);
    }

    @Test
    @DisplayName("prepare span LLM request with instruction strategy")
    void testPrepareSpanLlmRequestWithInstructionStrategy() {
        var evaluatorCode = JsonUtils.readValue(TEST_EVALUATOR,
                AutomationRuleEvaluatorSpanLlmAsJudge.SpanLlmAsJudgeCode.class);
        var span = createSpan(generator.generate(), generator.generate());

        var request = OnlineScoringEngine.prepareSpanLlmRequest(evaluatorCode, span, new InstructionStrategy());

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

    @Test
    @DisplayName("templateReferencesSpanStructure detects the 'span' sentinel and implicit {{span}} references")
    void templateReferencesSpanStructureDetectsSentinelAndImplicitReference() {
        var mustache = PromptType.MUSTACHE;
        var noMessages = List.<LlmAsJudgeMessage>of();
        var spanMessage = List.of(LlmAsJudgeMessage.builder()
                .role(ChatMessageType.USER)
                .content("Span: {{span}}")
                .build());

        // Sentinel-valued variable (bare "span") anywhere in the map.
        assertThat(OnlineScoringEngine.templateReferencesSpanStructure(noMessages, Map.of("s", "span"), mustache))
                .isTrue();
        // Implicit reference: prompt has {{span}}, variables map doesn't bind "span".
        assertThat(OnlineScoringEngine.templateReferencesSpanStructure(spanMessage, Map.of(), mustache)).isTrue();
        // Explicit override to a JSONPath wins — don't treat it as the sentinel.
        assertThat(OnlineScoringEngine.templateReferencesSpanStructure(spanMessage,
                Map.of("span", "input.span"), mustache)).isFalse();
        // Case-sensitive, and "input.span" / the plural "spans" are not the bare "span" sentinel.
        assertThat(OnlineScoringEngine.templateReferencesSpanStructure(noMessages, Map.of("x", "Span"), mustache))
                .isFalse();
        assertThat(OnlineScoringEngine.templateReferencesSpanStructure(noMessages, Map.of("x", "spans"), mustache))
                .isFalse();
    }

    @Test
    @DisplayName("prepareSpanLlmRequest (tool-mode) substitutes a {{span}}-referencing variable with the structure JSON")
    void prepareSpanLlmRequestInjectsSpanStructure() {
        var evaluatorCode = AutomationRuleEvaluatorSpanLlmAsJudge.SpanLlmAsJudgeCode.builder()
                .model(LlmAsJudgeModelParameters.builder()
                        .name("gpt-4o").temperature(0.3).build())
                .messages(List.of(
                        LlmAsJudgeMessage.builder()
                                .role(ChatMessageType.USER)
                                .content("Inspect: {{span}}")
                                .build()))
                .variables(new LinkedHashMap<>(Map.of("span", "span")))
                .schema(List.of())
                .build();
        var span = createSpan(generator.generate(), generator.generate());
        var spanRef = "span-" + RandomStringUtils.secure().nextAlphanumeric(12);
        var structure = "{\"span_id\":\"%s\",\"attachments\":[]}".formatted(spanRef);

        var request = OnlineScoringEngine.prepareSpanLlmRequest(evaluatorCode, span, new InstructionStrategy(),
                4_000, "drill hint", structure);

        var allText = request.messages().stream().map(Object::toString).collect(Collectors.joining("\n"));
        assertThat(allText).contains(spanRef);
        // Sentinel literal must not leak into the rendered prompt.
        assertThat(allText).doesNotContain("{{span}}");
    }

    @Test
    @DisplayName("prepareSpanLlmRequest (inline) injects the {{span}} structure without capping")
    void prepareSpanLlmRequestInlineInjectsSpanStructure() {
        var evaluatorCode = AutomationRuleEvaluatorSpanLlmAsJudge.SpanLlmAsJudgeCode.builder()
                .model(LlmAsJudgeModelParameters.builder()
                        .name("gpt-4o").temperature(0.3).build())
                .messages(List.of(
                        LlmAsJudgeMessage.builder()
                                .role(ChatMessageType.USER)
                                .content("Inspect: {{span}}")
                                .build()))
                .variables(new LinkedHashMap<>(Map.of("span", "span")))
                .schema(List.of())
                .build();
        var span = createSpan(generator.generate(), generator.generate());
        var spanRef = "span-" + RandomStringUtils.secure().nextAlphanumeric(12);
        var structure = "{\"span_id\":\"%s\",\"attachments\":[]}".formatted(spanRef);

        var request = OnlineScoringEngine.prepareSpanLlmRequest(evaluatorCode, span,
                new InstructionStrategy(), structure);

        var allText = request.messages().stream().map(Object::toString).collect(Collectors.joining("\n"));
        assertThat(allText).contains(spanRef);
        assertThat(allText).doesNotContain("{{span}}");
    }

    @Test
    @DisplayName("prepareSpanLlmRequest renders {{span}} as {} when no structure is supplied, not the literal sentinel")
    void prepareSpanLlmRequestRendersEmptyStructureWhenNull() {
        var evaluatorCode = AutomationRuleEvaluatorSpanLlmAsJudge.SpanLlmAsJudgeCode.builder()
                .model(LlmAsJudgeModelParameters.builder()
                        .name("gpt-4o").temperature(0.3).build())
                .messages(List.of(
                        LlmAsJudgeMessage.builder()
                                .role(ChatMessageType.USER)
                                .content("Inspect: {{span}}")
                                .build()))
                .variables(new LinkedHashMap<>(Map.of("span", "span")))
                .schema(List.of())
                .build();
        var span = createSpan(generator.generate(), generator.generate());

        var request = OnlineScoringEngine.prepareSpanLlmRequest(evaluatorCode, span,
                new InstructionStrategy(), null);

        var allText = request.messages().stream().map(Object::toString).collect(Collectors.joining("\n"));
        assertThat(allText).contains("Inspect: {}");
        assertThat(allText).doesNotContain("{{span}}");
    }

    private Span createSpan(UUID spanId, UUID projectId) {
        return Span.builder()
                .id(spanId)
                .projectName(PROJECT_NAME)
                .projectId(projectId)
                .createdBy(USER_NAME)
                .traceId(generator.generate())
                .input(JsonUtils.getJsonNodeFromString(INPUT))
                .output(JsonUtils.getJsonNodeFromString(OUTPUT))
                .startTime(java.time.Instant.now())
                .build();
    }
}
