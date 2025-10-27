package com.comet.opik.infrastructure.db;

import com.comet.opik.api.ChartPosition;
import com.comet.opik.api.ChartType;
import com.comet.opik.api.DashboardChart;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Custom JDBI RowMapper for DashboardChart that handles the mapping of
 * position columns (position_x, position_y, width, height) to ChartPosition object.
 */
public class DashboardChartRowMapper implements RowMapper<DashboardChart> {

    @Override
    public DashboardChart map(ResultSet rs, StatementContext ctx) throws SQLException {
        // Map position columns to ChartPosition object
        ChartPosition position = null;
        int positionX = rs.getInt("position_x");
        if (!rs.wasNull()) {
            int positionY = rs.getInt("position_y");
            int width = rs.getInt("width");
            int height = rs.getInt("height");
            position = ChartPosition.builder()
                    .x(positionX)
                    .y(positionY)
                    .width(width)
                    .height(height)
                    .build();
        }

        // Map timestamps
        Instant createdAt = null;
        Timestamp createdAtTimestamp = rs.getTimestamp("created_at");
        if (createdAtTimestamp != null) {
            createdAt = createdAtTimestamp.toInstant();
        }

        Instant lastUpdatedAt = null;
        Timestamp lastUpdatedAtTimestamp = rs.getTimestamp("last_updated_at");
        if (lastUpdatedAtTimestamp != null) {
            lastUpdatedAt = lastUpdatedAtTimestamp.toInstant();
        }

        // Build DashboardChart
        return DashboardChart.builder()
                .id(UUID.fromString(rs.getString("id")))
                .dashboardId(UUID.fromString(rs.getString("dashboard_id")))
                .name(rs.getString("name"))
                .description(rs.getString("description"))
                .chartType(ChartType.valueOf(rs.getString("chart_type").toUpperCase()))
                .position(position)
                .dataSeries(null) // Will be loaded separately by service
                .groupBy(null) // Will be loaded separately by service
                .createdAt(createdAt)
                .createdBy(rs.getString("created_by"))
                .lastUpdatedAt(lastUpdatedAt)
                .lastUpdatedBy(rs.getString("last_updated_by"))
                .build();
    }
}

