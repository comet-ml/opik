package com.comet.opik.domain.topics;

import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.infrastructure.TopicsConfig;
import org.assertj.core.groups.Tuple;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxAction;
import ru.vyarus.guicey.jdbi3.tx.TxConfig;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The night tables against MySQL: the reads behind the state gauges, what housekeeping fails and deletes, and the
 * gauges a refresh publishes. Each test starts from empty night, run and opt-in tables.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("topics night record and state gauges")
class TopicNightsTest {

    private static final String WORKSPACE_ID = "ws-1";
    private static final String QUIET_WORKSPACE_ID = "ws-quiet";
    private static final String MISMATCHED_WORKSPACE_ID = "ws-mismatched";
    private static final LocalDate NIGHT = LocalDate.of(2026, 11, 2);

    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer();
    private Jdbi jdbi;
    private TopicsTestMeter meter;
    private MutableClock clock;
    private TopicsConfig config;
    private AtomicBoolean down;
    private TopicsNightlyGauges gauges;

    @BeforeAll
    void setUpAll() {
        mysql.start();
        MigrationUtils.runMysqlDbMigration(mysql);
        jdbi = Jdbi.create(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .installPlugin(new SqlObjectPlugin());
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbi.useHandle(handle -> {
            for (var table : List.of("topic_night_projects", "topic_nights", "topic_runs", "topic_workspaces",
                    "ai_label_workspaces")) {
                handle.execute("DELETE FROM " + table);
            }
        });
        meter = new TopicsTestMeter();
        clock = new MutableClock(NIGHT.plusDays(1).atTime(12, 0).toInstant(ZoneOffset.UTC));
        config = new TopicsConfig();
        config.setEnabled(true);
        down = new AtomicBoolean();
        var template = mock(TransactionTemplate.class);
        when(template.inTransaction(any(TxConfig.class), any())).thenAnswer(invocation -> {
            if (down.get()) {
                throw new IllegalStateException("MySQL down");
            }
            return jdbi.inTransaction(handle -> invocation.<TxAction<?>>getArgument(1).execute(handle));
        });
        gauges = new TopicsNightlyGauges(config, template,
                new TopicsWorkspaceNames(workspaceId -> "name of " + workspaceId, clock), meter.meter, clock);
        optIn(WORKSPACE_ID, true);
        optIn(QUIET_WORKSPACE_ID, true);
        optIn(MISMATCHED_WORKSPACE_ID, false);
    }

    @Test
    @DisplayName("stale: expected on each of the last 3 nights, no saved run since; quiet, deleted and listed projects "
            + "are left out")
    void stale() {
        var stale = project(WORKSPACE_ID);
        var small = project(WORKSPACE_ID);
        var listed = project(WORKSPACE_ID);
        var savedByHand = project(WORKSPACE_ID);
        var twoNights = project(WORKSPACE_ID);
        var deleted = project(WORKSPACE_ID);
        var notReached = project(WORKSPACE_ID);
        var otherWorkspace = project(MISMATCHED_WORKSPACE_ID);
        for (int i = 0; i < 3; i++) {
            var night = NIGHT.minusDays(i);
            ended(night, TopicNight.Outcome.FINISHED);
            row(night, WORKSPACE_ID, stale, "first_build", null, run(WORKSPACE_ID, stale, "failed", night));
            row(night, WORKSPACE_ID, small, "skip", "small", null);
            row(night, WORKSPACE_ID, listed, "skip", "already_saved", null);
            row(night, WORKSPACE_ID, savedByHand, "first_build", null, null);
            row(night, WORKSPACE_ID, deleted, "first_build", null, null);
            row(night, WORKSPACE_ID, notReached, "not_reached", null, null);
            row(night, MISMATCHED_WORKSPACE_ID, otherWorkspace, "first_build", null, null);
            if (i < 2) {
                row(night, WORKSPACE_ID, twoNights, "first_build", null, null);
            }
        }
        run(WORKSPACE_ID, savedByHand, "saved", NIGHT);
        jdbi.useHandle(handle -> handle.execute("DELETE FROM projects WHERE id = ?", deleted.toString()));

        refresh();

        assertThat(levels(TopicsNightlyGauges.STALE_PROJECTS)).containsExactlyInAnyOrder(
                tuple(WORKSPACE_ID, "name of " + WORKSPACE_ID, 2.0),
                tuple(QUIET_WORKSPACE_ID, "name of " + QUIET_WORKSPACE_ID, 0.0));
    }

    @Test
    @DisplayName("stale needs 3 ended nights, starved 2; a running night does not count")
    void fewerNights() {
        var project = project(WORKSPACE_ID);
        ended(NIGHT.minusDays(1), TopicNight.Outcome.FINISHED);
        ended(NIGHT, TopicNight.Outcome.FAILED);
        dao().start(NIGHT.plusDays(1), clock.instant().minusSeconds(60));
        for (var night : List.of(NIGHT.minusDays(1), NIGHT, NIGHT.plusDays(1))) {
            row(night, WORKSPACE_ID, project, "not_reached", null, null);
        }

        refresh();

        assertThat(levels(TopicsNightlyGauges.STALE_PROJECTS)).extracting(level -> level.toList().get(2))
                .containsOnly(0.0);
        assertThat(levels(TopicsNightlyGauges.STARVED_PROJECTS)).contains(
                tuple(WORKSPACE_ID, "name of " + WORKSPACE_ID, 1.0));
        assertThat(gauge(TopicsNightlyGauges.RUNNING)).isEqualTo(60.0);
    }

    @Test
    @DisplayName("starved: not_reached on each of the last 2 nights")
    void starved() {
        var starved = project(WORKSPACE_ID);
        var once = project(WORKSPACE_ID);
        ended(NIGHT.minusDays(1), TopicNight.Outcome.FINISHED);
        ended(NIGHT, TopicNight.Outcome.FINISHED);
        row(NIGHT.minusDays(1), WORKSPACE_ID, starved, "not_reached", null, null);
        row(NIGHT, WORKSPACE_ID, starved, "not_reached", null, null);
        row(NIGHT.minusDays(1), WORKSPACE_ID, once, "first_build", null, null);
        row(NIGHT, WORKSPACE_ID, once, "not_reached", null, null);

        refresh();

        assertThat(levels(TopicsNightlyGauges.STARVED_PROJECTS)).containsExactlyInAnyOrder(
                tuple(WORKSPACE_ID, "name of " + WORKSPACE_ID, 1.0),
                tuple(QUIET_WORKSPACE_ID, "name of " + QUIET_WORKSPACE_ID, 0.0));
    }

    @Test
    @DisplayName("the latest ended night's projects by decision and reason, and its runs by status; a run that could "
            + "not start is failed")
    void lastNight() {
        ended(NIGHT.minusDays(1), TopicNight.Outcome.FINISHED);
        row(NIGHT.minusDays(1), WORKSPACE_ID, project(WORKSPACE_ID), "skip", "deleted", null);
        ended(NIGHT, TopicNight.Outcome.FINISHED);
        var saved = project(WORKSPACE_ID);
        var failed = project(WORKSPACE_ID);
        row(NIGHT, WORKSPACE_ID, saved, "first_build", null, run(WORKSPACE_ID, saved, "saved", NIGHT));
        row(NIGHT, WORKSPACE_ID, failed, "first_build", null, run(WORKSPACE_ID, failed, "failed", NIGHT));
        row(NIGHT, WORKSPACE_ID, project(WORKSPACE_ID), "first_build", null, null);
        row(NIGHT, WORKSPACE_ID, project(WORKSPACE_ID), "skip", "small", null);
        row(NIGHT, WORKSPACE_ID, project(WORKSPACE_ID), "skip", "small", null);
        row(NIGHT, QUIET_WORKSPACE_ID, null, TopicNightProject.COUNT_FAILED, null, null);
        row(NIGHT, MISMATCHED_WORKSPACE_ID, project(MISMATCHED_WORKSPACE_ID), "first_build", null, null);

        refresh();

        assertThat(meter.observe(TopicsNightlyGauges.LAST_NIGHT_PROJECTS)).extracting(
                point -> point.get("workspace_id"), point -> point.get("decision"), point -> point.get("reason"),
                TopicsTestMeter.Point::value)
                .containsExactlyInAnyOrder(
                        tuple(WORKSPACE_ID, "first_build", null, 3.0),
                        tuple(WORKSPACE_ID, "skip", "small", 2.0),
                        tuple(QUIET_WORKSPACE_ID, "count_failed", null, 1.0));
        assertThat(meter.observe(TopicsNightlyGauges.LAST_NIGHT_RUNS)).extracting(
                point -> point.get("workspace_id"), point -> point.get("status"), TopicsTestMeter.Point::value)
                .containsExactlyInAnyOrder(
                        tuple(WORKSPACE_ID, "saved", 1.0),
                        tuple(WORKSPACE_ID, "failed", 2.0));
    }

    @Test
    @DisplayName("ages come from the kept timestamps at each scrape, with no read")
    void ages() {
        ended(NIGHT, TopicNight.Outcome.FINISHED);

        refresh();
        clock.advance(Duration.ofMinutes(5));
        down.set(true);

        var started = NIGHT.atTime(2, 0).toInstant(ZoneOffset.UTC);
        assertThat(gauge(TopicsNightlyGauges.LAST_STARTED_AGE))
                .isEqualTo(Duration.between(started, clock.instant()).toSeconds());
        assertThat(gauge(TopicsNightlyGauges.RUNNING)).isZero();
        assertThat(gauge(TopicsNightlyGauges.STATE_AGE)).isEqualTo(300.0);
    }

    @Test
    @DisplayName("a workspace opted in to topics and not to ai-labels is mismatched")
    void mismatched() {
        ended(NIGHT, TopicNight.Outcome.FINISHED);

        refresh();

        assertThat(levels(TopicsNightlyGauges.MISMATCHED)).containsExactly(
                tuple(MISMATCHED_WORKSPACE_ID, "name of " + MISMATCHED_WORKSPACE_ID, 1.0));
    }

    @Test
    @DisplayName("off, nothing is published, the heartbeat included")
    void off() {
        ended(NIGHT, TopicNight.Outcome.FINISHED);
        config.getNightly().setEnabled(false);

        gauges.begin();
        gauges.refresh();

        for (var name : List.of(TopicsNightlyGauges.STATE_AGE, TopicsNightlyGauges.LAST_STARTED_AGE,
                TopicsNightlyGauges.RUNNING, TopicsNightlyGauges.LAST_NIGHT_PROJECTS,
                TopicsNightlyGauges.LAST_NIGHT_RUNS, TopicsNightlyGauges.STALE_PROJECTS,
                TopicsNightlyGauges.STARVED_PROJECTS, TopicsNightlyGauges.MISMATCHED)) {
            assertThat(meter.hasGauge(name)).as(name).isTrue();
            assertThat(meter.observe(name)).as(name).isEmpty();
        }
    }

    @Test
    @DisplayName("before the first night only the heartbeat is published, and the failure counter is set to 0")
    void beforeFirstNight() {
        refresh();

        assertThat(gauge(TopicsNightlyGauges.STATE_AGE)).isZero();
        assertThat(meter.points(TopicsNightlyGauges.STATE_REFRESH_FAILURES)).singleElement()
                .satisfies(point -> assertThat(point.value()).isZero());
        for (var name : List.of(TopicsNightlyGauges.LAST_STARTED_AGE, TopicsNightlyGauges.RUNNING,
                TopicsNightlyGauges.LAST_NIGHT_PROJECTS, TopicsNightlyGauges.STALE_PROJECTS,
                TopicsNightlyGauges.MISMATCHED)) {
            assertThat(meter.observe(name)).as(name).isEmpty();
        }
    }

    @Test
    @DisplayName("a read that fails is counted, keeps the last values, and the heartbeat ages from the last read")
    void refreshFails() {
        down.set(true);
        gauges.begin();
        clock.advance(Duration.ofMinutes(2));
        gauges.refresh();

        assertThat(meter.sum(TopicsNightlyGauges.STATE_REFRESH_FAILURES)).isEqualTo(1);
        assertThat(gauge(TopicsNightlyGauges.STATE_AGE)).isEqualTo(120.0);

        down.set(false);
        ended(NIGHT, TopicNight.Outcome.FINISHED);
        gauges.refresh();
        down.set(true);
        clock.advance(Duration.ofMinutes(11));
        gauges.refresh();

        assertThat(meter.sum(TopicsNightlyGauges.STATE_REFRESH_FAILURES)).isEqualTo(2);
        assertThat(gauge(TopicsNightlyGauges.STATE_AGE)).isEqualTo(660.0);
        assertThat(meter.observe(TopicsNightlyGauges.LAST_STARTED_AGE)).hasSize(1);
    }

    @Test
    @DisplayName("an abandoned run of a night that finished was counted by that night; others are not")
    void abandoned() {
        var old = NIGHT.minusDays(1).atTime(2, 0).toInstant(ZoneOffset.UTC);
        var counted = runAt(WORKSPACE_ID, project(WORKSPACE_ID), "running", old);
        var ofFailedNight = runAt(WORKSPACE_ID, project(WORKSPACE_ID), "running", old);
        runAt("ws-2", project("ws-2"), "running", old);
        ended(NIGHT.minusDays(1), TopicNight.Outcome.FINISHED);
        row(NIGHT.minusDays(1), WORKSPACE_ID, null, "first_build", null, counted);
        ended(NIGHT.minusDays(2), TopicNight.Outcome.FAILED);
        row(NIGHT.minusDays(2), WORKSPACE_ID, null, "first_build", null, ofFailedNight);

        var workspaceIds = jdbi.inTransaction(handle -> handle.attach(TopicRunDAO.class)
                .findAbandonedWorkspaceIds(clock.instant()));

        assertThat(workspaceIds).containsExactlyInAnyOrder(WORKSPACE_ID, "ws-2");
    }

    @Test
    @DisplayName("housekeeping fails a night left running past its lease, and deletes nights past the retention")
    void housekeeping() {
        var dao = dao();
        dao.start(NIGHT, clock.instant().minus(Duration.ofHours(4)));
        dao.start(NIGHT.plusDays(1), clock.instant().minus(Duration.ofMinutes(5)));
        ended(NIGHT.minusDays(100), TopicNight.Outcome.FINISHED);
        row(NIGHT.minusDays(100), WORKSPACE_ID, null, TopicNightProject.COUNT_FAILED, null, null);
        row(NIGHT, WORKSPACE_ID, null, TopicNightProject.COUNT_FAILED, null, null);

        assertThat(dao.failAbandoned(clock.instant().minus(Duration.ofHours(3)), clock.instant())).isEqualTo(1);
        assertThat(dao.deleteNightsBefore(NIGHT.minusDays(90))).isEqualTo(1);
        assertThat(dao.deleteProjectsBefore(NIGHT.minusDays(90))).isEqualTo(1);

        assertThat(dao.findByNight(NIGHT).outcome()).isEqualTo(TopicNight.Outcome.FAILED);
        assertThat(dao.findByNight(NIGHT.plusDays(1)).outcome()).isEqualTo(TopicNight.Outcome.RUNNING);
        assertThat(dao.findProjects(NIGHT)).hasSize(1);
    }

    @Test
    @DisplayName("a night run again for the same date starts over")
    void startOver() {
        var dao = dao();
        ended(NIGHT, TopicNight.Outcome.FAILED);
        var again = clock.instant();

        dao.start(NIGHT, again);

        assertThat(dao.findByNight(NIGHT)).satisfies(night -> {
            assertThat(night.outcome()).isEqualTo(TopicNight.Outcome.RUNNING);
            assertThat(night.startedAt()).isEqualTo(again);
            assertThat(night.finishedAt()).isNull();
            assertThat(night.projects()).isNull();
        });
    }

    private void refresh() {
        gauges.begin();
        gauges.refresh();
    }

    /** The gauge's single value. */
    private double gauge(String name) {
        return meter.observe(name).getFirst().value();
    }

    /** Workspace id, workspace name and value of each series. */
    private List<Tuple> levels(String name) {
        return meter.observe(name).stream()
                .map(point -> tuple(point.get("workspace_id"), point.get("workspace_name"), point.value()))
                .toList();
    }

    private TopicNightDAO dao() {
        return jdbi.onDemand(TopicNightDAO.class);
    }

    /** A night that started at 02:00 UTC and ended with {@code outcome}. */
    private void ended(LocalDate night, TopicNight.Outcome outcome) {
        var startedAt = night.atTime(2, 0).toInstant(ZoneOffset.UTC);
        dao().end(TopicNight.builder().night(night).startedAt(startedAt).finishedAt(startedAt.plusSeconds(600))
                .outcome(outcome).projects("{}").build());
    }

    private void row(LocalDate night, String workspaceId, UUID projectId, String decision, String reason,
            UUID runId) {
        dao().insertProjects(List.of(TopicNightProject.builder().id(UUID.randomUUID()).night(night)
                .workspaceId(workspaceId).projectId(projectId).vectors(projectId == null ? null : 120L)
                .decision(decision).reason(reason).runId(runId).build()));
    }

    private UUID project(String workspaceId) {
        var projectId = UUID.randomUUID();
        jdbi.useHandle(handle -> handle.execute("INSERT INTO projects (id, name, workspace_id) VALUES (?, ?, ?)",
                projectId.toString(), "project " + projectId, workspaceId));
        return projectId;
    }

    /** A run started during {@code night}. */
    private UUID run(String workspaceId, UUID projectId, String status, LocalDate night) {
        return runAt(workspaceId, projectId, status, night.atTime(2, 5).toInstant(ZoneOffset.UTC));
    }

    private UUID runAt(String workspaceId, UUID projectId, String status, Instant startedAt) {
        var runId = UUID.randomUUID();
        jdbi.useHandle(handle -> handle.execute("INSERT INTO topic_runs (run_id, workspace_id, project_id, mode, "
                + "status, started_at) VALUES (?, ?, ?, 'first_build', ?, ?)", runId.toString(), workspaceId,
                projectId.toString(), status, startedAt.truncatedTo(ChronoUnit.MICROS)));
        return runId;
    }

    /** Enabled in topics; in ai-labels too when {@code aiLabels}. */
    private void optIn(String workspaceId, boolean aiLabels) {
        jdbi.useHandle(handle -> {
            handle.attach(TopicWorkspaceDAO.class).upsert(workspaceId, true, "test");
            if (aiLabels) {
                handle.execute("INSERT INTO ai_label_workspaces (workspace_id, enabled, added_by) VALUES (?, TRUE, ?)",
                        workspaceId, "test");
            }
        });
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
