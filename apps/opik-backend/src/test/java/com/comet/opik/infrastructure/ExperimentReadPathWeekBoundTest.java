package com.comet.opik.infrastructure;

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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.resources.ExperimentTestAssertions.EXPERIMENT_ITEMS_IGNORED_FIELDS;
import static com.comet.opik.api.resources.utils.resources.ExperimentTestAssertions.assertExperimentResultsIgnoringFields;
import static org.apache.http.HttpStatus.SC_OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * The experiment read paths of OPIK-8343 on the estate the migrations produce: the legacy, unpartitioned
 * {@code traces}. Here the week bound is deliberately <b>not</b> emitted, and this suite is what says so.
 *
 * <p>Emitting it would be wrong rather than merely useless. That {@code id_at} is a 32-bit {@code DateTime}, so a
 * far-future id — the kind a broken client clock mints — is filed under a <em>wrapped</em> past week that a set
 * derived from the honest ids does not name, and the trace disappears from the experiment that references it. There
 * are no partitions to prune in exchange. So both halves are asserted: that the statements carry no bound, and that
 * an experiment referencing such a trace still reports it.
 *
 * <p>The partitioned estate, where the bound is emitted and prunes, is {@link ExperimentTracesWeekBoundTest}'s.
 *
 * <p>A dedicated, non-reused ClickHouse carrying {@code clickhouse-fast-log-flush.xml}, so the statement reads can
 * poll {@code system.query_log} instead of forcing a server-wide flush that races the rows it is meant to reveal.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class ExperimentReadPathWeekBoundTest {

    private static final String API_KEY = "apiKey-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(32));
    private static final String WORKSPACE_NAME = "workspace-%s"
            .formatted(RandomStringUtils.secure().nextAlphanumeric(32));
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(32));

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    /**
     * The only marker the statement checks look for. The week expression is the sole use of {@code toDayOfWeek} in
     * these statements, so its absence is exactly "no week bound rendered" without pinning how one would be spelled.
     */
    private static final String WEEK_BOUND_MARKER = "toDayOfWeek";

    private static final String LAST_STATEMENT_FOR = """
            SELECT query
            FROM system.query_log
            WHERE (log_comment = :op OR log_comment LIKE concat(:op, ':%'))
            AND type = 'QueryFinish'
            ORDER BY event_time_microseconds DESC
            LIMIT 1
            """;

    /** See the file itself for why the faster flush interval is opt-in rather than part of the shared config. */
    private static final String FAST_LOG_FLUSH_CONFIG = "clickhouse-fast-log-flush.xml";

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
     * The {@code id_at} a far-future id carries. Past {@code Date}'s 2149 ceiling, which is what makes the legacy
     * column wrap it into a plausible recent week rather than storing the real one.
     */
    private final Instant farFutureIdAt = LocalDate.of(RandomUtils.secure().randomInt(2150, 2296), 6, 1)
            .atStartOfDay()
            .toInstant(ZoneOffset.UTC);

    private final TransactionTemplateAsync template;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redisContainer, mysqlContainer, clickHouseContainer, zookeeperContainer).join();
        wireMock = WireMockUtils.startWireMock();
        MigrationUtils.runMysqlDbMigration(mysqlContainer);
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);
        template = TransactionTemplateAsync.create(ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(clickHouseContainer, ClickHouseContainerUtils.DATABASE_NAME)
                .build());
        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(mysqlContainer.getJdbcUrl())
                        .databaseAnalyticsFactory(ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                                clickHouseContainer, ClickHouseContainerUtils.DATABASE_NAME))
                        .redisUrl(redisContainer.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        // traceColumnsNonNullable is left at its default: this suite is the estate where it is off,
                        // which is what says traces is the legacy table and so carries no week bound.
                        //
                        // uuidValidation off is what lets a far-future id reach the table through the ingestion
                        // endpoint rather than through an INSERT that bypasses it.
                        .customConfigs(List.of(new CustomConfig("uuidValidation.enabled", "false")))
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
     * {@code DatasetVersionResourceTest} take, and at the service rather than the DAO, so the {@code traces} reads it
     * performs are reached the way production reaches them.
     */
    private ExperimentAggregatesService experimentAggregatesService;

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
        exerciseReadPaths();
    }

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
        clickHouseContainer.stop();
        zookeeperContainer.stop();
        network.close();
    }

    /**
     * The experiment list, which runs the four statements of the list page, and its trace count is what a lost
     * far-future trace would show up in.
     */
    private Stream<Arguments> everyReadCountsAFarFutureTrace() {
        return Stream.of(
                // The list read, whose trace count is what a lost far-future trace would show up in.
                arguments("experiment list", (Supplier<Experiment>) () -> experimentResourceClient
                        .findExperiments(1, 10, fixture.experimentName(), API_KEY, WORKSPACE_NAME)
                        .content().getFirst()),
                // The aggregation job's answer, materialised by exerciseReadPaths and served to every later read, so
                // a trace it lost would disappear from all of them.
                arguments("aggregated experiment", (Supplier<Experiment>) () -> experimentResourceClient
                        .getExperiment(fixture.experimentId(), API_KEY, WORKSPACE_NAME)));
    }

    @ParameterizedTest(name = "{0} counts a far-future trace")
    @MethodSource
    void everyReadCountsAFarFutureTrace(String read, Supplier<Experiment> experiment) {
        assertThat(experiment.get().traceCount()).isEqualTo(fixture.traces().size());
    }

    /**
     * The comparison view and the item stream, whose rows carry the trace's own input: a dropped trace shows there as
     * a present row with empty content rather than as a missing one.
     */
    @Test
    void datasetItemComparisonResolvesAFarFutureTrace() {
        // The comparison projects a different set of trace fields than the stream does: it carries the trace's
        // metadata. Both resolve the trace, which is what is being asserted.
        var expectedItems = IntStream.range(0, fixture.experimentItems().size())
                .mapToObj(index -> fixture.experimentItems().get(index).toBuilder()
                        .traceMetadata(fixture.traces().get(index).metadata())
                        .build())
                .toList();

        var actualItems = datasetResourceClient
                .getDatasetItemsWithExperimentItems(fixture.datasetId(), List.of(fixture.experimentId()), API_KEY,
                        WORKSPACE_NAME)
                .content().stream()
                .flatMap(item -> item.experimentItems().stream())
                .toList();

        assertItems(actualItems, expectedItems);
    }

    @Test
    void experimentItemStreamResolvesAFarFutureTrace() {
        var expectedItems = fixture.experimentItems();

        var actualItems = experimentResourceClient.streamExperimentItems(
                ExperimentItemStreamRequest.builder().experimentName(fixture.experimentName()).build(),
                API_KEY, WORKSPACE_NAME);

        assertItems(actualItems, expectedItems);
    }

    /**
     * Why the rows above hold: on this estate the statements carry no week bound at all. Asserted per statement
     * rather than once, since the flag is read per DAO and per template — a site wired to a template that skipped it
     * would emit the bound on its own.
     *
     * <p>The triggers run once in {@link #beforeAll}, and each of them scopes the request so that the {@code traces}
     * access these statements guard behind a filter is rendered: without that, a statement with no {@code traces}
     * access at all would satisfy this vacuously.</p>
     */
    @ParameterizedTest(name = "{0} carries no week bound on the legacy table")
    @ValueSource(strings = {"find_experiments", "count_experiments", "get_experiment_by_id",
            "get_experiments_by_ids", "get_experiments_stream", "get_experiment_metadata_by_id",
            "get_target_project_ids_for_experiments", "find_experiment_groups",
            "find_experiment_groups_aggregations", "get_aggregation_branch_counts",
            "get_target_project_ids_experiment_items", "get_experiment_items_stream",
            "get_target_project_ids", "get_dataset_item_versions_with_experiment_items",
            "count_dataset_item_versions_with_experiment_items",
            "get_dataset_item_versions_with_experiment_items_stats", "get_experiment_items_output_columns",
            "find_optimizations", "get_optimization_by_id", "getProjectIds", "getTraceAggregations",
            "getTracesData"})
    void noStatementCarriesTheWeekBound(String queryName) {
        assertThat(statementFor(queryName)).doesNotContain(WEEK_BOUND_MARKER);
    }

    /**
     * Runs every read path in scope once, project-scoped and filtered where that is what renders the {@code traces}
     * access, so {@link #noStatementCarriesTheWeekBound} has a statement of each to read back.
     */
    private void exerciseReadPaths() {
        var groupByDataset = List.of(GroupBy.builder().field("dataset_id").type(FieldType.STRING).build());
        // Matches every trace, so it turns on the accesses the comparison guards behind an experiment-item filter
        // without narrowing what the request reads.
        var everyTrace = List.of(ExperimentsComparisonFilter.builder()
                .field(ExperimentsComparisonValidKnownField.OUTPUT.getQueryParamField())
                .operator(Operator.NOT_CONTAINS)
                .value(RandomStringUtils.secure().nextAlphanumeric(32))
                .build());

        experimentResourceClient.getProjectExperiments(fixture.projectId(), 1, 10, null, null, null, false, null,
                null, false, API_KEY, WORKSPACE_NAME, SC_OK);
        experimentResourceClient.getExperiment(fixture.experimentId(), API_KEY, WORKSPACE_NAME);
        experimentResourceClient.streamExperiments(
                ExperimentStreamRequest.builder().name(fixture.experimentName()).build(), API_KEY, WORKSPACE_NAME);
        batchUpdateExperiment();
        experimentResourceClient.findGroups(groupByDataset, null, null, null, fixture.projectId(), API_KEY,
                WORKSPACE_NAME, SC_OK);
        experimentResourceClient.findGroupsAggregations(groupByDataset, null, null, null, fixture.projectId(),
                API_KEY, WORKSPACE_NAME, SC_OK);
        streamExperimentItems();
        datasetResourceClient.getDatasetItemsWithExperimentItems(fixture.datasetId(),
                List.of(fixture.experimentId()), null, everyTrace, API_KEY, WORKSPACE_NAME);
        // Searching renders the count's second traces resolution, which no other request here reaches.
        datasetResourceClient.getDatasetItemsWithExperimentItems(fixture.datasetId(),
                List.of(fixture.experimentId()), RandomStringUtils.secure().nextAlphanumeric(16), everyTrace,
                API_KEY, WORKSPACE_NAME);
        datasetResourceClient.getDatasetItemsOutputColumns(fixture.datasetId(), List.of(fixture.experimentId()),
                API_KEY, WORKSPACE_NAME);
        datasetResourceClient.getDatasetExperimentItemsStats(fixture.datasetId(), List.of(fixture.experimentId()),
                API_KEY, WORKSPACE_NAME, everyTrace);
        optimizationResourceClient.find(API_KEY, WORKSPACE_NAME, 1, 10, fixture.datasetId(), null, null, SC_OK);
        optimizationResourceClient.get(fixture.optimizationId(), API_KEY, WORKSPACE_NAME, SC_OK);
        bulkUploadExperimentItem();
        populateAggregates();
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
     * One project, one dataset, and one experiment belonging to an optimization, whose items reference a present-day
     * and a far-future trace — the era the legacy column files under a wrapped past week.
     */
    private Fixture seed() {
        var project = createProject();
        var traces = List.of(createTrace(project, Instant.now()), createTrace(project, farFutureIdAt));

        var dataset = createDataset();
        var datasetItems = traces.stream().map(ignored -> datasetItem()).toList();
        datasetResourceClient.createDatasetItems(
                DatasetItemBatch.builder().datasetId(dataset.id()).items(datasetItems).build(),
                WORKSPACE_NAME, API_KEY);

        var optimizationId = optimizationResourceClient.create(optimizationResourceClient.createPartialOptimization()
                .datasetName(dataset.name())
                .build(), API_KEY, WORKSPACE_NAME);
        var experiment = createExperiment(dataset, optimizationId);

        return Fixture.builder()
                .projectId(project.id())
                .traces(traces)
                .datasetId(dataset.id())
                .datasetName(dataset.name())
                .experimentId(experiment.id())
                .experimentName(experiment.name())
                .experimentItems(createExperimentItems(experiment.id(), datasetItems, traces))
                .optimizationId(optimizationId)
                // The bulk trigger writes an item, so it gets an experiment of its own, and one that already exists:
                // the metadata read is what resolves an existing experiment's project.
                .bulkExperiment(createExperiment(dataset, null))
                .build();
    }

    private Dataset createDataset() {
        var dataset = DatasetResourceClient.buildDataset(factory).toBuilder()
                .name("dataset-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(32)))
                .build();
        return dataset.toBuilder().id(datasetResourceClient.createDataset(dataset, API_KEY, WORKSPACE_NAME)).build();
    }

    private Project createProject() {
        var project = factory.manufacturePojo(Project.class);
        return project.toBuilder()
                .id(projectResourceClient.createProject(project, API_KEY, WORKSPACE_NAME))
                .build();
    }

    private DatasetItem datasetItem() {
        return factory.manufacturePojo(DatasetItem.class).toBuilder()
                .id(ID_GENERATOR.generateId())
                .source(DatasetItemSource.MANUAL)
                .traceId(null)
                .spanId(null)
                .experimentItems(null)
                .build();
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

    /**
     * Experiment items pairing each dataset item with the trace at the same position, returned carrying the fields a
     * read path resolves from that trace plus the project the server fills in from it — so they are the expected
     * objects the row cases compare against.
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

        return IntStream.range(0, items.size())
                .mapToObj(index -> items.get(index).toBuilder()
                        .input(traces.get(index).input())
                        .output(traces.get(index).output())
                        .createdBy(USER)
                        .lastUpdatedBy(USER)
                        .build())
                .toList();
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
     * Whole-object comparison of the experiment items a read path returned. {@code executionPolicy},
     * {@code duration} and {@code projectId} are the fields the server derives rather than stores — the first two per
     * read path, {@code projectId} backfilled from the trace by a job — so none of them carries information about
     * whether the trace resolved, which is what these cases are about.
     */
    private void assertItems(List<ExperimentItem> actualItems, List<ExperimentItem> expectedItems) {
        assertExperimentResultsIgnoringFields(sortedById(actualItems), sortedById(expectedItems),
                Stream.concat(Arrays.stream(EXPERIMENT_ITEMS_IGNORED_FIELDS),
                        Stream.of("executionPolicy", "duration", "projectId")).toArray(String[]::new));
    }

    private List<ExperimentItem> sortedById(List<ExperimentItem> items) {
        return items.stream().sorted(Comparator.comparing(ExperimentItem::id)).toList();
    }

    /**
     * A trace through the real ingestion path whose {@code id} carries {@code idAt} — the id is what a week bound
     * would read. {@code startTime} stays present-day: it is a separate column with its own range validation.
     */
    private Trace createTrace(Project project, Instant idAt) {
        var trace = factory.manufacturePojo(Trace.class).toBuilder()
                .id(ID_GENERATOR.getTimeOrderedEpoch(idAt.toEpochMilli()))
                .projectId(project.id())
                .projectName(project.name())
                .startTime(Instant.now().truncatedTo(ChronoUnit.MILLIS))
                .endTime(null)
                .guardrailsValidations(null)
                .threadId(null)
                .feedbackScores(null)
                .usage(null)
                .build();
        traceResourceClient.batchCreateTraces(List.of(trace), API_KEY, WORKSPACE_NAME);
        return trace;
    }

    private void streamExperimentItems() {
        experimentResourceClient.streamExperimentItems(
                ExperimentItemStreamRequest.builder().experimentName(fixture.experimentName()).build(),
                API_KEY, WORKSPACE_NAME);
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

    private String queryOneString(String sql, Consumer<Statement> binder) {
        return template.nonTransaction(connection -> {
            var statement = connection.createStatement(sql);
            binder.accept(statement);
            return Mono.from(statement.execute())
                    .flatMap(result -> Mono.from(result.map((row, ignored) -> row.get(0, String.class))));
        }).block();
    }

    @Builder(toBuilder = true)
    private record Fixture(UUID projectId, List<Trace> traces, UUID datasetId, String datasetName,
            UUID experimentId, String experimentName, List<ExperimentItem> experimentItems, UUID optimizationId,
            Experiment bulkExperiment) {
    }
}
