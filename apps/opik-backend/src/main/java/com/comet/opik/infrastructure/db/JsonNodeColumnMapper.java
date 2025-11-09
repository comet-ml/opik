package com.comet.opik.infrastructure.db;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;

@Slf4j
public class JsonNodeColumnMapper implements ColumnMapper<JsonNode> {

    @Override
    public JsonNode map(ResultSet r, int columnNumber, StatementContext ctx) throws SQLException {
        String json = r.getString(columnNumber);
        if (json == null || json.isBlank()) {
            return null;
        }

        try {
            return JsonUtils.readTree(json);
        } catch (Exception exception) {
            log.warn("Failed to parse JSON: '{}'", json, exception);
            return null;
        }
    }

    @Override
    public JsonNode map(ResultSet r, String columnLabel, StatementContext ctx) throws SQLException {
        String json = r.getString(columnLabel);
        if (json == null || json.isBlank()) {
            return null;
        }

        try {
            return JsonUtils.readTree(json);
        } catch (Exception exception) {
            log.warn("Failed to parse JSON: '{}'", json, exception);
            return null;
        }
    }
}
