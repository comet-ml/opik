package com.comet.opik.infrastructure.inference;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import lombok.Builder;
import lombok.NonNull;

import java.io.IOException;
import java.util.List;

/**
 * {@code POST /v1/generate}: one completion, answered in the same call. {@code schema} constrains the output to a JSON
 * object; without it the output is text. A caller may set {@code request_id} (a topics run sets its run id); without
 * one the client sets a new id per call. The client sends {@code deadline_ms}: the time left, cut to the lane's
 * maximum.
 * <p>
 * The {@code schema} goes on the wire with its keys in the caller's order, not sorted like the rest of the body: the
 * service decodes the keys in schema order, so an author schema sorted to {@code other} before {@code topics} made
 * Qwen fill {@code other} first and leave {@code topics} empty.
 */
@Builder(toBuilder = true)
public record GenerateRequest(@NonNull String orgId, String workspaceId, String requestId, boolean cache,
        @NonNull GenerateLane lane, @NonNull List<Message> messages,
        @JsonSerialize(using = InOrder.class) JsonNode schema,
        int maxTokens, double temperature) {

    public GenerateRequest {
        messages = List.copyOf(messages);
    }

    /** Writes a JSON value with its keys in insertion order: the canonical writer without its key sorting. */
    static class InOrder extends JsonSerializer<JsonNode> {

        private static final ObjectWriter IN_ORDER = InferenceJson.MAPPER.writer()
                .without(JsonNodeFeature.WRITE_PROPERTIES_SORTED);

        @Override
        public void serialize(JsonNode value, JsonGenerator generator, SerializerProvider provider)
                throws IOException {
            generator.writeRawValue(IN_ORDER.writeValueAsString(value));
        }
    }
}
