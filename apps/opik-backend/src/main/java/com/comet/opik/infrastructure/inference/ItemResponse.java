package com.comet.opik.infrastructure.inference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import lombok.Builder;
import lombok.NonNull;

import java.io.IOException;
import java.util.Map;

/** A successful item's response: decide or generate, told apart by the shape of {@code result}. */
public sealed interface ItemResponse {

    String requestId();

    Usage usage();

    boolean cacheHit();

    long latencyMs();

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record DecideResponse(@NonNull String requestId, @NonNull Usage usage, boolean cacheHit, long latencyMs,
            @NonNull DecideResult result) implements ItemResponse {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record GenerateResponse(@NonNull String requestId, @NonNull Usage usage, boolean cacheHit, long latencyMs,
            @NonNull GenerateResult result) implements ItemResponse {
    }

    /** One answer per question id. */
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record DecideResult(@NonNull Map<String, Answer> answers) {
    }

    /** {@code output} conforms to the item's schema; it is JSON null if the service returned null. */
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record GenerateResult(JsonNode output) {
    }

    /**
     * The wire's {@code DecideResponse | GenerateResponse} has no discriminator: only a decide result has
     * {@code answers}.
     */
    class Deserializer extends StdDeserializer<ItemResponse> {

        public Deserializer() {
            super(ItemResponse.class);
        }

        @Override
        public ItemResponse deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode node = parser.readValueAsTree();
            var type = node.path("result").has("answers") ? DecideResponse.class : GenerateResponse.class;
            return parser.getCodec().treeToValue(node, type);
        }
    }
}
