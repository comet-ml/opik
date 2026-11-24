package com.comet.opik.domain.topics;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.PostCutoverTraces;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.redis.testcontainers.RedisContainer;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.dropwizard.guice.test.jupiter.param.Jit;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The labelling pass against real ClickHouse and MySQL: the rows a pass writes and its slice bounds. No run builds
 * here: tests seed vectors and a saved list directly and call the label service. {@link TopicsDaoStorageTest} covers
 * the DAO reads.
 */
@DisplayName("topics labelling pass")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TopicsLabellingPassTest {

    private static final int BUILT = 120;
    /** The pass's page and slice in config-test.yml. */
    private static final int PAGE = 7;
    private static final int SLICE = 20;
    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();
    private static final int WINDOW_DAYS = 30;
    private static final Instant NIGHT = Instant.parse("2026-10-15T00:00:00Z");
    private static final TopicsTestData.Traces TRACES = TopicsTestData.Traces.synthetic(BUILT, NIGHT, 7);
    /** One trace every 72 minutes over the window. */
    private static final int SPREAD = 600;
    private static final TopicsTestData.Traces SPREAD_TRACES = TopicsTestData.Traces.spread(SPREAD, NIGHT,
            WINDOW_DAYS, 3);
    // The app needs Redis to start; no test here uses it.
    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer(false);
    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer(false, network);
    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer(false, network,
            zookeeper);

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redis, mysql, zookeeper, clickHouse).join();
        MigrationUtils.runMysqlDbMigration(mysql);
        PostCutoverTraces.migrate(clickHouse);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(AppContextConfig.builder()
                .jdbcUrl(mysql.getJdbcUrl())
                .databaseAnalyticsFactory(ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouse,
                        DATABASE_NAME))
                .redisUrl(redis.getRedisURI())
                .customConfigs(List.of(
                        new CustomConfig("databaseAnalyticsDataModel.traceColumnsNonNullable", "true"),
                        new CustomConfig("databaseAnalyticsDataModel.tracesDistributedWrapEnabled", "true")))
                .build());
    }

    private OpikConfiguration opikConfig;
    private TransactionTemplate template;
    private TransactionTemplateAsync clickHouseTemplate;
    private TopicVectorsDAO vectorsDAO;
    private TraceTopicsDAO traceTopicsDAO;
    private TopicWindowLabelService labelService;
    private TopicWindowDAO windowDAO;
    private TopicsTestData data;
    private TopicsTestData spreadData;

    private TopicAlgorithm algorithm;

    @BeforeAll
    void beforeAll(OpikConfiguration opikConfig, TransactionTemplate template,
            TransactionTemplateAsync clickHouseTemplate, @Jit TopicVectorsDAO vectorsDAO,
            @Jit TraceTopicsDAO traceTopicsDAO, @Jit TopicWindowLabelService labelService,
            @Jit TopicWindowDAO windowDAO, @Jit TopicAlgorithm algorithm) {
        this.algorithm = algorithm;
        this.opikConfig = opikConfig;
        this.template = template;
        this.clickHouseTemplate = clickHouseTemplate;
        this.vectorsDAO = vectorsDAO;
        this.traceTopicsDAO = traceTopicsDAO;
        this.labelService = labelService;
        this.windowDAO = windowDAO;
        this.data = new TopicsTestData(template, clickHouseTemplate, vectorsDAO, TRACES);
        this.spreadData = new TopicsTestData(template, clickHouseTemplate, vectorsDAO, SPREAD_TRACES);
    }

    @AfterAll
    void afterAll() {
        clickHouse.stop();
        zookeeper.stop();
        mysql.stop();
        network.close();
    }

    @Test
    @DisplayName("the labelling pass gives every window trace exactly one row of the saved run, by its prototypes; a "
            + "trace that has one keeps it, a trace outside the window gets none, and a second pass has nothing left")
    void labellingPassLabelsTheWindow() {
        var workspaceId = id().toString();
        var projectId = id();
        data.seed(workspaceId, projectId, 0, BUILT);
        // After the window.
        spreadData.seed(workspaceId, projectId, 0, 20, NIGHT.plus(Duration.ofDays(WINDOW_DAYS + 1)));
        var runId = id();
        var topicId = id();
        int dimensions = TopicEmbedding.DIMENSIONS;
        var vectors = saveList(workspaceId, projectId, runId, topicId);
        // The run's own insert, or an earlier cut pass, labelled 30 traces.
        var labelledBefore = id();
        insertRunRows(workspaceId, projectId, runId, labelledBefore, TRACES.traceIds().subList(3, 33),
                TRACES.createdAt().subList(3, 33));

        assertThat(data.topicProject(projectId).labelsComplete()).isFalse();

        var pass = labelService.labelWindow(workspace(workspaceId), projectId,
                Instant.now().plus(Duration.ofMinutes(5)), LabelTrigger.RUN, Schedulers.boundedElastic()).block();

        assertThat(pass).isEqualTo(LabelPassOutcome.COMPLETE);
        assertThat(data.topicProject(projectId).labelsComplete()).isTrue();
        assertThat(rowsOfRun(workspaceId, projectId, runId))
                .containsOnlyKeys(TRACES.traceIds())
                .allSatisfy((traceId, rows) -> assertThat(rows).isEqualTo(1));
        var assigner = new PrototypeAssigner(List.of(vectors.get(0), vectors.get(2)), List.of(0, 0),
                List.of(vectors.get(1)), dimensions, TopicsTestAlgorithm.config().getAssignMinCosine(),
                TopicsTestAlgorithm.config().getOtherGate());
        var labels = assigner.assign(TRACES.vectors());
        var expected = new HashMap<UUID, UUID>();
        for (int i = 0; i < BUILT; i++) {
            expected.put(TRACES.traceIds().get(i), i >= 3 && i < 33
                    ? labelledBefore
                    : labels[i] == TopicAlgorithm.OTHER ? TraceTopic.OTHER : topicId);
        }
        assertThat(expected.get(TRACES.traceIds().get(0))).isEqualTo(topicId);
        assertThat(expected.get(TRACES.traceIds().get(1))).isEqualTo(TraceTopic.OTHER);
        assertThat(data.traceTopics(workspaceId, projectId).stream()
                .collect(Collectors.toMap(TraceTopic::traceId, TraceTopic::topicId))).isEqualTo(expected);

        var rowsBefore = data.traceTopics(workspaceId, projectId).size();
        var again = labelService.labelWindow(workspace(workspaceId), projectId,
                Instant.now().plus(Duration.ofMinutes(5)), LabelTrigger.RESUME, Schedulers.boundedElastic()).block();

        assertThat(again).isEqualTo(LabelPassOutcome.COMPLETE);
        assertThat(data.traceTopics(workspaceId, projectId)).hasSize(rowsBefore);
        var maxRowsToRead = String.valueOf(opikConfig.getTopics().getRun().getRead().getMaxRowsToRead());
        assertThat(data.queryGuards("topics:read_unlabelled", workspaceId))
                .containsExactly(List.of("2", maxRowsToRead, "100", "120"));
    }

    @Test
    @DisplayName("the window's slice bounds hold the same number of live traces each, however the traffic is spread in "
            + "time: most of this window is in its last 20 hours")
    void sliceBoundsAreEqualInTraces() {
        var workspaceId = id().toString();
        var projectId = id();
        data.seed(workspaceId, projectId, 0, BUILT);
        spreadData.seed(workspaceId, projectId, 0, 40);
        int sliceTraces = 20;

        var window = windowDAO.countAndBounds(workspaceId, projectId, from(), to(), sliceTraces, 60).block();

        var created = Stream.concat(TRACES.createdAt().stream(), SPREAD_TRACES.createdAt().subList(0, 40).stream())
                .sorted().toList();
        assertThat(window.traces()).isEqualTo(BUILT + 40);
        assertThat(window.bounds()).isEqualTo(IntStream.rangeClosed(1, created.size())
                .filter(i -> i % sliceTraces == 0).mapToObj(i -> created.get(i - 1)).toList());
        var read = opikConfig.getTopics().getRun().getRead();
        assertThat(data.queryGuards("topics:count_and_bounds", workspaceId))
                .containsExactly(List.of(String.valueOf(read.getMaxThreads()), String.valueOf(read.getMaxRowsToRead()),
                        String.valueOf(read.getPriority()), "60"));
    }

    @Test
    @DisplayName("every live window trace gets one row of the run, whatever its keys hold: a relabelled trace (two "
            + "task versions), a tombstoned one (none), traces tied on a slice bound, and traces that already have a "
            + "row of an older run or an arrival row")
    void labellingPassOverEveryKeyShape() {
        var workspaceId = id().toString();
        var projectId = id();
        data.seed(workspaceId, projectId, 0, BUILT);
        var runId = id();
        saveList(workspaceId, projectId, runId, id());
        data.relabel(workspaceId, projectId, TRACES.traceIds().subList(40, 45));
        var tombstoned = TRACES.traceIds().subList(50, 55);
        data.insertVectorTombstones(workspaceId, projectId, tombstoned, 1);
        // Five more traces created at the same time as trace 19, the first slice's bound.
        var tied = IntStream.range(0, 5).mapToObj(i -> id()).toList();
        tied.forEach(traceId -> copyVector(workspaceId, projectId, TRACES.traceIds().get(19), traceId));
        TRACES.traceIds().subList(60, 65).forEach(traceId -> data.insertTraceTopic(workspaceId, projectId, traceId,
                createdAt(traceId), id(), Instant.now(), 0));
        TRACES.traceIds().subList(70, 75).forEach(traceId -> data.insertTraceTopic(workspaceId, projectId, traceId,
                createdAt(traceId), id(), TraceTopic.Source.ARRIVAL, Instant.now(), 0));

        assertThat(label(labelService, workspaceId, projectId, Instant.now().plus(Duration.ofMinutes(5))))
                .isEqualTo(LabelPassOutcome.COMPLETE);

        var live = Stream.concat(TRACES.traceIds().stream().filter(traceId -> !tombstoned.contains(traceId)),
                tied.stream()).toList();
        assertThat(rowsOfRun(workspaceId, projectId, runId))
                .containsOnlyKeys(live)
                .allSatisfy((traceId, rows) -> assertThat(rows).isEqualTo(1));
        assertThat(data.topicProject(projectId).labelsComplete()).isTrue();
    }

    @Test
    @DisplayName("a failed insert fails the pass: the pages before it stay and nothing is marked complete; the next "
            + "pass labels the rest, every window trace ends with one row, and only then are the labels complete")
    void failedInsertResumes() {
        var workspaceId = id().toString();
        var projectId = id();
        data.seed(workspaceId, projectId, 0, BUILT);
        var runId = id();
        saveList(workspaceId, projectId, runId, id());
        var failing = spy(traceTopicsDAO);
        var calls = new AtomicInteger();
        doAnswer(invocation -> calls.incrementAndGet() == 3
                ? Mono.error(new IllegalStateException("not a transient error"))
                : invocation.callRealMethod()).when(failing).insert(any());

        assertThat(label(service(windowDAO, failing), workspaceId, projectId,
                Instant.now().plus(Duration.ofMinutes(5)))).isEqualTo(LabelPassOutcome.FAILED);

        assertThat(rowsOfRun(workspaceId, projectId, runId)).hasSize(2 * PAGE);
        assertThat(data.topicProject(projectId).labelsComplete()).isFalse();

        assertThat(label(labelService, workspaceId, projectId, Instant.now().plus(Duration.ofMinutes(5))))
                .isEqualTo(LabelPassOutcome.COMPLETE);

        assertThat(rowsOfRun(workspaceId, projectId, runId))
                .containsOnlyKeys(TRACES.traceIds())
                .allSatisfy((traceId, rows) -> assertThat(rows).isEqualTo(1));
        assertThat(data.topicProject(projectId).labelsComplete()).isTrue();
    }

    @Test
    @DisplayName("a pass past its deadline is cut before it reads; one whose deadline comes mid-pass, or whose caller "
            + "cancels it, is cut too: the pages inserted stay, and nothing is marked complete")
    void cutPasses() {
        var workspaceId = id().toString();
        var projectId = id();
        data.seed(workspaceId, projectId, 0, BUILT);
        var runId = id();
        saveList(workspaceId, projectId, runId, id());

        assertThat(label(labelService, workspaceId, projectId, Instant.now().minusSeconds(1)))
                .isEqualTo(LabelPassOutcome.CUT);
        assertThat(rowsOfRun(workspaceId, projectId, runId)).isEmpty();

        // From here, the second page's insert of each pass outlasts the pass.
        var slow = spy(traceTopicsDAO);
        var calls = new AtomicInteger();
        doAnswer(invocation -> calls.incrementAndGet() % 2 == 0
                ? Mono.delay(Duration.ofSeconds(30)).then(Mono.<Long>empty())
                : invocation.callRealMethod()).when(slow).insert(any());
        var service = service(windowDAO, slow);

        assertThat(label(service, workspaceId, projectId, Instant.now().plusSeconds(3)))
                .isEqualTo(LabelPassOutcome.CUT);
        assertThat(rowsOfRun(workspaceId, projectId, runId)).hasSize(PAGE);

        var cancelled = service.labelWindow(workspace(workspaceId), projectId,
                Instant.now().plus(Duration.ofMinutes(5)), LabelTrigger.RESUME, Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(3)).onErrorResume(
                        error -> Mono.empty())
                .block();
        assertThat(cancelled).isNull();
        assertThat(rowsOfRun(workspaceId, projectId, runId)).hasSize(2 * PAGE);
        assertThat(data.topicProject(projectId).labelsComplete()).isFalse();
    }

    @Test
    @DisplayName("a broken read stops its slice and fails the pass: a page that does not move past the last trace, or "
            + "more full pages than the slice can hold")
    void pageChecksStopTheSlice() {
        var workspaceId = id().toString();
        var projectId = id();
        data.seed(workspaceId, projectId, 0, BUILT);
        var runId = id();
        saveList(workspaceId, projectId, runId, id());

        // Every page of a slice is its first page again.
        var stuck = spy(windowDAO);
        doAnswer(invocation -> windowDAO.readUnlabelled(workspaceId, projectId, runId, invocation.getArgument(3),
                invocation.getArgument(4), "", invocation.getArgument(6), invocation.getArgument(7)))
                .when(stuck).readUnlabelled(any(), any(), any(), any(), any(), any(), anyInt(), anyLong());
        // Inserts write nothing, so the stuck read returns the same traces on every page.
        var noopInserts = spy(traceTopicsDAO);
        doReturn(Mono.just(0L)).when(noopInserts).insert(any());

        assertThat(
                label(service(stuck, noopInserts), workspaceId, projectId, Instant.now().plus(Duration.ofMinutes(5))))
                .isEqualTo(LabelPassOutcome.FAILED);

        // Every page is full of traces the slice never had.
        var next = new AtomicLong();
        var endless = spy(windowDAO);
        doAnswer(invocation -> Flux.range(0, invocation.<Integer>getArgument(6))
                .map(i -> new TopicWindowDAO.WindowVector(new UUID(0x7000_0000_0000_7000L
                        + (next.incrementAndGet() << 16), 0x8000_0000_0000_0000L), invocation.getArgument(3),
                        TopicsTestData.unit(0))))
                .when(endless).readUnlabelled(any(), any(), any(), any(), any(), any(), anyInt(), anyLong());

        assertThat(
                label(service(endless, noopInserts), workspaceId, projectId, Instant.now().plus(Duration.ofMinutes(5))))
                .isEqualTo(LabelPassOutcome.FAILED);
        // A bound at every 20th trace: the last slice holds the window's last trace alone.
        int slices = BUILT / SLICE + 1;
        int maxPages = (SLICE + PAGE - 1) / PAGE + 2;
        // The stuck pass inserted each slice's first page; the endless one every page of every slice.
        verify(noopInserts, times(slices + slices * maxPages)).insert(any());
        assertThat(data.topicProject(projectId).labelsComplete()).isFalse();
    }

    /**
     * A saved list of one topic, saved by {@code runId}: trace 0 and trace 2 are the topic's prototypes, trace 1 a
     * known-Other one. Returns the three vectors.
     */
    private List<float[]> saveList(String workspaceId, UUID projectId, UUID runId, UUID topicId) {
        int dimensions = TopicEmbedding.DIMENSIONS;
        var vectors = new ArrayList<float[]>();
        for (int i = 0; i < 3; i++) {
            vectors.add(Arrays.copyOfRange(TRACES.vectors(), i * dimensions, (i + 1) * dimensions));
        }
        var savedAt = NIGHT.plus(Duration.ofHours(2));
        template.inTransaction(WRITE, handle -> {
            var projectDAO = handle.attach(TopicProjectDAO.class);
            var topicDAO = handle.attach(TopicDAO.class);
            projectDAO.startRun(workspaceId, projectId, runId, TopicProject.RunMode.DISCOVERY,
                    savedAt.minus(Duration.ofMinutes(1)));
            projectDAO.saveList(workspaceId, projectId, null, runId, savedAt, NIGHT,
                    new PrototypeVectors(List.of(vectors.get(1))), TopicEmbedding.MODEL);
            topicDAO.insertTopics(List.of(Topic.builder()
                    .id(topicId)
                    .workspaceId(workspaceId)
                    .projectId(projectId)
                    .name("Billing")
                    .definition("Requests about invoices")
                    .born(LocalDate.ofInstant(NIGHT, ZoneOffset.UTC))
                    .vectors(new PrototypeVectors(List.of(vectors.get(0), vectors.get(2))))
                    .build()));
            projectDAO.markSaved(workspaceId, projectId, runId, savedAt);
            return null;
        });
        return vectors;
    }

    /** The raw {@code trace_topics} rows of the run per trace, before any merge dedupes them. */
    private Map<UUID, Long> rowsOfRun(String workspaceId, UUID projectId, UUID runId) {
        return clickHouseTemplate.nonTransaction(connection -> Flux.from(connection.createStatement("""
                SELECT trace_id, count() AS rows
                FROM trace_topics
                WHERE workspace_id = :workspace_id AND project_id = :project_id AND run_id = :run_id
                GROUP BY trace_id
                """)
                .bind("workspace_id", workspaceId)
                .bind("project_id", projectId.toString())
                .bind("run_id", runId.toString())
                .execute())
                .flatMap(result -> result.map((row, metadata) -> Map.entry(
                        UUID.fromString(row.get("trace_id", String.class)), row.get("rows", Long.class))))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue))
                .block();
    }

    /** The window that ends at the seeded traces' night. */
    private static Instant from() {
        return NIGHT.minus(Duration.ofDays(WINDOW_DAYS));
    }

    private static Instant to() {
        return NIGHT;
    }

    /** Rows of the run labelling trace {@code traceIds[i]}, created at {@code createdAt[i]}, with {@code topicId}. */
    private void insertRunRows(String workspaceId, UUID projectId, UUID runId, UUID topicId, List<UUID> traceIds,
            List<Instant> createdAt) {
        traceTopicsDAO.insert(IntStream.range(0, traceIds.size())
                .mapToObj(i -> TraceTopic.builder()
                        .workspaceId(workspaceId)
                        .projectId(projectId)
                        .traceId(traceIds.get(i))
                        .traceCreatedAt(createdAt.get(i))
                        .topicId(topicId)
                        .source(TraceTopic.Source.RUN)
                        .runId(runId)
                        .build())
                .toList()).block();
    }

    private LabelPassOutcome label(TopicWindowLabelService service, String workspaceId, UUID projectId,
            Instant deadline) {
        return service.labelWindow(workspace(workspaceId), projectId, deadline, LabelTrigger.RUN,
                Schedulers.boundedElastic()).block();
    }

    private static TopicsMetrics.Workspace workspace(String workspaceId) {
        return new TopicsMetrics.Workspace(workspaceId, "Acme");
    }

    /** The label service on the given stores, as the app builds it. */
    private TopicWindowLabelService service(TopicWindowDAO windowDAO, TraceTopicsDAO traceTopicsDAO) {
        var metrics = TopicsMetricsReader.metrics(InMemoryMetricReader.create());
        var store = new TopicRunStore(opikConfig.getTopics(), template, traceTopicsDAO, mock(ProjectService.class),
                ID_GENERATOR, metrics);
        return new TopicWindowLabelService(opikConfig.getTopics(), windowDAO, store, metrics, algorithm);
    }

    private static UUID id() {
        return ID_GENERATOR.generateId();
    }

    private static Instant createdAt(UUID traceId) {
        return TRACES.createdAt().get(TRACES.traceIds().indexOf(traceId));
    }

    /** A new trace {@code traceId} with the vector row of {@code source}: same time, text and vector. */
    private void copyVector(String workspaceId, UUID projectId, UUID source, UUID traceId) {
        clickHouseTemplate.nonTransaction(connection -> Mono.from(connection.createStatement("""
                INSERT INTO topic_vectors (workspace_id, project_id, task, trace_id, trace_created_at, task_version,
                    embed_model, embed_dimensions, text, vector)
                SELECT workspace_id, project_id, task, :trace_id, trace_created_at, task_version, embed_model,
                    embed_dimensions, text, vector
                FROM topic_vectors
                WHERE workspace_id = :workspace_id AND project_id = :project_id AND trace_id = :source
                SETTINGS log_comment = 'topics_test:copy_vector'
                """)
                .bind("workspace_id", workspaceId)
                .bind("project_id", projectId.toString())
                .bind("source", source.toString())
                .bind("trace_id", traceId.toString())
                .execute())
                .flatMap(result -> Mono.from(result.getRowsUpdated())))
                .block();
    }
}
