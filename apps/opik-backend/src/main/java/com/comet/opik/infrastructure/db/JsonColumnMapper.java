package com.comet.opik.infrastructure.db;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.comet.opik.utils.JsonUtils;
import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

public class JsonColumnMapper implements ColumnMapper<Object> {

    @Override
    public Object map(ResultSet r, int columnNumber, StatementContext ctx) throws SQLException {
        String json = r.getString(columnNumber);
        if (json == null) {
            return null;
        }
        
        try {
            // Try to parse as a generic Map first
            return JsonUtils.MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            // If that fails, return as JsonNode
            try {
                return JsonUtils.MAPPER.readTree(json);
            } catch (Exception ex) {
                throw new SQLException("Failed to parse JSON column", ex);
            }
        }
    }
}
