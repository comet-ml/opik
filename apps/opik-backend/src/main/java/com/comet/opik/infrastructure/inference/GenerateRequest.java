package com.comet.opik.infrastructure.inference;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * {@code POST /v1/generate}: one completion, answered in the same call. {@code schema} constrains the output to a JSON
 * object; without it the output is text. Callers leave {@code request_id} and {@code deadline_ms} null: the client
 * sets one request id per call and the deadline left, cut to the lane's maximum.
 * <p>
 * The {@code schema} goes on the wire with its keys in the caller's order, not sorted like the rest of the body: the
 * service decodes the keys in schema order, so an author schema sorted to {@code other} before {@code topics} made
 * Qwen fill {@code other} first and leave {@code topics} empty.
 */
@Builder(toBuilder = true)
public record GenerateRequest(@NonNull String orgId, String workspaceId, String requestId, Long deadlineMs,
        boolean cache, @NonNull Lane lane, @NonNull List<Message> messages,
        @JsonSerialize(using = InOrder.class) JsonNode schema, int maxTokens,
        double temperature) {

    public GenerateRequest {
        messages = List.copyOf(messages);
    }

    /**
     * The pool the call runs in. {@code realtime} is for a user waiting on the answer; {@code batch} is for background
     * work, which takes a longer deadline and yields to realtime calls.
     */
    @Getter
    @RequiredArgsConstructor
    public enum Lane {
        REALTIME("realtime", Duration.ofSeconds(60)),
        BATCH("batch", Duration.ofSeconds(300));

        @JsonValue
        private final String value;
        /** The service rejects a {@code deadline_ms} above this. */
        private final Duration maxDeadline;
    }

    /** Writes a JSON value with its keys in insertion order. */
    static class InOrder extends JsonSerializer<JsonNode> {

        private static final ObjectMapper PLAIN = new ObjectMapper();

        @Override
        public void serialize(JsonNode value, JsonGenerator generator, SerializerProvider provider)
                throws IOException {
            generator.writeRawValue(PLAIN.writeValueAsString(value));
        }
    }
}
