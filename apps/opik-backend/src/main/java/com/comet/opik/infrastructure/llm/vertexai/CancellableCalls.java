package com.comet.opik.infrastructure.llm.vertexai;

import lombok.NonNull;
import okhttp3.Call;
import okhttp3.Interceptor;
import okhttp3.Response;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the calls of one Vertex AI client, so that they can be cancelled. Its dispatcher can't do it: OkHttp counts a
 * call as finished once its headers arrive, while its body, a stream, is still being read.
 */
class CancellableCalls implements Interceptor {

    /** The calls of one client, which makes few: one, and any retries of it. */
    private final Set<Call> calls = ConcurrentHashMap.newKeySet();

    @Override
    public @NonNull Response intercept(@NonNull Chain chain) throws IOException {
        calls.add(chain.call());
        return chain.proceed(chain.request());
    }

    /** Cancels every call, which also ends a read blocked on its body. */
    void cancelAll() {
        calls.forEach(Call::cancel);
    }
}
