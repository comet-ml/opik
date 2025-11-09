package com.comet.opik.infrastructure.db;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class StringListColumnMapper implements ColumnMapper<List<String>> {

    private static final TypeReference<List<String>> LIST_TYPE_REFERENCE = new TypeReference<>() {
    };

    @Override
    public List<String> map(ResultSet r, int columnNumber, StatementContext ctx) throws SQLException {
        String json = r.getString(columnNumber);
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }

        try {
            return JsonUtils.readValue(json, LIST_TYPE_REFERENCE);
        } catch (Exception exception) {
            log.warn("Failed to parse JSON array: '{}'", json, exception);
            return new ArrayList<>();
        }
    }

    @Override
    public List<String> map(ResultSet r, String columnLabel, StatementContext ctx) throws SQLException {
        String json = r.getString(columnLabel);
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }

        try {
            return JsonUtils.readValue(json, LIST_TYPE_REFERENCE);
        } catch (Exception exception) {
            log.warn("Failed to parse JSON array: '{}'", json, exception);
            return new ArrayList<>();
        }
    }
}
