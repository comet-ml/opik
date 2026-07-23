package com.comet.opik.api.resources.utils.planshape;

import lombok.NonNull;

import java.util.Locale;

/**
 * A single plan-shape regression found in a captured query. The {@link #fingerprint()} is the stable identity used to
 * match a violation against the checked-in baseline: it normalizes the SQL (collapse whitespace, strip literals) so
 * that run-to-run parameter differences (random UUIDs, timestamps) do not change identity, while a genuinely new or
 * structurally-changed query does.
 */
public record PlanShapeViolation(@NonNull String renderedSql, @NonNull Type type, @NonNull String detail) {

    public enum Type {
        MATERIALIZED_SUBQUERY,
        TEMPORARY_TABLE,
        FULL_TABLE_SCAN
    }

    public String fingerprint() {
        return type.name() + " :: " + normalize(renderedSql);
    }

    private static String normalize(String sql) {
        return sql
                // string / number literals -> placeholder so bound values do not perturb identity
                .replaceAll("'(?:[^']|'')*'", "?")
                .replaceAll("\\b\\d+\\b", "?")
                .replaceAll("\\s+", " ")
                .strip()
                .toLowerCase(Locale.ROOT);
    }
}
