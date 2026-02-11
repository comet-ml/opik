package com.comet.opik.infrastructure.db;

import com.comet.opik.api.EvaluatorType;
import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;

public class EvaluatorTypeColumnMapper implements ColumnMapper<EvaluatorType> {

    @Override
    public EvaluatorType map(ResultSet r, int columnNumber, StatementContext ctx) throws SQLException {
        String value = r.getString(columnNumber);
        return value == null ? null : EvaluatorType.fromString(value);
    }

    @Override
    public EvaluatorType map(ResultSet r, String columnLabel, StatementContext ctx) throws SQLException {
        String value = r.getString(columnLabel);
        return value == null ? null : EvaluatorType.fromString(value);
    }
}
