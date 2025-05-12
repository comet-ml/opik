package com.comet.opik.infrastructure.db;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;
import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Map;
import java.util.Optional;

public class MapFlatArgumentFactory extends AbstractArgumentFactory<Map<String, String>>
        implements
            ColumnMapper<Map<String, String>> {

    public static final TypeReference<Map<String, String>> TYPE_REFERENCE = new TypeReference<>() {
    };

    public MapFlatArgumentFactory() {
        super(Types.VARCHAR);
    }

    @Override
    protected Argument build(Map<String, String> value, ConfigRegistry config) {
        return (position, statement, ctx) -> {
            if (value == null) {
                statement.setNull(position, Types.VARCHAR);
            } else {
                statement.setObject(position, JsonUtils.readTree(value).toString());
            }
        };
    }

    @Override
    public Map<String, String> map(ResultSet r, int columnNumber, StatementContext ctx) throws SQLException {
        return Optional.ofNullable(r.getString(columnNumber))
                .map(value -> JsonUtils.readValue(value, TYPE_REFERENCE))
                .orElse(null);
    }

    @Override
    public Map<String, String> map(ResultSet r, String columnLabel, StatementContext ctx) throws SQLException {
        return Optional.ofNullable(r.getString(columnLabel))
                .map(value -> JsonUtils.readValue(value, TYPE_REFERENCE))
                .orElse(null);
    }
}
