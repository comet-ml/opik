package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import lombok.Data;

/**
 * Toggles for analytics-query optimisations that are safe to disable — each renders a different but equivalent
 * query shape, so turning one off costs performance and nothing else.
 */
@Data
public class QueryOptimizationsConfig {

    /**
     * Whether queries may declare a CTE {@code AS MATERIALIZED}, evaluating it once instead of once per reference.
     *
     * <p>Off by default because it requires ClickHouse 26.3 or newer: on 25.8 the keyword is a {@code SYNTAX_ERROR}
     * and the {@code enable_materialized_cte} setting it needs is an {@code UNKNOWN_SETTING}, either of which fails
     * the query outright rather than degrading. The bundled ClickHouse is 26.3, so deployments using it can enable
     * this; an environment pointed at an older external ClickHouse must leave it off.
     *
     * <p>Enabling it emits both the keyword and the setting, per statement. Both are needed — with the setting
     * absent the keyword parses and is silently ignored.
     */
    @Valid @JsonProperty
    private boolean enableCteMaterialization = false;
}
