package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.LlmAsJudgeOutputSchemaType;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Trace;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.AutomationRuleEvaluatorResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.domain.AutomationRuleEvaluatorService;
import com.comet.opik.domain.ChatCompletionService;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.eventbus.EventBus;
import com.redis.testcontainers.RedisContainer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.Redisson;
import org.redisson.config.Config;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("LlmAsJudge Message Render")
@ExtendWith(MockitoExtension.class)
class OnlineScoringEngineTest {

    @Mock
    AutomationRuleEvaluatorService ruleEvaluatorService;
    @Mock
    ChatCompletionService aiProxyService;
    @Mock
    FeedbackScoreService feedbackScoreService;
    @Mock
    EventBus eventBus;

    OnlineScoringSampler onlineScoringSampler;
    OnlineScoringLlmAsJudgeScorer onlineScorer;

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String PROJECT_NAME = "project-" + UUID.randomUUID();
    private static final String WORKSPACE_NAME = "workspace-" + UUID.randomUUID();
    private static final String WORKSPACE_ID = "wid-" + UUID.randomUUID();
    private static final String USER_NAME = "user-" + UUID.randomUUID();

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    LlmAsJudgeCode evaluatorCode;
    Trace trace;

    String messageToTest = "Summary: {{summary}}\\nInstruction: {{instruction}}\\n\\n";
    String testEvaluator = """
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
    String summaryStr = "What was the approach to experimenting with different data mixtures?";
    String outputStr = "The study employed a systematic approach to experiment with varying data mixtures by manipulating the proportions and sources of datasets used for model training.";
    String input = """
            {
                "questions": {
                    "question1": "%s",
                    "question2": "Whatever, we wont use it anyway"
                 },
                "pdf_url": "https://arxiv.org/pdf/2406.04744",
                "title": "CRAG -- Comprehensive RAG Benchmark"
            }
            """.formatted(summaryStr).trim();
    String output = """
            {
                "output": "%s"
            }
            """.formatted(outputStr).trim();

    String edgeCaseTemplate = "Summary: {{summary}}\\nInstruction: {{ instruction     }}\\n\\n";
    String testEvaluatorEdgeCase = """
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
            .formatted(edgeCaseTemplate).trim();

    private final ObjectMapper mapper = new ObjectMapper();

    private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private static final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();

    @RegisterExtension
    private static final TestDropwizardAppExtension app;

    private static final WireMockUtils.WireMockRuntime wireMock;

    static {
        Startables.deepStart(REDIS, MYSQL).join();

        wireMock = WireMockUtils.startWireMock();

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL.getJdbcUrl(), null, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private AutomationRuleEvaluatorResourceClient evaluatorsResourceClient;
    private ProjectResourceClient projectResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client, Jdbi jdbi) throws Exception {

        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        var baseURI = "http://localhost:%d".formatted(client.getPort());

        ClientSupportUtils.config(client);

        this.projectResourceClient = new ProjectResourceClient(client, baseURI, factory);
        this.evaluatorsResourceClient = new AutomationRuleEvaluatorResourceClient(client, baseURI);
    }

    @Test
    @DisplayName("test Redis producer and consumer base flow")
    void testRedisProducerAndConsumerBaseFlow() throws Exception {
        var onlineScoringConfig = new OnlineScoringConfig();
        onlineScoringConfig.setLlmAsJudgeStream("test-stream");
        onlineScoringConfig.setConsumerGroupName("test-group");
        onlineScoringConfig.setPoolingIntervalMs(100);
        onlineScoringConfig.setConsumerBatchSize(1);

        Config redisConfig = new Config();
        redisConfig.useSingleServer().setAddress(REDIS.getRedisURI()).setDatabase(0);
        var redisson = Redisson.create(redisConfig).reactive();

        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER_NAME);

        log.debug("Setting up project '{}'", PROJECT_NAME);
        UUID projectId = projectResourceClient.createProject(PROJECT_NAME, API_KEY, WORKSPACE_NAME);

        var mapper = JsonUtils.MAPPER;
        evaluatorCode = mapper.readValue(testEvaluator, LlmAsJudgeCode.class);

        var evaluator = AutomationRuleEvaluatorLlmAsJudge.builder()
                .projectId(projectId)
                .name("evaluator-test-" + UUID.randomUUID())
                .createdBy(USER_NAME)
                .code(evaluatorCode)
                .samplingRate(1.0f).build();

        log.info("Creating evaluator {}", evaluator);
        evaluatorsResourceClient.createEvaluator(evaluator, projectId, WORKSPACE_NAME, API_KEY);

        var traceId = UUID.randomUUID();
        trace = Trace.builder()
                .id(traceId)
                .projectName(PROJECT_NAME)
                .projectId(projectId)
                .createdBy(USER_NAME)
                .input(mapper.readTree(input))
                .output(mapper.readTree(output)).build();
        var event = new TracesCreated(List.of(trace), WORKSPACE_ID, USER_NAME);

        // return the evaluator we just created
        Mockito.doReturn(List.of(evaluator)).when(ruleEvaluatorService).findAll(Mockito.any(), Mockito.any(),
                Mockito.any());
        Mockito.doNothing().when(eventBus).register(Mockito.any());

        onlineScoringSampler = new OnlineScoringSampler(onlineScoringConfig, redisson, eventBus, ruleEvaluatorService);
        onlineScoringSampler.onTracesCreated(event);

        Thread.sleep(onlineScoringConfig.getPoolingIntervalMs());

        var aiMessage = "{\"Relevance\":{\"score\":4,\"reason\":\"The summary addresses the instruction by covering the main points and themes. However, it could have included a few more specific details to fully align with the instruction.\"},"
                +
                "\"Technical Accuracy\":{\"score\":4.5,\"reason\":\"The summary accurately conveys the technical details, but there is a slight room for improvement in the precision of certain terms or concepts.\"},"
                +
                "\"Conciseness\":{\"score\":true,\"reason\":\"The summary is concise and effectively captures the essence of the content without unnecessary elaboration.\"}}";

        // mocked response from AI, reused from dev tests
        var aiResponse = ChatResponse.builder().aiMessage(AiMessage.aiMessage(aiMessage)).build();

        ArgumentCaptor<List<FeedbackScoreBatchItem>> captor = ArgumentCaptor.forClass(List.class);
        Mockito.doReturn(Mono.empty()).when(feedbackScoreService).scoreBatchOfTraces(captor.capture());
        Mockito.doReturn(aiResponse).when(aiProxyService).scoreTrace(Mockito.any(), Mockito.any(), Mockito.any());

        onlineScorer = new OnlineScoringLlmAsJudgeScorer(onlineScoringConfig, redisson, aiProxyService,
                feedbackScoreService);

        Thread.sleep(onlineScoringConfig.getPoolingIntervalMs() * 2L);

        // check which feedback scores would be stored in Clickhouse by our process
        List<FeedbackScoreBatchItem> processed = captor.getValue();
        log.info(processed.toString());

        assertThat(processed).hasSize(event.traces().size() * 3);

        // test if all 3 feedbacks are generated with the expected value
        var resultMap = processed.stream().collect(Collectors.toMap(FeedbackScoreBatchItem::name, Function.identity()));
        assertThat(resultMap.get("Relevance").value()).isEqualTo(new BigDecimal(4));
        assertThat(resultMap.get("Technical Accuracy").value()).isEqualTo(new BigDecimal("4.5"));
        assertThat(resultMap.get("Conciseness").value()).isEqualTo(BigDecimal.ONE);
    }

    @Test
    @DisplayName("parse variable mapping into a usable one")
    void testVariableMapping() {
        var variableMappings = OnlineScoringEngine.toVariableMapping(evaluatorCode.variables());

        assertThat(variableMappings).hasSize(4);

        var varSummary = variableMappings.get(0);
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
    }

    @Test
    @DisplayName("render message templates with a trace")
    void testRenderTemplate() {
        var renderedMessages = OnlineScoringEngine.renderMessages(evaluatorCode.messages(), evaluatorCode.variables(),
                trace);

        assertThat(renderedMessages).hasSize(2);

        var userMessage = renderedMessages.get(0);
        assertThat(userMessage.getClass()).isEqualTo(UserMessage.class);
        assertThat(((UserMessage) userMessage).singleText()).contains(summaryStr);
        assertThat(((UserMessage) userMessage).singleText()).contains(outputStr);

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
            var relevanceScore = feedbackScores.get(0);
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

        var evaluatorEdgeCase = mapper.readValue(testEvaluatorEdgeCase,
                AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode.class);

        var renderedMessages = OnlineScoringEngine.renderMessages(evaluatorEdgeCase.messages(),
                evaluatorEdgeCase.variables(), trace);

        assertThat(renderedMessages).hasSize(2);

        var userMessage = renderedMessages.get(0);
        assertThat(userMessage.getClass()).isEqualTo(UserMessage.class);
        assertThat(((UserMessage) userMessage).singleText()).contains(summaryStr);
        assertThat(((UserMessage) userMessage).singleText()).contains(outputStr);

        var systemMessage = renderedMessages.get(1);
        assertThat(systemMessage.getClass()).isEqualTo(SystemMessage.class);
    }
}
