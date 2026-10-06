package com.comet.opik.infrastructure;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.insert.InsertSettings;
import com.clickhouse.client.api.query.GenericRecord;
import com.clickhouse.data.ClickHouseFormat;
import com.comet.opik.TestConfigUtils;
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

    /**
     * The configured cadence, read from the same {@code config-test.yml} the app boots with rather than restated here,
     * so this suite cannot drift from it. A factory built in code carries the primitive's 0, so a suite exercising the
     * guard has to set the value it is exercising.
     */
    private static final int CONFIGURED_PROGRESS_HEADER_CADENCE_MS = TestConfigUtils.loadConfigTest()
            .getDatabaseAnalytics().getHttpHeadersProgressIntervalMs();

    private DatabaseAnalyticsFactory factoryWith(String queryParameters) {
        var factory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickhouse, "default");
        factory.setQueryParameters(queryParameters);
        factory.setHttpHeadersProgressIntervalMs(CONFIGURED_PROGRESS_HEADER_CADENCE_MS);
        return factory;
    }

    private Stream<Arguments> queryParametersScenarios() {
        return Stream.of(
                // custom_http_params entries are applied as ClickHouse server settings
                Arguments.of("server settings applied",
                        "custom_http_params=max_query_size=123456789,async_insert=1,wait_for_async_insert=1",
                        null, null, null,
                        Map.of("max_query_size", "123456789", "async_insert", "1", "wait_for_async_insert", "1")),
                // top-level driver option coexists with custom_http_params; server settings still apply
                Arguments.of("mixed driver and server params",
                        "compress=1&custom_http_params=max_query_size=7777777,async_insert=1",
                        null, null, null,
                        Map.of("max_query_size", "7777777", "async_insert", "1")),
                // the field overrides async_insert_busy_timeout_max_ms; siblings and driver options are preserved
                Arguments.of("override applied",
                        "auto_discovery=true&failover=3&custom_http_params=async_insert_busy_timeout_max_ms=250,max_query_size=123456789",
                        7000, null, null,
                        Map.of("async_insert_busy_timeout_max_ms", "7000", "max_query_size", "123456789")),
                // no top-level driver options: the override is serialized as custom_http_params only
                Arguments.of("override applied without driver options",
                        "custom_http_params=async_insert_busy_timeout_max_ms=250,max_query_size=123456789",
                        7000, null, null,
                        Map.of("async_insert_busy_timeout_max_ms", "7000", "max_query_size", "123456789")),
                // null queryParameters + field set: the override is injected (custom_http_params is synthesized)
                Arguments.of("null query parameters",
                        null,
                        7000, null, null,
                        Map.of("async_insert_busy_timeout_max_ms", "7000")),
                // blank (whitespace) queryParameters + field set: treated like null — the override is injected
                Arguments.of("blank query parameters",
                        "   ",
                        7000, null, null,
                        Map.of("async_insert_busy_timeout_max_ms", "7000")),
                // max field unset: the queryParameters value is kept
                Arguments.of("max value kept",
                        "custom_http_params=async_insert_busy_timeout_max_ms=250,max_query_size=123456789",
                        null, null, null,
                        Map.of("async_insert_busy_timeout_max_ms", "250", "max_query_size", "123456789")),
                // max setting absent from the chain: the override is injected (custom_http_params is present)
                Arguments.of("max injected when absent",
                        "custom_http_params=max_query_size=123456789",
                        7000, null, null,
                        Map.of("async_insert_busy_timeout_max_ms", "7000", "max_query_size", "123456789")),
                // min field overridden when present, same as the max field
                Arguments.of("min override applied",
                        "custom_http_params=async_insert_busy_timeout_min_ms=100,max_query_size=123456789",
                        null, 30, null,
                        Map.of("async_insert_busy_timeout_min_ms", "30", "max_query_size", "123456789")),
                // min field unset: the queryParameters value is kept
                Arguments.of("min value kept",
                        "custom_http_params=async_insert_busy_timeout_min_ms=100,max_query_size=123456789",
                        null, null, null,
                        Map.of("async_insert_busy_timeout_min_ms", "100", "max_query_size", "123456789")),
                // min setting absent from the chain: the override is injected the same way
                Arguments.of("min injected when absent",
                        "custom_http_params=max_query_size=123456789",
                        null, 30, null,
                        Map.of("async_insert_busy_timeout_min_ms", "30", "max_query_size", "123456789")),
                // max_data_size is NOT pinned by Opik, so it is injected whenever the field is set, even if absent
                Arguments.of("max data size injected when absent",
                        "custom_http_params=max_query_size=123456789",
                        null, null, 52428800L,
                        Map.of("async_insert_max_data_size", "52428800", "max_query_size", "123456789")),
                // when present in the chain, the field value wins
                Arguments.of("max data size override applied",
                        "custom_http_params=async_insert_max_data_size=10485760,max_query_size=123456789",
                        null, null, 52428800L,
                        Map.of("async_insert_max_data_size", "52428800", "max_query_size", "123456789")),
                // present in the chain but field unset: nothing injected, the chain value is preserved (not stripped)
                Arguments.of("max data size value kept",
                        "custom_http_params=async_insert_max_data_size=20971520,max_query_size=123456789",
                        null, null, null,
                        Map.of("async_insert_max_data_size", "20971520", "max_query_size", "123456789")),
                // absent and field unset: nothing injected, so ClickHouse keeps its default (10 MiB) — the upgrade-safe case
                Arguments.of("max data size not injected when unset",
                        "custom_http_params=max_query_size=123456789",
                        null, null, null,
                        Map.of("async_insert_max_data_size", "10485760", "max_query_size", "123456789")),
                // all overrides together: max/min override values present in the chain, max_data_size injected though absent
                Arguments.of("all overrides applied",
                        "custom_http_params=async_insert_busy_timeout_max_ms=250,async_insert_busy_timeout_min_ms=100",
                        7000, 30, 52428800L,
                        Map.of("async_insert_busy_timeout_max_ms", "7000", "async_insert_busy_timeout_min_ms", "30",
                                "async_insert_max_data_size", "52428800")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("queryParametersScenarios")
    void appliesQueryParametersOverridesWithR2dbcClient(
            String name,
            String queryParameters,
            Integer asyncInsertBusyTimeoutMaxMsOverride,
            Integer asyncInsertBusyTimeoutMinMsOverride,
            Long asyncInsertMaxDataSizeOverride,
            Map<String, String> expectedSettings) {
        var factory = factoryWith(queryParameters);
        factory.setAsyncInsertBusyTimeoutMaxMs(asyncInsertBusyTimeoutMaxMsOverride);
        factory.setAsyncInsertBusyTimeoutMinMs(asyncInsertBusyTimeoutMinMsOverride);
        factory.setAsyncInsertMaxDataSize(asyncInsertMaxDataSizeOverride);

        var actualSettings = readSettings(factory.build(), expectedSettings.keySet());

        assertThat(actualSettings).isEqualTo(expectedSettings);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("queryParametersScenarios")
    void appliesQueryParametersOverridesWithV2Client(
            String name,
            String queryParameters,
            Integer asyncInsertBusyTimeoutMaxMsOverride,
            Integer asyncInsertBusyTimeoutMinMsOverride,
            Long asyncInsertMaxDataSizeOverride,
            Map<String, String> expectedSettings) {
        var factory = factoryWith(queryParameters);
        factory.setAsyncInsertBusyTimeoutMaxMs(asyncInsertBusyTimeoutMaxMsOverride);
        factory.setAsyncInsertBusyTimeoutMinMs(asyncInsertBusyTimeoutMinMsOverride);
        factory.setAsyncInsertMaxDataSize(asyncInsertMaxDataSizeOverride);

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
    @DisplayName("a factory built in code, which skips validation, omits the cadence rather than sending 0")
    void factoryBuiltInCodeOmitsTheCadence() {
        // Programmatic construction bypasses Bean Validation, so the primitive keeps its 0 — DatabaseAnalyticsModule
        // #buildReadOnlyClient and the suites that call build() directly. Sending a value that @Min would reject
        // overrides the server's own cadence with "no throttle"; omitting it inherits the server default, which is
        // what an unconfigured factory should do. Deliberately not factoryWith(), which sets a cadence.
        var factory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickhouse, "default");

        var actualSettings = readSettings(factory.build(), "http_headers_progress_interval_ms");

        assertThat(actualSettings).isEqualTo(Map.of("http_headers_progress_interval_ms", "100"));
    }

    @Test
    @DisplayName("R2DBC connection carries the progress-header cadence that keeps responses under Apache HC's cap")
    void r2dbcConnectionCarriesTheProgressHeaderCadence() {
        var factory = factoryWith(null);

        var actualSettings = readSettings(factory.build(), "http_headers_progress_interval_ms");

        assertThat(actualSettings)
                .isEqualTo(Map.of("http_headers_progress_interval_ms",
                        String.valueOf(CONFIGURED_PROGRESS_HEADER_CADENCE_MS)));
    }

    @Test
    @DisplayName("v2 client does not carry the progress-header cadence, which a readonly=1 user would reject")
    void v2ClientDoesNotCarryTheProgressHeaderCadence() {
        // The guard is clickhouse-r2dbc's alone, and DatabaseAnalyticsModule#buildReadOnlyClient builds a bare factory
        // for a user whose profile is readonly=1 with a two-setting allowlist — sending anything else fails every read
        // that user makes. This is that factory's shape: no queryParameters, so no server settings at all.
        var factory = factoryWith(null);

        try (var client = factory.buildClient()) {
            var actualSettings = readSettings(client, "http_headers_progress_interval_ms");

            assertThat(actualSettings).isEqualTo(Map.of("http_headers_progress_interval_ms", "100"));
        }
    }

    @Test
    @DisplayName("a cadence in custom_http_params: the field wins on R2DBC, the operator's value stands on v2")
    void operatorSuppliedCadenceIsOverriddenOnlyOnTheR2dbcPath() {
        // Two different rules meeting, both pre-existing. On R2DBC the dedicated field overrides a value present in
        // the chain, exactly as asyncInsertBusyTimeoutMaxMs does — and because the configuration must supply one for
        // the app to start, it always does, so the guard cannot be undercut from custom_http_params. On v2 the field
        // is not applied at all,
        // so the operator's own entry stands, forwarded verbatim like async_insert or max_query_size; singling this
        // one key out for filtering would be the surprising behaviour. Neither reaches the readonly free-form user,
        // whose factory is built without queryParameters (DatabaseAnalyticsModule#buildReadOnlyClient).
        var factory = factoryWith("custom_http_params=http_headers_progress_interval_ms=500");

        var r2dbcSettings = readSettings(factory.build(), "http_headers_progress_interval_ms");
        assertThat(r2dbcSettings)
                .isEqualTo(Map.of("http_headers_progress_interval_ms",
                        String.valueOf(CONFIGURED_PROGRESS_HEADER_CADENCE_MS)));

        try (var client = factory.buildClient()) {
            var v2Settings = readSettings(client, "http_headers_progress_interval_ms");

            assertThat(v2Settings).isEqualTo(Map.of("http_headers_progress_interval_ms", "500"));
        }
    }

    @Test
    @DisplayName("R2DBC: a query outlasting the 100-header budget at ClickHouse's default progress cadence completes")
    void longRunningQuerySurvivesTheApacheHeaderCap() {
        // clickhouse-r2dbc sets send_progress_in_http_headers=1 on every HTTP statement, and the v1 Apache transport
        // caps a response at Http1Config.DEFAULT.getMaxHeaderCount() == 100 with no option to raise it. One block per
        // row keeps the query busy for ~12s, which at ClickHouse's 100ms default cadence emits ~120
        // X-ClickHouse-Progress headers and the response fails to parse ("Maximum header count exceeded", surfaced as a
        // bare ConnectException). httpHeadersProgressIntervalMs throttles the cadence to 3s, so ~4 headers reach the
        // client instead. Drop that default to reproduce. Summing `number` alongside the sleep is what forces the
        // sleep to be evaluated — a bare count() over it is optimised away and returns in milliseconds.
        var factory = factoryWith(null);

        var sum = Mono.usingWhen(
                factory.build().create(),
                connection -> Flux.from(connection.createStatement(
                        "SELECT sum(number + sleepEachRow(0.1)) AS c FROM numbers(120) SETTINGS max_block_size = 1")
                        .execute())
                        .flatMap(result -> result.map((row, _) -> row.get("c", Double.class)))
                        .single(),
                Connection::close)
                .block();

        assertThat(sum).isEqualTo(7140.0d);
    }

    @Test
    @DisplayName("session timezone reaches both clients and overrides a value in custom_http_params")
    void sessionTimezoneIsAppliedToBothClients() {
        var factory = factoryWith("custom_http_params=session_timezone=America/New_York");
        factory.setSessionTimezone("UTC");

        assertThat(readSettings(factory.build(), "session_timezone"))
                .isEqualTo(Map.of("session_timezone", "UTC"));
        try (var client = factory.buildClient()) {
            assertThat(readSettings(client, "session_timezone")).isEqualTo(Map.of("session_timezone", "UTC"));
        }
    }

    @Test
    @DisplayName("a factory built in code omits the session timezone, which a readonly=1 user would reject")
    void factoryBuiltInCodeOmitsTheSessionTimezone() {
        // The shape of DatabaseAnalyticsModule#buildReadOnlyClient: a bare factory, so the server default (empty) stands.
        var factory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(clickhouse, "default");

        try (var client = factory.buildClient()) {
            assertThat(readSettings(client, "session_timezone")).isEqualTo(Map.of("session_timezone", ""));
        }
    }

    private Stream<Arguments> epochSentinelScenarios() {
        // A non-UTC session in the chain stands in for a non-UTC server: the app binds the absolute epoch
        // (Instant.EPOCH), while its SQL compares against a timezone-less literal resolved in the session timezone.
        return Stream.of(
                Arguments.of("non-UTC session, field unset: the literal is not the epoch", null, false),
                Arguments.of("non-UTC session, field empty: the literal is not the epoch", "", false),
                Arguments.of("non-UTC session, field blank: the literal is not the epoch", "   ", false),
                Arguments.of("non-UTC session, field UTC: the literal is the epoch", "UTC", true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("epochSentinelScenarios")
    void epochSentinelLiteralMatchesTheAbsoluteEpoch(String name, String sessionTimezone, boolean expected) {
        var factory = factoryWith("custom_http_params=session_timezone=America/New_York");
        factory.setSessionTimezone(sessionTimezone);
        var sql = """
                SELECT toDateTime64('1970-01-01 00:00:00', 6) = toDateTime64(0, 6) AS matches
                """;

        var r2dbcMatches = Mono.usingWhen(
                factory.build().create(),
                connection -> Flux.from(connection.createStatement(sql).execute())
                        .flatMap(result -> result.map((row, _) -> row.get("matches", Boolean.class)))
                        .single(),
                Connection::close)
                .block();
        assertThat(r2dbcMatches).isEqualTo(expected);

        try (var client = factory.buildClient()) {
            assertThat(client.queryAll(sql).getFirst().getBoolean("matches")).isEqualTo(expected);
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
