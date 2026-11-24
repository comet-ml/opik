package com.comet.opik.infrastructure.db;

import com.clickhouse.client.api.ClickHouseException;
import com.clickhouse.client.api.Client;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dropwizard.jackson.Jackson;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

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

    /** Inserts three rows into {@code table} with a row mapper that throws {@code failure} on the second. */
    private Mono<Long> insertFailingOnSecondRow(String table, boolean streamPayloads, RuntimeException failure) {
        var items = List.of(RandomStringUtils.secure().nextAlphanumeric(32),
                RandomStringUtils.secure().nextAlphanumeric(32),
                RandomStringUtils.secure().nextAlphanumeric(32));
        Function<String, ObjectNode> rowMapper = item -> {
            if (item.equals(items.get(1))) {
                throw failure;
            }
            return row(item);
        };
        return new JsonEachRowBulkInsert(client, MAPPER, JsonEachRowBulkInsertTest.streamPayloads(streamPayloads))
                .insert(table, "log-comment", items, rowMapper);
    }

    @Test
    @DisplayName("streamed: a row mapper failing mid-body fails the insert with the client's exception and writes no row")
    void streamedMapperFailureMidBodyFailsTheInsertAndWritesNothing() {
        var table = newTable();
        var failure = new IllegalStateException(RandomStringUtils.secure().nextAlphanumeric(32));

        var insert = insertFailingOnSecondRow(table, true, failure);

        // The client wraps what the writer throws, and does not retry it, so callers matching on exception types
        // see ClickHouseException rather than the mapper's own exception.
        assertThatThrownBy(insert::block).isInstanceOf(ClickHouseException.class).hasRootCause(failure);
        assertThat(count(table)).isZero();
    }

    @Test
    @DisplayName("buffered: a row mapper failing mid-body fails the insert with the mapper's exception and writes no row")
    void bufferedMapperFailureMidBodyFailsTheInsertAndWritesNothing() {
        var table = newTable();
        var failure = new IllegalStateException(RandomStringUtils.secure().nextAlphanumeric(32));

        var insert = insertFailingOnSecondRow(table, false, failure);

        // The batch is built before the client is called, so the mapper's exception is what fails the insert.
        assertThatThrownBy(insert::block).isSameAs(failure);
        assertThat(count(table)).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("rows written into the body reach the table")
    void writesTheRows(boolean streamPayloads) {
        var table = newTable();
        var items = List.of(RandomStringUtils.secure().nextAlphanumeric(32),
                RandomStringUtils.secure().nextAlphanumeric(32));

        Long written = new JsonEachRowBulkInsert(client, MAPPER,
                JsonEachRowBulkInsertTest.streamPayloads(streamPayloads))
                .insert(table, "log-comment", items, JsonEachRowBulkInsertClickHouseTest::row)
                .block();

        assertThat(written).isEqualTo(items.size());
        assertThat(count(table)).isEqualTo(items.size());
    }

    static Stream<JsonNode> deepPayloads() {
        return JsonRowValuesTest.deepPayloads();
    }

    @ParameterizedTest
    @MethodSource("deepPayloads")
    @DisplayName("streamed: a payload as deep as a request body can be is stored verbatim, though its row is one level deeper")
    void streamedDeepestPayloadIsStored(JsonNode payload) {
        var table = "rows_%s".formatted(RandomStringUtils.secure().nextAlphabetic(12).toLowerCase());
        client.queryAll(
                "CREATE TABLE %s (name String, input String, input_slim String) ENGINE = MergeTree ORDER BY name"
                        .formatted(table));
        var name = RandomStringUtils.secure().nextAlphanumeric(32);
        Function<String, ObjectNode> rowMapper = item -> {
            var node = row(item);
            JsonRowValues.putJsonWithSlim(node, "input", "input_slim", payload);
            return node;
        };

        Long written = new JsonEachRowBulkInsert(client, MAPPER, JsonEachRowBulkInsertTest.streamPayloads(true))
                .insert(table, "log-comment", List.of(name), rowMapper)
                .block();

        // Compared as text: a recursive tree comparison would overflow the stack this deep.
        var record = client.queryAll("SELECT name, input, input_slim FROM %s".formatted(table)).getFirst();
        var actual = Map.of("name", record.getString("name"),
                "input", record.getString("input"),
                "input_slim", record.getString("input_slim"));
        var json = payload.toString();
        assertThat(written).isEqualTo(1L);
        assertThat(actual).isEqualTo(Map.of("name", name, "input", json, "input_slim", json));
    }
}
