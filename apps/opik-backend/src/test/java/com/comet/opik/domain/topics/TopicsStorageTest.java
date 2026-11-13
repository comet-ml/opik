package com.comet.opik.domain.topics;

import com.comet.opik.api.events.ProjectsDeleted;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.PostCutoverTraces;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.v1.events.TopicsProjectDeletedListener;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.redis.testcontainers.RedisContainer;
import org.jdbi.v3.core.mapper.reflect.ConstructorMapper;
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

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The topics tables and their reads against real ClickHouse and MySQL: the {@code topic_vectors} window reads, the
 * {@code trace_topics} read shape, and the project-delete cleanup. No run builds here: tests seed vectors and rows
 * directly and call the DAOs and the listener.
 */
@DisplayName("topics storage")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TopicsStorageTest {

    private static final int BUILT = 120;
    private static final int WINDOW_DAYS = 30;
    private static final Instant NIGHT = Instant.parse("2026-10-15T00:00:00Z");
    private static final TopicsTestData.Traces TRACES = TopicsTestData.Traces.synthetic(BUILT, NIGHT, 7);
    /** One trace every 72 minutes over the window: a window larger than {@link #SAMPLE}. */
    private static final int SPREAD = 600;
    private static final int SAMPLE = 150;
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
        try {
            PostCutoverTraces.migrate(clickHouse);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }

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
    private TopicWindowDAO windowDAO;
    private TraceTopicsDAO traceTopicsDAO;
    private TopicWindowLabelService labelService;
    private TopicsProjectDeletedListener projectDeletedListener;
    private TopicsTestData data;
    private TopicsTestData spreadData;

    @BeforeAll
    void beforeAll(OpikConfiguration opikConfig, TransactionTemplate template,
            TransactionTemplateAsync clickHouseTemplate, @Jit TopicVectorsDAO vectorsDAO,
            @Jit TopicWindowDAO windowDAO, @Jit TraceTopicsDAO traceTopicsDAO,
            @Jit TopicWindowLabelService labelService, @Jit TopicsProjectDeletedListener projectDeletedListener) {
        this.opikConfig = opikConfig;
        this.template = template;
        this.clickHouseTemplate = clickHouseTemplate;
        this.vectorsDAO = vectorsDAO;
        this.windowDAO = windowDAO;
        this.traceTopicsDAO = traceTopicsDAO;
        this.labelService = labelService;
        this.projectDeletedListener = projectDeletedListener;
        this.data = new TopicsTestData(template, clickHouseTemplate, vectorsDAO, TRACES);
        this.spreadData = new TopicsTestData(template, clickHouseTemplate, vectorsDAO, SPREAD_TRACES);
    }

    @Test
    @DisplayName("a trace_topics read drops a trace whose latest row is a tombstone, and keeps one whose tombstone is older")
    void traceTopicsReadsSkipTombstones() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        var tombstoned = TRACES.traceIds().get(0);
        var revived = TRACES.traceIds().get(1);
        var untouched = TRACES.traceIds().get(2);
        var topicId = UUID.randomUUID();
        var createdAt = Instant.parse("2026-11-01T10:00:00Z");
        var earlier = Instant.parse("2026-11-02T00:00:00Z");
        var later = Instant.parse("2026-11-03T00:00:00Z");
        // The tombstone comes after the live row, so the trace is gone.
        data.insertTraceTopic(workspaceId, projectId, tombstoned, createdAt, topicId, earlier, 0);
        data.insertTraceTopic(workspaceId, projectId, tombstoned, createdAt, topicId, later, 1);
        // The tombstone comes before the live row: the dedup keeps the live row, so the trace stays.
        data.insertTraceTopic(workspaceId, projectId, revived, createdAt, topicId, earlier, 1);
        data.insertTraceTopic(workspaceId, projectId, revived, createdAt, topicId, later, 0);
        data.insertTraceTopic(workspaceId, projectId, untouched, createdAt, TraceTopic.OTHER, earlier, 0);

        assertThat(data.traceTopics(workspaceId, projectId))
                .extracting(TraceTopic::traceId, TraceTopic::topicId)
                .containsExactlyInAnyOrder(tuple(revived, topicId), tuple(untouched, TraceTopic.OTHER));
    }

    @Test
    @DisplayName("deleting a project deletes its topics, prototypes, state and runs")
    void projectDeleted() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        var kept = UUID.randomUUID();
        data.savedList(workspaceId, projectId, NIGHT, BUILT - 10);
        data.savedList(workspaceId, kept, NIGHT, BUILT - 10);

        projectDeletedListener.onProjectsDeleted(new ProjectsDeleted(Set.of(projectId), workspaceId, "test"));

        assertThat(data.topics(projectId)).isEmpty();
        assertThat(data.prototypes(projectId)).isEmpty();
        assertThat(data.state(projectId)).isNull();
        assertThat(data.runs(projectId)).isEmpty();
        assertThat(data.topics(kept)).isNotEmpty();
        assertThat(data.runs(kept)).hasSize(1);
    }

    @Test
    @DisplayName("the window count runs on 2 threads under the row guard at a background priority; the window read "
            + "stops after 120 s")
    void readsCarryTheirGuards() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, BUILT);

        // As a first-build run reads: the count of its projects under 60 s, a run's window under 120 s.
        assertThat(windowDAO.countByProject(workspaceId, Set.of(projectId), from(), to(), 60)
                .collectList().block())
                .containsExactly(new TopicWindowDAO.ProjectCount(projectId, BUILT));
        assertThat(windowDAO.readWindow(workspaceId, projectId, from(), to(), BUILT, 120).collectList().block())
                .hasSize(BUILT);

        var maxRowsToRead = String.valueOf(opikConfig.getTopics().getVectors().getFind().getMaxRowsToRead());
        assertThat(guards("topics:count_window", workspaceId))
                .containsExactly(List.of("2", maxRowsToRead, "100", "60"));
        // The count's memory bound rests on these two.
        assertThat(settings("topics:count_window", workspaceId, "optimize_aggregation_in_order", "max_block_size"))
                .containsExactly(List.of("1", "8192"));
        assertThat(guards("topics:read_window", workspaceId))
                .containsExactly(List.of("2", maxRowsToRead, "", "120"));
    }

    @Test
    @DisplayName("the window count and the window read skip the vectors of deleted traces")
    void deletedVectorsAreNotRead() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, BUILT);
        var deleted = TRACES.traceIds().subList(0, 10);
        // deleteByKeys runs deleteByTraceIds, the trace-delete cascade's delete, as the system user.
        vectorsDAO.deleteByKeys(workspaceId, deleted.stream()
                .map(traceId -> TopicTraceKey.builder().projectId(projectId).traceId(traceId).build())
                .toList()).block();

        assertThat(windowDAO.countByProject(workspaceId, Set.of(projectId), from(), to(), 60).collectList().block())
                .containsExactly(new TopicWindowDAO.ProjectCount(projectId, BUILT - deleted.size()));
        assertThat(windowDAO.readWindow(workspaceId, projectId, from(), to(), BUILT, 60).collectList().block())
                .extracting(TopicWindowRow::traceId)
                .containsExactlyInAnyOrderElementsOf(TRACES.traceIds().subList(deleted.size(), BUILT));
    }

    @Test
    @DisplayName("the window reads drop a vector whose latest row is a tombstone, and keep one whose tombstone is older")
    void tombstonedVectorsAreNotRead() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, BUILT);
        var tombstoned = TRACES.traceIds().subList(0, 10);
        var revived = TRACES.traceIds().subList(10, 20);
        // The tombstone comes after the live row, so the key is gone.
        data.insertVectorTombstones(workspaceId, projectId, tombstoned, 3_600);
        // The tombstone comes before the live row: the dedup keeps the live row, so the key stays.
        data.insertVectorTombstones(workspaceId, projectId, revived, -3_600);

        assertThat(windowDAO.countByProject(workspaceId, Set.of(projectId), from(), to(), 60).collectList().block())
                .containsExactly(new TopicWindowDAO.ProjectCount(projectId, BUILT - tombstoned.size()));
        assertThat(windowDAO.readWindow(workspaceId, projectId, from(), to(), BUILT, 60).collectList().block())
                .extracting(TopicWindowRow::traceId)
                .containsExactlyInAnyOrderElementsOf(TRACES.traceIds().subList(tombstoned.size(), BUILT));
    }

    @Test
    @DisplayName("a tombstone stamped at the same time as its live row loses, in the count and in both reads")
    void tiedTombstoneLoses() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, BUILT);
        var tied = TRACES.traceIds().subList(0, 10);
        // A merge would keep one row of each tied key; the queries are checked on the rows as written.
        data.withoutMerges(() -> {
            data.insertTiedVectorTombstones(workspaceId, projectId, tied);

            assertThat(windowDAO.countByProject(workspaceId, Set.of(projectId), from(), to(), 60).collectList()
                    .block()).containsExactly(new TopicWindowDAO.ProjectCount(projectId, BUILT));
            assertThat(windowDAO.readWindow(workspaceId, projectId, from(), to(), BUILT, 60).collectList().block())
                    .extracting(TopicWindowRow::traceId).containsAll(tied).hasSize(BUILT);
            assertThat(windowDAO.readUnlabelled(workspaceId, projectId, UUID.randomUUID(), from(), to(), "", BUILT,
                    60).collectList().block())
                    .extracting(TopicWindowDAO.WindowVector::traceId).containsAll(tied).hasSize(BUILT);
        });
    }

    @Test
    @DisplayName("the window count is exact per project asked for, at the minVectors boundary too: a relabelled trace "
            + "counts once, a trace counts while one of its task versions is live, and a project with none is left out")
    void countIsExactAtTheBoundary() {
        var workspaceId = UUID.randomUUID().toString();
        int minVectors = opikConfig.getTopics().getBuild().getMinVectors();
        var below = UUID.randomUUID();
        var at = UUID.randomUUID();
        var above = UUID.randomUUID();
        data.seed(workspaceId, below, 0, minVectors - 1);
        data.seed(workspaceId, at, 0, minVectors);
        data.seed(workspaceId, above, 0, minVectors + 1);
        var gone = UUID.randomUUID();
        data.seed(workspaceId, gone, 0, 10);
        data.insertVectorTombstones(workspaceId, gone, TRACES.traceIds().subList(0, 10), 3_600);
        var mixed = UUID.randomUUID();
        data.seed(workspaceId, mixed, 0, 12);
        var ids = TRACES.traceIds();
        // Traces 0 to 3 get version 2. Version 1 of traces 0 and 1 is deleted: they stay, by version 2.
        data.relabel(workspaceId, mixed, ids.subList(0, 4));
        data.insertVersionOneTombstones(workspaceId, mixed, ids.subList(0, 2), 3_600);
        // Both versions of trace 2 are deleted, and the only version of traces 4 and 5.
        data.insertVectorTombstones(workspaceId, mixed, List.of(ids.get(2)), 3_600);
        data.insertVersionOneTombstones(workspaceId, mixed, ids.subList(4, 6), 3_600);
        // Trace 6's tombstone is older than its live row: it stays.
        data.insertVersionOneTombstones(workspaceId, mixed, List.of(ids.get(6)), -3_600);

        assertThat(windowDAO.countByProject(workspaceId, Set.of(below, at, above, gone, mixed), from(), to(), 60)
                .collectList().block())
                .containsExactlyInAnyOrder(
                        new TopicWindowDAO.ProjectCount(below, minVectors - 1),
                        new TopicWindowDAO.ProjectCount(at, minVectors),
                        new TopicWindowDAO.ProjectCount(above, minVectors + 1),
                        new TopicWindowDAO.ProjectCount(mixed, 12 - 3));
        assertThat(windowDAO.countByProject(workspaceId, Set.of(below, gone), from(), to(), 60).collectList().block())
                .containsExactly(new TopicWindowDAO.ProjectCount(below, minVectors - 1));
    }

    @Test
    @DisplayName("a window larger than the limit is read as an even sample of it, not as its newest traces")
    void sampleIsEven() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        spreadData.seed(workspaceId, projectId, 0, SPREAD);

        var sample = windowDAO.readWindow(workspaceId, projectId, from(), to(), SAMPLE, 120).collectList().block();

        assertThat(sample).hasSize(SAMPLE);
        // About 5 traces a day; the newest 150 would cover the last 7.5 days only.
        assertThat(sample.stream().map(row -> LocalDate.ofInstant(row.traceCreatedAt(), ZoneOffset.UTC)).distinct())
                .hasSizeGreaterThanOrEqualTo(26);
        var middle = from().plus(Duration.ofDays(WINDOW_DAYS / 2));
        assertThat(sample.stream().filter(row -> row.traceCreatedAt().isBefore(middle)).count())
                .isBetween((long) SAMPLE * 35 / 100, (long) SAMPLE * 65 / 100);
    }

    @Test
    @DisplayName("the sample is the same on every read, and a window a day later keeps most of it")
    void sampleIsStable() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        spreadData.seed(workspaceId, projectId, 0, SPREAD);

        var first = sampleIds(workspaceId, projectId, from(), to(), SAMPLE);

        assertThat(sampleIds(workspaceId, projectId, from(), to(), SAMPLE)).isEqualTo(first);
        // The window loses its first day, about 20 traces of which about 5 were sampled.
        var later = new HashSet<>(sampleIds(workspaceId, projectId, from().plus(Duration.ofDays(1)),
                to().plus(Duration.ofDays(1)), SAMPLE));
        later.retainAll(first);
        assertThat(later).hasSizeGreaterThanOrEqualTo(SAMPLE - 20);
    }

    @Test
    @DisplayName("a window of the limit's size or less is read whole")
    void smallWindowIsReadWhole() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        spreadData.seed(workspaceId, projectId, 0, SPREAD);

        assertThat(sampleIds(workspaceId, projectId, from(), to(), SPREAD))
                .containsExactlyInAnyOrderElementsOf(SPREAD_TRACES.traceIds());
        assertThat(sampleIds(workspaceId, projectId, from(), to(), SPREAD + 1))
                .containsExactlyInAnyOrderElementsOf(SPREAD_TRACES.traceIds());
    }

    @Test
    @DisplayName("the labelling pass gives every window trace exactly one row of the saved run, by its prototypes; a "
            + "trace that has one keeps it, a trace outside the window gets none, and a second pass has nothing left")
    void labellingPassLabelsTheWindow() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, BUILT);
        // After the window.
        spreadData.seed(workspaceId, projectId, 0, 20, NIGHT.plus(Duration.ofDays(WINDOW_DAYS + 1)));
        var runId = UUID.randomUUID();
        var topicId = UUID.randomUUID();
        int dimensions = TopicVectorsTickService.EMBED_DIMENSIONS;
        // Trace 0 and trace 2 are the topic's prototypes, trace 1 a known-Other one.
        var vectors = new ArrayList<float[]>();
        for (int i = 0; i < 3; i++) {
            vectors.add(Arrays.copyOfRange(TRACES.vectors(), i * dimensions, (i + 1) * dimensions));
        }
        template.inTransaction(WRITE, handle -> {
            var topicDAO = handle.attach(TopicDAO.class);
            topicDAO.insertState(TopicProjectState.builder()
                    .projectId(projectId)
                    .workspaceId(workspaceId)
                    .rebuilds(1)
                    .lastRunId(runId)
                    .windowUntil(NIGHT)
                    .build());
            topicDAO.insertTopics(List.of(Topic.builder()
                    .id(topicId)
                    .workspaceId(workspaceId)
                    .projectId(projectId)
                    .name("Billing")
                    .definition("Requests about invoices")
                    .born(LocalDate.ofInstant(NIGHT, ZoneOffset.UTC))
                    .build()));
            topicDAO.insertPrototypes(List.of(
                    prototype(workspaceId, projectId, 0, topicId, vectors.get(0)),
                    prototype(workspaceId, projectId, 1, null, vectors.get(1)),
                    prototype(workspaceId, projectId, 2, topicId, vectors.get(2))));
            return null;
        });
        // The run's own insert, or an earlier cut pass, labelled 30 traces.
        var labelledBefore = UUID.randomUUID();
        traceTopicsDAO.insert(IntStream.range(3, 33)
                .mapToObj(i -> TraceTopic.builder()
                        .workspaceId(workspaceId)
                        .projectId(projectId)
                        .traceId(TRACES.traceIds().get(i))
                        .traceCreatedAt(TRACES.createdAt().get(i))
                        .topicId(labelledBefore)
                        .source(TraceTopic.Source.RUN)
                        .runId(runId.toString())
                        .build())
                .toList()).block();

        assertThat(data.state(projectId).labelsComplete()).isFalse();

        var pass = labelService.labelWindow(workspaceId, projectId, Instant.now().plus(Duration.ofMinutes(5)),
                TopicWindowLabelService.Trigger.RUN, 7, 20);

        assertThat(pass.complete()).isTrue();
        assertThat(data.state(projectId).labelsComplete()).isTrue();
        assertThat(pass.runId()).isEqualTo(runId);
        assertThat(pass.windowTraces()).isEqualTo(BUILT);
        assertThat(pass.labelled()).isEqualTo(BUILT - 30);
        assertThat(rowsOfRun(workspaceId, projectId, runId))
                .containsOnlyKeys(TRACES.traceIds())
                .allSatisfy((traceId, rows) -> assertThat(rows).isEqualTo(1));
        var assigner = new PrototypeAssigner(List.of(vectors.get(0), vectors.get(2)), List.of(0, 0),
                List.of(vectors.get(1)), dimensions);
        var labels = assigner.assign(TRACES.vectors());
        var expected = new HashMap<UUID, UUID>();
        for (int i = 0; i < BUILT; i++) {
            expected.put(TRACES.traceIds().get(i), i >= 3 && i < 33
                    ? labelledBefore
                    : labels[i] == PrototypeAssigner.OTHER ? TraceTopic.OTHER : topicId);
        }
        assertThat(expected.get(TRACES.traceIds().get(0))).isEqualTo(topicId);
        assertThat(expected.get(TRACES.traceIds().get(1))).isEqualTo(TraceTopic.OTHER);
        assertThat(data.traceTopics(workspaceId, projectId).stream()
                .collect(Collectors.toMap(TraceTopic::traceId, TraceTopic::topicId))).isEqualTo(expected);

        var again = labelService.labelWindow(workspaceId, projectId, Instant.now().plus(Duration.ofMinutes(5)),
                TopicWindowLabelService.Trigger.RESUME, 7, 20);

        assertThat(again.complete()).isTrue();
        assertThat(again.labelled()).isZero();
        var maxRowsToRead = String.valueOf(opikConfig.getTopics().getVectors().getFind().getMaxRowsToRead());
        assertThat(guards("topics:read_unlabelled", workspaceId))
                .containsExactly(List.of("2", maxRowsToRead, "100", "120"));
    }

    @Test
    @DisplayName("the labels are marked complete only for the run the state names: an older run's pass marks nothing")
    void labelsCompleteOnlyForTheLastRun() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        data.savedList(workspaceId, projectId, NIGHT, BUILT);
        var lastRunId = data.state(projectId).lastRunId();

        int older = template.inTransaction(WRITE, handle -> handle.attach(TopicDAO.class)
                .markLabelsComplete(workspaceId, projectId, UUID.randomUUID()));

        assertThat(older).isZero();
        assertThat(data.state(projectId).labelsComplete()).isFalse();

        int last = template.inTransaction(WRITE, handle -> handle.attach(TopicDAO.class)
                .markLabelsComplete(workspaceId, projectId, lastRunId));

        assertThat(last).isEqualTo(1);
        assertThat(data.state(projectId).labelsComplete()).isTrue();
    }

    @Test
    @DisplayName("the passes to resume are the workspaces' projects whose labels are not complete, the oldest save "
            + "first, whatever order they were saved in")
    void labelsIncompleteOldestFirst() {
        var workspaceId = UUID.randomUUID().toString();
        var newest = UUID.randomUUID();
        var oldest = UUID.randomUUID();
        var middle = UUID.randomUUID();
        var complete = UUID.randomUUID();
        data.savedList(workspaceId, newest, NIGHT, BUILT);
        data.savedList(workspaceId, oldest, NIGHT.minus(Duration.ofDays(3)), BUILT);
        data.savedList(workspaceId, middle, NIGHT.minus(Duration.ofDays(1)), BUILT);
        data.savedList(workspaceId, complete, NIGHT.minus(Duration.ofDays(4)), BUILT);
        var completeRunId = data.state(complete).lastRunId();
        template.inTransaction(WRITE, handle -> handle.attach(TopicDAO.class)
                .markLabelsComplete(workspaceId, complete, completeRunId));
        data.savedList(UUID.randomUUID().toString(), UUID.randomUUID(), NIGHT.minus(Duration.ofDays(5)), BUILT);

        var unfinished = template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicDAO.class).findLabelsIncomplete(List.of(workspaceId)));

        assertThat(unfinished).extracting(TopicDAO.ProjectKey::projectId).containsExactly(oldest, middle, newest);
    }

    @Test
    @DisplayName("the nightly save: topic updates, the state's compare-and-set, the live-topic reads, and the deletes "
            + "of a list with no live topic")
    void nightlySave() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        var other = UUID.randomUUID();
        data.savedList(workspaceId, projectId, NIGHT, BUILT);
        data.savedList(workspaceId, other, NIGHT, BUILT);
        var state = data.state(projectId);
        var topic = data.topics(projectId).getFirst();

        assertThat(liveProjects(workspaceId, List.of(projectId, other, UUID.randomUUID())))
                .containsExactlyInAnyOrder(projectId, other);
        assertThat(liveProjects(List.of(workspaceId)))
                .containsExactlyInAnyOrder(new TopicDAO.ProjectKey(workspaceId, projectId),
                        new TopicDAO.ProjectKey(workspaceId, other));

        var later = state.toBuilder().rebuilds(2).lastRunId(UUID.randomUUID())
                .lastSuccessAt(NIGHT.plus(Duration.ofDays(1)))
                .windowUntil(NIGHT.plus(Duration.ofDays(1))).labelsComplete(false).build();
        int stale = template.inTransaction(WRITE, handle -> handle.attach(TopicDAO.class)
                .updateState(later, UUID.randomUUID()));
        assertThat(stale).isZero();
        assertThat(data.state(projectId)).isEqualTo(state);

        int saved = template.inTransaction(WRITE, handle -> handle.attach(TopicDAO.class)
                .updateState(later, state.lastRunId()));
        assertThat(saved).isEqualTo(1);
        assertThat(data.state(projectId)).isEqualTo(later);

        var retired = topic.toBuilder().name("Renamed").definition("Changed").retiredAt(LocalDate.of(2026, 10, 16))
                .retiredReason("empty").lowNights(3).size(0).build();
        var updated = template.inTransaction(WRITE, handle -> handle.attach(TopicDAO.class)
                .updateTopics(List.of(retired)));
        assertThat(updated).containsExactly(1);
        assertThat(data.topics(projectId)).isEmpty();
        assertThat(allTopics(workspaceId, projectId)).singleElement()
                .isEqualTo(retired.toBuilder().name(topic.name()).definition(topic.definition()).build());
        assertThat(liveProjects(workspaceId, List.of(projectId, other))).containsExactly(other);
        assertThat(liveProjects(List.of(workspaceId)))
                .containsExactly(new TopicDAO.ProjectKey(workspaceId, other));

        template.inTransaction(WRITE, handle -> {
            var topicDAO = handle.attach(TopicDAO.class);
            assertThat(topicDAO.deletePrototypes(workspaceId, projectId)).isEqualTo(2);
            assertThat(topicDAO.deleteState(workspaceId, projectId)).isEqualTo(1);
            return null;
        });
        assertThat(data.prototypes(projectId)).isEmpty();
        assertThat(data.state(projectId)).isNull();
        assertThat(data.prototypes(other)).hasSize(2);
        assertThat(data.state(other)).isNotNull();
    }

    @Test
    @DisplayName("a project is paused when its last 3 runs failed and the latest started since the given time")
    void pausedProjects() {
        var workspaceId = UUID.randomUUID().toString();
        var since = NIGHT.minus(Duration.ofDays(7));
        var paused = UUID.randomUUID();
        var savedLast = UUID.randomUUID();
        var tooOld = UUID.randomUUID();
        var twoFailures = UUID.randomUUID();
        var savedBefore = UUID.randomUUID();
        insertRuns(workspaceId, paused, NIGHT, TopicRun.Status.FAILED, TopicRun.Status.FAILED, TopicRun.Status.FAILED);
        insertRuns(workspaceId, savedLast, NIGHT, TopicRun.Status.FAILED, TopicRun.Status.FAILED,
                TopicRun.Status.SAVED);
        insertRuns(workspaceId, tooOld, since.minus(Duration.ofDays(1)), TopicRun.Status.FAILED,
                TopicRun.Status.FAILED, TopicRun.Status.FAILED);
        insertRuns(workspaceId, twoFailures, NIGHT, TopicRun.Status.FAILED, TopicRun.Status.FAILED);
        insertRuns(workspaceId, savedBefore, NIGHT, TopicRun.Status.SAVED, TopicRun.Status.FAILED,
                TopicRun.Status.FAILED, TopicRun.Status.FAILED);

        var found = template.inTransaction(READ_ONLY, handle -> handle.attach(TopicRunDAO.class)
                .findPausedProjectIds(workspaceId, List.of(paused, savedLast, tooOld, twoFailures, savedBefore), 3,
                        since));

        assertThat(found).containsExactlyInAnyOrder(paused, savedBefore);
    }

    @Test
    @DisplayName("of the project ids asked for, only those of the workspace's existing projects are returned")
    void existingProjectIds() {
        var workspaceId = UUID.randomUUID().toString();
        var existing = UUID.randomUUID();
        var elsewhere = UUID.randomUUID();
        data.project(workspaceId, existing);
        data.project(UUID.randomUUID().toString(), elsewhere);

        var found = template.inTransaction(READ_ONLY, handle -> handle.attach(TopicDAO.class)
                .findExistingProjectIds(workspaceId, List.of(existing, elsewhere, UUID.randomUUID())));

        assertThat(found).containsExactly(existing);
    }

    @Test
    @DisplayName("a night's record: its row as it starts and ends, its project rows, and a second start of the same "
            + "night that replaces both")
    void nightRecord() {
        var night = LocalDate.of(2026, 1, 15);
        var startedAt = Instant.parse("2026-01-15T02:00:00Z");
        var finishedAt = startedAt.plus(Duration.ofHours(1));
        var workspaceId = UUID.randomUUID().toString();
        var row = TopicNightProject.builder()
                .id(UUID.randomUUID())
                .night(night)
                .workspaceId(workspaceId)
                .projectId(UUID.randomUUID())
                .decision("skip")
                .reason("small")
                .build();
        template.inTransaction(WRITE, handle -> {
            var nightDAO = handle.attach(TopicNightDAO.class);
            nightDAO.start(night, startedAt);
            nightDAO.insertProjects(List.of(row));
            return null;
        });
        assertThat(findNight(night)).isEqualTo(new TopicNight(night, startedAt, null, TopicNight.Outcome.RUNNING));

        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicNightDAO.class)
                    .end(new TopicNight(night, startedAt, finishedAt, TopicNight.Outcome.FINISHED));
            return null;
        });
        assertThat(findNight(night)).isEqualTo(new TopicNight(night, startedAt, finishedAt,
                TopicNight.Outcome.FINISHED));
        assertThat(nightProjects(night))
                .containsExactly(row);

        var again = startedAt.plus(Duration.ofHours(5));
        template.inTransaction(WRITE, handle -> {
            var nightDAO = handle.attach(TopicNightDAO.class);
            nightDAO.start(night, again);
            nightDAO.deleteProjects(night);
            return null;
        });
        assertThat(findNight(night)).isEqualTo(new TopicNight(night, again, null, TopicNight.Outcome.RUNNING));
        assertThat(nightProjects(night))
                .isEmpty();
    }

    private List<TopicNightProject> nightProjects(LocalDate night) {
        return template.inTransaction(READ_ONLY, handle -> handle.attach(TopicNightDAO.class).findProjects(night));
    }

    private TopicNight findNight(LocalDate night) {
        return template.inTransaction(READ_ONLY, handle -> handle.attach(TopicNightDAO.class).findByNight(night));
    }

    private List<UUID> liveProjects(String workspaceId, List<UUID> projectIds) {
        return template.inTransaction(READ_ONLY, handle -> handle.attach(TopicDAO.class)
                .findProjectsWithLiveTopics(workspaceId, projectIds));
    }

    private List<TopicDAO.ProjectKey> liveProjects(List<String> workspaceIds) {
        return template.inTransaction(READ_ONLY, handle -> handle.attach(TopicDAO.class)
                .findProjectsWithLiveTopics(workspaceIds));
    }

    /** Every topic row of the project, retired ones included. */
    private List<Topic> allTopics(String workspaceId, UUID projectId) {
        return template.inTransaction(READ_ONLY, handle -> handle
                .createQuery("SELECT * FROM topics WHERE workspace_id = :workspaceId AND project_id = :projectId")
                .bind("workspaceId", workspaceId)
                .bind("projectId", projectId.toString())
                .map(ConstructorMapper.of(Topic.class))
                .list());
    }

    /** Runs of the project with these statuses, oldest first, one hour apart, the last started at {@code latest}. */
    private void insertRuns(String workspaceId, UUID projectId, Instant latest, TopicRun.Status... statuses) {
        template.inTransaction(WRITE, handle -> {
            var runDAO = handle.attach(TopicRunDAO.class);
            for (int i = 0; i < statuses.length; i++) {
                var startedAt = latest.minus(Duration.ofHours(statuses.length - 1 - i));
                runDAO.insert(TopicRun.builder()
                        .runId(UUID.randomUUID())
                        .workspaceId(workspaceId)
                        .projectId(projectId)
                        .mode(TopicRun.Mode.NIGHTLY_UPDATE)
                        .status(statuses[i])
                        .startedAt(startedAt)
                        .finishedAt(startedAt.plus(Duration.ofMinutes(5)))
                        .build());
            }
            return null;
        });
    }

    private List<UUID> sampleIds(String workspaceId, UUID projectId, Instant from, Instant to, int limit) {
        return windowDAO.readWindow(workspaceId, projectId, from, to, limit, 120)
                .map(TopicWindowRow::traceId)
                .collectList()
                .block();
    }

    private static TopicPrototype prototype(String workspaceId, UUID projectId, int position, UUID topicId,
            float[] vector) {
        return TopicPrototype.builder()
                .workspaceId(workspaceId)
                .projectId(projectId)
                .position(position)
                .topicId(topicId)
                .vector(TopicPrototype.encode(vector))
                .build();
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

    /** The distinct values of the two settings the workspace's finished {@code queryName} queries ran with. */
    private List<List<String>> settings(String queryName, String workspaceId, String first, String second) {
        return clickHouseTemplate.nonTransaction(connection -> Mono
                .from(connection.createStatement("SYSTEM FLUSH LOGS").execute())
                .flatMap(result -> Mono.from(result.getRowsUpdated()))
                .thenMany(Flux.from(connection.createStatement("""
                        SELECT DISTINCT Settings[:first] AS first, Settings[:second] AS second
                        FROM system.query_log
                        WHERE type = 'QueryFinish' AND is_initial_query AND log_comment LIKE :log_comment
                        """)
                        .bind("first", first)
                        .bind("second", second)
                        .bind("log_comment", queryName + ":" + workspaceId + ":%")
                        .execute()))
                .flatMap(result -> result.map((row, metadata) -> List.of(row.get("first", String.class),
                        row.get("second", String.class))))
                .collectList())
                .block();
    }

    /**
     * The distinct {@code max_threads}, {@code max_rows_to_read}, {@code priority} and {@code max_execution_time} the
     * workspace's finished {@code queryName} queries ran with, an empty string for a setting left at its default.
     */
    private List<List<String>> guards(String queryName, String workspaceId) {
        return clickHouseTemplate.nonTransaction(connection -> Mono
                .from(connection.createStatement("SYSTEM FLUSH LOGS").execute())
                .flatMap(result -> Mono.from(result.getRowsUpdated()))
                .thenMany(Flux.from(connection.createStatement("""
                        SELECT DISTINCT Settings['max_threads'] AS max_threads,
                            Settings['max_rows_to_read'] AS max_rows_to_read, Settings['priority'] AS priority,
                            Settings['max_execution_time'] AS max_execution_time
                        FROM system.query_log
                        WHERE type = 'QueryFinish' AND is_initial_query AND log_comment LIKE :log_comment
                        """)
                        .bind("log_comment", queryName + ":" + workspaceId + ":%")
                        .execute()))
                .flatMap(result -> result.map((row, metadata) -> List.of(row.get("max_threads", String.class),
                        row.get("max_rows_to_read", String.class), row.get("priority", String.class),
                        row.get("max_execution_time", String.class))))
                .collectList())
                .block();
    }
}
