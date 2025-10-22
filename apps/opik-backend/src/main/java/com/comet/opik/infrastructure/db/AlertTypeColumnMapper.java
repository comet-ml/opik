package com.comet.opik.infrastructure.db;

import com.comet.opik.api.AlertType;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;

@Slf4j
public class AlertTypeColumnMapper implements ColumnMapper<AlertType> {

    @Override
    public AlertType map(ResultSet r, int columnNumber, StatementContext ctx) throws SQLException {
        String value = r.getString(columnNumber);
        if (value == null) {
            return null;
        }

        try {
            return AlertType.fromString(value);
        } catch (IllegalArgumentException exception) {
            log.warn("Failed to parse alert_type: '{}'", value, exception);
            return null;
        }
    }

    @Override
    public AlertType map(ResultSet r, String columnLabel, StatementContext ctx) throws SQLException {
        String value = r.getString(columnLabel);
        if (value == null) {
            return null;
        }

        try {
            return AlertType.fromString(value);
        } catch (IllegalArgumentException exception) {
            log.warn("Failed to parse alert_type: '{}'", value, exception);
            return null;
        }
    }
}
