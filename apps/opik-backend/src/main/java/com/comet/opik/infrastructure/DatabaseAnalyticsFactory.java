package com.comet.opik.infrastructure;

import com.clickhouse.client.api.Client;
import com.google.common.base.Splitter;
import io.dropwizard.util.Duration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Data
public class DatabaseAnalyticsFactory {

    private static final String URL_TEMPLATE = "r2dbc:clickhouse:%s://%s:%s@%s:%d/%s%s";
    private static final String CUSTOM_HTTP_PARAMS_KEY = "custom_http_params";
    private static final String ASYNC_INSERT_BUSY_TIMEOUT_MAX_MS = "async_insert_busy_timeout_max_ms";
    private static final String KEY_VALUE_FORMAT = "%s=%s";

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

    /**
     * Optional override (ms) for {@code async_insert_busy_timeout_max_ms} in the {@link #queryParameters}
     * {@code custom_http_params} chain; when unset the value carried by {@code queryParameters} is left untouched.
     * With {@code async_insert_use_adaptive_busy_timeout=1} this is a ceiling on the buffer window — the adaptive
     * scheduler widens it only while rows are queued.
     */
    private @Min(1) Integer asyncInsertBusyTimeoutMaxMs;

    private Duration healthCheckTimeout = Duration.seconds(1);

    // Optional socket timeout, applied in buildClient() only when set (null = library default of 0/no timeout).
    private Duration clientSocketTimeout;

    /**
     * Gates the {@code clickhouse-cluster} health check. Off for single-shard / non-Distributed
     * deployments; on only for the Distributed (Hyperscale) topology where the cluster definition
     * must be visible from every node.
     */
    private boolean clusterHealthCheckEnabled;

    /**
     * Gates the {@code clickhouse-cold-storage-disk} health check. Off for deployments without tier
     * storage (OSS Docker); on only once the {@code cold_s3} S3 disk is activated.
     */
    private boolean coldStorageDiskHealthCheckEnabled;

    public ConnectionFactory build() {
        var queryParametersOverrides = getQueryParametersOverrides(queryParameters);
        var options = queryParametersOverrides == null ? "" : "?%s".formatted(queryParametersOverrides);
        var url = URL_TEMPLATE.formatted(protocol.getValue(), username, password, host, port, databaseName, options);
        return ConnectionFactories.get(url);
    }

    /**
     * Builds the ClickHouse V2 HTTP {@link Client} used by bulk-insert paths
     * (see {@code ExperimentAggregatesDAO.insertExperimentItemAggregates}).
     *
     * <p>Credentials, host, port, database and {@code queryParameters} mirror {@link #build()}
     * so the two clients share a single source of truth. Connection pool, timeouts and other
     * driver-level behavior are intentionally left at library defaults unless explicitly set
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
                .compressServerResponse(true)
                // Truly non-blocking: without this, Client.query() runs the HTTP round-trip
                // synchronously on the calling thread and returns an already-completed future,
                // defeating Mono.fromFuture(). With async on, work runs on the v2 client's
                // executor and the future genuinely defers until the response is back.
                .useAsyncRequests(true);

        // Only server settings (custom_http_params content) are forwarded to the v2 client.
        // Top-level driver options (compress=1, health_check_interval, auto_discovery, failover)
        // are R2DBC-specific and do not translate to the v2 driver surface; connection pool,
        // timeouts and compression are owned by the v2 Client.Builder methods above. Values
        // are still returned by parseQueryParameters() for tests/observability.
        var parsed = parseQueryParameters(getQueryParametersOverrides(queryParameters));
        parsed.serverSettings().forEach(builder::serverSetting);

        if (clientSocketTimeout != null) {
            builder.setSocketTimeout(clientSocketTimeout.toMilliseconds(), ChronoUnit.MILLIS);
        }

        return builder.build();
    }

    /**
     * Returns {@code queryParameters} with each {@link #configurableQueryParameters() configurable server setting}
     * present in {@code custom_http_params} replaced by its config-field value; unchanged when none are present.
     */
    private String getQueryParametersOverrides(String queryParameters) {
        if (StringUtils.isBlank(queryParameters)) {
            return queryParameters;
        }
        var parsed = parseQueryParameters(queryParameters);
        var overrides = configurableQueryParameters().entrySet().stream()
                .filter(override -> parsed.serverSettings().containsKey(override.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        if (overrides.isEmpty()) {
            return queryParameters;
        }
        var serverSettings = new LinkedHashMap<>(parsed.serverSettings());
        serverSettings.putAll(overrides);
        return serialize(parsed.driverOptions(), serverSettings);
    }

    /**
     * Server settings whose values come from dedicated config fields, included only when the field is set. Applied
     * only when already present in {@code custom_http_params}, so settings absent from {@link #queryParameters} are
     * never injected.
     */
    private Map<String, String> configurableQueryParameters() {
        if (asyncInsertBusyTimeoutMaxMs == null) {
            return Map.of();
        }
        return Map.of(ASYNC_INSERT_BUSY_TIMEOUT_MAX_MS, String.valueOf(asyncInsertBusyTimeoutMaxMs));
    }

    private String serialize(Map<String, String> driverOptions, Map<String, String> serverSettings) {
        var topLevel = driverOptions.entrySet().stream()
                .map(this::formatEntry)
                .collect(Collectors.toCollection(ArrayList::new));
        if (!serverSettings.isEmpty()) {
            var customHttpParams = serverSettings.entrySet().stream()
                    .map(this::formatEntry)
                    .collect(Collectors.joining(","));
            topLevel.add(formatEntry(CUSTOM_HTTP_PARAMS_KEY, customHttpParams));
        }
        return String.join("&", topLevel);
    }

    private String formatEntry(Map.Entry<String, String> entry) {
        return formatEntry(entry.getKey(), entry.getValue());
    }

    private String formatEntry(String key, String value) {
        return KEY_VALUE_FORMAT.formatted(key, value);
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
