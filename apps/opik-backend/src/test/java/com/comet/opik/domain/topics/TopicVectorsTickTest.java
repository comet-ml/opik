package com.comet.opik.domain.topics;

import com.comet.opik.api.events.TracesDeleted;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.PostCutoverTraces;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.v1.jobs.TopicVectorsJob;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.ailabels.AiLabel;
import com.comet.opik.domain.ailabels.AiLabelStatus;
import com.comet.opik.domain.ailabels.AiLabelStatusDAO;
import com.comet.opik.domain.ailabels.AiLabelTaskRegistry;
import com.comet.opik.domain.ailabels.AiLabelWorkspaceDAO;
import com.comet.opik.domain.ailabels.AiLabelsDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.inference.InferenceClient;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.comet.opik.infrastructure.lock.LockService;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.extension.ResponseTransformerV2;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.http.Response;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.google.common.eventbus.EventBus;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.jobs.GuiceJobManager;
import io.opentelemetry.api.common.Attributes;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.quartz.JobKey;
import org.quartz.SchedulerException;
import org.quartz.SimpleTrigger;
import org.quartz.impl.matchers.GroupMatcher;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.dropwizard.guice.test.jupiter.param.Jit;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.ailabels.AiLabelTaskLoader.CLASSPATH_DIRECTORY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The topic vectors tick against real ClickHouse, MySQL and Redis, with opik-inference's {@code /v1/embed} stubbed in
 * WireMock by {@link EmbedStub}, which answers every input with a deterministic vector of its text. Tests seed
 * {@code ai_label_status} and {@code ai_labels} directly and call {@link TopicVectorsTickService#tick(Instant)}; the
 * scheduled job fires once at startup and then only every hour.
 */
@DisplayName("topic vectors tick")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TopicVectorsTickTest {

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();
    private static final String EMBED_PATH = "/v1/embed";
    private static final String TASK = TopicVectorsTickService.TASK;
    // Short, so a test can wait for the daily pass instead of a day.
    private static final Duration LONG_WINDOW_INTERVAL = Duration.ofSeconds(5);

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
    // Not reused: the opt-in read spans the whole table, and the traces cutover is destructive.
    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer(false);
    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer(false, network);
    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer(false, network,
            zookeeper);

    private final EmbedStub embedStub = new EmbedStub();
    private final WireMockServer wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redis, mysql, zookeeper, clickHouse).join();
        wireMock = new WireMockServer(wireMockConfig().dynamicPort().extensions(embedStub));
        wireMock.start();
        MigrationUtils.runMysqlDbMigration(mysql);
        try {
            PostCutoverTraces.migrate(clickHouse);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(AppContextConfig.builder()
                .jdbcUrl(mysql.getJdbcUrl())
                .databaseAnalyticsFactory(ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouse,
                        DATABASE_NAME))
                .runtimeInfo(new WireMockRuntimeInfo(wireMock))
                .redisUrl(redis.getRedisURI())
                .customConfigs(List.of(
                        new CustomConfig("databaseAnalyticsDataModel.traceColumnsNonNullable", "true"),
                        new CustomConfig("databaseAnalyticsDataModel.tracesDistributedWrapEnabled", "true"),
                        new CustomConfig("aiLabels.enabled", "true"),
                        new CustomConfig("aiLabels.inference.url", wireMock.baseUrl()),
                        new CustomConfig("aiLabels.inference.credential", "test-credential"),
                        new CustomConfig("aiLabels.tasksDir", writeTasks().toString()),
                        new CustomConfig("aiLabels.tickInterval", "1h"),
                        new CustomConfig("aiLabels.healthInterval", "1h"),
                        new CustomConfig("topics.enabled", "true"),
                        new CustomConfig("topics.vectors.tickInterval", "1h"),
                        new CustomConfig("topics.vectors.tickBudget", "30s"),
                        new CustomConfig("topics.vectors.longWindowInterval",
                                LONG_WINDOW_INTERVAL.toSeconds() + "s")))
                .build());
    }

    private OpikConfiguration opikConfig;
    private TransactionTemplate template;
    private TransactionTemplateAsync templateAsync;
    private TopicVectorsTickService tickService;
    private TopicVectorsDAO vectorsDAO;
    private AiLabelStatusDAO statusDAO;
    private AiLabelsDAO labelsDAO;
    private GuiceJobManager jobManager;
    private EventBus eventBus;
    private TopicsConfig.VectorsConfig defaults;
    private InferenceClient inferenceClient;
    private LockService lockService;
    private AiLabelTaskRegistry taskRegistry;

    @BeforeAll
    void beforeAll(OpikConfiguration opikConfig, TransactionTemplate template, TransactionTemplateAsync templateAsync,
            @Jit LockService lockService, GuiceJobManager jobManager, EventBus eventBus,
            @Jit TopicVectorsTickService tickService, @Jit TopicVectorsDAO vectorsDAO,
            @Jit AiLabelStatusDAO statusDAO, @Jit AiLabelsDAO labelsDAO, @Jit InferenceClient inferenceClient,
            @Jit AiLabelTaskRegistry taskRegistry) {
        this.inferenceClient = inferenceClient;
        this.lockService = lockService;
        this.taskRegistry = taskRegistry;
        this.opikConfig = opikConfig;
        this.template = template;
        this.templateAsync = templateAsync;
        this.tickService = tickService;
        this.vectorsDAO = vectorsDAO;
        this.statusDAO = statusDAO;
        this.labelsDAO = labelsDAO;
        this.jobManager = jobManager;
        this.eventBus = eventBus;
        var vectors = config();
        this.defaults = new TopicsConfig.VectorsConfig();
        defaults.setTickBudget(vectors.getTickBudget());
        defaults.setEmbedBatchSize(vectors.getEmbedBatchSize());
        defaults.setEmbedTimeout(vectors.getEmbedTimeout());
        defaults.setMaxTracesPerTick(vectors.getMaxTracesPerTick());
        defaults.setTickInterval(vectors.getTickInterval());

        // The job fires once at startup: wait for that tick to release the leader lock, so it can't race the tests.
        lockService.bestEffortLock(new LockService.Lock("topics:vectors:leader"), Mono.just(true), Mono.empty(),
                Duration.ofSeconds(5), Duration.ofSeconds(60)).block();
    }

    @AfterAll
    void afterAll() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        template.inTransaction(WRITE, handle -> handle.execute("UPDATE ai_label_workspaces SET enabled = FALSE"));
        wireMock.resetAll();
        wireMock.stubFor(post(urlEqualTo(EMBED_PATH)).willReturn(aResponse().withTransformers(EmbedStub.NAME)));
        embedStub.reset();
    }

    @AfterEach
    void tearDown() {
        config().setTickBudget(defaults.getTickBudget());
        config().setEmbedBatchSize(defaults.getEmbedBatchSize());
        config().setEmbedTimeout(defaults.getEmbedTimeout());
        config().setMaxTracesPerTick(defaults.getMaxTracesPerTick());
        config().setTickInterval(defaults.getTickInterval());
    }

    @Test
    @DisplayName("enabled, the tick job is scheduled every tickInterval")
    void jobIsScheduled() throws SchedulerException {
        var scheduler = jobManager.getScheduler();
        var jobKeys = new ArrayList<JobKey>();
        for (var key : scheduler.getJobKeys(GroupMatcher.anyGroup())) {
            if (scheduler.getJobDetail(key).getJobClass() == TopicVectorsJob.class) {
                jobKeys.add(key);
            }
        }

        assertThat(jobKeys).singleElement().satisfies(key -> assertThat(scheduler.getTriggersOfJob(key))
                .singleElement()
                .isInstanceOfSatisfying(SimpleTrigger.class, trigger -> assertThat(trigger.getRepeatInterval())
                        .isEqualTo(Duration.ofHours(1).toMillis())));
    }

    @Test
    @DisplayName("vectors appear only for enabled workspaces, as the stub's unit-length vectors of the stored text")
    void onlyEnabledWorkspaces() {
        var enabled = List.of(enabledWorkspace(), enabledWorkspace());
        var disabled = UUID.randomUUID().toString();
        var labeled = new java.util.LinkedHashMap<String, List<UUID>>();
        for (var workspaceId : List.of(enabled.getFirst(), enabled.getLast(), disabled)) {
            labeled.put(workspaceId, labelTraces(workspaceId, 3, 1));
        }

        var result = tickService.tick(Instant.now());

        assertThat(result.written()).isEqualTo(6);
        for (var workspaceId : enabled) {
            var vectors = vectors(workspaceId, labeled.get(workspaceId));
            assertThat(vectors).extracting(TopicVector::traceId)
                    .containsExactlyInAnyOrderElementsOf(labeled.get(workspaceId));
            assertThat(vectors).allSatisfy(vector -> {
                assertThat(vector.text()).isEqualTo(summaryOf(vector.traceId()));
                assertVectorClose(vector.vector(), expectedVector(vector.text()));
                assertThat(vector.embedModel()).isEqualTo("bge-small-en-v1.5");
                assertThat(vector.embedDimensions()).isEqualTo(384);
                assertThat(vector.taskVersion()).isEqualTo(1);
            });
        }
        assertThat(vectors(disabled, labeled.get(disabled))).isEmpty();
        var sent = wireMock.findAll(postRequestedFor(urlEqualTo(EMBED_PATH)));
        assertThat(sent).allSatisfy(request -> {
            var body = json(request.getBodyAsString());
            assertThat(enabled).contains(body.path("org_id").asText());
            assertThat(body.path("cache").asBoolean(true)).isFalse();
        });
    }

    @Test
    @DisplayName("a second tick writes nothing new")
    void idempotent() {
        var workspaceId = enabledWorkspace();
        var traceIds = labelTraces(workspaceId, 5, 1);
        assertThat(tickService.tick(Instant.now()).written()).isEqualTo(5);
        int calls = embedStub.calls.get();

        var second = tickService.tick(Instant.now());

        assertThat(second.written()).isZero();
        assertThat(embedStub.calls.get()).isEqualTo(calls);
        assertThat(countRows(workspaceId)).isEqualTo(5);
        assertThat(vectors(workspaceId, traceIds)).hasSize(5);
    }

    @Test
    @DisplayName("a status older than the short window is found only by the tick on which the long-window pass is due")
    void longWindowPass() {
        // Wait for a tick that runs the pass, so the next one certainly doesn't.
        Awaitility.await().atMost(20, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(500))
                .until(() -> tickService.tick(Instant.now()).longWindowPass());
        var workspaceId = enabledWorkspace();
        var traceId = ID_GENERATOR.generateId();
        var projectId = UUID.randomUUID();
        labelsDAO.insert(List.of(outputLabel(workspaceId, projectId, traceId, 1))).block();
        insertStatusAt(workspaceId, projectId, traceId, 1, Instant.now().minus(2, ChronoUnit.DAYS));

        var normal = tickService.tick(Instant.now());

        assertThat(normal.longWindowPass()).isFalse();
        assertThat(normal.written()).isZero();
        assertThat(vectors(workspaceId, List.of(traceId))).isEmpty();

        Awaitility.await().atMost(20, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    var result = tickService.tick(Instant.now());
                    assertThat(result.longWindowPass()).isTrue();
                    assertThat(result.written()).isOne();
                });
        assertThat(vectors(workspaceId, List.of(traceId))).extracting(TopicVector::traceId).containsExactly(traceId);
    }

    @Test
    @DisplayName("a 429 on the second chunk: the first chunk is written, the next tick completes the second")
    void throttledSecondChunk() {
        config().setEmbedBatchSize(10);
        var workspaceId = enabledWorkspace();
        var traceIds = labelTraces(workspaceId, 15, 1);
        embedStub.throttleCall = 2;

        var first = tickService.tick(Instant.now());

        assertThat(first.written()).isEqualTo(10);
        assertThat(first.skipped()).isEqualTo(Map.of(TopicVectorsTickService.EMBED_FAILED, 5));
        assertThat(countRows(workspaceId)).isEqualTo(10);

        var second = tickService.tick(Instant.now());

        assertThat(second.written()).isEqualTo(5);
        assertThat(vectors(workspaceId, traceIds)).extracting(TopicVector::traceId)
                .containsExactlyInAnyOrderElementsOf(traceIds);
    }

    @Test
    @DisplayName("an embed timeout writes nothing, escapes nothing, and is counted in the embed failures metric")
    void embedTimeout() {
        config().setEmbedTimeout(io.dropwizard.util.Duration.milliseconds(500));
        var workspaceId = enabledWorkspace();
        labelTraces(workspaceId, 4, 1);
        embedStub.delay = Duration.ofSeconds(3);
        var meter = new TopicVectorsTickServiceTest.TestMeter();
        var metered = new TopicVectorsTickService(opikConfig.getTopics(), opikConfig.getAiLabels(), template,
                vectorsDAO, inferenceClient, lockService, taskRegistry, meter.meter);

        var result = metered.tick(Instant.now());

        assertThat(result.written()).isZero();
        assertThat(result.skipped()).isEqualTo(Map.of(TopicVectorsTickService.EMBED_FAILED, 4));
        assertThat(countRows(workspaceId)).isZero();
        var attributes = ArgumentCaptor.forClass(Attributes.class);
        verify(meter.counter("topics.vectors.embed.failures")).add(eq(1L), attributes.capture());
        assertThat(attributes.getValue().get(stringKey("code"))).isIn("upstream_error", "out_of_time");
        verify(meter.counter("topics.vectors.skipped"))
                .add(4, Attributes.of(stringKey("reason"), TopicVectorsTickService.EMBED_FAILED));
        verify(meter.counter("topics.vectors.written"), never()).add(anyLong());
    }

    @Test
    @DisplayName("the find guards reach the window read: max_rows_to_read 1 trips it, counted as a find read failure; "
            + "the default reads it")
    void findGuardsReachTheWindowRead() {
        var workspaceId = enabledWorkspace();
        labelTraces(workspaceId, 3, 1);
        var meter = new TopicVectorsTickServiceTest.TestMeter();
        var metered = new TopicVectorsTickService(opikConfig.getTopics(), opikConfig.getAiLabels(), template,
                vectorsDAO, inferenceClient, lockService, taskRegistry, meter.meter);
        var find = config().getFind();
        var maxRowsToRead = find.getMaxRowsToRead();
        find.setMaxRowsToRead(1);
        TopicVectorsTickService.TickResult tripped;
        try {
            // ClickHouse's TOO_MANY_ROWS: the guard, not some other failure, fails the read.
            assertThatThrownBy(() -> vectorsDAO.findUnvectored(workspaceId, TASK,
                    Instant.now().minus(1, ChronoUnit.HOURS), Instant.now(), Instant.EPOCH, 100, 10)
                    .collectList().block())
                    .hasStackTraceContaining("Code: 158");
            tripped = metered.tick(Instant.now());
        } finally {
            find.setMaxRowsToRead(maxRowsToRead);
        }

        assertThat(tripped.written()).isZero();
        assertThat(countRows(workspaceId)).isZero();
        var attributes = ArgumentCaptor.forClass(Attributes.class);
        verify(meter.counter("topics.vectors.read.failures")).add(eq(1L), attributes.capture());
        assertThat(attributes.getValue().get(stringKey("phase"))).isEqualTo("find");
        assertThat(attributes.getValue().get(stringKey("workspace_id"))).isEqualTo(workspaceId);
        assertThat(attributes.getValue().get(stringKey("error_type"))).isNotBlank();

        assertThat(metered.tick(Instant.now()).written()).isEqualTo(3);
    }

    @Test
    @DisplayName("a done status without an output row is skipped and counted, the rest written")
    void missingOutputRow() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        var traceIds = labelTraces(workspaceId, projectId, 2, 1);
        var withoutOutput = ID_GENERATOR.generateId();
        statusDAO.insert(List.of(doneStatus(workspaceId, projectId, withoutOutput, 1))).block();

        var result = tickService.tick(Instant.now());

        assertThat(result.written()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(Map.of(TopicVectorsTickService.MISSING_SUMMARY, 1));
        assertThat(vectors(workspaceId, List.of(withoutOutput))).isEmpty();
        assertThat(vectors(workspaceId, traceIds)).hasSize(2);
    }

    @Test
    @DisplayName("two task versions of one trace give two rows, both kept")
    void twoTaskVersions() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        var traceId = ID_GENERATOR.generateId();
        labelsDAO.insert(List.of(outputLabel(workspaceId, projectId, traceId, 1))).block();
        statusDAO.insert(List.of(doneStatus(workspaceId, projectId, traceId, 1))).block();
        assertThat(tickService.tick(Instant.now()).written()).isOne();

        // A relabel: the new version's label, then its status, as ai-labels writes them.
        labelsDAO.insert(List.of(outputLabel(workspaceId, projectId, traceId, 2))).block();
        statusDAO.insert(List.of(doneStatus(workspaceId, projectId, traceId, 2))).block();
        assertThat(tickService.tick(Instant.now()).written()).isOne();

        assertThat(vectors(workspaceId, List.of(traceId)))
                .extracting(TopicVector::taskVersion, TopicVector::text)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(1, summaryOf(traceId, 1)),
                        org.assertj.core.groups.Tuple.tuple(2, summaryOf(traceId, 2)));
    }

    @Test
    @DisplayName("with a cap of 5,000, one tick writes 5,000 of 20,000 traces, oldest status first, and four ticks "
            + "write all")
    void cap() {
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        // Four batches of 5,000, labeled in this order. Each batch's ids are generated after the next one's, so trace id
        // order is the reverse of status order: the ticks can only follow status order if they sort by updated_at.
        var batches = new ArrayList<List<UUID>>();
        for (int i = 0; i < 4; i++) {
            batches.addFirst(IntStream.range(0, 5_000).mapToObj(j -> ID_GENERATOR.generateId()).toList());
        }
        for (var batch : batches) {
            label(workspaceId, projectId, batch, 1);
        }

        for (var batch : batches) {
            var before = Set.copyOf(writtenTraceIds(workspaceId));
            assertThat(tickService.tick(Instant.now()).written()).isEqualTo(5_000);
            var now = Set.copyOf(writtenTraceIds(workspaceId));
            assertThat(now).hasSize(before.size() + 5_000).containsAll(batch);
        }

        assertThat(countRows(workspaceId)).isEqualTo(20_000);
        assertThat(tickService.tick(Instant.now()).written()).isZero();
    }

    @Test
    @DisplayName("the cap is per workspace, and a 429 with an unknown code backs off only its workspace")
    void perWorkspaceCapAndBackOff() {
        config().setMaxTracesPerTick(4);
        config().setEmbedBatchSize(2);
        var throttled = enabledWorkspace();
        var healthy = enabledWorkspace();
        var throttledTraces = labelTraces(throttled, 6, 1);
        var healthyTraces = labelTraces(healthy, 6, 1);
        embedStub.fail(throttled, 429, "{\"error\":{\"code\":\"rate_limited\",\"message\":\"m\"}}");

        var result = tickService.tick(Instant.now());

        assertThat(embedStub.callsFor(throttled)).isOne();
        assertThat(result.written()).isEqualTo(4);
        assertThat(result.skipped()).isEqualTo(Map.of(TopicVectorsTickService.EMBED_FAILED, 4));
        assertThat(vectors(healthy, healthyTraces)).hasSize(4);
        assertThat(vectors(throttled, throttledTraces)).isEmpty();

        embedStub.reset();
        assertThat(tickService.tick(Instant.now()).written()).isEqualTo(6);
        assertThat(vectors(throttled, throttledTraces)).hasSize(4);
        assertThat(vectors(healthy, healthyTraces)).hasSize(6);
    }

    @Test
    @DisplayName("a 5xx with an unknown code and no retryable flag backs the workspace off after one call")
    void backOffOnUnknown5xx() {
        config().setEmbedBatchSize(2);
        var workspaceId = enabledWorkspace();
        labelTraces(workspaceId, 6, 1);
        embedStub.fail(workspaceId, 503, "{\"error\":{\"code\":\"something_new\",\"message\":\"m\"}}");

        var result = tickService.tick(Instant.now());

        assertThat(embedStub.callsFor(workspaceId)).isOne();
        assertThat(result.written()).isZero();
        assertThat(result.skipped()).isEqualTo(Map.of(TopicVectorsTickService.EMBED_FAILED, 6));
    }

    @Test
    @DisplayName("the long-window pass stays on while its ticks reach the cap, and stops once one finds fewer")
    void longWindowUntilDrained() {
        // Drain any pass in progress, and wait for a pass to be due, so the next tick is a long one.
        Awaitility.await().atMost(20, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(500))
                .until(() -> tickService.tick(Instant.now()).longWindowPass());
        config().setMaxTracesPerTick(2);
        var workspaceId = enabledWorkspace();
        var projectId = UUID.randomUUID();
        var traceIds = IntStream.range(0, 5).mapToObj(i -> ID_GENERATOR.generateId()).toList();
        labelsDAO.insert(traceIds.stream().map(traceId -> outputLabel(workspaceId, projectId, traceId, 1)).toList())
                .block();
        for (int i = 0; i < traceIds.size(); i++) {
            insertStatusAt(workspaceId, projectId, traceIds.get(i), 1,
                    Instant.now().minus(2, ChronoUnit.DAYS).plusSeconds(i));
        }
        Awaitility.await().atMost(20, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(500))
                .until(() -> !tickService.tick(Instant.now()).longWindowPass());

        var ticks = new ArrayList<TopicVectorsTickService.TickResult>();
        Awaitility.await().atMost(20, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(500))
                .until(() -> {
                    var result = tickService.tick(Instant.now());
                    if (result.longWindowPass()) {
                        ticks.add(result);
                    }
                    return result.longWindowPass();
                });
        ticks.add(tickService.tick(Instant.now()));
        ticks.add(tickService.tick(Instant.now()));
        ticks.add(tickService.tick(Instant.now()));

        assertThat(ticks)
                .extracting(TopicVectorsTickService.TickResult::longWindowPass,
                        TopicVectorsTickService.TickResult::written)
                .containsExactly(tuple(true, 2), tuple(true, 2), tuple(true, 1), tuple(false, 0));
        assertThat(vectors(workspaceId, traceIds)).hasSize(5);
    }

    @Test
    @DisplayName("a tick that runs out of budget starts no chunk with less than the embed timeout left, counts the rest "
            + "as over_budget, and the next tick continues")
    void budget() {
        config().setEmbedBatchSize(10);
        config().setTickBudget(io.dropwizard.util.Duration.seconds(2));
        config().setEmbedTimeout(io.dropwizard.util.Duration.milliseconds(500));
        var workspaceId = enabledWorkspace();
        var traceIds = labelTraces(workspaceId, 100, 1);
        embedStub.delay = Duration.ofMillis(300);

        var first = tickService.tick(Instant.now());

        assertThat(first.written()).isPositive().isLessThan(100);
        assertThat(first.skipped()).isEqualTo(Map.of(TopicVectorsTickService.OVER_BUDGET, 100 - first.written()));
        assertThat(embedStub.calls.get() * 10).isEqualTo(first.written());

        embedStub.delay = Duration.ZERO;
        config().setTickBudget(defaults.getTickBudget());
        var second = tickService.tick(Instant.now());

        assertThat(first.written() + second.written()).isEqualTo(100);
        assertThat(vectors(workspaceId, traceIds)).hasSize(100);
    }

    @Nested
    @DisplayName("storage")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Storage {

        @Test
        @DisplayName("the window read finds a done status without a vector, and nothing once the vector is written")
        void windowReadAndAntiJoin() {
            var workspaceId = UUID.randomUUID().toString();
            var projectId = UUID.randomUUID();
            var traceId = ID_GENERATOR.generateId();
            var failedTraceId = ID_GENERATOR.generateId();
            var status = doneStatus(workspaceId, projectId, traceId, 3);
            statusDAO.insert(List.of(status)).block();
            // Done, then failed: its latest row is not done.
            statusDAO.insert(List.of(doneStatus(workspaceId, projectId, failedTraceId, 3))).block();
            statusDAO.insert(List.of(doneStatus(workspaceId, projectId, failedTraceId, 3).toBuilder()
                    .status(AiLabelStatus.Status.FAILED).build())).block();
            var lower = Instant.now().minus(1, ChronoUnit.HOURS);

            var found = findUnvectored(workspaceId, lower);

            assertThat(found).singleElement().satisfies(candidate -> {
                assertThat(candidate.traceId()).isEqualTo(traceId);
                assertThat(candidate.projectId()).isEqualTo(projectId);
                assertThat(candidate.taskVersion()).isEqualTo(3);
                assertThat(candidate.traceCreatedAt()).isEqualTo(status.traceCreatedAt());
            });

            vectorsDAO.insert(List.of(vector(workspaceId, projectId, traceId, 3))).block();

            assertThat(findUnvectored(workspaceId, lower)).isEmpty();
            // Another version of the same trace still needs its own vector.
            statusDAO.insert(List.of(doneStatus(workspaceId, projectId, traceId, 4))).block();
            assertThat(findUnvectored(workspaceId, lower)).extracting(TopicVectorCandidate::taskVersion)
                    .containsExactly(4);
        }

        @Test
        @DisplayName("the trace_created_at bound drops older traces from both sides alike, so the anti-join stays exact")
        void createdAtBound() {
            var workspaceId = UUID.randomUUID().toString();
            var projectId = UUID.randomUUID();
            var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            var createdAfter = now.minus(9, ChronoUnit.DAYS);
            var inside = ID_GENERATOR.generateId();
            var insideCreatedAt = createdAfter.plus(1, ChronoUnit.HOURS);
            var outside = ID_GENERATOR.generateId();
            statusDAO.insert(List.of(
                    doneStatus(workspaceId, projectId, inside, 1).toBuilder().traceCreatedAt(insideCreatedAt).build(),
                    doneStatus(workspaceId, projectId, outside, 1).toBuilder()
                            .traceCreatedAt(createdAfter.minus(1, ChronoUnit.HOURS)).build()))
                    .block();
            var lower = now.minus(1, ChronoUnit.HOURS);

            assertThat(findUnvectored(workspaceId, lower, createdAfter)).extracting(TopicVectorCandidate::traceId)
                    .containsExactly(inside);
            // Without the bound both are found: the bound is what leaves the older one out.
            assertThat(findUnvectored(workspaceId, lower, Instant.EPOCH)).extracting(TopicVectorCandidate::traceId)
                    .containsExactlyInAnyOrder(inside, outside);

            vectorsDAO.insert(List.of(vector(workspaceId, projectId, inside, 1).toBuilder()
                    .traceCreatedAt(insideCreatedAt).build())).block();

            assertThat(findUnvectored(workspaceId, lower, createdAfter)).isEmpty();
            // A status rewritten long after its vector, of a trace still inside the bound, stays dropped.
            statusDAO.insert(List.of(doneStatus(workspaceId, projectId, inside, 1).toBuilder()
                    .traceCreatedAt(insideCreatedAt).build())).block();
            assertThat(findUnvectored(workspaceId, lower, createdAfter)).isEmpty();
        }

        @Test
        @DisplayName("vectors read back as written, and id_at and the partition equal ai_label_status's")
        void roundTripAndPartition() {
            var workspaceId = UUID.randomUUID().toString();
            var projectId = UUID.randomUUID();
            var monday = Instant.parse("2025-03-03T00:00:00Z");
            var traceIds = List.of(ID_GENERATOR.generateId(), ID_GENERATOR.generateId(monday),
                    ID_GENERATOR.generateId(monday.minusMillis(1)));
            var rows = traceIds.stream().map(traceId -> vector(workspaceId, projectId, traceId, 1)).toList();
            vectorsDAO.insert(rows).block();
            statusDAO.insert(traceIds.stream().map(traceId -> doneStatus(workspaceId, projectId, traceId, 1))
                    .toList()).block();

            var read = vectors(workspaceId, traceIds);
            assertThat(read).usingRecursiveFieldByFieldElementComparatorIgnoringFields("vector")
                    .containsExactlyInAnyOrderElementsOf(rows);
            var expectedByTrace = rows.stream().collect(Collectors.toMap(TopicVector::traceId, TopicVector::vector));
            read.forEach(vector -> assertVectorClose(vector.vector(), expectedByTrace.get(vector.traceId())));
            assertThat(idAtAndPartition(TopicVectorsDAO.TABLE, workspaceId))
                    .isEqualTo(idAtAndPartition("ai_label_status", workspaceId));
        }

        @Test
        @DisplayName("the trace-delete event deletes the traces' vectors and leaves other traces and workspaces")
        void traceDeleteCascade() {
            var workspaceId = UUID.randomUUID().toString();
            var otherWorkspaceId = UUID.randomUUID().toString();
            var projectId = UUID.randomUUID();
            var deleted = ID_GENERATOR.generateId();
            var kept = ID_GENERATOR.generateId();
            vectorsDAO.insert(List.of(vector(workspaceId, projectId, deleted, 1),
                    vector(workspaceId, projectId, kept, 1),
                    vector(otherWorkspaceId, projectId, deleted, 1))).block();

            eventBus.post(new TracesDeleted(Set.of(deleted), projectId, workspaceId, "user"));

            Awaitility.await().atMost(30, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(300))
                    .untilAsserted(() -> assertThat(vectors(workspaceId, Set.of(deleted, kept)))
                            .extracting(TopicVector::traceId).containsExactly(kept));
            assertThat(vectors(otherWorkspaceId, List.of(deleted))).hasSize(1);
        }

        @Test
        @DisplayName("retention deletes vectors in range, and keeps experiment-linked, newer and other workspaces' traces")
        void retention() {
            var workspaceId = UUID.randomUUID().toString();
            var otherWorkspaceId = UUID.randomUUID().toString();
            var projectId = UUID.randomUUID();
            var now = Instant.now();
            var old = ID_GENERATOR.generateId(now.minus(20, ChronoUnit.DAYS));
            var oldInExperiment = ID_GENERATOR.generateId(now.minus(20, ChronoUnit.DAYS));
            var recent = ID_GENERATOR.generateId(now.minus(1, ChronoUnit.DAYS));
            vectorsDAO.insert(List.of(vector(workspaceId, projectId, old, 1),
                    vector(workspaceId, projectId, oldInExperiment, 1),
                    vector(workspaceId, projectId, recent, 1),
                    vector(otherWorkspaceId, projectId, old, 1))).block();
            insertExperimentItem(workspaceId, oldInExperiment);

            vectorsDAO.deleteForRetention(List.of(workspaceId),
                    ID_GENERATOR.generateId(now.minus(10, ChronoUnit.DAYS)),
                    ID_GENERATOR.generateId(now.minus(30, ChronoUnit.DAYS))).block();

            assertThat(vectors(workspaceId, List.of(old, oldInExperiment, recent)))
                    .extracting(TopicVector::traceId).containsExactlyInAnyOrder(oldInExperiment, recent);
            assertThat(vectors(otherWorkspaceId, List.of(old))).hasSize(1);
        }

        @Test
        @DisplayName("bounded retention deletes from each workspace's own floor up to the cutoff")
        void boundedRetention() {
            var workspaceId = UUID.randomUUID().toString();
            var projectId = UUID.randomUUID();
            var now = Instant.now();
            var belowFloor = ID_GENERATOR.generateId(now.minus(20, ChronoUnit.DAYS));
            var aboveFloor = ID_GENERATOR.generateId(now.minus(12, ChronoUnit.DAYS));
            var afterCutoff = ID_GENERATOR.generateId(now.minus(5, ChronoUnit.DAYS));
            vectorsDAO.insert(List.of(vector(workspaceId, projectId, belowFloor, 1),
                    vector(workspaceId, projectId, aboveFloor, 1),
                    vector(workspaceId, projectId, afterCutoff, 1))).block();

            vectorsDAO.deleteForRetentionBounded(
                    Map.of(workspaceId, ID_GENERATOR.generateId(now.minus(15, ChronoUnit.DAYS))),
                    ID_GENERATOR.generateId(now.minus(10, ChronoUnit.DAYS)),
                    ID_GENERATOR.generateId(now.minus(30, ChronoUnit.DAYS))).block();

            assertThat(vectors(workspaceId, List.of(belowFloor, aboveFloor, afterCutoff)))
                    .extracting(TopicVector::traceId).containsExactlyInAnyOrder(belowFloor, afterCutoff);
        }

        private List<TopicVectorCandidate> findUnvectored(String workspaceId, Instant lower) {
            return findUnvectored(workspaceId, lower, Instant.EPOCH);
        }

        private List<TopicVectorCandidate> findUnvectored(String workspaceId, Instant lower, Instant createdAfter) {
            return vectorsDAO.findUnvectored(workspaceId, TASK, lower, Instant.now(), createdAfter, 100, 10)
                    .collectList().block();
        }
    }

    /** Answers {@code /v1/embed} with {@link #rawVector} of each input; can throttle one call or delay every call. */
    static final class EmbedStub implements ResponseTransformerV2 {

        static final String NAME = "embed-stub";

        final AtomicInteger calls = new AtomicInteger();
        private final Map<String, AtomicInteger> callsByOrg = new java.util.concurrent.ConcurrentHashMap<>();
        volatile int throttleCall;
        volatile Duration delay = Duration.ZERO;
        private volatile String failOrg;
        private volatile int failStatus;
        private volatile String failBody;

        void reset() {
            calls.set(0);
            callsByOrg.clear();
            throttleCall = 0;
            delay = Duration.ZERO;
            failOrg = null;
        }

        /** Answers every call for {@code orgId} with {@code status} and {@code body}. */
        void fail(String orgId, int status, String body) {
            failStatus = status;
            failBody = body;
            failOrg = orgId;
        }

        int callsFor(String orgId) {
            return callsByOrg.getOrDefault(orgId, new AtomicInteger()).get();
        }

        @Override
        public Response transform(Response response, ServeEvent serveEvent) {
            int call = calls.incrementAndGet();
            var request = json(serveEvent.getRequest().getBodyAsString());
            var orgId = request.path("org_id").asText();
            callsByOrg.computeIfAbsent(orgId, key -> new AtomicInteger()).incrementAndGet();
            if (!delay.isZero()) {
                Mono.delay(delay).block();
            }
            var json = new HttpHeaders(new HttpHeader("Content-Type", "application/json"));
            if (call == throttleCall) {
                return Response.Builder.like(response).but().status(429).headers(json)
                        .body("{\"error\":{\"code\":\"quota_exceeded\",\"message\":\"m\",\"retryable\":true}}")
                        .build();
            }
            if (orgId.equals(failOrg)) {
                return Response.Builder.like(response).but().status(failStatus).headers(json).body(failBody).build();
            }
            var inputs = request.path("inputs");
            var body = InferenceJson.MAPPER.createObjectNode();
            body.put("request_id", "r").put("cache_hit", false).put("latency_ms", 1);
            body.putObject("usage").put("input_tokens", 1).put("output_tokens", 0);
            var result = body.putObject("result");
            result.put("dimensions", 384);
            var embeddings = result.putArray("embeddings");
            inputs.forEach(input -> {
                var vector = embeddings.addArray();
                rawVector(input.asText()).forEach(vector::add);
            });
            return Response.Builder.like(response).but().status(200).headers(json).body(body.toString()).build();
        }

        @Override
        public String getName() {
            return NAME;
        }

        @Override
        public boolean applyGlobally() {
            return false;
        }
    }

    // --- Fixtures ---

    private TopicsConfig.VectorsConfig config() {
        return opikConfig.getTopics().getVectors();
    }

    private String enabledWorkspace() {
        var workspaceId = UUID.randomUUID().toString();
        template.inTransaction(WRITE, handle -> {
            handle.attach(AiLabelWorkspaceDAO.class).upsert(workspaceId, true, "test");
            return null;
        });
        return workspaceId;
    }

    private List<UUID> labelTraces(String workspaceId, int count, int taskVersion) {
        return labelTraces(workspaceId, UUID.randomUUID(), count, taskVersion);
    }

    /** {@code count} traces labeled as ai-labels writes them: the output rows, then the done statuses. */
    private List<UUID> labelTraces(String workspaceId, UUID projectId, int count, int taskVersion) {
        var traceIds = IntStream.range(0, count).mapToObj(i -> ID_GENERATOR.generateId()).toList();
        label(workspaceId, projectId, traceIds, taskVersion);
        return traceIds;
    }

    private void label(String workspaceId, UUID projectId, List<UUID> traceIds, int taskVersion) {
        labelsDAO.insert(traceIds.stream().map(traceId -> outputLabel(workspaceId, projectId, traceId, taskVersion))
                .toList()).block();
        statusDAO.insert(traceIds.stream().map(traceId -> doneStatus(workspaceId, projectId, traceId, taskVersion))
                .toList()).block();
    }

    private static AiLabelStatus doneStatus(String workspaceId, UUID projectId, UUID traceId, int taskVersion) {
        return AiLabelStatus.builder()
                .workspaceId(workspaceId)
                .projectId(projectId)
                .task(TASK)
                .traceId(traceId)
                .traceCreatedAt(Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS))
                .taskVersion(taskVersion)
                .rendererVersion(1)
                .status(AiLabelStatus.Status.DONE)
                .retryable(false)
                .attempt(1)
                .errorCode("")
                .jobId("job")
                .compactionLevel("none")
                .build();
    }

    private static AiLabel outputLabel(String workspaceId, UUID projectId, UUID traceId, int taskVersion) {
        var output = InferenceJson.MAPPER.createObjectNode().put("summary", summaryOf(traceId, taskVersion));
        return AiLabel.builder()
                .workspaceId(workspaceId)
                .projectId(projectId)
                .task(TASK)
                .traceId(traceId)
                .key(AiLabel.OUTPUT_KEY)
                .taskVersion(taskVersion)
                .answerType(AiLabel.AnswerType.OUTPUT)
                .probabilities(Map.of())
                .legend(Map.of())
                .output(output.toString())
                .build();
    }

    private static String summaryOf(UUID traceId) {
        return summaryOf(traceId, 1);
    }

    private static String summaryOf(UUID traceId, int taskVersion) {
        return "Intent of trace %s, version %d".formatted(traceId, taskVersion);
    }

    private static TopicVector vector(String workspaceId, UUID projectId, UUID traceId, int taskVersion) {
        var text = summaryOf(traceId, taskVersion);
        return TopicVector.builder()
                .workspaceId(workspaceId)
                .projectId(projectId)
                .traceId(traceId)
                .traceCreatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
                .taskVersion(taskVersion)
                .embedModel(TopicVectorsTickService.EMBED_MODEL)
                .embedDimensions(TopicVectorsTickService.EMBED_DIMENSIONS)
                .text(text)
                .vector(expectedVector(text))
                .build();
    }

    /** A deterministic, non-unit vector of 384 numbers per text. */
    static List<Float> rawVector(String text) {
        int seed = text.hashCode();
        return IntStream.range(0, 384)
                .mapToObj(i -> (float) (Math.floorMod(seed + i * 31, 1_000) + 1) / 100f)
                .toList();
    }

    // ClickHouse parses the JSON text of a Float32 to within one ulp, so the read-back vector may differ in the last bit.
    private static void assertVectorClose(List<Float> actual, List<Float> expected) {
        assertThat(actual).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            assertThat(actual.get(i)).isCloseTo(expected.get(i), org.assertj.core.data.Offset.offset(1e-6f));
        }
    }

    private static List<Float> expectedVector(String text) {
        return TopicVectorsTickService.normalize(rawVector(text)).orElseThrow();
    }

    private List<TopicVector> vectors(String workspaceId, java.util.Collection<UUID> traceIds) {
        return vectorsDAO.findByTraceIds(workspaceId, Set.copyOf(traceIds)).collectList().block();
    }

    private void insertStatusAt(String workspaceId, UUID projectId, UUID traceId, int taskVersion,
            Instant updatedAt) {
        execute("""
                INSERT INTO ai_label_status (workspace_id, project_id, task, trace_id, trace_created_at, task_version,
                    renderer_version, status, retryable, attempt, updated_at)
                VALUES (:workspace_id, :project_id, :task, :trace_id, now64(6), :task_version, 1, 'done', false, 1,
                    fromUnixTimestamp64Micro(:updated_at))
                """, Map.of("workspace_id", workspaceId, "project_id", projectId.toString(), "task", TASK,
                "trace_id", traceId.toString(), "task_version", taskVersion,
                "updated_at", ChronoUnit.MICROS.between(Instant.EPOCH, updatedAt)));
    }

    private void insertExperimentItem(String workspaceId, UUID traceId) {
        execute("""
                INSERT INTO experiment_items (id, experiment_id, dataset_item_id, trace_id, workspace_id)
                VALUES (:id, :experiment_id, :dataset_item_id, :trace_id, :workspace_id)
                """, Map.of("id", ID_GENERATOR.generateId().toString(),
                "experiment_id", ID_GENERATOR.generateId().toString(),
                "dataset_item_id", ID_GENERATOR.generateId().toString(),
                "trace_id", traceId.toString(), "workspace_id", workspaceId));
    }

    private void execute(String sql, Map<String, Object> parameters) {
        templateAsync.nonTransaction(connection -> {
            var statement = connection.createStatement(sql);
            parameters.forEach(statement::bind);
            return Mono.from(statement.execute()).flatMap(result -> Mono.from(result.getRowsUpdated()));
        }).block();
    }

    private List<UUID> writtenTraceIds(String workspaceId) {
        return templateAsync.nonTransaction(connection -> reactor.core.publisher.Flux
                .from(connection.createStatement("""
                        SELECT DISTINCT trace_id FROM topic_vectors WHERE workspace_id = :workspace_id
                        """)
                        .bind("workspace_id", workspaceId)
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> UUID.fromString(row.get("trace_id", String.class))))
                .collectList())
                .block();
    }

    private long countRows(String workspaceId) {
        return templateAsync.nonTransaction(connection -> Mono
                .from(connection.createStatement("""
                        SELECT count() AS cnt FROM (
                            SELECT trace_id FROM topic_vectors WHERE workspace_id = :workspace_id
                            ORDER BY workspace_id, project_id, trace_id, task_version, updated_at DESC
                            LIMIT 1 BY workspace_id, project_id, trace_id, task_version)
                        """)
                        .bind("workspace_id", workspaceId)
                        .execute())
                .flatMap(result -> Mono.from(result.map((row, metadata) -> row.get("cnt", Long.class)))))
                .block();
    }

    private Map<String, String> idAtAndPartition(String table, String workspaceId) {
        return templateAsync.nonTransaction(connection -> reactor.core.publisher.Flux
                .from(connection.createStatement("""
                        SELECT trace_id AS id, concat(toString(id_at), ' ', _partition_id) AS id_at_partition
                        FROM %s
                        WHERE workspace_id = :workspace_id
                        """.formatted(table))
                        .bind("workspace_id", workspaceId)
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> Map.entry(row.get("id", String.class),
                        row.get("id_at_partition", String.class))))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue))
                .block();
    }

    private static JsonNode json(String body) {
        try {
            return InferenceJson.MAPPER.readTree(body.getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static Path writeTasks() {
        try (var bundled = TopicVectorsTickTest.class.getClassLoader()
                .getResourceAsStream(CLASSPATH_DIRECTORY + "/trace_summary.yaml")) {
            var summary = new String(bundled.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceFirst("(?m)^applies_from: .*$", "applies_from: 2020-01-01T00:00:00Z");
            var directory = Files.createTempDirectory("topics-tasks");
            Files.writeString(directory.resolve("trace_summary.yaml"), summary);
            return directory;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
