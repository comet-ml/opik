package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RequiredArgsConstructor
@Slf4j
public class AnnotationQueueRowMapper implements RowMapper<AnnotationQueue> {

    private final @NonNull ObjectMapper objectMapper;

    @Override
    public AnnotationQueue map(ResultSet rs, StatementContext ctx) throws SQLException {
        try {
            // Parse JSON fields
            List<String> visibleFields = parseJsonList(rs.getString("visible_fields"));
            List<String> requiredMetrics = parseJsonList(rs.getString("required_metrics"));
            List<String> optionalMetrics = parseJsonList(rs.getString("optional_metrics"));
            List<String> assignedSmes = parseJsonList(rs.getString("assigned_smes"));

            return AnnotationQueue.builder()
                    .id(getUUID(rs, "id"))
                    .name(rs.getString("name"))
                    .description(rs.getString("description"))
                    .status(parseStatus(rs.getString("status")))
                    .createdBy(rs.getString("created_by"))
                    .projectId(getUUID(rs, "project_id"))
                    .templateId(getUUID(rs, "template_id"))
                    .visibleFields(visibleFields)
                    .requiredMetrics(requiredMetrics)
                    .optionalMetrics(optionalMetrics)
                    .instructions(rs.getString("instructions"))
                    .dueDate(getInstant(rs, "due_date"))
                    .createdAt(getInstant(rs, "created_at"))
                    .updatedAt(getInstant(rs, "updated_at"))
                    .totalItems(rs.getInt("total_items"))
                    .completedItems(rs.getInt("completed_items"))
                    .assignedSmes(assignedSmes)
                    .build();
        } catch (Exception e) {
            log.error("Error mapping AnnotationQueue row", e);
            throw new SQLException("Failed to map AnnotationQueue", e);
        }
    }

    private List<String> parseJsonList(String json) {
        if (json == null || json.trim().isEmpty()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse JSON list: {}", json, e);
            return List.of();
        }
    }

    private AnnotationQueue.AnnotationQueueStatus parseStatus(String status) {
        if (status == null) {
            return AnnotationQueue.AnnotationQueueStatus.ACTIVE;
        }
        try {
            return AnnotationQueue.AnnotationQueueStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown annotation queue status: {}", status);
            return AnnotationQueue.AnnotationQueueStatus.ACTIVE;
        }
    }

    private UUID getUUID(ResultSet rs, String columnName) throws SQLException {
        String value = rs.getString(columnName);
        return value != null ? UUID.fromString(value) : null;
    }

    private Instant getInstant(ResultSet rs, String columnName) throws SQLException {
        var timestamp = rs.getTimestamp(columnName);
        return timestamp != null ? timestamp.toInstant() : null;
    }
}