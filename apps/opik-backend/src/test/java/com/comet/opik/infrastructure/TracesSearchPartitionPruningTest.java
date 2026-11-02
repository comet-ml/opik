package com.comet.opik.infrastructure;

import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.TraceThread.TraceThreadPage;
import com.comet.opik.api.TraceThreadStatus;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.DurationUtils;
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
import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortableFields;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.template.TemplateUtils;
import com.redis.testcontainers.RedisContainer;
import io.r2dbc.spi.Statement;
import lombok.Builder;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.FilterTestUtils.threadIdFilter;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * The reads that load the large columns of a project's traces, on the weekly-partitioned {@code traces}, where a
 * load the planner cannot bound opens every part from the window start through the far-future weeks a bad client clock
 * creates. A trace or thread search reads only the weeks the project's own traces fall in (a key-only pre-pass). A
 * thread read that loads the traces of ids it first resolved by thread id reads only those ids' weeks: the thread
 * list's page, its trace-set prefilter (a thread id filter, which the thread count, stats and search stream take too)
 * and the thread by id. The legacy table's side of the thread reads is {@code TracesReadPathWeekBoundTest}'s.
 * <p>
 * A pruning hint must never drop a row, so each case checks the whole response across ordinary and far-future weeks,
 * next to a neighbouring part whose key range brackets the project, and that each statement prunes: {@code traces} is
 * the {@code Distributed} wrapper here, so every read is forwarded to {@code traces_local} as a statement of its own.
 * A search's reads of the text columns must touch only the project's weeks; the key-only pre-pass is forwarded
 * separately and touches the neighbours' week too. A thread read may go beyond its thread's weeks in exactly one read,
 * the lookup of its trace ids by thread id, which names no week. That is why the initial statement's partitions cannot
 * show this. That the reads prune has no effect a client can see, so it is read from {@code system.query_log}.
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

    private static final int WINDOW_WEEKS = 52;
    private static final LocalDate THIS_MONDAY = LocalDate.now(ZoneOffset.UTC)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    private static final Instant FROM_TIME = THIS_MONDAY.minusWeeks(WINDOW_WEEKS).atStartOfDay()
            .toInstant(ZoneOffset.UTC);
    /** Three distinct weeks inside the search window: two for the project, one only its neighbours have traces in. */
    private static final List<LocalDate> WINDOW_MONDAYS = Stream
            .generate(() -> RandomUtils.secure().randomInt(1, WINDOW_WEEKS))
            .distinct()
            .limit(3)
            .map(THIS_MONDAY::minusWeeks)
            .toList();
    /**
     * The searched project's traces, and the tested thread's: two ordinary weeks and a far-future one, as a bad clock
     * files them.
     */
    private static final List<LocalDate> PROJECT_MONDAYS = List.of(WINDOW_MONDAYS.get(0), WINDOW_MONDAYS.get(1),
            LocalDate.of(RandomUtils.secure().randomInt(2150, 2290), 1, 1)
                    .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)));
    /** The neighbours' week, which the thread reads' project also has its other thread in. */
    private static final LocalDate FILLER_MONDAY = WINDOW_MONDAYS.get(2);
    private static final Set<String> PROJECT_PARTITIONS = PROJECT_MONDAYS.stream()
            .map(monday -> "%s.traces_local.%s".formatted(ClickHouseContainerUtils.DATABASE_NAME,
                    monday.format(DateTimeFormatter.BASIC_ISO_DATE)))
            .collect(Collectors.toSet());

    /**
     * One row per forwarded read of any of the given {@code traces_local} columns: the initial statement it belongs to,
     * and the partitions that read touched. A statement is told apart by its query name and by a value the test chose
     * in its text: the search token, or the project id.
     */
    private static final String LOAD_PARTITIONS = """
            SELECT initial_query_id, arrayStringConcat(partitions, ',')
            FROM system.query_log
            WHERE initial_query_id IN (
                SELECT query_id
                FROM system.query_log
                WHERE log_comment LIKE concat(:query_name, ':%')
                AND type = 'QueryFinish'
                AND is_initial_query
                AND query LIKE concat('%', :mentioned, '%'))
            AND NOT is_initial_query
            AND type = 'QueryFinish'
            AND hasAny(columns, :columns)
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
                    "/etc/clickhouse-server/config.d/%s".formatted(FAST_LOG_FLUSH_CONFIG));
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

    @Test
    @DisplayName("a search bounded to the project's own weeks still returns its matches in every week")
    void searchBoundedToProjectWeeksReturnsEveryMatch() {
        var search = search();

        var actualPage = search.page();
        var expectedTraces = search.expected();
        assertThat(actualPage.total()).isEqualTo(expectedTraces.size());
        TraceAssertions.assertTraces(actualPage.content(), expectedTraces, USER);
        var actualStats = search.stats().stats();
        var expectedStats = StatsUtils.getProjectTraceStatItems(actualPage.content());
        TraceAssertions.assertStats(actualStats, expectedStats);
    }

    /** One search shared by the statement cases, each checking a different statement of it. */
    private Stream<Arguments> searchStatements() {
        var search = search();
        return Stream.of("find_traces_by_project_id", "count_traces_by_project", "get_trace_stats_traces_spans")
                .map(queryName -> Arguments.of(queryName, search));
    }

    @ParameterizedTest(name = "{0} reads the text columns of only the project's weeks")
    @MethodSource("searchStatements")
    void searchStatementReadsOnlyProjectWeeks(String queryName, Search search) {
        assertSearchScansReadOnlyProjectWeeks(queryName, search.token());
    }

    @Test
    @DisplayName("a thread search bounded to the project's own weeks still returns its threads in every week")
    void threadSearchBoundedToProjectWeeksReturnsEveryMatch() {
        var search = threadSearch();

        var expectedThreads = search.expected();
        var actualPage = search.page();
        assertThat(actualPage.total()).isEqualTo(expectedThreads.size());
        TraceAssertions.assertThreads(expectedThreads, actualPage.content());
        var actualSearchOnlyPage = search.searchOnlyPage();
        assertThat(actualSearchOnlyPage.total()).isEqualTo(expectedThreads.size());
        TraceAssertions.assertThreads(expectedThreads, actualSearchOnlyPage.content());
        var actualStats = search.stats().stats();
        var expectedStats = StatsUtils.getProjectThreadStatItems(search.traces(), List.of(), null);
        TraceAssertions.assertStats(actualStats, expectedStats);
    }

    /** One thread search shared by the statement cases, each checking a different statement of it. */
    private Stream<Arguments> threadSearchStatements() {
        var search = threadSearch();
        return Stream.of("find_thread_page_ids", "find_threads_by_project", "count_threads_by_project",
                "thread_stats")
                .map(queryName -> Arguments.of(queryName, search));
    }

    // The time-bounded and the search-only page share a query name, so both of their statements are checked.
    @ParameterizedTest(name = "{0} reads the text columns of only the project's weeks")
    @MethodSource("threadSearchStatements")
    void threadSearchStatementReadsOnlyProjectWeeks(String queryName, ThreadSearch search) {
        assertSearchScansReadOnlyProjectWeeks(queryName, search.token());
    }

    /**
     * Each thread read and the statements that load its traces. A one-thread page in id order keeps the page pushdown;
     * a thread id filter turns on the trace-set prefilter, which the page's count takes too, and turns the pushdown off.
     */
    private Stream<Arguments> threadReadLoadsOnlyItsThreadWeeks() {
        var idOrder = List.of(SortingField.builder().field(SortableFields.ID).direction(Direction.ASC).build());
        return Stream.of(
                arguments(Named.<Function<SeededThreads, List<TraceThread>>>of("thread list page",
                        seeded -> traceResourceClient.getTraceThreads(seeded.projectId(), null, API_KEY,
                                WORKSPACE_NAME, null, idOrder, Map.of("size", "1")).content()),
                        List.of("find_threads_by_project")),
                arguments(Named.<Function<SeededThreads, List<TraceThread>>>of("thread list filtered by thread id",
                        seeded -> traceResourceClient.getTraceThreads(seeded.projectId(), null, API_KEY,
                                WORKSPACE_NAME, threadIdFilter(seeded.thread().id()), null, Map.of()).content()),
                        List.of("find_threads_by_project", "count_threads_by_project")),
                arguments(Named.<Function<SeededThreads, List<TraceThread>>>of(
                        "thread search stream filtered by thread id",
                        seeded -> traceResourceClient.searchTraceThreadsStream(null, seeded.projectId(), API_KEY,
                                WORKSPACE_NAME, threadIdFilter(seeded.thread().id()))),
                        List.of("search_threads")),
                arguments(Named.<Function<SeededThreads, List<TraceThread>>>of("thread by id",
                        seeded -> List.of(traceResourceClient.getTraceThread(seeded.thread().id(),
                                seeded.projectId(), API_KEY, WORKSPACE_NAME))),
                        List.of("find_thread_by_id")));
    }

    @ParameterizedTest(name = "{0} returns the whole thread and loads only its weeks")
    @MethodSource
    void threadReadLoadsOnlyItsThreadWeeks(Function<SeededThreads, List<TraceThread>> read, List<String> queryNames) {
        var seeded = seedThreads();

        var actualThreads = read.apply(seeded);

        var expectedThreads = List.of(seeded.thread());
        TraceAssertions.assertThreads(expectedThreads, actualThreads);
        queryNames.forEach(queryName -> assertOnlyTheThreadIdLookupLeavesTheThreadWeeks(queryName,
                seeded.projectId()));
    }

    @Test
    void threadStatsFilteredByThreadIdCountTheWholeThreadAndLoadOnlyItsWeeks() {
        var seeded = seedThreads();

        var actualStats = traceResourceClient.getTraceThreadStats(null, seeded.projectId(), API_KEY, WORKSPACE_NAME,
                threadIdFilter(seeded.thread().id()), Map.of()).stats();

        var expectedStats = StatsUtils.getProjectThreadStatItems(seeded.traces(), List.of(), null);
        TraceAssertions.assertStats(actualStats, expectedStats);
        assertOnlyTheThreadIdLookupLeavesTheThreadWeeks("thread_stats", seeded.projectId());
    }

    /** The next page loads the other thread from the filler week, so bounding the page loses no thread. */
    @Test
    void threadListNextPageReturnsTheOtherThread() {
        var seeded = seedThreads();

        var actualPage = traceResourceClient.getTraceThreads(seeded.projectId(), null, API_KEY, WORKSPACE_NAME, null,
                List.of(SortingField.builder().field(SortableFields.ID).direction(Direction.ASC).build()),
                Map.of("size", "1", "page", "2"));

        var expectedThreads = List.of(seeded.otherThread());
        assertThat(actualPage.total()).isEqualTo(2);
        TraceAssertions.assertThreads(expectedThreads, actualPage.content());
    }

    /**
     * Every statement of {@code queryName} for this search has a forwarded read of the text columns that touched
     * exactly the project's weeks, not the neighbours' week inside the window.
     */
    private void assertSearchScansReadOnlyProjectWeeks(String queryName, String token) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    var actualTextReadsByStatement = loadPartitionsByStatement(queryName, token, "input");
                    assertThat(actualTextReadsByStatement).as("%s statements for this search", queryName)
                            .isNotEmpty();
                    assertThat(actualTextReadsByStatement.values()).allSatisfy(actualReads -> assertThat(actualReads)
                            .as("the text-column reads %s forwarded to traces_local", queryName)
                            .contains(PROJECT_PARTITIONS));
                });
    }

    /**
     * Every statement of {@code queryName} for this project read the traces table exactly once beyond the thread's
     * weeks: to look its trace ids up by thread id, which names no week, so that read also opens the filler week its
     * project's other thread is in. Every other read stays within the thread's weeks, and at least one loads them.
     */
    private void assertOnlyTheThreadIdLookupLeavesTheThreadWeeks(String queryName, UUID projectId) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    // Every traces read filters on workspace_id, so this takes all of them.
                    var actualReadsByStatement = loadPartitionsByStatement(queryName, projectId.toString(),
                            "workspace_id");
                    assertThat(actualReadsByStatement).as("%s statements for this project", queryName).isNotEmpty();
                    assertThat(actualReadsByStatement.values()).allSatisfy(actualReads -> {
                        assertThat(actualReads)
                                .as("the reads %s forwarded to traces_local beyond the thread's weeks", queryName)
                                .filteredOn(partitions -> !PROJECT_PARTITIONS.containsAll(partitions))
                                .hasSize(1);
                        assertThat(actualReads)
                                .as("the reads %s forwarded to traces_local within the thread's weeks", queryName)
                                .filteredOn(partitions -> !partitions.isEmpty()
                                        && PROJECT_PARTITIONS.containsAll(partitions))
                                .isNotEmpty();
                    });
                });
    }

    /** The partitions of each forwarded read of any of {@code columns}, by the statement it belongs to. */
    private Map<String, List<Set<String>>> loadPartitionsByStatement(String queryName, String mentioned,
            String... columns) {
        var qualifiedColumns = Stream.of(columns)
                .map(column -> "%s.traces_local.%s".formatted(ClickHouseContainerUtils.DATABASE_NAME, column))
                .toArray(String[]::new);
        return template.nonTransaction(connection -> Flux.from(connection.createStatement(LOAD_PARTITIONS)
                .bind("query_name", queryName)
                .bind("mentioned", mentioned)
                .bind("columns", qualifiedColumns)
                .execute())
                .flatMap(result -> result.map((row, _) -> Map.entry(row.get(0, String.class),
                        partitionsOf(row.get(1, String.class)))))
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList()))))
                .block();
    }

    private static Set<String> partitionsOf(String partitions) {
        return partitions.isEmpty() ? Set.of() : Set.of(partitions.split(","));
    }

    private Search search() {
        var seeded = seed();
        var params = searchParams(seeded);
        var page = traceResourceClient.getTraces(seeded.projectName(), null, API_KEY, WORKSPACE_NAME, List.of(),
                List.of(), 10, params);
        var stats = traceResourceClient.getTraceStats(seeded.projectName(), null, API_KEY, WORKSPACE_NAME, null,
                params);
        return Search.builder().token(seeded.token()).expected(seeded.expected()).page(page).stats(stats).build();
    }

    private ThreadSearch threadSearch() {
        var seeded = seed();
        var params = searchParams(seeded);
        // One thread per trace. The page orders by last update desc, then start time asc: closing the latest start
        // first makes both keys agree, so the order holds even when two closes share a timestamp.
        var expected = seeded.expected().stream()
                .sorted(Comparator.comparing(Trace::startTime))
                .map(trace -> expectedThread(seeded.projectId(), List.of(trace), TraceThreadStatus.INACTIVE))
                .toList();
        // A time-bounded thread list joins trace_threads, whose rows closing a thread writes.
        expected.reversed().forEach(thread -> traceResourceClient.closeTraceThread(thread.id(), null,
                seeded.projectName(), API_KEY, WORKSPACE_NAME));
        var page = Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> traceResourceClient.getTraceThreads(null, seeded.projectName(), API_KEY, WORKSPACE_NAME,
                        List.of(), List.of(), params), threads -> threads.total() == expected.size());
        // Without a time range nothing but the week hint bounds the weeks the page lookup reads.
        var searchOnlyPage = traceResourceClient.getTraceThreads(null, seeded.projectName(), API_KEY,
                WORKSPACE_NAME, List.of(), List.of(), Map.of("search", seeded.token()));
        var stats = traceResourceClient.getTraceThreadStats(seeded.projectName(), null, API_KEY, WORKSPACE_NAME,
                null, params);
        return ThreadSearch.builder()
                .token(seeded.token())
                .traces(seeded.expected())
                .expected(expected)
                .page(page)
                .searchOnlyPage(searchOnlyPage)
                .stats(stats)
                .build();
    }

    private Seeded seed() {
        var token = RandomStringUtils.secure().nextAlphanumeric(32);
        var projects = createBracketedProjects();
        var project = projects.get(1);
        // ClickHouse keeps microseconds, and the CI JVM clock has nanoseconds.
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        // One batch, so one part whose key range brackets the searched project in a week it has no traces in.
        traceResourceClient.batchCreateTraces(Stream.of(projects.get(0), projects.get(2))
                .map(neighbour -> newTrace(neighbour.getValue(), threadId(token), FILLER_MONDAY, token, now))
                .toList(), API_KEY, WORKSPACE_NAME);
        // A start time a second apart per trace, so the thread page's start-time order is unambiguous.
        var expected = IntStream.range(0, PROJECT_MONDAYS.size())
                .mapToObj(i -> newTrace(project.getValue(), threadId(token), PROJECT_MONDAYS.get(i), token,
                        now.minusSeconds(i + 1)))
                .sorted(Comparator.comparing(Trace::id).reversed())
                .toList();
        traceResourceClient.batchCreateTraces(expected, API_KEY, WORKSPACE_NAME);
        awaitTraces(project.getKey(), expected.size());
        return Seeded.builder()
                .token(token)
                .projectId(project.getKey())
                .projectName(project.getValue())
                .expected(expected)
                .build();
    }

    /**
     * The tested thread has a trace in each of the project's weeks; the project's other thread has one in the filler
     * week, in the same part as the neighbours' traces. The tested thread's id sorts first, so it alone fills a
     * one-thread page in id order.
     */
    private SeededThreads seedThreads() {
        var token = RandomStringUtils.secure().nextAlphanumeric(32);
        var projects = createBracketedProjects();
        var project = projects.get(1);
        var threadId = "a-%s".formatted(threadId(token));
        var otherThreadId = "b-%s".formatted(threadId(token));
        // ClickHouse keeps microseconds, and the CI JVM clock has nanoseconds.
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var otherTrace = newTrace(project.getValue(), otherThreadId, FILLER_MONDAY, token, now);
        // One batch, so one part whose key range brackets the project in a week the tested thread has no traces in.
        traceResourceClient.batchCreateTraces(List.of(
                newTrace(projects.get(0).getValue(), otherThreadId, FILLER_MONDAY, token, now),
                otherTrace,
                newTrace(projects.get(2).getValue(), otherThreadId, FILLER_MONDAY, token, now)), API_KEY,
                WORKSPACE_NAME);
        // A start time a second apart per trace, so the thread's first and last message are unambiguous.
        var traces = IntStream.range(0, PROJECT_MONDAYS.size())
                .mapToObj(i -> newTrace(project.getValue(), threadId, PROJECT_MONDAYS.get(i), token,
                        now.minusSeconds(i + 1)))
                .toList();
        traceResourceClient.batchCreateTraces(traces, API_KEY, WORKSPACE_NAME);
        awaitTraces(project.getKey(), traces.size() + 1);
        return SeededThreads.builder()
                .projectId(project.getKey())
                .traces(traces)
                .thread(expectedThread(project.getKey(), traces, TraceThreadStatus.ACTIVE))
                .otherThread(expectedThread(project.getKey(), List.of(otherTrace), TraceThreadStatus.ACTIVE))
                .build();
    }

    /** Three projects by id, so the middle one sits between the other two in the (workspace_id, project_id, id) key. */
    private List<Map.Entry<UUID, String>> createBracketedProjects() {
        return Stream.generate(() -> "project-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(32)))
                .limit(3)
                .collect(Collectors.toMap(
                        name -> projectResourceClient.createProject(name, API_KEY, WORKSPACE_NAME), name -> name))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
    }

    /** {@code traces} is a Distributed wrapper here, which forwards inserts to traces_local in the background. */
    private void awaitTraces(UUID projectId, int count) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> traceResourceClient.getTraces(null, projectId, API_KEY, WORKSPACE_NAME, List.of(),
                        List.of(), 10, Map.of()).total() == count);
    }

    private static Map<String, String> searchParams(Seeded seeded) {
        return Map.of("search", seeded.token(), "from_time", FROM_TIME.toString());
    }

    private static TraceThread expectedThread(UUID projectId, List<Trace> traces, TraceThreadStatus status) {
        var first = traces.stream().min(Comparator.comparing(Trace::startTime)).orElseThrow();
        var last = traces.stream().max(Comparator.comparing(Trace::endTime)).orElseThrow();
        return TraceThread.builder()
                .id(first.threadId())
                .projectId(projectId)
                .firstMessage(first.input())
                .lastMessage(last.output())
                .startTime(first.startTime())
                .endTime(last.endTime())
                .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(first.startTime(), last.endTime()))
                .numberOfMessages(2L * traces.size())
                .status(status)
                .environment(first.environment())
                .build();
    }

    /** Thread search matches thread_id, not the trace name. */
    private static String threadId(String token) {
        return "thread-%s-%s".formatted(token, RandomStringUtils.secure().nextAlphanumeric(32));
    }

    /**
     * A trace whose id is minted mid-week, so the partition value is the week's Monday rather than the id's own day. It
     * lasts a random span to the microsecond, between a millisecond and a second, so a thread that lost a trace cannot
     * keep a round duration the stats comparison would take as equal.
     */
    private Trace newTrace(String projectName, String threadId, LocalDate monday, String token, Instant startTime) {
        var idAt = monday.plusDays(2).atTime(12, 0).toInstant(ZoneOffset.UTC);
        return factory.manufacturePojo(Trace.class).toBuilder()
                .id(ID_GENERATOR.getTimeOrderedEpoch(idAt.toEpochMilli()))
                .startTime(startTime)
                .endTime(startTime.plus(RandomUtils.secure().randomLong(1_000, 1_000_000), ChronoUnit.MICROS))
                .projectName(projectName)
                .name("searchable-%s".formatted(token))
                .threadId(threadId)
                // The thread row keeps the environment of the trace that created it, so a thread has one.
                .environment("env-%s".formatted(threadId))
                .feedbackScores(null)
                .usage(null)
                .build();
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

    /** The searched project's traces, one thread each, in ordinary and far-future weeks. */
    @Builder(toBuilder = true)
    private record Seeded(String token, UUID projectId, String projectName, List<Trace> expected) {
    }

    /** The searched data and the responses. */
    @Builder(toBuilder = true)
    private record Search(String token, List<Trace> expected, Trace.TracePage page, ProjectStats stats) {
    }

    /** The searched threads and the responses, with and without a time range. */
    @Builder(toBuilder = true)
    private record ThreadSearch(String token, List<Trace> traces, List<TraceThread> expected, TraceThreadPage page,
            TraceThreadPage searchOnlyPage, ProjectStats stats) {
    }

    /** The tested thread, its traces, and the project's other thread. */
    @Builder(toBuilder = true)
    private record SeededThreads(UUID projectId, List<Trace> traces, TraceThread thread, TraceThread otherThread) {
    }
}
