package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.ProjectTopics;
import com.comet.opik.api.ProjectTopics.TopicStats;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.PostCutoverTraces;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.ailabels.AiLabelWorkspaceDAO;
import com.comet.opik.domain.topics.Topic;
import com.comet.opik.domain.topics.TopicDAO;
import com.comet.opik.domain.topics.TopicProjectState;
import com.comet.opik.domain.topics.TopicRun;
import com.comet.opik.domain.topics.TopicRunDAO;
import com.comet.opik.domain.topics.TopicWorkspaceDAO;
import com.comet.opik.domain.topics.TraceTopic;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.podam.PodamFactoryUtils;
import com.google.inject.Injector;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.core.HttpHeaders;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
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
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.infrastructure.RateLimitConfig.LimitConfig;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Load test 7.5 of the topics read API brief, and a check of the runbook's "Reading a project by hand" queries against
 * the API. Runs only with {@code -Dtopics.loadTest=true}; writes its numbers to {@code target/topics-read-load-test.md}.
 */
@DisplayName("topics read API load test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
@EnabledIfSystemProperty(named = "topics.loadTest", matches = "true")
class TopicsReadLoadTest {

    private static final String RESOURCE_PATH = "%s/v1/private/topics";
    private static final String USER = "user-" + UUID.randomUUID();
    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();
    private static final int TRACES = 30_000;
    private static final int NIGHTS = 30;
    private static final int ARRIVALS = 2_000;
    private static final int ARRIVAL_BATCH = 100;
    private static final int LIVE_TOPICS = 10;
    private static final int CALLS = 100;
    private static final int FAILED_TRACES = 1_000;
    private static final String COMMENT = "topics:count_by_topic_and_day:";
    private static final String OTHER = TraceTopic.OTHER.toString();
    private static final Path REPORT = Path.of("target", "topics-read-load-test.md");
    private static final Path RUNBOOK = Path.of("docs", "ai-labels-runbook.md");

    private final RedisContainer redis = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer(zookeeper);
    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer();
    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redis, clickHouse, mysql, zookeeper).join();
        wireMock = WireMockUtils.startWireMock();
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
                .runtimeInfo(wireMock.runtimeInfo())
                .redisUrl(redis.getRedisURI())
                .rateLimitEnabled(true)
                .limit(100_000L)
                .workspaceLimit(100_000L)
                .limitDurationInSeconds(60L)
                .customLimits(Map.of("getProjectTopics", new LimitConfig("Get-Project-Topics",
                        "get_project_topics", 100_000, 60, "You have exceeded the rate limit for this operation.")))
                .customConfigs(List.of(
                        new CustomConfig("databaseAnalyticsDataModel.traceColumnsNonNullable", "true"),
                        new CustomConfig("databaseAnalyticsDataModel.tracesDistributedWrapEnabled", "true"),
                        new CustomConfig("aiLabels.enabled", "true"),
                        new CustomConfig("aiLabels.inference.url", wireMock.server().baseUrl()),
                        new CustomConfig("aiLabels.inference.credential", "test-credential"),
                        new CustomConfig("aiLabels.tickInterval", "1h"),
                        new CustomConfig("aiLabels.healthInterval", "1h"),
                        new CustomConfig("topics.enabled", "true"),
                        new CustomConfig("topics.vectors.tickInterval", "1h"),
                        new CustomConfig("topics.nightly.enabled", "false"),
                        new CustomConfig("topics.arrival.enabled", "false")))
                .build());
    }

    private String baseUrl;
    private ClientSupport client;
    private ProjectResourceClient projectClient;
    private TransactionTemplate template;

    @BeforeAll
    void setUpAll(ClientSupport client, Injector injector) {
        this.client = client;
        this.baseUrl = TestUtils.getBaseUrl(client);
        this.projectClient = new ProjectResourceClient(client, baseUrl, PodamFactoryUtils.newPodamFactory());
        this.template = injector.getInstance(TransactionTemplate.class);
    }

    @Test
    @Timeout(value = 20, unit = java.util.concurrent.TimeUnit.MINUTES)
    @DisplayName("30,000 traces x 30 saved nights + 2,000 arrival rows: 100 calls, query_log, argMax, runbook SQL")
    void load() throws Exception {
        var report = new StringBuilder("# Topics read API: load test 7.5 and runbook check\n\n");
        var apiKey = UUID.randomUUID().toString();
        var workspaceName = "ws-" + UUID.randomUUID();
        var workspaceId = UUID.randomUUID().toString();
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
        var projectId = projectClient.createProject("topics-load-" + UUID.randomUUID(), apiKey, workspaceName);
        var today = LocalDate.now(ZoneOffset.UTC);
        var windowFrom = today.minusDays(29);

        var live = IntStream.range(0, LIVE_TOPICS)
                .mapToObj(i -> topic(workspaceId, projectId, i < 8 ? windowFrom : today.minusDays(i), null))
                .toList();
        var retired = topic(workspaceId, projectId, windowFrom, today.minusDays(1));
        var runs = IntStream.range(0, NIGHTS)
                .mapToObj(i -> run(workspaceId, projectId, TopicRun.Status.SAVED,
                        Instant.now().minus(NIGHTS - i, ChronoUnit.DAYS)))
                .toList();
        var lastRun = runs.getLast();
        template.inTransaction(WRITE, handle -> {
            handle.attach(AiLabelWorkspaceDAO.class).upsert(workspaceId, true, "test");
            handle.attach(TopicWorkspaceDAO.class).upsert(workspaceId, true, "test");
            var topicDAO = handle.attach(TopicDAO.class);
            var all = new ArrayList<>(live);
            all.add(retired);
            topicDAO.insertTopics(all);
            var runDAO = handle.attach(TopicRunDAO.class);
            runs.forEach(runDAO::insert);
            topicDAO.insertState(TopicProjectState.builder()
                    .projectId(projectId)
                    .workspaceId(workspaceId)
                    .rebuilds(NIGHTS)
                    .firstBuiltAt(windowFrom.atTime(2, 0).toInstant(ZoneOffset.UTC))
                    .lastRunId(lastRun.runId())
                    .lastSuccessAt(lastRun.startedAt().truncatedTo(ChronoUnit.MICROS))
                    .windowUntil(today.atStartOfDay().toInstant(ZoneOffset.UTC))
                    .build());
            return null;
        });

        // Night k labels every trace with topic (n * 7 + k) % 11 of [live..., Other]; the last night's label wins.
        var labels = new ArrayList<>(live.stream().map(topic -> topic.id().toString()).toList());
        labels.add(OTHER);
        var labelArray = labels.stream().map(id -> "'" + id + "'").collect(Collectors.joining(",", "[", "]"));
        var tracesTable = "%s.topics_load_traces_%s".formatted(DATABASE_NAME,
                UUID.randomUUID().toString().replace("-", ""));
        var savedRunIds = runs.stream().map(run -> "'" + run.runId() + "'").collect(Collectors.joining(","));
        var where = """
                workspace_id = '%s' AND project_id = '%s'
                AND trace_created_at >= toDateTime64('%s 00:00:00', 6, 'UTC')
                AND trace_created_at < toDateTime64('%s 00:00:00', 6, 'UTC')
                AND (source = 'arrival' OR run_id IN (%s))
                """.formatted(workspaceId, projectId, windowFrom, today.plusDays(1), savedRunIds);
        var expectedTotal = (long) TRACES + ARRIVALS;
        try (var ch = clickHouseConnection(); var statement = ch.createStatement()) {
            // The worst case keeps every night's rows. Background merges collapse a trace's older rows within seconds
            // here, so they stay stopped for the first measurement.
            statement.execute("SYSTEM STOP MERGES %s.trace_topics".formatted(DATABASE_NAME));
            try {
                statement.execute("CREATE TABLE %s (n UInt32, trace_id String, trace_created_at DateTime64(6, 'UTC')) "
                        .formatted(tracesTable) + "ENGINE = Memory");
                statement.execute("""
                        INSERT INTO %s
                        SELECT number, toString(generateUUIDv7(number)),
                            addSeconds(addDays(toDateTime64('%s 00:00:00', 6, 'UTC'), number %% 30),
                                3600 + intDiv(number, 30))
                        FROM numbers(%d)
                        """.formatted(tracesTable, windowFrom, TRACES));
                for (int k = 0; k < NIGHTS; k++) {
                    statement.execute("""
                            INSERT INTO %s.trace_topics
                                (workspace_id, project_id, trace_id, trace_created_at, topic_id, source, run_id,
                                updated_at)
                            SELECT '%s', '%s', trace_id, trace_created_at, %s[((n * 7 + %d) %% 11) + 1], 'night',
                                '%s', now64(6) - toIntervalMinute(%d)
                            FROM %s
                            SETTINGS insert_deduplicate = 0
                            """.formatted(DATABASE_NAME, workspaceId, projectId, labelArray, k,
                            runs.get(k).runId(), 60 - k, tracesTable));
                }
                // Arrival ticks after the last night: every 20th row names the retired topic (stale).
                for (int batch = 0; batch < ARRIVALS / ARRIVAL_BATCH; batch++) {
                    statement.execute("""
                            INSERT INTO %s.trace_topics
                                (workspace_id, project_id, trace_id, trace_created_at, topic_id, source, run_id)
                            SELECT '%s', '%s', toString(generateUUIDv7(number)),
                                greatest(now64(6, 'UTC') - toIntervalSecond(number + %d),
                                    toDateTime64('%s 00:00:00', 6, 'UTC')),
                                if(number %% 20 = 0, '%s', %s[(number %% 11) + 1]), 'arrival', ''
                            FROM numbers(%d)
                            SETTINGS insert_deduplicate = 0
                            """.formatted(DATABASE_NAME, workspaceId, projectId, batch * ARRIVAL_BATCH, today,
                            retired.id(), labelArray, ARRIVAL_BATCH));
                }
                var rows = projectRows(statement, workspaceId);
                assertThat(rows).isEqualTo((long) TRACES * NIGHTS + ARRIVALS);
                report.append(("Seed: %,d traces x %d saved nights + %,d arrival rows in %d inserts, %d live topics + "
                        + "1 retired.\n\n").formatted(TRACES, NIGHTS, ARRIVALS, ARRIVALS / ARRIVAL_BATCH,
                                LIVE_TOPICS))
                        .append("## A. Unmerged: merges stopped, %,d rows, %d active parts\n\n".formatted(rows,
                                activeParts(statement)));
                measure(report, statement, apiKey, workspaceName, projectId, today, expectedTotal, where);
            } finally {
                statement.execute("SYSTEM START MERGES %s.trace_topics".formatted(DATABASE_NAME));
            }

            waitForMerges(statement);
            report.append("## B. After background merges, no OPTIMIZE: %,d rows, %d active parts\n\n".formatted(
                    projectRows(statement, workspaceId), activeParts(statement)));
            measure(report, statement, apiKey, workspaceName, projectId, today, expectedTotal, where);

            // Runbook check, with merges stopped so both reads see the same rows: a failed run relabels 1,000 traces
            // after the last night, and three rows sit at the window edges. The API and the runbook queries must agree.
            statement.execute("SYSTEM STOP MERGES %s.trace_topics".formatted(DATABASE_NAME));
            try {
                statement.execute("""
                        INSERT INTO %s.trace_topics
                            (workspace_id, project_id, trace_id, trace_created_at, topic_id, source, run_id)
                        SELECT '%s', '%s', trace_id, trace_created_at, '%s', 'night', '%s'
                        FROM %s WHERE n < %d
                        SETTINGS insert_deduplicate = 0
                        """.formatted(DATABASE_NAME, workspaceId, projectId, live.getFirst().id(),
                        run(workspaceId, projectId, TopicRun.Status.FAILED, Instant.now()).runId(), tracesTable,
                        FAILED_TRACES));
                statement.execute("""
                        INSERT INTO %s.trace_topics
                            (workspace_id, project_id, trace_id, trace_created_at, topic_id, source, run_id)
                        VALUES
                            ('%2$s', '%3$s', toString(generateUUIDv7(1)), toDateTime64('%4$s 00:00:00', 6, 'UTC'),
                                '%5$s', 'night', '%6$s'),
                            ('%2$s', '%3$s', toString(generateUUIDv7(2)),
                                toDateTime64('%4$s 00:00:00', 6, 'UTC') - toIntervalMicrosecond(1), '%5$s', 'night',
                                '%6$s'),
                            ('%2$s', '%3$s', toString(generateUUIDv7(3)), toDateTime64('%7$s 00:00:00', 6, 'UTC'),
                                '%5$s', 'night', '%6$s')
                        """.formatted(DATABASE_NAME, workspaceId, projectId, windowFrom, live.get(1).id(),
                        lastRun.runId(), today.plusDays(1)));
                var api = get(apiKey, workspaceName, projectId);
                assumeTrue(today.equals(api.today()), "the UTC day changed during the test");
                report.append("## Runbook queries against the API\n\n").append(runbookCheck(api, workspaceId,
                        projectId, today, live.size(), lastRun));
            } finally {
                statement.execute("SYSTEM START MERGES %s.trace_topics".formatted(DATABASE_NAME));
                statement.execute("DROP TABLE IF EXISTS " + tracesTable);
            }
        }

        Files.writeString(REPORT, report);
        System.out.println(report);
    }

    /** 100 API calls, their counts query in the query log, then the LIMIT BY and argMax versions by hand. */
    private void measure(StringBuilder report, java.sql.Statement statement, String apiKey, String workspaceName,
            UUID projectId, LocalDate today, long expectedTotal, String where) throws Exception {
        var loadStart = Instant.now();
        var millis = new double[CALLS];
        ProjectTopics body = null;
        for (int i = 0; i < CALLS; i++) {
            var start = System.nanoTime();
            body = get(apiKey, workspaceName, projectId);
            millis[i] = (System.nanoTime() - start) / 1e6;
        }
        assumeTrue(today.equals(body.today()), "the UTC day changed during the test");
        assertThat(body.status()).isEqualTo(ProjectTopics.Status.READY);
        assertThat(body.totalTraces()).isEqualTo(expectedTotal);
        var rest = Arrays.copyOfRange(millis, 1, CALLS);
        report.append("API, %d calls in a row (client wall time, ms): first %.1f; the other %d: p50 %.1f, p95 %.1f, "
                .formatted(CALLS, millis[0], CALLS - 1, percentile(rest, 0.50), percentile(rest, 0.95))
                + "max %.1f.\n\n".formatted(percentile(rest, 1.0)));

        statement.execute("SYSTEM FLUSH LOGS");
        report.append("The API's counts query (`%s`):\n\n".formatted(COMMENT))
                .append(queryLog(statement, COMMENT, loadStart));

        var settings = "SETTINGS max_threads = 2, max_execution_time = 10, max_memory_usage = 1073741824, "
                + "max_rows_to_read = 30000000, log_comment = '%s'";
        var limitBy = """
                SELECT topic_id, toDate(trace_created_at, 'UTC') AS day, count() AS traces
                FROM (
                    SELECT topic_id, trace_created_at FROM %s.trace_topics
                    WHERE %s
                    ORDER BY workspace_id, project_id, trace_id, updated_at DESC, run_id DESC
                    LIMIT 1 BY workspace_id, project_id, trace_id
                )
                GROUP BY topic_id, day
                ORDER BY topic_id, day
                """.formatted(DATABASE_NAME, where) + settings.formatted("topics:load:limit_by");
        var argMax = """
                SELECT latest_topic AS topic_id, toDate(created, 'UTC') AS day, count() AS traces
                FROM (
                    SELECT argMax(topic_id, (updated_at, run_id)) AS latest_topic, any(trace_created_at) AS created
                    FROM %s.trace_topics
                    WHERE %s
                    GROUP BY trace_id
                )
                GROUP BY topic_id, day
                ORDER BY topic_id, day
                """.formatted(DATABASE_NAME, where) + settings.formatted("topics:load:arg_max");
        var handStart = Instant.now();
        List<String> limitByRows = null;
        List<String> argMaxRows = null;
        for (int i = 0; i < CALLS; i++) {
            limitByRows = rows(statement, limitBy);
            argMaxRows = rows(statement, argMax);
        }
        assertThat(argMaxRows).isEqualTo(limitByRows);
        statement.execute("SYSTEM FLUSH LOGS");
        report.append("By hand, %d runs each, alternated, same result rows. LIMIT BY:\n\n"
                .formatted(CALLS))
                .append(queryLog(statement, "topics:load:limit_by", handStart))
                .append("argMax (the API's query):\n\n")
                .append(queryLog(statement, "topics:load:arg_max", handStart));
    }

    /** Waits until no merge of trace_topics runs and its active parts stay the same for three polls. */
    private static void waitForMerges(java.sql.Statement statement) throws Exception {
        long previous = -1;
        int stable = 0;
        for (int i = 0; i < 120 && stable < 3; i++) {
            Thread.sleep(1_000);
            var parts = activeParts(statement);
            var merging = count(statement, "SELECT count() FROM system.merges WHERE database = '%s' AND table = "
                    .formatted(DATABASE_NAME) + "'trace_topics'");
            stable = merging == 0 && parts == previous ? stable + 1 : 0;
            previous = parts;
        }
    }

    private static long projectRows(java.sql.Statement statement, String workspaceId) throws SQLException {
        return count(statement, "SELECT count() FROM %s.trace_topics WHERE workspace_id = '%s'"
                .formatted(DATABASE_NAME, workspaceId));
    }

    private String runbookCheck(ProjectTopics api, String workspaceId, UUID projectId, LocalDate today, int liveTopics,
            TopicRun lastRun) throws Exception {
        var runbook = Files.readString(RUNBOOK);
        var section = runbook.substring(runbook.indexOf("### Reading a project by hand"),
                runbook.indexOf("### Errors"));
        var blocks = Pattern.compile("```sql\n(.*?)```", Pattern.DOTALL).matcher(section).results()
                .map(match -> match.group(1)
                        .replace("<workspace_id>", workspaceId)
                        .replace("<project_id>", projectId.toString()))
                .toList();
        assertThat(blocks).hasSize(2);

        var mysqlResults = new ArrayList<List<Map<String, Object>>>();
        try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(),
                mysql.getPassword()); var statement = connection.createStatement()) {
            var sql = blocks.getFirst().lines().filter(line -> !line.strip().startsWith("--"))
                    .collect(Collectors.joining("\n"));
            for (var query : sql.split(";")) {
                if (query.isBlank()) {
                    continue;
                }
                if (statement.execute(query)) {
                    var resultSet = statement.getResultSet();
                    var rows = new ArrayList<Map<String, Object>>();
                    while (resultSet.next()) {
                        var row = new LinkedHashMap<String, Object>();
                        for (int c = 1; c <= resultSet.getMetaData().getColumnCount(); c++) {
                            row.put(resultSet.getMetaData().getColumnLabel(c), resultSet.getObject(c));
                        }
                        rows.add(row);
                    }
                    mysqlResults.add(rows);
                }
            }
        }
        assertThat(mysqlResults).hasSize(4);
        assertThat(String.valueOf(mysqlResults.get(0).getFirst().get("eligible"))).isIn("1", "true");
        assertThat(mysqlResults.get(1)).hasSize(liveTopics);
        assertThat(mysqlResults.get(2).getFirst().get("last_run_id")).isEqualTo(lastRun.runId().toString());
        var lists = mysqlResults.get(3).getFirst();
        var liveIds = (String) lists.get("live_ids");
        var savedRunIds = (String) lists.get("saved_run_ids");
        assertThat(liveIds.split(",")).hasSize(liveTopics);
        assertThat(savedRunIds.split(",")).hasSize(NIGHTS);
        assertThat(savedRunIds.length()).isGreaterThan(1024);

        var counts = new HashMap<String, Map<LocalDate, Long>>();
        long stale = 0;
        try (var ch = clickHouseConnection(); var statement = ch.createStatement()) {
            var query = blocks.getLast().replace("<live_ids>", liveIds).replace("<saved_run_ids>", savedRunIds);
            try (var resultSet = statement.executeQuery(query)) {
                while (resultSet.next()) {
                    counts.computeIfAbsent(resultSet.getString("topic"), id -> new HashMap<>())
                            .put(LocalDate.parse(resultSet.getString("day")), resultSet.getLong("traces"));
                    stale += resultSet.getLong("stale");
                }
            }
        }
        long total = counts.values().stream().flatMap(days -> days.values().stream()).mapToLong(Long::longValue)
                .sum();
        assertThat(total).isEqualTo(api.totalTraces());
        for (TopicStats topic : api.topics()) {
            var days = counts.getOrDefault(topic.id().toString(), Map.of());
            assertThat(sum(days)).as(topic.name()).isEqualTo(topic.count());
            assertThat(change(days, today)).as(topic.name()).isEqualTo(topic.change());
        }
        var other = counts.getOrDefault(OTHER, Map.of());
        assertThat(sum(other)).isEqualTo(api.other().count());
        assertThat(change(other, today)).isEqualTo(api.other().change());
        assertThat(stale).isEqualTo(api.other().stale());
        return ("MySQL block: %d statements ran (eligible 1, %d live topics, state, %d-character saved_run_ids "
                + "list). ClickHouse block: equal to the API for every topic's count and change, Other's count, "
                + "change and stale (%d), and total_traces (%,d), after a failed run relabelled %,d traces and three "
                + "rows were put at the window edges.\n").formatted(mysqlResults.size() + 1, liveTopics,
                        savedRunIds.length(), stale, total, FAILED_TRACES);
    }

    private static long sum(Map<LocalDate, Long> days) {
        return days.values().stream().mapToLong(Long::longValue).sum();
    }

    private static long change(Map<LocalDate, Long> days, LocalDate today) {
        return days.getOrDefault(today, 0L) - days.getOrDefault(today.minusDays(1), 0L);
    }

    private String queryLog(java.sql.Statement statement, String comment, Instant since) throws SQLException {
        var sql = """
                SELECT count() AS n,
                    quantileExact(0.5)(query_duration_ms) AS p50, quantileExact(0.95)(query_duration_ms) AS p95,
                    max(query_duration_ms) AS max_ms, max(read_rows) AS read_rows, max(read_bytes) AS read_bytes,
                    max(memory_usage) AS memory, max(ProfileEvents['SelectedParts']) AS parts,
                    max(ProfileEvents['SelectedMarks']) AS marks
                FROM system.query_log
                WHERE type = 'QueryFinish' AND startsWith(log_comment, '%s') AND event_time_microseconds >= %s
                """.formatted(comment, "fromUnixTimestamp64Micro(%d)".formatted(
                ChronoUnit.MICROS.between(Instant.EPOCH, since)));
        try (var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return ("| queries | p50 ms | p95 ms | max ms | read_rows | read_bytes | memory_usage | SelectedParts | "
                    + "SelectedMarks |\n|---|---|---|---|---|---|---|---|---|\n"
                    + "| %d | %d | %d | %d | %,d | %,d | %,d | %d | %d |\n\n").formatted(
                            resultSet.getLong("n"), resultSet.getLong("p50"), resultSet.getLong("p95"),
                            resultSet.getLong("max_ms"), resultSet.getLong("read_rows"),
                            resultSet.getLong("read_bytes"), resultSet.getLong("memory"), resultSet.getLong("parts"),
                            resultSet.getLong("marks"));
        }
    }

    private static List<String> rows(java.sql.Statement statement, String sql) throws SQLException {
        var rows = new ArrayList<String>();
        try (var resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                rows.add(resultSet.getString(1) + " " + resultSet.getString(2) + " " + resultSet.getLong(3));
            }
        }
        return rows;
    }

    private static long count(java.sql.Statement statement, String sql) throws SQLException {
        try (var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static long activeParts(java.sql.Statement statement) throws SQLException {
        return count(statement, "SELECT count() FROM system.parts WHERE database = '%s' AND table = 'trace_topics' "
                .formatted(DATABASE_NAME) + "AND active");
    }

    private static double percentile(double[] values, double quantile) {
        var sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[Math.max(0, (int) Math.ceil(quantile * sorted.length) - 1)];
    }

    private Connection clickHouseConnection() throws SQLException {
        var url = clickHouse.getJdbcUrl();
        return DriverManager.getConnection(url.substring(0, url.lastIndexOf('/') + 1) + DATABASE_NAME,
                clickHouse.getUsername(), clickHouse.getPassword());
    }

    private ProjectTopics get(String apiKey, String workspaceName, UUID projectId) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseUrl))
                .queryParam("project_id", projectId)
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .get()) {
            assertThat(response.getStatus()).isEqualTo(200);
            return response.readEntity(ProjectTopics.class);
        }
    }

    private TopicRun run(String workspaceId, UUID projectId, TopicRun.Status status, Instant startedAt) {
        var run = TopicRun.builder()
                .runId(ID_GENERATOR.generateId())
                .workspaceId(workspaceId)
                .projectId(projectId)
                .mode(TopicRun.Mode.NIGHTLY_UPDATE)
                .status(status)
                .startedAt(startedAt.truncatedTo(ChronoUnit.MICROS))
                .build();
        if (status == TopicRun.Status.FAILED) {
            template.inTransaction(WRITE, handle -> {
                handle.attach(TopicRunDAO.class).insert(run);
                return null;
            });
        }
        return run;
    }

    private static Topic topic(String workspaceId, UUID projectId, LocalDate born, LocalDate retiredAt) {
        var id = UUID.randomUUID();
        return Topic.builder()
                .id(id)
                .workspaceId(workspaceId)
                .projectId(projectId)
                .name("Topic " + id)
                .definition("Definition of " + id)
                .born(born)
                .retiredAt(retiredAt)
                .retiredReason(retiredAt == null ? null : "merged")
                .build();
    }
}
