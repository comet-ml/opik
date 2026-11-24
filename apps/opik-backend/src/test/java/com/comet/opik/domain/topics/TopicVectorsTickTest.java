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
import com.comet.opik.domain.ailabels.AiLabelWorkspaceDAO;
import com.comet.opik.domain.ailabels.AiLabelsDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
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
import org.quartz.JobKey;
import org.quartz.SchedulerException;
import org.quartz.SimpleTrigger;
import org.quartz.impl.matchers.GroupMatcher;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.dropwizard.guice.test.jupiter.param.Jit;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * The topic vectors tick against real ClickHouse, MySQL and Redis, with opik-inference's {@code /v1/embed} stubbed in
 * WireMock by {@link EmbedStub}, which answers every input with a deterministic vector of its text. Tests seed
 * {@code ai_label_status} and {@code ai_labels} directly and call {@link TopicVectorsTickService#tick(Instant)}. A
 * project with no drained backfill row has its traces embedded by the backfill, in the same tick. The scheduled job
 * fires once at startup and then only every hour.
 */
@DisplayName("topic vectors tick")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TopicVectorsTickTest {

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();
    private static final String EMBED_PATH = "/v1/embed";

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
                        new CustomConfig("inference.url", wireMock.baseUrl()),
                        new CustomConfig("inference.credential", "test-credential"),
                        new CustomConfig("aiLabels.tasksDir", writeTasks().toString()),
                        new CustomConfig("aiLabels.tickInterval", "1h"),
                        new CustomConfig("aiLabels.healthInterval", "1h"),
                        new CustomConfig("topics.enabled", "true"),
                        new CustomConfig("topics.vectors.tickInterval", "1h"),
                        new CustomConfig("topics.vectors.tickBudget", "30s")))
                .build());
    }

    private OpikConfiguration opikConfig;
    private TransactionTemplate template;
    private TransactionTemplateAsync templateAsync;
    private TopicVectorsTickService tickService;
    private TopicVectorsDAO vectorsDAO;
    private AiLabelStatusDAO statusDAO;
    private AiLabelsDAO labelsDAO;
    private TopicWorkspaceService workspaceService;
    private TopicVectorsTables tables;
    private LockService lockService;
    private TopicVectorBackfillService backfillService;
    private TopicsConfig.VectorsConfig defaults;
    private GuiceJobManager jobManager;
    private EventBus eventBus;

    @BeforeAll
    void beforeAll(OpikConfiguration opikConfig, TransactionTemplate template, TransactionTemplateAsync templateAsync,
            @Jit TopicVectorsTickService tickService, @Jit TopicVectorsDAO vectorsDAO,
            @Jit AiLabelStatusDAO statusDAO, @Jit AiLabelsDAO labelsDAO,
            @Jit TopicWorkspaceService workspaceService, @Jit LockService lockService,
            @Jit TopicVectorBackfillService backfillService, GuiceJobManager jobManager, EventBus eventBus) {
        this.opikConfig = opikConfig;
        this.template = template;
        this.templateAsync = templateAsync;
        this.tickService = tickService;
        this.vectorsDAO = vectorsDAO;
        this.statusDAO = statusDAO;
        this.labelsDAO = labelsDAO;
        this.workspaceService = workspaceService;
        this.tables = new TopicVectorsTables(template, templateAsync);
        this.lockService = lockService;
        this.backfillService = backfillService;
        this.jobManager = jobManager;
        this.eventBus = eventBus;
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
        // Every tick a short one unless a test makes the long-window pass due: the first tick of a run would run it.
        lockService.lockUsingToken(LongWindowPass.LOCK, Duration.ofHours(1)).block();
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
        var labeled = new LinkedHashMap<String, List<UUID>>();
        for (var workspaceId : List.of(both, other, aiLabelsOnly, listedOnly, listedWithoutAiLabelsRow)) {
            labeled.put(workspaceId, labelTraces(workspaceId, projectId, 2, 1));
        }

        assertThat(workspaceService.enabledWorkspaceIds()).containsExactlyInAnyOrder(both, other);

        assertThat(tick()).isEqualTo(TickResult.written(4));

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
        assertThat(tick()).isEqualTo(TickResult.EMPTY);
        for (var workspaceId : List.of(both, other)) {
            assertThat(tables.vectors(workspaceId, later.get(workspaceId))).isEmpty();
            assertVectors(tables.vectors(workspaceId, labeled.get(workspaceId)),
                    expectedVectors(workspaceId, projectId, labeled.get(workspaceId), 1));
        }
        assertThat(workspaceService.enabledWorkspaceIds()).isEmpty();
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

        assertThat(tick()).isEqualTo(TickResult.written(2));

        assertVectors(tables.vectors(workspaceId, List.of(traceId)),
                List.of(vector(workspaceId, first, traceId, 1), vector(workspaceId, second, traceId, 2)));
    }

    @Test
    @DisplayName("a drained project's status older than the short window is found only by the tick on which the daily "
            + "long-window pass is due")
    void longWindowPass() {
        var workspaceId = enabledWorkspace();
        var traceId = ID_GENERATOR.generateId();
        var projectId = ID_GENERATOR.generateId();
        // Not drained, the project would be the backfill's, on any tick.
        markDrained(workspaceId, projectId);
        labelsDAO.insert(List.of(outputLabel(workspaceId, projectId, traceId, 1))).block();
        tables.insertStatusAt(workspaceId, projectId, traceId, 1, Instant.now().minus(2, ChronoUnit.DAYS));

        assertThat(tick()).isEqualTo(TickResult.EMPTY);
        assertThat(tables.vectors(workspaceId, List.of(traceId))).isEmpty();

        makeLongWindowPassDue();
        assertThat(tick()).isEqualTo(TickResult.builder().written(1).skipped(Map.of()).longWindowPass(true).build());
        assertVectors(tables.vectors(workspaceId, List.of(traceId)),
                List.of(vector(workspaceId, projectId, traceId, 1)));
    }

    @Test
    @DisplayName("the daily long-window pass stays on while its ticks reach the cap, and stops once one finds fewer")
    void longWindowUntilDrained() {
        config().setMaxTracesPerTick(2);
        var workspaceId = enabledWorkspace();
        var projectId = ID_GENERATOR.generateId();
        // A drained project: its old statuses are the long-window pass's, not the backfill's.
        markDrained(workspaceId, projectId);
        var traceIds = IntStream.range(0, 5).mapToObj(i -> ID_GENERATOR.generateId()).toList();
        labelsDAO.insert(traceIds.stream().map(traceId -> outputLabel(workspaceId, projectId, traceId, 1)).toList())
                .block();
        for (int i = 0; i < traceIds.size(); i++) {
            tables.insertStatusAt(workspaceId, projectId, traceIds.get(i), 1,
                    Instant.now().minus(2, ChronoUnit.DAYS).plusSeconds(i));
        }
        // An undrained pass is due again after one short tick (1.5 tick intervals): wait for each pass tick.
        config().setTickInterval(io.dropwizard.util.Duration.seconds(1));
        makeLongWindowPassDue();
        var ticks = new ArrayList<TickResult>();
        for (int pass = 0; pass < 3; pass++) {
            var result = new AtomicReference<TickResult>();
            Awaitility.await().atMost(20, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(200))
                    .until(() -> {
                        result.set(tick());
                        return result.get().longWindowPass();
                    });
            ticks.add(result.get());
        }
        // Drained: not due again for longWindowInterval.
        ticks.add(tick());

        var passTick = TickResult.builder().skipped(Map.of()).longWindowPass(true);
        assertThat(ticks).containsExactly(passTick.written(2).build(), passTick.written(2).build(),
                passTick.written(1).build(), TickResult.EMPTY);
        assertVectors(tables.vectors(workspaceId, traceIds), expectedVectors(workspaceId, projectId, traceIds, 1));
    }

    @Test
    @DisplayName("a long-window pass that takes two ticks reads each workspace's long window once: the second tick "
            + "reads only the workspace the first left at the cap, and drains the pass")
    void longWindowAcrossTicks() {
        config().setMaxTracesPerTick(2);
        var capped = enabledWorkspace();
        var cappedProject = ID_GENERATOR.generateId();
        var read = enabledWorkspace();
        var readProject = ID_GENERATOR.generateId();
        // Drained projects: their old statuses are the long-window pass's, not the backfill's.
        markDrained(capped, cappedProject);
        markDrained(read, readProject);
        var cappedTraces = oldStatuses(capped, cappedProject, 3);
        var readTraces = oldStatuses(read, readProject, 1);
        // A short tick first: the pass's progress is kept on the watermark rows a fresh read creates.
        assertThat(tick()).isEqualTo(TickResult.EMPTY);

        makeLongWindowPassDue();
        var passTick = TickResult.builder().skipped(Map.of()).longWindowPass(true);
        assertThat(tick()).isEqualTo(passTick.written(3).build());

        // Found only by a long read of the workspace the first tick already read in full.
        var lateTraces = oldStatuses(read, readProject, 1);
        makeLongWindowPassDue();
        assertThat(tick()).isEqualTo(passTick.written(1).build());
        // Drained: not due again for longWindowInterval.
        assertThat(tick()).isEqualTo(TickResult.EMPTY);

        assertVectors(tables.vectors(capped, cappedTraces), expectedVectors(capped, cappedProject, cappedTraces, 1));
        assertVectors(tables.vectors(read, readTraces), expectedVectors(read, readProject, readTraces, 1));
        assertThat(tables.vectors(read, lateTraces)).isEmpty();
    }

    /** {@code count} traces with output rows and done statuses written two days ago, older than the short window. */
    private List<UUID> oldStatuses(String workspaceId, UUID projectId, int count) {
        var traceIds = IntStream.range(0, count).mapToObj(i -> ID_GENERATOR.generateId()).toList();
        labelsDAO.insert(traceIds.stream().map(traceId -> outputLabel(workspaceId, projectId, traceId, 1)).toList())
                .block();
        for (int i = 0; i < count; i++) {
            tables.insertStatusAt(workspaceId, projectId, traceIds.get(i), 1,
                    Instant.now().minus(2, ChronoUnit.DAYS).plusSeconds(i));
        }
        return traceIds;
    }

    @Test
    @DisplayName("a workspace's old traces are embedded project by project in one tick, and each project is marked "
            + "drained with its counts; a new trace of a drained project is the next tick's workspace read's")
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
        assertThat(tickService.tick(start).block()).isEqualTo(TickResult.written(5));

        assertVectors(tables.vectors(workspaceId, firstTraces), expectedVectors(workspaceId, first, firstTraces, 1));
        assertVectors(tables.vectors(workspaceId, secondTraces), expectedVectors(workspaceId, second, secondTraces, 1));
        var rows = backfillService.findByKeys(workspaceId, List.of(first, second));
        assertThat(rows.get(first)).usingRecursiveComparison().ignoringFields("backfillLastDoneAt")
                .isEqualTo(TopicBackfill.builder().workspaceId(workspaceId).projectId(first)
                        .backfillStartedAt(start).backfillDrainedAt(start).backfillTracesTotal(3)
                        .backfillTracesDone(3).backfillLastDoneAt(written).build());
        assertThat(rows.get(first).backfillLastDoneAt()).isCloseTo(written, within(1, ChronoUnit.SECONDS));
        assertThat(rows.get(second)).usingRecursiveComparison().ignoringFields("backfillLastDoneAt")
                .isEqualTo(TopicBackfill.builder().workspaceId(workspaceId).projectId(second)
                        .backfillStartedAt(start).backfillDrainedAt(start).backfillTracesTotal(2)
                        .backfillTracesDone(2).backfillLastDoneAt(written).build());
        assertThat(backfillService.findUndrained(List.of(workspaceId))).isEmpty();

        var later = labelTraces(workspaceId, first, 1, 1);
        assertThat(tick()).isEqualTo(TickResult.written(1));
        assertVectors(tables.vectors(workspaceId, later), expectedVectors(workspaceId, first, later, 1));
    }

    @Test
    @DisplayName("with embedConcurrency 2, two workspaces are embedded at the same time, and every chunk's vectors are "
            + "written")
    void workspacesInParallel() {
        config().setEmbedConcurrency(2);
        var workspaceIds = List.of(enabledWorkspace(), enabledWorkspace());
        var projectId = ID_GENERATOR.generateId();
        var labeled = new LinkedHashMap<String, List<UUID>>();
        workspaceIds.forEach(workspaceId -> labeled.put(workspaceId, labelTraces(workspaceId, projectId, 3, 1)));
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

        TickResult result;
        try {
            result = tick();
        } finally {
            while (bothInFlight.getCount() > 0) {
                bothInFlight.countDown();
            }
            embedStub.beforeAnswer = null;
        }

        assertThat(waitedAlone).isFalse();
        assertThat(result).isEqualTo(TickResult.written(6));
        labeled.forEach((workspaceId, traceIds) -> assertVectors(tables.vectors(workspaceId, traceIds),
                expectedVectors(workspaceId, projectId, traceIds, 1)));
    }

    @Test
    @DisplayName("a 429 on the second chunk: the first chunk is written and the watermark stays behind the second, "
            + "which the next tick completes, moving the watermark to its start")
    void throttledSecondChunk() {
        config().setEmbedBatchSize(10);
        var workspaceId = enabledWorkspace();
        var projectId = ID_GENERATOR.generateId();
        // Drained, so its traces are the workspace read's, which keeps the watermark.
        markDrained(workspaceId, projectId);
        var traceIds = labelTraces(workspaceId, projectId, 15, 1);
        embedStub.throttleCall = 2;

        // MySQL keeps microseconds.
        var first = Instant.now().truncatedTo(ChronoUnit.MICROS);
        assertThat(tickService.tick(first).block())
                .isEqualTo(new TickResult(10, Map.of(SkipReason.EMBED_FAILED, 5)));
        assertThat(countRows(workspaceId)).isEqualTo(10);
        assertThat(watermark(workspaceId)).isBefore(first);

        var second = Instant.now().truncatedTo(ChronoUnit.MICROS);
        assertThat(tickService.tick(second).block()).isEqualTo(TickResult.written(5));
        assertVectors(tables.vectors(workspaceId, traceIds), expectedVectors(workspaceId, projectId, traceIds, 1));
        assertThat(watermark(workspaceId)).isEqualTo(second);
    }

    @Test
    @DisplayName("a workspace read the budget cuts short after the turn's own budget check reads nothing and leaves "
            + "the watermark as it is, so the next tick still finds a status older than the overlap")
    void budgetEndsBeforeTheRead() {
        // The turn starts with at least 1s + embedTimeout (3s) left; the 6s name lookup leaves the read under that.
        config().setTickBudget(io.dropwizard.util.Duration.seconds(8));
        config().setEmbedTimeout(io.dropwizard.util.Duration.seconds(2));
        var workspaceId = enabledWorkspace();
        var projectId = ID_GENERATOR.generateId();
        // Drained, so its traces are the workspace read's, which keeps the watermark.
        markDrained(workspaceId, projectId);
        var traceId = ID_GENERATOR.generateId();
        labelsDAO.insert(List.of(outputLabel(workspaceId, projectId, traceId, 1))).block();
        // Inside the short window, older than the overlap before the next tick's start.
        tables.insertStatusAt(workspaceId, projectId, traceId, 1, Instant.now().minus(1, ChronoUnit.HOURS));
        wireMock.stubFor(get(urlPathEqualTo("/workspaces/workspace-name"))
                .willReturn(aResponse().withStatus(200).withBody("slow-name").withFixedDelay(6_000)));

        assertThat(tick()).isEqualTo(TickResult.EMPTY);
        assertThat(watermark(workspaceId)).isNull();

        config().setTickBudget(defaults.getTickBudget());
        config().setEmbedTimeout(defaults.getEmbedTimeout());
        assertThat(tick()).isEqualTo(TickResult.written(1));
        assertVectors(tables.vectors(workspaceId, List.of(traceId)),
                List.of(vector(workspaceId, projectId, traceId, 1)));
    }

    private Instant watermark(String workspaceId) {
        return workspaceService.vectorsWatermarks(List.of(workspaceId)).get(workspaceId);
    }

    @Test
    @DisplayName("a rejected chunk (a non-retryable 4xx) is sent again in single inputs, and costs only the inputs "
            + "refused on their own: they get a status row, the workspace's next chunk, its backfill and its next "
            + "project are still sent, the watermark moves past them, and the projects drain")
    void rejectedChunk() {
        config().setEmbedBatchSize(2);
        var workspaceId = enabledWorkspace();
        // Drained, so its traces are the workspace read's; the two new projects are the backfill's.
        var drained = ID_GENERATOR.generateId();
        markDrained(workspaceId, drained);
        var traceIds = new ArrayList<>(labelTraces(workspaceId, drained, 4, 1));
        traceIds.addAll(labelTraces(workspaceId, 2, 1));
        traceIds.addAll(labelTraces(workspaceId, 2, 1));
        embedStub.fail(workspaceId, 400, "{\"error\":{\"code\":\"invalid_request\",\"message\":\"m\"}}");

        // MySQL keeps microseconds.
        var start = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var result = tickService.tick(start).block();

        // Each of the 4 chunks of 2: the chunk, then each input alone.
        assertThat(embedStub.callsFor(workspaceId)).isEqualTo(12);
        assertThat(result).isEqualTo(TickResult.skipped(SkipReason.EMBED_REFUSED, 8));
        assertThat(watermark(workspaceId)).isEqualTo(start);
        assertThat(backfillService.findUndrained(List.of(workspaceId))).isEmpty();
        assertThat(tables.statuses(workspaceId, traceIds)).hasSize(8).containsValue("embed_refused");
    }

    @Test
    @DisplayName("an answer of the wrong dimensions leaves the traces for a later tick: the project stays in backfill")
    void wrongDimensions() {
        var workspaceId = enabledWorkspace();
        labelTraces(workspaceId, 2, 1);
        embedStub.dimensions = 8;

        assertThat(tick()).isEqualTo(TickResult.skipped(SkipReason.INVALID_VECTOR, 2));
        assertThat(backfillService.findUndrained(List.of(workspaceId))).isNotEmpty();
    }

    @Test
    @DisplayName("an embed timeout writes nothing and escapes nothing: its traces are embed_failed")
    void embedTimeout() {
        config().setEmbedTimeout(io.dropwizard.util.Duration.milliseconds(500));
        var workspaceId = enabledWorkspace();
        labelTraces(workspaceId, 4, 1);
        embedStub.delay = Duration.ofSeconds(3);

        assertThat(tick()).isEqualTo(TickResult.skipped(SkipReason.EMBED_FAILED, 4));
        assertThat(countRows(workspaceId)).isZero();
        // WireMock journals a request only after the stub's delay ends, after this test. Wait for it, so the entry
        // cannot land after the next test's resetAll and show up in that test's journal.
        Awaitility.await().atMost(10, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(wireMock.findAll(postRequestedFor(urlEqualTo(EMBED_PATH))))
                        .hasSize(embedStub.calls.get()));
    }

    @Test
    @DisplayName("the find guards reach the discovery and the window read: max_rows_to_read 1 trips both and the tick "
            + "writes and skips nothing; the default reads it")
    void findGuardsReachTheWindowRead() {
        var workspaceId = enabledWorkspace();
        labelTraces(workspaceId, 3, 1);
        var find = config().getFind();
        var maxRowsToRead = find.getMaxRowsToRead();
        find.setMaxRowsToRead(1);
        TickResult tripped;
        try {
            // ClickHouse's TOO_MANY_ROWS: the guard, not some other failure, fails the read.
            assertThatThrownBy(() -> vectorsDAO.findUnvectored(TopicVectorsFind.builder()
                    .workspaceId(workspaceId)
                    .task(TopicEmbedding.SUMMARY_TASK)
                    .lowerBound(Instant.now().minus(1, ChronoUnit.HOURS))
                    .upperBound(Instant.now())
                    .createdAfter(Instant.EPOCH)
                    .limit(100)
                    .maxExecutionSeconds(10)
                    .build()).collectList().block())
                    .hasStackTraceContaining("Code: 158");
            tripped = tick();
        } finally {
            find.setMaxRowsToRead(maxRowsToRead);
        }

        assertThat(tripped).isEqualTo(TickResult.EMPTY);
        assertThat(countRows(workspaceId)).isZero();

        assertThat(tick()).isEqualTo(TickResult.written(3));
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

        assertThat(tick()).isEqualTo(TickResult.written(2));
        assertThat(tables.vectors(workspaceId, List.of(withoutOutput, blank))).isEmpty();
        assertVectors(tables.vectors(workspaceId, traceIds), expectedVectors(workspaceId, projectId, traceIds, 1));
    }

    @Test
    @DisplayName("a zero vector costs only its own trace: it is counted as invalid_vector, the rest of the chunk is "
            + "written")
    void invalidVector() {
        var workspaceId = enabledWorkspace();
        var projectId = ID_GENERATOR.generateId();
        var zero = ID_GENERATOR.generateId();
        embedStub.zeroText = "a summary the stub embeds as zeros";
        labelsDAO.insert(List.of(outputLabel(workspaceId, projectId, zero, 1, embedStub.zeroText))).block();
        statusDAO.insert(List.of(doneStatus(workspaceId, projectId, zero, 1))).block();
        var traceIds = labelTraces(workspaceId, projectId, 2, 1);

        assertThat(tick()).isEqualTo(new TickResult(2, Map.of(SkipReason.INVALID_VECTOR, 1)));
        assertThat(tables.vectors(workspaceId, List.of(zero))).isEmpty();
        assertVectors(tables.vectors(workspaceId, traceIds), expectedVectors(workspaceId, projectId, traceIds, 1));
    }

    @Test
    @DisplayName("a backfill project read page by page sends an invalid vector's trace once: it gets a status row, "
            + "and the next page's read leaves it out")
    void backfillPagesSkipSettledKeys() {
        config().setMaxTracesPerTick(2);
        var workspaceId = enabledWorkspace();
        var projectId = ID_GENERATOR.generateId();
        var zero = ID_GENERATOR.generateId();
        embedStub.zeroText = "a summary the stub embeds as zeros";
        labelsDAO.insert(List.of(outputLabel(workspaceId, projectId, zero, 1, embedStub.zeroText))).block();
        // The oldest status, so every page of the project's read starts with it.
        tables.insertStatusAt(workspaceId, projectId, zero, 1, Instant.now().minus(3, ChronoUnit.DAYS));
        var traceIds = oldStatuses(workspaceId, projectId, 2);

        assertThat(tick()).isEqualTo(new TickResult(2, Map.of(SkipReason.INVALID_VECTOR, 1)));
        assertThat(embedStub.calls.get()).isEqualTo(2);
        assertVectors(tables.vectors(workspaceId, traceIds), expectedVectors(workspaceId, projectId, traceIds, 1));
        assertThat(backfillService.findUndrained(List.of(workspaceId))).isEmpty();
    }

    @Test
    @DisplayName("a full backfill page of traces that can never get a vector is read and sent once: they get status "
            + "rows, the next read moves past them in the same tick, and the project drains")
    void unembeddablePageReadOnce() {
        config().setMaxTracesPerTick(2);
        var workspaceId = enabledWorkspace();
        var projectId = ID_GENERATOR.generateId();
        embedStub.zeroText = "a summary the stub embeds as zeros";
        var zeros = List.of(ID_GENERATOR.generateId(), ID_GENERATOR.generateId());
        for (int i = 0; i < zeros.size(); i++) {
            labelsDAO.insert(List.of(outputLabel(workspaceId, projectId, zeros.get(i), 1, embedStub.zeroText)))
                    .block();
            // The oldest statuses, so the first page is only them.
            tables.insertStatusAt(workspaceId, projectId, zeros.get(i), 1,
                    Instant.now().minus(3, ChronoUnit.DAYS).plusSeconds(i));
        }
        var traceIds = oldStatuses(workspaceId, projectId, 2);

        assertThat(tick()).isEqualTo(new TickResult(2, Map.of(SkipReason.INVALID_VECTOR, 2)));
        assertThat(embedStub.calls.get()).isEqualTo(2);
        assertThat(tables.statuses(workspaceId, zeros)).containsOnlyKeys(zeros).containsValue("invalid_vector");
        assertVectors(tables.vectors(workspaceId, traceIds), expectedVectors(workspaceId, projectId, traceIds, 1));
        assertThat(backfillService.findUndrained(List.of(workspaceId))).isEmpty();

        makeLongWindowPassDue();
        assertThat(tick()).isEqualTo(TickResult.builder().skipped(Map.of()).longWindowPass(true).build());
        assertThat(embedStub.calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("two task versions of one trace give two rows, both kept")
    void twoTaskVersions() {
        var workspaceId = enabledWorkspace();
        var projectId = ID_GENERATOR.generateId();
        var traceId = ID_GENERATOR.generateId();
        labelsDAO.insert(List.of(outputLabel(workspaceId, projectId, traceId, 1))).block();
        statusDAO.insert(List.of(doneStatus(workspaceId, projectId, traceId, 1))).block();
        assertThat(tick()).isEqualTo(TickResult.written(1));

        // A relabel: the new version's label, then its status, as ai-labels writes them.
        labelsDAO.insert(List.of(outputLabel(workspaceId, projectId, traceId, 2))).block();
        statusDAO.insert(List.of(doneStatus(workspaceId, projectId, traceId, 2))).block();
        assertThat(tick()).isEqualTo(TickResult.written(1));

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
            assertThat(tick()).isEqualTo(TickResult.written(5_000));
            written.addAll(batch);
            assertThat(Set.copyOf(writtenTraceIds(workspaceId))).isEqualTo(written);
        }

        assertThat(countRows(workspaceId)).isEqualTo(20_000);
        int calls = embedStub.calls.get();
        assertThat(tick()).isEqualTo(TickResult.EMPTY);
        assertThat(embedStub.calls.get()).isEqualTo(calls);
    }

    static Stream<Arguments> backOffs() {
        return Stream.of(
                arguments(429, "{\"error\":{\"code\":\"rate_limited\",\"message\":\"m\"}}"),
                // Refusals of the workspace itself, not of the inputs: every later chunk would get the same answer.
                arguments(401, "{\"error\":{\"code\":\"unauthorized\",\"message\":\"m\"}}"),
                arguments(403, "{\"error\":{\"code\":\"org_not_allowed\",\"message\":\"m\"}}"),
                // An unknown code and no retryable flag.
                arguments(503, "{\"error\":{\"code\":\"something_new\",\"message\":\"m\"}}"));
    }

    @ParameterizedTest(name = "status {0}")
    @MethodSource("backOffs")
    @DisplayName("the cap is per workspace, and a 429, a 401, a 403, or a 5xx with an unknown code and no retryable "
            + "flag, backs off only its workspace after one call")
    void perWorkspaceCapAndBackOff(int status, String body) {
        config().setMaxTracesPerTick(4);
        config().setEmbedBatchSize(2);
        var throttled = enabledWorkspace();
        var healthy = enabledWorkspace();
        var throttledProject = ID_GENERATOR.generateId();
        var healthyProject = ID_GENERATOR.generateId();
        // Drained projects: the workspace read's cap applies, not the backfill's turns.
        markDrained(throttled, throttledProject);
        markDrained(healthy, healthyProject);
        var throttledTraces = labelTraces(throttled, throttledProject, 6, 1);
        var healthyTraces = labelTraces(healthy, healthyProject, 6, 1);
        embedStub.fail(throttled, status, body);

        var result = tick();

        assertThat(embedStub.callsFor(throttled)).isOne();
        assertThat(result).isEqualTo(new TickResult(4, Map.of(SkipReason.EMBED_FAILED, 4)));
        assertVectors(tables.vectors(healthy, healthyTraces),
                expectedVectors(healthy, healthyProject, oldestFour(healthyTraces), 1));
        assertThat(tables.vectors(throttled, throttledTraces)).isEmpty();

        embedStub.reset();
        assertThat(tick()).isEqualTo(TickResult.written(6));
        assertVectors(tables.vectors(throttled, throttledTraces),
                expectedVectors(throttled, throttledProject, oldestFour(throttledTraces), 1));
        assertVectors(tables.vectors(healthy, healthyTraces),
                expectedVectors(healthy, healthyProject, healthyTraces, 1));
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

        var first = tick();

        assertThat(first.written()).isPositive().isLessThan(100);
        assertThat(first)
                .isEqualTo(new TickResult(first.written(), Map.of(SkipReason.OVER_BUDGET, 100 - first.written())));
        assertThat(embedStub.calls.get() * 10).isEqualTo(first.written());

        embedStub.delay = Duration.ZERO;
        config().setTickBudget(defaults.getTickBudget());
        var second = tick();

        assertThat(second).isEqualTo(TickResult.written(100 - first.written()));
        assertVectors(tables.vectors(workspaceId, traceIds), expectedVectors(workspaceId, projectId, traceIds, 1));
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
    @DisplayName("a project delete removes that project's backfill row, in its workspace only")
    void backfillRowsOfDeletedProject() {
        var workspaceId = ID_GENERATOR.generateId().toString();
        var otherWorkspaceId = ID_GENERATOR.generateId().toString();
        var deleted = ID_GENERATOR.generateId();
        var kept = ID_GENERATOR.generateId();
        var t0 = Instant.parse("2026-11-01T10:00:00Z");
        var row = TopicBackfill.builder().workspaceId(workspaceId).backfillStartedAt(t0)
                .backfillLastDoneAt(t0);
        backfillService.start(List.of(row.projectId(deleted).build(), row.projectId(kept).build(),
                row.workspaceId(otherWorkspaceId).projectId(deleted).build()));

        eventBus.post(new ProjectsDeleted(Set.of(deleted), workspaceId, "user"));

        Awaitility.await().atMost(15, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> assertThat(backfillService.findByKeys(workspaceId, List.of(deleted, kept)))
                        .containsOnlyKeys(kept));
        assertThat(backfillService.findByKeys(otherWorkspaceId, List.of(deleted))).containsOnlyKeys(deleted);
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
                        AND log_comment LIKE concat('topics:delete_by_trace_ids_topic_vectors:', :workspace_id, ':%')
                        AND query LIKE '%IN PARTITION%'
                        """)
                        .bind("workspace_id", workspaceId)
                        .execute()))
                .flatMap(result -> Mono.from(result.map((row, metadata) -> row.get("cnt", Long.class)))))
                .block();
    }

    private TickResult tick() {
        return tickService.tick(Instant.now()).block();
    }

    /**
     * Answers {@code /v1/embed} with {@link TopicVectorsFixtures#rawVector} of each input, or zeros for
     * {@link #zeroText}; can throttle one call, delay every call, or fail every call of one workspace.
     */
    static final class EmbedStub implements ResponseTransformerV2 {

        static final String NAME = "embed-stub";

        final AtomicInteger calls = new AtomicInteger();
        private final Map<String, AtomicInteger> callsByOrg = new ConcurrentHashMap<>();
        volatile int throttleCall;
        volatile Duration delay = Duration.ZERO;
        volatile String zeroText;
        volatile int dimensions = TopicEmbedding.DIMENSIONS;
        volatile Runnable beforeAnswer;
        private volatile String failOrg;
        private volatile int failStatus;
        private volatile String failBody;

        void reset() {
            calls.set(0);
            callsByOrg.clear();
            throttleCall = 0;
            delay = Duration.ZERO;
            zeroText = null;
            dimensions = TopicEmbedding.DIMENSIONS;
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
            var hook = beforeAnswer;
            if (hook != null) {
                hook.run();
            }
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
            var body = InferenceJson.MAPPER.createObjectNode();
            body.put("request_id", "r").put("cache_hit", false).put("latency_ms", 1);
            body.putObject("usage").put("input_tokens", 1).put("output_tokens", 0);
            var result = body.putObject("result");
            result.put("dimensions", dimensions);
            var embeddings = result.putArray("embeddings");
            for (var input : request.path("inputs")) {
                var text = input.asText();
                var raw = text.equals(zeroText) ? new float[TopicEmbedding.DIMENSIONS] : rawVector(text);
                if (dimensions != TopicEmbedding.DIMENSIONS) {
                    raw = Arrays.copyOf(raw, dimensions);
                }
                var vector = embeddings.addArray();
                for (float value : raw) {
                    vector.add(value);
                }
            }
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

    /** Drops the long-window lock, so the next tick runs the pass. */
    private void makeLongWindowPassDue() {
        lockService.unlockUsingToken(LongWindowPass.LOCK).block();
    }

    /** Gives the project a drained backfill row, as a project whose backfill ended earlier has. */
    private void markDrained(String workspaceId, UUID projectId) {
        tables.project(workspaceId, projectId);
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        backfillService.start(List.of(TopicBackfill.builder().workspaceId(workspaceId)
                .projectId(projectId).backfillStartedAt(now).backfillLastDoneAt(now).build()));
        backfillService.record(List.of(TopicBackfillProgress.builder().workspaceId(workspaceId)
                .projectId(projectId).drainedAt(now).build()));
    }

    private TopicsConfig.VectorsConfig config() {
        return opikConfig.getTopics().getVectors();
    }

    private String enabledWorkspace() {
        return workspaceWith(true, true);
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
        var ids = new TreeSet<>(topics.enabledWorkspaceIds());
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

    private List<UUID> writtenTraceIds(String workspaceId) {
        return templateAsync.nonTransaction(connection -> Flux
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
