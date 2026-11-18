package com.comet.opik.domain.topics;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.PostCutoverTraces;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TopicsTraces;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.fasterxml.uuid.Generators;
import com.redis.testcontainers.RedisContainer;
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
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.dropwizard.guice.test.jupiter.param.Jit;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The topics DAOs against real ClickHouse and MySQL: the {@code topic_vectors} window count and reads, the
 * {@code trace_topics} read shape, writes, counts and deletes, and the label-state reads of
 * {@code topic_project_states}. Tests seed vectors and rows
 * directly and call the DAOs; no service runs here.
 */
@DisplayName("topics DAO storage")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TopicsDaoStorageTest {

    private static final int BUILT = 120;
    private static final int WINDOW_DAYS = 30;
    private static final Instant NIGHT = Instant.parse("2026-10-15T00:00:00Z");
    private static final TopicsTestData.Traces TRACES = TopicsTestData.Traces.synthetic(BUILT, NIGHT, 7);
    /** One trace every 72 minutes over the window: a window larger than {@link #SAMPLE}. */
    private static final int SPREAD = 600;
    private static final int SAMPLE = 150;
    private static final TopicsTestData.Traces SPREAD_TRACES = TopicsTestData.Traces.spread(SPREAD, NIGHT,
            WINDOW_DAYS, 3);
    /** A first build's minimum of vectors, as config-test.yml sets it; the window count does not read it. */
    private static final int MIN_VECTORS = 100;

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
    private TopicVectorsDAO vectorsDAO;
    private TopicWindowDAO windowDAO;
    private TraceTopicsDAO traceTopicsDAO;
    private TransactionTemplateAsync clickHouseTemplate;
    private ConnectionFactory connectionFactory;
    private TopicsTestData data;
    private TopicsTestData spreadData;

    @BeforeAll
    void beforeAll(OpikConfiguration opikConfig, TransactionTemplate template,
            TransactionTemplateAsync clickHouseTemplate, @Jit TopicVectorsDAO vectorsDAO,
            @Jit TopicWindowDAO windowDAO, @Jit TraceTopicsDAO traceTopicsDAO, ConnectionFactory connectionFactory) {
        this.opikConfig = opikConfig;
        this.template = template;
        this.vectorsDAO = vectorsDAO;
        this.windowDAO = windowDAO;
        this.traceTopicsDAO = traceTopicsDAO;
        this.clickHouseTemplate = clickHouseTemplate;
        this.connectionFactory = connectionFactory;
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
        assertThat(data.queryGuards("topics:count_window", workspaceId))
                .containsExactly(List.of("2", maxRowsToRead, "100", "60"));
        // The count's memory bound rests on these two.
        assertThat(data.queryLogSettings("topics:count_window", workspaceId, "optimize_aggregation_in_order",
                "max_block_size"))
                .containsExactly(List.of("1", "8192"));
        assertThat(data.queryGuards("topics:read_window", workspaceId))
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
        var below = UUID.randomUUID();
        var at = UUID.randomUUID();
        var above = UUID.randomUUID();
        data.seed(workspaceId, below, 0, MIN_VECTORS - 1);
        data.seed(workspaceId, at, 0, MIN_VECTORS);
        data.seed(workspaceId, above, 0, MIN_VECTORS + 1);
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
                        new TopicWindowDAO.ProjectCount(below, MIN_VECTORS - 1),
                        new TopicWindowDAO.ProjectCount(at, MIN_VECTORS),
                        new TopicWindowDAO.ProjectCount(above, MIN_VECTORS + 1),
                        new TopicWindowDAO.ProjectCount(mixed, 12 - 3));
        assertThat(windowDAO.countByProject(workspaceId, Set.of(below, gone), from(), to(), 60).collectList().block())
                .containsExactly(new TopicWindowDAO.ProjectCount(below, MIN_VECTORS - 1));
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
    @DisplayName("the topic counts take each inserted trace's latest row, per topic and Other, among the traces Logs "
            + "lists: an experiment trace, a hidden trace and a tombstoned row are left out")
    void insertedRowsAreCountedByTopic() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        var topicId = UUID.randomUUID();
        var ids = TRACES.traceIds().subList(0, 6);
        var experiment = ids.get(2);
        var hidden = ids.get(3);
        var tombstoned = ids.get(4);
        var topics = List.of(topicId, TraceTopic.OTHER, topicId, topicId, topicId, topicId);
        insertRunRows(workspaceId, projectId, UUID.randomUUID(), ids, topics);
        data.insertTraceTopic(workspaceId, projectId, tombstoned, TRACES.createdAt().get(4), topicId,
                Instant.now().plus(Duration.ofHours(1)), 1);
        TopicsTraces.insert(connectionFactory, PostCutoverTraces.SHARD, IntStream.range(0, ids.size())
                .mapToObj(i -> TopicsTraces.TraceRow.shown(workspaceId, projectId, ids.get(i),
                        TRACES.createdAt().get(i)))
                .map(row -> row.id().equals(experiment) ? row.toBuilder().source("experiment").build() : row)
                .map(row -> row.id().equals(hidden) ? row.toBuilder().visibilityMode("hidden").build() : row)
                .toList());

        var counts = traceTopicsDAO.countByTopic(workspaceId, projectId, from(), to(), 60).collectList().block();

        assertThat(counts).extracting(TraceTopicsDAO.TopicCount::topicId, TraceTopicsDAO.TopicCount::traces)
                .containsExactlyInAnyOrder(tuple(topicId, 2L), tuple(TraceTopic.OTHER, 1L));
    }

    @Test
    @DisplayName("the trace-delete cascade deletes the rows of the given traces of the given project only")
    void deleteByTraceIdsDeletesOnlyThoseTraces() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        var otherProjectId = UUID.randomUUID();
        var topicId = UUID.randomUUID();
        var ids = TRACES.traceIds().subList(0, 4);
        insertRunRows(workspaceId, projectId, UUID.randomUUID(), ids, List.of(topicId, topicId, topicId, topicId));
        insertRunRows(workspaceId, otherProjectId, UUID.randomUUID(), ids.subList(0, 1), List.of(topicId));

        traceTopicsDAO.deleteByTraceIds(Set.copyOf(ids.subList(0, 2)), projectId)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, RequestContext.SYSTEM_USER))
                .block();

        assertThat(data.traceTopics(workspaceId, projectId)).extracting(TraceTopic::traceId)
                .containsExactlyInAnyOrderElementsOf(ids.subList(2, 4));
        assertThat(data.traceTopics(workspaceId, otherProjectId)).extracting(TraceTopic::traceId)
                .containsExactly(ids.get(0));
    }

    @Test
    @DisplayName("retention deletes the rows of traces below the cutoff, and keeps experiment-linked, newer and other "
            + "workspaces' traces")
    void retentionDeletesBelowTheCutoff() {
        var workspaceId = UUID.randomUUID().toString();
        var otherWorkspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        var topicId = UUID.randomUUID();
        var now = Instant.now();
        var old = traceId(now.minus(20, ChronoUnit.DAYS));
        var oldInExperiment = traceId(now.minus(20, ChronoUnit.DAYS));
        var recent = traceId(now.minus(1, ChronoUnit.DAYS));
        insertRows(workspaceId, projectId, topicId, List.of(old, oldInExperiment, recent));
        insertRows(otherWorkspaceId, projectId, topicId, List.of(old));
        insertExperimentItem(workspaceId, oldInExperiment);

        traceTopicsDAO.deleteForRetention(List.of(workspaceId), traceId(now.minus(10, ChronoUnit.DAYS)),
                traceId(now.minus(30, ChronoUnit.DAYS))).block();

        assertThat(data.traceTopics(workspaceId, projectId)).extracting(TraceTopic::traceId)
                .containsExactlyInAnyOrder(oldInExperiment, recent);
        assertThat(data.traceTopics(otherWorkspaceId, projectId)).extracting(TraceTopic::traceId)
                .containsExactly(old);
    }

    @Test
    @DisplayName("bounded retention deletes from each workspace's own floor up to the cutoff")
    void boundedRetentionDeletesFromTheFloor() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        var topicId = UUID.randomUUID();
        var now = Instant.now();
        var belowFloor = traceId(now.minus(20, ChronoUnit.DAYS));
        var aboveFloor = traceId(now.minus(12, ChronoUnit.DAYS));
        var afterCutoff = traceId(now.minus(5, ChronoUnit.DAYS));
        insertRows(workspaceId, projectId, topicId, List.of(belowFloor, aboveFloor, afterCutoff));

        traceTopicsDAO.deleteForRetentionBounded(Map.of(workspaceId, traceId(now.minus(15, ChronoUnit.DAYS))),
                traceId(now.minus(10, ChronoUnit.DAYS)), traceId(now.minus(30, ChronoUnit.DAYS))).block();

        assertThat(data.traceTopics(workspaceId, projectId)).extracting(TraceTopic::traceId)
                .containsExactlyInAnyOrder(belowFloor, afterCutoff);
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

    private List<UUID> sampleIds(String workspaceId, UUID projectId, Instant from, Instant to, int limit) {
        return windowDAO.readWindow(workspaceId, projectId, from, to, limit, 120)
                .map(TopicWindowRow::traceId)
                .collectList()
                .block();
    }

    /** Rows of one run: trace {@code traceIds[i]} of {@link #TRACES}, at its creation time, with {@code topicIds[i]}. */
    private void insertRunRows(String workspaceId, UUID projectId, UUID runId, List<UUID> traceIds,
            List<UUID> topicIds) {
        traceTopicsDAO.insert(IntStream.range(0, traceIds.size())
                .mapToObj(i -> TraceTopic.builder()
                        .workspaceId(workspaceId)
                        .projectId(projectId)
                        .traceId(traceIds.get(i))
                        .traceCreatedAt(TRACES.createdAt().get(TRACES.traceIds().indexOf(traceIds.get(i))))
                        .topicId(topicIds.get(i))
                        .source(TraceTopic.Source.RUN)
                        .runId(runId.toString())
                        .build())
                .toList()).block();
    }

    /** One run row of {@code topicId} per trace, each created at its UUID v7 id's time. */
    private void insertRows(String workspaceId, UUID projectId, UUID topicId, List<UUID> traceIds) {
        traceTopicsDAO.insert(traceIds.stream()
                .map(traceId -> TraceTopic.builder()
                        .workspaceId(workspaceId)
                        .projectId(projectId)
                        .traceId(traceId)
                        .traceCreatedAt(Instant.ofEpochMilli(traceId.getMostSignificantBits() >>> 16))
                        .topicId(topicId)
                        .source(TraceTopic.Source.RUN)
                        .runId(UUID.randomUUID().toString())
                        .build())
                .toList()).block();
    }

    private static UUID traceId(Instant at) {
        return Generators.timeBasedEpochGenerator().construct(at.toEpochMilli());
    }

    private void insertExperimentItem(String workspaceId, UUID traceId) {
        clickHouseTemplate.nonTransaction(connection -> Mono.from(connection.createStatement("""
                INSERT INTO experiment_items (id, experiment_id, dataset_item_id, trace_id, workspace_id)
                VALUES (:id, :experiment_id, :dataset_item_id, :trace_id, :workspace_id)
                """)
                .bind("id", UUID.randomUUID().toString())
                .bind("experiment_id", UUID.randomUUID().toString())
                .bind("dataset_item_id", UUID.randomUUID().toString())
                .bind("trace_id", traceId.toString())
                .bind("workspace_id", workspaceId)
                .execute())
                .flatMap(result -> Mono.from(result.getRowsUpdated())))
                .block();
    }

    /** The window that ends at the seeded traces' night. */
    private static Instant from() {
        return NIGHT.minus(Duration.ofDays(WINDOW_DAYS));
    }

    private static Instant to() {
        return NIGHT;
    }
}
