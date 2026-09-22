package com.comet.opik.infrastructure;

import com.comet.opik.api.ExperimentItem;
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
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.TraceDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import lombok.Builder;
import lombok.NonNull;
import org.apache.commons.lang3.RandomStringUtils;
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
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pre-cutover counterpart of {@link TracesDistributedWrapMutationTest}: the same {@code TraceDAO} mutation paths
 * with {@code databaseAnalyticsDataModel.tracesDistributedWrapEnabled} left at its default {@code false}, where
 * {@code traces} is still the {@code ReplicatedReplacingMergeTree} and {@code traces_local} does not exist at
 * all.
 *
 * <p><b>Why both halves are needed.</b> {@code TraceDAOImpl#tracesMutationTable()} decides between two names from one
 * flag, so a routing test that only exercises the wrapped topology leaves the other branch unverified — an
 * implementation that sent unwrapped mutations to {@code traces_local} would keep that suite green. Most trace paths
 * would fail loudly, since no {@code traces_local} table exists pre-cutover, but the two retention sweeps are the
 * exception: they have no public endpoint (retention is disabled everywhere and runs only from the internal
 * {@code RetentionCatchUpJob}), so nothing else in the repository calls
 * {@code deleteForRetentionBounded} at all. This suite closes that gap and pins both branches of the resolver.
 *
 * <p>{@link #tracesIsTheReplicatedReplacingMergeTreePreCutover} is the guard that keeps the positive tests honest, mirroring
 * {@code distributedTracesRejectsDirectMutation} in the wrapped suite: it proves the deletes above really ran against
 * the unwrapped {@code traces} rather than accidentally against a shard.
 *
 * <p>Dedicated, non-reused containers, as in the wrapped suite: nothing here renames a table, but the suite asserts
 * the pre-cutover topology as a precondition, so it has to own that topology rather than inherit it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TracesUnwrappedMutationTest {

    private static final String API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);

    // A second workspace, so the multi-workspace retention case can assert *selective* deletion rather than merely
    // rendering more template branches.
    private static final String API_KEY_2 = "apiKey-" + UUID.randomUUID();
    private static final String WORKSPACE_NAME_2 = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String WORKSPACE_ID_2 = UUID.randomUUID().toString();

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    // Dedicated, non-reused ClickHouse + ZooKeeper on their own network, matching TracesDistributedWrapMutationTest.
    // This suite asserts the pre-cutover topology as a *precondition*, so it must own the container rather than inherit
    // whatever state a shared one is in: reuse is enabled in CI, and a container left wrapped would fail this suite for
    // environmental reasons rather than real ones.
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
        // No custom config: tracesDistributedWrapEnabled stays at its default false, which is the whole point.
        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(mysqlContainer.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(redisContainer.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        .build());
    }

    private TraceResourceClient traceResourceClient;
    private ExperimentResourceClient experimentResourceClient;
    private TransactionTemplateAsync template;
    private TraceDAO traceDAO;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport, TransactionTemplateAsync template, TraceDAO traceDAO) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        mockTargetWorkspace(wireMock.server(), API_KEY_2, WORKSPACE_NAME_2, WORKSPACE_ID_2, USER);
        traceResourceClient = new TraceResourceClient(clientSupport, baseUrl);
        experimentResourceClient = new ExperimentResourceClient(clientSupport, baseUrl, factory);
        this.template = template;
        this.traceDAO = traceDAO;
    }

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
        clickHouseContainer.stop();
        zookeeperContainer.stop();
        network.close();
    }

    @Test
    void deleteByIdRemovesTraceFromTheUnwrappedTable() {
        var trace = newTrace().build();

        traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);
        assertThat(getTraceIds(trace.projectName())).contains(trace.id());

        traceResourceClient.deleteTrace(trace.id(), WORKSPACE_NAME, API_KEY);

        assertThat(getTraceIds(trace.projectName())).doesNotContain(trace.id());
    }

    @Test
    void deleteForRetentionRunsAgainstTheUnwrappedTable() {
        var window = RetentionWindow.aroundNow();
        var trace = newTrace().id(window.middleId()).build();
        traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);
        assertThat(getTraceIds(trace.projectName())).contains(trace.id());

        traceDAO.deleteForRetention(List.of(WORKSPACE_ID), window.cutoffId(), window.lowerBound()).block();

        assertThat(getTraceIds(trace.projectName())).doesNotContain(trace.id());
    }

    /**
     * The one mutation nothing else in the repository exercises, and whose SQL is assembled per workspace, so its
     * rendered form is only ever proven by running it.
     */
    @Test
    void deleteForRetentionBoundedRunsAgainstTheUnwrappedTable() {
        var window = RetentionWindow.aroundNow();
        var trace = newTrace().id(window.middleId()).build();
        traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);
        assertThat(getTraceIds(trace.projectName())).contains(trace.id());

        traceDAO.deleteForRetentionBounded(Map.of(WORKSPACE_ID, window.lowerBound()), window.cutoffId(),
                window.lowerBound()).block();

        assertThat(getTraceIds(trace.projectName())).doesNotContain(trace.id());
    }

    /**
     * The case the OR-ed per-workspace predicates exist for, asserting what they are actually for: each workspace
     * carries its <b>own</b> id floor, so the same call must delete in one workspace and spare the other.
     * <p>
     * Both traces sit inside the shared week window, so the {@code toMonday} bounds cannot be what separates them. The
     * only thing that can is the per-workspace {@code :lb_i}: the first workspace's floor sits below its trace, the
     * second's above its own. A broken separator, a mis-numbered bind, or a single shared floor all collapse this to
     * "delete both" or "delete neither".
     */
    @Test
    void deleteForRetentionBoundedAppliesPerWorkspaceLowerBounds() {
        var now = Instant.now();
        var floor = ID_GENERATOR.generateId(now.minusSeconds(2));
        var deletedId = ID_GENERATOR.generateId(now.minusSeconds(1));
        var sparedId = ID_GENERATOR.generateId(now.minusSeconds(1));
        var aboveTheSpared = ID_GENERATOR.generateId(now);
        var cutoff = ID_GENERATOR.generateId(now.plusSeconds(1));

        var deleted = newTrace().id(deletedId).build();
        var spared = newTrace().id(sparedId).build();
        traceResourceClient.createTrace(deleted, API_KEY, WORKSPACE_NAME);
        traceResourceClient.createTrace(spared, API_KEY_2, WORKSPACE_NAME_2);
        assertThat(getTraceIds(deleted.projectName(), API_KEY, WORKSPACE_NAME)).contains(deletedId);
        assertThat(getTraceIds(spared.projectName(), API_KEY_2, WORKSPACE_NAME_2)).contains(sparedId);

        traceDAO.deleteForRetentionBounded(
                Map.of(WORKSPACE_ID, floor, WORKSPACE_ID_2, aboveTheSpared),
                cutoff, floor).block();

        assertThat(getTraceIds(deleted.projectName(), API_KEY, WORKSPACE_NAME))
                .as("its workspace's floor sits below this trace, so it is in range")
                .doesNotContain(deletedId);
        assertThat(getTraceIds(spared.projectName(), API_KEY_2, WORKSPACE_NAME_2))
                .as("its workspace's floor sits above this trace, so the same statement must spare it")
                .contains(sparedId);
    }

    /**
     * Both sweeps spare a trace listed in {@code experiment_items}: experiment-linked data is excluded from
     * retention by design, and a lightweight delete is not recoverable.
     * <p>
     * This is the one clause in either sweep whose breakage is <b>silent</b>. A wrong table name fails loudly (the
     * routing guard, or code 36/60 at the server) and an unbound parameter fails at execution, but a {@code NOT IN}
     * that stops excluding renders, executes and reports success while deleting rows retention exists to keep. So the
     * spared trace is asserted alongside a sibling that <b>is</b> deleted from the same window — asserting survival
     * alone would also pass if the sweep had quietly deleted nothing at all.
     * <p>
     * Covered here rather than in {@code RetentionPolicyServiceTest}, which drives the same sweeps end-to-end: that
     * suite can only reach {@code applyToPast=true}, because the bounded path derives each workspace's floor from
     * {@code rule.createdAt()}, so a freshly created rule floors at {@code now} and sweeps nothing.
     */
    @Test
    void deleteForRetentionSparesTracesLinkedToAnExperiment() {
        var fixture = seedExperimentExclusionFixture();

        traceDAO.deleteForRetention(List.of(WORKSPACE_ID), fixture.cutoff(), fixture.floor()).block();

        assertExperimentLinkedTraceSurvived(fixture);
    }

    @Test
    void deleteForRetentionBoundedSparesTracesLinkedToAnExperiment() {
        var fixture = seedExperimentExclusionFixture();

        traceDAO.deleteForRetentionBounded(Map.of(WORKSPACE_ID, fixture.floor()), fixture.cutoff(), fixture.floor())
                .block();

        assertExperimentLinkedTraceSurvived(fixture);
    }

    /**
     * The guard that keeps the deletes above honest: pre-cutover {@code traces} must be the local
     * {@code ReplicatedReplacingMergeTree} and
     * {@code traces_local} must not exist, so a mutation routed to the shard could not have silently succeeded.
     */
    @Test
    void tracesIsTheReplicatedReplacingMergeTreePreCutover() {
        // Pinned, not merely "not Distributed": the helper returns "" for a missing table and any other engine
        // (Memory, a plain MergeTree) would have satisfied a negative check, so an absent or wrong table passed.
        assertThat(engineOf("traces"))
                .as("pre-cutover `traces` must be the live ReplicatedReplacingMergeTree")
                .isEqualTo("ReplicatedReplacingMergeTree");
        assertThat(engineOf("traces_local"))
                .as("`traces_local` is created by the cutover runbook and must not exist pre-cutover")
                .isEmpty();
    }

    private Trace.TraceBuilder newTrace() {
        return factory.manufacturePojo(Trace.class).toBuilder()
                .feedbackScores(null)
                .usage(null);
    }

    /**
     * Two traces in one retention window, one of them linked to an experiment. Both ids are minted at the same
     * instant, so only the experiment link can separate their fates. The experiment and dataset item need not exist:
     * the item insert only validates the ids are UUIDv7 and resolves the project, which is all the sweeps'
     * {@code id NOT IN (SELECT trace_id FROM experiment_items ...)} reads.
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

        var experimentItem = factory.manufacturePojo(ExperimentItem.class).toBuilder()
                .traceId(fixture.linkedTrace().id())
                .projectName(fixture.linkedTrace().projectName())
                .feedbackScores(null)
                .comments(null)
                .build();
        experimentResourceClient.createExperimentItem(Set.of(experimentItem), API_KEY, WORKSPACE_NAME);

        assertThat(getTraceIds(fixture.linkedTrace().projectName())).contains(fixture.linkedTrace().id());
        assertThat(getTraceIds(fixture.unlinkedTrace().projectName())).contains(fixture.unlinkedTrace().id());
        return fixture;
    }

    private List<UUID> getTraceIds(String projectName) {
        return getTraceIds(projectName, API_KEY, WORKSPACE_NAME);
    }

    private List<UUID> getTraceIds(String projectName, String apiKey, String workspaceName) {
        return traceResourceClient
                .getTraces(projectName, null, apiKey, workspaceName, List.of(), List.of(), 100, Map.of())
                .content().stream()
                .map(Trace::id)
                .toList();
    }

    private void assertExperimentLinkedTraceSurvived(ExperimentExclusionFixture fixture) {
        assertThat(getTraceIds(fixture.unlinkedTrace().projectName()))
                .as("a trace in the window with no experiment link must be swept")
                .doesNotContain(fixture.unlinkedTrace().id());
        assertThat(getTraceIds(fixture.linkedTrace().projectName()))
                .as("the experiment_items exclusion must spare this trace from the same sweep")
                .contains(fixture.linkedTrace().id());
    }

    private String engineOf(String table) {
        return template.nonTransaction(connection -> {
            var statement = connection.createStatement("""
                    SELECT engine FROM system.tables WHERE database = :database AND name = :table
                    SETTINGS log_comment = 'traces_unwrapped_mutation_test:engine_of'
                    """)
                    .bind("database", DATABASE_NAME)
                    .bind("table", table);
            return Mono.from(statement.execute())
                    .flatMap(result -> Mono.from(result.map((row, meta) -> row.get("engine", String.class))))
                    .defaultIfEmpty("");
        }).block();
    }

    /** Mirrors {@code TracesDistributedWrapMutationTest.RetentionWindow}; see its Javadoc for the ±1s rationale. */
    @Builder(toBuilder = true)
    private record RetentionWindow(@NonNull UUID lowerBound, @NonNull UUID middleId, @NonNull UUID cutoffId) {
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
    private record ExperimentExclusionFixture(Trace linkedTrace, Trace unlinkedTrace, UUID floor, UUID cutoff) {
    }
}
