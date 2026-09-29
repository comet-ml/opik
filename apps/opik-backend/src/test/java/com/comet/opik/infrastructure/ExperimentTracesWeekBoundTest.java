package com.comet.opik.infrastructure;

import com.comet.opik.api.Column;
import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentBatchUpdate;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.ExperimentItemBulkRecord;
import com.comet.opik.api.ExperimentItemBulkUpload;
import com.comet.opik.api.ExperimentItemStreamRequest;
import com.comet.opik.api.ExperimentStreamRequest;
import com.comet.opik.api.ExperimentUpdate;
import com.comet.opik.api.Project;
import com.comet.opik.api.Trace;
import com.comet.opik.api.filter.ExperimentsComparisonFilter;
import com.comet.opik.api.filter.ExperimentsComparisonValidKnownField;
import com.comet.opik.api.filter.FieldType;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.grouping.GroupBy;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.OptimizationResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesService;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Injector;
import com.redis.testcontainers.RedisContainer;
import io.r2dbc.spi.Statement;
import lombok.Builder;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.resources.ExperimentTestAssertions.EXPERIMENT_ITEMS_IGNORED_FIELDS;
import static com.comet.opik.api.resources.utils.resources.ExperimentTestAssertions.assertExperimentResultsIgnoringFields;
import static org.apache.http.HttpStatus.SC_OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * The week bound the experiment read paths carry on their {@code traces} access (OPIK-8343), against the estate it is
 * written for: a weekly-partitioned {@code traces}.
 *
 * <p>These reads reach {@code traces} through a trace-id set drawn from {@code experiment_items}, which prunes no
 * partition on its own — {@code id_at} is derived from the UUIDv7 {@code id} through a function the planner cannot see
 * through — so each opened parts in every weekly partition. Two claims follow, both driven through the endpoints a
 * client calls:
 *
 * <ul>
 *   <li><b>That the reads prune.</b> This has no row-level symptom: a site that lost its bound returns the same rows
 *   and merely opens every partition again, which is the whole regression. It is taken from the plan of the statement
 *   ClickHouse actually received rather than from one written here, so a statement that stopped emitting the bound
 *   fails even if it still looks right in the DAO.</li>
 *   <li><b>That no row moves.</b> The bound is a consequence of the {@code id IN} subquery it accompanies, so an
 *   experiment reports every trace it did before. The fixture spans several weeks and includes a far-future trace —
 *   the id a broken client clock mints, which the successor files in its own honest week — and a past-ceiling one,
 *   where {@code id_at} saturates and the derived week has to saturate with it.</li>
 * </ul>
 *
 * <p><b>Which comparison implementation this covers.</b> The dataset comparison has two, and which one serves a
 * request is decided by whether the dataset has versions. This suite runs dataset versioning at its production
 * default, on, under which every dataset created through the batch endpoint gets a version — so the comparison here
 * is {@code DatasetItemVersionDAO}'s, the one production serves. {@code DatasetItemDAO}'s copy is reachable only with
 * the toggle off, and is covered on the legacy estate by {@link ExperimentReadPathWeekBoundTest}.
 *
 * <p>The optimizations list is here for the same reason: its own {@code traces} reads are bounded elsewhere
 * (OPIK-8333), but the experiment path it shares with these DAOs is bounded by this change, and that path is what
 * set the statement's partition count.
 *
 * <p>The legacy estate is {@link ExperimentReadPathWeekBoundTest}'s: there the bound is not emitted at all, and these
 * statements are the ones that shipped before this change.
 *
 * <p><b>Topology is setup, never the subject.</b> After the migrations the live {@code traces} is still the legacy
 * table and the successor exists only as the empty {@code traces_local_v2}, so
 * {@link #ensurePartitionedSuccessorUnderTraces()} runs the EXCHANGE step of the cutover before the app boots —
 * idempotent, so it becomes a no-op once the cutover migration lands. The {@code Distributed} wrap is deliberately not
 * applied: {@code EXPLAIN} through a {@code Distributed} table reports remote reads rather than the part selection
 * asserted here. {@code TracesLocalV2CutoverTest} owns the cutover itself.
 *
 * <p>A dedicated, non-reused ClickHouse, because the EXCHANGE destructively renames the live {@code traces}; it
 * carries {@code clickhouse-fast-log-flush.xml} so the statement reads can poll {@code system.query_log} instead of
 * forcing a server-wide flush that races the rows it is meant to reveal.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class ExperimentTracesWeekBoundTest {

    private static final String API_KEY = "apiKey-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(32));
    private static final String WORKSPACE_NAME = "workspace-%s"
            .formatted(RandomStringUtils.secure().nextAlphanumeric(32));
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(32));

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    /** The {@code log_comment} query names the DAOs stamp, by which each statement is read back. */
    private static final String FIND_EXPERIMENTS = "find_experiments";
    private static final String COUNT_EXPERIMENTS = "count_experiments";
    private static final String GET_EXPERIMENT_BY_ID = "get_experiment_by_id";
    private static final String GET_EXPERIMENT_METADATA_BY_ID = "get_experiment_metadata_by_id";
    private static final String GET_EXPERIMENTS_STREAM = "get_experiments_stream";
    private static final String GET_EXPERIMENTS_BY_IDS = "get_experiments_by_ids";
    private static final String FIND_EXPERIMENT_GROUPS = "find_experiment_groups";
    private static final String FIND_EXPERIMENT_GROUPS_AGGREGATIONS = "find_experiment_groups_aggregations";
    private static final String GET_TARGET_PROJECT_IDS_FOR_EXPERIMENTS = "get_target_project_ids_for_experiments";
    private static final String GET_AGGREGATION_BRANCH_COUNTS = "get_aggregation_branch_counts";
    private static final String GET_TARGET_PROJECT_IDS_EXPERIMENT_ITEMS = "get_target_project_ids_experiment_items";
    private static final String GET_EXPERIMENT_ITEMS_STREAM = "get_experiment_items_stream";
    private static final String GET_TARGET_PROJECT_IDS_VERSIONS = "get_target_project_ids";
    private static final String GET_COMPARISON = "get_dataset_item_versions_with_experiment_items";
    private static final String COUNT_COMPARISON = "count_dataset_item_versions_with_experiment_items";
    private static final String GET_COMPARISON_STATS = "get_dataset_item_versions_with_experiment_items_stats";
    private static final String GET_OUTPUT_COLUMNS = "get_experiment_items_output_columns";
    private static final String GET_PROJECT_IDS = "getProjectIds";
    private static final String GET_TRACE_AGGREGATIONS = "getTraceAggregations";
    private static final String GET_TRACES_DATA = "getTracesData";
    private static final String FIND_OPTIMIZATIONS = "find_optimizations";
    private static final String GET_OPTIMIZATION_BY_ID = "get_optimization_by_id";

    /** How {@code EXPLAIN} names a {@code traces} read in the plan. */
    private static final String TRACES_TABLE = "%s.traces".formatted(ClickHouseContainerUtils.DATABASE_NAME);

    /**
     * The week expression is the sole use of {@code toDayOfWeek} in these statements, so its presence is exactly "the
     * week bound rendered" without pinning how the predicate is spelled.
     */
    private static final String WEEK_BOUND_MARKER = "toDayOfWeek";

    /**
     * The index entries that decide which <em>parts</em> a read opens, as opposed to which granules. ClickHouse
     * applies them in this order, each narrowing the previous one's selection, and which of the two carries the week
     * bound is a planner decision rather than a property of the query — so both are read and reduced together.
     */
    private static final Set<String> PARTITION_ANALYSIS = Set.of("MinMax", "Partition");

    /**
     * Carried by every fixture trace's input, so a comparison searching for it matches all of them. That is what lets
     * the count case see a bound that dropped a trace: the expected count is every item, not some of them.
     */
    private static final String SEARCH_MARKER = "marker-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(16));

    /** The weeks seeded with traces the experiments do not reference, so there is something for the bound to prune. */
    private static final int UNREFERENCED_WEEKS = 5;

    private static final String LAST_STATEMENT_FOR = """
            SELECT query
            FROM system.query_log
            WHERE (log_comment = :op OR log_comment LIKE concat(:op, ':%'))
            AND type = 'QueryFinish'
            ORDER BY event_time_microseconds DESC
            LIMIT 1
            """;

    private static final String PARTITION_KEY_OF_TABLE = """
            SELECT partition_key
            FROM system.tables
            WHERE database = currentDatabase()
            AND name = :table
            """;

    private static final String TABLE_COUNT = """
            SELECT toString(count())
            FROM system.tables
            WHERE database = currentDatabase()
            AND name = :table
            """;

    /**
     * Wraps the statement read back from {@code query_log}, which already carries its values inline. Formatted rather
     * than rendered through StringTemplate: the statement is opaque SQL and a {@code <} anywhere in it would read as a
     * template expression.
     */
    private static final String EXPLAIN_PLAN = "EXPLAIN indexes = 1, json = 1 %s";

    /** See the file itself for why the faster flush interval is opt-in rather than part of the shared config. */
    private static final String FAST_LOG_FLUSH_CONFIG = "clickhouse-fast-log-flush.xml";

    private static final String NO_PARTITIONED_TABLE = """
            neither a partitioned `traces` (partition key: '%s') nor `traces_local_v2` is present - this suite \
            needs one of those states to install the successor from""";

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

    /**
     * The week the experiments' own traces fall in, and the origin every other week is named relative to. Historical,
     * so the weeks around it exist without depending on the wall clock, and random within that, since nothing about
     * the bound depends on which week it is.
     */
    private final LocalDate anchorMonday = LocalDate.now(ZoneOffset.UTC)
            .minusWeeks(RandomUtils.secure().randomInt(8, 52))
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

    /**
     * The last millisecond of the week before the anchor. {@code UUIDv7ToDateTime} yields milliseconds and both
     * {@code id_at} and the bound reduce that to whole seconds — the column through its MATERIALIZED cast, the bound
     * through {@code toDateTime64(..., 0, 'UTC')}. Agreeing there is what makes the bound a hint rather than a
     * filter, and only an id this close to a boundary can tell the two reductions apart: one rounding where the other
     * truncates moves this id a whole week, and the trace leaves its experiment.
     */
    private final Instant weekBoundaryIdAt = anchorMonday.atStartOfDay().toInstant(ZoneOffset.UTC).minusMillis(1);

    /**
     * The {@code id_at} a far-future id carries — the shape a broken client clock mints. Past {@code Date}'s 2149
     * ceiling, so a narrow week expression would fold it into a plausible recent week, and inside {@code DateTime64}'s,
     * so the successor stores its real week and files it in a partition of its own.
     */
    private final Instant farFutureIdAt = LocalDate.of(RandomUtils.secure().randomInt(2150, 2296), 6, 1)
            .atStartOfDay()
            .toInstant(ZoneOffset.UTC);

    /**
     * Past the end of {@code DateTime64}'s range, where {@code id_at} saturates rather than storing the real week. The
     * derived week has to saturate identically or the row is lost — the one era a derivation done outside ClickHouse
     * cannot reproduce and has to fall back for.
     */
    private final Instant pastCeilingIdAt = LocalDate.of(RandomUtils.secure().randomInt(2300, 2400), 1, 1)
            .atStartOfDay()
            .toInstant(ZoneOffset.UTC);

    /**
     * Runs the topology setup and this suite's own reads of {@code system.query_log} and {@code EXPLAIN} straight
     * against the container, with no app in the way — it has to be app-independent, since the topology must be
     * installed before the app boots against it.
     */
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
    }

    /**
     * Declared after the instance initialiser on purpose: field initialisers run in textual order, so the partitioned
     * successor is under {@code traces} before the app boots against it.
     */
    @RegisterApp
    private final TestDropwizardAppExtension app = newApp();

    /**
     * The app as production runs post-cutover.
     * <p>
     * {@code traceColumnsNonNullable} is what makes the successor's sentinel-defaulted columns writable, and the same
     * flag is what tells these DAOs {@code traces} is weekly partitioned and so may carry the week bound. Without it
     * every assertion here would pass vacuously against the unbounded form.
     * <p>
     * {@code uuidValidation} is the production default, which {@code config-test.yml} turns on so other suites can
     * cover the validator itself. Off is what lets this suite's backdated, far-future and past-ceiling ids reach the
     * table through the ingestion endpoint rather than through an {@code INSERT} that bypasses it.
     * <p>
     * Dataset versioning is left at its production default, which is on — see the class javadoc for why the fixture
     * then covers both comparison implementations rather than one.
     */
    private TestDropwizardAppExtension newApp() {
        return TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(mysqlContainer.getJdbcUrl())
                        .databaseAnalyticsFactory(ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                                clickHouseContainer, ClickHouseContainerUtils.DATABASE_NAME))
                        .redisUrl(redisContainer.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        .customConfigs(List.of(
                                new CustomConfig("databaseAnalyticsDataModel.traceColumnsNonNullable", "true"),
                                new CustomConfig("uuidValidation.enabled", "false")))
                        .build());
    }

    private DatasetResourceClient datasetResourceClient;
    private ExperimentResourceClient experimentResourceClient;
    private OptimizationResourceClient optimizationResourceClient;
    private ProjectResourceClient projectResourceClient;
    private TraceResourceClient traceResourceClient;

    /**
     * The aggregation job's own entry point, called directly because no request reaches it: the subscriber that
     * normally drives it debounces for two minutes. The same exception {@code DatasetsResourceTest} and
     * {@code DatasetVersionResourceTest} take, and at the service rather than the DAO, so the {@code traces} reads
     * it performs are reached the way production reaches them.
     */
    private ExperimentAggregatesService experimentAggregatesService;

    /** The one fixture every case reads: rebuilding it per case would only slow the suite down. */
    private Fixture fixture;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport, Injector injector) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        this.datasetResourceClient = new DatasetResourceClient(clientSupport, baseUrl);
        this.experimentResourceClient = new ExperimentResourceClient(clientSupport, baseUrl, factory);
        this.optimizationResourceClient = new OptimizationResourceClient(clientSupport, baseUrl, factory);
        this.projectResourceClient = new ProjectResourceClient(clientSupport, baseUrl, factory);
        this.traceResourceClient = new TraceResourceClient(clientSupport, baseUrl);
        this.experimentAggregatesService = injector.getInstance(ExperimentAggregatesService.class);
        this.fixture = seed();
    }

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
        clickHouseContainer.stop();
        zookeeperContainer.stop();
        network.close();
    }

    private Stream<Arguments> everyReadPrunes() {
        // Read at call time rather than captured: @MethodSource runs before @BeforeAll has wired the clients.
        Runnable projectScopedList = () -> experimentResourceClient.getProjectExperiments(fixture.projectId(), 1, 10,
                null, null, null, false, null, null, false, API_KEY, WORKSPACE_NAME, SC_OK);
        Runnable compare = () -> compare(fixture.datasetId(), fixture.experimentId(), null);
        var groupByDataset = List.of(GroupBy.builder().field("dataset_id").type(FieldType.STRING).build());

        return Stream.of(
                // The project-scoped list runs these in one request, each on its own statement.
                arguments(FIND_EXPERIMENTS, projectScopedList),
                arguments(COUNT_EXPERIMENTS, projectScopedList),
                arguments(GET_TARGET_PROJECT_IDS_FOR_EXPERIMENTS, projectScopedList),
                arguments(GET_EXPERIMENT_BY_ID, (Runnable) () -> experimentResourceClient
                        .getExperiment(fixture.experimentId(), API_KEY, WORKSPACE_NAME)),
                arguments(GET_EXPERIMENTS_STREAM, (Runnable) () -> experimentResourceClient.streamExperiments(
                        ExperimentStreamRequest.builder().name(fixture.experimentName()).build(), API_KEY,
                        WORKSPACE_NAME)),
                arguments(GET_EXPERIMENTS_BY_IDS, (Runnable) this::batchUpdateExperiment),
                arguments(FIND_EXPERIMENT_GROUPS, (Runnable) () -> experimentResourceClient.findGroups(
                        groupByDataset, null, null, null, fixture.projectId(), API_KEY, WORKSPACE_NAME, SC_OK)),
                arguments(FIND_EXPERIMENT_GROUPS_AGGREGATIONS,
                        (Runnable) () -> experimentResourceClient.findGroupsAggregations(groupByDataset, null, null,
                                null, fixture.projectId(), API_KEY, WORKSPACE_NAME, SC_OK)),
                // The item stream runs its own target-projects read first and feeds it into the main one.
                arguments(GET_TARGET_PROJECT_IDS_EXPERIMENT_ITEMS, (Runnable) this::streamExperimentItems),
                arguments(GET_EXPERIMENT_ITEMS_STREAM, (Runnable) this::streamExperimentItems),
                // The comparison, whose own target-projects read runs first and feeds both the page and
                // the count of it; its stats and output columns are separate endpoints over the same items.
                arguments(GET_TARGET_PROJECT_IDS_VERSIONS, compare),
                arguments(GET_COMPARISON, compare),
                arguments(COUNT_COMPARISON, compare),
                // The same count again, searching: its second traces resolution renders only then.
                arguments(COUNT_COMPARISON, (Runnable) () -> compare(fixture.datasetId(), fixture.experimentId(),
                        SEARCH_MARKER)),
                arguments(GET_COMPARISON_STATS, (Runnable) () -> datasetResourceClient
                        .getDatasetExperimentItemsStats(fixture.datasetId(), List.of(fixture.experimentId()),
                                API_KEY, WORKSPACE_NAME, everyTrace())),
                arguments(GET_OUTPUT_COLUMNS, (Runnable) () -> datasetResourceClient.getDatasetItemsOutputColumns(
                        fixture.datasetId(), List.of(fixture.experimentId()), API_KEY, WORKSPACE_NAME)),
                // The optimizations list, whose experiment path is the one this change bounds.
                arguments(FIND_OPTIMIZATIONS, (Runnable) () -> optimizationResourceClient.find(API_KEY,
                        WORKSPACE_NAME, 1, 10, fixture.datasetId(), null, null, SC_OK)),
                arguments(GET_OPTIMIZATION_BY_ID, (Runnable) () -> optimizationResourceClient
                        .get(fixture.optimizationId(), API_KEY, WORKSPACE_NAME, SC_OK)),
                // The aggregation job's three reads, all emitted by one pass. The per-batch read of the trace
                // rows derives its bound from the bound id array rather than from a relation, being where its ids
                // come from, and covers a batch holding an id past the DateTime64 ceiling.
                arguments(GET_PROJECT_IDS, (Runnable) this::populateAggregates),
                arguments(GET_TRACE_AGGREGATIONS, (Runnable) this::populateAggregates),
                arguments(GET_TRACES_DATA, (Runnable) this::populateAggregates));
    }

    /**
     * Every site in scope, parameterised because the claim is identical for all of them and only the trigger differs.
     * A site whose bound went missing is a read that opens every weekly partition again, with no row-level symptom —
     * which is precisely what went unnoticed until this ticket.
     *
     * <p>Asserted over the {@code traces} reads the plan exposes, which is not all of them: a set built for an
     * {@code IN (SELECT ... FROM traces)} is resolved before planning and appears in no read node. So this pins that
     * every read the plan does show prunes, and that there is one to show.</p>
     */
    @ParameterizedTest(name = "{0} prunes the partitions its experiments do not reference")
    @MethodSource
    void everyReadPrunes(String queryName, Runnable trigger) {
        trigger.run();

        var actualReads = tracesReads(planOf(statementFor(queryName)));

        assertThat(actualReads).isNotEmpty();
        assertThat(actualReads).allSatisfy(read -> {
            var actualParts = partitionAnalysisOf(read);
            assertThat(actualParts.selected()).isLessThan(actualParts.total());
        });
    }

    private Stream<Arguments> theSitesThePlanCannotShowCarryTheWeekBound() {
        return Stream.of(
                arguments(GET_AGGREGATION_BRANCH_COUNTS, (Runnable) () -> experimentResourceClient
                        .getProjectExperiments(fixture.projectId(), 1, 10, null, null, null, false, null, null,
                                false, API_KEY, WORKSPACE_NAME, SC_OK)),
                arguments(GET_EXPERIMENT_METADATA_BY_ID, (Runnable) this::bulkUploadExperimentItem));
    }

    /**
     * The two sites whose pruning the plan cannot show, and so the ones asserted on statement text. Their
     * {@code traces} access sits inside a subquery ClickHouse resolves before planning — a nested {@code IN} on the
     * branch counts, a scalar in the projection on the metadata read — so it appears in no read node and the plan of
     * the bounded statement is indistinguishable from the unbounded one's. What is left to assert is that the bound
     * is rendered at all, which the other cases get for free from pruning.
     */
    @ParameterizedTest(name = "{0} carries the week bound its plan cannot show")
    @MethodSource
    void theSitesThePlanCannotShowCarryTheWeekBound(String queryName, Runnable trigger) {
        trigger.run();

        assertThat(statementFor(queryName)).contains(WEEK_BOUND_MARKER);
    }

    private Stream<Arguments> everyReadCountsEveryTraceItsItemsReference() {
        return Stream.of(
                // The list read, which resolves the count through find_experiments and its target-projects read.
                arguments("experiment list", (Supplier<Experiment>) () -> experimentResourceClient
                        .findExperiments(1, 10, fixture.experimentName(), API_KEY, WORKSPACE_NAME)
                        .content().getFirst()),
                // The same statement rendered with the id set rather than the page filters.
                arguments("experiment by id", (Supplier<Experiment>) () -> experimentResourceClient
                        .getExperiment(fixture.experimentId(), API_KEY, WORKSPACE_NAME)),
                // The aggregation job's own answer, which every later read of that experiment is then served from,
                // so a trace it lost would disappear from all of them.
                arguments("aggregated experiment", (Supplier<Experiment>) () -> {
                    populateAggregates();
                    return experimentResourceClient.getExperiment(fixture.experimentId(), API_KEY, WORKSPACE_NAME);
                }));
    }

    /**
     * The headline row claim: an experiment's trace count covers every trace its items reference, including ids that
     * are far-future and past the {@code DateTime64} ceiling. Asserted of each read that reports it, because each
     * renders the bound from a different statement and a wrong week in any of them reports fewer traces.
     */
    @ParameterizedTest(name = "{0} counts every trace its items reference")
    @MethodSource
    void everyReadCountsEveryTraceItsItemsReference(String read, Supplier<Experiment> experiment) {
        assertThat(experiment.get().traceCount()).isEqualTo(fixture.traces().size());
    }

    /**
     * The experiment-items stream, compared as whole items rather than field by field: each carries the input and
     * output of the trace it names, so a trace the bound dropped shows up as a present item with those fields empty,
     * which only a comparison against the expected item catches.
     */
    @Test
    void experimentItemStreamReturnsEveryItemWithItsTraceResolved() {
        var expectedItems = fixture.experimentItems();

        var actualItems = experimentResourceClient.streamExperimentItems(
                ExperimentItemStreamRequest.builder().experimentName(fixture.experimentName()).build(),
                API_KEY, WORKSPACE_NAME);

        assertItems(actualItems, expectedItems);
    }

    private Stream<Arguments> comparisonResolvesTheTraceOfEveryExperimentItem() {
        return Stream.of(
                arguments("unsearched", null),
                // Searching is what renders the count's second traces resolution; every trace's input carries the
                // marker, so the search matches all of them and the page is the same one the unsearched shape returns.
                arguments("searched", SEARCH_MARKER));
    }

    /**
     * The dataset comparison, compared as whole experiment items for the reason the stream case gives: a trace the
     * bound dropped surfaces as a present item with its trace fields empty, which a count alone would not catch. The
     * count is asserted alongside because it is a statement of its own, and the searched shape is the only request in
     * either suite that renders its second {@code traces} resolution.
     */
    @ParameterizedTest(name = "the {0} comparison resolves the trace of every experiment item")
    @MethodSource
    void comparisonResolvesTheTraceOfEveryExperimentItem(String shape, String search) {
        // The comparison projects a different set of trace fields than the stream does: it carries the trace's
        // metadata. Both resolve the trace, which is what is being asserted.
        var expectedItems = IntStream.range(0, fixture.experimentItems().size())
                .mapToObj(index -> fixture.experimentItems().get(index).toBuilder()
                        .traceMetadata(fixture.traces().get(index).metadata())
                        .build())
                .toList();

        var actualPage = compare(fixture.datasetId(), fixture.experimentId(), search);

        assertThat(actualPage.total()).isEqualTo(fixture.experimentItems().size());
        assertItems(experimentItemsOf(actualPage), expectedItems);
    }

    /**
     * The columns the comparison offers, read from the traces' own {@code output_keys}. Each trace contributes a key
     * only it has, so a trace the bound dropped leaves a column missing — which a row count would not show.
     */
    @Test
    void outputColumnsNameEveryTraceTheExperimentReferences() {
        var expectedColumns = fixture.traces().stream().map(trace -> outputKeyOf(trace.id())).toList();

        var actualColumns = datasetResourceClient.getDatasetItemsOutputColumns(fixture.datasetId(),
                List.of(fixture.experimentId()), API_KEY, WORKSPACE_NAME);

        assertThat(actualColumns.columns()).extracting(Column::name).containsAll(expectedColumns);
    }

    /** The stats over the same comparison, which aggregate over the traces rather than listing them. */
    @Test
    void datasetItemStatsCoverEveryTrace() {
        var actualStats = datasetResourceClient.getDatasetExperimentItemsStats(fixture.datasetId(),
                List.of(fixture.experimentId()), API_KEY, WORKSPACE_NAME, null);

        assertThat(actualStats.stats()).anySatisfy(actualStat -> {
            assertThat(actualStat.getName()).isEqualTo("trace_count");
            assertThat(((Number) actualStat.getValue()).intValue()).isEqualTo(fixture.traces().size());
        });
    }

    /**
     * One project, one dataset, one experiment belonging to an optimization, and the traces its items reference — one
     * per era the bound has to derive correctly, the last of them a millisecond short of a week boundary. The traces seeded into other weeks are what gives the pruning
     * assertion something to prune.
     */
    private Fixture seed() {
        var project = createProject();
        IntStream.rangeClosed(1, UNREFERENCED_WEEKS)
                .forEach(week -> createTrace(project, weekInstant(-week)));

        var traces = List.of(
                createTrace(project, weekInstant(0)),
                createTrace(project, farFutureIdAt),
                createTrace(project, pastCeilingIdAt),
                createTrace(project, weekBoundaryIdAt));

        var dataset = createDataset();
        var datasetItems = createDatasetItems(dataset, traces.size());
        var optimizationId = optimizationResourceClient.create(optimizationResourceClient.createPartialOptimization()
                .datasetName(dataset.name())
                .build(), API_KEY, WORKSPACE_NAME);
        var experiment = createExperiment(dataset, optimizationId);

        return Fixture.builder()
                .projectId(project.id())
                .projectName(project.name())
                .traces(traces)
                .datasetName(dataset.name())
                .datasetId(dataset.id())
                .experimentId(experiment.id())
                .experimentName(experiment.name())
                .experimentItems(createExperimentItems(experiment.id(), datasetItems, traces))
                .optimizationId(optimizationId)
                // The bulk trigger writes an item, so it gets an experiment of its own: pointed at the fixture's it
                // would change what every row case above reads, depending on the order the cases ran in. It has to
                // exist already — the metadata read is what resolves an existing experiment's project.
                .bulkExperiment(createExperiment(dataset, null))
                .build();
    }

    /**
     * Experiment items pairing each dataset item with the trace at the same position, returned carrying the trace
     * fields the read paths resolve, so they are the expected objects the row cases compare against.
     */
    private List<ExperimentItem> createExperimentItems(UUID experimentId, List<DatasetItem> datasetItems,
            List<Trace> traces) {
        var items = IntStream.range(0, traces.size())
                .mapToObj(index -> factory.manufacturePojo(ExperimentItem.class).toBuilder()
                        .id(ID_GENERATOR.generateId())
                        .experimentId(experimentId)
                        .datasetItemId(datasetItems.get(index).id())
                        .traceId(traces.get(index).id())
                        .feedbackScores(null)
                        .comments(null)
                        .totalEstimatedCost(null)
                        .usage(null)
                        .build())
                .toList();

        experimentResourceClient.createExperimentItem(Set.copyOf(items), API_KEY, WORKSPACE_NAME);

        // What a read path returns: the item as written, plus the fields it resolves from the trace it names, plus
        // the project the server fills in from that trace. Building the expectation here rather than at each
        // assertion keeps the row cases comparing whole objects.
        return IntStream.range(0, items.size())
                .mapToObj(index -> items.get(index).toBuilder()
                        .input(traces.get(index).input())
                        .output(traces.get(index).output())
                        .createdBy(USER)
                        .lastUpdatedBy(USER)
                        .build())
                .toList();
    }

    private Experiment createExperiment(Dataset dataset, UUID optimizationId) {
        var experiment = experimentResourceClient.createPartialExperiment()
                .datasetName(dataset.name())
                .optimizationId(optimizationId)
                .build();
        return experiment.toBuilder()
                .id(experimentResourceClient.create(experiment, API_KEY, WORKSPACE_NAME))
                .build();
    }

    private Dataset createDataset() {
        var dataset = DatasetResourceClient.buildDataset(factory).toBuilder()
                .name("dataset-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(32)))
                .build();
        return dataset.toBuilder().id(datasetResourceClient.createDataset(dataset, API_KEY, WORKSPACE_NAME)).build();
    }

    private List<DatasetItem> createDatasetItems(Dataset dataset, int count) {
        var items = IntStream.range(0, count)
                .mapToObj(ignored -> factory.manufacturePojo(DatasetItem.class).toBuilder()
                        .id(ID_GENERATOR.generateId())
                        .source(DatasetItemSource.MANUAL)
                        .traceId(null)
                        .spanId(null)
                        .experimentItems(null)
                        .build())
                .toList();

        datasetResourceClient.createDatasetItems(DatasetItemBatch.builder()
                .datasetId(dataset.id())
                .items(items)
                .build(), WORKSPACE_NAME, API_KEY);
        return items;
    }

    private Project createProject() {
        var project = factory.manufacturePojo(Project.class);
        return project.toBuilder()
                .id(projectResourceClient.createProject(project, API_KEY, WORKSPACE_NAME))
                .build();
    }

    /**
     * A trace through the real ingestion path whose {@code id} places it in {@code idAt}'s week — the id is what the
     * week bound reads. {@code startTime} stays present-day: it is a separate column with its own range validation.
     */
    private Trace createTrace(Project project, Instant idAt) {
        var id = ID_GENERATOR.getTimeOrderedEpoch(idAt.toEpochMilli());
        var trace = factory.manufacturePojo(Trace.class).toBuilder()
                .id(id)
                .projectId(project.id())
                .projectName(project.name())
                .startTime(Instant.now().truncatedTo(ChronoUnit.MILLIS))
                // Input carries the marker every search matches; output carries a key only this trace has, so the
                // column set names each trace exactly once and a dropped one is a missing column.
                .input(JsonUtils.getJsonNodeFromString("{\"%s\": \"%s\"}".formatted(SEARCH_MARKER, id)))
                .output(JsonUtils.getJsonNodeFromString("{\"%s\": \"%s\"}".formatted(outputKeyOf(id), id)))
                .endTime(null)
                .guardrailsValidations(null)
                .threadId(null)
                .feedbackScores(null)
                .usage(null)
                .build();
        traceResourceClient.batchCreateTraces(List.of(trace), API_KEY, WORKSPACE_NAME);
        return trace;
    }

    /** The output column a trace contributes, unique to it, so the column set counts the traces that resolved. */
    private String outputKeyOf(UUID traceId) {
        return "output_%s".formatted(traceId.toString().replace("-", ""));
    }

    /** Mid-week, so an assertion that depends on the week exercises the map back to Monday rather than identity. */
    private Instant weekInstant(int weekOffset) {
        return anchorMonday.plusWeeks(weekOffset).plusDays(2).atTime(12, 0).toInstant(ZoneOffset.UTC);
    }

    /**
     * A filter no trace output can carry, so it turns on the comparison reads that are guarded behind an
     * experiment-item filter without narrowing what the request returns.
     */
    private List<ExperimentsComparisonFilter> everyTrace() {
        return List.of(ExperimentsComparisonFilter.builder()
                .field(ExperimentsComparisonValidKnownField.OUTPUT.getQueryParamField())
                .operator(Operator.NOT_CONTAINS)
                .value(RandomStringUtils.secure().nextAlphanumeric(32))
                .build());
    }

    /**
     * The comparison, optionally searching. A search term is what renders the count's second {@code traces}
     * resolution, which no other request in either suite reaches.
     */
    private DatasetItem.DatasetItemPage compare(UUID datasetId, UUID experimentId, String search) {
        return datasetResourceClient.getDatasetItemsWithExperimentItems(datasetId, List.of(experimentId), search,
                everyTrace(), API_KEY, WORKSPACE_NAME);
    }

    /** The experiment items a comparison page carries, which is the projection the row cases compare. */
    private List<ExperimentItem> experimentItemsOf(DatasetItem.DatasetItemPage page) {
        return page.content().stream()
                .flatMap(item -> item.experimentItems().stream())
                .toList();
    }

    /**
     * Whole-object comparison of the experiment items a read path returned. {@code executionPolicy},
     * {@code duration} and {@code projectId} are the fields the server derives rather than stores — the first two per
     * read path, {@code projectId} backfilled from the trace by a job — so none of them carries information about
     * whether the trace resolved, which is what these cases are about.
     */
    private void assertItems(List<ExperimentItem> actualItems, List<ExperimentItem> expectedItems) {
        assertExperimentResultsIgnoringFields(sortedById(actualItems), sortedById(expectedItems),
                ignoredFieldsPlus("executionPolicy", "duration", "projectId"));
    }

    private String[] ignoredFieldsPlus(String... extraFields) {
        return Stream.concat(Arrays.stream(EXPERIMENT_ITEMS_IGNORED_FIELDS), Arrays.stream(extraFields))
                .toArray(String[]::new);
    }

    private List<ExperimentItem> sortedById(List<ExperimentItem> items) {
        return items.stream().sorted(Comparator.comparing(ExperimentItem::id)).toList();
    }

    private void streamExperimentItems() {
        experimentResourceClient.streamExperimentItems(
                ExperimentItemStreamRequest.builder().experimentName(fixture.experimentName()).build(),
                API_KEY, WORKSPACE_NAME);
    }

    /**
     * A one-record bulk upload naming an experiment that already exists, by id: the ingestion resolves an existing
     * experiment's project through {@code traces} only when the request carries the id, and skips the read otherwise.
     */
    private void bulkUploadExperimentItem() {
        experimentResourceClient.bulkUploadExperimentItem(ExperimentItemBulkUpload.builder()
                .experimentId(fixture.bulkExperiment().id())
                .experimentName(fixture.bulkExperiment().name())
                .datasetName(fixture.datasetName())
                .items(List.of(ExperimentItemBulkRecord.builder()
                        .datasetItemId(ID_GENERATOR.generateId())
                        .trace(factory.manufacturePojo(Trace.class).toBuilder()
                                .id(ID_GENERATOR.generateId())
                                .projectName(null)
                                .startTime(Instant.now().truncatedTo(ChronoUnit.MILLIS))
                                .endTime(null)
                                .guardrailsValidations(null)
                                .threadId(null)
                                .feedbackScores(null)
                                .usage(null)
                                .build())
                        .build()))
                .build(), API_KEY, WORKSPACE_NAME);
    }

    /**
     * The batch update, whose pre-update read of the experiments is the one place {@code FIND} renders under
     * {@code get_experiments_by_ids}. Pointed at the experiment the bulk trigger owns, since it writes: the fixture's
     * own would change what the row cases read.
     */
    private void batchUpdateExperiment() {
        experimentResourceClient.batchUpdate(ExperimentBatchUpdate.builder()
                .ids(Set.of(fixture.bulkExperiment().id()))
                .update(ExperimentUpdate.builder()
                        .tagsToAdd(Set.of(RandomStringUtils.secure().nextAlphanumeric(10)))
                        .build())
                .mergeTags(true)
                .build(), API_KEY, WORKSPACE_NAME);
    }

    /** Materialises the experiment's aggregate, which is what runs the job's {@code traces} reads. */
    private void populateAggregates() {
        experimentAggregatesService.populateAggregations(fixture.experimentId())
                .contextWrite(context -> context
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID))
                .block();
    }

    /**
     * The statement ClickHouse received for {@code queryName}, polled rather than read once: the {@code query_log} row
     * is queued asynchronously after the result reaches the client, and this container's flush interval is 200 ms.
     * <p>
     * Two {@code log_comment} shapes, because the DAOs stamp two: most go through {@code getSTWithLogComment}, which
     * renders {@code name:workspace:user:details}, while the target-projects reads set the bare name.
     */
    private String statementFor(String queryName) {
        return Awaitility.await()
                .alias("query_log holds a %s statement".formatted(queryName))
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> queryOneString(LAST_STATEMENT_FOR, statement -> statement.bind("op", queryName)),
                        Objects::nonNull);
    }

    private JsonNode planOf(String statement) {
        var explain = EXPLAIN_PLAN.formatted(statement.strip().replaceAll(";$", ""));

        var rows = template.stream(connection -> Flux.from(connection.createStatement(explain).execute())
                .flatMap(result -> result.map((row, ignored) -> row.get("explain", String.class))))
                .collectList().block();

        return JsonUtils.getJsonNodeFromString(String.join("\n", rows));
    }

    /** Every {@code traces} read the plan performs, as the nodes that describe them. */
    private List<JsonNode> tracesReads(JsonNode plan) {
        return plan.findParents("Description").stream()
                .filter(node -> TRACES_TABLE.equals(node.path("Description").asText()))
                .toList();
    }

    /**
     * How many parts one read's partition analysis started with and how many it ended up selecting. Reduced over the
     * entries rather than read off one of them, because they narrow in sequence: the widest start and the narrowest
     * end are the two ends of that sequence whichever entry carried the bound.
     */
    private PrunedParts partitionAnalysisOf(JsonNode read) {
        var analysis = StreamSupport.stream(read.path("Indexes").spliterator(), false)
                .filter(index -> PARTITION_ANALYSIS.contains(index.path("Type").asText()))
                .toList();

        return PrunedParts.builder()
                .total(analysis.stream().mapToInt(index -> index.path("Initial Parts").asInt()).max().orElse(0))
                .selected(analysis.stream().mapToInt(index -> index.path("Selected Parts").asInt()).min().orElse(0))
                .build();
    }

    /**
     * Setup, not a test: puts the partitioned successor under the name these DAOs read from, so the pruning assertion
     * is made against a table that has weekly partitions at all. Idempotent — it returns early once the estate
     * provides that state on its own, which is what the cutover migration will do.
     */
    private void ensurePartitionedSuccessorUnderTraces() {
        if (partitionKeyOf("traces").contains("id_at")) {
            return;
        }
        assertThat(tableExists("traces_local_v2"))
                .as(NO_PARTITIONED_TABLE.formatted(partitionKeyOf("traces")))
                .isTrue();
        execute("EXCHANGE TABLES traces AND traces_local_v2 ON CLUSTER '{cluster}'");
        execute("RENAME TABLE traces_local_v2 TO traces_pre_cutover_backup ON CLUSTER '{cluster}'");
    }

    private boolean tableExists(String table) {
        return "1".equals(queryOneString(TABLE_COUNT, statement -> statement.bind("table", table)));
    }

    /** The table's partition-key expression, or {@code ""} when there is no such table — never {@code null}. */
    private String partitionKeyOf(String table) {
        return Optional
                .ofNullable(queryOneString(PARTITION_KEY_OF_TABLE, statement -> statement.bind("table", table)))
                .orElse("");
    }

    private void execute(String sql) {
        template.nonTransaction(connection -> Mono.from(connection.createStatement(sql).execute())
                .flatMap(result -> Mono.from(result.getRowsUpdated()))).block();
    }

    private String queryOneString(String sql, Consumer<Statement> binder) {
        return template.nonTransaction(connection -> {
            var statement = connection.createStatement(sql);
            binder.accept(statement);
            return Mono.from(statement.execute())
                    .flatMap(result -> Mono.from(result.map((row, ignored) -> row.get(0, String.class))));
        }).block();
    }

    @Builder(toBuilder = true)
    private record Fixture(UUID projectId, String projectName, List<Trace> traces,
            UUID datasetId, String datasetName, UUID experimentId, String experimentName,
            List<ExperimentItem> experimentItems, UUID optimizationId, Experiment bulkExperiment) {
    }

    @Builder(toBuilder = true)
    private record PrunedParts(int selected, int total) {
    }
}
