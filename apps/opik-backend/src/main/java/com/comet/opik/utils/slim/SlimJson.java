package com.comet.opik.utils.slim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializable;
import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;

/**
 * The full JSON of a payload ({@code input}, {@code output}) and its slim copy ({@code input_slim},
 * {@code output_slim}): the same JSON with every string value longer than {@link #MAX_STRING_LENGTH} cut to it and
 * followed by {@link #TRUNCATION_SUFFIX}, keys and structure kept.
 *
 * <p>The node is scanned first. With no long string, the slim copy is the full JSON. Otherwise both are written in
 * one walk of the node, the slim copy teed off the full one.
 */
@UtilityClass
public class SlimJson {

    public static final int MAX_STRING_LENGTH = 1000;
    public static final String TRUNCATION_SUFFIX = TruncatingGenerator.SUFFIX;

    /** The full and slim JSON strings of a payload node. */
    @Builder(toBuilder = true)
    public record JsonWithSlim(@NonNull String json, @NonNull String slim) {

        public static final JsonWithSlim EMPTY = JsonWithSlim.builder().json("").slim("").build();
    }

    /**
     * The full and slim JSON of a payload node, as values written into the caller's generator when the enclosing
     * value is serialized: embed them with {@code putRawValue(field, new RawValue(...))}, {@code json} first.
     */
    @Builder(toBuilder = true)
    public record RawJsonWithSlim(@NonNull JsonSerializable json, @NonNull JsonSerializable slim) {
    }

    /** Whether the slim JSON of the node differs from its full JSON. */
    public static boolean needsSlimming(@NonNull JsonNode node) {
        return LongStringScan.hasLongString(node, MAX_STRING_LENGTH);
    }

    /**
     * The full and slim JSON strings of a payload node. With nothing to cut, the slim copy is the full JSON, the same
     * {@code String} instance. For a node parsed from JSON, they match {@code node.toString()} and
     * {@code TruncationUtils.createSlimJsonString(node.toString())}.
     *
     * @param node the payload node (may be null)
     * @return the full and slim JSON strings, both empty if the node is null
     */
    public static JsonWithSlim toStrings(@Nullable JsonNode node) {
        if (node == null) {
            return JsonWithSlim.EMPTY;
        }
        if (!needsSlimming(node)) {
            String json = node.toString();
            return JsonWithSlim.builder().json(json).slim(json).build();
        }
        var full = new StringWriter();
        var slim = new StringWriter();
        try (var fullGenerator = WritableJson.NODE_WRITER.createGenerator(full);
                var tee = new TeeGenerator(fullGenerator,
                        new TruncatingGenerator(WritableJson.NODE_WRITER.createGenerator(slim), MAX_STRING_LENGTH))) {
            WritableJson.writeNode(node, tee);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return JsonWithSlim.builder().json(full.toString()).slim(slim.toString()).build();
    }

    /**
     * The full and slim JSON of a container payload node, written into a row without materializing either as a
     * {@code String}. With nothing to cut, both write the node. Otherwise the full JSON tees the slim copy into a
     * buffer as it is written, and the slim JSON writes that buffer raw.
     */
    public static RawJsonWithSlim toRaw(@NonNull JsonNode node) {
        if (!needsSlimming(node)) {
            var json = WritableJson.of(node);
            return RawJsonWithSlim.builder().json(json).slim(json).build();
        }
        var payload = new OneWalkPayload(node, MAX_STRING_LENGTH);
        return RawJsonWithSlim.builder().json(payload.full()).slim(payload.slim()).build();
    }
}
