package com.comet.opik.infrastructure.db;

import com.comet.opik.domain.AgentBlueprint.BlueprintType;
import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;

public class BlueprintTypeColumnMapper implements ColumnMapper<BlueprintType> {
    @Override
    public BlueprintType map(ResultSet r, int columnNumber, StatementContext ctx) throws SQLException {
        String value = r.getString(columnNumber);
        return value == null ? null : BlueprintType.fromString(value);
    }
}
