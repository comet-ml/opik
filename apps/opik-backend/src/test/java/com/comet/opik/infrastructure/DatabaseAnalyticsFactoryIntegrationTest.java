package com.comet.opik.infrastructure;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.insert.InsertSettings;
import com.clickhouse.client.api.query.GenericRecord;
import com.clickhouse.data.ClickHouseFormat;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.lifecycle.Startables;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DatabaseAnalyticsFactory.buildClient — ClickHouse wiring")
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

    @Test
    @DisplayName("custom_http_params entries land as real ClickHouse server settings")
    void serverSettingsFromCustomHttpParamsAreAppliedByClickHouse() throws Exception {
        var factory = factoryWith(
                "custom_http_params=max_query_size=123456789,async_insert=1,wait_for_async_insert=1");

        try (Client client = factory.buildClient()) {
            Map<String, String> observed = readSettings(client, "max_query_size", "async_insert",
                    "wait_for_async_insert");

            assertThat(observed)
                    .containsEntry("max_query_size", "123456789")
                    .containsEntry("async_insert", "1")
                    .containsEntry("wait_for_async_insert", "1");
        }
    }

    @Test
    @DisplayName("driver-level top-level entries do not leak into ClickHouse server settings")
    void driverOptionsAreNotAppliedAsServerSettings() throws Exception {
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
    @DisplayName("mixed top-level + custom_http_params are correctly split")
    void mixedQueryParametersAreSplit() throws Exception {
        var factory = factoryWith(
                "compress=1&custom_http_params=max_query_size=7777777,async_insert=1");

        try (Client client = factory.buildClient()) {
            Map<String, String> observed = readSettings(client, "max_query_size", "async_insert");

            assertThat(observed)
                    .containsEntry("max_query_size", "7777777")
                    .containsEntry("async_insert", "1");
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
            assertThat(records.get(0).getLong("c")).isEqualTo(2L);
        }
    }

    private Map<String, String> readSettings(Client client, String... names) {
        // Names come from trusted call-sites in this test; inline to avoid v2 param-binding
        // quirks around Array(String) serialization. We return the *current* value of each
        // requested setting (regardless of whether it matches the server default), so the
        // caller can assert the value seen by ClickHouse for the running query.
        String inClause = java.util.Arrays.stream(names).map(n -> "'" + n + "'")
                .collect(Collectors.joining(","));
        List<GenericRecord> records = client.queryAll(
                "SELECT name, value FROM system.settings WHERE name IN (" + inClause + ")");
        return records.stream().collect(Collectors.toMap(
                r -> r.getString("name"),
                r -> r.getString("value")));
    }
}
