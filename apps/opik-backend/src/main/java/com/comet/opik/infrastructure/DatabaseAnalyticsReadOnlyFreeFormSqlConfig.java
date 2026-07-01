package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Credentials and socket timeout for the Agent Insights read-only free-form SQL ClickHouse user. Connection params
 * (protocol/host/port/database) are shared with {@code databaseAnalytics}. {@code socketTimeout} sits above the
 * read-only profile's 180s {@code max_execution_time} so the server-side cap fires first (client-v2 default is 0/none).
 */
@Data
public class DatabaseAnalyticsReadOnlyFreeFormSqlConfig {

    @JsonProperty
    private @NotBlank String username;

    @JsonProperty
    private @NotNull String password;

    @JsonProperty
    private @Valid @NotNull Duration socketTimeout;
}
