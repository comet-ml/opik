package com.comet.opik.infrastructure.db;

import com.comet.opik.api.TemplateStructure;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;

import java.sql.Types;

public class TemplateStructureArgumentFactory extends AbstractArgumentFactory<TemplateStructure> {
    public TemplateStructureArgumentFactory() {
        super(Types.VARCHAR);
    }

    @Override
    protected Argument build(TemplateStructure value, ConfigRegistry config) {
        return (position, statement, ctx) -> {
            if (value == null) {
                statement.setString(position, TemplateStructure.TEXT.getValue());
            } else {
                statement.setString(position, value.getValue());
            }
        };
    }
}
