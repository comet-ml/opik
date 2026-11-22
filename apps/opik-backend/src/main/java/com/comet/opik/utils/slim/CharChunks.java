package com.comet.opik.utils.slim;

import com.fasterxml.jackson.core.JsonGenerator;
import lombok.NonNull;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * A char buffer kept as chunks that double in size, up to {@link #MAX_CHUNK}, and are never copied: growing a single
 * array would copy everything written so far each time it doubles. Written raw by {@link #writeTo}, chunk by chunk,
 * with no {@code String} copy. Not thread-safe.
 */
final class CharChunks extends Writer {

    static final int FIRST_CHUNK = 1024;
    static final int MAX_CHUNK = 64 * 1024;

    private final List<char[]> full = new ArrayList<>();
    private char[] chunk = new char[FIRST_CHUNK];
    private int used;

    private void ensureSpace() {
        if (used == chunk.length) {
            full.add(chunk);
            chunk = new char[Math.min(chunk.length * 2, MAX_CHUNK)];
            used = 0;
        }
    }

    @Override
    public void write(char[] source, int offset, int length) {
        while (length > 0) {
            ensureSpace();
            int count = Math.min(length, chunk.length - used);
            System.arraycopy(source, offset, chunk, used, count);
            used += count;
            offset += count;
            length -= count;
        }
    }

    @Override
    public void write(String source, int offset, int length) {
        while (length > 0) {
            ensureSpace();
            int count = Math.min(length, chunk.length - used);
            source.getChars(offset, offset + count, chunk, used);
            used += count;
            offset += count;
            length -= count;
        }
    }

    @Override
    public void write(int character) {
        ensureSpace();
        chunk[used++] = (char) character;
    }

    /**
     * Writes the buffered text as one raw value: the first chunk through {@code writeRawValue}, so the generator
     * places it in its context, the rest appended raw.
     */
    void writeTo(@NonNull JsonGenerator generator) throws IOException {
        if (full.isEmpty()) {
            generator.writeRawValue(chunk, 0, used);
            return;
        }
        generator.writeRawValue(full.getFirst(), 0, full.getFirst().length);
        for (int index = 1; index < full.size(); index++) {
            generator.writeRaw(full.get(index), 0, full.get(index).length);
        }
        generator.writeRaw(chunk, 0, used);
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
}
