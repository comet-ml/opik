package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge.TraceThreadLlmAsJudgeCode;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.AutomationRuleEvaluatorResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.llm.LlmModule;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.google.inject.AbstractModule;
import com.redis.testcontainers.RedisContainer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Black-box coverage for the trace-thread LLM-as-judge online scorer with {@code agenticTools} enabled —
 * the route-before-fetch path added in OPIK-7454. Drives the public flow (create rule + create a thread
 * with traces/spans) end-to-end so the async scorer runs with the toggle on, exercising the real
 * {@code getSpansSizeByTraceIds} aggregate + the bounded span preload before scoring. The scorer's
 * user-facing "Evaluating threadId ..." log (emitted after the size aggregate resolves) is asserted via
 * the rule logs, proving the whole path executed. Complements the existing toggle-off scorer coverage and
 * leaves a reference test for the agentic-tools path.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TraceThreadOnlineScoringAgenticToolsE2ETest {

    private static final String API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);

    // Real evaluator code so the scorer renders against a valid schema; response matches the schema so
    // toFeedbackScores parses it and the scorer completes.
    private static final String EVALUATOR_CODE = """
            {
              "model": { "name": "gpt-4o", "temperature": 0.3 },
              "messages": [
                { "role": "USER", "content": "Score the conversation: {{context}}" },
                { "role": "SYSTEM", "content": "You are a helpful AI." }
              ],
              "schema": [
                { "name": "Relevance",   "type": "INTEGER", "description": "Relevance of the answer" },
                { "name": "Conciseness", "type": "DOUBLE",  "description": "How concise the answer is" }
              ]
            }
            """;

    private static final String LLM_RESPONSE = """
            {
              "Relevance":   { "score": 4,   "reason": "on-topic" },
              "Conciseness": { "score": 3.5, "reason": "could be tighter" }
            }
            """;

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickhouse = ClickHouseContainerUtils.newClickHouseContainer(zookeeper);
    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer();

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redis, clickhouse, mysql, zookeeper).join();

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
                                bind(LlmProviderFactory.class).toInstance(
                                        Mockito.mock(LlmProviderFactory.class, Mockito.RETURNS_DEEP_STUBS));
                            }
                        }))
                        // The subject under test: run the trace-thread scorers with agentic tools on.
                        .customConfigs(List.of(new CustomConfig("serviceToggles.agenticToolsEnabled", "true")))
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private ProjectResourceClient projectResourceClient;
    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;
    private AutomationRuleEvaluatorResourceClient evaluatorsResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        var baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);
        mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        projectResourceClient = new ProjectResourceClient(client, baseURI, factory);
        traceResourceClient = new TraceResourceClient(client, baseURI);
        spanResourceClient = new SpanResourceClient(client, baseURI);
        evaluatorsResourceClient = new AutomationRuleEvaluatorResourceClient(client, baseURI);
    }

    @Test
    void runsSizeAggregateAndScoresThreadWhenAgenticToolsEnabled(LlmProviderFactory llmProviderFactory)
            throws Exception {
        when(llmProviderFactory.getLanguageModel(anyString(), any()).chat(any(ChatRequest.class)))
                .thenAnswer(invocation -> ChatResponse.builder().aiMessage(AiMessage.from(LLM_RESPONSE)).build());

        var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(32);
        var projectId = projectResourceClient.createProject(projectName, API_KEY, WORKSPACE_NAME);

        var evaluator = factory.manufacturePojo(AutomationRuleEvaluatorTraceThreadLlmAsJudge.class).toBuilder()
                .code(JsonUtils.readValue(EVALUATOR_CODE, TraceThreadLlmAsJudgeCode.class))
                .samplingRate(1f)
                .enabled(true)
                .filters(List.of())
                .projectIds(Set.of(projectId))
                .build();
        var ruleId = evaluatorsResourceClient.createEvaluator(evaluator, WORKSPACE_NAME, API_KEY);

        var threadId = "thread-" + RandomStringUtils.secure().nextAlphanumeric(32);
        // Open the thread before logging into it — online scoring samples the thread as it is created,
        // with the rule already in place.
        traceResourceClient.openTraceThread(threadId, null, projectName, API_KEY, WORKSPACE_NAME);

        var trace = factory.manufacturePojo(Trace.class).toBuilder()
                .projectId(projectId)
                .projectName(projectName)
                .source(null)
                .threadId(threadId)
                .input(JsonUtils.getJsonNodeFromString("{\"question\":\"Summarize the study\"}"))
                .output(JsonUtils.getJsonNodeFromString("{\"answer\":\"It varied data mixtures.\"}"))
                .feedbackScores(null)
                .usage(null)
                .build();
        var traceId = traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);

        // Two spans in the thread so the size aggregate sums across multiple span rows
        // (argMax(...) GROUP BY id -> sum) rather than trivially reading one, before routing.
        var span1 = factory.manufacturePojo(Span.class).toBuilder()
                .id(null)
                .projectName(projectName)
                .traceId(traceId)
                .parentSpanId(null)
                .input(JsonUtils.getJsonNodeFromString("{\"tool\":\"search\",\"args\":\"study\"}"))
                .output(JsonUtils.getJsonNodeFromString("{\"result\":\"ok\"}"))
                .feedbackScores(null)
                .build();
        var span2 = factory.manufacturePojo(Span.class).toBuilder()
                .id(null)
                .projectName(projectName)
                .traceId(traceId)
                .parentSpanId(null)
                .input(JsonUtils.getJsonNodeFromString("{\"tool\":\"summarize\",\"args\":\"findings\"}"))
                .output(JsonUtils.getJsonNodeFromString("{\"result\":\"done\"}"))
                .feedbackScores(null)
                .build();
        spanResourceClient.batchCreateSpans(List.of(span1, span2), API_KEY, WORKSPACE_NAME);

        // Ordering is load-bearing: the sampler records the per-rule sampling decision on the thread
        // asynchronously (fired on thread creation), and the close below only enqueues scoring for rules
        // already marked sampled. Wait for that decision (surfaced in the rule logs) before closing. The
        // "will be sampled" log is emitted just before the decision is persisted, but the poll interval plus
        // the close round-trip comfortably cover that sub-second gap, so the enqueue is not raced.
        await().atMost(60, TimeUnit.SECONDS).pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
            var logPage = evaluatorsResourceClient.getLogs(ruleId, WORKSPACE_NAME, API_KEY);
            assertThat(logPage.content()).anyMatch(log -> log.message().contains("will be sampled"));
        });

        // Close the thread → it flips to INACTIVE, is persisted to trace_threads (threadModelId assigned),
        // and the sampled rule is enqueued for trace-thread online scoring — which runs the
        // route-before-fetch path (getSpansSizeByTraceIds → bounded preload) added in OPIK-7454.
        traceResourceClient.closeTraceThread(threadId, null, projectName, API_KEY, WORKSPACE_NAME);

        await().atMost(60, TimeUnit.SECONDS).pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
            var thread = traceResourceClient.getTraceThread(threadId, projectId, API_KEY, WORKSPACE_NAME);
            assertThat(thread.threadModelId()).isNotNull();

            // "Evaluating threadId ..." is logged by the scorer only after getSpansSizeByTraceIds resolves,
            // so its presence proves the agentic-tools size aggregate ran end-to-end for this thread.
            var logPage = evaluatorsResourceClient.getLogs(ruleId, WORKSPACE_NAME, API_KEY);
            assertThat(logPage.content())
                    .anyMatch(log -> log.message().contains("Evaluating threadId '%s'".formatted(threadId)));

            // The mocked judge returns the evaluator's schema, so scoring completes and persists exactly the
            // two thread feedback scores with the response's values. Asserting name AND value proves the
            // whole path — size aggregate → bounded preload → evaluate → parse → persist — not merely that
            // the scorer started (OPIK-7454 test plan).
            var threads = traceResourceClient.getTraceThreads(projectId, projectName, API_KEY, WORKSPACE_NAME,
                    null, null, Map.of());
            assertThat(threads.content()).isNotEmpty();
            var scoresByName = threads.content().getFirst().feedbackScores().stream()
                    .collect(toMap(FeedbackScore::name, FeedbackScore::value));
            assertThat(scoresByName).containsOnlyKeys("Relevance", "Conciseness");
            // compareTo ignores the persisted decimal scale (4 == 4.000000000).
            assertThat(scoresByName.get("Relevance")).isEqualByComparingTo("4");
            assertThat(scoresByName.get("Conciseness")).isEqualByComparingTo("3.5");
        });
    }
}
