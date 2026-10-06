package com.comet.opik.infrastructure;

import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.Trace;
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
import org.junit.jupiter.params.provider.ValueSource;
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

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

    /** The searched data and the responses, created once and shared by the tests below. */
    private record Search(String token, List<Trace> expected, Trace.TracePage page, ProjectStats stats) {
    }

    private Search search;

    private synchronized Search search() {
        if (search != null) {
            return search;
        }
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
        var params = Map.of("search", token, "from_time", FROM_TIME.toString());
        var page = traceResourceClient.getTraces(project.getValue(), null, API_KEY, WORKSPACE_NAME, List.of(),
                List.of(), 10, params);
        var stats = traceResourceClient.getTraceStats(project.getValue(), null, API_KEY, WORKSPACE_NAME, null, params);
        search = new Search(token, expected, page, stats);
        return search;
    }

    @Test
    @DisplayName("a search bounded to the project's own weeks still returns its matches in every week")
    void searchBoundedToTheProjectsWeeksReturnsEveryMatch() {
        var search = search();

        assertThat(search.page().total()).isEqualTo(search.expected().size());
        TraceAssertions.assertTraces(search.page().content(), search.expected(), USER);
        TraceAssertions.assertStats(search.stats().stats(),
                StatsUtils.getProjectTraceStatItems(search.page().content()));
    }

    @ParameterizedTest(name = "{0} carries the traces week hint")
    @ValueSource(strings = {"find_traces_by_project_id", "count_traces_by_project", "get_trace_stats_traces_spans"})
    @DisplayName("each search statement carries the traces week hint")
    void searchStatementCarriesTheWeekHint(String queryName) {
        var expectedWeeks = search().expected().stream().map(Trace::id)
                .map(TracesSearchPartitionPruningTest::mondayOfId)
                .collect(Collectors.toSet());
        // Run each pre-pass from the statement the app executed, and compare with the weeks the written rows fall in.
        assertThat(preparedWeekSets(lastSearch(queryName, search().token())))
                .as("the traces week hint in %s selects exactly the project's weeks", queryName)
                .isNotEmpty()
                .allSatisfy(weeks -> assertThat(weeks).containsExactlyInAnyOrderElementsOf(expectedWeeks));
    }

    @Test
    @DisplayName("the page re-reads its rows through the cached page-id scalar, so the search runs once")
    void searchRunsOnce() {
        assertThat(lastSearch("find_traces_by_project_id", search().token()))
                .contains("IN (SELECT arrayJoin((SELECT groupArray(id) FROM page_ids)))")
                .doesNotContain("IN (SELECT id FROM page_ids)");
    }

    /** A trace whose id is minted mid-week, so the partition value is the week's Monday rather than the id's own day. */
    private Trace newTrace(String projectName, LocalDate monday, String token) {
        var idAt = monday.plusDays(2).atTime(12, 0).toInstant(ZoneOffset.UTC);
        return factory.manufacturePojo(Trace.class).toBuilder()
                .id(ID_GENERATOR.getTimeOrderedEpoch(idAt.toEpochMilli()))
                .projectName(projectName)
                .name("searchable-" + token)
                .feedbackScores(null)
                .usage(null)
                .build();
    }

    /**
     * Runs every week pre-pass in a logged statement on its own and returns the weeks each yields, so a test can
     * compare them with the weeks it derives from the rows it wrote. The logged statement carries its values inline.
     */
    private List<Set<String>> preparedWeekSets(String statement) {
        var sets = new ArrayList<Set<String>>();
        for (int at = statement.indexOf("SELECT DISTINCT toYYYYMMDD"); at >= 0; at = statement
                .indexOf("SELECT DISTINCT toYYYYMMDD", at + 1)) {
            int open = statement.lastIndexOf('(', at);
            int depth = 0, close = open;
            do {
                char c = statement.charAt(close++);
                depth += c == '(' ? 1 : c == ')' ? -1 : 0;
            } while (depth > 0);
            var subquery = statement.substring(open + 1, close - 1);
            sets.add(new HashSet<>(template.nonTransaction(connection -> Mono.from(connection
                    .createStatement(subquery).execute())
                    .flatMapMany(result -> result.map((row, _) -> String.valueOf(row.get(0))))
                    .collectList()).block()));
        }
        return sets;
    }

    private static String mondayOfId(UUID id) {
        var monday = Instant.ofEpochMilli(id.getMostSignificantBits() >>> 16).atZone(ZoneOffset.UTC).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return "%04d%02d%02d".formatted(monday.getYear(), monday.getMonthValue(), monday.getDayOfMonth());
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
