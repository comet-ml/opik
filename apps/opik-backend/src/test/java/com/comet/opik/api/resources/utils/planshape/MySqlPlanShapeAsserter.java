package com.comet.opik.api.resources.utils.planshape;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Inspects a MySQL {@code EXPLAIN FORMAT=JSON} plan (JSON <b>format version 2</b>, produced by
 * {@link CapturingSqlLogger}) for the query-shape regressions the gate guards against (OPIK-7448):
 *
 * <ul>
 *   <li><b>Materialized / temporary-table nodes</b> — the OPIK-7198 class. Multi-reference CTEs and derived tables that
 *       MySQL materializes into an internal temporary table saturate the TempTable pool under load and fail
 *       non-deterministically. In v2 these appear as a node with {@code access_type = "materialize"}.</li>
 *   <li><b>Full table scans</b> on tables that grow with tenant data — a latency cliff at scale. In v2 a base-table
 *       scan is {@code access_type = "table"} (an indexed read is {@code "index"}), and {@code table_name} is the base
 *       table name (v1 reported the alias here, which is why the gate pins v2).</li>
 * </ul>
 *
 * The walk is recursive because the v2 plan nests {@code inputs} / nested operations arbitrarily deep.
 */
public class MySqlPlanShapeAsserter {

    private static final String OPERATION = "operation";
    private static final String ACCESS_TYPE = "access_type";
    private static final String TABLE_NAME = "table_name";
    private static final String ACCESS_MATERIALIZE = "materialize";
    private static final String ACCESS_TABLE_SCAN = "table";

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
            var accessType = node.path(ACCESS_TYPE).asText(null);

            if (ACCESS_MATERIALIZE.equals(accessType)) {
                violations.add(PlanShapeViolation.builder()
                        .renderedSql(renderedSql)
                        .type(PlanShapeViolation.Type.MATERIALIZED_SUBQUERY)
                        .detail("Plan materializes a subquery into an internal temporary table (OPIK-7198 class): "
                                + node.path(OPERATION).asText("materialize"))
                        .build());
            } else if (ACCESS_TABLE_SCAN.equals(accessType)) {
                var tableName = node.path(TABLE_NAME).asText(null);
                if (tableName != null && fullScanSensitiveTables.contains(tableName)) {
                    violations.add(PlanShapeViolation.builder()
                            .renderedSql(renderedSql)
                            .type(PlanShapeViolation.Type.FULL_TABLE_SCAN)
                            .detail("Full table scan on tenant-growing table '%s': %s".formatted(
                                    tableName, node.path(OPERATION).asText("table scan")))
                            .build());
                }
            }

            node.fields().forEachRemaining(entry -> walk(entry.getValue(), renderedSql, violations));
        } else if (node.isArray()) {
            node.forEach(child -> walk(child, renderedSql, violations));
        }
    }
}
