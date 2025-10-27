package com.comet.opik.infrastructure.db;

import com.comet.opik.api.AlertType;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;

import java.sql.Types;

public class AlertTypeArgumentFactory extends AbstractArgumentFactory<AlertType> {
    public AlertTypeArgumentFactory() {
        super(Types.VARCHAR);
    }

    @Override
    protected Argument build(AlertType value, ConfigRegistry config) {
        return (position, statement, ctx) -> {
            if (value == null) {
                statement.setNull(position, Types.VARCHAR);
            } else {
                statement.setString(position, value.getValue());
            }
        };
    }
}