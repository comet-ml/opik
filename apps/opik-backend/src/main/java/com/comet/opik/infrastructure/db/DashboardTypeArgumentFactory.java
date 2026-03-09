package com.comet.opik.infrastructure.db;

import com.comet.opik.api.DashboardType;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;

import java.sql.Types;

public class DashboardTypeArgumentFactory extends AbstractArgumentFactory<DashboardType> {
    public DashboardTypeArgumentFactory() {
        super(Types.VARCHAR);
    }

    @Override
    protected Argument build(DashboardType value, ConfigRegistry config) {
        return (position, statement, ctx) -> {
            if (value == null) {
                statement.setNull(position, Types.VARCHAR);
            } else {
                statement.setString(position, value.getValue());
            }
        };
    }
}
