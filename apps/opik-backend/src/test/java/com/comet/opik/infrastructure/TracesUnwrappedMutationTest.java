package com.comet.opik.infrastructure;

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
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.AuthTestUtils.mockTargetWorkspace;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pre-cutover counterpart of {@link TracesDistributedWrapMutationTest}: the same {@code TraceDAO} mutation paths
 * with {@code databaseAnalyticsDataModel.tracesDistributedWrapEnabled} left at its default {@code false}, where
 * {@code traces} is still a plain {@code MergeTree} and {@code traces_local} does not exist at all.
 *
 * <p><b>Why both halves are needed.</b> {@code TraceDAOImpl#tracesMutationTable()} decides between two names from one
 * flag, so a routing test that only exercises the wrapped topology leaves the other branch unverified — an
 * implementation that sent unwrapped mutations to {@code traces_local} would keep that suite green. Most trace paths
 * would fail loudly, since no {@code traces_local} table exists pre-cutover, but the two retention sweeps are the
 * exception: they have no public endpoint (retention is disabled everywhere and runs only from the internal
 * {@code RetentionCatchUpJob}), so nothing else in the repository calls
 * {@code deleteForRetentionBounded} at all. This suite closes that gap and pins both branches of the resolver.
 *
 * <p>{@link #tracesIsAPlainMergeTreePreCutover} is the guard that keeps the positive tests honest, mirroring
 * {@code distributedTracesRejectsDirectMutation} in the wrapped suite: it proves the deletes above really ran against
 * an unwrapped {@code traces} rather than accidentally against a shard.
 *
 * <p>Reusable containers are fine here — unlike the wrapped suite, nothing in this one renames or drops a table.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class TracesUnwrappedMutationTest {

    private static final String API_KEY = "apiKey-" + UUID.randomUUID();
    private static final String WORKSPACE_NAME = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(32);

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(zookeeperContainer);
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
    private TransactionTemplateAsync template;
    private TraceDAO traceDAO;

    @BeforeAll
    void beforeAll(ClientSupport clientSupport, TransactionTemplateAsync template, TraceDAO traceDAO) {
        var baseUrl = TestUtils.getBaseUrl(clientSupport);
        ClientSupportUtils.config(clientSupport);
        mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);
        traceResourceClient = new TraceResourceClient(clientSupport, baseUrl);
        this.template = template;
        this.traceDAO = traceDAO;
    }

    @AfterAll
    void afterAll() {
        wireMock.server().stop();
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
     * Multiple workspaces in one call, which is the case the OR-ed per-workspace predicates exist for: a single
     * statement whose arity varies with the input. One workspace alone would render only the first branch of the
     * template loop and never its separator.
     */
    @Test
    void deleteForRetentionBoundedSpansSeveralWorkspaces() {
        var window = RetentionWindow.aroundNow();
        var trace = newTrace().id(window.middleId()).build();
        traceResourceClient.createTrace(trace, API_KEY, WORKSPACE_NAME);
        assertThat(getTraceIds(trace.projectName())).contains(trace.id());

        // The extra workspaces hold no traces; they are here to force the multi-branch rendering.
        traceDAO.deleteForRetentionBounded(
                Map.of(WORKSPACE_ID, window.lowerBound(),
                        UUID.randomUUID().toString(), window.lowerBound(),
                        UUID.randomUUID().toString(), window.lowerBound()),
                window.cutoffId(), window.lowerBound()).block();

        assertThat(getTraceIds(trace.projectName())).doesNotContain(trace.id());
    }

    /**
     * The guard that keeps the deletes above honest: pre-cutover {@code traces} must be a local {@code MergeTree} and
     * {@code traces_local} must not exist, so a mutation routed to the shard could not have silently succeeded.
     */
    @Test
    void tracesIsAPlainMergeTreePreCutover() {
        assertThat(engineOf("traces"))
                .as("pre-cutover `traces` is the live MergeTree, not a Distributed wrapper")
                .doesNotContain("Distributed");
        assertThat(engineOf("traces_local"))
                .as("`traces_local` is created by the cutover runbook and must not exist pre-cutover")
                .isEmpty();
    }

    private String engineOf(String table) {
        return template.nonTransaction(connection -> {
            var statement = connection.createStatement(
                    "SELECT engine FROM system.tables WHERE database = :database AND name = :table")
                    .bind("database", DATABASE_NAME)
                    .bind("table", table);
            return Mono.from(statement.execute())
                    .flatMap(result -> Mono.from(result.map((row, meta) -> row.get("engine", String.class))))
                    .defaultIfEmpty("");
        }).block();
    }

    /** Mirrors {@code TracesDistributedWrapMutationTest.RetentionWindow}; see its Javadoc for the ±1s rationale. */
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

    private Trace.TraceBuilder newTrace() {
        return factory.manufacturePojo(Trace.class).toBuilder()
                .feedbackScores(null)
                .usage(null);
    }

    private List<UUID> getTraceIds(String projectName) {
        return traceResourceClient
                .getTraces(projectName, null, API_KEY, WORKSPACE_NAME, List.of(), List.of(), 100, Map.of())
                .content().stream()
                .map(Trace::id)
                .toList();
    }
}
