package com.comet.opik.infrastructure.db;

import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;
import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.function.Function;

@Slf4j
public abstract class AbstractEnumColumnMapper<T extends Enum<T> & HasValue> extends AbstractArgumentFactory<T>
        implements
            ColumnMapper<T> {

    private final Function<String, T> fromString;
    private final String typeName;

    protected AbstractEnumColumnMapper(Function<String, T> fromString, String typeName) {
        super(Types.VARCHAR);
        this.fromString = fromString;
        this.typeName = typeName;
    }

    @Override
    protected Argument build(T value, ConfigRegistry config) {
        return (position, statement, ctx) -> {
            if (value == null) {
                statement.setNull(position, Types.VARCHAR);
            } else {
                statement.setString(position, value.getValue());
            }
        };
    }

    @Override
    public T map(ResultSet r, int columnNumber, StatementContext ctx) throws SQLException {
        return parse(r.getString(columnNumber));
    }

    @Override
    public T map(ResultSet r, String columnLabel, StatementContext ctx) throws SQLException {
        return parse(r.getString(columnLabel));
    }

    private T parse(String value) {
        if (value == null) {
            return null;
        }

        try {
            return fromString.apply(value);
        } catch (IllegalArgumentException exception) {
            log.warn("Failed to parse {}: '{}'", typeName, value, exception);
            return null;
        }
    }
}
