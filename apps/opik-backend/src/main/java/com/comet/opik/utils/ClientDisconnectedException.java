package com.comet.opik.utils;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The caller of a stream is gone: its output is closed or can't be written to. Whoever is producing the stream stops,
 * cancelling the provider call that feeds it.
 */
public class ClientDisconnectedException extends UncheckedIOException {

    public ClientDisconnectedException(IOException cause) {
        super("The client of the stream disconnected", cause);
    }
}
