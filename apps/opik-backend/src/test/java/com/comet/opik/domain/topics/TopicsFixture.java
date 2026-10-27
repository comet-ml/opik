package com.comet.opik.domain.topics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The stage 2a fixture under {@code src/test/resources/topics/fixture/}: one small Ollie window and the stand's outputs
 * for it, written by topics-lab's {@code scripts/export_topics_fixture.py}.
 */
final class TopicsFixture {

    static final int DIMENSIONS = 384;
    private static final String DIR = "/topics/fixture/";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    final Instant night;
    final Instant windowFrom;
    final List<UUID> traceIds = new ArrayList<>();
    final List<Instant> createdAt = new ArrayList<>();
    final String[] intents;
    final float[] vectors;
    final float[] centres;
    final JsonNode expected;
    final JsonNode answer;
    final String authorPrompt;

    private TopicsFixture() {
        var window = json("window.json");
        night = Instant.parse(window.get("night").asText());
        windowFrom = Instant.parse(window.get("window_from").asText());
        var traces = window.get("traces");
        intents = new String[traces.size()];
        for (int i = 0; i < traces.size(); i++) {
            traceIds.add(UUID.fromString(traces.get(i).get("id").asText()));
            createdAt.add(Instant.parse(traces.get(i).get("created_at").asText()));
            intents[i] = traces.get(i).get("intent").asText();
        }
        vectors = floats("window.f32");
        centres = floats("centres.f32");
        expected = json("expected.json");
        answer = json("answer.json");
        authorPrompt = new String(bytes("author_prompt.txt"), StandardCharsets.UTF_8);
    }

    static TopicsFixture load() {
        return new TopicsFixture();
    }

    int size() {
        return intents.length;
    }

    int k() {
        return expected.get("k").asInt();
    }

    /** The scikit-learn seed-0 label of every trace, through its text. */
    int[] sklearnTraceLabels(IntentMerger.Merged merged) {
        var unique = ints(expected.get("sklearn").get("labels"));
        var out = new int[size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = unique[merged.inverse()[i]];
        }
        return out;
    }

    boolean[] recent() {
        var from = night.minus(java.time.Duration.ofDays(7));
        var out = new boolean[size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = !createdAt.get(i).isBefore(from);
        }
        return out;
    }

    static int[] ints(JsonNode array) {
        var out = new int[array.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = array.get(i).asInt();
        }
        return out;
    }

    static List<String> strings(JsonNode array) {
        var out = new ArrayList<String>(array.size());
        array.forEach(item -> out.add(item.asText()));
        return out;
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
        try (InputStream stream = TopicsFixture.class.getResourceAsStream(DIR + name)) {
            if (stream == null) {
                throw new IllegalStateException("missing fixture file '%s'".formatted(name));
            }
            return stream.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
