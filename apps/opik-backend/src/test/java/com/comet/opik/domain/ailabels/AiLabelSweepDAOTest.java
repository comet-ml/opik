package com.comet.opik.domain.ailabels;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.PostCutoverTraces;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.ailabels.AiLabelSweepDAO.Candidate;
import com.comet.opik.domain.ailabels.AiLabelSweepDAO.SweepTask;
import com.comet.opik.domain.ailabels.AiLabelSweepDAO.WorkspaceHealth;
import com.comet.opik.infrastructure.AiLabelsConfig;
import io.r2dbc.spi.ConnectionFactory;
import lombok.Builder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ai-labels sweep")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiLabelSweepDAOTest {

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();
    private static final Instant LONG_AGO = Instant.parse("2020-01-01T00:00:00Z");
    private static final String PRODUCTION_QUERY_PARAMETERS = "custom_http_params=max_query_size=100000000";
    private static final SweepTask SUMMARY = new SweepTask("summary", List.of(1), List.of(LONG_AGO));

    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer(false, network);
    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer(false, network,
            zookeeper);

    private ConnectionFactory connectionFactory;
    private Connection connection;

    {
        Startables.deepStart(zookeeper, clickHouse).join();
    }

    @BeforeAll
    void beforeAll() throws Exception {
        PostCutoverTraces.migrate(clickHouse);
        var factory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouse, DATABASE_NAME);
        // The driver inlines bound arrays into the SQL text, so a bucket's workspace list counts toward
        // max_query_size. Production raises it on the connection (config.yml queryParameters); so does this test.
        factory.setQueryParameters(PRODUCTION_QUERY_PARAMETERS);
        connectionFactory = factory.build();
        connection = clickHouse.createConnection("");
    }

    @AfterAll
    void afterAll() throws Exception {
        connection.close();
        clickHouse.stop();
        zookeeper.stop();
    }

    @Nested
    @DisplayName("eligibility")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Eligibility {

        @Test
        @DisplayName("only ended traces, quiet for Q and created inside H, are candidates")
        void onlyEndedQuietTracesInsideHorizon() {
            var workspaceId = workspace();
            var eligible = trace(workspaceId).build().insert();
            trace(workspaceId).open(true).build().insert();
            trace(workspaceId).lastUpdatedSecondsAgo(10 * 60).build().insert();
            trace(workspaceId).createdSecondsAgo(8 * 24 * 3600).lastUpdatedSecondsAgo(8 * 24 * 3600).build().insert();
            trace(workspaceId).deleted(true).build().insert();

            assertThat(sweep(workspaceId)).extracting(Candidate::traceId).containsExactly(eligible.id());
        }

        @Test
        @DisplayName("the latest version decides: an ended older version doesn't count once a newer one is open")
        void latestVersionWins() {
            var workspaceId = workspace();
            var reopened = trace(workspaceId).lastUpdatedSecondsAgo(3 * 3600).build().insert();
            reopened.toBuilder().open(true).lastUpdatedSecondsAgo(2 * 3600).build().insert();
            var ended = trace(workspaceId).open(true).lastUpdatedSecondsAgo(3 * 3600).build().insert();
            ended.toBuilder().open(false).lastUpdatedSecondsAgo(2 * 3600).build().insert();

            assertThat(sweep(workspaceId)).extracting(Candidate::traceId).containsExactly(ended.id());
        }
    }

    @Nested
    @DisplayName("settled")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Settled {

        static Stream<Arguments> latestStatus() {
            // X = 2 attempts, D = 15 min, P = 24 h (the config defaults); a value of -1 means the pair is settled.
            // Each case has an older row first: pending, except where a settled older row must not win.
            return Stream.of(
                    Arguments.of("done", "pending", 0, "done", false, 1, 1, 60, -1),
                    Arguments.of("failed, not retryable", "pending", 0, "failed", false, 1, 1, 60 * 60, -1),
                    Arguments.of("failed, retryable, X attempts made", "pending", 0, "failed", true, 2, 1, 60 * 60,
                            -1),
                    Arguments.of("failed, retryable, below X, D elapsed", "pending", 0, "failed", true, 1, 1, 16 * 60,
                            1),
                    Arguments.of("failed, retryable, below X, inside D", "pending", 0, "failed", true, 1, 1, 14 * 60,
                            -1),
                    Arguments.of("pending, inside P", "pending", 0, "pending", false, 1, 1, 23 * 3600, -1),
                    Arguments.of("pending, P elapsed (an orphan)", "pending", 0, "pending", false, 1, 1, 25 * 3600, 1),
                    Arguments.of("done at a version below the one required", "pending", 0, "done", false, 1, 0, 60,
                            0),
                    Arguments.of("done, then a newer orphaned pending row", "done", 1, "pending", false, 2, 1,
                            25 * 3600, 2));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("latestStatus")
        void latestStatusDecides(String description, String olderStatus, int olderAttempt, String status,
                boolean retryable, int attempt, int taskVersion, long updatedSecondsAgo, int expectedAttempt) {
            var workspaceId = workspace();
            var trace = trace(workspaceId).build().insert();
            status(trace, "summary", olderStatus, false, olderAttempt, taskVersion, updatedSecondsAgo + 60);
            status(trace, "summary", status, retryable, attempt, taskVersion, updatedSecondsAgo);

            var candidates = sweep(workspaceId, List.of(SUMMARY.toBuilder().versions(List.of(1)).build()));

            if (expectedAttempt < 0) {
                assertThat(candidates).isEmpty();
            } else {
                assertThat(candidates).singleElement()
                        .extracting(Candidate::missing)
                        .isEqualTo(Map.of("summary", expectedAttempt));
            }
        }

        @Test
        @DisplayName("of two tasks only the unsettled one is missing; a task newer than the trace is absent")
        void missingListsOnlyUnsettledTasksThatApply() {
            var workspaceId = workspace();
            var trace = trace(workspaceId).build().insert();
            status(trace, "summary", "done", false, 1, 1, 60);
            status(trace, "topic", "failed", true, 1, 1, 20 * 60);
            var future = new SweepTask("future", List.of(1), List.of(Instant.now().plusSeconds(3600)));
            var topic = new SweepTask("topic", List.of(1), List.of(LONG_AGO));

            assertThat(sweep(workspaceId, List.of(SUMMARY, topic, future))).singleElement()
                    .extracting(Candidate::missing)
                    .isEqualTo(Map.of("topic", 1));
        }

        @Test
        @DisplayName("the required version follows applies_from: a relabel moves it earlier, a rollout doesn't")
        void requiredVersionFollowsAppliesFrom() {
            var workspaceId = workspace();
            var trace = trace(workspaceId).build().insert();
            status(trace, "summary", "done", false, 1, 1, 60);
            var rolledOut = new SweepTask("summary", List.of(1, 2), List.of(LONG_AGO, Instant.now().plusSeconds(60)));
            var relabel = new SweepTask("summary", List.of(1, 2), List.of(LONG_AGO, LONG_AGO.plusSeconds(1)));

            assertThat(sweep(workspaceId, List.of(rolledOut))).isEmpty();
            assertThat(sweep(workspaceId, List.of(relabel))).singleElement()
                    .extracting(Candidate::missing)
                    .isEqualTo(Map.of("summary", 0));
        }
    }

    @Nested
    @DisplayName("limits and buckets")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Limits {

        @Test
        @DisplayName("C caps each workspace on its own, so a big workspace can't crowd out a small one")
        void perWorkspaceLimit() {
            var big = workspace();
            var small = workspace();
            IntStream.range(0, 10).forEach(i -> trace(big).build().insert());
            IntStream.range(0, 2).forEach(i -> trace(small).build().insert());
            var config = new AiLabelsConfig();
            config.setPerWorkspaceCandidates(3);

            var candidates = new AiLabelSweepDAO(connectionFactory, config)
                    .sweep(List.of(big, small), List.of(SUMMARY), 0)
                    .collectList()
                    .block();

            assertThat(candidates).extracting(Candidate::workspaceId)
                    .filteredOn(big::equals).hasSize(3);
            assertThat(candidates).extracting(Candidate::workspaceId)
                    .filteredOn(small::equals).hasSize(2);
        }

        @Test
        @DisplayName("a bucket of 10,000 workspace ids fits, with production's max_query_size")
        void tenThousandWorkspaceIds() {
            var workspaceId = workspace();
            var trace = trace(workspaceId).build().insert();
            var bucket = new ArrayList<String>();
            IntStream.range(0, 9_999).forEach(i -> bucket.add(UUID.randomUUID().toString()));
            bucket.add(workspaceId);

            var candidates = dao().sweep(bucket, List.of(SUMMARY), 0).collectList().block();

            assertThat(candidates).extracting(Candidate::traceId).containsExactly(trace.id());
        }

        @Test
        @DisplayName("a guard trip surfaces as its ClickHouse code")
        void guardTrip() {
            var workspaceId = workspace();
            IntStream.range(0, 3).forEach(i -> trace(workspaceId).build().insert());
            var config = new AiLabelsConfig();
            config.getSweep().setMaxRowsToRead(1);

            assertThatThrownBy(() -> new AiLabelSweepDAO(connectionFactory, config)
                    .sweep(List.of(workspaceId), List.of(SUMMARY), 0)
                    .collectList()
                    .block())
                    .satisfies(error -> assertThat(AiLabelSweepService.guardCode(error)).contains("TOO_MANY_ROWS"));
        }
    }

    @Nested
    @DisplayName("headline")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Headline {

        @Test
        @DisplayName("counts unsettled pairs, those past the SLO, and never-ended traces")
        void countsMatchTheFixture() {
            var workspaceId = workspace();
            var topic = new SweepTask("topic", List.of(1), List.of(LONG_AGO));
            // Past the SLO (Q + slack = 45 min): created and last updated 2 h ago, both tasks missing.
            trace(workspaceId).createdSecondsAgo(2 * 3600).lastUpdatedSecondsAgo(2 * 3600).build().insert();
            // Unsettled but inside the SLO: created 40 min ago, quiet for 35, one task done.
            var recent = trace(workspaceId).createdSecondsAgo(40 * 60).lastUpdatedSecondsAgo(35 * 60).build().insert();
            status(recent, "summary", "done", false, 1, 1, 60);
            // Settled for both tasks.
            var settled = trace(workspaceId).build().insert();
            status(settled, "summary", "done", false, 1, 1, 60);
            status(settled, "topic", "done", false, 1, 1, 60);
            trace(workspaceId).open(true).build().insert();
            trace(workspaceId).open(true).build().insert();

            var health = dao().health(List.of(workspaceId), List.of(SUMMARY, topic), 0).collectList().block();
            var neverEnded = dao().neverEnded(List.of(workspaceId), 0).block();

            assertThat(health).containsExactly(new WorkspaceHealth(workspaceId, 3, 2));
            assertThat(neverEnded).isEqualTo(2);
        }
    }

    /** Pinned values, so a change to the hash or to its input moves every workspace's bucket visibly. */
    @Test
    @DisplayName("a workspace's bucket is murmur3_32 of its id mod N")
    void bucketAssignment() {
        assertThat(AiLabelSweepService.bucketOf("0192f0c4-4d1e-7a6b-9c3d-2e1f0a9b8c7d", 8)).isEqualTo(5);
        assertThat(AiLabelSweepService.bucketOf("7e3c1a90-55d2-4b1f-9a8e-0d4c6b2e1f3a", 8)).isEqualTo(2);
        assertThat(AiLabelSweepService.bucketOf("a0000000-0000-4000-8000-000000000000", 8)).isEqualTo(3);
    }

    private AiLabelSweepDAO dao() {
        return new AiLabelSweepDAO(connectionFactory, new AiLabelsConfig());
    }

    private List<Candidate> sweep(String workspaceId) {
        return sweep(workspaceId, List.of(SUMMARY));
    }

    private List<Candidate> sweep(String workspaceId, List<SweepTask> tasks) {
        return dao().sweep(List.of(workspaceId), tasks, 0).collectList().block();
    }

    private static String workspace() {
        return UUID.randomUUID().toString();
    }

    private TraceFixture.TraceFixtureBuilder trace(String workspaceId) {
        return TraceFixture.builder()
                .connection(connection)
                .workspaceId(workspaceId)
                .projectId(ID_GENERATOR.generateId())
                .id(ID_GENERATOR.generateId());
    }

    private void status(TraceFixture trace, String task, String status, boolean retryable, int attempt,
            int taskVersion, long updatedSecondsAgo) {
        execute("""
                INSERT INTO %s.ai_label_status (workspace_id, project_id, task, trace_id, trace_created_at, task_version,
                    renderer_version, status, retryable, attempt, updated_at)
                SELECT '%s', '%s', '%s', '%s', created_at, %d, 1, '%s', %b, %d, now64(6) - toIntervalSecond(%d)
                FROM %s.%s WHERE id = '%s' LIMIT 1
                """
                .formatted(DATABASE_NAME, trace.workspaceId(), trace.projectId(), task, trace.id(), taskVersion,
                        status, retryable, attempt, updatedSecondsAgo, DATABASE_NAME, PostCutoverTraces.SHARD,
                        trace.id()));
    }

    private void execute(String sql) {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Builder(toBuilder = true)
    private record TraceFixture(Connection connection, String workspaceId, UUID projectId, UUID id,
            long createdSecondsAgo,
            long lastUpdatedSecondsAgo,
            boolean open,
            boolean deleted) {

        private static final long DEFAULT_CREATED_SECONDS_AGO = 3 * 3600;

        TraceFixture {
            createdSecondsAgo = createdSecondsAgo == 0 ? DEFAULT_CREATED_SECONDS_AGO : createdSecondsAgo;
            lastUpdatedSecondsAgo = lastUpdatedSecondsAgo == 0 ? createdSecondsAgo : lastUpdatedSecondsAgo;
        }

        TraceFixture insert() {
            var sql = """
                    INSERT INTO %s.%s (workspace_id, project_id, id, name, start_time, end_time, created_at,
                        last_updated_at, is_deleted)
                    VALUES ('%s', '%s', '%s', 'trace', now64(6) - toIntervalSecond(%d), %s,
                        now64(6) - toIntervalSecond(%d), now64(6) - toIntervalSecond(%d), %d)
                    """.formatted(DATABASE_NAME, PostCutoverTraces.SHARD, workspaceId, projectId, id,
                    createdSecondsAgo,
                    open
                            ? "toDateTime64(0, 6)"
                            : "now64(6) - toIntervalSecond(%d)".formatted(lastUpdatedSecondsAgo),
                    createdSecondsAgo, lastUpdatedSecondsAgo, deleted ? 1 : 0);
            try (var statement = connection.createStatement()) {
                statement.execute(sql);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
            return this;
        }
    }
}
