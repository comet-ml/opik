package com.comet.opik.api.resources.utils.planshape;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Inspects a MySQL {@code EXPLAIN FORMAT=JSON} plan for the query-shape regressions the gate guards against
 * (OPIK-7448):
 *
 * <ul>
 *   <li><b>Materialized / temporary-table nodes</b> — the OPIK-7198 class. Multi-reference CTEs and derived tables that
 *       MySQL materializes into an internal {@code #sql...} temporary table saturate the TempTable pool under load and
 *       fail non-deterministically. The JSON surfaces these as {@code materialized_from_subquery} nodes or a
 *       {@code using_temporary_table} flag.</li>
 *   <li><b>Full table scans</b> ({@code access_type = ALL}) on tables that grow with tenant data — a latency cliff at
 *       scale.</li>
 * </ul>
 *
 * The walk is recursive because EXPLAIN nests {@code query_block} / {@code nested_loop} / {@code table} /
 * {@code materialized_from_subquery} arbitrarily deep.
 */
public class MySqlPlanShapeAsserter {

    private static final String MATERIALIZED_FROM_SUBQUERY = "materialized_from_subquery";
    private static final String USING_TEMPORARY_TABLE = "using_temporary_table";
    private static final String TABLE = "table";
    private static final String TABLE_NAME = "table_name";
    private static final String ACCESS_TYPE = "access_type";
    private static final String FULL_SCAN = "ALL";

    private final Set<String> fullScanSensitiveTables;

    public MySqlPlanShapeAsserter(@NonNull Set<String> fullScanSensitiveTables) {
        this.fullScanSensitiveTables = fullScanSensitiveTables;
    }

    public List<PlanShapeViolation> findViolations(@NonNull String renderedSql, @NonNull String explainJson) {
        var violations = new ArrayList<PlanShapeViolation>();
        walk(JsonUtils.getJsonNodeFromString(explainJson), renderedSql, violations);
        return violations;
    }

    private void walk(JsonNode node, String renderedSql, List<PlanShapeViolation> violations) {
        if (node == null) {
            return;
        }

        if (node.isObject()) {
            if (node.has(MATERIALIZED_FROM_SUBQUERY)) {
                violations.add(new PlanShapeViolation(renderedSql, PlanShapeViolation.Type.MATERIALIZED_SUBQUERY,
                        "Plan materializes a subquery into an internal temporary table (OPIK-7198 class)."));
            }
            if (node.path(USING_TEMPORARY_TABLE).asBoolean(false)) {
                violations.add(new PlanShapeViolation(renderedSql, PlanShapeViolation.Type.TEMPORARY_TABLE,
                        "Plan uses an internal temporary table (OPIK-7198 class)."));
            }

            var tableNode = node.get(TABLE);
            if (tableNode != null && tableNode.isObject()) {
                checkTableAccess(tableNode, renderedSql, violations);
            }

            node.fields().forEachRemaining(entry -> walk(entry.getValue(), renderedSql, violations));
        } else if (node.isArray()) {
            node.forEach(child -> walk(child, renderedSql, violations));
        }
    }

    private void checkTableAccess(JsonNode tableNode, String renderedSql, List<PlanShapeViolation> violations) {
        var accessType = tableNode.path(ACCESS_TYPE).asText(null);
        var tableName = tableNode.path(TABLE_NAME).asText(null);
        if (FULL_SCAN.equals(accessType) && tableName != null && fullScanSensitiveTables.contains(tableName)) {
            violations.add(new PlanShapeViolation(renderedSql, PlanShapeViolation.Type.FULL_TABLE_SCAN,
                    "Full table scan (access_type=ALL) on tenant-growing table '%s'.".formatted(tableName)));
        }
    }
}
