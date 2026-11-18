package com.comet.opik.domain.topics;

import com.comet.opik.api.events.ProjectsDeleted;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.ailabels.AiLabelTaskLoader.CLASSPATH_DIRECTORY;
import static com.comet.opik.domain.topics.TopicVectorsFixtures.assertVectors;
import static com.comet.opik.domain.topics.TopicVectorsFixtures.doneStatus;
import static com.comet.opik.domain.topics.TopicVectorsFixtures.expectedVectors;
import static com.comet.opik.domain.topics.TopicVectorsFixtures.outputLabel;
import static com.comet.opik.domain.topics.TopicVectorsFixtures.rawVector;
import static com.comet.opik.domain.topics.TopicVectorsFixtures.vector;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.params.provider.Arguments.arguments;
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
        PostCutoverTraces.migrate(clickHouse);

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
    private TopicVectorsTables tables;

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
        this.tables = new TopicVectorsTables(template, templateAsync);
        var vectors = config();
        this.defaults = new TopicsConfig.VectorsConfig();
        defaults.setTickBudget(vectors.getTickBudget());
        defaults.setEmbedBatchSize(vectors.getEmbedBatchSize());
        defaults.setEmbedTimeout(vectors.getEmbedTimeout());
        defaults.setMaxTracesPerTick(vectors.getMaxTracesPerTick());
        defaults.setTickInterval(vectors.getTickInterval());
        defaults.setEmbedConcurrency(vectors.getEmbedConcurrency());

        // The job fires once at startup: wait for that tick to release the leader lock, so it can't race the tests.
        lockService.bestEffortLock(new LockService.Lock("topics:vectors:leader"), Mono.just(true), Mono.empty(),
                Duration.ofSeconds(5), Duration.ofSeconds(60)).block();
    }

    @AfterAll
    void afterAll() {
        wireMock.stop();
        clickHouse.stop();
        zookeeper.stop();
        mysql.stop();
        network.close();
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
        config().setEmbedConcurrency(defaults.getEmbedConcurrency());
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
    @DisplayName("a workspace needs both opt-ins: the topics list alone or ai-labels alone gets no vectors, every "
            + "embed call is an enabled workspace's with cache off, and taking it off either one drops it on the next "
            + "tick")
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
            assertVectors(tables.vectors(workspaceId, labeled.get(workspaceId)),
                    expectedVectors(workspaceId, projectId, labeled.get(workspaceId), 1));
        }
        for (var workspaceId : List.of(aiLabelsOnly, listedOnly, listedWithoutAiLabelsRow)) {
            assertThat(tables.vectors(workspaceId, labeled.get(workspaceId))).isEmpty();
        }
        var sent = wireMock.findAll(postRequestedFor(urlEqualTo(EMBED_PATH)));
        assertThat(sent).allSatisfy(request -> {
            var body = json(request.getBodyAsString());
            assertThat(List.of(both, other)).contains(body.path("org_id").asText());
            assertThat(body.path("cache").asBoolean(true)).isFalse();
        });

        // Taken off the list (a deploy) or off ai-labels (no deploy): the next tick drops it; its vectors stay.
        setListed(both, false);
        template.inTransaction(WRITE, handle -> {
            handle.attach(AiLabelWorkspaceDAO.class).upsert(other, false, "test");
            return null;
        });
        var later = Map.of(both, labelTraces(both, projectId, 2, 1), other, labelTraces(other, projectId, 2, 1));
        assertThat(tickService.tick(Instant.now()).written()).isZero();
        for (var workspaceId : List.of(both, other)) {
            assertThat(tables.vectors(workspaceId, later.get(workspaceId))).isEmpty();
            assertVectors(tables.vectors(workspaceId, labeled.get(workspaceId)),
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

        assertVectors(tables.vectors(workspaceId, List.of(traceId)),
                List.of(vector(workspaceId, first, traceId, 1), vector(workspaceId, second, traceId, 2)));
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
        tables.insertStatusAt(workspaceId, projectId, traceId, 1, Instant.now().minus(2, ChronoUnit.DAYS));
        // Not drained, the project would be the backfill phase's, on any tick.
        markDrained(workspaceId, projectId);

        var normal = tickService.tick(Instant.now());

        assertThat(normal.longWindowPass()).isFalse();
        assertThat(normal.written()).isZero();
        assertThat(tables.vectors(workspaceId, List.of(traceId))).isEmpty();

        Awaitility.await().atMost(20, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    var result = tickService.tick(Instant.now());
                    assertThat(result.longWindowPass()).isTrue();
                    assertThat(result.written()).isOne();
                });
        assertVectors(tables.vectors(workspaceId, List.of(traceId)),
                List.of(vector(workspaceId, projectId, traceId, 1)));
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
            traceIds.forEach(traceId -> tables.insertStatusAt(workspaceId, projectId, traceId, 1, written));
        });

        var start = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var result = tickService.tick(start);

        assertThat(result.longWindowPass()).isFalse();
        assertThat(result.written()).isEqualTo(5);
        assertThat(result.projectsDrained()).isEqualTo(2);
        assertVectors(tables.vectors(workspaceId, firstTraces), expectedVectors(workspaceId, first, firstTraces, 1));
        assertVectors(tables.vectors(workspaceId, secondTraces), expectedVectors(workspaceId, second, secondTraces, 1));
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
        assertThat(tables.vectors(workspaceId, later)).hasSize(1);
    }

    @Test
    @DisplayName("a project delete removes that project's backfill row, in its workspace only")
    void backfillRowsOfDeletedProject() {
        var workspaceId = ID_GENERATOR.generateId().toString();
        var otherWorkspaceId = ID_GENERATOR.generateId().toString();
        var deleted = ID_GENERATOR.generateId();
        var kept = ID_GENERATOR.generateId();
        var t0 = Instant.parse("2026-11-01T10:00:00Z");
        var row = TopicVectorBackfill.builder().workspaceId(workspaceId).startedAt(t0).checkedAt(t0).lastDoneAt(t0);
        backfillService.start(List.of(row.projectId(deleted).build(), row.projectId(kept).build(),
                row.workspaceId(otherWorkspaceId).projectId(deleted).build()));

        eventBus.post(new ProjectsDeleted(Set.of(deleted), workspaceId, "user"));

        Awaitility.await().atMost(15, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> assertThat(backfillService.findByKeys(workspaceId, List.of(deleted, kept)))
                        .containsOnlyKeys(kept));
        assertThat(backfillService.findByKeys(otherWorkspaceId, List.of(deleted))).containsOnlyKeys(deleted);
    }

    @Test
    @DisplayName("with two embed calls in flight, every chunk's vectors are written, each with its own trace")
    void embedConcurrency() {
        config().setEmbedBatchSize(2);
        config().setEmbedConcurrency(2);
        var workspaceId = enabledWorkspace();
        var projectId = ID_GENERATOR.generateId();
        var traceIds = labelTraces(workspaceId, projectId, 9, 1);
        // The first two calls each wait for the other: calls made one after another leave the first waiting alone.
        var bothInFlight = new CountDownLatch(2);
        var waitedAlone = new AtomicBoolean();
        embedStub.beforeAnswer = () -> {
            bothInFlight.countDown();
            try {
                if (!bothInFlight.await(5, TimeUnit.SECONDS)) {
                    waitedAlone.set(true);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        };

        TopicVectorsTickService.TickResult result;
        try {
            result = tickService.tick(Instant.now());
        } finally {
            while (bothInFlight.getCount() > 0) {
                bothInFlight.countDown();
            }
            embedStub.beforeAnswer = null;
        }

        assertThat(waitedAlone).isFalse();
        assertThat(result.written()).isEqualTo(9);
        assertThat(embedStub.callsFor(workspaceId)).isEqualTo(5);
        assertVectors(tables.vectors(workspaceId, traceIds), expectedVectors(workspaceId, projectId, traceIds, 1));
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
        assertVectors(tables.vectors(workspaceId, traceIds), expectedVectors(workspaceId, projectId, traceIds, 1));
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
        // WireMock journals a request only after the stub's delay ends, after this test. Wait for it, so the entry
        // cannot land after the next test's resetAll and show up in that test's journal.
        Awaitility.await().atMost(10, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(wireMock.findAll(postRequestedFor(urlEqualTo(EMBED_PATH))))
                        .hasSize(embedStub.calls.get()));
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
            assertThatThrownBy(() -> vectorsDAO.findUnvectored(TopicVectorsFind.builder()
                    .workspaceId(workspaceId)
                    .task(TASK)
                    .lowerBound(Instant.now().minus(1, ChronoUnit.HOURS))
                    .upperBound(Instant.now())
                    .createdAfter(Instant.EPOCH)
                    .limit(100)
                    .maxExecutionSeconds(10)
                    .build()).collectList().block())
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
        assertThat(tables.vectors(workspaceId, List.of(withoutOutput, blank))).isEmpty();
        assertVectors(tables.vectors(workspaceId, traceIds), expectedVectors(workspaceId, projectId, traceIds, 1));
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

        assertVectors(tables.vectors(workspaceId, List.of(traceId)),
                List.of(vector(workspaceId, projectId, traceId, 1), vector(workspaceId, projectId, traceId, 2)));
    }

    @Test
    @DisplayName("with a cap of 5,000, one tick writes 5,000 of a drained project's 20,000 traces, oldest status "
            + "first, four ticks write all, and a fifth neither writes nor embeds")
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
        int calls = embedStub.calls.get();
        assertThat(tickService.tick(Instant.now()).written()).isZero();
        assertThat(embedStub.calls.get()).isEqualTo(calls);
    }

    static Stream<Arguments> backOffs() {
        return Stream.of(
                arguments(429, "{\"error\":{\"code\":\"rate_limited\",\"message\":\"m\"}}"),
                // An unknown code and no retryable flag.
                arguments(503, "{\"error\":{\"code\":\"something_new\",\"message\":\"m\"}}"));
    }

    @ParameterizedTest(name = "status {0}")
    @MethodSource("backOffs")
    @DisplayName("the workspace read's cap is per workspace, and a 429, or a 5xx with an unknown code and no retryable "
            + "flag, backs off only its workspace after one call")
    void perWorkspaceCapAndBackOff(int status, String body) {
        config().setMaxTracesPerTick(4);
        config().setEmbedBatchSize(2);
        var throttled = enabledWorkspace();
        var healthy = enabledWorkspace();
        var throttledProject = ID_GENERATOR.generateId();
        var healthyProject = ID_GENERATOR.generateId();
        markDrained(throttled, throttledProject);
        markDrained(healthy, healthyProject);
        var throttledTraces = labelTraces(throttled, throttledProject, 6, 1);
        var healthyTraces = labelTraces(healthy, healthyProject, 6, 1);
        embedStub.fail(throttled, status, body);

        var result = tickService.tick(Instant.now());

        assertThat(embedStub.callsFor(throttled)).isOne();
        assertThat(result.written()).isEqualTo(4);
        assertThat(result.skipped()).isEqualTo(Map.of(TopicVectorsTickService.EMBED_FAILED, 4));
        assertVectors(tables.vectors(healthy, healthyTraces),
                expectedVectors(healthy, healthyProject, oldestFour(healthyTraces), 1));
        assertThat(tables.vectors(throttled, throttledTraces)).isEmpty();

        embedStub.reset();
        assertThat(tickService.tick(Instant.now()).written()).isEqualTo(6);
        assertVectors(tables.vectors(throttled, throttledTraces),
                expectedVectors(throttled, throttledProject, oldestFour(throttledTraces), 1));
        assertVectors(tables.vectors(healthy, healthyTraces),
                expectedVectors(healthy, healthyProject, healthyTraces, 1));
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
            tables.insertStatusAt(workspaceId, projectId, traceIds.get(i), 1,
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
        assertVectors(tables.vectors(workspaceId, traceIds), expectedVectors(workspaceId, projectId, traceIds, 1));
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
        assertVectors(tables.vectors(workspaceId, traceIds), expectedVectors(workspaceId, projectId, traceIds, 1));
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
                            && tables.vectors(workspaceId, List.of(deleted)).isEmpty());
        };

        var result = tickService.tick(Instant.now());

        assertThat(result.written()).isOne();
        assertThat(result.skipped()).isEqualTo(Map.of(TopicVectorsTickService.TRACE_DELETED, 1));
        assertVectors(tables.vectors(workspaceId, traceIds), List.of(vector(workspaceId, projectId, kept, 1)));
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
                .untilAsserted(() -> assertVectors(tables.vectors(workspaceId, Set.of(deleted, kept)),
                        List.of(vector(workspaceId, projectId, kept, 1),
                                vector(workspaceId, otherProjectId, deleted, 1))));
        assertVectors(tables.vectors(otherWorkspaceId, List.of(deleted)),
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

    /**
     * Answers {@code /v1/embed} with {@link TopicVectorsFixtures#rawVector} of each input; can throttle one call, delay every call, or run
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
        tables.project(workspaceId, projectId);
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
        tables.project(workspaceId, projectId);
        labelsDAO.insert(traceIds.stream().map(traceId -> outputLabel(workspaceId, projectId, traceId, taskVersion))
                .toList()).block();
        statusDAO.insert(traceIds.stream().map(traceId -> doneStatus(workspaceId, projectId, traceId, taskVersion))
                .toList()).block();
    }

    /**
     * The four of one {@link #labelTraces} call's traces a cap of 4 takes: its statuses are one insert, so they share
     * {@code updated_at}, and the window read's tie-break is the trace id.
     */
    private static List<UUID> oldestFour(List<UUID> traceIds) {
        return traceIds.stream().sorted(Comparator.comparing(UUID::toString)).limit(4).toList();
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
