package com.comet.opik.infrastructure.db;

import com.comet.opik.api.DashboardScope;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;

import java.sql.Types;

public class DashboardScopeArgumentFactory extends AbstractArgumentFactory<DashboardScope> {
    public DashboardScopeArgumentFactory() {
        super(Types.VARCHAR);
    }

    @Override
    protected Argument build(DashboardScope value, ConfigRegistry config) {
        return (position, statement, ctx) -> {
            if (value == null) {
                statement.setNull(position, Types.VARCHAR);
            } else {
                statement.setString(position, value.getValue());
            }
        };
    }
}
