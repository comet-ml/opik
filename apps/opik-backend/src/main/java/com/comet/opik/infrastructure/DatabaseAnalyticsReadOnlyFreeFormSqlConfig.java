package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Credentials for the Agent Insights read-only free-form SQL ClickHouse user. Scope is intentionally reduced to just
 * the user/password: every other connection parameter (protocol, host, port, database) is shared with
 * {@code databaseAnalytics} and reused when building the client (see {@code DatabaseAnalyticsModule}).
 */
@Data
public class DatabaseAnalyticsReadOnlyFreeFormSqlConfig {

    @JsonProperty
    private @NotBlank String username;

    @JsonProperty
    private @NotNull String password;
}
