package com.comet.opik.infrastructure.db;

import com.comet.opik.domain.AgentConfigValue.ValueType;
import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;

public class ValueTypeColumnMapper implements ColumnMapper<ValueType> {
    @Override
    public ValueType map(ResultSet r, int columnNumber, StatementContext ctx) throws SQLException {
        String value = r.getString(columnNumber);
        return value == null ? null : ValueType.fromString(value);
    }
}
