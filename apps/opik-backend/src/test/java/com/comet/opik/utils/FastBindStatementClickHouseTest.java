package com.comet.opik.utils;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.utils.template.TemplateUtils;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.instrumentation.r2dbc.v1_0.R2dbcTelemetry;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.lifecycle.Startables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The unit tests bind against a fake whose {@code namedParameters} field is our own model of the
 * driver's. That cannot detect a wrong model: if the real field were ordered differently, every
 * unit test would still pass while rows went into the wrong columns.
 *
 * <p>This test goes through the production composition - the same {@code FastBindConnectionFactory}
 * over a telemetry-wrapped factory that {@code DatabaseAnalyticsModule} builds - so it covers the
 * wiring and the proxy peeling too, not only the reflection. It binds by name against a real
 * ClickHouse and reads the rows back.
 *
 * <p>It also pins that the wrapper engages at all: no statement in the rest of the suite reaches
 * {@code MIN_PARAMETERS}, so before this test the real-driver path ran in no test.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FastBindStatementClickHouseTest {

    /** 22 rows x 3 columns = 66 distinct names, clear of the 64-parameter threshold. */
    private static final int ROWS = 22;

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS fastbind_probe (
                id Int32,
                name String,
                value String
            ) ENGINE = MergeTree ORDER BY id
            """;

    private static final String TRUNCATE_TABLE = "TRUNCATE TABLE fastbind_probe";

    private static final String DROP_TABLE = "DROP TABLE IF EXISTS fastbind_probe";

    private static final String SELECT_ALL = """
            SELECT id, name, value FROM fastbind_probe ORDER BY id
            """;

    private static final String BULK_INSERT = """
            INSERT INTO fastbind_probe (id, name, value) VALUES
            <items:{item |
                (
                    :id<item.index>,
                    :name<item.index>,
                    :value<item.index>
                )
                <if(item.hasNext)>
                   ,
                <endif>
            }>
            """;

    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer();

    private ConnectionFactory connectionFactory;

    @BeforeAll
    void beforeAll() {
        Startables.deepStart(clickHouse).join();

        // The production composition from DatabaseAnalyticsModule: FastBindConnectionFactory over
        // the telemetry-wrapped factory. Going through it means createStatement exercises the real
        // wiring, and the telemetry proxy exercises the wrapper's unwrap path. "default" always
        // exists, so no migrations are needed.
        connectionFactory = new FastBindConnectionFactory(
                R2dbcTelemetry.create(GlobalOpenTelemetry.get())
                        .wrapConnectionFactory(
                                ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouse, "default").build(),
                                ConnectionFactoryOptions.builder().build()));

        execute(CREATE_TABLE);
        execute(TRUNCATE_TABLE);
    }

    @AfterAll
    void afterAll() {
        if (connectionFactory != null) {
            execute(DROP_TABLE);
        }
    }

    @Test
    void bindingByNameThroughTheRealDriver_landsEveryValueInItsOwnColumn() {
        String sql = TemplateUtils.getBatchSql(BULK_INSERT, ROWS).render();

        Mono.usingWhen(
                Mono.from(connectionFactory.create()),
                connection -> {
                    Statement statement = connection.createStatement(sql);

                    // The production chain must have wrapped it. If the driver's field cannot be
                    // read, or the factory stops wrapping, binding silently reverts to the driver's
                    // O(n^2) scan and nothing else fails - this is what catches that.
                    assertThat(statement)
                            .as("the production connection factory must return a wrapped statement")
                            .isInstanceOf(FastBindStatement.class);

                    for (int i = 0; i < ROWS; i++) {
                        statement.bind("id" + i, i);
                        statement.bind("name" + i, "n" + i);
                        statement.bind("value" + i, "v" + i);
                    }
                    return Flux.from(statement.execute()).then();
                },
                Connection::close)
                .block();

        var rows = query(SELECT_ALL);

        assertThat(rows).hasSize(ROWS);
        for (int i = 0; i < ROWS; i++) {
            // A misaligned index would still type-check and still insert - it would just put these
            // values somewhere else. Asserting the triple per row is what catches that.
            assertThat(rows.get(i)).containsExactly(String.valueOf(i), "n" + i, "v" + i);
        }
    }

    private void execute(String sql) {
        Mono.usingWhen(
                Mono.from(connectionFactory.create()),
                connection -> Flux.from(connection.createStatement(sql).execute())
                        .flatMap(Result::getRowsUpdated)
                        .then(),
                Connection::close)
                .block();
    }

    private List<List<String>> query(String sql) {
        return Mono.usingWhen(
                Mono.from(connectionFactory.create()),
                connection -> Flux.from(connection.createStatement(sql).execute())
                        .flatMap(result -> result.map((row, metadata) -> {
                            List<String> values = new ArrayList<>();
                            values.add(String.valueOf(row.get("id", Integer.class)));
                            values.add(row.get("name", String.class));
                            values.add(row.get("value", String.class));
                            return values;
                        }))
                        .collectList(),
                Connection::close)
                .block();
    }
}
