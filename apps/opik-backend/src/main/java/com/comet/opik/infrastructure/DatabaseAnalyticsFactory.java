package com.comet.opik.infrastructure;

import com.clickhouse.client.api.Client;
import com.google.common.base.Splitter;
import io.dropwizard.util.Duration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class DatabaseAnalyticsFactory {

    private static final String URL_TEMPLATE = "r2dbc:clickhouse:%s://%s:%s@%s:%d/%s%s";
    private static final String CUSTOM_HTTP_PARAMS_KEY = "custom_http_params";

    // Split each `&`/`,`-chunk on the FIRST `=` only — values may themselves contain `=`,
    // e.g. `custom_http_params=max_query_size=100000000,async_insert=1`.
    private static final Splitter KV_SPLITTER = Splitter.on('=').trimResults().limit(2);
    private static final Splitter.MapSplitter TOP_LEVEL_SPLITTER = Splitter.on('&')
            .trimResults().omitEmptyStrings().withKeyValueSeparator(KV_SPLITTER);
    private static final Splitter.MapSplitter CUSTOM_HTTP_PARAMS_SPLITTER = Splitter.on(',')
            .trimResults().omitEmptyStrings().withKeyValueSeparator(KV_SPLITTER);

    private @NotNull Protocol protocol;
    private @NotBlank String host;
    private int port;
    private @NotBlank String username;
    private @NotNull String password;
    private @NotBlank String databaseName;
    private String queryParameters;
    private Duration healthCheckTimeout = Duration.seconds(1);

    public ConnectionFactory build() {
        var options = queryParameters == null ? "" : "?%s".formatted(queryParameters);
        var url = URL_TEMPLATE.formatted(protocol.getValue(), username, password, host, port, databaseName, options);
        return ConnectionFactories.get(url);
    }

    /**
     * Builds the ClickHouse V2 HTTP {@link Client} used by bulk-insert paths
     * (see {@code ExperimentAggregatesDAO.insertExperimentItemAggregates}).
     *
     * <p>Credentials, host, port, database and {@code queryParameters} mirror {@link #build()}
     * so the two clients share a single source of truth. Connection pool, timeouts and other
     * driver-level behaviour are intentionally left at library defaults unless explicitly set
     * via {@code queryParameters} (e.g. {@code compress=1}, {@code health_check_interval=2000}).
     *
     * <p>{@code queryParameters} parsing: top-level {@code &}-separated keys are applied as
     * driver options via {@link Client.Builder#setOption(String, String)}; the
     * {@code custom_http_params} value is a comma-separated list of ClickHouse server settings,
     * applied via {@link Client.Builder#serverSetting(String, String)}. This matches the
     * R2DBC convention where the top-level holds driver flags and {@code custom_http_params}
     * carries the server-side {@code SETTINGS ...} payload.
     */
    public Client buildClient() {
        var builder = new Client.Builder()
                .addEndpoint("%s://%s:%d/".formatted(protocol.getValue(), host, port))
                .setUsername(username)
                .setPassword(password)
                .setDefaultDatabase(databaseName)
                .compressClientRequest(true)
                .compressServerResponse(true);

        // Only server settings (custom_http_params content) are forwarded to the v2 client.
        // Top-level driver options (compress=1, health_check_interval, auto_discovery, failover)
        // are R2DBC-specific and do not translate to the v2 driver surface; connection pool,
        // timeouts and compression are owned by the v2 Client.Builder methods above. Values
        // are still returned by parseQueryParameters() for tests/observability.
        ParsedQueryParameters parsed = parseQueryParameters(queryParameters);
        parsed.serverSettings().forEach(builder::serverSetting);
        return builder.build();
    }

    /**
     * Split a {@code queryParameters} string into two maps:
     * <ul>
     *   <li>{@code driverOptions} — top-level {@code &}-separated entries, intended for
     *       {@link Client.Builder#setOption(String, String)}.</li>
     *   <li>{@code serverSettings} — contents of {@code custom_http_params=...}, which is a
     *       comma-separated list of ClickHouse server settings (destined for
     *       {@link Client.Builder#serverSetting(String, String)}).</li>
     * </ul>
     */
    static ParsedQueryParameters parseQueryParameters(String queryParameters) {
        if (queryParameters == null || queryParameters.isBlank()) {
            return new ParsedQueryParameters(Map.of(), Map.of());
        }
        Map<String, String> driverOptions = new LinkedHashMap<>();
        Map<String, String> serverSettings = new LinkedHashMap<>();
        TOP_LEVEL_SPLITTER.split(queryParameters).forEach((key, value) -> {
            if (CUSTOM_HTTP_PARAMS_KEY.equals(key)) {
                serverSettings.putAll(CUSTOM_HTTP_PARAMS_SPLITTER.split(value));
                return;
            }
            driverOptions.put(key, value);
        });
        return new ParsedQueryParameters(driverOptions, serverSettings);
    }

    record ParsedQueryParameters(Map<String, String> driverOptions, Map<String, String> serverSettings) {
    }

    @RequiredArgsConstructor
    @Getter
    public enum Protocol {
        HTTP("http"),
        HTTPS("https"),
        ;

        private final String value;
    }

}
