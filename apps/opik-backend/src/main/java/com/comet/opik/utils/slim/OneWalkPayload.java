package com.comet.opik.utils.slim;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.NonNull;

import java.io.IOException;

/**
 * The full and slim JSON of a payload node from one walk of it. Writing {@link #full()} writes the full JSON and tees
 * the slim copy into a buffer, which {@link #slim()} then writes raw. A row writes them as consecutive fields, full
 * first; if slim is written first, it walks the node itself. Written once, on one thread, as a row is.
 */
final class OneWalkPayload {

    private final JsonNode node;
    private final int maxStringLength;

    /** The slim copy teed by the last write of {@link #full()}, until {@link #slim()} writes it. */
    private CharChunks slimText;

    OneWalkPayload(@NonNull JsonNode node, int maxStringLength) {
        this.node = node;
        this.maxStringLength = maxStringLength;
    }

    WritableJson full() {
        return this::writeFull;
    }

    WritableJson slim() {
        return this::writeSlim;
    }

    private void writeFull(JsonGenerator generator) throws IOException {
        var buffer = new CharChunks();
        try (var slimGenerator = WritableJson.NODE_WRITER.createGenerator(buffer)) {
            // Written as the row generator would write it in place.
            slimGenerator.overrideStdFeatures(generator.getFeatureMask(), ~0);
            slimGenerator.setCharacterEscapes(generator.getCharacterEscapes());
            if (generator.getHighestEscapedChar() > 0) {
                slimGenerator.setHighestNonEscapedChar(generator.getHighestEscapedChar());
            }
            WritableJson.writeNode(node, new TeeGenerator(generator,
                    new TruncatingGenerator(slimGenerator, maxStringLength)));
        }
        slimText = buffer;
    }

    private void writeSlim(JsonGenerator generator) throws IOException {
        if (slimText == null) {
            WritableJson.writeNode(node, new TruncatingGenerator(generator, maxStringLength));
            return;
        }
        CharChunks buffered = slimText;
        // Released as soon as it is written, not with the row.
        slimText = null;
        buffered.writeTo(generator);
    }
}
