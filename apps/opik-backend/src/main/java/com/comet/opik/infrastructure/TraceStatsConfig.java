package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import lombok.Data;

@Data
public class TraceStatsConfig {

    /**
     * Whether the feedback-scores stats query declares its {@code trace_final} CTE {@code AS MATERIALIZED}.
     *
     * <p>Off by default because both the keyword and the {@code enable_materialized_cte} setting it needs require
     * ClickHouse 26.3 or newer: on 25.8 the keyword is a {@code SYNTAX_ERROR} and the setting is an
     * {@code UNKNOWN_SETTING}, either of which would turn every trace- and project-stats request into a 5xx rather
     * than degrade. Deployments that ship the bundled ClickHouse are on 26.3 and can enable it; an environment
     * pointed at an older external ClickHouse must leave it off.
     *
     * <p>When off, the query renders exactly as before. When on it is roughly 2x fewer bytes and 2x less CPU
     * (OPIK-7693).
     */
    @Valid @JsonProperty
    private boolean materializeFeedbackScoresCte = false;
}
