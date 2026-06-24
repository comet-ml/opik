package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Credentials and client bounds for the Agent Insights read-only free-form SQL ClickHouse user. Scope is intentionally
 * reduced to just the user/password and the v2-client bounds: every other connection parameter (protocol, host, port,
 * database) is shared with {@code databaseAnalytics} and reused when building the client (see
 * {@code DatabaseAnalyticsModule}).
 *
 * <p>The bounds exist because this client runs caller-supplied (LLM-generated) SQL: a bounded pool + timeouts keep a
 * slow or stuck query from pinning connections and wedging ClickHouse access for the whole instance. The per-query
 * execution/memory/row caps are enforced server-side on the read-only profile (readonly=1 rejects per-query settings),
 * so {@code socketTimeout} is intentionally larger than the profile's {@code max_execution_time} (180s) — the
 * server-side cap should fire first and surface as a clean error rather than a client-side socket abort.
 */
@Data
public class DatabaseAnalyticsReadOnlyFreeFormSqlConfig {

    @JsonProperty
    private @NotBlank String username;

    @JsonProperty
    private @NotNull String password;

    @JsonProperty
    private @NotNull Integer maxConnections = 20;

    @JsonProperty
    private @NotNull Duration connectionRequestTimeout = Duration.seconds(10);

    @JsonProperty
    private @NotNull Duration socketTimeout = Duration.seconds(200);
}
