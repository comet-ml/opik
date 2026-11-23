package com.comet.opik.domain;

import com.comet.opik.api.GuardrailTrainingRun;
import com.comet.opik.api.GuardrailTrainingRunStatus;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.planshape.CapturingSqlLogger;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.SqlStatements;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.mysql.MySQLContainer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reads of {@link GuardrailTrainingRunDAO} that go through a secondary index use the one the design's "Operations
 * and indexes" table names for them. The optimizer chooses by table statistics, so the table is seeded with many
 * workspaces, models and finished runs and analysed first, on a container of this class's own.
 *
 * <p>A single row read by primary key shows in a plan without its table: MySQL fetches it while optimising. The writes
 * are left out, since the v2 EXPLAIN format the capture uses cannot describe them; they are by primary key or by
 * model.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Guardrail training query plans")
class GuardrailTrainingQueryPlanTest {

    private static final String RUNS = "guardrail_training_runs";
    private static final String PRIMARY = "PRIMARY";
    private static final String RUNS_MODEL = "guardrail_training_runs_model_status_created_idx";
    private static final String RUNS_STATUS = "guardrail_training_runs_status_last_updated_idx";

    private static final int WORKSPACES = 40;
    private static final int MODELS_PER_WORKSPACE = 10;
    private static final int RUNS_PER_MODEL = 12;
    private static final String USER = "user";
    private static final JsonNode METADATA = JsonUtils.getJsonNodeFromString("{\"files\": {\"layout\": \"v1\"}}");
    private static final List<GuardrailTrainingRunStatus> FINISHED = List.of(GuardrailTrainingRunStatus.COMPLETED,
            GuardrailTrainingRunStatus.FAILED, GuardrailTrainingRunStatus.CANCELLED);
    private static final TimeBasedEpochGenerator ID_GENERATOR = Generators.timeBasedEpochGenerator();

    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer(false);
    private Jdbi jdbi;

    private String workspaceId;
    private UUID modelId;
    private List<UUID> modelRunIds;
    private GuardrailTrainingRun runInFlight;

    @BeforeAll
    void setUp() {
        mysql.start();
        MigrationUtils.runMysqlDbMigration(mysql);
        jdbi = Jdbi.create(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .installPlugin(new SqlObjectPlugin());
        seed();
        var runs = jdbi.onDemand(GuardrailTrainingRunDAO.class);
        runInFlight = runs.findInFlight(modelId, workspaceId).orElseThrow();
        modelRunIds = runs.findIdsByModel(modelId, workspaceId);
    }

    @AfterAll
    void tearDown() {
        mysql.stop();
    }

    /**
     * Each workspace: models whose runs are mostly finished, the first model with its last run in flight. The reads
     * under test are for the first model of the first workspace.
     */
    private void seed() {
        var start = Instant.now().minus(30, ChronoUnit.DAYS);
        jdbi.useHandle(handle -> {
            var runs = handle.prepareBatch("""
                    INSERT INTO guardrail_training_runs(id, model_id, workspace_id, status, attempt, metadata,
                        created_at, created_by, last_updated_at, last_updated_by)
                    VALUES (:id, :modelId, :workspaceId, :status, :attempt, :metadata, :createdAt, :user,
                        :createdAt, :user)
                    """);
            for (int workspace = 0; workspace < WORKSPACES; workspace++) {
                var workspaceIdentifier = UUID.randomUUID().toString();
                var modelIds = new ArrayList<UUID>();
                for (int model = 0; model < MODELS_PER_WORKSPACE; model++) {
                    var modelIdentifier = ID_GENERATOR.generate();
                    modelIds.add(modelIdentifier);
                    for (int run = 0; run < RUNS_PER_MODEL; run++) {
                        var status = model == 0 && run == RUNS_PER_MODEL - 1
                                ? GuardrailTrainingRunStatus.RUNNING
                                : FINISHED.get(run % FINISHED.size());
                        runs.bind("id", ID_GENERATOR.generate().toString())
                                .bind("modelId", modelIdentifier.toString())
                                .bind("workspaceId", workspaceIdentifier).bind("status", status.name())
                                .bind("attempt", 1).bind("metadata", METADATA.toString())
                                .bind("createdAt", start.plus(run, ChronoUnit.HOURS)).bind("user", USER)
                                .add();
                    }
                }
                if (workspace == 0) {
                    workspaceId = workspaceIdentifier;
                    modelId = modelIds.getFirst();
                }
            }
            runs.execute();
            handle.execute("ANALYZE TABLE guardrail_training_runs");
        });
    }

    private record Read(String name, Consumer<GuardrailTrainingRunDAO> call, Map<String, Set<String>> indexes) {
        @Override
        public String toString() {
            return name;
        }
    }

    Stream<Arguments> reads() {
        var modelRuns = Map.of(RUNS, Set.of(RUNS_MODEL));
        var runsInFlight = Map.of(RUNS, Set.of(RUNS_STATUS));
        return Stream.of(
                new Read("a page's runs", dao -> dao.findByIds(modelRunIds, workspaceId),
                        Map.of(RUNS, Set.of(PRIMARY))),
                new Read("serving run", dao -> dao.findServingRun(modelId, workspaceId), modelRuns),
                new Read("run in flight", dao -> dao.findInFlight(modelId, workspaceId), modelRuns),
                new Read("previous attempt",
                        dao -> dao.findPreviousAttempt(runInFlight.toBuilder().attempt(2).build()), modelRuns),
                new Read("run history", dao -> dao.findByModel(modelId, workspaceId, 10, 0), modelRuns),
                new Read("run history count", dao -> dao.countByModel(modelId, workspaceId), modelRuns),
                new Read("a model's run ids", dao -> dao.findIdsByModel(modelId, workspaceId), modelRuns),
                new Read("worker scan", dao -> dao.findAllInFlight(100), runsInFlight),
                new Read("workspace's runs in flight", dao -> dao.countInFlight(workspaceId), runsInFlight))
                .map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reads")
    @DisplayName("Each read goes through the index named for it")
    void readUsesItsIndex(Read read) {
        var logger = new CapturingSqlLogger();
        jdbi.useHandle(handle -> {
            handle.getConfig(SqlStatements.class).setSqlLogger(logger);
            read.call().accept(handle.attach(GuardrailTrainingRunDAO.class));
        });

        var captured = logger.captured();
        assertThat(captured.failedSql()).as("statements whose EXPLAIN failed").isEmpty();
        assertThat(captured.plansByRenderedSql()).as("captured plans").isNotEmpty();

        var usedByTable = captured.plansByRenderedSql().values().stream()
                .flatMap(plan -> tableAccesses(JsonUtils.getJsonNodeFromString(plan)).stream())
                .collect(Collectors.groupingBy(TableAccess::table,
                        Collectors.mapping(TableAccess::index, Collectors.toSet())));

        var plans = String.join("\n", captured.plansByRenderedSql().values());
        assertThat(usedByTable)
                .as("indexes per table, from the plans:%n%s", plans)
                .containsOnlyKeys(read.indexes().keySet());
        usedByTable.forEach((table, used) -> assertThat(read.indexes().get(table))
                .as("the indexes allowed on %s, from the plans:%n%s", table, plans)
                .containsAll(used));
    }

    private record TableAccess(String table, String index) {
    }

    /** The guardrail tables a v2 plan reads, each with the index it reads through, or "table scan". */
    private static List<TableAccess> tableAccesses(JsonNode node) {
        var accesses = new ArrayList<TableAccess>();
        collectTableAccesses(node, accesses);
        return accesses;
    }

    private static void collectTableAccesses(JsonNode node, List<TableAccess> accesses) {
        if (node.isObject()) {
            var table = node.path("table_name").asText(null);
            if (table != null && table.startsWith("guardrail_")) {
                var index = "table".equals(node.path("access_type").asText(null))
                        ? "table scan"
                        : node.path("index_name").asText("no index: " + node.path("operation").asText());
                accesses.add(new TableAccess(table, index));
            }
            node.forEach(child -> collectTableAccesses(child, accesses));
        } else if (node.isArray()) {
            node.forEach(child -> collectTableAccesses(child, accesses));
        }
    }
}
