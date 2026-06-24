package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Credentials and socket timeout for the Agent Insights read-only free-form SQL ClickHouse user. Connection params
 * (protocol/host/port/database) are shared with {@code databaseAnalytics}.
 *
 * <p>The client-v2 default {@code socket_timeout} is {@code 0} (no timeout): the read-only profile caps query
 * execution server-side ({@code max_execution_time=180}), but that doesn't bound how long our client waits on the
 * socket — a half-open/dropped connection would block a request thread forever and pin a pool connection until
 * restart. {@code socketTimeout} sits above the 180s profile cap so the server-side limit still fires first.
 */
@Data
public class DatabaseAnalyticsReadOnlyFreeFormSqlConfig {

    @JsonProperty
    private @NotBlank String username;

    @JsonProperty
    private @NotNull String password;

    @JsonProperty
    private @NotNull Duration socketTimeout = Duration.seconds(200);
}
