package com.comet.opik.domain.topics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The 60 planted traces under {@code src/test/resources/topics/nightly/}: invented questions about a car's tire
 * pressure, with their vectors, that the nightly job tests plant a new theme with.
 */
final class TopicsNightlyFixture {

    static final int DIMENSIONS = 384;
    private static final String DIR = "/topics/nightly/";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    final String[] intents;
    final float[] vectors;

    private TopicsNightlyFixture() {
        var traces = json("traces.json").get("traces");
        intents = new String[traces.size()];
        for (int i = 0; i < traces.size(); i++) {
            intents[i] = traces.get(i).get("intent").asText();
        }
        vectors = floats("traces.f32");
    }

    static TopicsNightlyFixture load() {
        return new TopicsNightlyFixture();
    }

    private static JsonNode json(String name) {
        try {
            return MAPPER.readTree(bytes(name));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static float[] floats(String name) {
        var raw = bytes(name);
        var out = new float[raw.length / Float.BYTES];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out);
        return out;
    }

    private static byte[] bytes(String name) {
        try (InputStream stream = TopicsNightlyFixture.class.getResourceAsStream(DIR + name)) {
            if (stream == null) {
                throw new IllegalStateException("missing fixture file '%s'".formatted(name));
            }
            return stream.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
