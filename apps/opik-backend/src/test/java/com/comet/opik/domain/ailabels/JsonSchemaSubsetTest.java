package com.comet.opik.domain.ailabels;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/** Mirrors opik-inference's {@code tests/unit/core/test_schema_subset.py} for {@code validate_schema}. */
class JsonSchemaSubsetTest {

    private static final String TAGS = """
            {"type": "object",
             "properties": {
               "tags": {"type": "array", "items": {"type": "string", "maxLength": 40}, "maxItems": 5},
               "sentiment": {"enum": ["pos", "neg"]}},
             "required": ["tags"],
             "additionalProperties": false}
            """;

    @ParameterizedTest
    @ValueSource(strings = {
            TAGS,
            """
                    {"$defs": {"t": {"type": "string"}}, "anyOf": [{"$ref": "#/$defs/t"}, {"type": "null"}]}""",
            """
                    {"$defs": {"tag": {"type": "string"}}, "type": "object",
                     "properties": {"a": {"$ref": "#/$defs/tag"}, "b": {"type": "array", "items": {"$ref": "#/$defs/tag"}}}}""",
            """
                    {"type": "array", "items": {"type": "string", "format": "date"}}""",
            """
                    {"type": "object", "additionalProperties": {"type": "integer", "minimum": 0}}""",
            """
                    {"type": "object", "additionalProperties": false}""",
            """
                    {"type": ["string", "null"], "pattern": "^[a-z]+$", "minLength": 1, "title": "t", "description": "d"}"""
    })
    void supportedSchemaPasses(String schema) {
        assertThatCode(() -> JsonSchemaSubset.validate(json(schema))).doesNotThrowAnyException();
    }

    static Stream<Arguments> invalidSchemas() {
        return Stream.of(
                arguments("""
                        {"type": "object", "properties": {"x": {"not": {"type": "string"}}}}""",
                        "/properties/x: unsupported keywords [not]"),
                arguments("""
                        {"type": "array", "items": {"not": {"type": "string"}}}""", "unsupported keywords"),
                arguments("""
                        {"type": "array", "items": {"type": "array", "items": {"if": {}}}}""", "unsupported keywords"),
                arguments("""
                        {"type": "object", "additionalProperties": {"not": {"type": "string"}}}""",
                        "unsupported keywords"),
                arguments(
                        """
                                {"type": "object", "additionalProperties": {"type": "object", "properties": {"x": {"contains": {}}}}}""",
                        "unsupported keywords"),
                arguments(
                        """
                                {"type": "object", "properties": {"a": {"type": "array", "items": {"type": "string", "$comment": "x"}}}}""",
                        "unsupported keywords"),
                arguments("""
                        {"type": 5}""", "'type' must be"),
                arguments("""
                        {"type": "text"}""", "'type' must be"),
                arguments("""
                        {"required": "summary"}""", "'required' must be"),
                arguments("""
                        {"maxLength": -1}""", "'maxLength' must be"),
                arguments("""
                        {"$ref": "#/$defs/missing"}""", "must be a local #/ pointer"),
                arguments("""
                        {"$ref": "http://example.com/schema.json"}""", "must be a local #/ pointer"),
                arguments("""
                        {"type": "string", "pattern": "("}""", "invalid regex pattern"),
                arguments("""
                        {"$defs": {"a": {"$ref": "#/$defs/a"}}, "$ref": "#/$defs/a"}""", "recursive $ref"),
                arguments("""
                        {"$defs": {"a": {"$ref": "#/$defs/b"}, "b": {"$ref": "#/$defs/a"}}, "$ref": "#/$defs/a"}""",
                        "recursive $ref"),
                arguments(
                        """
                                {"$defs": {"node": {"type": "object", "properties": {"children": {"type": "array", "items": {"$ref": "#/$defs/node"}}}}},
                                 "$ref": "#/$defs/node"}""",
                        "recursive $ref"),
                arguments("""
                        {"type": "object", "properties": {"self": {"$ref": "#/"}}}""", "recursive $ref"),
                arguments("""
                        {"type": "object", "properties": {"x": 5}}""", "a schema must be an object or boolean"));
    }

    @ParameterizedTest
    @MethodSource("invalidSchemas")
    void unsupportedOrInvalidSchemaIsRejected(String schema, String reason) {
        assertThatThrownBy(() -> JsonSchemaSubset.validate(json(schema)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("invalid_schema: ")
                .hasMessageContaining(reason);
    }

    @Test
    void deeplyNestedSchemaIsRejected() {
        JsonNode schema = json("{\"type\": \"string\"}");
        for (int level = 0; level < 500; level++) {
            var wrapper = JsonUtils.createObjectNode();
            wrapper.put("type", "array");
            wrapper.set("items", schema);
            schema = wrapper;
        }
        var nested = schema;

        assertThatThrownBy(() -> JsonSchemaSubset.validate(nested))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too deeply nested");
    }

    @ParameterizedTest
    @ValueSource(strings = {"#/$defs/a~1b", "#/$defs/a~0b", "#/$defs/a%20b", "#/anyOf/1", "#/properties/p/anyOf/0"})
    void refResolutionHandlesPointerEscapesAndListIndexes(String ref) {
        var schema = (ObjectNode) json("""
                {"$defs": {"a/b": {"type": "string"}, "a~b": {"type": "string"}, "a b": {"type": "string"}},
                 "anyOf": [{"type": "string"}, {"type": "integer"}],
                 "properties": {"p": {"anyOf": [{"type": "string"}]}},
                 "type": "object"}""");
        ((ObjectNode) schema.get("properties")).putObject("q").put("$ref", ref);

        assertThatCode(() -> JsonSchemaSubset.validate(schema)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"#/anyOf/2", "#/anyOf/-1", "#/anyOf/01", "#/anyOf/x", "#/$defs/a~2b", "#/$defs/nope",
            "#/anyOf/0/type/x"})
    void refResolutionRejectsBadPointers(String ref) {
        var schema = (ObjectNode) json("""
                {"$defs": {"a": {"type": "string"}}, "anyOf": [{"type": "string"}, {"type": "integer"}]}""");
        schema.putObject("properties").putObject("q").put("$ref", ref);

        assertThatThrownBy(() -> JsonSchemaSubset.validate(schema))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("invalid_schema: ");
    }

    private static JsonNode json(String value) {
        return JsonUtils.getJsonNodeFromString(value);
    }
}
