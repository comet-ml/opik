package com.comet.opik.utils;

import lombok.Getter;

import java.io.Writer;

/**
 * {@link Writer} that only counts the characters written and holds no buffer, so the serialized size of a
 * value can be measured by streaming it through the writer instead of materializing its full string.
 * Single-use: create a fresh instance per measurement.
 */
@Getter
final class CountingWriter extends Writer {

    private long count;

    @Override
    public void write(char[] buffer, int offset, int length) {
        count += length;
    }

    @Override
    public void write(int character) {
        count++;
    }

    @Override
    public void write(String string, int offset, int length) {
        count += length;
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
}
