package com.comet.opik.domain.topics;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.topics.TraceTopicsDAO.TopicDayCount;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.google.inject.Injector;
import com.redis.testcontainers.RedisContainer;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reads behind the topics read API against real ClickHouse and MySQL: the per topic and day counts of
 * {@code trace_topics}, and the workspace-filtered reads of a project's topics, state and saved runs. Each test uses
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

    @BeforeAll
    void setUpAll(Injector injector) {
        traceTopicsDAO = injector.getInstance(TraceTopicsDAO.class);
        template = injector.getInstance(TransactionTemplate.class);
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

        assertThat(count(workspaceId, projectId, Set.of())).containsExactlyInAnyOrder(
                TopicDayCount.builder().topicId(topicId).day(TODAY.minusDays(29)).traces(1).build(),
                TopicDayCount.builder().topicId(topicId).day(TODAY).traces(1).build());
    }

    @Test
    @DisplayName("a trace counts once, by its latest row: a night after an arrival wins, an arrival after a night wins")
    void latestRowWins() {
        var workspaceId = workspaceId();
        var projectId = UUID.randomUUID();
        var arrivalTopic = UUID.randomUUID();
        var nightTopic = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var arrivalThenNight = ID_GENERATOR.generateId();
        var nightThenArrival = ID_GENERATOR.generateId();
        insert(arrival(workspaceId, projectId, arrivalThenNight, NOON, arrivalTopic),
                night(workspaceId, projectId, nightThenArrival, NOON, nightTopic, runId));
        insert(night(workspaceId, projectId, arrivalThenNight, NOON, nightTopic, runId),
                arrival(workspaceId, projectId, nightThenArrival, NOON, arrivalTopic));

        assertThat(count(workspaceId, projectId, Set.of(runId))).containsExactlyInAnyOrder(
                TopicDayCount.builder().topicId(arrivalTopic).day(TODAY).traces(1).build(),
                TopicDayCount.builder().topicId(nightTopic).day(TODAY).traces(1).build());
    }

    @Test
    @DisplayName("rows of a run that did not save are skipped: the trace counts by its earlier row, a trace with only "
            + "such rows is not counted")
    void unsavedRunSkipped() {
        var workspaceId = workspaceId();
        var projectId = UUID.randomUUID();
        var savedTopic = UUID.randomUUID();
        var failedTopic = UUID.randomUUID();
        var savedRun = UUID.randomUUID();
        var failedRun = UUID.randomUUID();
        var traceId = ID_GENERATOR.generateId();
        insert(night(workspaceId, projectId, traceId, NOON, savedTopic, savedRun));
        insert(night(workspaceId, projectId, traceId, NOON, failedTopic, failedRun),
                night(workspaceId, projectId, ID_GENERATOR.generateId(), NOON, failedTopic, failedRun));

        assertThat(count(workspaceId, projectId, Set.of(savedRun)))
                .containsExactly(TopicDayCount.builder().topicId(savedTopic).day(TODAY).traces(1).build());
    }

    @Test
    @DisplayName("with no saved run only arrival rows count")
    void noSavedRun() {
        var workspaceId = workspaceId();
        var projectId = UUID.randomUUID();
        var arrivalTopic = UUID.randomUUID();
        insert(arrival(workspaceId, projectId, NOON, arrivalTopic),
                night(workspaceId, projectId, ID_GENERATOR.generateId(), NOON, UUID.randomUUID(),
                        UUID.randomUUID()));

        assertThat(count(workspaceId, projectId, Set.of()))
                .containsExactly(TopicDayCount.builder().topicId(arrivalTopic).day(TODAY).traces(1).build());
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

        assertThat(count(workspaceId, projectId, Set.of())).containsExactlyInAnyOrder(
                TopicDayCount.builder().topicId(topicId).day(TODAY).traces(2).build(),
                TopicDayCount.builder().topicId(topicId).day(TODAY.minusDays(1)).traces(1).build(),
                TopicDayCount.builder().topicId(TraceTopic.OTHER).day(TODAY).traces(1).build());
    }

    @Test
    @DisplayName("the same project id under another workspace is not counted")
    void otherWorkspace() {
        var workspaceId = workspaceId();
        var projectId = UUID.randomUUID();
        var topicId = UUID.randomUUID();
        insert(arrival(workspaceId, projectId, NOON, topicId),
                arrival(workspaceId(), projectId, NOON, topicId));

        assertThat(count(workspaceId, projectId, Set.of()))
                .containsExactly(TopicDayCount.builder().topicId(topicId).day(TODAY).traces(1).build());
    }

    @Test
    @DisplayName("saved run ids: only the project's saved runs, not running or failed ones, not another project's, "
            + "not the same project id under another workspace")
    void savedRunIds() {
        var workspaceId = workspaceId();
        var projectId = UUID.randomUUID();
        var saved = run(workspaceId, projectId, TopicRun.Status.SAVED);
        var savedToo = run(workspaceId, projectId, TopicRun.Status.SAVED);
        run(workspaceId, projectId, TopicRun.Status.RUNNING);
        run(workspaceId, projectId, TopicRun.Status.FAILED);
        run(workspaceId, UUID.randomUUID(), TopicRun.Status.SAVED);
        run(workspaceId(), projectId, TopicRun.Status.SAVED);

        var found = template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicRunDAO.class).findSavedRunIds(workspaceId, projectId));

        assertThat(found).containsExactlyInAnyOrder(saved, savedToo);
    }

    @Test
    @DisplayName("last two runs' counts: the two latest saved_at, newest first, by rank with Other last; not an older "
            + "run, not another project's, not the same project id under another workspace")
    void lastTwoRunCounts() {
        var workspaceId = workspaceId();
        var projectId = UUID.randomUUID();
        var topicId = UUID.randomUUID();
        saveRunCounts(workspaceId, projectId, NOON.minus(Duration.ofDays(2)), topicId);
        var previous = saveRunCounts(workspaceId, projectId, NOON.minus(Duration.ofDays(1)), topicId);
        var last = saveRunCounts(workspaceId, projectId, NOON, topicId);
        saveRunCounts(workspaceId, UUID.randomUUID(), NOON.plus(Duration.ofHours(1)), topicId);
        saveRunCounts(workspaceId(), projectId, NOON.plus(Duration.ofHours(1)), topicId);

        var found = template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicRunDAO.class).findLastTwoRunCounts(workspaceId, projectId));

        assertThat(found).usingRecursiveComparison()
                .isEqualTo(Stream.concat(last.stream(), previous.stream()).toList());
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
            assertThat(dao.findState(workspaceId, projectId))
                    .hasValueSatisfying(found -> assertThat(found).usingRecursiveComparison().isEqualTo(state));
            assertThat(dao.findState(workspaceId(), projectId)).isEmpty();
            return null;
        });
    }

    private List<TopicDayCount> count(String workspaceId, UUID projectId, Set<UUID> savedRunIds) {
        return traceTopicsDAO.countByTopicAndDay(workspaceId, projectId, FROM, TO, savedRunIds,
                MAX_EXECUTION_SECONDS).collectList().block();
    }

    private void insert(TraceTopic... rows) {
        traceTopicsDAO.insert(List.of(rows)).block();
    }

    private static TraceTopic arrival(String workspaceId, UUID projectId, Instant createdAt, UUID topicId) {
        return arrival(workspaceId, projectId, ID_GENERATOR.generateId(), createdAt, topicId);
    }

    private static TraceTopic arrival(String workspaceId, UUID projectId, UUID traceId, Instant createdAt,
            UUID topicId) {
        return row(workspaceId, projectId, traceId, createdAt, topicId, TraceTopic.Source.ARRIVAL, "");
    }

    private static TraceTopic night(String workspaceId, UUID projectId, UUID traceId, Instant createdAt,
            UUID topicId, UUID runId) {
        return row(workspaceId, projectId, traceId, createdAt, topicId, TraceTopic.Source.NIGHT, runId.toString());
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

    /** One saved run's counts, Other inserted first; returned as the read orders them, the topic then Other. */
    private List<TopicRunCount> saveRunCounts(String workspaceId, UUID projectId, Instant savedAt, UUID topicId) {
        var runId = ID_GENERATOR.generateId();
        var other = TopicRunCount.builder().runId(runId).workspaceId(workspaceId).projectId(projectId)
                .topicId(TraceTopic.OTHER).traces(3).savedAt(savedAt).build();
        var topic = other.toBuilder().topicId(topicId).traces(7).rank(1).build();
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicRunDAO.class).insertCounts(List.of(other, topic));
            return null;
        });
        return List.of(topic, other);
    }

    private UUID run(String workspaceId, UUID projectId, TopicRun.Status status) {
        var run = TopicRun.builder()
                .runId(ID_GENERATOR.generateId())
                .workspaceId(workspaceId)
                .projectId(projectId)
                .mode(TopicRun.Mode.NIGHTLY_UPDATE)
                .status(status)
                .startedAt(NOON)
                .build();
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicRunDAO.class).insert(run);
            return null;
        });
        return run.runId();
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
