package com.comet.opik.infrastructure.db;

import com.comet.opik.api.DashboardScope;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;

@Slf4j
public class DashboardScopeColumnMapper implements ColumnMapper<DashboardScope> {

    @Override
    public DashboardScope map(ResultSet r, int columnNumber, StatementContext ctx) throws SQLException {
        String value = r.getString(columnNumber);
        if (value == null) {
            return null;
        }

        try {
            return DashboardScope.fromString(value);
        } catch (IllegalArgumentException exception) {
            log.warn("Failed to parse dashboard scope: '{}'", value, exception);
            return null;
        }
    }

    @Override
    public DashboardScope map(ResultSet r, String columnLabel, StatementContext ctx) throws SQLException {
        String value = r.getString(columnLabel);
        if (value == null) {
            return null;
        }

        try {
            return DashboardScope.fromString(value);
        } catch (IllegalArgumentException exception) {
            log.warn("Failed to parse dashboard scope: '{}'", value, exception);
            return null;
        }
    }
}
