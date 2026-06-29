package com.comet.opik.infrastructure;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.insert.InsertSettings;
import com.clickhouse.client.api.query.GenericRecord;
import com.clickhouse.data.ClickHouseFormat;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.lifecycle.Startables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseAnalyticsFactoryIntegrationTest {

    private ClickHouseContainer clickhouse;

    @BeforeAll
    void setUp() {
        clickhouse = ClickHouseContainerUtils.newClickHouseContainer(false);
        Startables.deepStart(clickhouse).join();
    }

    @AfterAll
    void tearDown() {
        if (clickhouse != null && clickhouse.isRunning()) {
            clickhouse.stop();
        }
    }

    private DatabaseAnalyticsFactory factoryWith(String queryParameters) {
        var factory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickhouse, "default");
        factory.setQueryParameters(queryParameters);
        return factory;
    }

    private Stream<Arguments> queryParametersScenarios() {
        return Stream.of(
                // custom_http_params entries are applied as ClickHouse server settings
                Arguments.of("server settings applied",
                        "custom_http_params=max_query_size=123456789,async_insert=1,wait_for_async_insert=1",
                        null,
                        Map.of("max_query_size", "123456789", "async_insert", "1", "wait_for_async_insert", "1")),
                // top-level driver option coexists with custom_http_params; server settings still apply
                Arguments.of("mixed driver and server params",
                        "compress=1&custom_http_params=max_query_size=7777777,async_insert=1",
                        null,
                        Map.of("max_query_size", "7777777", "async_insert", "1")),
                // the field overrides async_insert_busy_timeout_max_ms; siblings and driver options are preserved
                Arguments.of("override applied",
                        "auto_discovery=true&failover=3&custom_http_params=async_insert_busy_timeout_max_ms=250,max_query_size=123456789",
                        7000,
                        Map.of("async_insert_busy_timeout_max_ms", "7000", "max_query_size", "123456789")),
                // no top-level driver options: the override is serialized as custom_http_params only
                Arguments.of("override applied without driver options",
                        "custom_http_params=async_insert_busy_timeout_max_ms=250,max_query_size=123456789",
                        7000,
                        Map.of("async_insert_busy_timeout_max_ms", "7000", "max_query_size", "123456789")),
                // blank queryParameters: nothing to override, ClickHouse keeps its default (200)
                Arguments.of("blank query parameters",
                        null,
                        7000,
                        Map.of("async_insert_busy_timeout_max_ms", "200")),
                // field unset: the queryParameters value is kept
                Arguments.of("value kept",
                        "custom_http_params=async_insert_busy_timeout_max_ms=250,max_query_size=123456789",
                        null,
                        Map.of("async_insert_busy_timeout_max_ms", "250", "max_query_size", "123456789")),
                // the setting is absent from queryParameters, so the field is not injected and ClickHouse keeps its default (200)
                Arguments.of("not injected when absent",
                        "custom_http_params=max_query_size=123456789",
                        7000,
                        Map.of("async_insert_busy_timeout_max_ms", "200", "max_query_size", "123456789")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("queryParametersScenarios")
    void appliesQueryParametersOverridesWithR2dbcClient(
            String name,
            String queryParameters,
            Integer asyncInsertBusyTimeoutMaxMsOverride,
            Map<String, String> expectedSettings) {
        var factory = factoryWith(queryParameters);
        factory.setAsyncInsertBusyTimeoutMaxMs(asyncInsertBusyTimeoutMaxMsOverride);

        var actualSettings = readSettings(factory.build(), expectedSettings.keySet());

        assertThat(actualSettings).isEqualTo(expectedSettings);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("queryParametersScenarios")
    void appliesQueryParametersOverridesWithV2Client(
            String name,
            String queryParameters,
            Integer asyncInsertBusyTimeoutMaxMsOverride,
            Map<String, String> expectedSettings) {
        var factory = factoryWith(queryParameters);
        factory.setAsyncInsertBusyTimeoutMaxMs(asyncInsertBusyTimeoutMaxMsOverride);

        try (var client = factory.buildClient()) {
            var actualSettings = readSettings(client, expectedSettings.keySet());

            assertThat(actualSettings).isEqualTo(expectedSettings);
        }
    }

    @Test
    @DisplayName("R2DBC connection factory: driver-level options do not leak into ClickHouse server settings")
    void driverOptionsAreNotAppliedAsServerSettingsWithR2dbc() {
        var factory = factoryWith("compress=1&auto_discovery=true&failover=3");

        var actualSettings = readSettings(factory.build(), "auto_discovery", "failover");

        assertThat(actualSettings).isEmpty();
    }

    @Test
    @DisplayName("driver-level top-level entries do not leak into ClickHouse server settings")
    void driverOptionsAreNotAppliedAsServerSettingsWithV2Client() {
        var factory = factoryWith("compress=1&auto_discovery=true&failover=3");

        try (Client client = factory.buildClient()) {
            // These are driver-side keys and should not be reflected as server settings.
            // `compress` exists as a CH setting too — verify the client still ran and server
            // didn't misinterpret the driver flag as the server setting value.
            Map<String, String> observed = readSettings(client, "auto_discovery", "failover");

            assertThat(observed).isEmpty();
        }
    }

    @Test
    @DisplayName("a bulk JSONEachRow insert completes against the built client")
    void bulkInsertRoundTrip() throws Exception {
        var factory = factoryWith("custom_http_params=async_insert=0,wait_for_async_insert=1");

        try (Client client = factory.buildClient()) {
            client.query("CREATE TABLE IF NOT EXISTS t_factory_it (id UInt64, name String) ENGINE = Memory")
                    .get().close();
            client.query("TRUNCATE TABLE t_factory_it").get().close();

            String body = "{\"id\": 1, \"name\": \"a\"}\n{\"id\": 2, \"name\": \"b\"}\n";
            var settings = new InsertSettings().serverSetting("date_time_input_format", "best_effort");
            try (var response = client.insert(
                    "t_factory_it",
                    new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),
                    ClickHouseFormat.JSONEachRow,
                    settings).get()) {
                assertThat(response).isNotNull();
            }

            List<GenericRecord> records = client.queryAll("SELECT count() AS c FROM t_factory_it");
            assertThat(records).hasSize(1);
            assertThat(records.getFirst().getLong("c")).isEqualTo(2L);
        }
    }

    private Map<String, String> readSettings(ConnectionFactory connectionFactory, String... names) {
        return readSettings(connectionFactory, List.of(names));
    }

    private Map<String, String> readSettings(ConnectionFactory connectionFactory, Collection<String> names) {
        var inClause = names.stream().map(name -> "'" + name + "'")
                .collect(Collectors.joining(","));
        return Mono.usingWhen(
                connectionFactory.create(),
                connection -> Flux.from(connection.createStatement(
                        "SELECT name, value FROM system.settings WHERE name IN (" + inClause + ")").execute())
                        .flatMap(result -> result.map((row, _) -> Map.entry(
                                row.get("name", String.class), row.get("value", String.class))))
                        .collectMap(Map.Entry::getKey, Map.Entry::getValue),
                Connection::close)
                .block();
    }

    private Map<String, String> readSettings(Client client, String... names) {
        return readSettings(client, List.of(names));
    }

    private Map<String, String> readSettings(Client client, Collection<String> names) {
        // Names come from trusted call-sites in this test; inline to avoid v2 param-binding
        // quirks around Array(String) serialization. We return the *current* value of each
        // requested setting (regardless of whether it matches the server default), so the
        // caller can assert the value seen by ClickHouse for the running query.
        var inClause = names.stream().map(name -> "'" + name + "'")
                .collect(Collectors.joining(","));
        List<GenericRecord> records = client.queryAll(
                "SELECT name, value FROM system.settings WHERE name IN (" + inClause + ")");
        return records.stream().collect(Collectors.toMap(
                r -> r.getString("name"),
                r -> r.getString("value")));
    }
}
