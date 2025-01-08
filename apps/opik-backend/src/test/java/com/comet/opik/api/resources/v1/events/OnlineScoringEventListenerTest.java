package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.AutomationRuleEvaluatorResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.AutomationRuleEvaluatorService;
import com.comet.opik.domain.ChatCompletionService;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.eventbus.EventBus;
import com.redis.testcontainers.RedisContainer;
import dev.ai4j.openai4j.chat.Role;
import dev.ai4j.openai4j.chat.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Online Scoring Event Listener")
public class OnlineScoringEventListenerTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();

    private static final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();

    private static final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer();

    @RegisterExtension
    private static final TestDropwizardAppExtension app;

    private static final WireMockUtils.WireMockRuntime wireMock;

    static {
        Startables.deepStart(MYSQL, CLICKHOUSE, REDIS).join();

        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE, DATABASE_NAME);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;
    private TraceResourceClient traceResourceClient;
    private AutomationRuleEvaluatorResourceClient evaluatorResourceClient;
    private ProjectResourceClient projectResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client, Jdbi jdbi) throws Exception {

        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICKHOUSE.createConnection("")) {
            MigrationUtils.runDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        this.baseURI = "http://localhost:%d".formatted(client.getPort());
        this.client = client;

        ClientSupportUtils.config(client);

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);

        this.traceResourceClient = new TraceResourceClient(this.client, baseURI);
        this.evaluatorResourceClient = new AutomationRuleEvaluatorResourceClient(this.client, baseURI);
        this.projectResourceClient = new ProjectResourceClient(this.client, baseURI, factory);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private static void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class BasicWorking {
        @Mock
        AutomationRuleEvaluatorService ruleEvaluatorService;
        @Mock
        ChatCompletionService aiProxyService;
        @Mock
        FeedbackScoreService feedbackScoreService;
        @Mock
        EventBus eventBus;
        OnlineScoringEventListener onlineScoringEventListener;

        AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode evaluatorCode;
        Trace trace;

        String messageToTest = "Summary: {{summary}}\\nInstruction: {{instruction}}\\n\\n";
        String testEvaluator = """
                {
                  "model": {
                      "name": "gpt-4o",
                      "temperature": 0.3
                  },
                  "messages": [
                    {
                      "role": "USER",
                      "content": "%s"
                    },
                    {
                      "role": "SYSTEM",
                      "content": "You're a helpful AI, be cordial."
                    }
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

        @BeforeEach
        void setUp() throws JsonProcessingException {
            MockitoAnnotations.initMocks(this);
            Mockito.doNothing().when(eventBus).register(Mockito.any());
            onlineScoringEventListener = new OnlineScoringEventListener(eventBus, ruleEvaluatorService,
                    aiProxyService, feedbackScoreService);

            var mapper = new ObjectMapper();
            evaluatorCode = mapper.readValue(testEvaluator, AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode.class);

            trace = Trace.builder().input(mapper.readTree(input)).output(mapper.readTree(output)).build();
        }

        @Test
        @DisplayName("parse variable mapping into a usable one")
        void when__parseRuleVariables() {
            log.info(evaluatorCode.toString());
            var variableMappings = onlineScoringEventListener.variableMapping(evaluatorCode.variables());

            assertThat(variableMappings).hasSize(4);

            var varSummary = variableMappings.get(0);
            assertThat(varSummary.traceSection()).isEqualTo(OnlineScoringEventListener.TraceSection.INPUT);
            assertThat(varSummary.jsonPath()).isEqualTo("questions.question1");

            var varInstruction = variableMappings.get(1);
            assertThat(varInstruction.traceSection()).isEqualTo(OnlineScoringEventListener.TraceSection.OUTPUT);
            assertThat(varInstruction.jsonPath()).isEqualTo("output");

            var varNonUsed = variableMappings.get(2);
            assertThat(varNonUsed.traceSection()).isEqualTo(OnlineScoringEventListener.TraceSection.INPUT);
            assertThat(varNonUsed.jsonPath()).isEqualTo("questions.question2");

            var varToFail = variableMappings.get(3);
            assertThat(varToFail.traceSection()).isEqualTo(OnlineScoringEventListener.TraceSection.METADATA);
            assertThat(varToFail.jsonPath()).isEqualTo("nonexistent.path");
        }

        @Test
        @DisplayName("render message templates with a trace")
        void when__parseRuleVariable2s() {
            log.info(evaluatorCode.toString());
            var renderedMessages = onlineScoringEventListener.renderMessages(trace, evaluatorCode);

            assertThat(renderedMessages).hasSize(2);

            var userMessage = (UserMessage) renderedMessages.get(0);
            assertThat(userMessage.role()).isEqualTo(Role.USER);
            assertThat(userMessage.content().toString()).contains(summaryStr);
            assertThat(userMessage.content().toString()).contains(outputStr);

            var systemMessage = renderedMessages.get(1);
            assertThat(systemMessage.role()).isEqualTo(Role.SYSTEM);
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class TracesCreatedEvent {

        @Test
        @DisplayName("when a new trace is created, OnlineScoring should see it within a event")
        void when__newTracesIsCreated__onlineScoringShouldKnow() {
            var projectName = factory.manufacturePojo(String.class);
            var projectId = projectResourceClient.createProject(projectName, API_KEY, TEST_WORKSPACE);

            var evaluator = factory.manufacturePojo(AutomationRuleEvaluatorLlmAsJudge.class)
                    .toBuilder().projectId(projectId).build();

            evaluatorResourceClient.createEvaluator(evaluator, TEST_WORKSPACE, API_KEY);

            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .build();

            UUID traceId = traceResourceClient.createTrace(trace, API_KEY, TEST_WORKSPACE);

            Trace returnTrace = traceResourceClient.getById(traceId, TEST_WORKSPACE, API_KEY);

            // TODO: run the actual test checking for if we have a FeedbackScore by the end. Prob mocking AI Proxy.
        }
    }

}
