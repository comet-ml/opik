package com.comet.opik.domain.llmproviders;

import org.glassfish.jersey.server.ChunkedOutput;

public interface LlmProviderStreamHandler {
    void handleMessage(Object item, ChunkedOutput<String> chunkedOutput);
    void handleClose(ChunkedOutput<String> chunkedOutput);
    void handleError(Throwable throwable, ChunkedOutput<String> chunkedOutput);
}
