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
import io.r2dbc.spi.Row;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
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
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.ailabels.AiLabelTaskLoader.CLASSPATH_DIRECTORY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
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
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
    // Vectors are compared whole. ClickHouse parses the JSON text of a Float32 to within one ulp, so a read-back vector
    // may differ in the last bit: its floats are compared to within 1e-6.
    private static final RecursiveComparisonConfiguration VECTORS = RecursiveComparisonConfiguration.builder()
            .withComparatorForFields(TopicVectorsTickTest::compareVectors, "vector")
            .build();

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
    private TopicWorkspaceService workspaceService;
    private TopicVectorBackfillService backfillService;

    @BeforeAll
    void beforeAll(OpikConfiguration opikConfig, TransactionTemplate template, TransactionTemplateAsync templateAsync,
            @Jit LockService lockService, GuiceJobManager jobManager, EventBus eventBus,
            @Jit TopicVectorsTickService tickService, @Jit TopicVectorsDAO vectorsDAO,
            @Jit AiLabelStatusDAO statusDAO, @Jit AiLabelsDAO labelsDAO, @Jit InferenceClient inferenceClient,
            @Jit AiLabelTaskRegistry taskRegistry, @Jit TopicWorkspaceService workspaceService,
            @Jit TopicVectorBackfillService backfillService) {
        this.backfillService = backfillService;
        this.inferenceClient = inferenceClient;
        this.workspaceService = workspaceService;
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
        template.inTransaction(WRITE, handle -> {
            handle.execute("UPDATE ai_label_workspaces SET enabled = FALSE");
            return null;
        });
        opikConfig.getTopics().setEnabledWorkspaces("");
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
        var disabled = ID_GENERATOR.generateId().toString();
        var projectId = ID_GENERATOR.generateId();
        var labeled = new java.util.LinkedHashMap<String, List<UUID>>();
        for (var workspaceId : List.of(enabled.getFirst(), enabled.getLast(), disabled)) {
            labeled.put(workspaceId, labelTraces(workspaceId, projectId, 3, 1));
        }

        var result = tickService.tick(Instant.now());

        assertThat(result.written()).isEqualTo(6);
        for (var workspaceId : enabled) {
            assertVectors(vectors(workspaceId, labeled.get(workspaceId)),
                    expectedVectors(workspaceId, projectId, labeled.get(workspaceId), 1));
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
    @DisplayName("a workspace needs both opt-ins: the topics list alone or ai-labels alone gets no vectors, and taking "
            + "it off either one drops it on the next tick")
    void topicsOptIn() {
        var both = enabledWorkspace();
        var other = enabledWorkspace();
        var aiLabelsOnly = workspaceWith(true, false);
        var listedOnly = workspaceWith(false, true);
        var listedWithoutAiLabelsRow = ID_GENERATOR.generateId().toString();
        setListed(listedWithoutAiLabelsRow, true);
        var projectId = ID_GENERATOR.generateId();
        var labeled = new java.util.LinkedHashMap<String, List<UUID>>();
        for (var workspaceId : List.of(both, other, aiLabelsOnly, listedOnly, listedWithoutAiLabelsRow)) {
            labeled.put(workspaceId, labelTraces(workspaceId, projectId, 2, 1));
        }

        assertThat(workspaceService.eligibleWorkspaceIds())
                .containsExactlyElementsOf(List.of(both, other).stream().sorted().toList());

        assertThat(tickService.tick(Instant.now()).written()).isEqualTo(4);

        for (var workspaceId : List.of(both, other)) {
            assertVectors(vectors(workspaceId, labeled.get(workspaceId)),
                    expectedVectors(workspaceId, projectId, labeled.get(workspaceId), 1));
        }
        for (var workspaceId : List.of(aiLabelsOnly, listedOnly, listedWithoutAiLabelsRow)) {
            assertThat(vectors(workspaceId, labeled.get(workspaceId))).isEmpty();
        }

        // Taken off the list (a deploy) or off ai-labels (no deploy): the next tick drops it; its vectors stay.
        setListed(both, false);
        template.inTransaction(WRITE, handle -> {
            handle.attach(AiLabelWorkspaceDAO.class).upsert(other, false, "test");
            return null;
        });
        var later = Map.of(both, labelTraces(both, projectId, 2, 1), other, labelTraces(other, projectId, 2, 1));
        assertThat(tickService.tick(Instant.now()).written()).isZero();
        for (var workspaceId : List.of(both, other)) {
            assertThat(vectors(workspaceId, later.get(workspaceId))).isEmpty();
            assertVectors(vectors(workspaceId, labeled.get(workspaceId)),
                    expectedVectors(workspaceId, projectId, labeled.get(workspaceId), 1));
        }
        assertThat(workspaceService.eligibleWorkspaceIds()).isEmpty();
    }

    @Test
    @DisplayName("a trace id reused in two projects gets each project's own summary")
    void traceIdReusedInTwoProjects() {
        var workspaceId = enabledWorkspace();
        var first = ID_GENERATOR.generateId();
        var second = ID_GENERATOR.generateId();
        var traceId = labelTraces(workspaceId, first, 1, 1).getFirst();
        // The same trace id in another project, with another summary (the text depends on the task version).
        label(workspaceId, second, List.of(traceId), 2);

        assertThat(tickService.tick(Instant.now()).written()).isEqualTo(2);

        assertVectors(vectors(workspaceId, List.of(traceId)),
                List.of(vector(workspaceId, first, traceId, 1), vector(workspaceId, second, traceId, 2)));
    }

    @Test
    @DisplayName("a second tick writes nothing new")
    void idempotent() {
        var workspaceId = enabledWorkspace();
        var projectId = ID_GENERATOR.generateId();
        var traceIds = labelTraces(workspaceId, projectId, 5, 1);
        assertThat(tickService.tick(Instant.now()).written()).isEqualTo(5);
        int calls = embedStub.calls.get();

        var second = tickService.tick(Instant.now());

        assertThat(second.written()).isZero();
        assertThat(embedStub.calls.get()).isEqualTo(calls);
        assertThat(countRows(workspaceId)).isEqualTo(5);
        assertVectors(vectors(workspaceId, traceIds), expectedVectors(workspaceId, projectId, traceIds, 1));
    }

    @Test
    @DisplayName("a drained project's status older than the short window is found only by the tick on which the daily "
            + "long-window pass is due")
    void longWindowPass() {
        // Wait for a tick that runs the pass, so the next one certainly doesn't.
        makeLongWindowPassDue();
        Awaitility.await().atMost(20, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(500))
                .until(() -> tickService.tick(Instant.now()).longWindowPass());
        var workspaceId = enabledWorkspace();
        var traceId = ID_GENERATOR.generateId();
        var projectId = ID_GENERATOR.generateId();
        labelsDAO.insert(List.of(outputLabel(workspaceId, projectId, traceId, 1))).block();
        insertStatusAt(workspaceId, projectId, traceId, 1, Instant.now().minus(2, ChronoUnit.DAYS));
        // Not drained, the project would be the backfill phase's, on any tick.
        markDrained(workspaceId, projectId);

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
        assertVectors(vectors(workspaceId, List.of(traceId)), List.of(vector(workspaceId, projectId, traceId, 1)));
    }

    @Test
    @DisplayName("a workspace's old traces are embedded project by project in one tick, and each project is marked "
            + "drained with its counts")
    void backfillByProject() {
        var workspaceId = enabledWorkspace();
        var first = ID_GENERATOR.generateId();
        var second = ID_GENERATOR.generateId();
        var firstTraces = IntStream.range(0, 3).mapToObj(i -> ID_GENERATOR.generateId()).toList();
        var secondTraces = IntStream.range(0, 2).mapToObj(i -> ID_GENERATOR.generateId()).toList();
        var written = Instant.now().minus(2, ChronoUnit.DAYS);
        Map.of(first, firstTraces, second, secondTraces).forEach((projectId, traceIds) -> {
            labelsDAO.insert(traceIds.stream().map(traceId -> outputLabel(workspaceId, projectId, traceId, 1))
                    .toList()).block();
            traceIds.forEach(traceId -> insertStatusAt(workspaceId, projectId, traceId, 1, written));
        });

        var start = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var result = tickService.tick(start);

        assertThat(result.longWindowPass()).isFalse();
        assertThat(result.written()).isEqualTo(5);
        assertThat(result.projectsDrained()).isEqualTo(2);
        assertVectors(vectors(workspaceId, firstTraces), expectedVectors(workspaceId, first, firstTraces, 1));
        assertVectors(vectors(workspaceId, secondTraces), expectedVectors(workspaceId, second, secondTraces, 1));
        var rows = backfillService.findByKeys(workspaceId, List.of(first, second));
        assertThat(rows.get(first)).usingRecursiveComparison().ignoringFields("lastDoneAt")
                .isEqualTo(TopicVectorBackfill.builder().workspaceId(workspaceId).projectId(first).startedAt(start)
                        .checkedAt(start).drainedAt(start).lastDoneAt(start).tracesTotal(3).tracesDone(3).build());
        assertThat(rows.get(second).drainedAt()).isEqualTo(start);
        assertThat(rows.get(second).tracesTotal()).isEqualTo(2);
        assertThat(rows.get(second).tracesDone()).isEqualTo(2);
        assertThat(rows.get(first).lastDoneAt()).isCloseTo(written, within(1, ChronoUnit.SECONDS));
        assertThat(backfillService.findUndrained(List.of(workspaceId))).isEmpty();

        // Drained: a new trace of the project is the next tick's workspace read's.
        var later = labelTraces(workspaceId, first, 1, 1);
        var next = tickService.tick(Instant.now());
        assertThat(next.written()).isOne();
        assertThat(next.projectsDrained()).isZero();
        assertThat(vectors(workspaceId, later)).hasSize(1);
    }

    @Test
    @DisplayName("the backfill table: a started row is not started again, a discovery keeps a row's start and drained "
            + "mark, progress never passes the total, and rows not checked for long start again")
    void backfillTable() {
        var workspaceId = ID_GENERATOR.generateId().toString();
        var first = ID_GENERATOR.generateId();
        var second = ID_GENERATOR.generateId();
        var third = ID_GENERATOR.generateId();
        var t0 = Instant.parse("2026-11-01T10:00:00.123456Z");
        var row = TopicVectorBackfill.builder().workspaceId(workspaceId).startedAt(t0).checkedAt(t0).lastDoneAt(t0);
        backfillService.start(List.of(row.projectId(first).build(), row.projectId(second).build()));
        backfillService.start(List.of(row.projectId(first).startedAt(t0.plusSeconds(60)).build()));
        var t1 = t0.plusSeconds(100);
        backfillService.record(List.of(TopicVectorBackfill.Progress.builder().workspaceId(workspaceId)
                .projectId(first).drainedAt(t1).written(5).build()));

        var started = backfillService.findByKeys(workspaceId, List.of(first, second, third));
        // Never discovered, so its total is 0 and nothing written counts yet.
        assertThat(started).isEqualTo(Map.of(
                first, row.projectId(first).startedAt(t0).drainedAt(t1).build(),
                second, row.projectId(second).startedAt(t0).drainedAt(null).build()));
        assertThat(backfillService.findUndrained(List.of(workspaceId)))
                .isEqualTo(Map.of(workspaceId, List.of(started.get(second))));

        var t2 = t0.plusSeconds(200);
        var t3 = t0.plusSeconds(300);
        var found = TopicVectorBackfill.builder().workspaceId(workspaceId).startedAt(t3).checkedAt(t3);
        backfillService.discovered(workspaceId, List.of(
                found.projectId(first).lastDoneAt(t2).tracesTotal(10).tracesDone(4).build(),
                found.projectId(third).lastDoneAt(t2).tracesTotal(7).tracesDone(0).build()), t3, t0.minusSeconds(1));
        var progress = TopicVectorBackfill.Progress.builder().workspaceId(workspaceId).projectId(third).written(5)
                .build();
        backfillService.record(List.of(progress, progress));

        assertThat(backfillService.findByKeys(workspaceId, List.of(first, second, third))).isEqualTo(Map.of(
                // Its start and drained mark are kept; the counts and the latest status are the discovery's.
                first, found.projectId(first).startedAt(t0).drainedAt(t1).lastDoneAt(t2).tracesTotal(10)
                        .tracesDone(4).build(),
                // Not found by the discovery, but checked with its workspace.
                second, row.projectId(second).startedAt(t0).checkedAt(t3).drainedAt(null).build(),
                // New: starts a backfill. 5 + 5 written of 7.
                third, found.projectId(third).startedAt(t3).drainedAt(null).lastDoneAt(t2).tracesTotal(7)
                        .tracesDone(7).build()));

        // The tick comes back long after t3: every row starts again.
        var t4 = t3.plus(3, ChronoUnit.DAYS);
        backfillService.discovered(workspaceId, List.of(), t4, t4.minus(2, ChronoUnit.DAYS));

        var all = template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicVectorBackfillDAO.class).findByWorkspaceIds(List.of(workspaceId)));
        assertThat(all).hasSize(3).allSatisfy(reset -> {
            assertThat(reset.startedAt()).isEqualTo(t4);
            assertThat(reset.checkedAt()).isEqualTo(t4);
            assertThat(reset.drainedAt()).isNull();
            assertThat(reset.tracesTotal()).isZero();
            assertThat(reset.tracesDone()).isZero();
        });
    }

    @Test
    @DisplayName("a 429 on the second chunk: the first chunk is written, the next tick completes the second")
    void throttledSecondChunk() {
        config().setEmbedBatchSize(10);
        var workspaceId = enabledWorkspace();
        var projectId = ID_GENERATOR.generateId();
        var traceIds = labelTraces(workspaceId, projectId, 15, 1);
        embedStub.throttleCall = 2;

        var first = tickService.tick(Instant.now());

        assertThat(first.written()).isEqualTo(10);
        assertThat(first.skipped()).isEqualTo(Map.of(TopicVectorsTickService.EMBED_FAILED, 5));
        assertThat(countRows(workspaceId)).isEqualTo(10);

        var second = tickService.tick(Instant.now());

        assertThat(second.written()).isEqualTo(5);
        assertVectors(vectors(workspaceId, traceIds), expectedVectors(workspaceId, projectId, traceIds, 1));
    }

    @Test
    @DisplayName("an embed timeout writes nothing, escapes nothing, and is counted in the embed failures metric")
    void embedTimeout() {
        config().setEmbedTimeout(io.dropwizard.util.Duration.milliseconds(500));
        var workspaceId = enabledWorkspace();
        labelTraces(workspaceId, 4, 1);
        embedStub.delay = Duration.ofSeconds(3);
        var meter = new TopicVectorsTickServiceTest.TestMeter();
        var metered = new TopicVectorsTickService(opikConfig.getTopics(), opikConfig.getAiLabels(), workspaceService,
                vectorsDAO, backfillService, inferenceClient, lockService, taskRegistry,
                TopicVectorsTickServiceTest.names(),
                meter.meter, Clock.systemUTC());

        var result = metered.tick(Instant.now());

        assertThat(result.written()).isZero();
        assertThat(result.skipped()).isEqualTo(Map.of(TopicVectorsTickService.EMBED_FAILED, 4));
        assertThat(countRows(workspaceId)).isZero();
        var attributes = ArgumentCaptor.forClass(Attributes.class);
        verify(meter.counter("topics.vectors.embed.failures")).add(eq(1L), attributes.capture());
        assertThat(attributes.getValue().get(stringKey("code"))).isIn("upstream_error", "out_of_time");
        assertThat(attributes.getValue().get(stringKey("workspace_name"))).isEqualTo("name-" + workspaceId);
        verify(meter.counter("topics.vectors.skipped"))
                .add(4, TopicVectorsTickServiceTest.workspace(workspaceId)
                        .put(stringKey("reason"), TopicVectorsTickService.EMBED_FAILED).build());
        verifyNoInteractions(meter.counter("topics.vectors.written"));
    }

    @Test
    @DisplayName("the find guards reach the discovery and the window read: max_rows_to_read 1 trips both, counted as "
            + "a discover and a find read failure; the default reads them")
    void findGuardsReachTheWindowRead() {
        var workspaceId = enabledWorkspace();
        labelTraces(workspaceId, 3, 1);
        var meter = new TopicVectorsTickServiceTest.TestMeter();
        var metered = new TopicVectorsTickService(opikConfig.getTopics(), opikConfig.getAiLabels(), workspaceService,
                vectorsDAO, backfillService, inferenceClient, lockService, taskRegistry,
                TopicVectorsTickServiceTest.names(),
                meter.meter, Clock.systemUTC());
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
        verify(meter.counter("topics.vectors.read.failures"), times(2)).add(eq(1L), attributes.capture());
        assertThat(attributes.getAllValues()).extracting(values -> values.get(stringKey("phase")))
                .containsExactly("discover", "find");
        assertThat(attributes.getValue().get(stringKey("workspace_id"))).isEqualTo(workspaceId);
        assertThat(attributes.getValue().get(stringKey("workspace_name"))).isEqualTo("name-" + workspaceId);
        assertThat(attributes.getValue().get(stringKey("error_type"))).isNotBlank();

        assertThat(metered.tick(Instant.now()).written()).isEqualTo(3);
    }

    @Test
    @DisplayName("done statuses without an output row or with a blank summary are not found or counted; the rest are "
            + "written")
    void unusableSummaries() {
        var workspaceId = enabledWorkspace();
        var projectId = ID_GENERATOR.generateId();
        var withoutOutput = ID_GENERATOR.generateId();
        statusDAO.insert(List.of(doneStatus(workspaceId, projectId, withoutOutput, 1))).block();
        var blank = ID_GENERATOR.generateId();
        labelsDAO.insert(List.of(outputLabel(workspaceId, projectId, blank, 1, " \t\n"))).block();
        statusDAO.insert(List.of(doneStatus(workspaceId, projectId, blank, 1))).block();
        var traceIds = labelTraces(workspaceId, projectId, 2, 1);

        var result = tickService.tick(Instant.now());

        assertThat(result.written()).isEqualTo(2);
        assertThat(result.skipped()).isEmpty();
        assertThat(vectors(workspaceId, List.of(withoutOutput, blank))).isEmpty();
        assertVectors(vectors(workspaceId, traceIds), expectedVectors(workspaceId, projectId, traceIds, 1));
    }

    @Test
    @DisplayName("two task versions of one trace give two rows, both kept")
    void twoTaskVersions() {
        var workspaceId = enabledWorkspace();
        var projectId = ID_GENERATOR.generateId();
        var traceId = ID_GENERATOR.generateId();
        labelsDAO.insert(List.of(outputLabel(workspaceId, projectId, traceId, 1))).block();
        statusDAO.insert(List.of(doneStatus(workspaceId, projectId, traceId, 1))).block();
        assertThat(tickService.tick(Instant.now()).written()).isOne();

        // A relabel: the new version's label, then its status, as ai-labels writes them.
        labelsDAO.insert(List.of(outputLabel(workspaceId, projectId, traceId, 2))).block();
        statusDAO.insert(List.of(doneStatus(workspaceId, projectId, traceId, 2))).block();
        assertThat(tickService.tick(Instant.now()).written()).isOne();

        assertVectors(vectors(workspaceId, List.of(traceId)),
                List.of(vector(workspaceId, projectId, traceId, 1), vector(workspaceId, projectId, traceId, 2)));
    }

    @Test
    @DisplayName("with a cap of 5,000, one tick writes 5,000 of a drained project's 20,000 traces, oldest status "
            + "first, and four ticks write all")
    void cap() {
        var workspaceId = enabledWorkspace();
        var projectId = ID_GENERATOR.generateId();
        // A drained project: the workspace read serves it, one cap per tick. In backfill it would take turn after turn.
        markDrained(workspaceId, projectId);
        // Four batches of 5,000, labeled in this order. Each batch's ids are generated after the next one's, so trace id
        // order is the reverse of status order: the ticks can only follow status order if they sort by updated_at.
        var batches = new ArrayList<List<UUID>>();
        for (int i = 0; i < 4; i++) {
            batches.addFirst(IntStream.range(0, 5_000).mapToObj(j -> ID_GENERATOR.generateId()).toList());
        }
        for (var batch : batches) {
            label(workspaceId, projectId, batch, 1);
        }

        // Trace ids, not whole vectors: a read of 20,000 trace ids would pass ClickHouse's max_query_size.
        var written = new HashSet<UUID>();
        for (var batch : batches) {
            assertThat(tickService.tick(Instant.now()).written()).isEqualTo(5_000);
            written.addAll(batch);
            assertThat(Set.copyOf(writtenTraceIds(workspaceId))).isEqualTo(written);
        }

        assertThat(countRows(workspaceId)).isEqualTo(20_000);
        assertThat(tickService.tick(Instant.now()).written()).isZero();
    }

    @Test
    @DisplayName("the workspace read's cap is per workspace, and a 429 with an unknown code backs off only its "
            + "workspace")
    void perWorkspaceCapAndBackOff() {
        config().setMaxTracesPerTick(4);
        config().setEmbedBatchSize(2);
        var throttled = enabledWorkspace();
        var healthy = enabledWorkspace();
        var projectId = ID_GENERATOR.generateId();
        markDrained(throttled, projectId);
        markDrained(healthy, projectId);
        var throttledTraces = labelTraces(throttled, projectId, 6, 1);
        var healthyTraces = labelTraces(healthy, projectId, 6, 1);
        embedStub.fail(throttled, 429, "{\"error\":{\"code\":\"rate_limited\",\"message\":\"m\"}}");

        var result = tickService.tick(Instant.now());

        assertThat(embedStub.callsFor(throttled)).isOne();
        assertThat(result.written()).isEqualTo(4);
        assertThat(result.skipped()).isEqualTo(Map.of(TopicVectorsTickService.EMBED_FAILED, 4));
        assertVectors(vectors(healthy, healthyTraces),
                expectedVectors(healthy, projectId, oldestFour(healthyTraces), 1));
        assertThat(vectors(throttled, throttledTraces)).isEmpty();

        embedStub.reset();
        assertThat(tickService.tick(Instant.now()).written()).isEqualTo(6);
        assertVectors(vectors(throttled, throttledTraces),
                expectedVectors(throttled, projectId, oldestFour(throttledTraces), 1));
        assertVectors(vectors(healthy, healthyTraces), expectedVectors(healthy, projectId, healthyTraces, 1));
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
    @DisplayName("the daily long-window pass stays on while its ticks reach the cap, and stops once one finds fewer")
    void longWindowUntilDrained() {
        // Drain any pass in progress, and wait for a pass to be due, so the next tick is a long one.
        makeLongWindowPassDue();
        Awaitility.await().atMost(20, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(500))
                .until(() -> tickService.tick(Instant.now()).longWindowPass());
        config().setMaxTracesPerTick(2);
        var workspaceId = enabledWorkspace();
        var projectId = ID_GENERATOR.generateId();
        var traceIds = IntStream.range(0, 5).mapToObj(i -> ID_GENERATOR.generateId()).toList();
        labelsDAO.insert(traceIds.stream().map(traceId -> outputLabel(workspaceId, projectId, traceId, 1)).toList())
                .block();
        for (int i = 0; i < traceIds.size(); i++) {
            insertStatusAt(workspaceId, projectId, traceIds.get(i), 1,
                    Instant.now().minus(2, ChronoUnit.DAYS).plusSeconds(i));
        }
        // A drained project: its old statuses are the daily pass's, not the backfill phase's.
        markDrained(workspaceId, projectId);
        Awaitility.await().atMost(20, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(500))
                .until(() -> !tickService.tick(Instant.now()).longWindowPass());

        // An undrained pass is due again after one normal tick (1.5 tick intervals): wait for each pass tick.
        config().setTickInterval(io.dropwizard.util.Duration.seconds(1));
        var ticks = new ArrayList<TopicVectorsTickService.TickResult>();
        for (int pass = 0; pass < 3; pass++) {
            var holder = new java.util.concurrent.atomic.AtomicReference<TopicVectorsTickService.TickResult>();
            Awaitility.await().atMost(20, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(200))
                    .until(() -> {
                        var result = tickService.tick(Instant.now());
                        holder.set(result);
                        return result.longWindowPass();
                    });
            ticks.add(holder.get());
        }
        // Drained: not due again for longWindowInterval.
        ticks.add(tickService.tick(Instant.now()));

        assertThat(ticks).containsExactly(
                new TopicVectorsTickService.TickResult(2, Map.of(), true),
                new TopicVectorsTickService.TickResult(2, Map.of(), true),
                new TopicVectorsTickService.TickResult(1, Map.of(), true),
                new TopicVectorsTickService.TickResult(0, Map.of(), false));
        assertVectors(vectors(workspaceId, traceIds), expectedVectors(workspaceId, projectId, traceIds, 1));
    }

    @Test
    @DisplayName("a tick that runs out of budget starts no chunk with less than the embed timeout left, counts the rest "
            + "as over_budget, and the next tick continues")
    void budget() {
        config().setEmbedBatchSize(10);
        config().setTickBudget(io.dropwizard.util.Duration.seconds(2));
        config().setEmbedTimeout(io.dropwizard.util.Duration.milliseconds(500));
        var workspaceId = enabledWorkspace();
        var projectId = ID_GENERATOR.generateId();
        var traceIds = labelTraces(workspaceId, projectId, 100, 1);
        embedStub.delay = Duration.ofMillis(300);

        var first = tickService.tick(Instant.now());

        assertThat(first.written()).isPositive().isLessThan(100);
        assertThat(first.skipped()).isEqualTo(Map.of(TopicVectorsTickService.OVER_BUDGET, 100 - first.written()));
        assertThat(embedStub.calls.get() * 10).isEqualTo(first.written());

        embedStub.delay = Duration.ZERO;
        config().setTickBudget(defaults.getTickBudget());
        var second = tickService.tick(Instant.now());

        assertThat(first.written() + second.written()).isEqualTo(100);
        assertVectors(vectors(workspaceId, traceIds), expectedVectors(workspaceId, projectId, traceIds, 1));
    }

    @Test
    @DisplayName("a trace deleted while its summary is embedded keeps no vector: the cascade's delete has run before "
            + "the insert, so the tick deletes what it wrote and counts it as trace_deleted")
    void traceDeletedDuringEmbed() {
        // Room for the whole cascade inside the embed call.
        config().setEmbedTimeout(io.dropwizard.util.Duration.seconds(20));
        var workspaceId = enabledWorkspace();
        var projectId = ID_GENERATOR.generateId();
        var traceIds = labelTraces(workspaceId, projectId, 2, 1);
        var deleted = traceIds.getFirst();
        var kept = traceIds.getLast();
        // Another version's vector of the trace: once it is gone, the cascade's vector delete has run.
        vectorsDAO.insert(List.of(vector(workspaceId, projectId, deleted, 2))).block();
        // After the summary read, before the insert: the trace is deleted and its cascade runs to the end.
        embedStub.beforeAnswer = () -> {
            eventBus.post(new TracesDeleted(Set.of(deleted), projectId, workspaceId, "user"));
            Awaitility.await().atMost(15, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(300))
                    .until(() -> statusRows(workspaceId, deleted) == 0
                            && vectors(workspaceId, List.of(deleted)).isEmpty());
        };

        var result = tickService.tick(Instant.now());

        assertThat(result.written()).isOne();
        assertThat(result.skipped()).isEqualTo(Map.of(TopicVectorsTickService.TRACE_DELETED, 1));
        assertVectors(vectors(workspaceId, traceIds), List.of(vector(workspaceId, projectId, kept, 1)));
    }

    @Nested
    @DisplayName("storage")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Storage {

        @Test
        @DisplayName("the window read finds a done status without a vector, and nothing once the vector is written")
        void windowReadAndAntiJoin() {
            var workspaceId = ID_GENERATOR.generateId().toString();
            var projectId = ID_GENERATOR.generateId();
            var traceId = ID_GENERATOR.generateId();
            var failedTraceId = ID_GENERATOR.generateId();
            labelsDAO.insert(List.of(outputLabel(workspaceId, projectId, traceId, 3),
                    outputLabel(workspaceId, projectId, failedTraceId, 3))).block();
            statusDAO.insert(List.of(doneStatus(workspaceId, projectId, traceId, 3))).block();
            // Done, then failed: its latest row is not done.
            statusDAO.insert(List.of(doneStatus(workspaceId, projectId, failedTraceId, 3))).block();
            statusDAO.insert(List.of(doneStatus(workspaceId, projectId, failedTraceId, 3).toBuilder()
                    .status(AiLabelStatus.Status.FAILED).build())).block();
            var lower = Instant.now().minus(1, ChronoUnit.HOURS);

            var found = findUnvectored(workspaceId, lower);

            assertCandidates(found, candidate(workspaceId, projectId, traceId, 3));

            vectorsDAO.insert(List.of(vector(workspaceId, projectId, traceId, 3))).block();

            assertThat(findUnvectored(workspaceId, lower)).isEmpty();
            // Another version of the same trace still needs its own vector.
            labelsDAO.insert(List.of(outputLabel(workspaceId, projectId, traceId, 4))).block();
            statusDAO.insert(List.of(doneStatus(workspaceId, projectId, traceId, 4))).block();
            assertCandidates(findUnvectored(workspaceId, lower), candidate(workspaceId, projectId, traceId, 4));
        }

        @Test
        @DisplayName("a vector whose latest row is a tombstone does not count: the window read finds its trace again, "
                + "and a newer live row hides it once more")
        void tombstonedVector() {
            var workspaceId = ID_GENERATOR.generateId().toString();
            var projectId = ID_GENERATOR.generateId();
            var traceId = ID_GENERATOR.generateId();
            labelsDAO.insert(List.of(outputLabel(workspaceId, projectId, traceId, 3))).block();
            statusDAO.insert(List.of(doneStatus(workspaceId, projectId, traceId, 3))).block();
            vectorsDAO.insert(List.of(vector(workspaceId, projectId, traceId, 3))).block();
            var lower = Instant.now().minus(1, ChronoUnit.HOURS);
            assertThat(findUnvectored(workspaceId, lower)).isEmpty();

            insertTombstone(workspaceId, traceId, 3);

            assertCandidates(findUnvectored(workspaceId, lower), candidate(workspaceId, projectId, traceId, 3));
            assertThat(vectors(workspaceId, List.of(traceId))).isEmpty();

            vectorsDAO.insert(List.of(vector(workspaceId, projectId, traceId, 3))).block();

            assertThat(findUnvectored(workspaceId, lower)).isEmpty();
            assertThat(vectors(workspaceId, List.of(traceId))).hasSize(1);
        }

        @Test
        @DisplayName("the trace_created_at bound drops older traces from both sides alike, so the anti-join stays exact")
        void createdAtBound() {
            var workspaceId = ID_GENERATOR.generateId().toString();
            var projectId = ID_GENERATOR.generateId();
            var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            var createdAfter = now.minus(9, ChronoUnit.DAYS);
            var inside = ID_GENERATOR.generateId();
            var insideCreatedAt = createdAfter.plus(1, ChronoUnit.HOURS);
            var outside = ID_GENERATOR.generateId();
            var outsideCreatedAt = createdAfter.minus(1, ChronoUnit.HOURS);
            labelsDAO.insert(List.of(outputLabel(workspaceId, projectId, inside, 1),
                    outputLabel(workspaceId, projectId, outside, 1))).block();
            statusDAO.insert(List.of(
                    doneStatus(workspaceId, projectId, inside, 1).toBuilder().traceCreatedAt(insideCreatedAt).build(),
                    doneStatus(workspaceId, projectId, outside, 1).toBuilder().traceCreatedAt(outsideCreatedAt)
                            .build()))
                    .block();
            var lower = now.minus(1, ChronoUnit.HOURS);
            var insideCandidate = candidate(workspaceId, projectId, inside, 1).toBuilder()
                    .traceCreatedAt(insideCreatedAt).build();
            var outsideCandidate = candidate(workspaceId, projectId, outside, 1).toBuilder()
                    .traceCreatedAt(outsideCreatedAt).build();

            assertCandidates(findUnvectored(workspaceId, lower, createdAfter), insideCandidate);
            // Without the bound both are found: the bound is what leaves the older one out.
            assertCandidates(findUnvectored(workspaceId, lower, Instant.EPOCH), insideCandidate, outsideCandidate);

            vectorsDAO.insert(List.of(vector(workspaceId, projectId, inside, 1).toBuilder()
                    .traceCreatedAt(insideCreatedAt).build())).block();

            assertThat(findUnvectored(workspaceId, lower, createdAfter)).isEmpty();
            // A status rewritten long after its vector, of a trace still inside the bound, stays dropped.
            statusDAO.insert(List.of(doneStatus(workspaceId, projectId, inside, 1).toBuilder()
                    .traceCreatedAt(insideCreatedAt).build())).block();
            assertThat(findUnvectored(workspaceId, lower, createdAfter)).isEmpty();
        }

        @Test
        @DisplayName("the summary read starts at the second of the oldest done trace id: that trace, whose id time has a "
                + "millisecond part, and a trace of a later week are both found")
        void idAtBoundOfTheSummaryRead() {
            var workspaceId = ID_GENERATOR.generateId().toString();
            var projectId = ID_GENERATOR.generateId();
            // id_at cuts the id's time to the second; a bound at 999 ms past it would leave this trace's row out.
            var oldest = ID_GENERATOR.generateId(Instant.now().minus(10, ChronoUnit.DAYS)
                    .truncatedTo(ChronoUnit.SECONDS).plusMillis(999));
            var newest = ID_GENERATOR.generateId();
            labelsDAO.insert(List.of(outputLabel(workspaceId, projectId, oldest, 1),
                    outputLabel(workspaceId, projectId, newest, 1))).block();
            statusDAO.insert(List.of(doneStatus(workspaceId, projectId, oldest, 1),
                    doneStatus(workspaceId, projectId, newest, 1))).block();

            assertCandidates(findUnvectored(workspaceId, Instant.now().minus(1, ChronoUnit.HOURS)),
                    candidate(workspaceId, projectId, oldest, 1), candidate(workspaceId, projectId, newest, 1));
        }

        @Test
        @DisplayName("statuses without a usable summary don't take the LIMIT: an older blank, Unicode-blank, missing "
                + "or other-version output leaves room for a newer usable one")
        void unusableSummariesBeforeLimit() {
            var workspaceId = ID_GENERATOR.generateId().toString();
            var projectId = ID_GENERATOR.generateId();
            var blank = ID_GENERATOR.generateId();
            var missing = ID_GENERATOR.generateId();
            var otherVersion = ID_GENERATOR.generateId();
            var unicodeBlank = ID_GENERATOR.generateId();
            var usable = ID_GENERATOR.generateId();
            labelsDAO.insert(List.of(outputLabel(workspaceId, projectId, blank, 1, "   "),
                    outputLabel(workspaceId, projectId, unicodeBlank, 1, "\u2003\u3000\u2028\u00A0\u2007\u202F\u0085"),
                    outputLabel(workspaceId, projectId, otherVersion, 2),
                    outputLabel(workspaceId, projectId, usable, 1))).block();
            var oldest = Instant.now().minus(30, ChronoUnit.MINUTES);
            insertStatusAt(workspaceId, projectId, blank, 1, oldest);
            insertStatusAt(workspaceId, projectId, missing, 1, oldest.plusSeconds(1));
            insertStatusAt(workspaceId, projectId, otherVersion, 1, oldest.plusSeconds(2));
            insertStatusAt(workspaceId, projectId, unicodeBlank, 1, oldest.plusSeconds(3));
            insertStatusAt(workspaceId, projectId, usable, 1, oldest.plusSeconds(4));

            var found = vectorsDAO.findUnvectored(workspaceId, TASK, Instant.now().minus(1, ChronoUnit.HOURS),
                    Instant.now(), Instant.EPOCH, 1, 10).collectList().block();

            // Written by insertStatusAt, so its updated_at is known too.
            assertThat(found).containsExactly(candidate(workspaceId, projectId, usable, 1).toBuilder()
                    .statusUpdatedAt(oldest.plusSeconds(4).truncatedTo(ChronoUnit.MICROS)).build());
        }

        @Test
        @DisplayName("vectors read back as written, and id_at and the partition equal ai_label_status's")
        void roundTripAndPartition() {
            var workspaceId = ID_GENERATOR.generateId().toString();
            var projectId = ID_GENERATOR.generateId();
            var monday = Instant.parse("2025-03-03T00:00:00Z");
            var traceIds = List.of(ID_GENERATOR.generateId(), ID_GENERATOR.generateId(monday),
                    ID_GENERATOR.generateId(monday.minusMillis(1)));
            var rows = traceIds.stream().map(traceId -> vector(workspaceId, projectId, traceId, 1)).toList();
            vectorsDAO.insert(rows).block();
            statusDAO.insert(traceIds.stream().map(traceId -> doneStatus(workspaceId, projectId, traceId, 1))
                    .toList()).block();

            assertVectors(vectors(workspaceId, traceIds), rows);
            assertThat(idAtAndPartition(TopicVectorsDAO.TABLE, workspaceId))
                    .isEqualTo(idAtAndPartition("ai_label_status", workspaceId));
        }

        @Test
        @DisplayName("the trace-delete event deletes the traces' vectors and leaves other traces, projects and workspaces")
        void traceDeleteCascade() {
            var workspaceId = ID_GENERATOR.generateId().toString();
            var otherWorkspaceId = ID_GENERATOR.generateId().toString();
            var projectId = ID_GENERATOR.generateId();
            var otherProjectId = ID_GENERATOR.generateId();
            var deleted = ID_GENERATOR.generateId();
            var kept = ID_GENERATOR.generateId();
            vectorsDAO.insert(List.of(vector(workspaceId, projectId, deleted, 1),
                    vector(workspaceId, projectId, kept, 1),
                    vector(workspaceId, otherProjectId, deleted, 1),
                    vector(otherWorkspaceId, projectId, deleted, 1))).block();

            eventBus.post(new TracesDeleted(Set.of(deleted), projectId, workspaceId, "user"));

            // The same trace id under another project of the workspace keeps its vector.
            Awaitility.await().atMost(30, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(300))
                    .untilAsserted(() -> assertVectors(vectors(workspaceId, Set.of(deleted, kept)),
                            List.of(vector(workspaceId, projectId, kept, 1),
                                    vector(workspaceId, otherProjectId, deleted, 1))));
            assertVectors(vectors(otherWorkspaceId, List.of(deleted)),
                    List.of(vector(otherWorkspaceId, projectId, deleted, 1)));
            // Scoped to the trace's partition, so the mutation does not visit every part.
            assertThat(partitionScopedDeletes(workspaceId)).isPositive();
        }

        private long partitionScopedDeletes(String workspaceId) {
            return templateAsync.nonTransaction(connection -> Mono
                    .from(connection.createStatement("SYSTEM FLUSH LOGS").execute())
                    .flatMap(result -> Mono.from(result.getRowsUpdated()))
                    .then(Mono.from(connection.createStatement("""
                            SELECT count() AS cnt
                            FROM system.query_log
                            WHERE type = 'QueryFinish'
                            AND log_comment LIKE concat('topics:delete_vectors_by_trace_ids:', :workspace_id, ':%')
                            AND query LIKE '%IN PARTITION%'
                            """)
                            .bind("workspace_id", workspaceId)
                            .execute()))
                    .flatMap(result -> Mono.from(result.map((row, metadata) -> row.get("cnt", Long.class)))))
                    .block();
        }

        @Test
        @DisplayName("retention deletes vectors in range, and keeps experiment-linked, newer and other workspaces' traces")
        void retention() {
            var workspaceId = ID_GENERATOR.generateId().toString();
            var otherWorkspaceId = ID_GENERATOR.generateId().toString();
            var projectId = ID_GENERATOR.generateId();
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

            assertVectors(vectors(workspaceId, List.of(old, oldInExperiment, recent)),
                    expectedVectors(workspaceId, projectId, List.of(oldInExperiment, recent), 1));
            assertVectors(vectors(otherWorkspaceId, List.of(old)),
                    List.of(vector(otherWorkspaceId, projectId, old, 1)));
        }

        @Test
        @DisplayName("bounded retention deletes from each workspace's own floor up to the cutoff")
        void boundedRetention() {
            var workspaceId = ID_GENERATOR.generateId().toString();
            var projectId = ID_GENERATOR.generateId();
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

            assertVectors(vectors(workspaceId, List.of(belowFloor, aboveFloor, afterCutoff)),
                    expectedVectors(workspaceId, projectId, List.of(belowFloor, afterCutoff), 1));
        }

        /**
         * Whole candidates, in any order, but {@code statusUpdatedAt}: ClickHouse stamps {@code updated_at} when
         * {@link AiLabelStatusDAO#insert} writes the status.
         */
        @Test
        @DisplayName("the window read in slices of trace creation time, per project or without some projects, finds "
                + "exactly what one read of the window finds: a trace on a slice edge, an old trace with a late "
                + "status and a trace that has its vector included")
        void slicedReadEqualsUnsliced() {
            var workspaceId = ID_GENERATOR.generateId().toString();
            var projects = List.of(ID_GENERATOR.generateId(), ID_GENERATOR.generateId());
            var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            var slice = Duration.ofDays(1);
            var createdAfter = now.minus(10, ChronoUnit.DAYS);
            // Every status is written now, so a trace created days ago has a late status.
            var createdAts = List.of(
                    now.minus(12, ChronoUnit.HOURS),
                    // Exactly on the edge of the first two slices, and a microsecond on its older side.
                    now.minus(slice),
                    now.minus(slice).minus(1, ChronoUnit.MICROS),
                    now.minus(5, ChronoUnit.DAYS),
                    now.minus(5, ChronoUnit.DAYS).plusSeconds(1),
                    // Exactly on the window's bound, which is inclusive, and just outside it.
                    createdAfter,
                    createdAfter.minus(1, ChronoUnit.MICROS));
            var created = new java.util.HashMap<UUID, Instant>();
            for (var projectId : projects) {
                var statuses = new ArrayList<AiLabelStatus>();
                for (var createdAt : createdAts) {
                    var traceId = ID_GENERATOR.generateId();
                    created.put(traceId, createdAt);
                    statuses.add(doneStatus(workspaceId, projectId, traceId, 1).toBuilder().traceCreatedAt(createdAt)
                            .build());
                }
                labelsDAO.insert(statuses.stream()
                        .map(status -> outputLabel(workspaceId, projectId, status.traceId(), 1)).toList()).block();
                statusDAO.insert(statuses).block();
                // One trace of the project, five days old, already has its vector.
                var vectored = statuses.get(3);
                vectorsDAO.insert(List.of(vector(workspaceId, projectId, vectored.traceId(), 1).toBuilder()
                        .traceCreatedAt(vectored.traceCreatedAt()).build())).block();
                created.remove(vectored.traceId());
            }
            var lower = now.minus(1, ChronoUnit.HOURS);
            var upper = Instant.now();

            var unsliced = vectorsDAO.findUnvectored(workspaceId, TASK, lower, upper, createdAfter, 100, 10)
                    .collectList().block();

            // Per project: 7 traces, less the one with a vector and the one before the bound.
            assertThat(unsliced).hasSize(10);
            assertThat(unsliced).extracting(TopicVectorCandidate::traceId).doesNotHaveDuplicates()
                    .allSatisfy(traceId -> assertThat(created.get(traceId)).isAfterOrEqualTo(createdAfter));
            var byKey = Comparator.comparing(TopicVectorCandidate::projectId)
                    .thenComparing(TopicVectorCandidate::traceId);
            var expected = unsliced.stream().sorted(byKey).toList();

            assertThat(
                    sliced(workspaceId, null, List.of(), lower, upper, now, createdAfter, slice).stream().sorted(byKey))
                    .containsExactlyElementsOf(expected);
            // Each project on its own, in slices and in one read.
            for (var projectId : projects) {
                var ofProject = expected.stream().filter(candidate -> candidate.projectId().equals(projectId))
                        .toList();
                assertThat(sliced(workspaceId, projectId, List.of(), lower, upper, now, createdAfter, slice).stream()
                        .sorted(byKey)).containsExactlyElementsOf(ofProject);
                assertThat(vectorsDAO.findUnvectored(workspaceId, TASK, lower, upper, createdAfter, null, projectId,
                        List.of(), 100, 10).collectList().block().stream().sorted(byKey))
                        .containsExactlyElementsOf(ofProject);
            }
            // Without the second project: the first project's.
            assertThat(sliced(workspaceId, null, List.of(projects.get(1)), lower, upper, now, createdAfter, slice)
                    .stream().sorted(byKey))
                    .containsExactlyElementsOf(expected.stream()
                            .filter(candidate -> candidate.projectId().equals(projects.get(0))).toList());
        }

        @Test
        @DisplayName("the discovery finds each project's done status rows of the window and its latest one, and the "
                + "vector count its vector rows; a failed status and a tombstoned vector are not counted")
        void discoveryAggregates() {
            var workspaceId = ID_GENERATOR.generateId().toString();
            var first = ID_GENERATOR.generateId();
            var second = ID_GENERATOR.generateId();
            var firstTraces = IntStream.range(0, 3).mapToObj(i -> ID_GENERATOR.generateId()).toList();
            var secondTraces = IntStream.range(0, 2).mapToObj(i -> ID_GENERATOR.generateId()).toList();
            var before = Instant.now().minusSeconds(1);
            statusDAO.insert(firstTraces.stream().map(traceId -> doneStatus(workspaceId, first, traceId, 1)).toList())
                    .block();
            statusDAO.insert(List.of(
                    doneStatus(workspaceId, second, secondTraces.get(0), 1),
                    doneStatus(workspaceId, second, secondTraces.get(1), 1).toBuilder()
                            .status(AiLabelStatus.Status.FAILED).build()))
                    .block();
            vectorsDAO.insert(List.of(vector(workspaceId, first, firstTraces.get(0), 1),
                    vector(workspaceId, first, firstTraces.get(1), 1),
                    vector(workspaceId, second, secondTraces.get(0), 1))).block();
            insertTombstone(workspaceId, secondTraces.get(0), 1);
            var lower = Instant.now().minus(2, ChronoUnit.HOURS);

            var found = vectorsDAO.findBackfillProjects(workspaceId, TASK, lower, lower.minus(8, ChronoUnit.DAYS), 10)
                    .collectList().block();
            var vectors = vectorsDAO.countVectorsByProject(workspaceId, lower, 10).block();

            assertThat(found).extracting(TopicBackfillProject::projectId, TopicBackfillProject::traces)
                    .containsExactlyInAnyOrder(tuple(first, 3L), tuple(second, 1L));
            assertThat(found).allSatisfy(project -> assertThat(project.lastDoneAt()).isBetween(before,
                    Instant.now().plusSeconds(1)));
            assertThat(vectors.get(first)).isEqualTo(2);
            // The tombstone row is not counted; the row it replaces still is, until ClickHouse merges them.
            assertThat(vectors.get(second)).isEqualTo(1);
            // A window that starts after the traces were created counts none of them, but still finds the projects.
            assertThat(vectorsDAO.findBackfillProjects(workspaceId, TASK, before, lower.minus(8, ChronoUnit.DAYS), 10)
                    .collectList().block())
                    .extracting(TopicBackfillProject::projectId, TopicBackfillProject::traces)
                    .containsExactlyInAnyOrder(tuple(first, 0L), tuple(second, 0L));
        }

        /** The window read as the tick walks it: slices counted back from {@code start}, the newest open-ended. */
        private List<TopicVectorCandidate> sliced(String workspaceId, UUID projectId, List<UUID> excluded,
                Instant lower, Instant upper, Instant start, Instant createdAfter, Duration slice) {
            var candidates = new ArrayList<TopicVectorCandidate>();
            Instant createdBefore = null;
            while (true) {
                var sliceStart = (createdBefore == null ? start : createdBefore).minus(slice);
                var from = sliceStart.isAfter(createdAfter) ? sliceStart : createdAfter;
                candidates.addAll(vectorsDAO.findUnvectored(workspaceId, TASK, lower, upper, from, createdBefore,
                        projectId, excluded, 100, 10).collectList().block());
                if (!from.isAfter(createdAfter)) {
                    return candidates;
                }
                createdBefore = from;
            }
        }

        private void assertCandidates(List<TopicVectorCandidate> actual, TopicVectorCandidate... expected) {
            assertThat(actual).usingRecursiveFieldByFieldElementComparatorIgnoringFields("statusUpdatedAt")
                    .containsExactlyInAnyOrder(expected);
        }

        /** The candidate of a status {@link #doneStatus} built; {@code statusUpdatedAt} is not known. */
        private TopicVectorCandidate candidate(String workspaceId, UUID projectId, UUID traceId, int taskVersion) {
            return TopicVectorCandidate.builder()
                    .workspaceId(workspaceId)
                    .projectId(projectId)
                    .traceId(traceId)
                    .traceCreatedAt(traceCreatedAt(traceId))
                    .taskVersion(taskVersion)
                    .statusUpdatedAt(Instant.EPOCH)
                    .build();
        }

        private List<TopicVectorCandidate> findUnvectored(String workspaceId, Instant lower) {
            return findUnvectored(workspaceId, lower, Instant.EPOCH);
        }

        private List<TopicVectorCandidate> findUnvectored(String workspaceId, Instant lower, Instant createdAfter) {
            return vectorsDAO.findUnvectored(workspaceId, TASK, lower, Instant.now(), createdAfter, 100, 10)
                    .collectList().block();
        }
    }

    /**
     * Answers {@code /v1/embed} with {@link #rawVector} of each input; can throttle one call, delay every call, or run
     * a hook before it answers.
     */
    static final class EmbedStub implements ResponseTransformerV2 {

        static final String NAME = "embed-stub";

        final AtomicInteger calls = new AtomicInteger();
        private final Map<String, AtomicInteger> callsByOrg = new java.util.concurrent.ConcurrentHashMap<>();
        volatile int throttleCall;
        volatile Duration delay = Duration.ZERO;
        // Runs before every answer, on WireMock's thread, while the tick waits for the call.
        volatile Runnable beforeAnswer;
        private volatile String failOrg;
        private volatile int failStatus;
        private volatile String failBody;

        void reset() {
            calls.set(0);
            callsByOrg.clear();
            throttleCall = 0;
            delay = Duration.ZERO;
            beforeAnswer = null;
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
            var hook = beforeAnswer;
            if (hook != null) {
                hook.run();
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

    /**
     * An earlier test whose single tick was a long-window pass that didn't drain (a back-off, a cap) holds the pass off
     * for 1.5 tick intervals, which is 1.5 h here: drop the lock, so the next tick runs the pass.
     */
    private void makeLongWindowPassDue() {
        lockService.unlockUsingToken(TopicVectorsTickService.LONG_WINDOW_LOCK).block();
    }

    private String enabledWorkspace() {
        return workspaceWith(true, true);
    }

    /** Gives the project a drained backfill row, as a project whose backfill ended earlier has. */
    private void markDrained(String workspaceId, UUID projectId) {
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        backfillService.start(List.of(TopicVectorBackfill.builder().workspaceId(workspaceId).projectId(projectId)
                .startedAt(now).checkedAt(now).lastDoneAt(now).build()));
        backfillService.record(List.of(TopicVectorBackfill.Progress.builder().workspaceId(workspaceId)
                .projectId(projectId).drainedAt(now).build()));
    }

    /** Enabled in ai_label_workspaces or not, and listed in topics.enabledWorkspaces or not. */
    private String workspaceWith(boolean aiLabels, boolean topics) {
        var workspaceId = ID_GENERATOR.generateId().toString();
        template.inTransaction(WRITE, handle -> {
            handle.attach(AiLabelWorkspaceDAO.class).upsert(workspaceId, aiLabels, "test");
            return null;
        });
        if (topics) {
            setListed(workspaceId, true);
        }
        return workspaceId;
    }

    /** Adds the workspace to, or removes it from, topics.enabledWorkspaces, as a Helm change would. */
    private void setListed(String workspaceId, boolean listed) {
        var topics = opikConfig.getTopics();
        var ids = new java.util.TreeSet<>(topics.enabledWorkspaceIds());
        if (listed) {
            ids.add(workspaceId);
        } else {
            ids.remove(workspaceId);
        }
        topics.setEnabledWorkspaces(String.join(",", ids));
    }

    private List<UUID> labelTraces(String workspaceId, int count, int taskVersion) {
        return labelTraces(workspaceId, ID_GENERATOR.generateId(), count, taskVersion);
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
                .traceCreatedAt(traceCreatedAt(traceId))
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
        return outputLabel(workspaceId, projectId, traceId, taskVersion, summaryOf(traceId, taskVersion));
    }

    private static AiLabel outputLabel(String workspaceId, UUID projectId, UUID traceId, int taskVersion,
            String summary) {
        var output = InferenceJson.MAPPER.createObjectNode().put("summary", summary);
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

    private static String summaryOf(UUID traceId, int taskVersion) {
        return "Intent of trace %s, version %d".formatted(traceId, taskVersion);
    }

    /**
     * The trace's created_at in its statuses: an hour before its id's time, so it is neither the id's time nor the
     * status's write time, and the same on every row of the trace.
     */
    private static Instant traceCreatedAt(UUID traceId) {
        return Instant.ofEpochMilli(traceId.getMostSignificantBits() >>> 16).minus(1, ChronoUnit.HOURS);
    }

    /** The row the tick writes for a trace labeled by {@link #label}: its summary, embedded and scaled to length 1. */
    private static TopicVector vector(String workspaceId, UUID projectId, UUID traceId, int taskVersion) {
        var text = summaryOf(traceId, taskVersion);
        return TopicVector.builder()
                .workspaceId(workspaceId)
                .projectId(projectId)
                .traceId(traceId)
                .traceCreatedAt(traceCreatedAt(traceId))
                .taskVersion(taskVersion)
                .embedModel("bge-small-en-v1.5")
                .embedDimensions(384)
                .text(text)
                .vector(expectedVector(text))
                .build();
    }

    private static List<TopicVector> expectedVectors(String workspaceId, UUID projectId, List<UUID> traceIds,
            int taskVersion) {
        return traceIds.stream().map(traceId -> vector(workspaceId, projectId, traceId, taskVersion)).toList();
    }

    /**
     * The four of one {@link #labelTraces} call's traces a cap of 4 takes: its statuses are one insert, so they share
     * {@code updated_at}, and the window read's tie-break is the trace id.
     */
    private static List<UUID> oldestFour(List<UUID> traceIds) {
        return traceIds.stream().sorted(Comparator.comparing(UUID::toString)).limit(4).toList();
    }

    /** A deterministic, non-unit vector of 384 numbers per text. */
    static List<Float> rawVector(String text) {
        int seed = text.hashCode();
        return IntStream.range(0, 384)
                .mapToObj(i -> (float) (Math.floorMod(seed + i * 31, 1_000) + 1) / 100f)
                .toList();
    }

    /** Whole vectors, in any order; the floats of {@code vector} as {@link #VECTORS} compares them. */
    private static void assertVectors(List<TopicVector> actual, List<TopicVector> expected) {
        assertThat(actual).usingRecursiveFieldByFieldElementComparator(VECTORS)
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    /** 0 when both vectors have the same length and their floats agree to within 1e-6, else 1. */
    @SuppressWarnings("unchecked")
    private static int compareVectors(Object actual, Object expected) {
        var left = (List<Float>) actual;
        var right = (List<Float>) expected;
        if (left.size() != right.size()) {
            return 1;
        }
        for (int i = 0; i < right.size(); i++) {
            if (Math.abs(left.get(i) - right.get(i)) > 1e-6f) {
                return 1;
            }
        }
        return 0;
    }

    private static List<Float> expectedVector(String text) {
        return TopicVectorsTickService.normalize(rawVector(text)).orElseThrow();
    }

    /** The workspace's vectors of {@code traceIds}: the latest row of each (project, trace, task version), unless deleted. */
    private List<TopicVector> vectors(String workspaceId, Collection<UUID> traceIds) {
        return templateAsync.nonTransaction(connection -> reactor.core.publisher.Flux
                .from(connection.createStatement("""
                        SELECT workspace_id, project_id, trace_id, trace_created_at, task_version, embed_model,
                            embed_dimensions, text, vector
                        FROM (
                            SELECT *
                            FROM topic_vectors
                            WHERE workspace_id = :workspace_id
                            AND trace_id IN :trace_ids
                            ORDER BY workspace_id, project_id, trace_id, task_version, last_updated_at DESC
                            LIMIT 1 BY workspace_id, project_id, trace_id, task_version
                        )
                        WHERE is_deleted = 0
                        """)
                        .bind("workspace_id", workspaceId)
                        .bind("trace_ids", traceIds.stream().map(UUID::toString).toArray(String[]::new))
                        .execute())
                .flatMap(result -> result.map((row, metadata) -> toVector(row)))
                .collectList())
                .block();
    }

    private static TopicVector toVector(Row row) {
        // The driver's Java array type for Array(Float32) isn't part of its contract, so read the elements as numbers.
        var array = row.get("vector", Object.class);
        var vector = new ArrayList<Float>(Array.getLength(array));
        for (int i = 0; i < Array.getLength(array); i++) {
            vector.add(((Number) Array.get(array, i)).floatValue());
        }
        return TopicVector.builder()
                .workspaceId(row.get("workspace_id", String.class))
                .projectId(UUID.fromString(row.get("project_id", String.class)))
                .traceId(UUID.fromString(row.get("trace_id", String.class)))
                .traceCreatedAt(row.get("trace_created_at", Instant.class))
                .taskVersion(row.get("task_version", Long.class).intValue())
                .embedModel(row.get("embed_model", String.class))
                .embedDimensions(row.get("embed_dimensions", Integer.class))
                .text(row.get("text", String.class))
                .vector(vector)
                .build();
    }

    private long statusRows(String workspaceId, UUID traceId) {
        return templateAsync.nonTransaction(connection -> Mono
                .from(connection.createStatement("""
                        SELECT count() AS cnt FROM ai_label_status
                        WHERE workspace_id = :workspace_id AND trace_id = :trace_id
                        """)
                        .bind("workspace_id", workspaceId)
                        .bind("trace_id", traceId.toString())
                        .execute())
                .flatMap(result -> Mono.from(result.map((row, metadata) -> row.get("cnt", Long.class)))))
                .block();
    }

    private void insertStatusAt(String workspaceId, UUID projectId, UUID traceId, int taskVersion,
            Instant updatedAt) {
        execute("""
                INSERT INTO ai_label_status (workspace_id, project_id, task, trace_id, trace_created_at, task_version,
                    renderer_version, status, retryable, attempt, updated_at)
                VALUES (:workspace_id, :project_id, :task, :trace_id, fromUnixTimestamp64Micro(:trace_created_at),
                    :task_version, 1, 'done', false, 1, fromUnixTimestamp64Micro(:updated_at))
                """, Map.of("workspace_id", workspaceId, "project_id", projectId.toString(), "task", TASK,
                "trace_id", traceId.toString(), "task_version", taskVersion,
                "trace_created_at", ChronoUnit.MICROS.between(Instant.EPOCH, traceCreatedAt(traceId)),
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

    /** A copy of the key's row with {@code is_deleted = 1}; ClickHouse stamps a later {@code last_updated_at}. */
    private void insertTombstone(String workspaceId, UUID traceId, int taskVersion) {
        execute("""
                INSERT INTO topic_vectors (workspace_id, project_id, trace_id, trace_created_at, task_version,
                    embed_model, embed_dimensions, text, vector, is_deleted)
                SELECT workspace_id, project_id, trace_id, trace_created_at, task_version, embed_model,
                    embed_dimensions, text, vector, 1
                FROM topic_vectors
                WHERE workspace_id = :workspace_id AND trace_id = :trace_id AND task_version = :task_version
                LIMIT 1
                """, Map.of("workspace_id", workspaceId, "trace_id", traceId.toString(), "task_version", taskVersion));
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
                            SELECT trace_id, is_deleted FROM topic_vectors WHERE workspace_id = :workspace_id
                            ORDER BY workspace_id, project_id, trace_id, task_version, last_updated_at DESC
                            LIMIT 1 BY workspace_id, project_id, trace_id, task_version)
                        WHERE is_deleted = 0
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
