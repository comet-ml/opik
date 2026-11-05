package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.ProjectTopics;
import com.comet.opik.api.ProjectTopics.TopicStats;
import com.comet.opik.api.Source;
import com.comet.opik.api.Trace;
import com.comet.opik.api.VisibilityMode;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
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
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
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
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
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
import java.util.function.BiFunction;
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
    private static final int LIST_CALLS = 30;
    private static final String COMMENT = "topics:count_by_topic_and_day:";
    private static final String OTHER = TraceTopic.OTHER.toString();
    private static final Path REPORT = Path.of("target", "topics-read-load-test.md");
    private static final Path RUNBOOK = Path.of("docs", "ai-labels-runbook.md");

    private static final String STOP_MERGES = "SYSTEM STOP MERGES trace_topics";
    private static final String START_MERGES = "SYSTEM START MERGES trace_topics";
    private static final String FLUSH_LOGS = "SYSTEM FLUSH LOGS";
    private static final String ACTIVE_PARTS = """
            SELECT count() AS n FROM system.parts
            WHERE database = currentDatabase() AND table = 'trace_topics' AND active
            """;
    private static final String RUNNING_MERGES = """
            SELECT count() AS n FROM system.merges WHERE database = currentDatabase() AND table = 'trace_topics'
            """;
    private static final String PROJECT_ROWS = """
            SELECT count() AS n FROM trace_topics WHERE workspace_id = :workspace_id
            """;
    private static final String CREATE_TRACES = """
            CREATE TABLE topics_load_traces (n UInt32, trace_id String, trace_created_at DateTime64(6, 'UTC'))
            ENGINE = Memory
            """;
    private static final String DROP_TRACES = "DROP TABLE IF EXISTS topics_load_traces";

    /** Trace n is created on window day n % 30, at least an hour after midnight. */
    private static final String INSERT_TRACES = """
            INSERT INTO topics_load_traces
            SELECT number, toString(generateUUIDv7(number)),
                addSeconds(addDays(fromUnixTimestamp64Micro(:window_from_micros, 'UTC'), number % 30),
                    3600 + intDiv(number, 30))
            FROM numbers(:traces)
            """;

    /** Night k labels trace n with topic (n * 7 + k) % 11 of [live..., Other]; the last night's label wins. */
    private static final String INSERT_NIGHT = """
            INSERT INTO trace_topics
                (workspace_id, project_id, trace_id, trace_created_at, topic_id, source, run_id, updated_at)
            SELECT :workspace_id, :project_id, trace_id, trace_created_at,
                arrayElement(:labels, ((n * 7 + :night) % 11) + 1), 'night', :run_id,
                now64(6) - toIntervalMinute(:minutes_ago)
            FROM topics_load_traces
            SETTINGS insert_deduplicate = 0
            """;

    /** Arrival ticks after the last night: every 20th row names the retired topic (stale). */
    private static final String INSERT_ARRIVALS = """
            INSERT INTO trace_topics
                (workspace_id, project_id, trace_id, trace_created_at, topic_id, source, run_id)
            SELECT :workspace_id, :project_id, toString(generateUUIDv7(number)),
                greatest(now64(6, 'UTC') - toIntervalSecond(number + :offset),
                    fromUnixTimestamp64Micro(:today_micros, 'UTC')),
                if(number % 20 = 0, :retired_id, arrayElement(:labels, (number % 11) + 1)), 'arrival', ''
            FROM numbers(:rows)
            SETTINGS insert_deduplicate = 0
            """;

    /** A failed run's rows, after the last night: they must not count. */
    private static final String INSERT_FAILED_RUN = """
            INSERT INTO trace_topics
                (workspace_id, project_id, trace_id, trace_created_at, topic_id, source, run_id)
            SELECT :workspace_id, :project_id, trace_id, trace_created_at, :topic_id, 'night', :run_id
            FROM topics_load_traces
            WHERE n < :traces
            SETTINGS insert_deduplicate = 0
            """;

    /** Rows at the window edges: its first microsecond counts, the one before it and its end do not. */
    private static final String INSERT_EDGES = """
            INSERT INTO trace_topics
                (workspace_id, project_id, trace_id, trace_created_at, topic_id, source, run_id)
            SELECT :workspace_id, :project_id, toString(generateUUIDv7(number)),
                fromUnixTimestamp64Micro([:from_micros, :before_from_micros, :to_micros][number + 1], 'UTC'),
                :topic_id, 'night', :run_id
            FROM numbers(3)
            """;

    /**
     * The traces table row of each trace with a topics row and none yet, created when that row says: the trace list
     * reads these. Into the shard, as {@link PostCutoverTraces} says.
     */
    private static final String INSERT_LIST_TRACES = """
            INSERT INTO %1$s (id, workspace_id, project_id, name, start_time, created_at)
            SELECT trace_id, workspace_id, project_id, 'load', any(trace_created_at), any(trace_created_at)
            FROM trace_topics
            WHERE workspace_id = :workspace_id
            AND trace_id NOT IN (SELECT id FROM %1$s WHERE workspace_id = :workspace_id)
            GROUP BY workspace_id, project_id, trace_id
            """.formatted(PostCutoverTraces.SHARD);

    private static final String LIMIT_BY = """
            SELECT topic_id, toDate(trace_created_at, 'UTC') AS day, count() AS traces
            FROM (
                SELECT topic_id, trace_created_at FROM trace_topics
                WHERE workspace_id = :workspace_id AND project_id = :project_id
                AND trace_created_at >= fromUnixTimestamp64Micro(:from_micros, 'UTC')
                AND trace_created_at < fromUnixTimestamp64Micro(:to_micros, 'UTC')
                AND (source = 'arrival' OR run_id IN :saved_run_ids)
                ORDER BY workspace_id, project_id, trace_id, updated_at DESC, run_id DESC
                LIMIT 1 BY workspace_id, project_id, trace_id
            )
            GROUP BY topic_id, day
            ORDER BY topic_id, day
            SETTINGS max_threads = 2, max_execution_time = 10, max_memory_usage = 1073741824,
                max_rows_to_read = 30000000, log_comment = 'topics:load:limit_by'
            """;

    private static final String ARG_MAX = """
            SELECT latest_topic AS topic_id, toDate(created, 'UTC') AS day, count() AS traces
            FROM (
                SELECT argMax(topic_id, (updated_at, run_id)) AS latest_topic, any(trace_created_at) AS created
                FROM trace_topics
                WHERE workspace_id = :workspace_id AND project_id = :project_id
                AND trace_created_at >= fromUnixTimestamp64Micro(:from_micros, 'UTC')
                AND trace_created_at < fromUnixTimestamp64Micro(:to_micros, 'UTC')
                AND (source = 'arrival' OR run_id IN :saved_run_ids)
                GROUP BY trace_id
            )
            GROUP BY topic_id, day
            ORDER BY topic_id, day
            SETTINGS max_threads = 2, max_execution_time = 10, max_memory_usage = 1073741824,
                max_rows_to_read = 30000000, log_comment = 'topics:load:arg_max'
            """;

    private static final String QUERY_LOG = """
            SELECT countIf(is_initial_query) AS n,
                quantileExactIf(0.5)(query_duration_ms, is_initial_query) AS p50,
                quantileExactIf(0.95)(query_duration_ms, is_initial_query) AS p95,
                maxIf(query_duration_ms, is_initial_query) AS max_ms, max(read_rows) AS read_rows,
                max(read_bytes) AS read_bytes, max(memory_usage) AS memory,
                max(ProfileEvents['SelectedParts']) AS parts, max(ProfileEvents['SelectedMarks']) AS marks
            FROM system.query_log
            WHERE type = 'QueryFinish' AND startsWith(log_comment, :comment)
            AND event_time_microseconds >= fromUnixTimestamp64Micro(:since_micros)
            """;

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
    private TraceResourceClient traceClient;
    private TransactionTemplate template;
    private ConnectionFactory connectionFactory;

    @BeforeAll
    void setUpAll(ClientSupport client, Injector injector) {
        this.client = client;
        this.baseUrl = TestUtils.getBaseUrl(client);
        this.projectClient = new ProjectResourceClient(client, baseUrl, PodamFactoryUtils.newPodamFactory());
        this.traceClient = new TraceResourceClient(client, baseUrl);
        this.template = injector.getInstance(TransactionTemplate.class);
        this.connectionFactory = injector.getInstance(ConnectionFactory.class);
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

        var labels = new ArrayList<>(live.stream().map(topic -> topic.id().toString()).toList());
        labels.add(OTHER);
        var labelArray = labels.toArray(String[]::new);
        var window = Map.<String, Object>of(
                "workspace_id", workspaceId,
                "project_id", projectId.toString(),
                "from_micros", micros(windowFrom),
                "to_micros", micros(today.plusDays(1)),
                "saved_run_ids", runs.stream().map(run -> run.runId().toString()).toArray(String[]::new));
        var expectedTotal = (long) TRACES + ARRIVALS;
        // The worst case keeps every night's rows. Background merges collapse a trace's older rows within seconds
        // here, so they stay stopped for the first measurement.
        execute(STOP_MERGES, Map.of());
        try {
            execute(CREATE_TRACES, Map.of());
            execute(INSERT_TRACES, Map.of("window_from_micros", micros(windowFrom), "traces", TRACES));
            for (int k = 0; k < NIGHTS; k++) {
                execute(INSERT_NIGHT, Map.of(
                        "workspace_id", workspaceId,
                        "project_id", projectId.toString(),
                        "labels", labelArray,
                        "night", k,
                        "run_id", runs.get(k).runId().toString(),
                        "minutes_ago", 60 - k));
            }
            for (int batch = 0; batch < ARRIVALS / ARRIVAL_BATCH; batch++) {
                execute(INSERT_ARRIVALS, Map.of(
                        "workspace_id", workspaceId,
                        "project_id", projectId.toString(),
                        "offset", batch * ARRIVAL_BATCH,
                        "today_micros", micros(today),
                        "retired_id", retired.id().toString(),
                        "labels", labelArray,
                        "rows", ARRIVAL_BATCH));
            }
            execute(INSERT_LIST_TRACES, Map.of("workspace_id", workspaceId));
            var rows = projectRows(workspaceId);
            assertThat(rows).isEqualTo((long) TRACES * NIGHTS + ARRIVALS);
            report.append(("Seed: %,d traces x %d saved nights + %,d arrival rows in %d inserts, %d live topics + "
                    + "1 retired.\n\n").formatted(TRACES, NIGHTS, ARRIVALS, ARRIVALS / ARRIVAL_BATCH,
                            LIVE_TOPICS))
                    .append("From `system.query_log`: `queries` and the times are the initial queries, one per "
                            + "call; read rows and bytes, memory, parts and marks are the largest of those and the "
                            + "shard queries they start, which have the same `log_comment`.\n\n")
                    .append("## A. Unmerged: merges stopped, %,d rows, %d active parts\n\n".formatted(rows,
                            activeParts()));
            measure(report, apiKey, workspaceName, projectId, today, expectedTotal, window);
        } finally {
            execute(START_MERGES, Map.of());
        }

        waitForMerges();
        report.append("## B. After background merges, no OPTIMIZE: %,d rows, %d active parts\n\n".formatted(
                projectRows(workspaceId), activeParts()));
        measure(report, apiKey, workspaceName, projectId, today, expectedTotal, window);

        // Runbook check, with merges stopped so both reads see the same rows: a failed run relabels 1,000 traces
        // after the last night, and three rows sit at the window edges. The API and the runbook queries must agree.
        execute(STOP_MERGES, Map.of());
        try {
            execute(INSERT_FAILED_RUN, Map.of(
                    "workspace_id", workspaceId,
                    "project_id", projectId.toString(),
                    "topic_id", live.getFirst().id().toString(),
                    "run_id", run(workspaceId, projectId, TopicRun.Status.FAILED, Instant.now()).runId().toString(),
                    "traces", FAILED_TRACES));
            execute(INSERT_EDGES, Map.of(
                    "workspace_id", workspaceId,
                    "project_id", projectId.toString(),
                    "from_micros", micros(windowFrom),
                    "before_from_micros", micros(windowFrom) - 1,
                    "to_micros", micros(today.plusDays(1)),
                    "topic_id", live.get(1).id().toString(),
                    "run_id", lastRun.runId().toString()));
            execute(INSERT_LIST_TRACES, Map.of("workspace_id", workspaceId));
            var api = get(apiKey, workspaceName, projectId);
            assumeTrue(today.equals(api.today()), "the UTC day changed during the test");
            report.append("## Runbook queries against the API\n\n").append(runbookCheck(api, workspaceId,
                    projectId, live.size(), lastRun));
        } finally {
            execute(START_MERGES, Map.of());
            execute(DROP_TRACES, Map.of());
        }

        Files.writeString(REPORT, report);
        System.out.println(report);
    }

    /** 100 API calls, their counts query in the query log, then the LIMIT BY and argMax versions by hand. */
    private void measure(StringBuilder report, String apiKey, String workspaceName, UUID projectId, LocalDate today,
            long expectedTotal, Map<String, Object> window) {
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

        execute(FLUSH_LOGS, Map.of());
        report.append("The API's counts query (`%s`):\n\n".formatted(COMMENT))
                .append(queryLog(COMMENT, loadStart));

        var handStart = Instant.now();
        List<String> limitByRows = null;
        List<String> argMaxRows = null;
        for (int i = 0; i < CALLS; i++) {
            limitByRows = countRows(LIMIT_BY, window);
            argMaxRows = countRows(ARG_MAX, window);
        }
        assertThat(argMaxRows).isEqualTo(limitByRows);
        execute(FLUSH_LOGS, Map.of());
        report.append("By hand, %d runs each, alternated, same result rows. LIMIT BY:\n\n"
                .formatted(CALLS))
                .append(queryLog("topics:load:limit_by", handStart))
                .append("argMax (the API's query):\n\n")
                .append(queryLog("topics:load:arg_max", handStart));

        measureList(report, apiKey, workspaceName, projectId, body);
    }

    /**
     * The trace list over the API's window by created_at, as "View all N logs" opens it: with no topic filter, then
     * filtered by the largest topic and by Other. Each total must be the API's count.
     */
    private void measureList(StringBuilder report, String apiKey, String workspaceName, UUID projectId,
            ProjectTopics body) {
        var window = logs(body.windowFrom(), body.windowUntil().plusDays(1));
        var largest = body.topics().getFirst();
        var cases = new LinkedHashMap<String, Map.Entry<List<TraceFilter>, Long>>();
        cases.put("no topic filter", Map.entry(window, body.totalTraces()));
        cases.put("topic_id = the largest topic", Map.entry(withTopic(window, largest.id().toString()),
                largest.count()));
        cases.put("topic_id = Other", Map.entry(withTopic(window, OTHER), body.other().count()));
        // The created_at range bounds the topic subquery, so a range with no trace reads almost none of its rows.
        cases.put("topic_id = the largest topic, the 30 days before the window", Map.entry(withTopic(
                logs(body.windowFrom().minusDays(30), body.windowFrom()), largest.id().toString()), 0L));
        report.append(("The trace list as Logs asks for it (the API's window by created_at, source sdk, visibility "
                + "default), page size 100, %d calls in a row per case. Each total equals the API's count; the "
                + "30 days before the window have no trace.\n\n").formatted(LIST_CALLS));
        cases.forEach((name, filtersAndCount) -> {
            var start = Instant.now();
            var millis = new double[LIST_CALLS];
            Trace.TracePage page = null;
            for (int i = 0; i < LIST_CALLS; i++) {
                var callStart = System.nanoTime();
                page = traceClient.getTraces(null, projectId, apiKey, workspaceName, filtersAndCount.getKey(),
                        List.of(), 100, Map.of());
                millis[i] = (System.nanoTime() - callStart) / 1e6;
            }
            assertThat(page.total()).as(name).isEqualTo(filtersAndCount.getValue());
            execute(FLUSH_LOGS, Map.of());
            report.append("%s, %,d traces (client wall time, ms): p50 %.1f, p95 %.1f, max %.1f.\n\n".formatted(name,
                    page.total(), percentile(millis, 0.50), percentile(millis, 0.95), percentile(millis, 1.0)))
                    .append("Page query (`find_traces_by_project_id`):\n\n")
                    .append(queryLog("find_traces_by_project_id:", start))
                    .append("Count query (`count_traces_by_project`):\n\n")
                    .append(queryLog("count_traces_by_project:", start));
        });
    }

    /** The Logs page's filters over the days {@code [from, until)} by created_at, in UTC. */
    private static List<TraceFilter> logs(LocalDate from, LocalDate until) {
        return List.of(
                filter(TraceField.CREATED_AT, Operator.GREATER_THAN_EQUAL,
                        from.atStartOfDay(ZoneOffset.UTC).toInstant().toString()),
                filter(TraceField.CREATED_AT, Operator.LESS_THAN,
                        until.atStartOfDay(ZoneOffset.UTC).toInstant().toString()),
                filter(TraceField.SOURCE, Operator.EQUAL, Source.SDK.getValue()),
                filter(TraceField.VISIBILITY_MODE, Operator.EQUAL, VisibilityMode.DEFAULT.getValue()));
    }

    private static List<TraceFilter> withTopic(List<TraceFilter> window, String topicId) {
        var filters = new ArrayList<>(window);
        filters.add(filter(TraceField.TOPIC_ID, Operator.EQUAL, topicId));
        return filters;
    }

    private static TraceFilter filter(TraceField field, Operator operator, String value) {
        return TraceFilter.builder().field(field).operator(operator).value(value).build();
    }

    /** Waits until no merge of trace_topics runs and its active parts stay the same for three polls. */
    private void waitForMerges() throws InterruptedException {
        long previous = -1;
        int stable = 0;
        for (int i = 0; i < 120 && stable < 3; i++) {
            Thread.sleep(1_000);
            var parts = activeParts();
            var merging = count(RUNNING_MERGES);
            stable = merging == 0 && parts == previous ? stable + 1 : 0;
            previous = parts;
        }
    }

    private long projectRows(String workspaceId) {
        return query(PROJECT_ROWS, Map.of("workspace_id", workspaceId), (row, metadata) -> number(row, "n"))
                .getFirst();
    }

    private String runbookCheck(ProjectTopics api, String workspaceId, UUID projectId, int liveTopics,
            TopicRun lastRun) throws Exception {
        var runbook = Files.readString(RUNBOOK);
        var section = runbook.substring(runbook.indexOf("### Reading a project by hand"),
                runbook.indexOf("### Changes since the last run"));
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
        assertThat(String.valueOf(mysqlResults.getFirst().getFirst().get("eligible"))).isIn("1", "true");
        assertThat(mysqlResults.get(1)).hasSize(liveTopics);
        assertThat(mysqlResults.get(2).getFirst().get("last_run_id")).isEqualTo(lastRun.runId().toString());
        var lists = mysqlResults.getLast().getFirst();
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
        }
        var other = counts.getOrDefault(OTHER, Map.of());
        assertThat(sum(other)).isEqualTo(api.other().count());
        assertThat(stale).isEqualTo(api.other().stale());
        return ("MySQL block: %d statements ran (eligible 1, %d live topics, state, %d-character saved_run_ids "
                + "list). ClickHouse block: equal to the API for every topic's count, Other's count and stale "
                + "(%d), and total_traces (%,d), after a failed run relabelled %,d traces and three "
                + "rows were put at the window edges.\n").formatted(mysqlResults.size() + 1, liveTopics,
                        savedRunIds.length(), stale, total, FAILED_TRACES);
    }

    private static long sum(Map<LocalDate, Long> days) {
        return days.values().stream().mapToLong(Long::longValue).sum();
    }

    private String queryLog(String comment, Instant since) {
        var binds = Map.<String, Object>of("comment", comment,
                "since_micros", ChronoUnit.MICROS.between(Instant.EPOCH, since));
        return query(QUERY_LOG, binds, (row, metadata) -> ("| queries | p50 ms | p95 ms | max ms | read_rows | "
                + "read_bytes | memory_usage | SelectedParts | SelectedMarks |\n|---|---|---|---|---|---|---|---|---|\n"
                + "| %d | %d | %d | %d | %,d | %,d | %,d | %d | %d |\n\n").formatted(number(row, "n"),
                        number(row, "p50"), number(row, "p95"), number(row, "max_ms"), number(row, "read_rows"),
                        number(row, "read_bytes"), number(row, "memory"), number(row, "parts"),
                        number(row, "marks")))
                .getFirst();
    }

    /** The result rows of a counts query, one "topic day traces" line each. */
    private List<String> countRows(String sql, Map<String, Object> window) {
        return query(sql, window, (row, metadata) -> row.get("topic_id", String.class) + " "
                + row.get("day", LocalDate.class) + " " + number(row, "traces"));
    }

    private void execute(String sql, Map<String, Object> binds) {
        run(sql, binds).flatMap(Result::getRowsUpdated).then().block();
    }

    private <T> List<T> query(String sql, Map<String, Object> binds, BiFunction<Row, RowMetadata, T> mapper) {
        return run(sql, binds).flatMap(result -> result.map(mapper)).collectList().block();
    }

    /**
     * Runs a statement with its values bound, as the DAOs do: never formatted into the SQL. It runs in the app's
     * ClickHouse database, so table names need no database.
     */
    private Flux<Result> run(String sql, Map<String, Object> binds) {
        return Mono.from(connectionFactory.create()).flatMapMany(connection -> {
            var statement = connection.createStatement(sql);
            binds.forEach(statement::bind);
            return statement.execute();
        });
    }

    private static long number(Row row, String column) {
        return ((Number) row.get(column, Object.class)).longValue();
    }

    private static long micros(LocalDate day) {
        return ChronoUnit.MICROS.between(Instant.EPOCH, day.atStartOfDay().toInstant(ZoneOffset.UTC));
    }

    private long count(String sql) {
        return query(sql, Map.of(), (row, metadata) -> number(row, "n")).getFirst();
    }

    private long activeParts() {
        return count(ACTIVE_PARTS);
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
