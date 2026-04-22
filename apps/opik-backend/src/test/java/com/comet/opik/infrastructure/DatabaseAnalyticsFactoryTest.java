package com.comet.opik.infrastructure;

import com.comet.opik.infrastructure.DatabaseAnalyticsFactory.ParsedQueryParameters;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DatabaseAnalyticsFactory.parseQueryParameters")
class DatabaseAnalyticsFactoryTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("returns empty maps when input is null / blank")
    void blankInputProducesEmptyMaps(String input) {
        ParsedQueryParameters parsed = DatabaseAnalyticsFactory.parseQueryParameters(input);

        assertThat(parsed.driverOptions()).isEmpty();
        assertThat(parsed.serverSettings()).isEmpty();
    }

    @Test
    @DisplayName("top-level entries are routed to driver options")
    void topLevelEntriesAreDriverOptions() {
        ParsedQueryParameters parsed = DatabaseAnalyticsFactory.parseQueryParameters(
                "health_check_interval=2000&compress=1&auto_discovery=true&failover=3");

        assertThat(parsed.driverOptions())
                .containsEntry("health_check_interval", "2000")
                .containsEntry("compress", "1")
                .containsEntry("auto_discovery", "true")
                .containsEntry("failover", "3");
        assertThat(parsed.serverSettings()).isEmpty();
    }

    @Test
    @DisplayName("custom_http_params value is split on commas and routed to server settings")
    void customHttpParamsValueIsServerSettings() {
        ParsedQueryParameters parsed = DatabaseAnalyticsFactory.parseQueryParameters(
                "custom_http_params=max_query_size=100000000,async_insert=1,wait_for_async_insert=1");

        assertThat(parsed.driverOptions()).isEmpty();
        assertThat(parsed.serverSettings())
                .containsEntry("max_query_size", "100000000")
                .containsEntry("async_insert", "1")
                .containsEntry("wait_for_async_insert", "1");
    }

    @Test
    @DisplayName("full production-style query string is parsed into driver + server maps")
    void fullProductionQueryString() {
        String qp = "health_check_interval=2000"
                + "&compress=1"
                + "&auto_discovery=true"
                + "&failover=3"
                + "&custom_http_params=max_query_size=100000000,"
                + "async_insert_busy_timeout_max_ms=250,"
                + "async_insert_busy_timeout_min_ms=100,"
                + "async_insert=1,"
                + "wait_for_async_insert=1,"
                + "async_insert_use_adaptive_busy_timeout=1,"
                + "async_insert_deduplicate=1,"
                + "use_skip_indexes_if_final=1";

        ParsedQueryParameters parsed = DatabaseAnalyticsFactory.parseQueryParameters(qp);

        assertThat(parsed.driverOptions()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "health_check_interval", "2000",
                "compress", "1",
                "auto_discovery", "true",
                "failover", "3"));
        assertThat(parsed.serverSettings()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "max_query_size", "100000000",
                "async_insert_busy_timeout_max_ms", "250",
                "async_insert_busy_timeout_min_ms", "100",
                "async_insert", "1",
                "wait_for_async_insert", "1",
                "async_insert_use_adaptive_busy_timeout", "1",
                "async_insert_deduplicate", "1",
                "use_skip_indexes_if_final", "1"));
    }

    @Test
    @DisplayName("whitespace and trailing separators are tolerated")
    void toleratesWhitespaceAndTrailingSeparators() {
        ParsedQueryParameters parsed = DatabaseAnalyticsFactory.parseQueryParameters(
                " compress=1 & failover=3 &custom_http_params= async_insert=1 ,  wait_for_async_insert=1 ");

        assertThat(parsed.driverOptions())
                .containsEntry("compress", "1")
                .containsEntry("failover", "3");
        assertThat(parsed.serverSettings())
                .containsEntry("async_insert", "1")
                .containsEntry("wait_for_async_insert", "1");
    }
}
