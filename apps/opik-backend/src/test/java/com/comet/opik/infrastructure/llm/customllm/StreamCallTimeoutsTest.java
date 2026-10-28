package com.comet.opik.infrastructure.llm.customllm;

import okhttp3.Call;
import okhttp3.Interceptor;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Over the wire, OkHttp hands a network interceptor an empty body rather than none, so a reply without one is built
 * here: the interceptor treats it as no stream and passes it on.
 */
class StreamCallTimeoutsTest {

    private static Stream<Arguments> aReplyWithoutABodyIsPassedOn() {
        var readTimeout = Duration.ofSeconds(1);
        var streamReadTimeout = Duration.ofSeconds(5);
        return Stream.of(
                arguments("streams only", StreamCallTimeouts.forStreams(readTimeout, streamReadTimeout)),
                arguments("event streams", StreamCallTimeouts.forEventStreams(readTimeout, streamReadTimeout)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void aReplyWithoutABodyIsPassedOn(String interceptorName, StreamCallTimeouts interceptor) throws Exception {
        var request = new Request.Builder().url("http://localhost/v1/responses").build();
        var expectedResponse = new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(204)
                .message("No Content")
                .build();
        var chain = mock(Interceptor.Chain.class);
        when(chain.request()).thenReturn(request);
        when(chain.call()).thenReturn(mock(Call.class));
        when(chain.proceed(any())).thenReturn(expectedResponse);

        var actualResponse = interceptor.intercept(chain);

        assertThat(actualResponse).isSameAs(expectedResponse);
    }
}
