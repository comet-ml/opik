package com.comet.opik.infrastructure.db;

import com.clickhouse.client.api.ClickHouseException;
import com.clickhouse.client.api.Client;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dropwizard.jackson.Jackson;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.clickhouse.ClickHouseContainer;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JsonEachRowBulkInsert} through the real client against a real server, for what the client does with the
 * body writer: the unit test mocks the client, so it never runs the writer the way the client does.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JsonEachRowBulkInsertClickHouseTest {

    private static final ObjectMapper MAPPER = Jackson.newObjectMapper();

    private final ClickHouseContainer clickHouse = ClickHouseContainerUtils.newClickHouseContainer(false);

    private Client client;

    @BeforeAll
    void setUpAll() {
        clickHouse.start();
        client = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickHouse, "default").buildClient();
    }

    @AfterAll
    void tearDownAll() {
        client.close();
        clickHouse.stop();
    }

    private String newTable() {
        var table = "rows_%s".formatted(RandomStringUtils.secure().nextAlphabetic(12).toLowerCase());
        client.queryAll("CREATE TABLE %s (name String) ENGINE = MergeTree ORDER BY name".formatted(table));
        return table;
    }

    private long count(String table) {
        return client.queryAll("SELECT count() AS rows FROM %s".formatted(table)).getFirst().getLong("rows");
    }

    private static ObjectNode row(String name) {
        return JsonUtils.createObjectNode().put("name", name);
    }

    @Test
    @DisplayName("a row mapper failing mid-body fails the insert, wrapped by the client, and writes no row")
    void mapperFailureMidBodyFailsTheInsertAndWritesNothing() {
        var table = newTable();
        var items = List.of(RandomStringUtils.secure().nextAlphanumeric(32),
                RandomStringUtils.secure().nextAlphanumeric(32),
                RandomStringUtils.secure().nextAlphanumeric(32));
        var failure = new IllegalStateException(RandomStringUtils.secure().nextAlphanumeric(32));
        Function<String, ObjectNode> rowMapper = item -> {
            if (item.equals(items.get(1))) {
                throw failure;
            }
            return row(item);
        };

        // The client wraps what the writer throws, and does not retry it, so callers matching on exception types see
        // ClickHouseException rather than the mapper's own exception.
        assertThatThrownBy(() -> new JsonEachRowBulkInsert(client, MAPPER)
                .insert(table, "log-comment", items, rowMapper)
                .block())
                .isInstanceOf(ClickHouseException.class)
                .hasRootCause(failure);

        assertThat(count(table)).isZero();
    }

    @Test
    @DisplayName("rows written into the body reach the table")
    void writesTheRows() {
        var table = newTable();
        var items = List.of(RandomStringUtils.secure().nextAlphanumeric(32),
                RandomStringUtils.secure().nextAlphanumeric(32));

        Long written = new JsonEachRowBulkInsert(client, MAPPER)
                .insert(table, "log-comment", items, JsonEachRowBulkInsertClickHouseTest::row)
                .block();

        assertThat(written).isEqualTo(items.size());
        assertThat(count(table)).isEqualTo(items.size());
    }
}
