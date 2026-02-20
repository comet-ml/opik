package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import com.comet.opik.api.LogItem.LogLevel;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge.TraceThreadLlmAsJudgeCode;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.evaluators.LlmAsJudgeMessage;
import com.comet.opik.api.evaluators.LlmAsJudgeMessageContent;
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
import com.comet.opik.domain.llm.MessageContentNormalizer;
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
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
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

    private static final String IMAGE_PLACEHOLDER = MessageContentNormalizer.IMAGE_PLACEHOLDER_START + "%s"
            + MessageContentNormalizer.IMAGE_PLACEHOLDER_END;

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

        var evaluatorCode = JsonUtils.readValue(TEST_EVALUATOR, LlmAsJudgeCode.class);

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
    @DisplayName("test filtering evaluators by trace metadata")
    void testFilteringEvaluatorsByTraceMetadata(OnlineScoringSampler onlineScoringSampler) throws Exception {
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

        var trace = createTrace(traceId, projectId).toBuilder()
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
    void testUserFacingLogErrorWhenAIProviderFails(OnlineScoringSampler onlineScoringSampler) throws Exception {
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

    private Trace createTrace(UUID traceId, UUID projectId) throws JsonProcessingException {
        return Trace.builder()
                .id(traceId)
                .projectName(PROJECT_NAME)
                .projectId(projectId)
                .createdBy(USER_NAME)
                .input(JsonUtils.getJsonNodeFromString(INPUT))
                .output(JsonUtils.getJsonNodeFromString(OUTPUT)).build();
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
    void testVariableMapping() throws JsonProcessingException {
        var evaluatorCode = JsonUtils.readValue(TEST_EVALUATOR, LlmAsJudgeCode.class);
        var variableMappings = OnlineScoringEngine.toVariableMapping(evaluatorCode.variables());

        assertThat(variableMappings).hasSize(6);

        var varSummary = variableMappings.getFirst();
        assertThat(varSummary.traceSection()).isEqualTo(OnlineScoringDataExtractor.TraceSection.INPUT);
        assertThat(varSummary.jsonPath()).isEqualTo("$.questions.question1");

        var varInstruction = variableMappings.get(1);
        assertThat(varInstruction.traceSection()).isEqualTo(OnlineScoringDataExtractor.TraceSection.OUTPUT);
        assertThat(varInstruction.jsonPath()).isEqualTo("$.output");

        var varNonUsed = variableMappings.get(2);
        assertThat(varNonUsed.traceSection()).isEqualTo(OnlineScoringDataExtractor.TraceSection.INPUT);
        assertThat(varNonUsed.jsonPath()).isEqualTo("$.questions.question2");

        var varToFail = variableMappings.get(3);
        assertThat(varToFail.traceSection()).isEqualTo(OnlineScoringDataExtractor.TraceSection.METADATA);
        assertThat(varToFail.jsonPath()).isEqualTo("$.nonexistent.path");

        var actualVarNonexistent = variableMappings.get(4);
        var expectedVarNonexistent = OnlineScoringDataExtractor.MessageVariableMapping.builder()
                .variableName("nonexistent")
                .valueToReplace("some.nonexistent.path")
                .build();
        assertThat(actualVarNonexistent).isEqualTo(expectedVarNonexistent);

        var actualVarLiteral = variableMappings.get(5);
        var expectedVarLiteral = OnlineScoringDataExtractor.MessageVariableMapping.builder()
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
        assertThat(inputMapping.traceSection()).isEqualTo(OnlineScoringDataExtractor.TraceSection.INPUT);
        assertThat(inputMapping.jsonPath()).isEqualTo("$");

        // Test "output" maps to root "$"
        var outputMapping = variableMappings.stream()
                .filter(mapping -> "full_output".equals(mapping.variableName()))
                .findFirst()
                .orElseThrow();
        assertThat(outputMapping.traceSection()).isEqualTo(OnlineScoringDataExtractor.TraceSection.OUTPUT);
        assertThat(outputMapping.jsonPath()).isEqualTo("$");

        // Test "metadata" maps to root "$"
        var metadataMapping = variableMappings.stream()
                .filter(mapping -> "full_metadata".equals(mapping.variableName()))
                .findFirst()
                .orElseThrow();
        assertThat(metadataMapping.traceSection()).isEqualTo(OnlineScoringDataExtractor.TraceSection.METADATA);
        assertThat(metadataMapping.jsonPath()).isEqualTo("$");

        // Test subtree works correctly
        var subtreeMapping = variableMappings.stream()
                .filter(mapping -> "subtree".equals(mapping.variableName()))
                .findFirst()
                .orElseThrow();
        assertThat(subtreeMapping.traceSection()).isEqualTo(OnlineScoringDataExtractor.TraceSection.INPUT);
        assertThat(subtreeMapping.jsonPath()).isEqualTo("$.questions");

        // Test nested field still works correctly
        var nestedMapping = variableMappings.stream()
                .filter(mapping -> "nested_field".equals(mapping.variableName()))
                .findFirst()
                .orElseThrow();
        assertThat(nestedMapping.traceSection()).isEqualTo(OnlineScoringDataExtractor.TraceSection.INPUT);
        assertThat(nestedMapping.jsonPath()).isEqualTo("$.questions.question1");
    }

    @Test
    @DisplayName("toReplacements should produce valid JSON for complex objects (Python evaluator path)")
    void testToReplacementsProducesValidJsonForComplexObjects() throws JsonProcessingException {
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
    void testRenderTemplateWithRootObjects() throws JsonProcessingException {
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
    void testRenderTemplateWithOutputRootObject() throws JsonProcessingException {
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
    void testRenderSpanTemplateWithRootObjects() throws JsonProcessingException {
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
                com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanLlmAsJudge.SpanLlmAsJudgeCode.class);
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
    void testRenderTemplate(String evaluator) throws JsonProcessingException {
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
    void testRenderTemplateWithTraceThread() throws JsonProcessingException {
        var evaluatorCode = JsonUtils.readValue(TEST_TRACE_THREAD_EVALUATOR, TraceThreadLlmAsJudgeCode.class);
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
        var evaluatorCode = JsonUtils.readValue(TEST_TRACE_THREAD_EVALUATOR, TraceThreadLlmAsJudgeCode.class);
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
        var evaluatorCode = JsonUtils.readValue(TEST_EVALUATOR, LlmAsJudgeCode.class);
        var trace = createTrace(generator.generate(), generator.generate());

        var request = OnlineScoringEngine.prepareLlmRequest(evaluatorCode, trace, new ToolCallingStrategy());

        assertThat(request.responseFormat()).isNotNull();
        var expectedSchema = createTestSchema();
        assertThat(request.responseFormat().jsonSchema().rootElement()).isEqualTo(expectedSchema);
    }

    @Test
    @DisplayName("prepare LLM request with instruction strategy")
    void testPrepareLlmRequestWithInstructionStrategy() throws JsonProcessingException {
        var evaluatorCode = JsonUtils.readValue(TEST_EVALUATOR, LlmAsJudgeCode.class);
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
        assertThat(message.asContentList().get(0).type()).isEqualTo("text");
        assertThat(message.asContentList().get(0).text()).isEqualTo("Analyze this video");
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
        var videoPart = message.asContentList().get(0);
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
    void shouldRenderTemplateVariables_whenVideoUrlFromInput() throws JsonProcessingException {
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
        var userMessage = (UserMessage) renderedMessages.get(0);
        assertThat(userMessage.contents()).hasSize(2);

        var textContent = (TextContent) userMessage.contents().get(0);
        assertThat(textContent.text()).isEqualTo("Analyze this video: Sample marketing video");

        var videoContent = (VideoContent) userMessage.contents().get(1);
        assertThat(videoContent.video().url().toString())
                .isEqualTo("http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4");
    }

    @Test
    @DisplayName("should render template variables with base64 video from input")
    void shouldRenderTemplateVariables_whenBase64VideoFromInput() throws JsonProcessingException {
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
        var userMessage = (UserMessage) renderedMessages.get(0);
        assertThat(userMessage.contents()).hasSize(2);

        var videoContent = (VideoContent) userMessage.contents().get(1);
        assertThat(videoContent.video().url().toString()).startsWith("data:video/mp4;base64,");
    }

    @Test
    @DisplayName("should render mixed media with template variables")
    void shouldRenderTemplateVariables_whenMixedMedia() throws JsonProcessingException {
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
        var userMessage = (UserMessage) renderedMessages.get(0);
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
    void shouldRenderTemplateVariables_whenMultimodalContentWithVideoUrl() throws JsonProcessingException {
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
        var userMessage = (UserMessage) renderedMessages.get(0);
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
    void shouldRenderTemplateVariables_whenVideoUrlFromOutput() throws JsonProcessingException {
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
        var userMessage = (UserMessage) renderedMessages.get(0);
        assertThat(userMessage.contents()).hasSize(2);

        var videoContent = (VideoContent) userMessage.contents().get(1);
        assertThat(videoContent.video().url().toString()).isEqualTo("http://example.com/generated-video.mp4");
    }

    @Test
    @DisplayName("render span message templates")
    void testRenderSpanTemplate() throws JsonProcessingException {
        var evaluatorCode = JsonUtils.readValue(TEST_EVALUATOR,
                com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanLlmAsJudge.SpanLlmAsJudgeCode.class);
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
    void testPrepareSpanLlmRequestWithToolCallingStrategy() throws JsonProcessingException {
        var evaluatorCode = JsonUtils.readValue(TEST_EVALUATOR,
                com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanLlmAsJudge.SpanLlmAsJudgeCode.class);
        var span = createSpan(generator.generate(), generator.generate());

        var request = OnlineScoringEngine.prepareSpanLlmRequest(evaluatorCode, span, new ToolCallingStrategy());

        assertThat(request.responseFormat()).isNotNull();
        var expectedSchema = createTestSchema();
        assertThat(request.responseFormat().jsonSchema().rootElement()).isEqualTo(expectedSchema);
    }

    @Test
    @DisplayName("prepare span LLM request with instruction strategy")
    void testPrepareSpanLlmRequestWithInstructionStrategy() throws JsonProcessingException {
        var evaluatorCode = JsonUtils.readValue(TEST_EVALUATOR,
                com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanLlmAsJudge.SpanLlmAsJudgeCode.class);
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

    private com.comet.opik.api.Span createSpan(UUID spanId, UUID projectId) throws JsonProcessingException {
        return com.comet.opik.api.Span.builder()
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

    // ==================== Tests for direct variable parsing (without mapping) ====================

    static Stream<Arguments> parseVariableAsPathCases() {
        return Stream.of(
                arguments("input.question", OnlineScoringDataExtractor.TraceSection.INPUT, "$.question"),
                arguments("output.answer", OnlineScoringDataExtractor.TraceSection.OUTPUT, "$.answer"),
                arguments("metadata.model", OnlineScoringDataExtractor.TraceSection.METADATA, "$.model"),
                arguments("input", OnlineScoringDataExtractor.TraceSection.INPUT, "$"),
                arguments("input.questions.question1", OnlineScoringDataExtractor.TraceSection.INPUT,
                        "$.questions.question1"));
    }

    @ParameterizedTest(name = "parseVariableAsPath(\"{0}\") -> section={1}, jsonPath={2}")
    @MethodSource("parseVariableAsPathCases")
    void testParseVariableAsPath(String variableName, OnlineScoringDataExtractor.TraceSection expectedSection,
            String expectedJsonPath) {
        var mapping = OnlineScoringDataExtractor.parseVariableAsPath(variableName);

        assertThat(mapping).isNotNull();
        assertThat(mapping.variableName()).isEqualTo(variableName);
        assertThat(mapping.traceSection()).isEqualTo(expectedSection);
        assertThat(mapping.jsonPath()).isEqualTo(expectedJsonPath);
    }

    @ParameterizedTest(name = "parseVariableAsPath(\"{0}\") -> null")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "unknown.field"})
    void testParseVariableAsPath_returnsNull(String input) {
        assertThat(OnlineScoringDataExtractor.parseVariableAsPath(input)).isNull();
    }

    @Test
    @DisplayName("toReplacementsFromTemplateVariables should extract values from trace")
    void testToReplacementsFromTemplateVariables() throws JsonProcessingException {
        // Given
        var traceId = generator.generate();
        var projectId = generator.generate();
        var trace = createTrace(traceId, projectId);

        var templateVariables = Set.of(
                "input.questions.question1",
                "output.output",
                "input");

        // When
        var replacements = OnlineScoringDataExtractor.toReplacementsFromTemplateVariables(templateVariables, trace);

        // Then
        assertThat(replacements).hasSize(3);
        assertThat(replacements.get("input.questions.question1")).isEqualTo(SUMMARY_STR);
        assertThat(replacements.get("output.output")).isEqualTo(OUTPUT_STR);
        // input should contain the full input JSON
        assertThat(replacements.get("input")).contains("questions");
    }

    @ParameterizedTest(name = "renderMessages with {0} variablesMap should render templates using dot-notation variables")
    @NullAndEmptySource
    void testRenderMessagesWithEmptyOrNullVariablesMap(Map<String, String> variablesMap)
            throws JsonProcessingException {
        // Given
        List<LlmAsJudgeMessage> messages = List.of(
                LlmAsJudgeMessage.builder()
                        .role(ChatMessageType.USER)
                        .content("Question: {{input.questions.question1}}\nAnswer: {{output.output}}")
                        .build());

        var traceId = generator.generate();
        var projectId = generator.generate();
        var trace = createTrace(traceId, projectId);

        // When
        var renderedMessages = OnlineScoringEngine.renderMessages(messages, variablesMap, trace);

        // Then
        assertThat(renderedMessages).hasSize(1);
        var userMessage = (UserMessage) renderedMessages.get(0);
        var messageText = userMessage.singleText();

        assertThat(messageText).contains("Question: " + SUMMARY_STR);
        assertThat(messageText).contains("Answer: " + OUTPUT_STR);
    }

    @Test
    @DisplayName("renderMessages with empty variablesMap should handle root section variables")
    void testRenderMessagesWithEmptyVariablesMap_rootSection() throws JsonProcessingException {
        // Given - template with root section variable
        List<LlmAsJudgeMessage> messages = List.of(
                LlmAsJudgeMessage.builder()
                        .role(ChatMessageType.USER)
                        .content("Full input: {{input}}")
                        .build());

        var traceId = generator.generate();
        var projectId = generator.generate();
        var trace = createTrace(traceId, projectId);

        // When - pass empty map to trigger direct JSONPath mode
        var renderedMessages = OnlineScoringEngine.renderMessages(messages, Map.of(), trace);

        // Then
        assertThat(renderedMessages).hasSize(1);
        var userMessage = (UserMessage) renderedMessages.get(0);
        var messageText = userMessage.singleText();

        // Should contain the entire input JSON
        assertThat(messageText).contains("Full input: {");
        assertThat(messageText).containsAnyOf("\"questions\"", "&quot;questions&quot;");
    }

    static Stream<Arguments> toFullSectionObjectDataCases() {
        return Stream.of(
                Arguments.of(
                        "dict input/output with metadata",
                        Map.of("question", "What is AI?", "context", "tech"),
                        Map.of("answer", "Artificial Intelligence", "confidence", 0.95),
                        Map.of("model", "gpt-4", "tokens", 150),
                        Map.of(
                                "input", Map.of("question", "What is AI?", "context", "tech"),
                                "output", Map.of("answer", "Artificial Intelligence", "confidence", 0.95),
                                "metadata", Map.of("model", "gpt-4", "tokens", 150))),
                Arguments.of(
                        "string input/output without metadata",
                        "What is AI?",
                        "Artificial Intelligence",
                        null,
                        Map.of(
                                "input", "What is AI?",
                                "output", "Artificial Intelligence")),
                Arguments.of(
                        "array input/output",
                        List.of("item1", "item2", "item3"),
                        List.of(Map.of("score", 0.8), Map.of("score", 0.9)),
                        null,
                        Map.of(
                                "input", List.of("item1", "item2", "item3"),
                                "output", List.of(Map.of("score", 0.8), Map.of("score", 0.9)))));
    }

    @ParameterizedTest(name = "toFullSectionObjectData should handle {0}")
    @MethodSource("toFullSectionObjectDataCases")
    void testToFullSectionObjectData(String scenario, Object inputVal, Object outputVal,
            Object metadataVal, Map<String, Object> expectedData) {
        // Given
        var input = JsonUtils.getMapper().valueToTree(inputVal);
        var output = JsonUtils.getMapper().valueToTree(outputVal);
        var metadata = metadataVal != null ? JsonUtils.getMapper().valueToTree(metadataVal) : null;

        var trace = Trace.builder()
                .id(UUID.randomUUID())
                .projectName(PROJECT_NAME)
                .projectId(UUID.randomUUID())
                .input(input)
                .output(output)
                .metadata(metadata)
                .build();

        // When
        var data = OnlineScoringDataExtractor.toFullSectionObjectData(trace);

        // Then
        assertThat(data).hasSize(expectedData.size());
        expectedData.forEach((key, value) -> assertThat(data.get(key)).isEqualTo(value));
    }

    @Test
    @DisplayName("toFullSectionObjectData should handle nested dict input/output")
    void testToFullSectionObjectData_nestedDictInputOutput() {
        // Given
        var input = JsonUtils.getMapper().valueToTree(
                Map.of("messages", List.of(
                        Map.of("role", "user", "content", "hello"),
                        Map.of("role", "assistant", "content", "hi there"))));
        var output = JsonUtils.getMapper().valueToTree(
                Map.of("result", Map.of("answer", "hello", "metadata", Map.of("score", 0.9))));

        var trace = Trace.builder()
                .id(UUID.randomUUID())
                .projectName(PROJECT_NAME)
                .projectId(UUID.randomUUID())
                .input(input)
                .output(output)
                .build();

        // When
        var data = OnlineScoringDataExtractor.toFullSectionObjectData(trace);

        // Then
        assertThat(data).hasSize(2);

        @SuppressWarnings("unchecked")
        var inputMap = (Map<String, Object>) data.get("input");
        assertThat(inputMap).containsKey("messages");
        @SuppressWarnings("unchecked")
        var messages = (List<Map<String, Object>>) inputMap.get("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).get("role")).isEqualTo("user");

        @SuppressWarnings("unchecked")
        var outputMap = (Map<String, Object>) data.get("output");
        @SuppressWarnings("unchecked")
        var resultMap = (Map<String, Object>) outputMap.get("result");
        assertThat(resultMap.get("answer")).isEqualTo("hello");
    }

    @Test
    @DisplayName("toFullSectionObjectData should skip null sections")
    void testToFullSectionObjectData_nullSections() {
        // Given - only output, no input or metadata
        var output = JsonUtils.getMapper().valueToTree(Map.of("result", "success"));

        var trace = Trace.builder()
                .id(UUID.randomUUID())
                .projectName(PROJECT_NAME)
                .projectId(UUID.randomUUID())
                .output(output)
                .build();

        // When
        var data = OnlineScoringDataExtractor.toFullSectionObjectData(trace);

        // Then
        assertThat(data).hasSize(1);
        assertThat(data).containsKey("output");
        assertThat(data).doesNotContainKey("input");
        assertThat(data).doesNotContainKey("metadata");
    }

    @Test
    @DisplayName("toFullSectionObjectData should handle mixed types - string input, dict output")
    void testToFullSectionObjectData_mixedTypes() {
        // Given
        var input = JsonUtils.getMapper().valueToTree("plain text input");
        var output = JsonUtils.getMapper().valueToTree(Map.of("answer", "hello", "confidence", 0.9));
        var metadata = JsonUtils.getMapper().valueToTree(Map.of("model", "gpt-4"));

        var trace = Trace.builder()
                .id(UUID.randomUUID())
                .projectName(PROJECT_NAME)
                .projectId(UUID.randomUUID())
                .input(input)
                .output(output)
                .metadata(metadata)
                .build();

        // When
        var data = OnlineScoringDataExtractor.toFullSectionObjectData(trace);

        // Then
        assertThat(data).hasSize(3);
        assertThat(data.get("input")).isEqualTo("plain text input");
        assertThat(data.get("output")).isEqualTo(Map.of("answer", "hello", "confidence", 0.9));
        assertThat(data.get("metadata")).isEqualTo(Map.of("model", "gpt-4"));
    }

    @Test
    @DisplayName("toFullSectionObjectData should work with Span")
    void testToFullSectionObjectData_span() {
        // Given
        var input = JsonUtils.getMapper().valueToTree(Map.of("prompt", "tell me a joke"));
        var output = JsonUtils.getMapper().valueToTree(Map.of("response", "Why did the chicken cross the road?"));

        var span = Span.builder()
                .id(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .projectName(PROJECT_NAME)
                .traceId(UUID.randomUUID())
                .name("test-span")
                .input(input)
                .output(output)
                .build();

        // When
        var data = OnlineScoringDataExtractor.toFullSectionObjectData(span);

        // Then
        assertThat(data).hasSize(2);
        assertThat(data.get("input")).isEqualTo(Map.of("prompt", "tell me a joke"));
        assertThat(data.get("output")).isEqualTo(Map.of("response", "Why did the chicken cross the road?"));
        assertThat(data).doesNotContainKey("metadata");
    }

    @Test
    @DisplayName("toFullSectionObjectData should handle numeric and boolean values in objects")
    void testToFullSectionObjectData_numericAndBooleanValues() {
        // Given
        var input = JsonUtils.getMapper().valueToTree(Map.of(
                "temperature", 0.7,
                "max_tokens", 100,
                "stream", true));
        var output = JsonUtils.getMapper().valueToTree(Map.of(
                "score", 0.95,
                "passed", true,
                "count", 42));

        var trace = Trace.builder()
                .id(UUID.randomUUID())
                .projectName(PROJECT_NAME)
                .projectId(UUID.randomUUID())
                .input(input)
                .output(output)
                .build();

        // When
        var data = OnlineScoringDataExtractor.toFullSectionObjectData(trace);

        // Then
        assertThat(data).hasSize(2);

        @SuppressWarnings("unchecked")
        var inputMap = (Map<String, Object>) data.get("input");
        assertThat(inputMap.get("temperature")).isEqualTo(0.7);
        assertThat(inputMap.get("max_tokens")).isEqualTo(100);
        assertThat(inputMap.get("stream")).isEqualTo(true);

        @SuppressWarnings("unchecked")
        var outputMap = (Map<String, Object>) data.get("output");
        assertThat(outputMap.get("score")).isEqualTo(0.95);
        assertThat(outputMap.get("passed")).isEqualTo(true);
        assertThat(outputMap.get("count")).isEqualTo(42);
    }
}
