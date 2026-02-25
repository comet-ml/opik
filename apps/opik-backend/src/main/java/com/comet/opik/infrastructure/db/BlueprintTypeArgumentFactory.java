package com.comet.opik.infrastructure.db;

import com.comet.opik.domain.AgentBlueprint.BlueprintType;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;

import java.sql.Types;

public class BlueprintTypeArgumentFactory extends AbstractArgumentFactory<BlueprintType> {
    public BlueprintTypeArgumentFactory() {
        super(Types.VARCHAR);
    }

    @Override
    protected Argument build(BlueprintType value, ConfigRegistry config) {
        return (position, statement, ctx) -> {
            if (value == null) {
                statement.setNull(position, Types.VARCHAR);
            } else {
                statement.setString(position, value.getType());
            }
        };
    }
}
