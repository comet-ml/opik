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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
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
    private TopicsProjectDeletedListener projectDeletedListener;
    private TopicsTestData data;

    @BeforeAll
    void beforeAll(OpikConfiguration opikConfig, TransactionTemplate template,
            TransactionTemplateAsync clickHouseTemplate, @Jit TopicVectorsDAO vectorsDAO,
            @Jit TopicWindowDAO windowDAO, @Jit TopicsProjectDeletedListener projectDeletedListener) {
        this.opikConfig = opikConfig;
        this.template = template;
        this.clickHouseTemplate = clickHouseTemplate;
        this.vectorsDAO = vectorsDAO;
        this.windowDAO = windowDAO;
        this.projectDeletedListener = projectDeletedListener;
        this.data = new TopicsTestData(template, clickHouseTemplate, vectorsDAO, TRACES);
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
    @DisplayName("deleting a project deletes its topics, prototypes, state, runs and run counts")
    void projectDeleted() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        var kept = UUID.randomUUID();
        insertTopicData(workspaceId, projectId);
        insertTopicData(workspaceId, kept);

        projectDeletedListener.onProjectsDeleted(new ProjectsDeleted(Set.of(projectId), workspaceId, "test"));

        assertThat(data.topics(projectId)).isEmpty();
        assertThat(data.prototypes(projectId)).isEmpty();
        assertThat(data.state(projectId)).isNull();
        assertThat(data.runs(projectId)).isEmpty();
        assertThat(data.counts(projectId)).isEmpty();
        assertThat(data.topics(kept)).isNotEmpty();
        assertThat(data.runs(kept)).hasSize(1);
        assertThat(data.counts(kept)).isNotEmpty();
    }

    @Test
    @DisplayName("the window count runs on 2 threads under the row guard at a background priority; the window read "
            + "stops after 120 s")
    void readsCarryTheirGuards() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, BUILT);

        // As a night reads: the count of every project of the workspace under 60 s, a run's window under 120 s.
        assertThat(windowDAO.countByProject(workspaceId, null, from(), to(), 60).collectList().block())
                .containsExactly(new TopicWindowDAO.ProjectCount(projectId, BUILT));
        assertThat(windowDAO.readWindow(workspaceId, projectId, from(), to(), BUILT, 120).collectList().block())
                .hasSize(BUILT);

        var maxRowsToRead = String.valueOf(opikConfig.getTopics().getVectors().getFind().getMaxRowsToRead());
        assertThat(guards("topics:count_window", workspaceId))
                .containsExactly(List.of("2", maxRowsToRead, "100", "60"));
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

        assertThat(windowDAO.countByProject(workspaceId, projectId, from(), to(), 60).collectList().block())
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

        assertThat(windowDAO.countByProject(workspaceId, projectId, from(), to(), 60).collectList().block())
                .containsExactly(new TopicWindowDAO.ProjectCount(projectId, BUILT - tombstoned.size()));
        assertThat(windowDAO.readWindow(workspaceId, projectId, from(), to(), BUILT, 60).collectList().block())
                .extracting(TopicWindowRow::traceId)
                .containsExactlyInAnyOrderElementsOf(TRACES.traceIds().subList(tombstoned.size(), BUILT));
    }

    /**
     * What a saved run leaves in MySQL for a project: a topic, its prototype and an Other one, the state, the run and
     * its counts.
     */
    private void insertTopicData(String workspaceId, UUID projectId) {
        var runId = UUID.randomUUID();
        var topicId = UUID.randomUUID();
        var savedAt = NIGHT.plus(Duration.ofHours(2));
        var vector = TopicPrototype.encode(new float[TopicVectorsTickService.EMBED_DIMENSIONS]);
        template.inTransaction(WRITE, handle -> {
            var topicDAO = handle.attach(TopicDAO.class);
            topicDAO.insertState(TopicProjectState.builder()
                    .projectId(projectId)
                    .workspaceId(workspaceId)
                    .rebuilds(1)
                    .firstBuiltAt(savedAt)
                    .lastRunId(runId)
                    .lastSuccessAt(savedAt)
                    .windowUntil(NIGHT)
                    .build());
            topicDAO.insertTopics(List.of(Topic.builder()
                    .id(topicId)
                    .workspaceId(workspaceId)
                    .projectId(projectId)
                    .name("Billing")
                    .definition("Requests about invoices")
                    .born(LocalDate.ofInstant(NIGHT, ZoneOffset.UTC))
                    .size(BUILT - 10)
                    .build()));
            topicDAO.insertPrototypes(List.of(
                    TopicPrototype.builder().workspaceId(workspaceId).projectId(projectId).position(0)
                            .topicId(topicId).vector(vector).build(),
                    TopicPrototype.builder().workspaceId(workspaceId).projectId(projectId).position(1)
                            .vector(vector).build()));
            var runDAO = handle.attach(TopicRunDAO.class);
            runDAO.insert(TopicRun.builder()
                    .runId(runId)
                    .workspaceId(workspaceId)
                    .projectId(projectId)
                    .mode(TopicRun.Mode.FIRST_BUILD)
                    .status(TopicRun.Status.SAVED)
                    .startedAt(savedAt.minus(Duration.ofMinutes(1)))
                    .finishedAt(savedAt)
                    .build());
            runDAO.insertCounts(List.of(
                    TopicRunCount.builder().runId(runId).workspaceId(workspaceId).projectId(projectId)
                            .topicId(topicId).traces(BUILT - 10).rank(1).savedAt(savedAt).build(),
                    TopicRunCount.builder().runId(runId).workspaceId(workspaceId).projectId(projectId)
                            .topicId(TraceTopic.OTHER).traces(10).savedAt(savedAt).build()));
            return null;
        });
    }

    /** The window that ends at the seeded traces' night. */
    private static Instant from() {
        return NIGHT.minus(Duration.ofDays(WINDOW_DAYS));
    }

    private static Instant to() {
        return NIGHT;
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
