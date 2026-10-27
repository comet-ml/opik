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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * The stage 2b fixture under {@code src/test/resources/topics/nightly/}: an Ollie sample with two planted themes, a
 * first build ({@code night0}) and three nightly updates ({@code night1} to {@code night3}), written by topics-lab's
 * {@code scripts/export_topics_nightly_fixture.py}. Topic ids there are a creation counter; here they become
 * {@code new UUID(0, id)}.
 */
final class TopicsNightlyFixture {

    static final int DIMENSIONS = 384;
    private static final String DIR = "/topics/nightly/";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    final String[] intents;
    final float[] vectors;
    final Instant[] createdAt;

    private TopicsNightlyFixture() {
        var traces = json("traces.json").get("traces");
        intents = new String[traces.size()];
        createdAt = new Instant[traces.size()];
        for (int i = 0; i < traces.size(); i++) {
            intents[i] = traces.get(i).get("intent").asText();
            createdAt[i] = Instant.parse(traces.get(i).get("created_at").asText());
        }
        vectors = floats("traces.f32");
    }

    static TopicsNightlyFixture load() {
        return new TopicsNightlyFixture();
    }

    static UUID id(long counter) {
        return new UUID(0, counter);
    }

    static long counter(UUID id) {
        return id.getLeastSignificantBits();
    }

    Night night(int q) {
        return new Night(q);
    }

    final class Night {
        final String dir;
        final JsonNode window;
        final JsonNode expected;
        final JsonNode clusters;
        final int[] traces;

        private Night(int q) {
            dir = "night" + q + "/";
            window = json(dir + "window.json");
            expected = json(dir + "expected.json");
            clusters = json(dir + "clusters.json");
            traces = TopicsFixture.ints(window.get("traces"));
        }

        Instant night() {
            return Instant.parse(window.get("night").asText());
        }

        LocalDate date() {
            return LocalDate.ofInstant(night(), ZoneOffset.UTC);
        }

        NightWindow nightWindow() {
            var texts = new String[traces.length];
            var at = new Instant[traces.length];
            var rows = new float[traces.length * DIMENSIONS];
            for (int r = 0; r < traces.length; r++) {
                texts[r] = intents[traces[r]];
                at[r] = createdAt[traces[r]];
                System.arraycopy(vectors, traces[r] * DIMENSIONS, rows, r * DIMENSIONS, DIMENSIONS);
            }
            return NightWindow.builder().texts(texts).vectors(rows).createdAt(at)
                    .from(Instant.parse(window.get("window_from").asText())).night(night()).dimensions(DIMENSIONS)
                    .build();
        }

        /** The saved state after this night, as {@code next} reads it. */
        NightState stateFor(Night next, LocalDate firstBuild) {
            var prototypes = floats(dir + "prototypes.f32");
            var owners = TopicsFixture.ints(expected.get("prototype_owners"));
            var topics = new ArrayList<NightState.Topic>();
            for (var topic : expected.get("topics")) {
                long counter = topic.get("id").asLong();
                var own = new ArrayList<float[]>();
                for (int p = 0; p < owners.length; p++) {
                    if (owners[p] == counter) {
                        own.add(row(prototypes, p));
                    }
                }
                var inactive = topic.get("inactive_since");
                topics.add(NightState.Topic.builder()
                        .id(id(counter))
                        .name(topic.get("name").asText())
                        .definition(topic.get("definition").asText())
                        .born(LocalDate.parse(topic.get("born").asText()))
                        .prototypes(own)
                        .lowNights(topic.get("low_nights").asInt())
                        .lo7(topic.get("lo7").asInt())
                        .hi7(topic.get("hi7").asInt())
                        .inactiveSince(inactive.isNull() ? null : LocalDate.parse(inactive.asText()))
                        .size(topic.get("size").asInt())
                        .build());
            }
            var other = new ArrayList<float[]>();
            for (int p = 0; p < owners.length; p++) {
                if (owners[p] == -1) {
                    other.add(row(prototypes, p));
                }
            }
            long[] nextId = {expected.get("next_id").asLong()};
            return NightState.builder()
                    .topics(topics)
                    .otherPrototypes(other)
                    .rebuilds(next.window.get("rebuilds_before").asInt())
                    .firstBuild(firstBuild)
                    .night(next.date())
                    .ids(() -> id(nextId[0]++))
                    .build();
        }

        float[] prototypes() {
            return floats(dir + "prototypes.f32");
        }

        String prompt(String tag) {
            return new String(bytes(dir + tag + "_prompt.txt"), StandardCharsets.UTF_8);
        }

        JsonNode answer(String tag) {
            return json(dir + tag + "_answer.json");
        }

        /** The recorded k-means result for a request name. */
        KMeans.Result cluster(String name) {
            var record = clusters.get(name);
            if (record == null) {
                throw new IllegalStateException("no recorded k-means '%s' in %s".formatted(name, dir));
            }
            return KMeans.Result.builder()
                    .labels(TopicsFixture.ints(record.get("unique_labels")))
                    .centres(floats(dir + "clusters_" + name + ".f32"))
                    .build();
        }
    }

    static float[] row(float[] rows, int p) {
        return Arrays.copyOfRange(rows, p * DIMENSIONS, (p + 1) * DIMENSIONS);
    }

    static List<String> strings(JsonNode array) {
        return TopicsFixture.strings(array);
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
