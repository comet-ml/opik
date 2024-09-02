package com.comet.opik.infrastructure.db;

import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

public class InstantColumnMapper implements ColumnMapper<Instant> {

    @Override
    public Instant map(ResultSet rs, int columnNumber, StatementContext ctx) throws SQLException {
        return Optional.ofNullable(rs.getTimestamp(columnNumber))
                .map(timestamp -> timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC))
                .orElse(null);
    }

    @Override
    public Instant map(ResultSet rs, String columnLabel, StatementContext ctx) throws SQLException {
        return Optional.ofNullable(rs.getTimestamp(columnLabel))
                .map(timestamp -> timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC))
                .orElse(null);
    }

}
