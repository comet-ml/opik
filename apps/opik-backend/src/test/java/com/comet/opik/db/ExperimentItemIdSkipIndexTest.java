package com.comet.opik.db;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.regex.Pattern;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks that a lookup by experiment item {@code id} prunes granules through the {@code minmax} skip index added in
 * migration 000151, on both tables that store experiment items. {@code id} is the last column of both sort keys, so
 * without the index such a lookup reads every granule of the workspace.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExperimentItemIdSkipIndexTest {

    private static final String WORKSPACE_ID = "skip-index-workspace";
    private static final int ROW_COUNT = 100_000;
    private static final int ROWS_PER_EXPERIMENT = 1_000;

    /**
     * Matches the granule counts reported for one skip index in a text {@code EXPLAIN indexes = 1} plan: the first
     * {@code Granules: selected/total} line after the index name, without crossing into the next index's block.
     */
    private static final String SKIP_INDEX_GRANULES = "Name: %s\\n(?:(?!Name: ).*\\n)*?Granules: (\\d+)/(\\d+)";

    private final Network network = Network.newNetwork();
    private final GenericContainer<?> zookeeper = ClickHouseContainerUtils.newZookeeperContainer(false, network);
    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer(false, network,
            zookeeper);

    private Connection connection;

    {
        Startables.deepStart(zookeeper, clickHouse).join();
    }

    @BeforeAll
    void setUp() throws Exception {
        MigrationUtils.runClickhouseDbMigration(clickHouse);
        connection = clickHouse.createConnection("");
    }

    @AfterAll
    void tearDown() throws Exception {
        try {
            if (connection != null) {
                connection.close();
            }
        } finally {
            clickHouse.stop();
            zookeeper.stop();
        }
    }

    @ParameterizedTest
    @CsvSource({"experiment_items, idx_experiment_items_id",
            "experiment_item_aggregates, idx_experiment_item_aggregates_id"})
    void lookupByIdReadsOnlyTheGranulesThatHoldTheId(String table, String index) throws SQLException {
        insertRows(table);
        var lookedUpId = uuidFor(ROW_COUNT / 2);

        var granules = skipIndexGranules(explain("""
                SELECT id FROM %s.%s
                WHERE workspace_id = '%s' AND id = '%s'
                """.formatted(DATABASE_NAME, table, WORKSPACE_ID, lookedUpId)), index);

        assertThat(granules.total())
                .as("granules the primary key leaves for the whole workspace")
                .isGreaterThan(10);
        assertThat(granules.selected())
                .as("granules left after %s on %s", index, table)
                .isBetween(1, 2);
    }

    /**
     * Ids increase along the sort key, as UUIDv7 ids do in production: experiments are created in order and their items
     * after them, so ids sorted by {@code (experiment_id, ...)} are close to sorted by {@code id}.
     */
    private void insertRows(String table) throws SQLException {
        execute("""
                INSERT INTO %s.%s (workspace_id, experiment_id, dataset_item_id, trace_id, id)
                SELECT '%s',
                       %s,
                       %s,
                       %s,
                       %s
                FROM numbers(%d)
                """.formatted(DATABASE_NAME, table, WORKSPACE_ID,
                uuidExpression("intDiv(number, %d)".formatted(ROWS_PER_EXPERIMENT)),
                uuidExpression("number"),
                uuidExpression("number"),
                uuidExpression("number"),
                ROW_COUNT));
    }

    /**
     * A UUIDv7-shaped string whose order follows {@code numberExpression}, varying only in the last 12 hex digits. The
     * id columns are {@code FixedString(36)}, so the value is inserted as text; zero-padding keeps text order equal to
     * numeric order.
     */
    private static String uuidExpression(String numberExpression) {
        return "concat('00000000-0000-7000-8000-', leftPad(lower(hex(%s)), 12, '0'))".formatted(numberExpression);
    }

    private static String uuidFor(long number) {
        return "00000000-0000-7000-8000-%012x".formatted(number);
    }

    private String explain(String query) throws SQLException {
        var lines = new ArrayList<String>();
        try (var statement = connection.createStatement();
                var resultSet = statement.executeQuery("EXPLAIN indexes = 1 " + query)) {
            while (resultSet.next()) {
                lines.add(resultSet.getString(1).strip());
            }
        }
        return String.join("\n", lines);
    }

    private static Granules skipIndexGranules(String plan, String index) {
        var matcher = Pattern.compile(SKIP_INDEX_GRANULES.formatted(Pattern.quote(index))).matcher(plan);
        assertThat(matcher.find())
                .as("plan uses skip index %s:%n%s", index, plan)
                .isTrue();
        return new Granules(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
    }

    private void execute(String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private record Granules(int selected, int total) {
    }
}
