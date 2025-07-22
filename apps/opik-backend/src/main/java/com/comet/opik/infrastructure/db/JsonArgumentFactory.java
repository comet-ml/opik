package com.comet.opik.infrastructure.db;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;

import java.sql.Types;

public class JsonArgumentFactory extends AbstractArgumentFactory<Object> {

    public JsonArgumentFactory() {
        super(Types.OTHER);
    }

    @Override
    protected Argument build(Object value, ConfigRegistry config) {
        return (position, statement, ctx) -> {
            try {
                if (value == null) {
                    statement.setNull(position, Types.NULL);
                } else {
                    // Convert object to JSON string using the application's JsonUtils
                    statement.setObject(position, JsonUtils.MAPPER.writeValueAsString(value));
                }
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Failed to serialize object to JSON string", e);
            }
        };
    }
}
