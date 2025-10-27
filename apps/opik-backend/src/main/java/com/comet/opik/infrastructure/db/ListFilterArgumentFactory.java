package com.comet.opik.infrastructure.db;

import com.comet.opik.api.filter.Filter;
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

public class ListFilterArgumentFactory extends AbstractArgumentFactory<List<Filter>>
        implements
            ColumnMapper<List<Filter>> {

    public static final TypeReference<List<Filter>> TYPE_REFERENCE = new TypeReference<>() {
    };

    public ListFilterArgumentFactory() {
        super(Types.VARCHAR);
    }

    @Override
    protected Argument build(List<Filter> value, ConfigRegistry config) {
        return (position, statement, ctx) -> {
            if (value == null || value.isEmpty()) {
                statement.setNull(position, Types.VARCHAR);
            } else {
                statement.setObject(position, JsonUtils.readTree(value).toString());
            }
        };
    }

    @Override
    public List<Filter> map(ResultSet r, int columnNumber, StatementContext ctx) throws SQLException {
        return Optional.ofNullable(r.getString(columnNumber))
                .map(value -> JsonUtils.readValue(value, TYPE_REFERENCE))
                .orElse(List.of());
    }

    @Override
    public List<Filter> map(ResultSet r, String columnLabel, StatementContext ctx) throws SQLException {
        return Optional.ofNullable(r.getString(columnLabel))
                .map(value -> JsonUtils.readValue(value, TYPE_REFERENCE))
                .orElse(List.of());
    }
}
