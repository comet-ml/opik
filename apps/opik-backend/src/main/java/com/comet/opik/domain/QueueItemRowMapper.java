package com.comet.opik.domain;

import com.comet.opik.api.QueueItem;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

@Slf4j
public class QueueItemRowMapper implements RowMapper<QueueItem> {

    @Override
    public QueueItem map(ResultSet rs, StatementContext ctx) throws SQLException {
        try {
            return QueueItem.builder()
                    .id(getUUID(rs, "id"))
                    .queueId(getUUID(rs, "queue_id"))
                    .itemType(parseItemType(rs.getString("item_type")))
                    .itemId(rs.getString("item_id"))
                    .status(parseStatus(rs.getString("status")))
                    .assignedSme(rs.getString("assigned_sme"))
                    .createdAt(getInstant(rs, "created_at"))
                    .completedAt(getInstant(rs, "completed_at"))
                    .build();
        } catch (Exception e) {
            log.error("Error mapping QueueItem row", e);
            throw new SQLException("Failed to map QueueItem", e);
        }
    }

    private QueueItem.QueueItemType parseItemType(String itemType) {
        if (itemType == null) {
            return QueueItem.QueueItemType.TRACE;
        }
        try {
            return QueueItem.QueueItemType.valueOf(itemType.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown queue item type: {}", itemType);
            return QueueItem.QueueItemType.TRACE;
        }
    }

    private QueueItem.QueueItemStatus parseStatus(String status) {
        if (status == null) {
            return QueueItem.QueueItemStatus.PENDING;
        }
        try {
            return QueueItem.QueueItemStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown queue item status: {}", status);
            return QueueItem.QueueItemStatus.PENDING;
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