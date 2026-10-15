package com.comet.opik.infrastructure;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.Optimization;
import com.comet.opik.api.Project;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
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
import com.comet.opik.api.resources.utils.resources.OptimizationResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.redis.testcontainers.RedisContainer;
import io.r2dbc.spi.Statement;
import lombok.Builder;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.apache.http.HttpStatus;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
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

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bounded tagged-cost scan of {@code OptimizationDAO} (OPIK-8333), against the estate it is written for: a
 * weekly-partitioned {@code traces}.
 *
 * <p>The tagged half of an optimization's total used to read {@code traces} twice, with nothing on either read that
 * could prune. It is now one read, bounded by the projects of the optimizations in scope and by the week floor their
 * own UUIDv7 ids resolve to. Two claims follow, both driven through the endpoints a client calls:
 *
 * <ul>
 *   <li><b>Which traces the floor admits.</b> The floor is a week <em>below</em> the run's own, so a trace from the
 *   week before the run still counts; one from two weeks before does not; and a far-future trace counts, which is the
 *   direction a narrow {@code toMonday} would have wrapped into the past and dropped (OPIK-8241). The fifth trace sits
 *   outside the run's project and is the one case the floor has no say in: it pins the equivalence the project bound
 *   rests on, since a tagged trace outside those projects has no span inside them and the spans read already left it
 *   out. Every trace is priced by podam, so the total identifies which ones were attributed rather than how many.
 *   Read through {@code getById}, which always runs {@code FIND}, and through the list, which with no experiment in
 *   scope runs {@code FIND_WITHOUT_EXPERIMENTS}: the two carry their own copy of this pipeline, so the run page is the
 *   oracle the list has to match.</li>
 *   <li><b>That the read is one read and that it prunes.</b> Neither has a row-level symptom — a query that went
 *   back to two scans, or lost its bound, returns the same numbers and merely opens every partition again, which is
 *   the whole regression this change exists to prevent. Both are taken from the statement ClickHouse actually
 *   received: pruning off its plan, which names the table each read opens and the parts its partition analysis
 *   selected, and the read count off its text, for the reason {@link #TRACES_READ} gives.</li>
 * </ul>
 *
 * <p>The unbounded side is the other suite's. The week bound is emitted only where {@code traceColumnsNonNullable}
 * says {@code traces} is the partitioned successor, because on the legacy table it has no partitions to prune and a
 * far-future id is filed under a wrapped past week the floor would exclude. {@code OptimizationsResourceTest} runs
 * these endpoints on that estate, and pins that a tagged trace older than the run still counts there.
 *
 * <p><b>Topology is setup, never the subject.</b> After the migrations the live {@code traces} is still the legacy
 * table and the successor exists only as the empty {@code traces_local_v2}, so
 * {@link #ensurePartitionedSuccessorUnderTraces()} runs the EXCHANGE block of the cutover before the app boots —
 * idempotent, so it becomes a no-op once the cutover migration lands. The {@code Distributed} wrap is deliberately
 * not applied: the pruning has to hold without it, and {@code EXPLAIN} through a {@code Distributed} table reports
 * remote reads rather than the part selection asserted here. {@code TracesLocalV2CutoverTest} owns the cutover itself.
 *
 * <p>A dedicated, non-reused ClickHouse, because the EXCHANGE destructively renames the live {@code traces}; it
 * carries {@code clickhouse-fast-log-flush.xml} so the statement reads can poll {@code system.query_log} instead of
 * forcing a server-wide flush that races the rows it is meant to reveal.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class OptimizationsTaggedCostWeekBoundTest {

    private static final String API_KEY = "apiKey-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(32));
    private static final String WORKSPACE_NAME = "workspace-%s"
            .formatted(RandomStringUtils.secure().nextAlphanumeric(32));
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(32));

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    /** The {@code log_comment} query names the DAO stamps, by which each statement is read back. */
    private static final String FIND_WITHOUT_EXPERIMENTS = "find_optimizations_without_experiments";
    private static final String GET_BY_ID = "get_optimization_by_id";

    /**
     * Every {@code traces} read in a statement, and the one claim of this suite that has to be asserted on SQL text.
     * <p>
     * {@code EXPLAIN} cannot carry it: the second read the collapse removed was an {@code IN (SELECT ... FROM traces)}
     * whose set ClickHouse builds eagerly, so it appears in no {@code ReadFromMergeTree} node and the plan of the old
     * two-scan form is indistinguishable from this one's — measured, not assumed. Nor can rows: both forms return the
     * same numbers and differ only in how many parts they open, which is the whole regression. So the statement text
     * is what is left, and {@code FIND_WITHOUT_EXPERIMENTS} is where it says the most, reaching {@code traces} nowhere
     * but this scan.
     */
    private static final Pattern TRACES_READ = Pattern.compile("FROM\\s+traces\\b");

    /**
     * The same claim for {@code FIND}, where a count would say less: it reads {@code traces} for its experiment CTEs
     * too, a separate slice (OPIK-8368), so the name of the CTE the collapse removed pins what this change owns
     * without pinning what it does not.
     */
    private static final String REMOVED_CANDIDATE_CTE = "optimization_tagged_trace_ids";

    /** How {@code EXPLAIN} names a {@code traces} read in the plan. */
    private static final String TRACES_TABLE = "%s.traces".formatted(ClickHouseContainerUtils.DATABASE_NAME);

    /**
     * The index entries that decide which <em>parts</em> a read opens, as opposed to which granules. ClickHouse
     * applies them in this order, each narrowing the previous one's selection, and which of the two carries a week
     * bound is a planner decision rather than a property of the query — so both are read and reduced together.
     */
    private static final Set<String> PARTITION_ANALYSIS = Set.of("MinMax", "Partition");

    /**
     * Costs round-trip through {@code Decimal(38, 12)}, so the scale a response carries is not the scale it was
     * written with and only the value is comparable. Read-only once built, so one instance serves every assertion.
     */
    private static final RecursiveComparisonConfiguration BY_VALUE = RecursiveComparisonConfiguration.builder()
            .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
            .build();

    private static final String LAST_STATEMENT_FOR = """
            SELECT query
            FROM system.query_log
            WHERE log_comment LIKE concat(:op, ':%')
            AND type = 'QueryFinish'
            AND query LIKE concat('%', :needle, '%')
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
     * than rendered through StringTemplate, as {@code TracesLocalV2PartitioningTest} does: the statement is opaque SQL
     * and a {@code \<} anywhere in it would read as a template expression.
     */
    private static final String EXPLAIN_PLAN = "EXPLAIN indexes = 1, json = 1 %s";

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
     * The week the run's own id falls in, and the origin every other week here is named relative to. Historical, so
     * the weeks below it exist without depending on the wall clock, and random within that, since nothing about the
     * bound depends on which week it is.
     */
    private final LocalDate anchorMonday = LocalDate.now(ZoneOffset.UTC)
            .minusWeeks(RandomUtils.secure().randomInt(4, 52))
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

    /**
     * The {@code id_at} a far-future id carries — the shape a broken client clock mints. Past {@code Date}'s 2149
     * ceiling, which is what makes it the case a wrapping week expression would fold into the past, and inside
     * {@code DateTime64}'s, so the successor stores its real week.
     */
    private final Instant farFutureIdAt = LocalDate.of(RandomUtils.secure().randomInt(2150, 2296), 6, 1)
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
     * flag is what tells the DAO {@code traces} is weekly partitioned and so may carry the week bound. Without it
     * every assertion here would pass vacuously against the unbounded form.
     * <p>
     * {@code uuidValidation} is the production default, which {@code config-test.yml} turns on so other suites can
     * cover the validator itself. Off is what lets this suite's backdated and far-future ids reach the table through
     * the ingestion endpoint rather than through an {@code INSERT} that bypasses it — and far-future rows are exactly
     * what the table holds where that switch is off.
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
    private OptimizationResourceClient optimizationResourceClient;
    private ProjectResourceClient projectResourceClient;
    private SpanResourceClient spanResourceClient;
    private TraceResourceClient traceResourceClient;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        this.datasetResourceClient = new DatasetResourceClient(clientSupport, baseUrl);
        this.optimizationResourceClient = new OptimizationResourceClient(clientSupport, baseUrl, factory);
        this.projectResourceClient = new ProjectResourceClient(clientSupport, baseUrl, factory);
        this.spanResourceClient = new SpanResourceClient(clientSupport, baseUrl);
        this.traceResourceClient = new TraceResourceClient(clientSupport, baseUrl);
    }

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
        clickHouseContainer.stop();
        zookeeperContainer.stop();
        network.close();
    }

    @Test
    void taggedCostIncludesTracesFromTheWeekBeforeTheRunOnward() {
        var run = seedRun();

        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    var expectedOptimization = optimizationResourceClient.get(run.optimizationId(), API_KEY,
                            WORKSPACE_NAME, HttpStatus.SC_OK);
                    var actualOptimizations = optimizationResourceClient.find(API_KEY, WORKSPACE_NAME, 1, 10,
                            run.datasetId(), null, null, HttpStatus.SC_OK).content();

                    assertThat(expectedOptimization.totalOptimizationCost())
                            .isEqualByComparingTo(run.expectedTotalCost());
                    assertThat(actualOptimizations)
                            .usingRecursiveFieldByFieldElementComparator(BY_VALUE)
                            .containsExactly(expectedOptimization);
                });
    }

    @Test
    void taggedCostFloorsEveryRunInScopeAtTheEarliestRunsWeek() {
        // One CTE serves every run the page returns, so its floor is the earliest of their weeks and not each run's
        // own. Two runs weeks apart is what tells the two apart: a floor taken from the latest would drop the earlier
        // run's traces from the list while getById, which sees that run alone, still found them - and the list and
        // the run page would report different totals for it.
        var dataset = createDataset();
        var project = createProject();

        var earlierRun = seedOptimization(dataset, project, weekInstant(-6));
        var laterRun = seedOptimization(dataset, project, weekInstant(0));

        var expectedEarlierCost = seedTaggedTrace(project, earlierRun, weekInstant(-6));
        var expectedLaterCost = seedTaggedTrace(project, laterRun, weekInstant(0));

        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    var expectedEarlier = optimizationResourceClient.get(earlierRun, API_KEY, WORKSPACE_NAME,
                            HttpStatus.SC_OK);
                    var expectedLater = optimizationResourceClient.get(laterRun, API_KEY, WORKSPACE_NAME,
                            HttpStatus.SC_OK);
                    var actualOptimizations = optimizationResourceClient.find(API_KEY, WORKSPACE_NAME, 1, 10,
                            dataset.id(), null, null, HttpStatus.SC_OK).content();

                    assertThat(expectedEarlier.totalOptimizationCost()).isEqualByComparingTo(expectedEarlierCost);
                    assertThat(expectedLater.totalOptimizationCost()).isEqualByComparingTo(expectedLaterCost);
                    assertThat(actualOptimizations)
                            .usingRecursiveFieldByFieldElementComparator(BY_VALUE)
                            .containsExactlyInAnyOrder(expectedEarlier, expectedLater);
                });
    }

    @Test
    void taggedScanReadsTracesOnceAndPrunesPartitions() {
        var run = seedRun();

        // The two reads this test is about, awaited on the run being in scope rather than fired blind: what they
        // return is taggedCostIncludesTracesFromTheWeekBeforeTheRunOnward's subject, but a statement that queried an
        // empty scope would carry neither claim below, and the endpoints are what put it in query_log.
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    optimizationResourceClient.get(run.optimizationId(), API_KEY, WORKSPACE_NAME, HttpStatus.SC_OK);
                    var actualOptimizations = optimizationResourceClient.find(API_KEY, WORKSPACE_NAME, 1, 10,
                            run.datasetId(), null, null, HttpStatus.SC_OK).content();

                    assertThat(actualOptimizations).extracting(Optimization::id).containsExactly(run.optimizationId());
                });

        // The list narrows on dataset_id and the detail on the optimization id, so each statement is found by the one
        // value of this fixture that reaches its text.
        var listStatement = statementFor(FIND_WITHOUT_EXPERIMENTS, run.datasetId());
        assertThat(TRACES_READ.matcher(listStatement).results().count()).isEqualTo(1);
        assertThat(statementFor(GET_BY_ID, run.optimizationId())).doesNotContain(REMOVED_CANDIDATE_CTE);

        // Pruning, unlike the read count, the plan does carry: it names the table each read opens and reports the
        // parts that survived its partition analysis.
        assertThat(tracesReads(planOf(listStatement))).singleElement().satisfies(read -> {
            var actualParts = partitionAnalysisOf(read);
            assertThat(actualParts.selected()).isLessThan(actualParts.total());
        });
    }

    /**
     * One optimization with no experiments, and the five tagged traces that decide its total. Each carries one span,
     * priced by podam, and is tagged the way the optimizer SDK tags them: the run id alongside the labels it also
     * stamps, so nothing here depends on which those are.
     */
    private Run seedRun() {
        var dataset = createDataset();
        var optimizerProject = createProject();
        var otherProject = createProject();

        var optimizationId = seedOptimization(dataset, optimizerProject, weekInstant(0));

        var attributedCost = Stream.of(
                seedTaggedTrace(optimizerProject, optimizationId, weekInstant(0)),
                seedTaggedTrace(optimizerProject, optimizationId, weekInstant(-1)),
                seedTaggedTrace(optimizerProject, optimizationId, farFutureIdAt))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Not attributed, each for its own reason: below the floor, and outside the run's projects.
        seedTaggedTrace(optimizerProject, optimizationId, weekInstant(-2));
        seedTaggedTrace(otherProject, optimizationId, weekInstant(0));

        return Run.builder()
                .datasetId(dataset.id())
                .optimizationId(optimizationId)
                .expectedTotalCost(attributedCost)
                .build();
    }

    private Dataset createDataset() {
        var dataset = Dataset.builder()
                .name("dataset-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(32)))
                .build();
        return dataset.toBuilder()
                .id(datasetResourceClient.createDataset(dataset, API_KEY, WORKSPACE_NAME))
                .build();
    }

    /**
     * A run whose id places it in {@code idAt}'s week. The floor derives from that id, not from {@code created_at},
     * so the id is what a test controls to place a run in a week.
     */
    private UUID seedOptimization(Dataset dataset, Project project, Instant idAt) {
        var optimizationId = ID_GENERATOR.generateId(idAt);
        var createdId = optimizationResourceClient.create(optimizationResourceClient.createPartialOptimization()
                .id(optimizationId)
                .datasetId(dataset.id())
                .datasetName(dataset.name())
                .projectName(project.name())
                .build(), API_KEY, WORKSPACE_NAME);

        assertThat(createdId)
                .as("the run keeps the id supplied here, which is the one every week is placed relative to")
                .isEqualTo(optimizationId);

        return optimizationId;
    }

    private Project createProject() {
        var project = factory.manufacturePojo(Project.class);
        return project.toBuilder()
                .id(projectResourceClient.createProject(project, API_KEY, WORKSPACE_NAME))
                .build();
    }

    /**
     * An optimizer-internal trace — tagged with the run id and linked to no experiment item — carrying one priced
     * span, and returns that price. {@code idAt} reaches the table through the trace's own UUIDv7 id, which is what
     * the week bound reads.
     */
    private BigDecimal seedTaggedTrace(Project project, UUID optimizationId, Instant idAt) {
        var generated = factory.manufacturePojo(Trace.class);
        var trace = generated.toBuilder()
                .id(ID_GENERATOR.getTimeOrderedEpoch(idAt.toEpochMilli()))
                .projectId(project.id())
                .projectName(project.name())
                .tags(alsoTagged(generated.tags(), optimizationId))
                .guardrailsValidations(null)
                .threadId(null)
                .feedbackScores(null)
                .usage(null)
                .build();
        traceResourceClient.batchCreateTraces(List.of(trace), API_KEY, WORKSPACE_NAME);

        var span = factory.manufacturePojo(Span.class).toBuilder()
                .projectId(project.id())
                .projectName(project.name())
                .traceId(trace.id())
                .parentSpanId(null)
                .feedbackScores(null)
                .build();
        spanResourceClient.batchCreateSpans(List.of(span), API_KEY, WORKSPACE_NAME);

        return span.totalEstimatedCost();
    }

    private Set<String> alsoTagged(Set<String> tags, UUID optimizationId) {
        var tagged = new HashSet<>(Optional.ofNullable(tags).orElseGet(Set::of));
        tagged.add(optimizationId.toString());
        return tagged;
    }

    /** Mid-week, so an assertion that depends on the week exercises the map back to Monday rather than identity. */
    private Instant weekInstant(int weekOffset) {
        return anchorMonday.plusWeeks(weekOffset).plusDays(2).atTime(12, 0).toInstant(ZoneOffset.UTC);
    }

    /**
     * The statement ClickHouse received for {@code queryName}, polled rather than read once: the {@code query_log} row
     * is queued asynchronously after the result reaches the client, and this container's flush interval is 200 ms.
     * Narrowed by a value of the fixture that appears in the statement's own text, since {@code log_comment} alone is
     * shared by every test here.
     */
    private String statementFor(String queryName, UUID needle) {
        return Awaitility.await()
                .alias("query_log holds a %s statement mentioning %s".formatted(queryName, needle))
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> queryOneString(LAST_STATEMENT_FOR, statement -> statement
                        .bind("op", queryName)
                        .bind("needle", needle.toString())), Objects::nonNull);
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
     * end are the two ends of that sequence whichever entry carried the condition.
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
     * Setup, not a test: puts the partitioned successor under the name the DAO reads from, so the pruning assertion is
     * made against a table that has weekly partitions at all. Idempotent — it returns early once the estate provides
     * that state on its own, which is what the cutover migration will do.
     */
    private void ensurePartitionedSuccessorUnderTraces() {
        if (partitionKeyOf("traces").contains("id_at")) {
            return;
        }
        assertThat(tableExists("traces_local_v2"))
                .as("neither a partitioned `traces` (partition key: '%s') nor `traces_local_v2` is present - this "
                        + "suite needs one of those states to install the successor from", partitionKeyOf("traces"))
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

    /** Built through the builder, not positionally: both ids are {@code UUID} and swapping them would compile. */
    @Builder(toBuilder = true)
    private record Run(UUID datasetId, UUID optimizationId, BigDecimal expectedTotalCost) {
    }

    @Builder(toBuilder = true)
    private record PrunedParts(int selected, int total) {
    }
}
