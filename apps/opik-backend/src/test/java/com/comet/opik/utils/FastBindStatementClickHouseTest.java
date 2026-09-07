package com.comet.opik.utils;

import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
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
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The unit tests bind against a fake whose {@code namedParameters} field is our own model of the
 * driver's. That cannot detect a wrong model: if the real field is ordered differently, every unit
 * test still passes and rows are written into the wrong columns, silently.
 *
 * <p>This test binds by name through the real ClickHouse driver and reads the rows back, so the
 * reflection assumption is checked against the driver itself rather than against our copy of it.
 *
 * <p>It also pins that the wrapper engages at all: no statement in the existing integration suite
 * reaches {@code MIN_PARAMETERS}, so before this test the real-driver path ran in no test.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FastBindStatementClickHouseTest {

    /** 22 rows x 3 columns = 66 distinct names, clear of the 64-parameter threshold. */
    private static final int ROWS = 22;
    private static final String TABLE = "fastbind_probe";

    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer();

    private ConnectionFactory connectionFactory;

    @BeforeAll
    void beforeAll() {
        Startables.deepStart(clickHouse).join();
        // "default" always exists, so this needs no migrations.
        connectionFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouse, "default").build();

        execute("CREATE TABLE IF NOT EXISTS " + TABLE
                + " (id Int32, name String, value String) ENGINE = MergeTree ORDER BY id");
        execute("TRUNCATE TABLE " + TABLE);
    }

    @AfterAll
    void afterAll() {
        if (connectionFactory != null) {
            execute("DROP TABLE IF EXISTS " + TABLE);
        }
    }

    @Test
    void bindingByNameThroughTheRealDriver_landsEveryValueInItsOwnColumn() {
        String sql = "INSERT INTO " + TABLE + " (id, name, value) VALUES "
                + IntStream.range(0, ROWS)
                        .mapToObj(i -> "(:id%d, :name%d, :value%d)".formatted(i, i, i))
                        .reduce((a, b) -> a + ", " + b)
                        .orElseThrow();

        Mono.usingWhen(
                Mono.from(connectionFactory.create()),
                connection -> {
                    Statement raw = connection.createStatement(sql);
                    Statement wrapped = FastBindStatement.wrap(raw);

                    // If the driver's field cannot be read the wrapper declines to wrap. Asserting
                    // this is the point of the test: it is what the fake cannot tell us.
                    assertThat(wrapped)
                            .as("positional binding must engage on the real driver statement")
                            .isNotSameAs(raw);

                    for (int i = 0; i < ROWS; i++) {
                        wrapped.bind("id" + i, i);
                        wrapped.bind("name" + i, "n" + i);
                        wrapped.bind("value" + i, "v" + i);
                    }
                    return Flux.from(wrapped.execute()).then();
                },
                Connection::close)
                .block();

        var rows = query("SELECT id, name, value FROM " + TABLE + " ORDER BY id");

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
