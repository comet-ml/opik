package com.comet.opik.domain.topics;

import com.comet.opik.api.Source;
import com.comet.opik.api.VisibilityMode;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.PostCutoverTraces;
import com.comet.opik.api.resources.utils.PostCutoverTraces.TraceRow;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TopicsTraces;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.topics.TraceTopicsDAO.TopicCount;
import com.comet.opik.domain.topics.TraceTopicsDAO.TopicDayCount;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.TopicsConfig;
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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The reads behind the topics read API against real ClickHouse: the per topic and day counts of
 * {@code trace_topics}. Each test uses its own workspace and project.
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
        PostCutoverTraces.migrate(clickHouse);
        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(AppContextConfig.builder()
                .jdbcUrl(mysql.getJdbcUrl())
                .databaseAnalyticsFactory(ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouse,
                        DATABASE_NAME))
                .runtimeInfo(wireMock.runtimeInfo())
                .redisUrl(redis.getRedisURI())
                .customConfigs(List.of(
                        new CustomConfig("databaseAnalyticsDataModel.traceColumnsNonNullable", "true"),
                        new CustomConfig("databaseAnalyticsDataModel.tracesDistributedWrapEnabled", "true")))
                .build());
    }

    private TraceTopicsDAO traceTopicsDAO;
    private ConnectionFactory connectionFactory;
    private TopicsConfig.ReadConfig read;

    @BeforeAll
    void setUpAll(Injector injector) {
        traceTopicsDAO = injector.getInstance(TraceTopicsDAO.class);
        connectionFactory = injector.getInstance(ConnectionFactory.class);
        read = injector.getInstance(OpikConfiguration.class).getTopics().getRead();
    }

    @Test
    @DisplayName("a spent budget fails the next day's query before it starts, as a guard trip")
    void spentBudgetIsAGuardTrip() {
        var workspaceId = workspaceId();
        var projectId = UUID.randomUUID();
        insert(arrival(workspaceId, projectId, NOON, UUID.randomUUID()));
        var budget = read.getBudget();
        read.setBudget(io.dropwizard.util.Duration.milliseconds(1));
        try {
            assertThatThrownBy(() -> count(workspaceId, projectId))
                    .satisfies(error -> assertThat(TopicsReadErrors.isGuardTrip(error)).isTrue());
        } finally {
            read.setBudget(budget);
        }
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
    @DisplayName("every run's row counts: a later run's row "
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
        PostCutoverTraces.insertTraces(connectionFactory, traces);

        assertThat(count(workspaceId, projectId))
                .containsExactly(TopicDayCount.builder().topicId(topicId).day(TODAY).traces(2).build());
    }

    @Test
    @DisplayName("one counting rule: per topic and for Other, countByTopic equals the sum over days of "
            + "countByTopicAndDay, with a tombstone, a trace of another source and a hidden trace; each day is its "
            + "own row, and the all-zeros id is a topic id like any other")
    void countByTopicIsTheSumOfTheDays() {
        var workspaceId = workspaceId();
        var projectId = UUID.randomUUID();
        var topicId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var yesterday = NOON.minus(Duration.ofDays(1));
        var deleted = ID_GENERATOR.generateId();
        insert(arrival(workspaceId, projectId, NOON, topicId),
                arrival(workspaceId, projectId, NOON, topicId),
                arrival(workspaceId, projectId, yesterday, topicId),
                arrival(workspaceId, projectId, NOON, TraceTopic.OTHER),
                run(workspaceId, projectId, deleted, NOON, topicId, runId));
        TopicsTraces.tombstone(connectionFactory, workspaceId, projectId, deleted, NOON, topicId);
        // Rows of traces Logs does not list: they count nowhere
        var experiment = arrival(workspaceId, projectId, NOON, topicId);
        var hidden = arrival(workspaceId, projectId, yesterday, TraceTopic.OTHER);
        traceTopicsDAO.insert(List.of(experiment, hidden)).block();
        PostCutoverTraces.insertTraces(connectionFactory, List.of(
                new TraceRow(workspaceId, projectId, experiment.traceId(), NOON, Source.EXPERIMENT.getValue(),
                        VisibilityMode.DEFAULT.getValue()),
                new TraceRow(workspaceId, projectId, hidden.traceId(), yesterday, Source.SDK.getValue(),
                        VisibilityMode.HIDDEN.getValue())));

        var byTopic = traceTopicsDAO.countByTopic(workspaceId, projectId, FROM, TO, MAX_EXECUTION_SECONDS)
                .collectList().block();
        var byDay = count(workspaceId, projectId);

        assertThat(byDay).containsExactlyInAnyOrder(
                TopicDayCount.builder().topicId(topicId).day(TODAY).traces(2).build(),
                TopicDayCount.builder().topicId(topicId).day(TODAY.minusDays(1)).traces(1).build(),
                TopicDayCount.builder().topicId(TraceTopic.OTHER).day(TODAY).traces(1).build());
        assertThat(byTopic).containsExactlyInAnyOrder(
                TopicCount.builder().topicId(topicId).traces(3).build(),
                TopicCount.builder().topicId(TraceTopic.OTHER).traces(1).build());
        assertThat(byTopic.stream().collect(Collectors.toMap(TopicCount::topicId, TopicCount::traces)))
                .isEqualTo(byDay.stream().collect(Collectors.groupingBy(TopicDayCount::topicId,
                        Collectors.summingLong(TopicDayCount::traces))));
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

    private List<TopicDayCount> count(String workspaceId, UUID projectId) {
        return traceTopicsDAO.countByTopicAndDay(workspaceId, projectId, FROM, TO)
                .collectList().block();
    }

    /** The rows, and a trace Logs shows for each: only those count. */
    private void insert(TraceTopic... rows) {
        traceTopicsDAO.insert(List.of(rows)).block();
        PostCutoverTraces.insertTraces(connectionFactory, Stream.of(rows)
                .map(row -> TraceRow.shown(row.workspaceId(), row.projectId(), row.traceId(), row.traceCreatedAt()))
                .toList());
    }

    private static TraceTopic arrival(String workspaceId, UUID projectId, Instant createdAt, UUID topicId) {
        return arrival(workspaceId, projectId, ID_GENERATOR.generateId(), createdAt, topicId);
    }

    private static TraceTopic arrival(String workspaceId, UUID projectId, UUID traceId, Instant createdAt,
            UUID topicId) {
        return row(workspaceId, projectId, traceId, createdAt, topicId, TraceTopic.Source.ARRIVAL, null);
    }

    private static TraceTopic run(String workspaceId, UUID projectId, UUID traceId, Instant createdAt,
            UUID topicId, UUID runId) {
        return row(workspaceId, projectId, traceId, createdAt, topicId, TraceTopic.Source.RUN, runId);
    }

    private static TraceTopic row(String workspaceId, UUID projectId, UUID traceId, Instant createdAt, UUID topicId,
            TraceTopic.Source source, UUID runId) {
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

    private static String workspaceId() {
        return "ws-" + UUID.randomUUID();
    }
}
