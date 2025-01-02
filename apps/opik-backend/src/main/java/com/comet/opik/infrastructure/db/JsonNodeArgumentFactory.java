package com.comet.opik.infrastructure.db;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;

import java.sql.Types;

public class JsonNodeArgumentFactory extends AbstractArgumentFactory<JsonNode> {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public JsonNodeArgumentFactory() {
        super(Types.OTHER);
    }

    @Override
    protected Argument build(JsonNode value, ConfigRegistry config) {
        return (position, statement, ctx) -> {
            try {
                if (value == null) {
                    statement.setNull(position, Types.NULL);
                } else {
                    // Convert JsonNode to a JSON string before binding
                    statement.setObject(position, objectMapper.writeValueAsString(value));
                }
            } catch (JsonProcessingException e) {
                // Wrap the exception in a RuntimeException to comply with the signature
                throw new IllegalArgumentException("Failed to serialize JsonNode to JSON string", e);
            }
        };
    }
}
