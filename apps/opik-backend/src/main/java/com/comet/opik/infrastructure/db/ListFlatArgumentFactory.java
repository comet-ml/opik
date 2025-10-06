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
import java.util.List;
import java.util.Optional;

public class ListFlatArgumentFactory extends AbstractArgumentFactory<List<String>>
        implements
            ColumnMapper<List<String>> {

    public static final TypeReference<List<String>> TYPE_REFERENCE = new TypeReference<>() {
    };

    public ListFlatArgumentFactory() {
        super(Types.VARCHAR);
    }

    @Override
    protected Argument build(List<String> value, ConfigRegistry config) {
        return (position, statement, ctx) -> {
            if (value == null) {
                statement.setNull(position, Types.VARCHAR);
            } else {
                statement.setObject(position, JsonUtils.readTree(value).toString());
            }
        };
    }

    @Override
    public List<String> map(ResultSet r, int columnNumber, StatementContext ctx) throws SQLException {
        return Optional.ofNullable(r.getString(columnNumber))
                .map(value -> JsonUtils.readValue(value, TYPE_REFERENCE))
                .orElse(null);
    }

    @Override
    public List<String> map(ResultSet r, String columnLabel, StatementContext ctx) throws SQLException {
        return Optional.ofNullable(r.getString(columnLabel))
                .map(value -> JsonUtils.readValue(value, TYPE_REFERENCE))
                .orElse(null);
    }
}
