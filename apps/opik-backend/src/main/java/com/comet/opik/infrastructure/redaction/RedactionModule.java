package com.comet.opik.infrastructure.redaction;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.POJONode;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Applies the active rule set to every string a response writes.
 * <p>
 * Registered on the mapper that serializes responses, so a new endpoint is covered the moment it exists —
 * unlike an allowlist of fields, which protects only what somebody remembered to list.
 * <p>
 * Two serializers are needed. {@code String} covers ordinary properties. {@code JsonNode} covers trace and
 * span payloads, and it has to walk the tree by hand: a node writes its own children through
 * {@code JsonSerializable}, so nothing registered for the scalar types is ever consulted, and the strings
 * inside an input or output would sail straight through untouched.
 * <p>
 * Only values are rewritten — field names are written back verbatim.
 * <p>
 * A small set of structural properties is exempt. These are lookup keys and API metadata rather than content:
 * rewriting a {@code projectName} or a {@code threadId} does not conceal anything a caller could not obtain
 * anyway, and it breaks navigation and filtering with no error to show for it.
 * <p>
 * The exemption is applied through a {@link BeanSerializerModifier}, which reaches declared bean properties and
 * nothing else. That distinction is load-bearing: several DTOs carry {@code Map<String, String>} metadata whose
 * keys the caller chooses, and a name-based check against the generator's current field would exempt the value
 * of any entry a caller happened to call {@code id} or {@code model}. Matching on properties instead means map
 * entries and {@code JsonNode} content are redacted whatever they are named.
 */
public class RedactionModule extends SimpleModule {

    /**
     * Held in the serialized form, since that is what the generator reports — these DTOs are snake_case on the
     * wire. The camel-case spellings are kept alongside so a DTO that does not apply the naming strategy is
     * covered by the same set.
     */
    private static final Set<String> EXEMPT_PROPERTIES = Set.of(
            // Resolved by name elsewhere in the API; redacting them breaks lookup.
            "project_name", "projectName",
            "dataset_name", "datasetName",
            "prompt_name", "promptName",
            "thread_id", "threadId",
            "environment",
            // Identifiers that happen to be typed as String rather than UUID.
            "id", "workspace_id", "workspaceId",
            // Version and cost lookup keys.
            "commit", "version_number", "versionNumber", "model", "provider", "providers",
            "total_estimated_cost_version", "totalEstimatedCostVersion",
            // Pure API metadata, never caller content.
            "sortable_by", "sortableBy");

    public RedactionModule() {
        addSerializer(String.class, new RedactingStringSerializer());
        addSerializer(JsonNode.class, new RedactingJsonNodeSerializer());
        setSerializerModifier(new ExemptStructuralProperties());
    }

    /**
     * Swaps in a pass-through serializer for the exempt properties, covering both a bare {@code String} and a
     * collection of them ({@code sortableBy}, {@code providers}).
     */
    private static class ExemptStructuralProperties extends BeanSerializerModifier {

        @Override
        public List<BeanPropertyWriter> changeProperties(SerializationConfig config, BeanDescription beanDesc,
                List<BeanPropertyWriter> beanProperties) {

            for (BeanPropertyWriter property : beanProperties) {
                if (!EXEMPT_PROPERTIES.contains(property.getName())) {
                    continue;
                }

                var type = property.getType();
                boolean text = type.hasRawClass(String.class);
                boolean textCollection = type.isCollectionLikeType()
                        && type.getContentType() != null
                        && type.getContentType().hasRawClass(String.class);

                if (text || textCollection) {
                    property.assignSerializer(VerbatimSerializer.INSTANCE);
                }
            }

            return beanProperties;
        }
    }

    /**
     * Writes text straight to the generator. Values written that way never reach a registered serializer,
     * which is the same reason a {@code UUID} or an {@code Instant} is already immune to redaction.
     */
    private static class VerbatimSerializer extends JsonSerializer<Object> {

        private static final VerbatimSerializer INSTANCE = new VerbatimSerializer();

        @Override
        public void serialize(Object value, JsonGenerator generator, SerializerProvider provider)
                throws IOException {

            if (value instanceof String text) {
                generator.writeString(text);
                return;
            }

            generator.writeStartArray();
            for (Object element : (Collection<?>) value) {
                if (element == null) {
                    generator.writeNull();
                } else {
                    generator.writeString(element.toString());
                }
            }
            generator.writeEndArray();
        }
    }

    private static class RedactingStringSerializer extends JsonSerializer<String> {
        @Override
        public void serialize(String value, JsonGenerator generator, SerializerProvider provider)
                throws IOException {
            generator.writeString(RedactionContext.current().apply(value));
        }
    }

    private static class RedactingJsonNodeSerializer extends JsonSerializer<JsonNode> {

        @Override
        public void serialize(JsonNode node, JsonGenerator generator, SerializerProvider provider)
                throws IOException {
            write(node, generator, provider);
        }

        private void write(JsonNode node, JsonGenerator generator, SerializerProvider provider)
                throws IOException {

            switch (node.getNodeType()) {
                case OBJECT -> {
                    generator.writeStartObject();
                    var fields = node.fields();
                    while (fields.hasNext()) {
                        var field = fields.next();
                        generator.writeFieldName(field.getKey());
                        write(field.getValue(), generator, provider);
                    }
                    generator.writeEndObject();
                }
                case ARRAY -> {
                    generator.writeStartArray();
                    for (JsonNode child : node) {
                        write(child, generator, provider);
                    }
                    generator.writeEndArray();
                }
                case STRING -> generator.writeString(RedactionContext.current().apply(node.textValue()));
                case NUMBER -> writeNumber(node, generator);
                case BOOLEAN -> generator.writeBoolean(node.booleanValue());
                case BINARY -> generator.writeBinary(node.binaryValue());
                // Deliberately not generator.writeTree(node): that would route back through this serializer.
                case POJO -> provider.defaultSerializeValue(((POJONode) node).getPojo(), generator);
                default -> generator.writeNull();
            }
        }

        private void writeNumber(JsonNode node, JsonGenerator generator) throws IOException {
            if (node.isInt()) {
                generator.writeNumber(node.intValue());
            } else if (node.isLong()) {
                generator.writeNumber(node.longValue());
            } else if (node.isBigInteger()) {
                generator.writeNumber(node.bigIntegerValue());
            } else if (node.isBigDecimal()) {
                generator.writeNumber(node.decimalValue());
            } else if (node.isFloat()) {
                generator.writeNumber(node.floatValue());
            } else {
                generator.writeNumber(node.doubleValue());
            }
        }
    }
}
