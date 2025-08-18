package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.ExperimentItemStreamRequest;
import com.comet.opik.api.ExperimentStreamRequest;
import com.comet.opik.api.ExperimentType;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.Optimization;
import com.comet.opik.api.OptimizationStatus;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.Span;
import com.comet.opik.api.TimeInterval;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.ValueEntry;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.SpanField;
import com.comet.opik.api.filter.SpanFilter;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.filter.TraceThreadField;
import com.comet.opik.api.filter.TraceThreadFilter;
import com.comet.opik.api.metrics.MetricType;
import com.comet.opik.api.metrics.ProjectMetricRequest;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.StatsUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.OptimizationResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectMetricsResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.resources.utils.spans.SpanAssertions;
import com.comet.opik.api.resources.utils.traces.TraceAssertions;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItemThread;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.ProjectService.DEFAULT_PROJECT;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

@DisplayName("Multi-value Feedback Scores Test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
public class MultiValueFeedbackScoresE2ETest {
    private static final String API_KEY1 = randomUUID().toString();
    private static final String USER1 = randomUUID().toString();
    private static final String API_KEY2 = randomUUID().toString();
    private static final String USER2 = randomUUID().toString();
    private static final String WORKSPACE_ID = randomUUID().toString();
    private static final String TEST_WORKSPACE = randomUUID().toString();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer<?> MYSQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MYSQL_CONTAINER, CLICK_HOUSE_CONTAINER, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICK_HOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL_CONTAINER);
        MigrationUtils.runClickhouseDbMigration(CLICK_HOUSE_CONTAINER);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(MYSQL_CONTAINER.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(REDIS.getRedisURI())
                        .customConfigs(List.of(
                                new TestDropwizardAppExtensionUtils.CustomConfig("feedbackScores.writeToAuthored",
                                        "true")))
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;
    private ProjectResourceClient projectResourceClient;
    private ExperimentResourceClient experimentResourceClient;
    private DatasetResourceClient datasetResourceClient;
    private OptimizationResourceClient optimizationResourceClient;
    private ProjectMetricsResourceClient projectMetricsResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {

        var baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY1, TEST_WORKSPACE, WORKSPACE_ID, USER1);
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY2, TEST_WORKSPACE, WORKSPACE_ID, USER2);

        this.traceResourceClient = new TraceResourceClient(client, baseURI);
        this.spanResourceClient = new SpanResourceClient(client, baseURI);
        this.projectResourceClient = new ProjectResourceClient(client, baseURI, factory);
        this.experimentResourceClient = new ExperimentResourceClient(client, baseURI, factory);
        this.datasetResourceClient = new DatasetResourceClient(client, baseURI);
        this.optimizationResourceClient = new OptimizationResourceClient(client, baseURI, factory);
        this.projectMetricsResourceClient = new ProjectMetricsResourceClient(client, baseURI);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Test
    @DisplayName("test score trace by multiple authors")
    void testScoreTraceByMultipleAuthors() {
        var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class).stream()
                .map(trace -> trace.toBuilder()
                        .id(null)
                        .projectName(DEFAULT_PROJECT)
                        .usage(null)
                        .feedbackScores(null)
                        .startTime(Instant.now().truncatedTo(ChronoUnit.HOURS))
                        .build())
                .toList();
        var trace1Id = traceResourceClient.createTrace(traces.getFirst(), API_KEY1, TEST_WORKSPACE);
        var trace2Id = traceResourceClient.createTrace(traces.get(1), API_KEY1, TEST_WORKSPACE);
        traceResourceClient.batchCreateTraces(traces.subList(2, traces.size()), API_KEY1, TEST_WORKSPACE);

        // score first trace
        var user1Score = factory.manufacturePojo(FeedbackScore.class);
        traceResourceClient.feedbackScore(trace1Id, user1Score, TEST_WORKSPACE, API_KEY1);

        // simulate another user scoring the same trace by using the same name
        var user2Score = factory.manufacturePojo(FeedbackScore.class).toBuilder()
                .name(user1Score.name()).build();
        traceResourceClient.feedbackScore(trace1Id, user2Score, TEST_WORKSPACE, API_KEY2);

        // score another trace
        var anotherTraceScore = factory.manufacturePojo(FeedbackScore.class).toBuilder()
                .name(user1Score.name()).build();
        traceResourceClient.feedbackScore(trace2Id, anotherTraceScore, TEST_WORKSPACE, API_KEY1);

        var actual = traceResourceClient.getTraces(DEFAULT_PROJECT, null, API_KEY2, TEST_WORKSPACE, null,
                null, 5, Map.of());

        assertThat(actual.content()).hasSize(traces.size());
        var actualTrace1 = actual.content().stream().filter(trace -> trace.id().equals(trace1Id)).findFirst()
                .orElseThrow(() -> new AssertionError("Trace with id " + trace1Id + " not found"));
        assertThat(actualTrace1.feedbackScores()).hasSize(1);
        var actualScore = actualTrace1.feedbackScores().getFirst();

        // assert trace values
        assertAverageScore(actualScore.value(), user1Score, user2Score);
        assertAuthorValue(actualScore.valueByAuthor(), USER1, user1Score);
        assertAuthorValue(actualScore.valueByAuthor(), USER2, user2Score);

        // assert trace stats
        ProjectStats actualStats = traceResourceClient.getTraceStats(DEFAULT_PROJECT, null, API_KEY2,
                TEST_WORKSPACE, null, Map.of());
        TraceAssertions.assertStats(actualStats.stats(), StatsUtils.getProjectTraceStatItems(actual.content()));

        // assert value filtering
        var actualFilteredEqual = traceResourceClient.getTraces(DEFAULT_PROJECT, null, API_KEY2, TEST_WORKSPACE,
                List.of(TraceFilter.builder()
                        .field(TraceField.FEEDBACK_SCORES)
                        .key(user1Score.name())
                        .value(actualScore.value().toString())
                        .operator(Operator.EQUAL)
                        .build()),
                null, 5, Map.of());
        assertThat(actualFilteredEqual.content()).hasSize(1);
        assertThat(actualFilteredEqual.content().getFirst().id()).isEqualTo(trace1Id);

        // assert empty filtering
        var actualFilteredNotEmpty = traceResourceClient.getTraces(DEFAULT_PROJECT, null, API_KEY2, TEST_WORKSPACE,
                List.of(TraceFilter.builder()
                        .field(TraceField.FEEDBACK_SCORES)
                        .key(user1Score.name())
                        .value("")
                        .operator(Operator.IS_NOT_EMPTY)
                        .build()),
                null, 5, Map.of());
        assertThat(actualFilteredNotEmpty.content()).hasSize(2);
        assertThat(actualFilteredNotEmpty.content().stream().map(Trace::id))
                .containsExactlyInAnyOrder(trace1Id, trace2Id);

        // assert feedback project metric
        assertProjectMetric(actualTrace1.projectId(), MetricType.FEEDBACK_SCORES, user1Score.name(),
                List.of(user1Score.value(), user2Score.value()), anotherTraceScore.value());
    }

    @Test
    @DisplayName("test score span by multiple authors")
    void testScoreSpanByMultipleAuthors() {
        // create a trace first
        var trace = factory.manufacturePojo(Trace.class).toBuilder()
                .id(null)
                .projectName(DEFAULT_PROJECT)
                .usage(null)
                .feedbackScores(null)
                .build();
        var traceId = traceResourceClient.createTrace(trace, API_KEY1, TEST_WORKSPACE);

        // create spans
        var spans = PodamFactoryUtils.manufacturePojoList(factory, Span.class).stream()
                .map(span -> span.toBuilder()
                        .id(null)
                        .traceId(traceId)
                        .projectName(DEFAULT_PROJECT)
                        .usage(null)
                        .feedbackScores(null)
                        .totalEstimatedCost(null)
                        .build())
                .toList();
        var span1Id = spanResourceClient.createSpan(spans.getFirst(), API_KEY1, TEST_WORKSPACE);
        var span2Id = spanResourceClient.createSpan(spans.get(1), API_KEY1, TEST_WORKSPACE);
        spanResourceClient.batchCreateSpans(spans.subList(2, spans.size()), API_KEY1, TEST_WORKSPACE);

        // score the first span
        var user1Score = factory.manufacturePojo(FeedbackScore.class);
        spanResourceClient.feedbackScore(span1Id, user1Score, TEST_WORKSPACE, API_KEY1);

        // simulate another user scoring the same span by using the same name
        var user2Score = factory.manufacturePojo(FeedbackScore.class).toBuilder()
                .name(user1Score.name()).build();
        spanResourceClient.feedbackScore(span1Id, user2Score, TEST_WORKSPACE, API_KEY2);

        // score another span
        var anotherSpanScore = factory.manufacturePojo(FeedbackScore.class).toBuilder()
                .name(user1Score.name()).build();
        spanResourceClient.feedbackScore(span2Id, anotherSpanScore, TEST_WORKSPACE, API_KEY1);

        var actual = spanResourceClient.findSpans(TEST_WORKSPACE, API_KEY2, DEFAULT_PROJECT,
                null, null, 5, traceId, null, null, null, null);

        assertThat(actual.content()).hasSize(spans.size());
        var actualSpan1 = actual.content().stream().filter(span -> span.id().equals(span1Id)).findFirst()
                .orElseThrow(() -> new AssertionError("Span with id " + span1Id + " not found"));
        assertThat(actualSpan1.feedbackScores()).hasSize(1);
        var actualScore = actualSpan1.feedbackScores().getFirst();

        // assert span values
        assertAverageScore(actualScore.value(), user1Score, user2Score);
        assertAuthorValue(actualScore.valueByAuthor(), USER1, user1Score);
        assertAuthorValue(actualScore.valueByAuthor(), USER2, user2Score);

        // assert span stats
        ProjectStats actualStats = spanResourceClient.getSpansStats(DEFAULT_PROJECT, null, null, API_KEY2,
                TEST_WORKSPACE, Map.of());
        SpanAssertions.assertionStatusPage(actualStats.stats(), StatsUtils.getProjectSpanStatItems(actual.content()));

        // assert value filtering
        var actualFilteredEqual = spanResourceClient.findSpans(TEST_WORKSPACE, API_KEY2, DEFAULT_PROJECT,
                null, null, 5, traceId, null,
                List.of(SpanFilter.builder()
                        .field(SpanField.FEEDBACK_SCORES)
                        .key(user1Score.name())
                        .value(actualScore.value().toString())
                        .operator(Operator.EQUAL)
                        .build()),
                null, null);
        assertThat(actualFilteredEqual.content()).hasSize(1);
        assertThat(actualFilteredEqual.content().getFirst().id()).isEqualTo(span1Id);

        // assert empty filtering
        var actualFilteredNotEmpty = spanResourceClient.findSpans(TEST_WORKSPACE, API_KEY2, DEFAULT_PROJECT,
                null, null, 5, traceId, null,
                List.of(SpanFilter.builder()
                        .field(SpanField.FEEDBACK_SCORES)
                        .key(user1Score.name())
                        .value("")
                        .operator(Operator.IS_NOT_EMPTY)
                        .build()),
                null, null);
        assertThat(actualFilteredNotEmpty.content()).hasSize(2);
        assertThat(actualFilteredNotEmpty.content().stream().map(Span::id))
                .containsExactlyInAnyOrder(span1Id, span2Id);
    }

    @Test
    @DisplayName("test score thread by multiple authors")
    void testScoreThreadByMultipleAuthors() {
        // create thread identifiers
        var threadId1 = randomUUID().toString();
        var threadId2 = randomUUID().toString();

        var projectName = RandomStringUtils.secure().nextAlphanumeric(10);
        UUID projectId = projectResourceClient.createProject(projectName, API_KEY1, TEST_WORKSPACE);

        // open threads first
        traceResourceClient.openTraceThread(threadId1, null, projectName, API_KEY1, TEST_WORKSPACE);
        traceResourceClient.openTraceThread(threadId2, null, projectName, API_KEY1, TEST_WORKSPACE);

        // create traces within threads
        var trace1 = factory.manufacturePojo(Trace.class).toBuilder()
                .id(null)
                .threadId(threadId1)
                .projectName(projectName)
                .usage(null)
                .feedbackScores(null)
                .build();
        var trace2 = factory.manufacturePojo(Trace.class).toBuilder()
                .id(null)
                .threadId(threadId1)
                .projectName(projectName)
                .usage(null)
                .feedbackScores(null)
                .build();
        var trace3 = factory.manufacturePojo(Trace.class).toBuilder()
                .id(null)
                .threadId(threadId2)
                .projectName(projectName)
                .usage(null)
                .feedbackScores(null)
                .build();

        traceResourceClient.batchCreateTraces(List.of(trace1, trace2, trace3), API_KEY1, TEST_WORKSPACE);

        // close threads to ensure they are written to the trace_threads table
        traceResourceClient.closeTraceThread(threadId1, null, projectName, API_KEY1, TEST_WORKSPACE);
        traceResourceClient.closeTraceThread(threadId2, null, projectName, API_KEY1, TEST_WORKSPACE);

        // wait for threads to be created and get their IDs
        Awaitility.await().pollInterval(100, TimeUnit.MILLISECONDS).untilAsserted(() -> {
            TraceThread thread1 = traceResourceClient.getTraceThread(threadId1, projectId, API_KEY1, TEST_WORKSPACE);
            assertThat(thread1.threadModelId()).isNotNull();
        });

        // threads are now created and available for scoring

        // score the first thread by user 1
        var user1Score = factory.manufacturePojo(FeedbackScore.class);
        var user1ScoreItem = FeedbackScoreBatchItemThread.builder()
                .threadId(threadId1)
                .projectName(projectName)
                .name(user1Score.name())
                .categoryName(user1Score.categoryName())
                .value(user1Score.value())
                .reason(user1Score.reason())
                .source(user1Score.source())
                .build();
        traceResourceClient.threadFeedbackScores(List.of(user1ScoreItem), API_KEY1, TEST_WORKSPACE);

        // simulate another user scoring the same thread by using the same name
        var user2Score = factory.manufacturePojo(FeedbackScore.class).toBuilder()
                .name(user1Score.name()).build();
        var user2ScoreItem = FeedbackScoreBatchItemThread.builder()
                .threadId(threadId1)
                .projectName(projectName)
                .name(user2Score.name())
                .categoryName(user2Score.categoryName())
                .value(user2Score.value())
                .reason(user2Score.reason())
                .source(user2Score.source())
                .build();
        traceResourceClient.threadFeedbackScores(List.of(user2ScoreItem), API_KEY2, TEST_WORKSPACE);

        // score another thread
        var anotherThreadScore = factory.manufacturePojo(FeedbackScore.class).toBuilder()
                .name(user1Score.name()).build();
        var anotherThreadScoreItem = FeedbackScoreBatchItemThread.builder()
                .threadId(threadId2)
                .projectName(projectName)
                .name(anotherThreadScore.name())
                .categoryName(anotherThreadScore.categoryName())
                .value(anotherThreadScore.value())
                .reason(anotherThreadScore.reason())
                .source(anotherThreadScore.source())
                .build();
        traceResourceClient.threadFeedbackScores(List.of(anotherThreadScoreItem), API_KEY1, TEST_WORKSPACE);

        TraceThread.TraceThreadPage actual = traceResourceClient.getTraceThreads(projectId, projectName, API_KEY2,
                TEST_WORKSPACE, null, null, Map.of());

        assertThat(actual.content()).hasSize(2);
        var actualThread1 = actual.content().stream().filter(thread -> thread.id().equals(threadId1)).findFirst()
                .orElseThrow(() -> new AssertionError("Thread with id " + threadId1 + " not found"));
        assertThat(actualThread1.feedbackScores()).hasSize(1);
        var actualScore = actualThread1.feedbackScores().getFirst();

        // assert thread values
        assertAverageScore(actualScore.value(), user1Score, user2Score);
        assertAuthorValue(actualScore.valueByAuthor(), USER1, user1Score);
        assertAuthorValue(actualScore.valueByAuthor(), USER2, user2Score);

        // assert value filtering
        TraceThread.TraceThreadPage actualFilteredEqual = traceResourceClient.getTraceThreads(projectId,
                projectName, API_KEY2, TEST_WORKSPACE,
                List.of(TraceThreadFilter.builder()
                        .field(TraceThreadField.FEEDBACK_SCORES)
                        .key(user1Score.name())
                        .value(actualScore.value().toString())
                        .operator(Operator.EQUAL)
                        .build()),
                null, Map.of());
        assertThat(actualFilteredEqual.content()).hasSize(1);
        assertThat(actualFilteredEqual.content().getFirst().id()).isEqualTo(threadId1);

        // assert empty filtering
        TraceThread.TraceThreadPage actualFilteredNotEmpty = traceResourceClient.getTraceThreads(projectId,
                projectName, API_KEY2, TEST_WORKSPACE,
                List.of(TraceThreadFilter.builder()
                        .field(TraceThreadField.FEEDBACK_SCORES)
                        .key(user1Score.name())
                        .value("")
                        .operator(Operator.IS_NOT_EMPTY)
                        .build()),
                null, Map.of());
        assertThat(actualFilteredNotEmpty.content()).hasSize(2);
        assertThat(actualFilteredNotEmpty.content().stream().map(TraceThread::id))
                .containsExactlyInAnyOrder(threadId1, threadId2);
    }

    @Test
    @DisplayName("test score experiment by multiple authors")
    void testScoreExperimentByMultipleAuthors() {
        // first create a dataset
        var dataset = factory.manufacturePojo(Dataset.class).toBuilder()
                .id(null)
                .build();
        var datasetId = datasetResourceClient.createDataset(dataset, API_KEY1, TEST_WORKSPACE);

        // create an experiment with a specific name for retrieval
        var experimentName = RandomStringUtils.secure().nextAlphanumeric(10);
        var experiment = experimentResourceClient.createPartialExperiment()
                .name(experimentName)
                .datasetId(datasetId)
                .build();

        UUID experimentId = experimentResourceClient.create(experiment, API_KEY1, TEST_WORKSPACE);

        // create traces to relate to experiment items
        var trace1 = factory.manufacturePojo(Trace.class).toBuilder()
                .id(null)
                .projectName(DEFAULT_PROJECT)
                .usage(null)
                .feedbackScores(null)
                .build();

        var trace1Id = traceResourceClient.createTrace(trace1, API_KEY1, TEST_WORKSPACE);

        // create dataset items that link to our trace
        var datasetItem = factory.manufacturePojo(DatasetItem.class).toBuilder()
                .datasetId(datasetId)
                .traceId(trace1Id)
                .build();

        datasetResourceClient.createDatasetItems(new DatasetItemBatch(null, datasetId, List.of(datasetItem)),
                TEST_WORKSPACE, API_KEY1);

        // define the feedback scores for the same trace from different users
        var user1Score = factory.manufacturePojo(FeedbackScore.class);
        var user2Score = factory.manufacturePojo(FeedbackScore.class).toBuilder().name(user1Score.name()).build();

        // create scores as different users
        var user1ScoreItem = FeedbackScoreBatchItem.builder()
                .id(trace1Id)
                .projectName(DEFAULT_PROJECT)
                .name(user1Score.name())
                .categoryName(user1Score.categoryName())
                .value(user1Score.value())
                .reason(user1Score.reason())
                .source(user1Score.source())
                .build();

        var user2ScoreItem = FeedbackScoreBatchItem.builder()
                .id(trace1Id)
                .projectName(DEFAULT_PROJECT)
                .name(user2Score.name())
                .categoryName(user2Score.categoryName())
                .value(user2Score.value())
                .reason(user2Score.reason())
                .source(user2Score.source())
                .build();

        // submit scores from different users
        traceResourceClient.feedbackScores(List.of(user1ScoreItem), API_KEY1, TEST_WORKSPACE);
        traceResourceClient.feedbackScores(List.of(user2ScoreItem), API_KEY2, TEST_WORKSPACE);

        // create experiment items linking traces to experiment
        var experimentItem1 = factory.manufacturePojo(ExperimentItem.class).toBuilder()
                .experimentId(experimentId)
                .traceId(trace1Id)
                .datasetItemId(datasetItem.id())
                .feedbackScores(null)
                .build();

        experimentResourceClient.createExperimentItem(Set.of(experimentItem1), API_KEY1, TEST_WORKSPACE);

        assertFindExperiments(experimentName, user1Score, user2Score);
        assertStreamExperiments(experimentName, user1Score, user2Score);
        assertExperimentItemsStream(experimentName, user1Score, user2Score);
        assertDatasetItemsWithExperimentItems(experimentId, datasetId, user1Score, user2Score);
    }

    @Test
    @DisplayName("test score optimization by multiple authors")
    void testScoreOptimizationByMultipleAuthors() {
        // create a dataset
        var dataset = factory.manufacturePojo(Dataset.class).toBuilder()
                .id(null)
                .build();
        var datasetId = datasetResourceClient.createDataset(dataset, API_KEY1, TEST_WORKSPACE);

        // create dataset items
        var datasetItems = PodamFactoryUtils.manufacturePojoList(factory, DatasetItem.class).stream()
                .map(item -> item.toBuilder()
                        .datasetId(datasetId)
                        .build())
                .toList();
        datasetResourceClient.createDatasetItems(new DatasetItemBatch(null, datasetId, datasetItems),
                TEST_WORKSPACE, API_KEY1);

        // create an optimization
        var scoreName = RandomStringUtils.secure().nextAlphanumeric(10);
        var optimization = factory.manufacturePojo(Optimization.class).toBuilder()
                .id(null)
                .datasetId(datasetId)
                .datasetName(dataset.name())
                .objectiveName(scoreName)
                .status(OptimizationStatus.RUNNING)
                .numTrials(null)
                .feedbackScores(null)
                .build();

        var optimizationId = optimizationResourceClient.create(optimization, API_KEY1, TEST_WORKSPACE);

        // create an experiment as a trial for the optimization
        var experiment = experimentResourceClient.createPartialExperiment()
                .datasetId(datasetId)
                .optimizationId(optimizationId)
                .datasetName(dataset.name())
                .type(ExperimentType.TRIAL)
                .build();

        var experimentId = experimentResourceClient.create(experiment, API_KEY1, TEST_WORKSPACE);

        // create a single trace for the experiment
        var trace = factory.manufacturePojo(Trace.class).toBuilder()
                .projectName(DEFAULT_PROJECT)
                .usage(null)
                .feedbackScores(null)
                .build();

        var traceId = traceResourceClient.createTrace(trace, API_KEY1, TEST_WORKSPACE);

        // create experiment item linking trace to the experiment
        var experimentItem = factory.manufacturePojo(ExperimentItem.class).toBuilder()
                .experimentId(experimentId)
                .traceId(traceId)
                .datasetItemId(datasetItems.getFirst().id())
                .feedbackScores(null)
                .build();

        var experimentItems = Set.of(experimentItem);

        experimentResourceClient.createExperimentItem(experimentItems, API_KEY1, TEST_WORKSPACE);

        // score the trace with different values from different users
        var user1Score = factory.manufacturePojo(FeedbackScore.class).toBuilder()
                .name(scoreName)
                .build();
        traceResourceClient.feedbackScore(traceId, user1Score, TEST_WORKSPACE, API_KEY1);

        // simulate another user scoring the same trace by using the same name
        var user2Score = factory.manufacturePojo(FeedbackScore.class).toBuilder()
                .name(scoreName)
                .build();
        traceResourceClient.feedbackScore(traceId, user2Score, TEST_WORKSPACE, API_KEY2);

        // get the optimization and verify feedback scores
        var actualOptimization = optimizationResourceClient.get(optimizationId, API_KEY2, TEST_WORKSPACE, 200);

        assertThat(actualOptimization.feedbackScores()).hasSize(1);
        var actualScore = actualOptimization.feedbackScores().getFirst();
        assertThat(actualScore.name()).isEqualTo(scoreName);

        assertAverageScore(actualScore.value(), user1Score, user2Score);

        // verify find optimization also returns the correct averaged scores
        var optimizationPage = optimizationResourceClient.find(API_KEY2, TEST_WORKSPACE, 1, 10,
                datasetId, null, false, 200);
        assertThat(optimizationPage.content()).hasSize(1);
        var foundOptimization = optimizationPage.content().getFirst();
        assertThat(foundOptimization.feedbackScores()).hasSize(1);
        var foundScore = foundOptimization.feedbackScores().getFirst();
        assertThat(foundScore.name()).isEqualTo(scoreName);
        assertAverageScore(foundScore.value(), user1Score, user2Score);
    }

    private void assertAuthorValue(Map<String, ValueEntry> valueByAuthor, String author, FeedbackScore expected) {
        assertThat(valueByAuthor.get(author).categoryName()).isEqualTo(expected.categoryName());
        assertThat(valueByAuthor.get(author).value()).isEqualByComparingTo(expected.value());
        assertThat(valueByAuthor.get(author).reason()).isEqualTo(expected.reason());
        assertThat(valueByAuthor.get(author).source()).isEqualTo(expected.source());
    }

    private BigDecimal calcAverage(List<BigDecimal> scores) {
        return BigDecimal.valueOf(StatsUtils.avgFromList(scores));
    }

    private void assertAverageScore(BigDecimal actualValue, FeedbackScore user1Score, FeedbackScore user2Score) {
        assertThat(actualValue).usingComparator(StatsUtils::bigDecimalComparator)
                .isEqualTo(calcAverage(List.of(user1Score.value(), user2Score.value())));
    }

    private void assertProjectMetric(
            UUID projectId, MetricType metricType, String scoreName, List<BigDecimal> authoredValues,
            BigDecimal otherValue) {
        var projectMetrics = projectMetricsResourceClient.getProjectMetrics(projectId, ProjectMetricRequest.builder()
                .metricType(metricType)
                .interval(TimeInterval.HOURLY)
                .intervalStart(Instant.now().truncatedTo(ChronoUnit.HOURS))
                .intervalEnd(Instant.now())
                .build(), BigDecimal.class, API_KEY1, TEST_WORKSPACE);
        var scoreMetric = projectMetrics.results().stream()
                .filter(predicate -> predicate.name().equals(scoreName)).findFirst()
                .orElseThrow(() -> new AssertionError("Metric for score " + scoreName + " not found"));
        assertThat(scoreMetric.data()).hasSize(1);

        var firstTraceScore = calcAverage(authoredValues);
        var finalScore = calcAverage(List.of(firstTraceScore, otherValue));

        assertThat(scoreMetric.data().getFirst().value())
                .usingComparator(StatsUtils::bigDecimalComparator)
                .isEqualTo(finalScore);
    }

    private void assertFindExperiments(String experimentName, FeedbackScore user1Score, FeedbackScore user2Score) {
        var foundExperiments = experimentResourceClient.findExperiments(1, 10, experimentName, API_KEY2,
                TEST_WORKSPACE);
        assertThat(foundExperiments.content()).hasSize(1);

        var foundExperiment = foundExperiments.content().getFirst();
        assertThat(foundExperiment.feedbackScores()).hasSize(1);

        // verify the feedback score through find experiments is the same as through streaming
        var foundActualScore = foundExperiment.feedbackScores().stream()
                .filter(score -> score.name().equals(user1Score.name()))
                .findFirst()
                .orElseThrow();

        assertAverageScore(foundActualScore.value(), user1Score, user2Score);
    }

    private void assertStreamExperiments(String experimentName, FeedbackScore user1Score, FeedbackScore user2Score) {
        var experimentStreamRequest = ExperimentStreamRequest.builder()
                .name(experimentName)
                .build();
        var streamedExperiments = experimentResourceClient.streamExperiments(experimentStreamRequest, API_KEY2,
                TEST_WORKSPACE);
        var streamedExperiment = streamedExperiments.getFirst();

        assertThat(streamedExperiment.feedbackScores()).hasSize(1);

        // find the actual score and verify it's averaged
        var streamedActualScore = streamedExperiment.feedbackScores().stream()
                .filter(score -> score.name().equals(user1Score.name()))
                .findFirst()
                .orElseThrow();

        assertAverageScore(streamedActualScore.value(), user1Score, user2Score);
    }

    private void assertExperimentItemsStream(
            String experimentName, FeedbackScore user1Score, FeedbackScore user2Score) {
        var experimentItemStreamRequest = ExperimentItemStreamRequest.builder()
                .experimentName(experimentName)
                .build();
        var streamedItems = experimentResourceClient.streamExperimentItems(experimentItemStreamRequest, API_KEY2,
                TEST_WORKSPACE);

        assertThat(streamedItems).hasSize(1);

        // verify that the experiment item has the correct feedback scores with multi-author data
        var item = streamedItems.getFirst();
        assertThat(item.feedbackScores()).hasSize(1);

        var itemScore = item.feedbackScores().stream()
                .filter(score -> score.name().equals(user1Score.name()))
                .findFirst()
                .orElseThrow();

        // experiment items preserve the full feedback score data including valueByAuthor
        assertThat(itemScore.valueByAuthor()).hasSize(2);
        assertAuthorValue(itemScore.valueByAuthor(), USER1, user1Score);
        assertAuthorValue(itemScore.valueByAuthor(), USER2, user2Score);

        // the value should be the average
        assertAverageScore(itemScore.value(), user1Score, user2Score);
    }

    private void assertDatasetItemsWithExperimentItems(
            UUID experimentId, UUID datasetId, FeedbackScore user1Score, FeedbackScore user2Score) {
        // now call the dataset items with experiment items API
        var datasetItemPage = datasetResourceClient.getDatasetItemsWithExperimentItems(datasetId, List.of(experimentId),
                API_KEY2, TEST_WORKSPACE);

        assertThat(datasetItemPage.content()).hasSize(1);

        // verify that the dataset item shows the experiment items with correct feedback scores
        var retrievedDatasetItem = datasetItemPage.content().getFirst();
        assertThat(retrievedDatasetItem.experimentItems()).hasSize(1);

        var experimentItemFromDataset = retrievedDatasetItem.experimentItems().getFirst();
        assertThat(experimentItemFromDataset.feedbackScores()).hasSize(1);

        var scoreFromDatasetApi = experimentItemFromDataset.feedbackScores().stream()
                .filter(score -> score.name().equals(user1Score.name()))
                .findFirst()
                .orElseThrow();

        // verify this also preserves the multi-author feedback score data
        assertThat(scoreFromDatasetApi.valueByAuthor()).hasSize(2);
        assertAuthorValue(scoreFromDatasetApi.valueByAuthor(), USER1, user1Score);
        assertAuthorValue(scoreFromDatasetApi.valueByAuthor(), USER2, user2Score);

        // verify the average is correct
        assertAverageScore(scoreFromDatasetApi.value(), user1Score, user2Score);
    }
}
