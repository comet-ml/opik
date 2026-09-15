package com.comet.opik.infrastructure;

import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.SpanDAO;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import lombok.Builder;
import org.apache.commons.lang3.RandomStringUtils;
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
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.dropwizard.guice.test.jupiter.param.Jit;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pre-cutover counterpart of {@link SpansDistributedWrapMutationTest}: the same {@code SpanDAO} mutation paths with
 * {@code databaseAnalyticsDataModel.spansDistributedWrapEnabled} left at its default {@code false}, where {@code spans}
 * is still the {@code ReplicatedReplacingMergeTree} and {@code spans_local} does not exist at all.
 *
 * <p><b>Why both halves are needed.</b> {@code SpanDAO#selectSpansMutationTable} picks between two names from one
 * flag, so a routing test that only exercises the wrapped topology leaves the other branch unverified — an
 * implementation that sent unwrapped mutations to {@code spans_local} would keep that suite green. The trace-delete
 * cascade would fail loudly, since no {@code spans_local} table exists pre-cutover, but the two retention sweeps are
 * the exception: they have no public endpoint (retention is disabled everywhere and runs only from the internal
 * {@code RetentionCatchUpJob}), so nothing else in the repository calls {@code deleteForRetentionBounded} at all. This
 * suite closes that gap and pins both branches of the routing decision.
 *
 * <p>{@link #spansIsTheReplicatedReplacingMergeTreePreCutover} is the guard that keeps the positive tests honest,
 * mirroring {@code distributedSpansRejectsDirectMutation} in the wrapped suite: it proves the deletes above really ran
 * against the unwrapped {@code spans} rather than accidentally against a shard.
 *
 * <p>Dedicated, non-reused containers, as in the wrapped suite: nothing here renames a table, but the suite asserts the
 * pre-cutover topology as a precondition, so it has to own that topology rather than inherit it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class SpansUnwrappedMutationTest {

    private static final String API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);

    // A second workspace, so the multi-workspace bounded-retention case can assert *selective* deletion rather than
    // merely rendering more template branches.
    private static final String API_KEY_2 = "apiKey-" + UUID.randomUUID();
    private static final String WORKSPACE_NAME_2 = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String WORKSPACE_ID_2 = UUID.randomUUID().toString();

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils.newZookeeperContainer(false,
            network);
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(false, network, zookeeperContainer);
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
        // No custom config: spansDistributedWrapEnabled stays at its default false, which is the whole point.
        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(mysqlContainer.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(redisContainer.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        .build());
    }

    private SpanResourceClient spanResourceClient;
    private TraceResourceClient traceResourceClient;
    private ExperimentResourceClient experimentResourceClient;
    private TransactionTemplateAsync template;
    private SpanDAO spanDAO;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport, TransactionTemplateAsync template, @Jit SpanDAO spanDAO) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        mockTargetWorkspace(wireMock.server(), API_KEY_2, WORKSPACE_NAME_2, WORKSPACE_ID_2, USER);
        spanResourceClient = new SpanResourceClient(clientSupport, baseUrl);
        traceResourceClient = new TraceResourceClient(clientSupport, baseUrl);
        experimentResourceClient = new ExperimentResourceClient(clientSupport, baseUrl, factory);
        this.template = template;
        this.spanDAO = spanDAO;
    }

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
        clickHouseContainer.stop();
        zookeeperContainer.stop();
        network.close();
    }

    @Test
    void traceDeleteCascadeRemovesSpansFromTheUnwrappedTable() {
        var trace = seedTraceWithSpanAt(ID_GENERATOR.generateId());

        traceResourceClient.deleteTrace(trace.id(), WORKSPACE_NAME, API_KEY);

        awaitNoSpansOn(trace);
    }

    /**
     * Neither sweep has any other exercise in the repository — retention is disabled everywhere and runs only from the
     * internal {@code RetentionCatchUpJob} — and the bounded one renders a per-workspace template loop, so its
     * rendered form is only ever proven by running it.
     */
    @Test
    void deleteForRetentionRunsAgainstTheUnwrappedTable() {
        var window = RetentionWindow.aroundNow();
        var trace = seedTraceWithSpanAt(window.middleId());

        spanDAO.deleteForRetention(List.of(WORKSPACE_ID), window.cutoffId(), window.lowerBound()).block();

        assertThat(spanIdsOf(trace, API_KEY, WORKSPACE_NAME)).isEmpty();
    }

    @Test
    void deleteForRetentionBoundedRunsAgainstTheUnwrappedTable() {
        var window = RetentionWindow.aroundNow();
        var trace = seedTraceWithSpanAt(window.middleId());

        spanDAO.deleteForRetentionBounded(Map.of(WORKSPACE_ID, window.lowerBound()), window.cutoffId(),
                window.lowerBound()).block();

        assertThat(spanIdsOf(trace, API_KEY, WORKSPACE_NAME)).isEmpty();
    }

    /**
     * The case the OR-ed per-workspace predicates exist for, asserting what they are actually for: each workspace
     * carries its <b>own</b> {@code trace_id} floor, so the same call must delete in one workspace and spare the other.
     * A broken separator, a mis-numbered bind, or a single shared floor all collapse this to "delete both" or "delete
     * neither".
     */
    @Test
    void deleteForRetentionBoundedAppliesPerWorkspaceLowerBounds() {
        var now = Instant.now();
        var floor = ID_GENERATOR.generateId(now.minusSeconds(2));
        var deletedTraceId = ID_GENERATOR.generateId(now.minusSeconds(1));
        var sparedTraceId = ID_GENERATOR.generateId(now.minusSeconds(1));
        var aboveTheSpared = ID_GENERATOR.generateId(now);
        var cutoff = ID_GENERATOR.generateId(now.plusSeconds(1));

        var deletedTrace = newTrace().id(deletedTraceId).build();
        var sparedTrace = newTrace().id(sparedTraceId).build();
        traceResourceClient.createTrace(deletedTrace, API_KEY, WORKSPACE_NAME);
        traceResourceClient.createTrace(sparedTrace, API_KEY_2, WORKSPACE_NAME_2);

        var deletedSpan = newSpan(deletedTrace.projectName(), deletedTraceId).build();
        var sparedSpan = newSpan(sparedTrace.projectName(), sparedTraceId).build();
        spanResourceClient.createSpan(deletedSpan, API_KEY, WORKSPACE_NAME);
        spanResourceClient.createSpan(sparedSpan, API_KEY_2, WORKSPACE_NAME_2);
        assertThat(spanIdsOf(deletedTrace, API_KEY, WORKSPACE_NAME)).contains(deletedSpan.id());
        assertThat(spanIdsOf(sparedTrace, API_KEY_2, WORKSPACE_NAME_2)).contains(sparedSpan.id());

        spanDAO.deleteForRetentionBounded(
                Map.of(WORKSPACE_ID, floor, WORKSPACE_ID_2, aboveTheSpared),
                cutoff, floor).block();

        assertThat(spanIdsOf(deletedTrace, API_KEY, WORKSPACE_NAME))
                .as("its workspace's floor sits below this span's trace_id, so it is in range")
                .doesNotContain(deletedSpan.id());
        assertThat(spanIdsOf(sparedTrace, API_KEY_2, WORKSPACE_NAME_2))
                .as("its workspace's floor sits above this span's trace_id, so the same statement must spare it")
                .contains(sparedSpan.id());
    }

    /**
     * Both sweeps spare a span whose {@code trace_id} appears in {@code experiment_items}: experiment-linked data is
     * excluded from retention by design, and a lightweight delete is not recoverable.
     * <p>
     * This is the one clause in either sweep whose breakage is <b>silent</b>. A wrong table name fails loudly (the
     * routing guard, or code 36/60 at the server) and an unbound parameter fails at execution, but a {@code NOT IN}
     * that stops excluding renders, executes and reports success while deleting rows retention exists to keep. So the
     * spared span is asserted alongside a sibling that <b>is</b> deleted from the same window — asserting survival
     * alone would also pass if the sweep had quietly deleted nothing at all.
     */
    @Test
    void deleteForRetentionSparesSpansWhoseTraceIsLinkedToAnExperiment() {
        var fixture = seedExperimentExclusionFixture();

        spanDAO.deleteForRetention(List.of(WORKSPACE_ID), fixture.cutoff(), fixture.floor()).block();

        assertExperimentLinkedSpanSurvived(fixture);
    }

    @Test
    void deleteForRetentionBoundedSparesSpansWhoseTraceIsLinkedToAnExperiment() {
        var fixture = seedExperimentExclusionFixture();

        spanDAO.deleteForRetentionBounded(Map.of(WORKSPACE_ID, fixture.floor()), fixture.cutoff(), fixture.floor())
                .block();

        assertExperimentLinkedSpanSurvived(fixture);
    }

    /**
     * The guard that keeps the deletes above honest: pre-cutover {@code spans} must be the local
     * {@code ReplicatedReplacingMergeTree} and {@code spans_local} must not exist, so a mutation routed to the shard
     * could not have silently succeeded.
     */
    @Test
    void spansIsTheReplicatedReplacingMergeTreePreCutover() {
        // Pinned, not merely "not Distributed": the helper returns "" for a missing table and any other engine
        // (Memory, a plain MergeTree) would have satisfied a negative check, so an absent or wrong table passed.
        assertThat(engineOf("spans"))
                .as("pre-cutover `spans` must be the live ReplicatedReplacingMergeTree")
                .isEqualTo("ReplicatedReplacingMergeTree");
        assertThat(engineOf("spans_local"))
                .as("`spans_local` is created by the cutover runbook and must not exist pre-cutover")
                .isEmpty();
    }

    private Trace.TraceBuilder newTrace() {
        return factory.manufacturePojo(Trace.class).toBuilder()
                .feedbackScores(null)
                .usage(null);
    }

    private Span.SpanBuilder newSpan(String projectName, UUID traceId) {
        return factory.manufacturePojo(Span.class).toBuilder()
                .projectName(projectName)
                .traceId(traceId)
                .feedbackScores(null);
    }

    /**
     * A trace carrying exactly one span, both keyed on {@code id}. The insert and the read-back both route through
     * {@code spans}, so the assertion here is also what shows that path intact on whichever topology the suite runs.
     */
    private Trace seedTraceWithSpanAt(UUID id) {
        var trace = newTrace().id(id).build();
        traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);
        var span = newSpan(trace.projectName(), id).build();
        spanResourceClient.createSpan(span, API_KEY, WORKSPACE_NAME);
        assertThat(spanIdsOf(trace, API_KEY, WORKSPACE_NAME)).containsExactly(span.id());
        return trace;
    }

    private UUID createSpanOn(Trace trace) {
        var span = newSpan(trace.projectName(), trace.id()).build();
        spanResourceClient.createSpan(span, API_KEY, WORKSPACE_NAME);
        return span.id();
    }

    /**
     * Two traces in one retention window, each with a span, one of them linked to an experiment. Both trace ids are
     * minted at the same instant, so only the experiment link can separate their spans' fates.
     */
    private ExperimentExclusionFixture seedExperimentExclusionFixture() {
        var now = Instant.now();
        var fixture = ExperimentExclusionFixture.builder()
                .floor(ID_GENERATOR.generateId(now.minusSeconds(1)))
                .cutoff(ID_GENERATOR.generateId(now.plusSeconds(1)))
                .linkedTrace(newTrace().id(ID_GENERATOR.generateId(now)).build())
                .unlinkedTrace(newTrace().id(ID_GENERATOR.generateId(now)).build())
                .build();

        traceResourceClient.createTrace(fixture.linkedTrace(), API_KEY, WORKSPACE_NAME);
        traceResourceClient.createTrace(fixture.unlinkedTrace(), API_KEY, WORKSPACE_NAME);

        var linked = fixture.toBuilder()
                .linkedSpan(createSpanOn(fixture.linkedTrace()))
                .unlinkedSpan(createSpanOn(fixture.unlinkedTrace()))
                .build();

        // The experiment and dataset item need not exist: the insert only validates the ids are UUIDv7 and resolves the
        // project, which is all the sweeps' `trace_id NOT IN (SELECT trace_id FROM experiment_items ...)` reads.
        var experimentItem = factory.manufacturePojo(ExperimentItem.class).toBuilder()
                .traceId(linked.linkedTrace().id())
                .projectName(linked.linkedTrace().projectName())
                .feedbackScores(null)
                .comments(null)
                .build();
        experimentResourceClient.createExperimentItem(Set.of(experimentItem), API_KEY, WORKSPACE_NAME);

        assertThat(spanIdsOf(linked.linkedTrace(), API_KEY, WORKSPACE_NAME)).contains(linked.linkedSpan());
        assertThat(spanIdsOf(linked.unlinkedTrace(), API_KEY, WORKSPACE_NAME)).contains(linked.unlinkedSpan());
        return linked;
    }

    private List<UUID> spanIdsOf(Trace trace, String apiKey, String workspaceName) {
        return spanResourceClient
                .getByTraceIdAndProject(trace.id(), trace.projectName(), workspaceName, apiKey)
                .content().stream()
                .map(Span::id)
                .toList();
    }

    /** The cascade runs on the AsyncEventBus after the trace delete returns, so its outcome is polled. */
    private void awaitNoSpansOn(Trace trace) {
        Awaitility.await()
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(spanIdsOf(trace, API_KEY, WORKSPACE_NAME)).isEmpty());
    }

    private void assertExperimentLinkedSpanSurvived(ExperimentExclusionFixture fixture) {
        assertThat(spanIdsOf(fixture.unlinkedTrace(), API_KEY, WORKSPACE_NAME))
                .as("a span in the window whose trace has no experiment link must be swept")
                .doesNotContain(fixture.unlinkedSpan());
        assertThat(spanIdsOf(fixture.linkedTrace(), API_KEY, WORKSPACE_NAME))
                .as("the experiment_items exclusion must spare this span from the same sweep")
                .contains(fixture.linkedSpan());
    }

    private String engineOf(String table) {
        return template.nonTransaction(connection -> {
            var statement = connection.createStatement("""
                    SELECT engine FROM system.tables WHERE database = :database AND name = :table
                    SETTINGS log_comment = 'spans_unwrapped_mutation_test:engine_of'
                    """)
                    .bind("database", DATABASE_NAME)
                    .bind("table", table);
            return Mono.from(statement.execute())
                    .flatMap(result -> Mono.from(result.map((row, meta) -> row.get("engine", String.class))))
                    .defaultIfEmpty("");
        }).block();
    }

    /** Mirrors {@code SpansDistributedWrapMutationTest.RetentionWindow}; see its Javadoc for the ±1s rationale. */
    @Builder(toBuilder = true)
    private record RetentionWindow(UUID lowerBound, UUID middleId, UUID cutoffId) {
        private static RetentionWindow aroundNow() {
            var now = Instant.now();
            return RetentionWindow.builder()
                    .lowerBound(ID_GENERATOR.generateId(now.minusSeconds(1)))
                    .middleId(ID_GENERATOR.generateId(now))
                    .cutoffId(ID_GENERATOR.generateId(now.plusSeconds(1)))
                    .build();
        }
    }

    @Builder(toBuilder = true)
    private record ExperimentExclusionFixture(Trace linkedTrace, UUID linkedSpan, Trace unlinkedTrace,
            UUID unlinkedSpan, UUID floor, UUID cutoff) {
    }
}
