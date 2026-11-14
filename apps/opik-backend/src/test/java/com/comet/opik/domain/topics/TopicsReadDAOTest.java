package com.comet.opik.domain.topics;

import com.comet.opik.api.Source;
import com.comet.opik.api.VisibilityMode;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TopicsTraces;
import com.comet.opik.api.resources.utils.TopicsTraces.TraceRow;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.topics.TraceTopicsDAO.TopicDayCount;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.google.inject.Injector;
import com.redis.testcontainers.RedisContainer;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reads behind the topics read API against real ClickHouse and MySQL: the per topic and day counts of
 * {@code trace_topics}, and the workspace-filtered reads of a project's topics and state. Each test uses
 * its own workspace and project.
 */
@DisplayName("topics read API reads")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TopicsReadDAOTest {

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 3);
    /** The API's window for {@link #TODAY}: {@code [today - 29, today + 1)}. */
    private static final Instant FROM = TODAY.minusDays(29).atStartOfDay().toInstant(ZoneOffset.UTC);
    private static final Instant TO = TODAY.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    private static final Instant NOON = TODAY.atTime(12, 0).toInstant(ZoneOffset.UTC);
    private static final long MAX_EXECUTION_SECONDS = 10;

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer(zookeeper);
    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer();

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redis, clickHouse, mysql, zookeeper).join();
        var wireMock = WireMockUtils.startWireMock();
        MigrationUtils.runMysqlDbMigration(mysql);
        MigrationUtils.runClickhouseDbMigration(clickHouse);
        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(AppContextConfig.builder()
                .jdbcUrl(mysql.getJdbcUrl())
                .databaseAnalyticsFactory(ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouse,
                        DATABASE_NAME))
                .runtimeInfo(wireMock.runtimeInfo())
                .redisUrl(redis.getRedisURI())
                .build());
    }

    private TraceTopicsDAO traceTopicsDAO;
    private TransactionTemplate template;
    private ConnectionFactory connectionFactory;

    @BeforeAll
    void setUpAll(Injector injector) {
        traceTopicsDAO = injector.getInstance(TraceTopicsDAO.class);
        template = injector.getInstance(TransactionTemplate.class);
        connectionFactory = injector.getInstance(ConnectionFactory.class);
    }

    @Test
    @DisplayName("window edges: the first instant is in, one microsecond before it and the end are out; the last "
            + "microsecond of a day counts on that day")
    void windowEdges() {
        var workspaceId = workspaceId();
        var projectId = UUID.randomUUID();
        var topicId = UUID.randomUUID();
        insert(arrival(workspaceId, projectId, FROM, topicId),
                arrival(workspaceId, projectId, FROM.minusNanos(1_000), topicId),
                arrival(workspaceId, projectId, TO, topicId),
                arrival(workspaceId, projectId, TO.minusNanos(1_000), topicId));

        assertThat(count(workspaceId, projectId)).containsExactlyInAnyOrder(
                TopicDayCount.builder().topicId(topicId).day(TODAY.minusDays(29)).traces(1).build(),
                TopicDayCount.builder().topicId(topicId).day(TODAY).traces(1).build());
    }

    @Test
    @DisplayName("a trace counts once, by its latest row: a run after an arrival wins, an arrival after a run wins")
    void latestRowWins() {
        var workspaceId = workspaceId();
        var projectId = UUID.randomUUID();
        var arrivalTopic = UUID.randomUUID();
        var runTopic = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var arrivalThenRun = ID_GENERATOR.generateId();
        var runThenArrival = ID_GENERATOR.generateId();
        insert(arrival(workspaceId, projectId, arrivalThenRun, NOON, arrivalTopic),
                run(workspaceId, projectId, runThenArrival, NOON, runTopic, runId));
        insert(run(workspaceId, projectId, arrivalThenRun, NOON, runTopic, runId),
                arrival(workspaceId, projectId, runThenArrival, NOON, arrivalTopic));

        assertThat(count(workspaceId, projectId)).containsExactlyInAnyOrder(
                TopicDayCount.builder().topicId(arrivalTopic).day(TODAY).traces(1).build(),
                TopicDayCount.builder().topicId(runTopic).day(TODAY).traces(1).build());
    }

    @Test
    @DisplayName("every run's row counts, with no topic_runs row (its retention deleted it): a later run's row "
            + "wins over an earlier one's, and a trace only an earlier run labelled keeps its topic")
    void everyRunRowCounts() {
        var workspaceId = workspaceId();
        var projectId = UUID.randomUUID();
        var earlierTopic = UUID.randomUUID();
        var laterTopic = UUID.randomUUID();
        var earlierRun = UUID.randomUUID();
        var traceId = ID_GENERATOR.generateId();
        insert(run(workspaceId, projectId, traceId, NOON, earlierTopic, earlierRun),
                run(workspaceId, projectId, ID_GENERATOR.generateId(), NOON, earlierTopic, earlierRun));
        insert(run(workspaceId, projectId, traceId, NOON, laterTopic, UUID.randomUUID()));

        assertThat(count(workspaceId, projectId)).containsExactlyInAnyOrder(
                TopicDayCount.builder().topicId(earlierTopic).day(TODAY).traces(1).build(),
                TopicDayCount.builder().topicId(laterTopic).day(TODAY).traces(1).build());
    }

    @Test
    @DisplayName("a trace whose latest row is a tombstone counts nowhere: not in its topic, not in Other")
    void tombstoneCountsNowhere() {
        var workspaceId = workspaceId();
        var projectId = UUID.randomUUID();
        var topicId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var deleted = ID_GENERATOR.generateId();
        insert(run(workspaceId, projectId, deleted, NOON, topicId, runId),
                run(workspaceId, projectId, ID_GENERATOR.generateId(), NOON, topicId, runId));
        TopicsTraces.tombstone(connectionFactory, workspaceId, projectId, deleted, NOON, topicId);

        assertThat(count(workspaceId, projectId)).containsExactly(
                TopicDayCount.builder().topicId(topicId).day(TODAY).traces(1).build());
    }

    @Test
    @DisplayName("Other and days apart: the all-zeros id is a topic id like any other, each day is its own row")
    void otherAndDays() {
        var workspaceId = workspaceId();
        var projectId = UUID.randomUUID();
        var topicId = UUID.randomUUID();
        var yesterday = NOON.minus(Duration.ofDays(1));
        insert(arrival(workspaceId, projectId, NOON, topicId),
                arrival(workspaceId, projectId, NOON, topicId),
                arrival(workspaceId, projectId, yesterday, topicId),
                arrival(workspaceId, projectId, NOON, TraceTopic.OTHER));

        assertThat(count(workspaceId, projectId)).containsExactlyInAnyOrder(
                TopicDayCount.builder().topicId(topicId).day(TODAY).traces(2).build(),
                TopicDayCount.builder().topicId(topicId).day(TODAY.minusDays(1)).traces(1).build(),
                TopicDayCount.builder().topicId(TraceTopic.OTHER).day(TODAY).traces(1).build());
    }

    @Test
    @DisplayName("only the traces Logs shows count: source sdk, or unknown from before sources, with visibility "
            + "default; experiment, playground, optimization, evaluator and hidden traces have rows but do not count")
    void onlyTracesLogsShows() {
        var workspaceId = workspaceId();
        var projectId = UUID.randomUUID();
        var topicId = UUID.randomUUID();
        var kinds = List.of(
                List.of(Source.SDK.getValue(), VisibilityMode.DEFAULT.getValue()),
                List.of(Source.UNKNOWN_VALUE, VisibilityMode.DEFAULT.getValue()),
                List.of(Source.EXPERIMENT.getValue(), VisibilityMode.DEFAULT.getValue()),
                List.of(Source.PLAYGROUND.getValue(), VisibilityMode.DEFAULT.getValue()),
                List.of(Source.OPTIMIZATION.getValue(), VisibilityMode.DEFAULT.getValue()),
                List.of(Source.EVALUATOR.getValue(), VisibilityMode.HIDDEN.getValue()),
                List.of(Source.EXPERIMENT.getValue(), VisibilityMode.HIDDEN.getValue()),
                List.of(Source.SDK.getValue(), VisibilityMode.HIDDEN.getValue()));
        var rows = new ArrayList<TraceTopic>();
        var traces = new ArrayList<TraceRow>();
        for (var kind : kinds) {
            var row = arrival(workspaceId, projectId, NOON, topicId);
            rows.add(row);
            traces.add(new TraceRow(workspaceId, projectId, row.traceId(), NOON, kind.get(0), kind.get(1)));
        }
        traceTopicsDAO.insert(rows).block();
        TopicsTraces.insert(connectionFactory, "traces", traces);

        assertThat(count(workspaceId, projectId))
                .containsExactly(TopicDayCount.builder().topicId(topicId).day(TODAY).traces(2).build());
    }

    @Test
    @DisplayName("the same project id under another workspace is not counted")
    void otherWorkspace() {
        var workspaceId = workspaceId();
        var projectId = UUID.randomUUID();
        var topicId = UUID.randomUUID();
        insert(arrival(workspaceId, projectId, NOON, topicId),
                arrival(workspaceId(), projectId, NOON, topicId));

        assertThat(count(workspaceId, projectId))
                .containsExactly(TopicDayCount.builder().topicId(topicId).day(TODAY).traces(1).build());
    }

    @Test
    @DisplayName("live topics and the project state: filtered by workspace too; retired topics are left out")
    void liveTopicsAndState() {
        var workspaceId = workspaceId();
        var projectId = UUID.randomUUID();
        var live = topic(workspaceId, projectId, null);
        var retired = topic(workspaceId, projectId, TODAY.minusDays(1));
        var state = TopicProjectState.builder()
                .projectId(projectId)
                .workspaceId(workspaceId)
                .rebuilds(1)
                .firstBuiltAt(TODAY.minusDays(10).atStartOfDay().toInstant(ZoneOffset.UTC))
                .lastRunId(UUID.randomUUID())
                .lastSuccessAt(NOON)
                .windowUntil(TODAY.atStartOfDay().toInstant(ZoneOffset.UTC))
                .build();
        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(TopicDAO.class);
            dao.insertTopics(List.of(live, retired));
            dao.insertState(state);
            return null;
        });

        template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(TopicDAO.class);
            assertThat(dao.findLive(workspaceId, projectId)).extracting(Topic::id).containsExactly(live.id());
            assertThat(dao.findLive(workspaceId(), projectId)).isEmpty();
            assertThat(dao.findStates(workspaceId, List.of(projectId)))
                    .singleElement().usingRecursiveComparison().isEqualTo(state);
            assertThat(dao.findStates(workspaceId(), List.of(projectId))).isEmpty();
            return null;
        });
    }

    private List<TopicDayCount> count(String workspaceId, UUID projectId) {
        return traceTopicsDAO.countByTopicAndDay(workspaceId, projectId, FROM, TO, MAX_EXECUTION_SECONDS)
                .collectList().block();
    }

    /** The rows, and a trace Logs shows for each: only those count. */
    private void insert(TraceTopic... rows) {
        traceTopicsDAO.insert(List.of(rows)).block();
        TopicsTraces.insert(connectionFactory, "traces", Stream.of(rows)
                .map(row -> TraceRow.shown(row.workspaceId(), row.projectId(), row.traceId(), row.traceCreatedAt()))
                .toList());
    }

    private static TraceTopic arrival(String workspaceId, UUID projectId, Instant createdAt, UUID topicId) {
        return arrival(workspaceId, projectId, ID_GENERATOR.generateId(), createdAt, topicId);
    }

    private static TraceTopic arrival(String workspaceId, UUID projectId, UUID traceId, Instant createdAt,
            UUID topicId) {
        return row(workspaceId, projectId, traceId, createdAt, topicId, TraceTopic.Source.ARRIVAL, "");
    }

    private static TraceTopic run(String workspaceId, UUID projectId, UUID traceId, Instant createdAt,
            UUID topicId, UUID runId) {
        return row(workspaceId, projectId, traceId, createdAt, topicId, TraceTopic.Source.RUN, runId.toString());
    }

    private static TraceTopic row(String workspaceId, UUID projectId, UUID traceId, Instant createdAt, UUID topicId,
            TraceTopic.Source source, String runId) {
        return TraceTopic.builder()
                .workspaceId(workspaceId)
                .projectId(projectId)
                .traceId(traceId)
                .traceCreatedAt(createdAt)
                .topicId(topicId)
                .source(source)
                .runId(runId)
                .build();
    }

    private static Topic topic(String workspaceId, UUID projectId, LocalDate retiredAt) {
        return Topic.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspaceId)
                .projectId(projectId)
                .name("Refund requests")
                .definition("Users ask to get their money back.")
                .born(TODAY.minusDays(10))
                .retiredAt(retiredAt)
                .retiredReason(retiredAt == null ? null : "merged")
                .build();
    }

    private static String workspaceId() {
        return "ws-" + UUID.randomUUID();
    }
}
