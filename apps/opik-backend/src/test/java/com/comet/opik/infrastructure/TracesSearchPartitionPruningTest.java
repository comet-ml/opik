package com.comet.opik.infrastructure;

import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.TraceThread.TraceThreadPage;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.StatsUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.resources.utils.traces.TraceAssertions;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.stats.StatsMapper;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.template.TemplateUtils;
import com.redis.testcontainers.RedisContainer;
import io.r2dbc.spi.Statement;
import org.apache.commons.lang3.RandomStringUtils;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A trace search reads the large text columns of every part its id-range admits. On the weekly-partitioned
 * {@code traces} that is every part from the window start through the far-future weeks a bad client clock creates,
 * so the search scans read only the weeks the project's own traces fall in (a key-only pre-pass). A pruning hint must
 * never drop a row, so this suite checks the whole page across ordinary and far-future weeks, next to a neighbouring
 * part whose key range brackets the project, and that the hint actually ran. {@code query_log.partitions} is
 * per-statement and includes the pre-pass's own key reads, so it cannot show the pruning itself.
 * <p>
 * Far-future and backdated ids are rejected at ingestion by default, so this suite disables that validation rather than
 * inserting rows directly. The topology setup is the one {@code TracesPartitionPruningMutationTest} uses.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TracesSearchPartitionPruningTest {

    private static final String API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    /** The project's traces: two ordinary weeks and the far-future one a bad clock files them under. */
    private static final List<LocalDate> PROJECT_MONDAYS = List.of(
            LocalDate.of(2025, 3, 3), LocalDate.of(2025, 6, 2), LocalDate.of(2199, 12, 30));
    /** A week only the neighbouring projects have traces in, inside the search window. */
    private static final LocalDate FILLER_MONDAY = LocalDate.of(2025, 4, 14);
    private static final Instant FROM_TIME = Instant.parse("2025-01-01T00:00:00Z");

    private static final String LAST_SEARCH = """
            SELECT query
            FROM system.query_log
            WHERE log_comment LIKE concat(:query_name, ':%')
            AND type = 'QueryFinish'
            AND is_initial_query
            AND query LIKE concat('%', :token, '%')
            ORDER BY event_time_microseconds DESC
            LIMIT 1
            """;

    private static final String PARTITION_KEY_OF_TABLE = """
            SELECT partition_key FROM system.tables WHERE database = currentDatabase() AND name = :table
            """;

    private static final String TABLE_COUNT = """
            SELECT toString(count()) FROM system.tables WHERE database = currentDatabase() AND name = :table
            """;

    private static final String TABLE_ENGINE_FULL = """
            SELECT engine_full FROM system.tables WHERE database = currentDatabase() AND name = 'traces'
            """;

    private static final String CREATE_DISTRIBUTED_WRAPPER = """
            CREATE TABLE traces_dist ON CLUSTER '{cluster}' AS traces
            ENGINE = Distributed('{cluster}', '<database>', 'traces_local', sipHash64(project_id))
            """;

    private static final String FAST_LOG_FLUSH_CONFIG = "clickhouse-fast-log-flush.xml";

    // Dedicated, non-reused ClickHouse + ZooKeeper: the EXCHANGE destructively swaps `traces`.
    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils.newZookeeperContainer(false,
            network);
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(false, network, zookeeperContainer)
            .withCopyFileToContainer(MountableFile.forClasspathResource(FAST_LOG_FLUSH_CONFIG),
                    "/etc/clickhouse-server/config.d/" + FAST_LOG_FLUSH_CONFIG);
    private final RedisContainer redisContainer = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer mysqlContainer = MySQLContainerUtils.newMySQLContainer();
    private final WireMockUtils.WireMockRuntime wireMock;
    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();
    private final TransactionTemplateAsync template;

    {
        Startables.deepStart(redisContainer, mysqlContainer, clickHouseContainer, zookeeperContainer).join();
        wireMock = WireMockUtils.startWireMock();
        MigrationUtils.runMysqlDbMigration(mysqlContainer);
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);
        template = TransactionTemplateAsync.create(ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(clickHouseContainer, ClickHouseContainerUtils.DATABASE_NAME)
                .build());
        ensurePartitionedSuccessorUnderTraces();
        ensureDistributedWrap();
    }

    // Declared after the initialiser on purpose: the topology is installed before the app boots against it.
    @RegisterApp
    private final TestDropwizardAppExtension app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
            AppContextConfig.builder()
                    .jdbcUrl(mysqlContainer.getJdbcUrl())
                    .databaseAnalyticsFactory(ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                            clickHouseContainer, ClickHouseContainerUtils.DATABASE_NAME))
                    .redisUrl(redisContainer.getRedisURI())
                    .runtimeInfo(wireMock.runtimeInfo())
                    .customConfigs(List.of(
                            new CustomConfig("databaseAnalyticsDataModel.traceColumnsNonNullable", "true"),
                            new CustomConfig("databaseAnalyticsDataModel.tracesDistributedWrapEnabled", "true"),
                            new CustomConfig("uuidValidation.enabled", "false")))
                    .build());

    private TraceResourceClient traceResourceClient;
    private ProjectResourceClient projectResourceClient;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        traceResourceClient = new TraceResourceClient(clientSupport, baseUrl);
        projectResourceClient = new ProjectResourceClient(clientSupport, baseUrl, factory);
    }

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
        clickHouseContainer.stop();
        zookeeperContainer.stop();
        network.close();
    }

    /** The searched project's traces, one thread each, in ordinary and far-future weeks. */
    private record Seeded(String token, String projectName, List<Trace> expected) {
    }

    private Seeded seed() {
        var token = RandomStringUtils.secure().nextAlphanumeric(12);
        // Sorted, so the middle project sits between the other two in the (workspace_id, project_id, id) key.
        var projects = Stream.generate(() -> "project-" + RandomStringUtils.secure().nextAlphanumeric(16))
                .limit(3)
                .collect(Collectors.toMap(
                        name -> projectResourceClient.createProject(name, API_KEY, WORKSPACE_NAME), name -> name))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
        var project = projects.get(1);
        // One batch, so one part whose key range brackets the searched project in a week it has no traces in.
        traceResourceClient.batchCreateTraces(Stream.of(projects.get(0), projects.get(2))
                .map(neighbour -> newTrace(neighbour.getValue(), FILLER_MONDAY, token))
                .toList(), API_KEY, WORKSPACE_NAME);
        var expected = PROJECT_MONDAYS.stream()
                .map(monday -> newTrace(project.getValue(), monday, token))
                .sorted(Comparator.comparing(Trace::id).reversed())
                .toList();
        traceResourceClient.batchCreateTraces(expected, API_KEY, WORKSPACE_NAME);
        // `traces` is a Distributed wrapper here, which forwards inserts to traces_local in the background.
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> traceResourceClient.getTraces(project.getValue(), null, API_KEY, WORKSPACE_NAME,
                        List.of(), List.of(), 10, Map.of()).total() == expected.size());
        return new Seeded(token, project.getValue(), expected);
    }

    /** The searched data and the responses. */
    private record Search(String token, List<Trace> expected, Trace.TracePage page, ProjectStats stats) {
    }

    private Search search() {
        var seeded = seed();
        var params = searchParams(seeded);
        var page = traceResourceClient.getTraces(seeded.projectName(), null, API_KEY, WORKSPACE_NAME, List.of(),
                List.of(), 10, params);
        var stats = traceResourceClient.getTraceStats(seeded.projectName(), null, API_KEY, WORKSPACE_NAME, null,
                params);
        return new Search(seeded.token(), seeded.expected(), page, stats);
    }

    /** The searched threads and the responses. */
    private record ThreadSearch(String token, List<String> expectedThreadIds, TraceThreadPage page,
            ProjectStats stats) {
    }

    private ThreadSearch threadSearch() {
        var seeded = seed();
        var params = searchParams(seeded);
        var expectedThreadIds = seeded.expected().stream().map(Trace::threadId).sorted().toList();
        // A time-bounded thread list joins trace_threads, whose rows closing a thread writes.
        expectedThreadIds.forEach(threadId -> traceResourceClient.closeTraceThread(threadId, null,
                seeded.projectName(), API_KEY, WORKSPACE_NAME));
        var page = Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> traceResourceClient.getTraceThreads(null, seeded.projectName(), API_KEY, WORKSPACE_NAME,
                        List.of(), List.of(), params), threads -> threads.total() == expectedThreadIds.size());
        var stats = traceResourceClient.getTraceThreadStats(seeded.projectName(), null, API_KEY, WORKSPACE_NAME,
                null, params);
        return new ThreadSearch(seeded.token(), expectedThreadIds, page, stats);
    }

    private static Map<String, String> searchParams(Seeded seeded) {
        return Map.of("search", seeded.token(), "from_time", FROM_TIME.toString());
    }

    @Test
    @DisplayName("a search bounded to the project's own weeks still returns its matches in every week")
    void searchBoundedToProjectWeeksReturnsEveryMatch() {
        var search = search();

        assertThat(search.page().total()).isEqualTo(search.expected().size());
        TraceAssertions.assertTraces(search.page().content(), search.expected(), USER);
        TraceAssertions.assertStats(search.stats().stats(),
                StatsUtils.getProjectTraceStatItems(search.page().content()));
    }

    /** One search shared by the statement cases, each checking a different statement of it. */
    private Stream<Arguments> searchStatements() {
        var search = search();
        return Stream.of("find_traces_by_project_id", "count_traces_by_project", "get_trace_stats_traces_spans")
                .map(queryName -> Arguments.of(queryName, search));
    }

    @ParameterizedTest(name = "{0} carries the traces week hint")
    @MethodSource("searchStatements")
    @DisplayName("each search statement carries the traces week hint")
    void searchStatementCarriesTheWeekHint(String queryName, Search search) {
        assertThat(lastSearch(queryName, search.token()))
                .as("the traces week hint ran in %s, so the results are not a vacuous pass", queryName)
                .contains("SELECT DISTINCT toYYYYMMDD(toDate32(id_at)");
    }

    @Test
    @DisplayName("the page re-reads its rows through the cached page-id scalar, so the search runs once")
    void searchRunsOnce() {
        var search = search();

        assertThat(lastSearch("find_traces_by_project_id", search.token()))
                .contains("IN (SELECT arrayJoin((SELECT groupArray(id) FROM page_ids)))")
                .doesNotContain("IN (SELECT id FROM page_ids)");
    }

    @Test
    @DisplayName("a thread search bounded to the project's own weeks still returns its threads in every week")
    void threadSearchBoundedToProjectWeeksReturnsEveryMatch() {
        var search = threadSearch();

        assertThat(search.page().content()).extracting(TraceThread::id)
                .containsExactlyInAnyOrderElementsOf(search.expectedThreadIds());
        assertThat(search.stats().stats())
                .filteredOn(stat -> StatsMapper.THREAD_COUNT.equals(stat.getName()))
                .singleElement()
                .extracting(ProjectStats.ProjectStatItem::getValue)
                .isEqualTo((long) search.expectedThreadIds().size());
    }

    /** One thread search shared by the statement cases, each checking a different statement of it. */
    private Stream<Arguments> threadSearchStatements() {
        var search = threadSearch();
        return Stream.of("find_threads_by_project", "count_threads_by_project", "thread_stats")
                .map(queryName -> Arguments.of(queryName, search));
    }

    @ParameterizedTest(name = "{0} carries the traces week hint")
    @MethodSource("threadSearchStatements")
    @DisplayName("each thread search statement carries the traces week hint")
    void threadSearchStatementCarriesTheWeekHint(String queryName, ThreadSearch search) {
        assertThat(lastSearch(queryName, search.token()))
                .as("the traces week hint ran in %s, so the results are not a vacuous pass", queryName)
                .contains("SELECT DISTINCT toYYYYMMDD(toDate32(id_at)");
    }

    @Test
    @DisplayName("the thread page re-reads the matched traces through the cached id scalar, so the search runs once")
    void threadSearchRunsOnce() {
        var search = threadSearch();

        assertThat(lastSearch("find_threads_by_project", search.token()))
                .contains("IN (SELECT arrayJoin((SELECT groupArray(id) FROM traces_final_ids)))")
                .doesNotContain("IN (SELECT id FROM traces_final_ids)");
    }

    /** A trace whose id is minted mid-week, so the partition value is the week's Monday rather than the id's own day. */
    private Trace newTrace(String projectName, LocalDate monday, String token) {
        var idAt = monday.plusDays(2).atTime(12, 0).toInstant(ZoneOffset.UTC);
        // ClickHouse keeps microseconds, and the CI JVM clock has nanoseconds.
        var startTime = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return factory.manufacturePojo(Trace.class).toBuilder()
                .id(ID_GENERATOR.getTimeOrderedEpoch(idAt.toEpochMilli()))
                .startTime(startTime)
                .endTime(startTime.plusMillis(100))
                .projectName(projectName)
                .name("searchable-" + token)
                // Thread search matches thread_id, not the trace name.
                .threadId("thread-" + token + "-" + RandomStringUtils.secure().nextAlphanumeric(8))
                .feedbackScores(null)
                .usage(null)
                .build();
    }

    /** Polled: a statement's query_log row is written asynchronously, flushed every 200 ms here. */
    private String lastSearch(String queryName, String token) {
        return Awaitility.await()
                .alias("query_log holds a " + queryName + " search for " + token)
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> queryOneString(LAST_SEARCH,
                        statement -> statement.bind("token", token).bind("query_name", queryName)),
                        Objects::nonNull);
    }

    /** See {@code TracesPartitionPruningMutationTest#ensurePartitionedSuccessorUnderTraces}. */
    private void ensurePartitionedSuccessorUnderTraces() {
        if (tableExists("traces_local") || partitionKeyOf("traces").contains("id_at")) {
            return;
        }
        assertThat(tableExists("traces_local_v2")).as("traces_local_v2 is needed to install the successor").isTrue();
        execute("EXCHANGE TABLES traces AND traces_local_v2 ON CLUSTER '{cluster}'", _ -> {
        });
        execute("RENAME TABLE traces_local_v2 TO traces_pre_cutover_backup ON CLUSTER '{cluster}'", _ -> {
        });
    }

    /** See {@code TracesPartitionPruningMutationTest#ensureDistributedWrap}. */
    private void ensureDistributedWrap() {
        var engineFull = Optional.ofNullable(queryOneString(TABLE_ENGINE_FULL, _ -> {
        })).orElse("");
        if (engineFull.startsWith("Distributed")) {
            return;
        }
        execute("DROP TABLE IF EXISTS traces_dist ON CLUSTER '{cluster}' SYNC", _ -> {
        });
        execute(TemplateUtils.newST(CREATE_DISTRIBUTED_WRAPPER)
                .add("database", ClickHouseContainerUtils.DATABASE_NAME)
                .render(), _ -> {
                });
        execute("""
                RENAME TABLE
                    traces TO traces_local,
                    traces_dist TO traces
                    ON CLUSTER '{cluster}'
                """, _ -> {
        });
    }

    private boolean tableExists(String table) {
        return "1".equals(queryOneString(TABLE_COUNT, statement -> statement.bind("table", table)));
    }

    private String partitionKeyOf(String table) {
        return Optional
                .ofNullable(queryOneString(PARTITION_KEY_OF_TABLE, statement -> statement.bind("table", table)))
                .orElse("");
    }

    private String queryOneString(String sql, Consumer<Statement> binder) {
        return template.nonTransaction(connection -> {
            var statement = connection.createStatement(sql);
            binder.accept(statement);
            return Mono.from(statement.execute())
                    .flatMap(result -> Mono.from(result.map((row, _) -> row.get(0, String.class))));
        }).block();
    }

    private void execute(String sql, Consumer<Statement> binder) {
        template.nonTransaction(connection -> {
            var statement = connection.createStatement(sql);
            binder.accept(statement);
            return Mono.from(statement.execute()).flatMap(result -> Mono.from(result.getRowsUpdated()));
        }).block();
    }
}
