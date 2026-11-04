package com.comet.opik.infrastructure;

import com.comet.opik.api.Comment;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceBatchUpdate;
import com.comet.opik.api.TraceUpdate;
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
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.RedirectResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.resources.utils.traces.TraceAssertions;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.apache.http.HttpStatus;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * The {@code TraceDAO} reads that carry a week bound, observed on the table the bounds are for: the weekly-partitioned
 * successor, swapped in under {@code traces} before the app starts, where {@code id_at} is a {@code DateTime64} and a
 * far-future id is filed under its real week.
 *
 * <p>Two claims, both driven through the API:
 *
 * <ul>
 *   <li><b>The reads prune.</b> The evidence is the {@code partitions} column ClickHouse records in
 *   {@code system.query_log} for the statement each site sent. Each filler week is one part holding a trace of the
 *   tested project and one of a project created after it, so the part's key range runs past every id of the tested
 *   project and the primary key keeps a granule of it for any id lookup there, as it does in a production part
 *   holding many projects. Only partition pruning can keep a read out of those weeks: a bounded read touches its own
 *   week, and an id past the 2300 ceiling, for which no week set is derived, falls back to touching every filler
 *   week. The project existence probe is shown to read only recent weeks first, and every week only when they hold
 *   no trace.</li>
 *   <li><b>No row moves.</b> The write merges find their stored row in every era, so a PATCH, a create after a PATCH
 *   and a batch update leave the trace the client expects, compared in full; and the single-trace lookups find it
 *   too, so its project redirect and comments resolve.</li>
 * </ul>
 *
 * <p>The legacy table, where a far-future id is filed under a wrapped week, is {@link TracesReadPathWeekBoundTest}'s.
 *
 * <p>A dedicated, non-reused ClickHouse, because the EXCHANGE renames the live {@code traces}; it carries
 * {@code clickhouse-fast-log-flush.xml} so the statement reads can poll {@code system.query_log}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TracesReadPathPartitionPruningTest {

    private static final String API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);

    /** One project for the filler and every site's trace, so no read can exclude the filler by project. */
    private static final String PROJECT_NAME = randomName("project");

    /** Created after {@link #PROJECT_NAME}, so its UUIDv7 id, and every key of its traces, sorts after it. */
    private static final String LATER_PROJECT_NAME = randomName("project");

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    /** Where {@code id_at} saturates, so no week set can be derived and the read runs unbounded. */
    private static final Instant PAST_CEILING_ID_AT = LocalDate.of(2300, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);

    private static final LocalDate THIS_MONDAY = LocalDate.now(ZoneOffset.UTC)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

    /** The weeks the existence probe reads first: the previous, the current and the next one. */
    private static final List<LocalDate> RECENT_MONDAYS = List.of(
            THIS_MONDAY.minusWeeks(1), THIS_MONDAY, THIS_MONDAY.plusWeeks(1));

    /** Filler in the two recent weeks a trace can already be in, and in one before them, which it must not read. */
    private static final List<LocalDate> RECENT_FILLER_MONDAYS = List.of(THIS_MONDAY.minusWeeks(1), THIS_MONDAY);
    private static final LocalDate OLD_FILLER_MONDAY = THIS_MONDAY.minusWeeks(RandomUtils.secure().randomInt(2, 200));
    private static final List<String> FILLER_WEEKS = Stream
            .concat(RECENT_FILLER_MONDAYS.stream(), Stream.of(OLD_FILLER_MONDAY))
            .map(TracesReadPathPartitionPruningTest::yyyymmdd)
            .toList();

    /** The {@code traces} partitions the latest statement of an op, mentioning the given id, read. */
    private static final String TRACES_PARTITIONS_READ = """
            SELECT arrayStringConcat(arrayFilter(p -> startsWith(p, :prefix), partitions), ',')
            FROM system.query_log
            WHERE log_comment LIKE concat(:op, ':%')
            AND type = 'QueryFinish'
            AND query LIKE concat('%', :mentioned_id, '%')
            ORDER BY event_time_microseconds DESC
            LIMIT 1
            """;

    /** The query name of the statement {@link #assertNoStatementFor} awaits before it looks for an absent one. */
    private static final String QUERY_LOG_MARKER = "query_log_marker";

    private static final String PARTITION_PREFIX = "%s.traces.".formatted(DATABASE_NAME);

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

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redisContainer, mysqlContainer, clickHouseContainer, zookeeperContainer).join();
        wireMock = WireMockUtils.startWireMock();
        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                clickHouseContainer, DATABASE_NAME);
        MigrationUtils.runMysqlDbMigration(mysqlContainer);
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);
        installPartitionedSuccessorUnderTraces(databaseAnalyticsFactory);
        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(mysqlContainer.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(redisContainer.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        .customConfigs(List.of(
                                // Lets the filler, far-future and past-ceiling ids through ingestion, as in production.
                                new CustomConfig("uuidValidation.enabled", "false"),
                                // The successor's columns are non-nullable, as the cutover flips this with it.
                                new CustomConfig("databaseAnalyticsDataModel.traceColumnsNonNullable", "true")))
                        .build());
    }

    private TraceResourceClient traceResourceClient;
    private ProjectResourceClient projectResourceClient;
    private DatasetResourceClient datasetResourceClient;
    private RedirectResourceClient redirectResourceClient;
    private TransactionTemplateAsync template;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport, TransactionTemplateAsync template) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        this.traceResourceClient = new TraceResourceClient(clientSupport, baseUrl);
        this.projectResourceClient = new ProjectResourceClient(clientSupport, baseUrl, factory);
        this.datasetResourceClient = new DatasetResourceClient(clientSupport, baseUrl);
        this.redirectResourceClient = new RedirectResourceClient(clientSupport);
        this.template = template;
        // In this order, so the later project's id sorts after the tested one's.
        projectResourceClient.createProject(PROJECT_NAME, API_KEY, WORKSPACE_NAME);
        projectResourceClient.createProject(LATER_PROJECT_NAME, API_KEY, WORKSPACE_NAME);
        // One batch, so each filler week is one part holding a trace of each project.
        createTraces(Stream.concat(RECENT_FILLER_MONDAYS.stream(), Stream.of(OLD_FILLER_MONDAY))
                .map(monday -> idAt(monday.atTime(12, 0).toInstant(ZoneOffset.UTC)))
                .flatMap(id -> Stream.of(
                        newTrace(id),
                        newTrace(sameWeekId(id)).toBuilder().projectName(LATER_PROJECT_NAME).build()))
                .toList());
    }

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
        clickHouseContainer.stop();
        zookeeperContainer.stop();
        network.close();
    }

    /** Each trigger reaches its site for a trace created at the given {@code id_at}, and returns the id it names. */
    private Stream<Arguments> sites() {
        // Read at call time: @MethodSource runs before @BeforeAll wires the clients in.
        var getById = onTraceAt(trace -> traceResourceClient.getById(trace.id(), WORKSPACE_NAME, API_KEY));
        var patchStored = onTraceAt(trace -> patch(trace.id(), nameUpdate(trace.projectName())));
        var batchUpdate = onTraceAt(trace -> batchUpdateTags(Set.of(trace.id()), randomTags()));
        return Stream.of(
                // A get-by-id runs the target-projects read first, so one trigger covers both query names.
                arguments("find_traces_by_ids", getById),
                arguments("get_target_project_ids_for_traces", getById),
                // A PATCH of a stored trace probes for it, then updates it.
                arguments("get_partial_trace_by_id", patchStored),
                arguments("update_trace", patchStored),
                // A batch update looks the projects up first, so one trigger covers both query names.
                arguments("get_project_ids_by_trace_ids", batchUpdate),
                arguments("bulk_update_traces", batchUpdate),
                arguments("get_trace_details_by_id", onTraceAt(trace -> redirectToProject(trace.id()))),
                arguments("get_project_id_from_trace", onTraceAt(trace -> addComment(trace.id()))),
                arguments("get_trace_workspace", onTraceAt(trace -> datasetResourceClient
                        .createDatasetItems(datasetItemReferencing(trace.id()), WORKSPACE_NAME, API_KEY))),
                arguments("get_start_times_by_trace_ids", onTraceAt(
                        trace -> patch(trace.id(), costIntelligenceIdentityUpdate(trace.projectName())))),
                // A PATCH of an id nobody created yet, minted in the same week as a created trace.
                arguments("partial_insert_trace", (Function<Instant, UUID>) idAt -> {
                    var fresh = sameWeekId(createTrace(newTrace(idAt(idAt))).id());
                    patch(fresh, nameUpdate(PROJECT_NAME));
                    return fresh;
                }),
                // INSERT reads a stored row only over a partial one: PATCH a fresh id in that week, then POST it.
                arguments("insert_trace", (Function<Instant, UUID>) idAt -> {
                    createTrace(newTrace(idAt(idAt)));
                    var fresh = newTrace(sameWeekId(idAt(idAt)));
                    patch(fresh.id(), nameUpdate(PROJECT_NAME));
                    createTrace(fresh);
                    return fresh.id();
                }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sites")
    void aBoundedReadTouchesOnlyItsOwnWeek(String queryName, Function<Instant, UUID> trigger) {
        // One instant for both the id and the expected week, so a run crossing Monday UTC still agrees.
        var idAt = Instant.now();

        var id = trigger.apply(idAt);

        var actualPartitions = tracesPartitionsRead(queryName, id);
        var expectedPartitions = Set.of(yyyymmdd(mondayOf(idAt)));
        assertThat(actualPartitions).isEqualTo(expectedPartitions);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sites")
    void anIdPastTheCeilingFallsBackToReadingEveryWeek(String queryName, Function<Instant, UUID> trigger) {
        var id = trigger.apply(PAST_CEILING_ID_AT);

        var actualPartitions = tracesPartitionsRead(queryName, id);
        assertThat(actualPartitions).containsAll(FILLER_WEEKS);
    }

    @Test
    void oneUnderivableIdDropsTheBoundForTheWholeBatch() {
        var derivable = createTrace(newTrace(idAt(Instant.now())));
        var underivable = createTrace(newTrace(idAt(PAST_CEILING_ID_AT)));

        batchUpdateTags(Set.of(derivable.id(), underivable.id()), randomTags());

        var actualPartitions = tracesPartitionsRead("bulk_update_traces", derivable.id());
        assertThat(actualPartitions).containsAll(FILLER_WEEKS);
    }

    private Stream<Arguments> recentEras() {
        return Stream.of(
                arguments(Named.of("this week", Instant.now())),
                arguments(Named.of("previous week", RECENT_MONDAYS.getFirst().atStartOfDay(ZoneOffset.UTC)
                        .toInstant().plusMillis(RandomUtils.secure().randomLong(0, Duration.ofDays(7).toMillis())))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("recentEras")
    void existsReadsOnlyRecentWeeksForAProjectWithARecentTrace(Instant idAt) {
        // A project of its own, so its only trace is the one in the week under test.
        var trace = createTrace(newTrace(idAt(idAt)).toBuilder().projectName(randomName("project")).build());
        var projectId = projectIdOf(trace);
        // Other cases may add traces to any recent week, so the probe may read any of them, but no other.
        var expectedWeeks = RECENT_MONDAYS.stream().map(TracesReadPathPartitionPruningTest::yyyymmdd).toList();

        var actualExists = traceResourceClient.existsTraces(projectId, false, API_KEY, WORKSPACE_NAME);

        assertThat(actualExists).isTrue();
        var actualPartitions = tracesPartitionsRead("exists_traces_by_project_id", projectId);
        assertThat(actualPartitions).contains(yyyymmdd(mondayOf(idAt))).isSubsetOf(expectedWeeks);
        assertNoStatementFor("exists_traces_by_project_id_all_weeks", projectId);
    }

    @Test
    void existsReadsEveryWeekForAProjectWithNoRecentTrace() {
        var oldTrace = createTrace(newTrace(idAt(OLD_FILLER_MONDAY.atTime(12, 0).toInstant(ZoneOffset.UTC)))
                .toBuilder().projectName(randomName("project")).build());
        var projectId = projectIdOf(oldTrace);

        var actualExists = traceResourceClient.existsTraces(projectId, false, API_KEY, WORKSPACE_NAME);

        assertThat(actualExists).isTrue();
        var actualPartitions = tracesPartitionsRead("exists_traces_by_project_id_all_weeks", projectId);
        assertThat(actualPartitions).contains(yyyymmdd(OLD_FILLER_MONDAY));
    }

    private Stream<Arguments> idEras() {
        return Stream.of(
                arguments(Named.of("past", pastIdAt())),
                arguments(Named.of("far future", farFutureIdAt())),
                arguments(Named.of("past ceiling", PAST_CEILING_ID_AT)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("idEras")
    void createAfterPatchKeepsThePatchedFields(Instant idAt) {
        // The trace is built before the PATCH because the merged row keeps the partial row's createdAt, and the
        // assertion requires the expected createdAt not to be later than it.
        var trace = newTrace(idAt(idAt)).toBuilder().projectName(randomName("project")).build();
        var traceUpdate = TraceUpdate.builder()
                .projectName(trace.projectName())
                .tags(randomTags())
                .build();
        patch(trace.id(), traceUpdate);

        createTrace(trace);

        var expectedTrace = trace.toBuilder().tags(traceUpdate.tags()).build();
        getAndAssert(expectedTrace);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("idEras")
    void patchUpdatesAStoredTraceAndKeepsItsOtherFields(Instant idAt) {
        // Only the name is patched, so every other field has to come from the stored row.
        var storedTrace = createTrace(newTrace(idAt(idAt)).toBuilder().projectName(randomName("project")).build());
        var traceUpdate = nameUpdate(storedTrace.projectName());

        patch(storedTrace.id(), traceUpdate);

        var expectedTrace = storedTrace.toBuilder().name(traceUpdate.name()).build();
        getAndAssert(expectedTrace);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("idEras")
    void projectRedirectPointsAtTheTracesProject(Instant idAt) {
        var trace = createTrace(newTrace(idAt(idAt)));
        var expectedUrl = redirectResourceClient.expectedProjectRedirectUrl(frontendBaseUrl(), WORKSPACE_NAME,
                projectIdOf(trace), trace.id());

        var actualUrl = redirectToProject(trace.id());

        assertThat(actualUrl).isEqualTo(expectedUrl);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("idEras")
    void commentIsAddedToAStoredTrace(Instant idAt) {
        var storedTrace = createTrace(newTrace(idAt(idAt)));

        var comment = addComment(storedTrace.id());

        var expectedTrace = storedTrace.toBuilder().comments(List.of(comment)).build();
        getAndAssert(expectedTrace);
    }

    @Test
    void batchUpdateAcrossErasUpdatesEveryTrace() {
        var storedTraces = Stream.of(pastIdAt(), farFutureIdAt(), PAST_CEILING_ID_AT)
                .map(idAt -> createTrace(newTrace(idAt(idAt))))
                .toList();
        var tags = randomTags();

        batchUpdateTags(storedTraces.stream().map(Trace::id).collect(Collectors.toSet()), tags);

        var expectedTraces = storedTraces.stream().map(trace -> trace.toBuilder().tags(tags).build()).toList();
        var actualTraces = storedTraces.stream()
                .map(trace -> traceResourceClient.getById(trace.id(), WORKSPACE_NAME, API_KEY))
                .toList();
        TraceAssertions.assertTraces(actualTraces, expectedTraces, USER);
    }

    private String redirectToProject(UUID traceId) {
        return redirectResourceClient.projectsRedirect(traceId, UUID.randomUUID().toString(), WORKSPACE_NAME,
                redirectResourceClient.encodedBackendPath(frontendBaseUrl()), HttpStatus.SC_SEE_OTHER);
    }

    /** The frontend a redirect points at; WireMock's, as {@code RedirectResourceTest} uses. */
    private String frontendBaseUrl() {
        return wireMock.runtimeInfo().getHttpBaseUrl();
    }

    private Comment addComment(UUID traceId) {
        return traceResourceClient.generateAndCreateComment(traceId, API_KEY, WORKSPACE_NAME, HttpStatus.SC_CREATED);
    }

    private void patch(UUID id, TraceUpdate update) {
        traceResourceClient.updateTrace(id, update, API_KEY, WORKSPACE_NAME);
    }

    private TraceUpdate nameUpdate(String projectName) {
        return TraceUpdate.builder().projectName(projectName).name(randomName("name")).build();
    }

    /**
     * A PATCH carrying a cost-intelligence identity, whose listener reads the stored trace's start time: the request
     * that reaches {@code get_start_times_by_trace_ids}.
     */
    private TraceUpdate costIntelligenceIdentityUpdate(String projectName) {
        return TraceUpdate.builder()
                .projectName(projectName)
                .metadata(JsonUtils.getJsonNodeFromString("""
                        {"cipx": {"session": {"identity": {"user_uuid": "%s"}}}}
                        """.formatted(UUID.randomUUID())))
                .build();
    }

    private void batchUpdateTags(Set<UUID> ids, Set<String> tags) {
        traceResourceClient.batchUpdateTraces(TraceBatchUpdate.builder()
                .ids(ids)
                .update(TraceUpdate.builder().tags(tags).build())
                .build(), API_KEY, WORKSPACE_NAME);
    }

    private void getAndAssert(Trace expectedTrace) {
        var actualTrace = traceResourceClient.getById(expectedTrace.id(), WORKSPACE_NAME, API_KEY);
        TraceAssertions.assertTraces(List.of(actualTrace), List.of(expectedTrace), USER);
    }

    private UUID projectIdOf(Trace trace) {
        return traceResourceClient.getById(trace.id(), WORKSPACE_NAME, API_KEY).projectId();
    }

    private DatasetItemBatch datasetItemReferencing(UUID traceId) {
        var item = factory.manufacturePojo(DatasetItem.class).toBuilder()
                .source(DatasetItemSource.TRACE)
                .traceId(traceId)
                .spanId(null)
                .experimentItems(null)
                .build();
        return DatasetItemBatch.builder()
                .datasetName(randomName("dataset"))
                .items(List.of(item))
                .build();
    }

    /** A trigger that creates a trace whose id carries the given {@code id_at}, then runs {@code action} on it. */
    private Function<Instant, UUID> onTraceAt(Consumer<Trace> action) {
        return idAt -> {
            var trace = createTrace(newTrace(idAt(idAt)));
            action.accept(trace);
            return trace.id();
        };
    }

    /** Through the single-trace endpoint, the one whose write merges with the stored row. */
    private Trace createTrace(Trace trace) {
        traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);
        return trace;
    }

    /** Through the batch endpoint, so the traces land in one insert and so one part per week. */
    private void createTraces(List<Trace> traces) {
        traceResourceClient.batchCreateTraces(traces, API_KEY, WORKSPACE_NAME);
    }

    /**
     * The successor has no public API before the cutover, so it is put under {@code traces} the way the cutover does
     * it, before the app starts against it.
     */
    private static void installPartitionedSuccessorUnderTraces(DatabaseAnalyticsFactory databaseAnalyticsFactory) {
        TransactionTemplateAsync.create(databaseAnalyticsFactory.build())
                .nonTransaction(connection -> Mono.from(connection
                        .createStatement("EXCHANGE TABLES traces AND traces_local_v2 ON CLUSTER '{cluster}'")
                        .execute()))
                .block();
    }

    /**
     * In {@link #PROJECT_NAME}, beside the filler. {@code startTime} stays present-day: it is a separate column with
     * its own range validation. No {@code endTime}, so no {@code duration} either.
     */
    private Trace newTrace(UUID id) {
        return factory.manufacturePojo(Trace.class).toBuilder()
                .id(id)
                .projectName(PROJECT_NAME)
                .startTime(Instant.now().truncatedTo(ChronoUnit.MILLIS))
                .endTime(null)
                .duration(null)
                .createdAt(Instant.now())
                .feedbackScores(null)
                .usage(null)
                .build();
    }

    private static UUID idAt(Instant idAt) {
        return ID_GENERATOR.getTimeOrderedEpoch(idAt.toEpochMilli());
    }

    /** Another id minted at the same millisecond, so in the same week. */
    private static UUID sameWeekId(UUID id) {
        return ID_GENERATOR.getTimeOrderedEpoch(id.getMostSignificantBits() >>> 16);
    }

    /** Within the last five years, before 2106. */
    private static Instant pastIdAt() {
        return Instant.now().minusMillis(RandomUtils.secure().randomLong(0, Duration.ofDays(5 * 365).toMillis()));
    }

    /** Past 2106, where the two {@code id_at} types disagree, and before {@link #PAST_CEILING_ID_AT}. */
    private static Instant farFutureIdAt() {
        return Instant.ofEpochMilli(RandomUtils.secure().randomLong(
                Instant.parse("2107-01-01T00:00:00Z").toEpochMilli(),
                Instant.parse("2299-12-01T00:00:00Z").toEpochMilli()));
    }

    private static Set<String> randomTags() {
        return Set.of(randomName("tag"));
    }

    private static String randomName(String prefix) {
        return "%s-%s".formatted(prefix, RandomStringUtils.secure().nextAlphanumeric(32));
    }

    /** Polled: a query's {@code query_log} row is written asynchronously, flushed every 200 ms here. */
    private Set<String> tracesPartitionsRead(String queryName, UUID mentionedId) {
        var partitions = Awaitility.await()
                .alias("query_log holds a %s statement mentioning id %s".formatted(queryName, mentionedId))
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> partitionsReadOrNull(queryName, mentionedId), Objects::nonNull);
        return Arrays.stream(partitions.split(","))
                .filter(partition -> !partition.isEmpty())
                .map(partition -> partition.substring(PARTITION_PREFIX.length()))
                .collect(Collectors.toSet());
    }

    /**
     * That ClickHouse logged no {@code queryName} statement mentioning the id. A marker statement is sent and its row
     * awaited first: a finished request's statements were queued before the marker, which is sent after its response,
     * and {@code query_log} writes rows in the order they were queued, so any such row is readable by then.
     */
    private void assertNoStatementFor(String queryName, UUID mentionedId) {
        var marker = UUID.randomUUID();
        template.nonTransaction(connection -> Mono.from(connection
                .createStatement("SELECT '%s' SETTINGS log_comment = '%s:'".formatted(marker, QUERY_LOG_MARKER))
                .execute())
                .flatMapMany(result -> result.map((row, _) -> row.get(0, String.class)))
                .then())
                .block();
        tracesPartitionsRead(QUERY_LOG_MARKER, marker);

        var actualPartitions = partitionsReadOrNull(queryName, mentionedId);
        assertThat(actualPartitions).isNull();
    }

    /** The partition list of the latest such statement, or null when {@code query_log} holds none yet. */
    private String partitionsReadOrNull(String queryName, UUID mentionedId) {
        return template.nonTransaction(connection -> Mono.from(connection
                .createStatement(TRACES_PARTITIONS_READ)
                .bind("prefix", PARTITION_PREFIX)
                .bind("op", queryName)
                .bind("mentioned_id", mentionedId.toString())
                .execute())
                .flatMap(result -> Mono.from(result.map((row, _) -> row.get(0, String.class)))))
                .block();
    }

    private static LocalDate mondayOf(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static String yyyymmdd(LocalDate monday) {
        return monday.format(DateTimeFormatter.BASIC_ISO_DATE);
    }
}
