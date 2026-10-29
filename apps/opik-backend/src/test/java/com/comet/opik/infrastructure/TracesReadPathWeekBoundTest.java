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
import com.comet.opik.utils.WeeklyPartitions;
import com.fasterxml.jackson.databind.JsonNode;
import com.redis.testcontainers.RedisContainer;
import io.r2dbc.spi.Statement;
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
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * The id-keyed {@code traces} reads of {@code TraceDAO} that carry the {@code <id_weeks>} week bound: the
 * trace-id-list reads of OPIK-8332 ({@code find_traces_by_ids} and the {@code get_target_project_ids_for_traces} it
 * runs first, {@code get_project_ids_by_trace_ids}, {@code get_trace_workspace} and
 * {@code get_start_times_by_trace_ids}), the single-trace lookups behind the project redirect
 * ({@code get_trace_details_by_id}) and trace comments ({@code get_project_id_from_trace}), the existence probe a
 * PATCH is routed by ({@code get_partial_trace_by_id}), and the stored-row read every trace write merges with
 * ({@code insert_trace}, {@code partial_insert_trace}, {@code update_trace} and {@code bulk_update_traces}). Also the
 * project existence probe, which reads recent weeks first and all of them only when that finds nothing.
 *
 * <p>Driven through the endpoints that reach them — {@code GET /v1/private/traces/{id}},
 * {@code POST /v1/private/traces}, {@code PATCH /v1/private/traces/{id}}, {@code PATCH /v1/private/traces/batch},
 * {@code GET /v1/private/traces/exists}, {@code POST /v1/private/traces/{id}/comments},
 * {@code GET /v1/session/redirect/projects} and {@code POST /v1/private/datasets/items} — so what is asserted is what
 * a client sees. {@code get_start_times_by_trace_ids} runs in the cost-intelligence listener that a PATCH carrying a
 * {@code cipx} identity triggers.
 *
 * <p><b>Every trace here is created through the ingestion endpoint, far-future ids included.</b> Ingestion rejects a
 * non-v7 id unconditionally but checks the timestamp window only under the {@code uuidValidation} kill-switch, which
 * is off by default — so these are the ids a client can still send today, and the suite needs no raw {@code INSERT}
 * to produce them.
 *
 * <p><b>Rows first, because the bound's whole contract is that it changes none.</b> It is a strict consequence of
 * the id predicate, and the way it can fail is silent: fewer rows, no error. For a far-future trace that is a 404
 * for a trace the client just created, and on a write a stored row the merge misses: an update that reports success
 * and does not land, or a create that drops the fields an earlier PATCH set.
 *
 * <p><b>Then that the statement still carries the bound</b>, which rows cannot see: a bound that stopped being
 * emitted leaves the query correct and merely opening every partition again, and on the project lookup a lost id has
 * no row-level symptom at all. Presence-or-absence only — the values are {@link WeeklyPartitions}' contract, pinned
 * by its unit test, and that the predicate prunes is pinned by the {@code EXPLAIN indexes = 1} gate in
 * {@link TracesLocalV2PartitioningTest}; asserting them here again would only couple this suite to the SQL text.
 * The project existence probe's id lower bound is the exception: on this unpartitioned table it is the half of its
 * bound that does the work, through the sort key, so {@code EXPLAIN indexes = 1} shows that the primary key uses it.
 *
 * <p>Runs on whatever estate the migrations produce, with no topology of its own. Today that is the legacy
 * {@code traces}, whose 32-bit {@code id_at} wraps a far-future timestamp — the harder direction, since a set
 * derived only from the successor's {@code DateTime64} would match zero rows for such an id. It is the row resolving
 * here, not a pinned week value, that shows the right week is in the set. That partitions are actually pruned is
 * {@link TracesReadPathPartitionPruningTest}'s job, on the partitioned successor.
 *
 * <p>A dedicated, non-reused ClickHouse carrying {@code clickhouse-fast-log-flush.xml}, so the statement reads poll
 * {@code system.query_log} rather than forcing a server-wide {@code SYSTEM FLUSH LOGS} that races the rows it is
 * meant to reveal.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TracesReadPathWeekBoundTest {

    private static final String API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);

    /**
     * A second workspace, for the one assertion that can tell a resolved row from a lost one on
     * {@code get_trace_workspace}: a reference from another workspace must be rejected, and only a row that came
     * back can reject it.
     */
    private static final String OTHER_API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String OTHER_WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String OTHER_WORKSPACE_ID = UUID.randomUUID().toString();

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    /** The {@code log_comment} query names the DAO stamps, by which each statement is read back. */
    private static final String FIND_TRACES_BY_IDS = "find_traces_by_ids";
    private static final String GET_TARGET_PROJECT_IDS = "get_target_project_ids_for_traces";
    private static final String GET_PROJECT_IDS = "get_project_ids_by_trace_ids";
    private static final String GET_TRACE_WORKSPACE = "get_trace_workspace";
    private static final String GET_START_TIMES = "get_start_times_by_trace_ids";
    private static final String INSERT_TRACE = "insert_trace";
    private static final String PARTIAL_INSERT_TRACE = "partial_insert_trace";
    private static final String UPDATE_TRACE = "update_trace";
    private static final String GET_PARTIAL_TRACE_BY_ID = "get_partial_trace_by_id";
    private static final String GET_TRACE_DETAILS_BY_ID = "get_trace_details_by_id";
    private static final String GET_PROJECT_ID_FROM_TRACE = "get_project_id_from_trace";
    private static final String EXISTS_TRACES = "exists_traces_by_project_id";
    private static final String EXISTS_TRACES_ALL_WEEKS = "exists_traces_by_project_id_all_weeks";
    private static final String BULK_UPDATE_TRACES = "bulk_update_traces";

    /** The range {@link #farFutureIdAt()} draws from: past 2106, and before {@link #PAST_CEILING_ID_AT}. */
    private static final Instant FAR_FUTURE_FROM = Instant.parse("2107-01-01T00:00:00Z");
    private static final Instant FAR_FUTURE_TO = Instant.parse("2299-12-01T00:00:00Z");

    /** How far back {@link #pastIdAt()} reaches, so the derivable cases cover old weeks and not only the current one. */
    private static final Duration PAST_SPAN = Duration.ofDays(5 * 365);

    /**
     * The first instant {@code id_at} cannot represent on the partitioned successor, where it saturates rather than
     * storing the real week — the one underivable cause that occurs in real data. A non-v7 id would also disable the
     * bound, but ingestion rejects those unconditionally, so it is not the case exercised here.
     */
    private static final Instant PAST_CEILING_ID_AT = LocalDate.of(2300, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);

    /**
     * The only marker the statement checks look for. None of these statements names {@code id_at} anywhere else (it
     * is a {@code MATERIALIZED} column, so neither an insert column list nor {@code SELECT *} spells it), so its
     * presence is exactly "the week bound rendered" and its absence exactly "this batch fell back to the unbounded
     * form" — without pinning how the predicate is spelled.
     */
    private static final String WEEK_BOUND_MARKER = "id_at";

    /** What {@link #batchUpdateTags} writes, so the input and the expectation are one value. */
    private static final Set<String> BATCH_UPDATE_TAGS = Set.of(randomName("tag"));

    /**
     * The statement ClickHouse received for one query name, narrowed to this test's own by a trace or project id that
     * appears in its text. {@code log_comment} alone is not enough: every test here runs the same query names under one
     * workspace, so only an id minted by this test identifies its statement.
     */
    private static final String LAST_STATEMENT_FOR = """
            SELECT query
            FROM system.query_log
            WHERE log_comment LIKE concat(:op, ':%')
            AND type = 'QueryFinish'
            AND query LIKE concat('%', :mentioned_id, '%')
            ORDER BY event_time_microseconds DESC
            LIMIT 1
            """;

    /** The query name of the statement {@link #assertNoStatementFor} awaits before it looks for an absent one. */
    private static final String QUERY_LOG_MARKER = "query_log_marker";

    /** See the class javadoc: opts this container into a 200 ms {@code system.query_log} flush interval. */
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
                clickHouseContainer, ClickHouseContainerUtils.DATABASE_NAME);
        MigrationUtils.runMysqlDbMigration(mysqlContainer);
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);
        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(mysqlContainer.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(redisContainer.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        // The production default, which config-test.yml turns on so other suites can cover the
                        // validator itself. Off is the state that produced the rows this suite is about: it is what
                        // lets a client's far-future id reach the table, and so what lets these cases be created by
                        // the ingestion endpoint rather than by an INSERT that bypasses it. Nothing else here
                        // depends on it - the week bound is emitted regardless.
                        .customConfigs(List.of(new CustomConfig("uuidValidation.enabled", "false")))
                        .build());
    }

    private TraceResourceClient traceResourceClient;
    private DatasetResourceClient datasetResourceClient;
    private ProjectResourceClient projectResourceClient;
    private RedirectResourceClient redirectResourceClient;
    private TransactionTemplateAsync template;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport, TransactionTemplateAsync template) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        mockTargetWorkspace(wireMock.server(), OTHER_API_KEY, OTHER_WORKSPACE_NAME, OTHER_WORKSPACE_ID, USER);
        this.traceResourceClient = new TraceResourceClient(clientSupport, baseUrl);
        this.datasetResourceClient = new DatasetResourceClient(clientSupport, baseUrl);
        this.projectResourceClient = new ProjectResourceClient(clientSupport, baseUrl, factory);
        this.redirectResourceClient = new RedirectResourceClient(clientSupport);
        this.template = template;
    }

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
        clickHouseContainer.stop();
        zookeeperContainer.stop();
        network.close();
    }

    private Stream<Arguments> everySiteBoundsWhatItCanDeriveAndFallsBackOtherwise() {
        // Each trigger reaches that site for an id carrying the given id_at, and returns that id. Reading the fields
        // at call time rather than capturing them: @MethodSource runs before @BeforeAll has wired the clients in.
        var getById = onTraceAt(trace -> traceResourceClient.getById(trace.id(), WORKSPACE_NAME, API_KEY));
        var batchUpdate = onTraceAt(trace -> batchUpdateTags(Set.of(trace.id())));
        var patchStored = onTraceAt(trace -> patch(trace.id(), nameUpdate(trace)));

        return Stream.of(
                // A get-by-id runs the target-projects read first and feeds it into the main one, so one trigger
                // covers the two query names it emits.
                arguments(FIND_TRACES_BY_IDS, getById),
                arguments(GET_TARGET_PROJECT_IDS, getById),
                // A batch update looks the projects up first, so one trigger covers both query names too.
                arguments(GET_PROJECT_IDS, batchUpdate),
                arguments(BULK_UPDATE_TRACES, batchUpdate),
                arguments(GET_TRACE_WORKSPACE, onTraceAt(trace -> datasetResourceClient
                        .createDatasetItems(datasetItemReferencing(trace.id()), WORKSPACE_NAME, API_KEY))),
                arguments(GET_TRACE_DETAILS_BY_ID, onTraceAt(trace -> redirectToProject(trace.id()))),
                arguments(GET_PROJECT_ID_FROM_TRACE, onTraceAt(trace -> addComment(trace.id()))),
                arguments(GET_START_TIMES, onTraceAt(
                        trace -> patch(trace.id(), costIntelligenceIdentityUpdate(trace.projectName())))),
                // Creating the trace is the trigger: every create merges with the stored row of its id.
                arguments(INSERT_TRACE, onTraceAt(NO_FURTHER_REQUEST)),
                // A PATCH of a stored trace probes for it first, then updates it.
                arguments(GET_PARTIAL_TRACE_BY_ID, patchStored),
                arguments(UPDATE_TRACE, patchStored),
                // A PATCH of an id with no row yet.
                arguments(PARTIAL_INSERT_TRACE, (Function<Instant, UUID>) idAt -> {
                    var id = idAt(idAt);
                    patch(id, TraceUpdate.builder()
                            .projectName(randomName("project"))
                            .name(randomName("name"))
                            .build());
                    return id;
                }));
    }

    /**
     * The {@code <if(id_weeks)>} branch at every site, in both directions: a derivable id set renders the bound, an
     * underivable one leaves it out and the query reverts to the form it had before this work.
     * <p>
     * Parameterised over the sites rather than written out per site because the claim is identical for all of them
     * and only the trigger differs — and because the fallback half is the direction that would otherwise go
     * untested on the sites whose lost rows have no visible symptom. A site that bound {@code :id_weeks}
     * unconditionally would fail here on the underivable case, with an unbound parameter; one that lost its slot
     * would fail on the derivable case.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource
    void everySiteBoundsWhatItCanDeriveAndFallsBackOtherwise(String queryName, Function<Instant, UUID> trigger) {
        var derivable = trigger.apply(pastIdAt());
        var underivable = trigger.apply(PAST_CEILING_ID_AT);

        var actualBoundStatement = statementFor(queryName, derivable);
        var actualFallbackStatement = statementFor(queryName, underivable);
        assertThat(actualBoundStatement).contains(WEEK_BOUND_MARKER);
        assertThat(actualFallbackStatement).doesNotContain(WEEK_BOUND_MARKER);
    }

    @Test
    void oneUnderivableIdDropsTheBoundForTheWholeBatch() {
        // All-or-nothing: a partially derived set is a set some rows are not in, so one id past the ceiling has to
        // disable the bound for every id in the batch. Every other case here sends a homogeneous batch and so cannot
        // see it. Both rows must still resolve, which is the half that says the fallback is correct and not merely
        // unbounded.
        var derivable = createTrace(pastIdAt());
        var underivable = createTrace(PAST_CEILING_ID_AT);

        batchUpdateTags(Set.of(derivable.id(), underivable.id()));

        var actualProjectLookup = statementFor(GET_PROJECT_IDS, derivable.id());
        var actualBulkUpdate = statementFor(BULK_UPDATE_TRACES, derivable.id());
        assertThat(actualProjectLookup).doesNotContain(WEEK_BOUND_MARKER);
        assertThat(actualBulkUpdate).doesNotContain(WEEK_BOUND_MARKER);
        getAndAssert(derivable.toBuilder().tags(BATCH_UPDATE_TAGS).build());
        getAndAssert(underivable.toBuilder().tags(BATCH_UPDATE_TAGS).build());
    }

    @Test
    void getTraceByIdResolvesAFarFutureTrace() {
        // The headline risk, and the only era where the bound can be wrong rather than absent. On this schema id_at
        // is a 32-bit DateTime, so the row is filed under the WRAPPED week: a bound carrying only the honest one
        // would match nothing and this would be a 404 for a trace the client just created.
        var expectedTrace = createTrace(farFutureIdAt());

        getAndAssert(expectedTrace);
    }

    @Test
    void datasetItemCreationRejectsAFarFutureTraceFromAnotherWorkspace() {
        // The same wrong-week risk where it is worse than a lost row: the caller reduces with allMatch, which over an
        // EMPTY result is true, so a bound that failed to return the row reads as "no workspace mismatch" and the
        // reference is ACCEPTED. A same-workspace reference is accepted either way and so cannot see that; only a
        // cross-workspace one, which must be rejected, can.
        var farFuture = createTrace(farFutureIdAt());

        try (var actualResponse = datasetResourceClient.callCreateDatasetItems(
                datasetItemReferencing(farFuture.id()), OTHER_WORKSPACE_NAME, OTHER_API_KEY)) {
            assertThat(actualResponse.getStatus()).isEqualTo(HttpStatus.SC_CONFLICT);
        }
    }

    private Stream<Arguments> idEras() {
        return Stream.of(
                arguments(Named.of("past", pastIdAt())),
                arguments(Named.of("far future", farFutureIdAt())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("idEras")
    void createAfterPatchKeepsThePatchedFields(Instant idAt) {
        // The create merges with the partial row the PATCH left and keeps its tags over the request's, which it can
        // only do if its stored-row read found that row.
        //
        // The trace is built before the PATCH because the merged row keeps the partial row's createdAt, and the
        // assertion requires the expected createdAt not to be later than it.
        var trace = newTrace(idAt(idAt));
        var traceUpdate = TraceUpdate.builder()
                .projectName(trace.projectName())
                .tags(Set.of(randomName("tag")))
                .build();
        patch(trace.id(), traceUpdate);

        createTrace(trace);

        var expectedTrace = trace.toBuilder().tags(traceUpdate.tags()).build();
        getAndAssert(expectedTrace);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("idEras")
    void patchUpdatesAStoredTraceAndKeepsItsOtherFields(Instant idAt) {
        // A PATCH whose probe missed the stored row takes partial_insert_trace and loses every field it does not carry;
        // one whose update missed it writes nothing. Only the name is patched, so every other field has to come from
        // the stored row.
        var storedTrace = createTrace(idAt);
        var traceUpdate = nameUpdate(storedTrace);

        patch(storedTrace.id(), traceUpdate);

        var expectedTrace = storedTrace.toBuilder().name(traceUpdate.name()).build();
        getAndAssert(expectedTrace);
    }

    @Test
    void batchUpdateAcrossErasUpdatesEveryTrace() {
        // One statement, so one week set that must hold both eras' weeks, and both of the far-future id's. A trace
        // whose week is missing keeps its tags while the request reports success.
        var past = createTrace(pastIdAt());
        var farFuture = createTrace(farFutureIdAt());

        batchUpdateTags(Set.of(past.id(), farFuture.id()));

        var actualBulkUpdate = statementFor(BULK_UPDATE_TRACES, past.id());
        assertThat(actualBulkUpdate).contains(WEEK_BOUND_MARKER);
        getAndAssert(past.toBuilder().tags(BATCH_UPDATE_TAGS).build());
        getAndAssert(farFuture.toBuilder().tags(BATCH_UPDATE_TAGS).build());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("idEras")
    void projectRedirectPointsAtTheTracesProject(Instant idAt) {
        // The redirect resolves the project from the trace alone; a lookup that missed the row has no project to name.
        var trace = createTrace(idAt);
        var expectedUrl = redirectResourceClient.expectedProjectRedirectUrl(frontendBaseUrl(), WORKSPACE_NAME,
                projectIdOf(trace), trace.id());

        var actualUrl = redirectToProject(trace.id());

        assertThat(actualUrl).isEqualTo(expectedUrl);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("idEras")
    void commentIsAddedToAStoredTrace(Instant idAt) {
        // A comment resolves its trace's project first, and a lookup that missed the row answers 404.
        var storedTrace = createTrace(idAt);

        var comment = addComment(storedTrace.id());

        var expectedTrace = storedTrace.toBuilder().comments(List.of(comment)).build();
        getAndAssert(expectedTrace);
    }

    private Stream<Arguments> farFutureEras() {
        return Stream.of(
                arguments(Named.of("far future", farFutureIdAt())),
                arguments(Named.of("past ceiling", PAST_CEILING_ID_AT)));
    }

    /**
     * A project whose only trace has an id past 2106 is found too. Which of the two probes found it is not asserted:
     * this table files such an id under a wrapped {@code id_at}, which can be any week up to 2106.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("farFutureEras")
    void existsFindsAProjectsOnlyTraceWithAFarFutureId(Instant idAt) {
        var projectId = projectIdOf(createTrace(idAt));

        var actualExists = traceResourceClient.existsTraces(projectId, false, API_KEY, WORKSPACE_NAME);

        assertThat(actualExists).isTrue();
        var actualRecentProbe = statementFor(EXISTS_TRACES, projectId);
        assertThat(actualRecentProbe).contains(WEEK_BOUND_MARKER);
    }

    private Stream<Arguments> recentEras() {
        return Stream.of(
                arguments(Named.of("this week", Instant.now())),
                arguments(Named.of("previous week", previousWeekIdAt())));
    }

    /** Both halves of the recent bound, and the lower edge they share: the previous week is still recent. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("recentEras")
    void existsChecksOnlyTheRecentWeeksForAProjectWithARecentTrace(Instant idAt) {
        var projectId = projectIdOf(createTrace(idAt));

        var actualExists = traceResourceClient.existsTraces(projectId, false, API_KEY, WORKSPACE_NAME);

        assertThat(actualExists).isTrue();
        var actualRecentProbe = statementFor(EXISTS_TRACES, projectId);
        assertThat(actualRecentProbe).contains(WEEK_BOUND_MARKER);
        assertThat(primaryKeyColumnsUsedBy(actualRecentProbe)).contains("id");
        assertNoStatementFor(EXISTS_TRACES_ALL_WEEKS, projectId);
    }

    @Test
    void existsChecksEveryWeekForAProjectWhoseTracesAreAllOld() {
        var projectId = projectIdOf(createTrace(olderThanRecentWeeksIdAt()));

        var actualExists = traceResourceClient.existsTraces(projectId, false, API_KEY, WORKSPACE_NAME);

        assertThat(actualExists).isTrue();
        var actualAllWeeksProbe = statementFor(EXISTS_TRACES_ALL_WEEKS, projectId);
        assertThat(actualAllWeeksProbe).doesNotContain(WEEK_BOUND_MARKER);
    }

    @Test
    void existsIsFalseForAProjectWithNoTraceAfterCheckingEveryWeek() {
        var projectId = projectResourceClient.createProject(randomName("project"), API_KEY, WORKSPACE_NAME);

        var actualExists = traceResourceClient.existsTraces(projectId, false, API_KEY, WORKSPACE_NAME);

        assertThat(actualExists).isFalse();
        var actualAllWeeksProbe = statementFor(EXISTS_TRACES_ALL_WEEKS, projectId);
        assertThat(actualAllWeeksProbe).doesNotContain(WEEK_BOUND_MARKER);
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

    /** A PATCH of a stored trace's name to a random one. */
    private TraceUpdate nameUpdate(Trace trace) {
        return TraceUpdate.builder().projectName(trace.projectName()).name(randomName("name")).build();
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

    private UUID projectIdOf(Trace trace) {
        return traceResourceClient.getById(trace.id(), WORKSPACE_NAME, API_KEY).projectId();
    }

    private void getAndAssert(Trace expectedTrace) {
        var actualTrace = traceResourceClient.getById(expectedTrace.id(), WORKSPACE_NAME, API_KEY);
        TraceAssertions.assertTraces(List.of(actualTrace), List.of(expectedTrace), USER);
    }

    private void batchUpdateTags(Set<UUID> traceIds) {
        traceResourceClient.batchUpdateTraces(TraceBatchUpdate.builder()
                .ids(traceIds)
                .update(TraceUpdate.builder().tags(BATCH_UPDATE_TAGS).build())
                .build(), API_KEY, WORKSPACE_NAME);
    }

    /** A one-item batch into a fresh dataset, referencing {@code traceId} - the shape that reaches the check. */
    private DatasetItemBatch datasetItemReferencing(UUID traceId) {
        var item = factory.manufacturePojo(DatasetItem.class).toBuilder()
                .source(DatasetItemSource.TRACE)
                .traceId(traceId)
                .spanId(null)
                .experimentItems(null)
                .build();

        return DatasetItemBatch.builder()
                .datasetName("dataset-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(32)))
                .items(List.of(item))
                .build();
    }

    /** For a site the trace's own creation reaches. */
    private static final Consumer<Trace> NO_FURTHER_REQUEST = _ -> {
    };

    /** A trigger that creates a trace whose id carries the given {@code id_at}, runs {@code action} on it. */
    private Function<Instant, UUID> onTraceAt(Consumer<Trace> action) {
        return idAt -> {
            var trace = createTrace(idAt);
            action.accept(trace);
            return trace.id();
        };
    }

    /** A trace through the real ingestion path whose {@code id} carries {@code idAt}, in a project of its own. */
    private Trace createTrace(Instant idAt) {
        return createTrace(newTrace(idAt(idAt)));
    }

    private Trace createTrace(Trace trace) {
        traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);
        return trace;
    }

    /**
     * {@code startTime} stays present-day: it is a separate column with its own range validation, and the id is what
     * this suite is about. No {@code endTime}, so no {@code duration} either.
     */
    private Trace newTrace(UUID id) {
        return factory.manufacturePojo(Trace.class).toBuilder()
                .id(id)
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

    /** A random instant within {@link #PAST_SPAN}, before 2106 and so the same week under both {@code id_at} types. */
    private static Instant pastIdAt() {
        return Instant.now().minusMillis(RandomUtils.secure().randomLong(0, PAST_SPAN.toMillis()));
    }

    /** A random instant in the previous UTC week, the first of the weeks the project existence probe reads first. */
    private static Instant previousWeekIdAt() {
        var previousMonday = LocalDate.now(ZoneOffset.UTC)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .minusWeeks(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        return previousMonday.plusMillis(RandomUtils.secure().randomLong(0, Duration.ofDays(7).toMillis()));
    }

    /** Before the weeks the project existence probe reads first, and within {@link #PAST_SPAN}. */
    private static Instant olderThanRecentWeeksIdAt() {
        // Fourteen days back is always before the previous week's Monday.
        var recentDays = Duration.ofDays(14);
        return Instant.now().minus(recentDays)
                .minusMillis(RandomUtils.secure().randomLong(0, PAST_SPAN.minus(recentDays).toMillis()));
    }

    /**
     * A random far-future {@code id_at}, the shape a broken client clock mints (litellm
     * <a href="https://github.com/BerriAI/litellm/issues/31294">BerriAI/litellm#31294</a>): past 2106 and so the only
     * era whose two {@code id_at} representations differ.
     */
    private static Instant farFutureIdAt() {
        return Instant.ofEpochMilli(
                RandomUtils.secure().randomLong(FAR_FUTURE_FROM.toEpochMilli(), FAR_FUTURE_TO.toEpochMilli()));
    }

    private static String randomName(String prefix) {
        return "%s-%s".formatted(prefix, RandomStringUtils.secure().nextAlphanumeric(32));
    }

    /**
     * The statement ClickHouse received for {@code queryName}, polled rather than read once: a query's
     * {@code query_log} row is queued asynchronously after the result reaches the client, and the flush interval this
     * container is configured with is 200 ms.
     */
    private String statementFor(String queryName, UUID mentionedId) {
        return Awaitility.await()
                .alias("query_log holds a %s statement mentioning id %s".formatted(queryName, mentionedId))
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> queryOneString(LAST_STATEMENT_FOR, statement -> statement
                        .bind("op", queryName)
                        .bind("mentioned_id", mentionedId.toString())), Objects::nonNull);
    }

    /**
     * That ClickHouse logged no {@code queryName} statement mentioning the id. A marker statement is sent and its row
     * awaited first: a finished request's statements were queued before the marker, which is sent after its response,
     * and {@code query_log} writes rows in the order they were queued, so any such row is readable by then.
     */
    private void assertNoStatementFor(String queryName, UUID mentionedId) {
        var marker = UUID.randomUUID();
        queryOneString("SELECT '%s' SETTINGS log_comment = '%s:'".formatted(marker, QUERY_LOG_MARKER), _ -> {
        });
        statementFor(QUERY_LOG_MARKER, marker);

        var actualStatement = queryOneString(LAST_STATEMENT_FOR, statement -> statement
                .bind("op", queryName)
                .bind("mentioned_id", mentionedId.toString()));
        assertThat(actualStatement).isNull();
    }

    /**
     * The sort-key columns ClickHouse narrows the scan by for {@code statement}, read from its {@code PrimaryKey}
     * entry in {@code EXPLAIN indexes = 1}: on this unpartitioned table, the columns a read can skip granules on.
     */
    private List<String> primaryKeyColumnsUsedBy(String statement) {
        var explainRows = template.stream(connection -> Flux.from(connection
                .createStatement("EXPLAIN indexes = 1, json = 1 %s".formatted(statement))
                .execute())
                .flatMap(result -> result.map((row, _) -> row.get(0, String.class))))
                .collectList()
                .block();
        var indexes = JsonUtils.getJsonNodeFromString(String.join("\n", explainRows)).findValue("Indexes");
        return StreamSupport.stream(indexes.spliterator(), false)
                .filter(index -> "PrimaryKey".equals(index.path("Type").asText()))
                .flatMap(index -> StreamSupport.stream(index.path("Keys").spliterator(), false))
                .map(JsonNode::asText)
                .toList();
    }

    private String queryOneString(String sql, Consumer<Statement> binder) {
        return template.nonTransaction(connection -> {
            var statement = connection.createStatement(sql);
            binder.accept(statement);
            return Mono.from(statement.execute())
                    .flatMap(result -> Mono.from(result.map((row, _) -> row.get(0, String.class))));
        }).block();
    }
}
