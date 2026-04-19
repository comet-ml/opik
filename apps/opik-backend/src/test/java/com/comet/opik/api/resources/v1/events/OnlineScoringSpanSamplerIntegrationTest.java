package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Source;
import com.comet.opik.api.Span;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanLlmAsJudge.SpanLlmAsJudgeCode;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.api.events.SpansCreated;
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
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.google.common.eventbus.EventBus;
import com.google.inject.AbstractModule;
import com.redis.testcontainers.RedisContainer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(MockitoExtension.class)
@ExtendWith(DropwizardAppExtensionProvider.class)
class OnlineScoringSpanSamplerIntegrationTest {

    private static final String API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER_NAME = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);

    private static final String TEST_EVALUATOR = """
            {
              "model": { "name": "gpt-4o", "temperature": 0.3 },
              "messages": [
                { "role": "USER", "content": "Score this: {{name}}" }
              ],
              "variables": { "name": "input.name" },
              "schema": [
                { "name": "Relevance",          "type": "INTEGER", "description": "Relevance" },
                { "name": "Conciseness",        "type": "DOUBLE",  "description": "Conciseness" },
                { "name": "Technical Accuracy", "type": "BOOLEAN", "description": "Technical accuracy" }
              ]
            }
            """;

    private static final String MOCK_AI_RESPONSE = """
            {
              "Relevance":{"score":4,"reason":"some reason"},
              "Technical Accuracy":{"score":4.5,"reason":"some reason"},
              "Conciseness":{"score":true,"reason":"some reason"}
             }
            """;

    private final ChatCompletionService aiProxyService;
    private final FeedbackScoreService feedbackScoreService;
    private final EventBus eventBus;

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER);

    @RegisterApp
    private final TestDropwizardAppExtension app;

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

        app = newTestDropwizardAppExtension(
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
                                new CustomConfig("onlineScoring.consumerGroupName", "test-span-group"),
                                new CustomConfig("onlineScoring.consumerBatchSize", "1"),
                                new CustomConfig("onlineScoring.poolingInterval", "100ms"),
                                new CustomConfig("onlineScoring.streams[4].streamName", "test-span-stream"),
                                new CustomConfig("serviceToggles.spanLlmAsJudgeEnabled", "true")))
                        .build());
    }

    private AutomationRuleEvaluatorResourceClient evaluatorResourceClient;
    private ProjectResourceClient projectResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport clientSupport) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);

        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER_NAME);

        projectResourceClient = new ProjectResourceClient(clientSupport, baseUrl, factory);
        evaluatorResourceClient = new AutomationRuleEvaluatorResourceClient(clientSupport, baseUrl);

        Mockito.reset(aiProxyService, feedbackScoreService, eventBus);
    }

    @ParameterizedTest
    @EnumSource(value = Source.class, names = {"SDK"})
    @NullSource
    void redisProducerAndConsumerBaseFlowForSpans(Source source, OnlineScoringSpanSampler spanSampler) {
        Mockito.reset(feedbackScoreService, aiProxyService, eventBus);

        var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(32);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

        var evaluatorCode = JsonUtils.readValue(TEST_EVALUATOR, SpanLlmAsJudgeCode.class);
        var evaluator = createSpanRule(projectId, evaluatorCode);
        evaluatorResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);

        var span = createSpan(projectId, source);
        var event = new SpansCreated(List.of(span), WORKSPACE_ID, USER_NAME);

        var aiResponse = ChatResponse.builder().aiMessage(AiMessage.aiMessage(MOCK_AI_RESPONSE)).build();

        ArgumentCaptor<List<FeedbackScoreBatchItem>> captor = ArgumentCaptor.forClass(List.class);
        Mockito.when(feedbackScoreService.scoreBatchOfSpans(Mockito.anyList())).thenReturn(Mono.empty());
        Mockito.when(aiProxyService.scoreTrace(
                Mockito.any(ChatRequest.class),
                Mockito.any(LlmAsJudgeModelParameters.class),
                Mockito.eq(WORKSPACE_ID)))
                .thenReturn(aiResponse);

        spanSampler.onSpansCreated(event);

        TestUtils.waitForMillis(300);

        Mockito.verify(feedbackScoreService).scoreBatchOfSpans(captor.capture());

        assertThat(captor.getValue())
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrder(
                        expectedScore(span, "Relevance", new BigDecimal(4)),
                        expectedScore(span, "Technical Accuracy", new BigDecimal("4.5")),
                        expectedScore(span, "Conciseness", BigDecimal.ONE));
    }

    private FeedbackScoreBatchItem expectedScore(Span span, String name, BigDecimal value) {
        return FeedbackScoreBatchItem.builder()
                .id(span.id())
                .projectId(span.projectId())
                .projectName(span.projectName())
                .name(name)
                .value(value)
                .reason("some reason")
                .source(ScoreSource.ONLINE_SCORING)
                .build();
    }

    private AutomationRuleEvaluatorSpanLlmAsJudge createSpanRule(UUID projectId, SpanLlmAsJudgeCode evaluatorCode) {
        return AutomationRuleEvaluatorSpanLlmAsJudge.builder()
                .projectIds(Set.of(projectId))
                .name("span-evaluator-" + RandomStringUtils.secure().nextAlphanumeric(32))
                .createdBy(USER_NAME)
                .code(evaluatorCode)
                .samplingRate(1.0f)
                .enabled(true)
                .filters(List.of())
                .build();
    }

    private Span createSpan(UUID projectId, Source source) {
        return factory.manufacturePojo(Span.class).toBuilder()
                .projectId(projectId)
                .source(source)
                .feedbackScores(null)
                .build();
    }
}
