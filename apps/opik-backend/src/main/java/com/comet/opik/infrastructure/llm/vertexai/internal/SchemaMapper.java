package com.comet.opik.infrastructure.llm.vertexai.internal;

import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import lombok.experimental.UtilityClass;

import java.util.Map;
import java.util.stream.Collectors;

@UtilityClass
class SchemaMapper {

    static GeminiSchema fromJsonSchemaToGSchema(JsonSchema jsonSchema) {
        return fromJsonSchemaToGSchema(jsonSchema.rootElement());
    }

    static GeminiSchema fromJsonSchemaToGSchema(JsonSchemaElement jsonSchema) {
        GeminiSchema.GeminiSchemaBuilder schemaBuilder = GeminiSchema.builder();

        switch (jsonSchema) {
            case JsonStringSchema jsonStringSchema -> {
                schemaBuilder.description(jsonStringSchema.description());
                schemaBuilder.type(GeminiType.STRING);
            }
            case JsonBooleanSchema jsonBooleanSchema -> {
                schemaBuilder.description(jsonBooleanSchema.description());
                schemaBuilder.type(GeminiType.BOOLEAN);
            }
            case JsonNumberSchema jsonNumberSchema -> {
                schemaBuilder.description(jsonNumberSchema.description());
                schemaBuilder.type(GeminiType.NUMBER);
            }
            case JsonIntegerSchema jsonIntegerSchema -> {
                schemaBuilder.description(jsonIntegerSchema.description());
                schemaBuilder.type(GeminiType.INTEGER);
            }
            case JsonEnumSchema jsonEnumSchema -> {
                schemaBuilder.description(jsonEnumSchema.description());
                schemaBuilder.type(GeminiType.STRING);
                schemaBuilder.enumeration(jsonEnumSchema.enumValues());
            }
            case JsonObjectSchema jsonObjectSchema -> {
                schemaBuilder.description(jsonObjectSchema.description());
                schemaBuilder.type(GeminiType.OBJECT);
                if (jsonObjectSchema.properties() != null) {
                    Map<String, JsonSchemaElement> properties = jsonObjectSchema.properties();
                    Map<String, GeminiSchema> mappedProperties = properties.entrySet().stream()
                            .collect(Collectors.toMap(Map.Entry::getKey,
                                    entry -> fromJsonSchemaToGSchema(entry.getValue())));
                    schemaBuilder.properties(mappedProperties);
                }

                if (jsonObjectSchema.required() != null) {
                    schemaBuilder.required(jsonObjectSchema.required());
                }
            }
            case JsonArraySchema jsonArraySchema -> {
                schemaBuilder.description(jsonArraySchema.description());
                schemaBuilder.type(GeminiType.ARRAY);
                if (jsonArraySchema.items() != null) {
                    schemaBuilder.items(fromJsonSchemaToGSchema(jsonArraySchema.items()));
                }
            }
            case null, default ->
                throw new IllegalArgumentException("Unsupported JsonSchemaElement type: " + jsonSchema.getClass());
        }

        return schemaBuilder.build();
    }

}
