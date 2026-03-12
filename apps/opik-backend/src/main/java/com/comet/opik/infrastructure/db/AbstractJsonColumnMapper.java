package com.comet.opik.infrastructure.db;

import com.comet.opik.utils.JsonUtils;
import org.apache.commons.lang3.StringUtils;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;
import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

public abstract class AbstractJsonColumnMapper<T> extends AbstractArgumentFactory<T>
        implements
            ColumnMapper<T> {

    protected AbstractJsonColumnMapper() {
        super(Types.VARCHAR);
    }

    @Override
    protected Argument build(T value, ConfigRegistry config) {
        return (position, statement, ctx) -> {
            if (value == null) {
                statement.setNull(position, Types.VARCHAR);
            } else {
                statement.setObject(position, JsonUtils.writeValueAsString(value));
            }
        };
    }

    @Override
    public T map(ResultSet r, int columnNumber, StatementContext ctx) throws SQLException {
        return deserialize(r.getString(columnNumber));
    }

    @Override
    public T map(ResultSet r, String columnLabel, StatementContext ctx) throws SQLException {
        return deserialize(r.getString(columnLabel));
    }

    protected boolean isBlank(String json) {
        return StringUtils.isBlank(json);
    }

    protected abstract T deserialize(String json);
}
