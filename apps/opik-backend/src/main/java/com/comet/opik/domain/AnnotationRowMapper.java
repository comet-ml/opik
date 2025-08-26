package com.comet.opik.domain;

import com.comet.opik.api.Annotation;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

public class AnnotationRowMapper implements RowMapper<Annotation> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    @Override
    public Annotation map(ResultSet rs, StatementContext ctx) throws SQLException {
        try {
            var metricsJson = rs.getString("metrics");
            Map<String, Object> metrics = null;
            if (metricsJson != null && !metricsJson.isEmpty()) {
                metrics = OBJECT_MAPPER.readValue(metricsJson, MAP_TYPE_REF);
            }

            return Annotation.builder()
                    .id(UUID.fromString(rs.getString("id")))
                    .queueItemId(UUID.fromString(rs.getString("queue_item_id")))
                    .smeId(rs.getString("sme_id"))
                    .metrics(metrics)
                    .comment(rs.getString("comment"))
                    .createdAt(rs.getTimestamp("created_at").toInstant())
                    .updatedAt(rs.getTimestamp("updated_at").toInstant())
                    .build();
        } catch (Exception e) {
            throw new SQLException("Failed to map annotation row", e);
        }
    }
}