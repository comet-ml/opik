package com.comet.opik.infrastructure.redaction;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.Environment;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.Project;
import com.comet.opik.api.Prompt;
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
 * Applies the active rule set to every string a response writes, on the mapper that serializes responses, so a
 * new endpoint is covered the moment it exists.
 * <p>
 * Two serializers, because a {@code JsonNode} writes its own children through {@code JsonSerializable}: the one
 * registered for {@code String} is never consulted for trace and span payloads, so the tree is walked by hand.
 * Only values are rewritten; field names are written verbatim.
 * <p>
 * Exemptions go through a {@link BeanSerializerModifier} so they reach declared properties and nothing else.
 * Several DTOs carry {@code Map<String, String>} metadata whose keys the caller chooses, and a name-based check
 * would exempt the value of any entry a caller happened to call {@code id} or {@code model}.
 */
public class RedactionModule extends SimpleModule {

    /**
     * Values that must survive redaction because something resolves by them. Listed in both spellings, since
     * a DTO that does not apply the naming strategy reports camelCase.
     */
    private static final Set<String> EXEMPT_PROPERTIES = Set.of(
            // Addressed by name; redacting them breaks lookup.
            "project_name", "projectName",
            "dataset_name", "datasetName",
            "prompt_name", "promptName",
            "thread_id", "threadId",
            // Identifiers typed as String rather than UUID.
            "id", "workspace_id", "workspaceId",
            // Vendor names the UI filters by. Exempt by decision: anything put here is returned as stored.
            "model", "provider", "providers",
            // Version and cost lookup keys.
            "commit", "version_number", "versionNumber",
            "total_estimated_cost_version", "totalEstimatedCostVersion",
            // API metadata, never caller content.
            "sortable_by", "sortableBy");

    /**
     * Entities whose {@code name} addresses them, so it has to survive. Their create endpoints are get-or-create and
     * the SDK replays the name it was handed, so a rewritten one does not fail — it quietly creates a second entity
     * under the replacement text. Listed explicitly so a new entity stays redacted until someone decides otherwise.
     * {@code Trace.name} and {@code Span.name} are absent: free text the caller writes per call.
     */
    private static final Set<Class<?>> NAME_ADDRESSED_ENTITIES = Set.of(
            Dataset.class, Project.class, Prompt.class, Experiment.class, Environment.class);

    private static final String NAME_PROPERTY = "name";

    /**
     * The single definition of what survives redaction, shared with {@link JsonNodeRedactor}.
     * <p>
     * A streamed response is rewritten by hand rather than through the serializer, so without this the two paths
     * hold two copies of one policy and drift: the streamed items skipped these exemptions entirely, returning
     * rewritten {@code thread_id}, {@code id} and {@code model} values from the search endpoints the SDK uses
     * while the paged endpoints returned them intact.
     *
     * @param beanClass    the DTO being written, which decides whether {@code name} addresses the entity
     * @param propertyName as serialized
     */
    static boolean isExemptProperty(Class<?> beanClass, String propertyName) {
        return EXEMPT_PROPERTIES.contains(propertyName)
                || (NAME_PROPERTY.equals(propertyName) && NAME_ADDRESSED_ENTITIES.contains(beanClass));
    }

    public RedactionModule() {
        addSerializer(String.class, new RedactingStringSerializer());
        addSerializer(JsonNode.class, new RedactingJsonNodeSerializer());
        setSerializerModifier(new ExemptStructuralProperties());
    }

    /**
     * Covers both a bare {@code String} and a collection of them ({@code sortableBy}, {@code providers}).
     */
    private static class ExemptStructuralProperties extends BeanSerializerModifier {

        @Override
        public List<BeanPropertyWriter> changeProperties(SerializationConfig config, BeanDescription beanDesc,
                List<BeanPropertyWriter> beanProperties) {

            for (BeanPropertyWriter property : beanProperties) {
                if (!isExempt(beanDesc, property.getName())) {
                    continue;
                }

                var type = property.getType();
                boolean text = type.hasRawClass(String.class);
                boolean textCollection = type.isCollectionLikeType()
                        && type.getContentType() != null
                        && type.getContentType().hasRawClass(String.class);

                // hasSerializer guard: assignSerializer throws IllegalStateException when one is already set,
                // and the exempt names (id, name, model, provider, commit) recur across many DTOs. Adding
                // @JsonSerialize to any field with one of those names would otherwise 500 that endpoint, with
                // nothing connecting the failure to redaction. An explicit per-field serializer should win here
                // anyway - Webhook.secretToken's masking serializer is the example that must not be overridden.
                if ((text || textCollection) && !property.hasSerializer()) {
                    property.assignSerializer(VerbatimSerializer.INSTANCE);
                }
            }

            return beanProperties;
        }

        private boolean isExempt(BeanDescription beanDesc, String propertyName) {
            return isExemptProperty(beanDesc.getBeanClass(), propertyName);
        }
    }

    /**
     * Writes straight to the generator, which is why a {@code UUID} or an {@code Instant} is already immune.
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
