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
import com.comet.opik.domain.WorkspaceNameService;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import com.redis.testcontainers.RedisContainer;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.r2dbc.spi.ConnectionFactory;
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
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.dropwizard.guice.test.jupiter.param.Jit;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
    private ConnectionFactory connectionFactory;
    private TopicsTestData data;
    private TopicsTestData spreadData;

    private TopicAlgorithm algorithm;

    @BeforeAll
    void beforeAll(OpikConfiguration opikConfig, TransactionTemplate template,
            TransactionTemplateAsync clickHouseTemplate, @Jit TopicVectorsDAO vectorsDAO,
            @Jit TraceTopicsDAO traceTopicsDAO, @Jit TopicWindowLabelService labelService,
            @Jit TopicWindowDAO windowDAO, @Jit TopicAlgorithm algorithm, ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
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
    @DisplayName("the labelling pass makes every window trace's topic the one its prototypes give, writing a row only "
            + "where the topic changes; a trace outside the window gets none, and a second pass by the same labeller "
            + "reads only stale traces and writes nothing")
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
        var assigner = new PrototypeAssigner(List.of(vectors.get(0), vectors.get(2)), List.of(0, 0),
                List.of(vectors.get(1)), dimensions, TopicsTestAlgorithm.config().getAssignMinCosine(),
                TopicsTestAlgorithm.config().getOtherGate());
        var labels = assigner.assign(TRACES.vectors());
        var expected = new HashMap<UUID, UUID>();
        for (int i = 0; i < BUILT; i++) {
            expected.put(TRACES.traceIds().get(i), labels[i] == TopicAlgorithm.OTHER ? TraceTopic.OTHER : topicId);
        }
        assertThat(expected.get(TRACES.traceIds().get(0))).isEqualTo(topicId);
        assertThat(expected.get(TRACES.traceIds().get(1))).isEqualTo(TraceTopic.OTHER);
        // An earlier run's insert labelled 30 traces right and 10 wrong.
        var earlier = id();
        var right = TRACES.traceIds().subList(3, 33);
        right.forEach(traceId -> insertRunRows(workspaceId, projectId, earlier, expected.get(traceId),
                List.of(traceId), List.of(createdAt(traceId))));
        var wrong = TRACES.traceIds().subList(33, 43);
        insertRunRows(workspaceId, projectId, earlier, id(), wrong, TRACES.createdAt().subList(33, 43));

        assertThat(data.topicProject(projectId).labelsComplete()).isFalse();

        assertThat(label(labelService, workspaceId, projectId, Instant.now().plus(Duration.ofMinutes(5))))
                .isEqualTo(LabelPassOutcome.COMPLETE);

        var project = data.topicProject(projectId);
        assertThat(project.labelsComplete()).isTrue();
        assertThat(project.labelsFingerprint()).isEqualTo(algorithm.labellerFingerprint(data.topics(projectId),
                project.otherVectors()));
        assertThat(data.traceTopics(workspaceId, projectId).stream()
                .collect(Collectors.toMap(TraceTopic::traceId, TraceTopic::topicId))).isEqualTo(expected);
        // A right topic keeps the earlier run's row; every other trace gets one row of this run.
        assertThat(rowsOfRun(workspaceId, projectId, runId))
                .containsOnlyKeys(TRACES.traceIds().stream().filter(traceId -> !right.contains(traceId)).toList())
                .allSatisfy((traceId, rows) -> assertThat(rows).isEqualTo(1));

        var rowsBefore = rowsOfRun(workspaceId, projectId, runId);
        long relabelReads = data.queryCount("topics:read_relabel", workspaceId);
        long staleReads = data.queryCount("topics:read_stale", workspaceId);
        assertThat(relabelReads).isPositive();
        var again = labelService.labelWindow(projectId,
                Instant.now().plus(Duration.ofMinutes(5)), LabelTrigger.RESUME)
                .contextWrite(TopicsTestData.context(workspace(workspaceId))).block();

        assertThat(again).isEqualTo(LabelPassOutcome.COMPLETE);
        assertThat(rowsOfRun(workspaceId, projectId, runId)).isEqualTo(rowsBefore);
        var maxRowsToRead = String.valueOf(opikConfig.getTopics().getRun().getRead().getMaxRowsToRead());
        // The first pass read both sweeps, the second only the stale one.
        assertThat(data.queryCount("topics:read_relabel", workspaceId)).isEqualTo(relabelReads);
        assertThat(data.queryCount("topics:read_stale", workspaceId)).isEqualTo(2 * staleReads);
        for (var page : List.of("topics:read_relabel", "topics:read_stale")) {
            assertThat(data.queryGuards(page, workspaceId)).containsExactly(List.of("2", maxRowsToRead, "100", "120"));
            // A page's memory is mostly the vector column, read in blocks of this size.
            assertThat(data.queryLogSettings(page, workspaceId, "optimize_read_in_order", "max_block_size",
                    "join_use_nulls"))
                    .containsExactly(List.of("0",
                            String.valueOf(opikConfig.getTopics().getRun().getRead().getMaxBlockSize()), "1"));
        }
    }

    @Test
    @DisplayName("a pass by the labeller the labels were completed with writes only stale traces: one with no topic, one "
            + "re-vectored since its topic, one whose topic is a tombstone; a changed list relabels the window and "
            + "writes only the topics that change; a save after a cut pass makes the next pass relabel again")
    void incrementalPasses() {
        var workspaceId = id().toString();
        var projectId = id();
        data.seed(workspaceId, projectId, 0, BUILT);
        var runId = id();
        var topicId = id();
        saveList(workspaceId, projectId, runId, topicId);
        assertThat(label(labelService, workspaceId, projectId, Instant.now().plus(Duration.ofMinutes(5))))
                .isEqualTo(LabelPassOutcome.COMPLETE);
        var first = latest(workspaceId, projectId);

        // A night that keeps the list: no topic for one trace, a new vector for another, a tombstone for a third.
        var unlabelled = id();
        copyVector(workspaceId, projectId, TRACES.traceIds().get(0), unlabelled);
        var revectored = TRACES.traceIds().get(2);
        data.relabel(workspaceId, projectId, List.of(revectored));
        var tombstoned = TRACES.traceIds().get(5);
        data.insertTraceTopic(workspaceId, projectId, tombstoned, createdAt(tombstoned), first.get(tombstoned),
                Instant.now(), 1);
        var sameList = id();
        saveNight(workspaceId, projectId, runId, sameList, List.of(topic(topicId, 0, 2)), 1);
        long rowsBefore = data.traceTopicRows(workspaceId, projectId);

        assertThat(label(labelService, workspaceId, projectId, Instant.now().plus(Duration.ofMinutes(5))))
                .isEqualTo(LabelPassOutcome.COMPLETE);

        assertThat(rowsOfRun(workspaceId, projectId, sameList)).containsOnlyKeys(unlabelled, revectored, tombstoned);
        assertThat(data.traceTopicRows(workspaceId, projectId)).isEqualTo(rowsBefore + 3);
        assertThat(latest(workspaceId, projectId)).containsEntry(unlabelled, first.get(TRACES.traceIds().get(0)))
                .containsEntry(tombstoned, first.get(tombstoned));

        // A night that moves the topic's prototypes: only the traces whose topic changes get a row.
        var moved = id();
        saveNight(workspaceId, projectId, sameList, moved, List.of(topic(topicId, 0, 1)), 2);
        var expected = expectedTopics(workspaceId, projectId, List.of(topic(topicId, 0, 1)), 2);
        var changed = expected.entrySet().stream()
                .filter(entry -> !entry.getValue().equals(latest(workspaceId, projectId).get(entry.getKey())))
                .map(Map.Entry::getKey).toList();
        assertThat(changed).isNotEmpty();

        assertThat(label(labelService, workspaceId, projectId, Instant.now().plus(Duration.ofMinutes(5))))
                .isEqualTo(LabelPassOutcome.COMPLETE);

        assertThat(latest(workspaceId, projectId)).isEqualTo(expected);
        assertThat(rowsOfRun(workspaceId, projectId, moved)).containsOnlyKeys(changed);

        // A pass cut after its first insert (one trace a page), then a save of the first list back: the labels are not
        // complete, so the save drops the fingerprint and the next pass relabels, though the fingerprint is the one
        // before the cut.
        var cutList = id();
        saveNight(workspaceId, projectId, moved, cutList, List.of(topic(topicId, 0, 2)), 1);
        var slow = spy(traceTopicsDAO);
        var calls = new AtomicInteger();
        doAnswer(invocation -> calls.incrementAndGet() > 1
                ? Mono.delay(Duration.ofSeconds(30)).then(Mono.<Long>empty())
                : invocation.callRealMethod()).when(slow).insert(any());
        assertThat(label(service(pageOf(1), windowDAO, slow), workspaceId, projectId, Instant.now().plusSeconds(3)))
                .isEqualTo(LabelPassOutcome.CUT);
        assertThat(calls).hasValueGreaterThan(1);
        var back = id();
        saveNight(workspaceId, projectId, cutList, back, List.of(topic(topicId, 0, 1)), 2);
        assertThat(data.topicProject(projectId).labelsFingerprint()).isNull();

        assertThat(label(labelService, workspaceId, projectId, Instant.now().plus(Duration.ofMinutes(5))))
                .isEqualTo(LabelPassOutcome.COMPLETE);

        assertThat(latest(workspaceId, projectId)).isEqualTo(expected);
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

        var window = windowDAO.countAndBounds(projectId, from(), to(), sliceTraces, 60)
                .contextWrite(TopicsTestData.context(workspaceId)).block();

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
    @DisplayName("the slice bounds of a large window come out exact under a memory guard that a sort of every "
            + "creation time in memory would trip: a million traces under 32 MiB, where sorting an array of their "
            + "times took 57 MiB")
    void sliceBoundsOfALargeWindowStayUnderTheGuard() {
        var workspaceId = id().toString();
        var projectId = id();
        int traces = 1_000_000;
        int sliceTraces = 25_000;
        // Three traces per second from the window's start, in random trace id order.
        clickHouseTemplate.nonTransaction(connection -> Mono.from(connection.createStatement("""
                INSERT INTO topic_vectors (workspace_id, project_id, task, trace_id, trace_created_at, task_version,
                    embed_model, embed_dimensions, text, vector)
                SELECT :workspace_id, :project_id, :task, toString(generateUUIDv7(number)),
                    parseDateTime64BestEffort(:window_from, 6, 'UTC') + toIntervalSecond(intDiv(number, 3)), 1, '', 0,
                    '', []
                FROM numbers(:traces)
                SETTINGS log_comment = 'topics_test:many_keys'
                """)
                .bind("workspace_id", workspaceId)
                .bind("project_id", projectId.toString())
                .bind("task", TopicEmbedding.SUMMARY_TASK)
                .bind("window_from", from().toString())
                .bind("traces", traces)
                .execute())
                .flatMap(result -> Mono.from(result.getRowsUpdated())))
                .block();
        var base = opikConfig.getTopics().getRun().getRead();
        var read = new TopicsConfig.RunReadConfig();
        read.setMaxRowsToRead(base.getMaxRowsToRead());
        read.setMaxMemoryUsage(32L * 1024 * 1024);
        read.setMaxThreads(base.getMaxThreads());
        read.setPriority(base.getPriority());
        read.setMaxBlockSize(base.getMaxBlockSize());
        read.setCountSlice(base.getCountSlice());
        var run = spy(opikConfig.getTopics().getRun());
        doReturn(read).when(run).getRead();
        var topics = spy(opikConfig.getTopics());
        doReturn(run).when(topics).getRun();

        var window = new TopicWindowDAO(connectionFactory, topics)
                .countAndBounds(projectId, from(), to(), sliceTraces, 60)
                .contextWrite(TopicsTestData.context(workspaceId)).block();

        // The k-th trace in creation order, from 1, was created (k - 1) / 3 seconds after the window's start.
        assertThat(window.traces()).isEqualTo(traces);
        assertThat(window.bounds()).isEqualTo(IntStream.rangeClosed(1, traces / sliceTraces)
                .mapToObj(j -> from().plusSeconds((j * sliceTraces - 1) / 3)).toList());
    }

    @Test
    @DisplayName("every live window trace gets its topic, whatever its keys hold: a relabelled trace (two task "
            + "versions), a tombstoned one (none), traces tied on a slice bound, and traces that already have a "
            + "wrong row of an older run or a wrong arrival row")
    void labellingPassOverEveryKeyShape() {
        var workspaceId = id().toString();
        var projectId = id();
        data.seed(workspaceId, projectId, 0, BUILT);
        var runId = id();
        var topicId = id();
        saveList(workspaceId, projectId, runId, topicId);
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
        assertThat(latest(workspaceId, projectId)).isEqualTo(expectedTopics(workspaceId, projectId,
                List.of(topic(topicId, 0, 2)), 1));
        assertThat(data.topicProject(projectId).labelsComplete()).isTrue();
    }

    @Test
    @DisplayName("a failed insert fails the pass: the pages before it stay and nothing is marked complete; the next "
            + "pass writes only the rest, every window trace ends with one row, and only then are the labels complete")
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
    @DisplayName("a vector that lands behind the cursor during a pass is labelled by its stale sweep, and the pass "
            + "completes")
    void vectorBehindTheCursorIsLabelled() {
        var workspaceId = id().toString();
        var projectId = id();
        data.seed(workspaceId, projectId, 0, BUILT);
        var runId = id();
        saveList(workspaceId, projectId, runId, id());
        // Lower than every trace id, so it is behind the first page's cursor; created with trace 0, in the first slice.
        var late = new UUID(0, 1);
        var inserts = spy(traceTopicsDAO);
        var calls = new AtomicInteger();
        // The insert runs after the page's labelling, on parallel(), so the copy is chained instead of blocking.
        doAnswer(invocation -> {
            var insert = (Mono<?>) invocation.callRealMethod();
            return calls.incrementAndGet() == 1
                    ? copyVectorLater(workspaceId, projectId, TRACES.traceIds().getFirst(), late).then(insert)
                    : insert;
        }).when(inserts).insert(any());

        assertThat(label(service(windowDAO, inserts), workspaceId, projectId,
                Instant.now().plus(Duration.ofMinutes(5)))).isEqualTo(LabelPassOutcome.COMPLETE);

        assertThat(rowsOfRun(workspaceId, projectId, runId))
                .containsOnlyKeys(Stream.concat(TRACES.traceIds().stream(), Stream.of(late)).toList())
                .allSatisfy((traceId, rows) -> assertThat(rows).isEqualTo(1));
        assertThat(data.topicProject(projectId).labelsComplete()).isTrue();
    }

    @Test
    @DisplayName("a trace re-vectored between a page's read and its insert stays stale: the pass's row is stamped with "
            + "the read time, so its stale sweep relabels the trace by the new vector")
    void vectorRewrittenBeforeTheInsertIsRelabelled() {
        var workspaceId = id().toString();
        var projectId = id();
        data.seed(workspaceId, projectId, 0, BUILT);
        var topicId = id();
        saveList(workspaceId, projectId, id(), topicId);
        var rewritten = new AtomicReference<UUID>();
        var inserts = spy(traceTopicsDAO);
        // Before the relabel sweep's first insert, one of its traces gets the vector of a trace of the other label:
        // trace 0 is a prototype of the topic, trace 1 the known-Other vector.
        doAnswer(invocation -> {
            var insert = (Mono<?>) invocation.callRealMethod();
            List<TraceTopic> rows = invocation.getArgument(0);
            if (rows.isEmpty() || !rewritten.compareAndSet(null, rows.getFirst().traceId())) {
                return insert;
            }
            var source = TRACES.traceIds().get(TraceTopic.OTHER.equals(rows.getFirst().topicId()) ? 0 : 1);
            return revectorLater(workspaceId, projectId, rows.getFirst().traceId(), source).then(insert);
        }).when(inserts).insert(any());

        assertThat(label(service(windowDAO, inserts), workspaceId, projectId,
                Instant.now().plus(Duration.ofMinutes(5)))).isEqualTo(LabelPassOutcome.COMPLETE);

        var expected = expectedTopics(workspaceId, projectId, List.of(topic(topicId, 0, 2)), 1);
        assertThat(latest(workspaceId, projectId)).containsEntry(rewritten.get(), expected.get(rewritten.get()))
                .isEqualTo(expected);
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

        var cancelled = service.labelWindow(projectId,
                Instant.now().plus(Duration.ofMinutes(5)), LabelTrigger.RESUME)
                .contextWrite(TopicsTestData.context(workspace(workspaceId)))
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
        doAnswer(invocation -> windowDAO.readPage(projectId, invocation.getArgument(1), invocation.getArgument(2), "",
                invocation.getArgument(4), invocation.getArgument(5), invocation.getArgument(6)))
                .when(stuck).readPage(any(), any(), any(), any(), anyInt(), anyBoolean(), anyLong());
        // Inserts write nothing, so the stuck read returns the same traces on every page.
        var noopInserts = spy(traceTopicsDAO);
        doReturn(Mono.just(0L)).when(noopInserts).insert(any());

        assertThat(
                label(service(stuck, noopInserts), workspaceId, projectId, Instant.now().plus(Duration.ofMinutes(5))))
                .isEqualTo(LabelPassOutcome.FAILED);

        // Every page is full of traces the slice never had.
        var next = new AtomicLong();
        var endless = spy(windowDAO);
        doAnswer(invocation -> Flux.range(0, invocation.<Integer>getArgument(4))
                .map(i -> new TopicWindowDAO.WindowVector(new UUID(0x7000_0000_0000_7000L
                        + (next.incrementAndGet() << 16), 0x8000_0000_0000_0000L), invocation.getArgument(1),
                        TopicsTestData.unit(0), null, true, Instant.now())))
                .when(endless).readPage(any(), any(), any(), any(), anyInt(), anyBoolean(), anyLong());

        assertThat(
                label(service(endless, noopInserts), workspaceId, projectId, Instant.now().plus(Duration.ofMinutes(5))))
                .isEqualTo(LabelPassOutcome.FAILED);
        // A bound at every 20th trace: the last slice holds the window's last trace alone.
        int slices = BUILT / SLICE + 1;
        int maxPages = (SLICE + PAGE - 1) / PAGE + 2;
        // The stuck pass inserted each slice's first page; the endless one every page of every slice; each in both
        // sweeps.
        verify(noopInserts, times(2 * (slices + slices * maxPages))).insert(any());
        assertThat(data.topicProject(projectId).labelsComplete()).isFalse();
    }

    @Test
    @DisplayName("over 120 nights of traffic, arrival with gaps and on both sides of the save, re-embeds, deletes, "
            + "births, retirements, merges, prototype and Other changes, and cut or failed passes, every complete pass "
            + "leaves each window trace with the topic a full relabel of the stored vectors gives it")
    void incrementalPassesMatchAFullRelabel() throws Exception {
        var simulation = new Simulation(new Random(11));
        simulation.start();
        for (int night = 1; night <= SIM_NIGHTS; night++) {
            simulation.night(night);
        }
        Files.writeString(Path.of("target", "topics-incremental-simulation.csv"), simulation.csv);

        // A cut or failed pass that is not resumed the same night is checked by the next night's pass.
        assertThat(simulation.checked + simulation.cutOrFailedPasses).isGreaterThanOrEqualTo(SIM_NIGHTS);
        assertThat(simulation.mismatches).isZero();
        assertThat(simulation.relabelNights).isPositive();
        assertThat(simulation.skippedRelabelNights).isPositive();
        assertThat(simulation.cutOrFailedPasses).isPositive();
    }

    private static final int SIM_NIGHTS = 120;
    private static final int SIM_THEMES = 12;
    /** Themes 0 to 9 can become topics; 10 and 11 stay Other. */
    private static final int SIM_TOPIC_THEMES = 10;
    private static final int SIM_TRACES_PER_DAY = 10;

    /**
     * One project over many nights, with synthetic vectors around {@link #SIM_THEMES} random theme centres. Each
     * night: traffic (arrival by the list of the time, or no arrival), re-embeds and deletes, a save of the list
     * (unchanged, or with a birth, a retirement, a merge, a prototype or an Other change), late traffic after the save,
     * then a pass, sometimes cut or failed, resumed now or the next night. Every complete pass is checked against a full
     * relabel: the labeller of the saved list over the window's stored vectors.
     */
    private final class Simulation {

        private final Random random;
        private final String workspaceId = id().toString();
        private final UUID projectId = id();
        private final float[][] centres = new float[SIM_THEMES][];
        private final TimeBasedEpochGenerator ids;
        private final Instant base = Instant.now().truncatedTo(ChronoUnit.DAYS)
                .minus(Duration.ofDays(SIM_NIGHTS + 2));
        /** Live traces: their creation time and task version. */
        private final Map<UUID, Map.Entry<Instant, Integer>> traces = new LinkedHashMap<>();
        private final List<Topic> topics = new ArrayList<>();
        private final Map<UUID, Integer> themeOf = new HashMap<>();
        private final TopicsConfig config;
        private final TopicWindowLabelService service;
        private final TopicsArrivalService arrival;
        private PrototypeVectors other;
        private UUID listRunId;
        private Instant savedAt;
        private final StringBuilder csv = new StringBuilder(
                "night,event,relabel,outcome,window_traces,pass_rows,day_rows,mismatches\n");
        private int checked;
        private long mismatches;
        private int relabelNights;
        private int skippedRelabelNights;
        private int cutOrFailedPasses;

        Simulation(Random random) {
            this.random = random;
            this.ids = Generators.timeBasedEpochGenerator(random);
            for (int theme = 0; theme < SIM_THEMES; theme++) {
                var centre = new float[TopicEmbedding.DIMENSIONS];
                for (int i = 0; i < centre.length; i++) {
                    centre[i] = (float) random.nextGaussian();
                }
                centres[theme] = TopicEmbedding.toUnit(centre);
            }
            // Pages of 40 and slices of 100: several pages and slices per sweep, at a pace 120 nights can afford.
            var run = spy(opikConfig.getTopics().getRun());
            doReturn(40).when(run).getLabelPageTraces();
            doReturn(100).when(run).getLabelSliceTraces();
            config = spy(opikConfig.getTopics());
            doReturn(run).when(config).getRun();
            service = service(config, windowDAO, traceTopicsDAO);
            arrival = new TopicsArrivalService(config, store(config, traceTopicsDAO), algorithm,
                    new TopicsArrivalMetrics(TopicsMetricsReader.meter(InMemoryMetricReader.create())));
        }

        /** Thirty days of traces, a discovery save of five topics, and its pass. */
        void start() {
            var history = new ArrayList<TopicVector>();
            for (int i = 0; i < 30 * SIM_TRACES_PER_DAY; i++) {
                history.add(newTrace(between(base.minus(Duration.ofDays(30)), base)));
            }
            vectorsDAO.insert(history).block();
            for (int theme = 0; theme < 5; theme++) {
                birth(theme, base);
            }
            other = new PrototypeVectors(List.of(near(SIM_TOPIC_THEMES, 0.2)));
            save(base, base.plus(Duration.ofHours(1)));
            pass(0, "discovery", true);
        }

        void night(int night) {
            var windowUntil = base.plus(Duration.ofDays(night));
            var previous = savedAt;
            var saveAt = windowUntil.plus(Duration.ofHours(1));
            long dayRows = data.traceTopicRows(workspaceId, projectId);
            // The day's traffic, labelled on arrival by the list saved the night before, or not at all.
            var traffic = new ArrayList<TopicVector>();
            for (int i = 0; i < SIM_TRACES_PER_DAY; i++) {
                traffic.add(newTrace(between(previous, saveAt.minus(Duration.ofMinutes(10)))));
            }
            var window = traces.entrySet().stream()
                    .filter(trace -> !trace.getValue().getKey().isBefore(windowUntil.minus(Duration.ofDays(30))))
                    .map(Map.Entry::getKey).toList();
            // Two re-embeds: a new task version with a new vector, maybe of another theme.
            for (int i = 0; i < 2 && !window.isEmpty(); i++) {
                traffic.add(reembed(window.get(random.nextInt(window.size()))));
            }
            write(traffic);
            // A delete: the vector goes, and the topic with it half of the time.
            if (!window.isEmpty()) {
                var deleted = window.get(random.nextInt(window.size()));
                var createdAt = traces.remove(deleted).getKey();
                data.insertVectorTombstones(workspaceId, projectId, List.of(deleted), 0);
                if (random.nextBoolean()) {
                    data.insertTraceTopic(workspaceId, projectId, deleted, createdAt, TraceTopic.OTHER,
                            Instant.now(), 1);
                }
            }
            var event = change(windowUntil);
            save(windowUntil, saveAt);
            // Late traffic: vectored after the save and before the pass, labelled by the new list on arrival.
            if (random.nextDouble() < 0.3) {
                var late = new ArrayList<TopicVector>();
                for (int i = 0; i < 3; i++) {
                    late.add(newTrace(between(saveAt.minus(Duration.ofMinutes(10)), saveAt)));
                }
                write(late);
            }
            dayRows = data.traceTopicRows(workspaceId, projectId) - dayRows;
            pass(night, event, false, dayRows);
        }

        /** The night's list change, applied to {@link #topics}; its name. */
        private String change(Instant night) {
            double draw = random.nextDouble();
            var uncovered = IntStream.range(0, SIM_TOPIC_THEMES).filter(theme -> !themeOf.containsValue(theme))
                    .boxed().toList();
            if (draw < 0.12 && !uncovered.isEmpty()) {
                birth(uncovered.get(random.nextInt(uncovered.size())), night);
                return "birth";
            }
            if (draw < 0.22 && topics.size() > 1) {
                var retired = topics.remove(random.nextInt(topics.size()));
                themeOf.remove(retired.id());
                return "retirement";
            }
            if (draw < 0.28 && topics.size() > 1) {
                var kept = random.nextInt(topics.size());
                var merged = topics.get(kept);
                var gone = topics.remove((kept + 1 + random.nextInt(topics.size() - 1)) % topics.size());
                themeOf.remove(gone.id());
                var vectors = Stream.concat(merged.vectors().vectors().stream(), gone.vectors().vectors().stream())
                        .limit(4).toList();
                topics.set(topics.indexOf(merged), merged.toBuilder().vectors(new PrototypeVectors(vectors)).build());
                return "merge";
            }
            if (draw < 0.35) {
                var index = random.nextInt(topics.size());
                var topic = topics.get(index);
                var vectors = new ArrayList<>(topic.vectors().vectors());
                if (vectors.size() > 1 && random.nextBoolean()) {
                    vectors.remove(random.nextInt(vectors.size()));
                } else {
                    vectors.add(near(themeOf.get(topic.id()), 0.25));
                }
                topics.set(index, topic.toBuilder().vectors(new PrototypeVectors(vectors)).build());
                return "prototypes";
            }
            if (draw < 0.38) {
                other = new PrototypeVectors(List.of(near(SIM_TOPIC_THEMES + random.nextInt(2), 0.2)));
                return "other";
            }
            return "same";
        }

        private void birth(int theme, Instant night) {
            var topic = Topic.builder()
                    .id(id())
                    .workspaceId(workspaceId)
                    .projectId(projectId)
                    .name("Theme " + theme)
                    .definition("Synthetic theme " + theme)
                    .born(LocalDate.ofInstant(night, ZoneOffset.UTC))
                    .vectors(new PrototypeVectors(IntStream.range(0, 1 + random.nextInt(2))
                            .mapToObj(i -> near(theme, 0.2)).toList()))
                    .build();
            topics.add(topic);
            themeOf.put(topic.id(), theme);
        }

        private void save(Instant windowUntil, Instant at) {
            var runId = id();
            template.inTransaction(WRITE, handle -> {
                var projectDAO = handle.attach(TopicProjectDAO.class);
                var topicDAO = handle.attach(TopicDAO.class);
                TopicsTestData.startRun(projectDAO, workspaceId, projectId, runId, listRunId == null
                        ? TopicProject.RunMode.DISCOVERY
                        : TopicProject.RunMode.UPDATE, at.minus(Duration.ofMinutes(1)));
                assertThat(projectDAO.saveList(workspaceId, projectId, listRunId, runId, at, windowUntil, other,
                        TopicEmbedding.MODEL)).isEqualTo(1);
                topicDAO.deleteTopicsByProjects(workspaceId, List.of(projectId));
                topicDAO.insertTopics(List.copyOf(topics));
                projectDAO.markSaved(workspaceId, projectId, runId, at);
                return null;
            });
            listRunId = runId;
            savedAt = at;
        }

        private void pass(int night, String event, boolean first) {
            pass(night, event, first, 0);
        }

        /**
         * The night's pass: one in twelve is cut at its first or second insert and one in twenty-five fails at an
         * insert; half of those are resumed at once, the rest by the next night's pass.
         */
        private void pass(int night, String event, boolean first, long dayRows) {
            var project = data.topicProject(projectId);
            boolean relabel = !algorithm.labellerFingerprint(data.topics(projectId), project.otherVectors())
                    .equals(project.labelsFingerprint());
            if (relabel) {
                relabelNights++;
            } else {
                skippedRelabelNights++;
            }
            long before = data.traceTopicRows(workspaceId, projectId);
            double draw = first ? 1 : random.nextDouble();
            LabelPassOutcome outcome;
            if (draw < 0.12) {
                int after = random.nextInt(2);
                var calls = new AtomicInteger();
                var broken = spy(traceTopicsDAO);
                boolean cut = draw < 0.08;
                doAnswer(invocation -> calls.incrementAndGet() <= after
                        ? invocation.callRealMethod()
                        : cut
                                ? Mono.delay(Duration.ofSeconds(30)).then(Mono.<Long>empty())
                                : Mono.error(new IllegalStateException("insert failed")))
                        .when(broken).insert(any());
                outcome = label(service(config, windowDAO, broken), workspaceId, projectId,
                        Instant.now().plusSeconds(cut ? 2 : 60));
                assertThat(outcome).as("night %d", night).isIn(LabelPassOutcome.COMPLETE,
                        cut ? LabelPassOutcome.CUT : LabelPassOutcome.FAILED);
                if (outcome != LabelPassOutcome.COMPLETE) {
                    cutOrFailedPasses++;
                    if (random.nextBoolean()) {
                        outcome = label(service, workspaceId, projectId, Instant.now().plus(Duration.ofMinutes(5)));
                        event += "+resumed";
                    }
                }
            } else {
                outcome = label(service, workspaceId, projectId, Instant.now().plus(Duration.ofMinutes(5)));
                assertThat(outcome).as("night %d", night).isEqualTo(LabelPassOutcome.COMPLETE);
            }
            long written = data.traceTopicRows(workspaceId, projectId) - before;
            int windowTraces = 0;
            long wrong = 0;
            if (outcome == LabelPassOutcome.COMPLETE) {
                var expected = expected();
                windowTraces = expected.size();
                var latest = latest(workspaceId, projectId);
                wrong = expected.entrySet().stream()
                        .filter(entry -> !entry.getValue().equals(latest.get(entry.getKey()))).count();
                assertThat(wrong).as("night %d, %s: traces whose topic is not a full relabel's", night, event)
                        .isZero();
                assertThat(data.topicProject(projectId).labelsComplete()).isTrue();
                checked++;
            }
            mismatches += wrong;
            csv.append("%d,%s,%s,%s,%d,%d,%d,%d%n".formatted(night, event, relabel, outcome.getValue(), windowTraces,
                    written, dayRows, wrong));
        }

        /** A full relabel: the saved list's labeller over the stored vectors of the pass's window. */
        private Map<UUID, UUID> expected() {
            var project = data.topicProject(projectId);
            var window = windowDAO.readWindow(projectId,
                    project.windowUntil().minus(Duration.ofDays(WINDOW_DAYS)), project.lastSuccessAt(), 100_000, 60)
                    .contextWrite(TopicsTestData.context(workspace(workspaceId)))
                    .collectList().block();
            var topicIds = algorithm.labeller(data.topics(projectId), project.otherVectors())
                    .label(window.stream().map(TopicWindowDAO.WindowRow::vector).toList());
            var expected = new HashMap<UUID, UUID>();
            for (int i = 0; i < window.size(); i++) {
                expected.put(window.get(i).traceId(), topicIds[i]);
            }
            return expected;
        }

        /** Writes the vectors, then gives four in five writes topics on arrival, as the vectors tick does. */
        private void write(List<TopicVector> vectors) {
            vectorsDAO.insert(vectors).block();
            if (random.nextDouble() < 0.8) {
                var workspace = TopicVectorsMetrics.Workspace.unnamed(workspaceId);
                var budget = TickBudget.of(Instant.now(), Duration.ofSeconds(30), Clock.systemUTC());
                var steps = arrival.loadLabellers(workspace, List.of(projectId), budget, ArrivalSteps.STARTED)
                        .flatMap(read -> arrival.label(workspace, vectors, budget, read)).block();
                assertThat(steps.stopped()).isFalse();
            }
        }

        private TopicVector newTrace(Instant createdAt) {
            var traceId = ids.construct(createdAt.toEpochMilli());
            traces.put(traceId, Map.entry(createdAt, 1));
            return vector(traceId, createdAt, 1);
        }

        private TopicVector reembed(UUID traceId) {
            var trace = traces.get(traceId);
            int version = trace.getValue() + 1;
            traces.put(traceId, Map.entry(trace.getKey(), version));
            return vector(traceId, trace.getKey(), version);
        }

        /** A trace of a random theme, from 0.67 to 0.9 cosine to its centre: many sit near assignMinCosine. */
        private TopicVector vector(UUID traceId, Instant createdAt, int version) {
            return TopicVector.builder()
                    .workspaceId(workspaceId)
                    .projectId(projectId)
                    .task(TopicEmbedding.SUMMARY_TASK)
                    .traceId(traceId)
                    .traceCreatedAt(createdAt)
                    .taskVersion(version)
                    .embedModel(TopicEmbedding.MODEL)
                    .embedDimensions(TopicEmbedding.DIMENSIONS)
                    .text("synthetic intent")
                    .vector(near(random.nextInt(SIM_THEMES), 0.5 + 0.6 * random.nextDouble()))
                    .build();
        }

        /** A unit vector about {@code spread} away from the theme's centre. */
        private float[] near(int theme, double spread) {
            var vector = new float[TopicEmbedding.DIMENSIONS];
            double scale = spread / Math.sqrt(vector.length);
            for (int i = 0; i < vector.length; i++) {
                vector[i] = centres[theme][i] + (float) (random.nextGaussian() * scale);
            }
            return TopicEmbedding.toUnit(vector);
        }

        private Instant between(Instant from, Instant to) {
            return from.plusMillis((long) (random.nextDouble() * Duration.between(from, to).toMillis()));
        }
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
            TopicsTestData.startRun(projectDAO, workspaceId, projectId, runId, TopicProject.RunMode.DISCOVERY,
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

    /** A topic whose prototypes are the vectors of {@link #TRACES} at {@code prototypes}. */
    private static Topic topic(UUID id, int... prototypes) {
        int dimensions = TopicEmbedding.DIMENSIONS;
        return Topic.builder()
                .id(id)
                .workspaceId("")
                .projectId(new UUID(0, 0))
                .name("Billing")
                .definition("Requests about invoices")
                .born(LocalDate.ofInstant(NIGHT, ZoneOffset.UTC))
                .vectors(new PrototypeVectors(Arrays.stream(prototypes)
                        .mapToObj(i -> Arrays.copyOfRange(TRACES.vectors(), i * dimensions, (i + 1) * dimensions))
                        .toList()))
                .build();
    }

    /**
     * A nightly save of {@code topics}, with the vector of trace {@code other} as the known-Other one, over the list of
     * {@code listRunId}: its topics are replaced, as a night's retirements, births and updates would.
     */
    private void saveNight(String workspaceId, UUID projectId, UUID listRunId, UUID runId, List<Topic> topics,
            int other) {
        var savedAt = NIGHT.plus(Duration.ofHours(2)).plusSeconds(nights.incrementAndGet());
        template.inTransaction(WRITE, handle -> {
            var projectDAO = handle.attach(TopicProjectDAO.class);
            var topicDAO = handle.attach(TopicDAO.class);
            TopicsTestData.startRun(projectDAO, workspaceId, projectId, runId, TopicProject.RunMode.UPDATE,
                    savedAt.minus(Duration.ofMinutes(1)));
            assertThat(projectDAO.saveList(workspaceId, projectId, listRunId, runId, savedAt, NIGHT,
                    topic(id(), other).vectors(), TopicEmbedding.MODEL)).isEqualTo(1);
            topicDAO.deleteTopicsByProjects(workspaceId, List.of(projectId));
            topicDAO.insertTopics(topics.stream()
                    .map(topic -> topic.toBuilder().workspaceId(workspaceId).projectId(projectId).build())
                    .toList());
            projectDAO.markSaved(workspaceId, projectId, runId, savedAt);
            return null;
        });
    }

    private final AtomicInteger nights = new AtomicInteger();

    /** Each window trace's topic by {@code topics} and the known-Other vector of trace {@code other}, read as stored. */
    private Map<UUID, UUID> expectedTopics(String workspaceId, UUID projectId, List<Topic> topics, int other) {
        var project = data.topicProject(projectId);
        var to = project.lastSuccessAt().isAfter(project.windowUntil())
                ? project.lastSuccessAt()
                : project.windowUntil();
        var window = windowDAO.readWindow(projectId, project.windowUntil().minus(Duration.ofDays(WINDOW_DAYS)), to,
                10_000, 60)
                .contextWrite(TopicsTestData.context(workspace(workspaceId)))
                .collectList().block();
        var topicIds = algorithm.labeller(topics, topic(id(), other).vectors())
                .label(window.stream().map(TopicWindowDAO.WindowRow::vector).toList());
        var expected = new HashMap<UUID, UUID>();
        for (int i = 0; i < window.size(); i++) {
            expected.put(window.get(i).traceId(), topicIds[i]);
        }
        return expected;
    }

    /** Each trace's topic, as readers see it: its latest live row's. */
    private Map<UUID, UUID> latest(String workspaceId, UUID projectId) {
        return data.traceTopics(workspaceId, projectId).stream()
                .collect(Collectors.toMap(TraceTopic::traceId, TraceTopic::topicId));
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
        return service.labelWindow(projectId, deadline, LabelTrigger.RUN)
                .contextWrite(TopicsTestData.context(workspace(workspaceId))).block();
    }

    private static TopicsMetrics.Workspace workspace(String workspaceId) {
        return new TopicsMetrics.Workspace(workspaceId, "Acme");
    }

    /** The label service on the given stores, as the app builds it. */
    private TopicWindowLabelService service(TopicWindowDAO windowDAO, TraceTopicsDAO traceTopicsDAO) {
        return service(opikConfig.getTopics(), windowDAO, traceTopicsDAO);
    }

    private TopicWindowLabelService service(TopicsConfig config, TopicWindowDAO windowDAO,
            TraceTopicsDAO traceTopicsDAO) {
        return new TopicWindowLabelService(config, windowDAO, store(config, traceTopicsDAO),
                TopicsMetricsReader.metrics(InMemoryMetricReader.create()), algorithm);
    }

    /** The app's config with {@code traces} a labelling page. */
    private TopicsConfig pageOf(int traces) {
        var run = spy(opikConfig.getTopics().getRun());
        doReturn(traces).when(run).getLabelPageTraces();
        var topics = spy(opikConfig.getTopics());
        doReturn(run).when(topics).getRun();
        return topics;
    }

    private TopicRunStore store(TopicsConfig config, TraceTopicsDAO traceTopicsDAO) {
        return new TopicRunStore(config, template, traceTopicsDAO, mock(ProjectService.class), ID_GENERATOR,
                TopicsMetricsReader.metrics(InMemoryMetricReader.create()), mock(WorkspaceNameService.class));
    }

    private static UUID id() {
        return ID_GENERATOR.generateId();
    }

    private static Instant createdAt(UUID traceId) {
        return TRACES.createdAt().get(TRACES.traceIds().indexOf(traceId));
    }

    /** A new trace {@code traceId} with the vector row of {@code source}: same time, text and vector. */
    private void copyVector(String workspaceId, UUID projectId, UUID source, UUID traceId) {
        copyVectorLater(workspaceId, projectId, source, traceId).block();
    }

    private Mono<Long> copyVectorLater(String workspaceId, UUID projectId, UUID source, UUID traceId) {
        return writeVectorLater(workspaceId, projectId, source, traceId, createdAt(source));
    }

    /** A newer vector row for trace {@code traceId}, with the vector of {@code source}: same time, new vector. */
    private Mono<Long> revectorLater(String workspaceId, UUID projectId, UUID traceId, UUID source) {
        return writeVectorLater(workspaceId, projectId, source, traceId, createdAt(traceId));
    }

    /** A vector row for trace {@code traceId}, created at {@code createdAt}, with the text and vector of {@code source}. */
    private Mono<Long> writeVectorLater(String workspaceId, UUID projectId, UUID source, UUID traceId,
            Instant createdAt) {
        return clickHouseTemplate.nonTransaction(connection -> Mono.from(connection.createStatement("""
                INSERT INTO topic_vectors (workspace_id, project_id, task, trace_id, trace_created_at, task_version,
                    embed_model, embed_dimensions, text, vector)
                SELECT workspace_id, project_id, task, :trace_id, parseDateTime64BestEffort(:created_at, 6, 'UTC'),
                    task_version, embed_model, embed_dimensions, text, vector
                FROM topic_vectors
                WHERE workspace_id = :workspace_id AND project_id = :project_id AND trace_id = :source
                SETTINGS log_comment = 'topics_test:write_vector'
                """)
                .bind("workspace_id", workspaceId)
                .bind("project_id", projectId.toString())
                .bind("source", source.toString())
                .bind("trace_id", traceId.toString())
                .bind("created_at", createdAt.toString())
                .execute())
                .flatMap(result -> Mono.from(result.getRowsUpdated())));
    }
}
