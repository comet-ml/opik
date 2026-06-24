package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Credentials and client bounds for the Agent Insights read-only free-form SQL ClickHouse user. Connection params
 * (protocol/host/port/database) are shared with {@code databaseAnalytics}. The bounds cap caller-supplied SQL so a
 * slow query can't pin connections; execution/memory/row caps are enforced server-side on the readonly profile, so
 * {@code socketTimeout} sits above its 180s {@code max_execution_time} (server cap fires first).
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
