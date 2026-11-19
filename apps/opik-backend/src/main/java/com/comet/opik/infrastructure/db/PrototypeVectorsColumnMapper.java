package com.comet.opik.infrastructure.db;

import com.comet.opik.domain.topics.PrototypeVectors;
import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;

/** Reads a vector BLOB as {@link PrototypeVectors}; a null column maps to null. See {@link PrototypeVectorsCodec}. */
public class PrototypeVectorsColumnMapper implements ColumnMapper<PrototypeVectors> {

    @Override
    public PrototypeVectors map(ResultSet r, int columnNumber, StatementContext ctx) throws SQLException {
        byte[] bytes = r.getBytes(columnNumber);
        if (bytes == null) {
            return null;
        }
        try {
            return PrototypeVectorsCodec.decode(bytes);
        } catch (IllegalArgumentException e) {
            throw new SQLException("Failed to read prototype vectors", e);
        }
    }
}
