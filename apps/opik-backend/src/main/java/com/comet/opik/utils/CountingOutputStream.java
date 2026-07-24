package com.comet.opik.utils;

import lombok.Getter;

import java.io.OutputStream;

/**
 * {@link OutputStream} that only counts the bytes written and holds no buffer, so the UTF-8 serialized
 * size of a value can be measured by streaming it through the stream instead of materializing its full
 * byte array. Single-use: create a fresh instance per measurement.
 */
@Getter
final class CountingOutputStream extends OutputStream {

    private long count;

    @Override
    public void write(int b) {
        count++;
    }

    @Override
    public void write(byte[] buffer, int offset, int length) {
        count += length;
    }
}
