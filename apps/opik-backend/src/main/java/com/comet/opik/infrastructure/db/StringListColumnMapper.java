package com.comet.opik.infrastructure.db;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Slf4j
public class StringListColumnMapper implements ColumnMapper<List<String>> {

    private static final TypeReference<List<String>> LIST_TYPE_REFERENCE = new TypeReference<>() {
    };

    @Override
    public List<String> map(ResultSet r, int columnNumber, StatementContext ctx) throws SQLException {
        return performMapping(r.getString(columnNumber));
    }

    @Override
    public List<String> map(ResultSet r, String columnLabel, StatementContext ctx) throws SQLException {
        return performMapping(r.getString(columnLabel));
    }

    private List<String> performMapping(String json) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }

        return JsonUtils.readValue(json, LIST_TYPE_REFERENCE);
    }
}
