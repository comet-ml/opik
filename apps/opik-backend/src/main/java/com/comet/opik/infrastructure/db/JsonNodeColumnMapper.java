package com.comet.opik.infrastructure.db;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.io.UncheckedIOException;
import java.sql.ResultSet;
import java.sql.SQLException;

public class JsonNodeColumnMapper implements ColumnMapper<JsonNode> {

    @Override
    public JsonNode map(ResultSet r, int columnNumber, StatementContext ctx) throws SQLException {
        String json = r.getString(columnNumber);
        if (json == null) {
            return null;
        }
        try {
            return JsonUtils.getJsonNodeFromString(json);
        } catch (UncheckedIOException e) {
            throw new SQLException("Failed to parse JSON string to JsonNode", e);
        }
    }
}
