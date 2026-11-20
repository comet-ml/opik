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
import reactor.core.publisher.Flux;
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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The labelling pass's storage against real ClickHouse and MySQL: the rows a pass writes and the counts it logs. No run builds
 * here: tests seed vectors and a saved list directly and call the label service. {@link TopicsDaoStorageTest} covers
 * the DAO reads.
 */
@DisplayName("topics storage")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TopicsStorageTest {

    private static final int BUILT = 120;
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
    private ConnectionFactory connectionFactory;
    private TopicsTestData data;
    private TopicsTestData spreadData;

    @BeforeAll
    void beforeAll(OpikConfiguration opikConfig, TransactionTemplate template,
            TransactionTemplateAsync clickHouseTemplate, @Jit TopicVectorsDAO vectorsDAO,
            @Jit TraceTopicsDAO traceTopicsDAO,
            @Jit TopicWindowLabelService labelService,
            ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
        this.opikConfig = opikConfig;
        this.template = template;
        this.clickHouseTemplate = clickHouseTemplate;
        this.vectorsDAO = vectorsDAO;
        this.traceTopicsDAO = traceTopicsDAO;
        this.labelService = labelService;
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
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, BUILT);
        // After the window.
        spreadData.seed(workspaceId, projectId, 0, 20, NIGHT.plus(Duration.ofDays(WINDOW_DAYS + 1)));
        var runId = UUID.randomUUID();
        var topicId = UUID.randomUUID();
        int dimensions = TopicEmbedding.DIMENSIONS;
        var vectors = saveList(workspaceId, projectId, runId, topicId);
        // The run's own insert, or an earlier cut pass, labelled 30 traces.
        var labelledBefore = UUID.randomUUID();
        insertRunRows(workspaceId, projectId, runId, labelledBefore, TRACES.traceIds().subList(3, 33),
                TRACES.createdAt().subList(3, 33));

        assertThat(data.topicProject(projectId).labelsComplete()).isFalse();

        var pass = labelService.labelWindow(workspaceId, projectId, Instant.now().plus(Duration.ofMinutes(5)),
                TopicWindowLabelService.Trigger.RUN, 7, 20);

        assertThat(pass.complete()).isTrue();
        assertThat(data.topicProject(projectId).labelsComplete()).isTrue();
        assertThat(pass.runId()).isEqualTo(runId);
        assertThat(pass.windowTraces()).isEqualTo(BUILT);
        assertThat(pass.labelled()).isEqualTo(BUILT - 30);
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

        var again = labelService.labelWindow(workspaceId, projectId, Instant.now().plus(Duration.ofMinutes(5)),
                TopicWindowLabelService.Trigger.RESUME, 7, 20);

        assertThat(again.complete()).isTrue();
        assertThat(again.labelled()).isZero();
        var maxRowsToRead = String.valueOf(opikConfig.getTopics().getVectors().getFind().getMaxRowsToRead());
        assertThat(data.queryGuards("topics:read_unlabelled", workspaceId))
                .containsExactly(List.of("2", maxRowsToRead, "100", "120"));
    }

    @Test
    @DisplayName("the pass that ends complete counts the run's traces: "
            + "per topic and Other, each trace by its latest row, only the traces Logs lists, only the run's window; "
            + "a trace from an experiment, a hidden one, or a labelled one just outside the window is left out")
    void runCountsAreOfWhatLogsLists() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        data.seed(workspaceId, projectId, 0, BUILT);
        var runId = UUID.randomUUID();
        var topicId = UUID.randomUUID();
        saveList(workspaceId, projectId, runId, topicId);
        var experiment = TRACES.traceIds().get(5);
        var hidden = TRACES.traceIds().get(6);
        TopicsTraces.insert(connectionFactory, PostCutoverTraces.SHARD, IntStream.range(0, BUILT)
                .mapToObj(i -> TopicsTraces.TraceRow.shown(workspaceId, projectId, TRACES.traceIds().get(i),
                        TRACES.createdAt().get(i)))
                .map(row -> row.id().equals(experiment) ? row.toBuilder().source("experiment").build() : row)
                .map(row -> row.id().equals(hidden) ? row.toBuilder().visibilityMode("hidden").build() : row)
                .toList());
        // Logs lists them and they have a row of the run, but they are outside its window: one created at its end
        // (labelled before the save, as a nightly update's pass does), one just before its start.
        var atEnd = NIGHT;
        var beforeStart = from().minusSeconds(1);
        var outside = List.of(Generators.timeBasedEpochGenerator().construct(atEnd.toEpochMilli()),
                Generators.timeBasedEpochGenerator().construct(beforeStart.toEpochMilli()));
        var outsideAt = List.of(atEnd, beforeStart);
        TopicsTraces.insert(connectionFactory, PostCutoverTraces.SHARD, IntStream.range(0, 2)
                .mapToObj(i -> TopicsTraces.TraceRow.shown(workspaceId, projectId, outside.get(i), outsideAt.get(i)))
                .toList());
        insertRunRows(workspaceId, projectId, runId, topicId, outside, outsideAt);

        var pass = labelService.labelWindow(workspaceId, projectId, Instant.now().plus(Duration.ofMinutes(5)),
                TopicWindowLabelService.Trigger.RUN, 7, 20);

        assertThat(pass.complete()).isTrue();
        var expected = new HashMap<UUID, Long>(Map.of(topicId, 0L, TraceTopic.OTHER, 0L));
        data.traceTopics(workspaceId, projectId).stream()
                .filter(row -> !row.traceId().equals(experiment) && !row.traceId().equals(hidden))
                .filter(row -> !outside.contains(row.traceId()))
                .forEach(row -> expected.merge(row.topicId(), 1L, Long::sum));
        assertThat(expected.get(topicId)).isPositive();
        assertThat(expected.get(TraceTopic.OTHER)).isPositive();
        assertThat(expected.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(BUILT - 2);
        assertThat(pass.counts()).isEqualTo(expected);
        var find = opikConfig.getTopics().getVectors().getFind();
        assertThat(data.queryGuards("topics:count_by_topic", workspaceId))
                .containsExactly(List.of("2", String.valueOf(find.getMaxRowsToRead()), "", "120"));
        assertThat(data.queryLogSettings("topics:count_by_topic", workspaceId, "max_memory_usage", "max_threads"))
                .containsExactly(List.of(String.valueOf(find.getMaxMemoryUsage()), "2"));
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
            var runDAO = handle.attach(TopicRunDAO.class);
            var topicDAO = handle.attach(TopicDAO.class);
            runDAO.start(workspaceId, projectId, runId, TopicProject.RunMode.FIRST_BUILD,
                    savedAt.minus(Duration.ofMinutes(1)));
            topicDAO.saveList(workspaceId, projectId, null, runId, savedAt, NIGHT,
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
            runDAO.markSaved(workspaceId, projectId, runId, savedAt);
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
}
