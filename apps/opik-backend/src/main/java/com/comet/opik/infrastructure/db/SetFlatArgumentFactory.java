package com.comet.opik.infrastructure.db;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.collections4.CollectionUtils;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;
import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Optional;
import java.util.Set;

public class SetFlatArgumentFactory extends AbstractArgumentFactory<Set<String>>
        implements
            ColumnMapper<Set<String>> {

    public static final TypeReference<Set<String>> TYPE_REFERENCE = new TypeReference<>() {
    };

    public SetFlatArgumentFactory() {
        super(Types.VARCHAR);
    }

    @Override
    protected Argument build(Set<String> value, ConfigRegistry config) {
        return (position, statement, ctx) -> {
            if (value == null) {
                statement.setNull(position, Types.VARCHAR);
            } else {
                statement.setObject(position, JsonUtils.readTree(value).toString());
            }
        };
    }

    @Override
    public Set<String> map(ResultSet r, int columnNumber, StatementContext ctx) throws SQLException {
        return Optional.ofNullable(r.getString(columnNumber))
                .map(value -> JsonUtils.readValue(value, TYPE_REFERENCE))
                .filter(CollectionUtils::isNotEmpty)
                .orElse(null);
    }

    @Override
    public Set<String> map(ResultSet r, String columnLabel, StatementContext ctx) throws SQLException {
        return Optional.ofNullable(r.getString(columnLabel))
                .map(value -> JsonUtils.readValue(value, TYPE_REFERENCE))
                .filter(CollectionUtils::isNotEmpty)
                .orElse(null);
    }
}
