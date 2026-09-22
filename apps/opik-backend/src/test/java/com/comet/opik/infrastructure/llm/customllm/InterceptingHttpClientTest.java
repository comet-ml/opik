package com.comet.opik.infrastructure.llm.customllm;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for behavioural edge cases in the Custom LLM decorator that
 * are awkward to reach through the WireMock integration tests.
 */
@ExtendWith(MockitoExtension.class)
class InterceptingHttpClientTest {

    @Mock
    private HttpClient delegate;

    /**
     * Regression guard for the NPE risk flagged in PR review: if the
     * decorator is constructed with {@code configuration == null} and the
     * incoming URL carries a {@code {model}} placeholder, {@code mutate()}
     * must still route the request through the delegate without dereferencing
     * the null map. The constructor normalizes null to {@code Map.of()} so
     * {@code applyQueryParams} and {@code applyAuthHeaders} can call
     * {@code configuration.get(...)} safely.
     */
    @Test
    void nullConfigurationWithModelPlaceholderStillSubstitutesWithoutNpe() {
        when(delegate.execute(any(HttpRequest.class)))
                .thenReturn(SuccessfulHttpResponse.builder()
                        .statusCode(200)
                        .body("{}")
                        .build());

        var client = new InterceptingHttpClient(delegate, null, "dummy-key");
        var request = HttpRequest.builder()
                .method(HttpMethod.POST)
                .url("https://example.test/openai/deployments/{model}/chat/completions")
                .body("{\"model\":\"gpt-4o-mini-ZA\"}")
                .build();

        client.execute(request);

        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(delegate).execute(captor.capture());
        assertThat(captor.getValue().url())
                .isEqualTo("https://example.test/openai/deployments/gpt-4o-mini-ZA/chat/completions");
    }

    /**
     * Empty configuration + no placeholder is a pure pass-through — the
     * request object handed to the delegate must be identity-equal to the one
     * we received, proving zero allocations on the legacy no-op path.
     */
    @Test
    void emptyConfigurationAndNoPlaceholderIsPureNoOp() {
        when(delegate.execute(any(HttpRequest.class)))
                .thenReturn(SuccessfulHttpResponse.builder()
                        .statusCode(200)
                        .body("{}")
                        .build());

        var client = new InterceptingHttpClient(delegate, Map.of(), "dummy-key");
        var request = HttpRequest.builder()
                .method(HttpMethod.POST)
                .url("https://example.test/chat/completions")
                .body("{\"model\":\"gpt-4o\"}")
                .build();

        client.execute(request);

        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(delegate).execute(captor.capture());
        assertThat(captor.getValue()).isSameAs(request);
    }

    /**
     * Malformed JSON body in the request with a {@code {model}} placeholder
     * must not bubble the parse error up — it logs and forwards the URL
     * unchanged so the downstream provider sees what LangChain4j built.
     * Confirms the narrow {@code UncheckedIOException} catch from the Baz
     * review fix.
     */
    @Test
    void malformedJsonBodyWithPlaceholderForwardsUrlUnchanged() {
        when(delegate.execute(any(HttpRequest.class)))
                .thenReturn(SuccessfulHttpResponse.builder()
                        .statusCode(200)
                        .body("{}")
                        .build());

        var client = new InterceptingHttpClient(delegate, Map.of(), "dummy-key");
        var url = "https://example.test/openai/deployments/{model}/chat/completions";
        var request = HttpRequest.builder()
                .method(HttpMethod.POST)
                .url(url)
                .body("not-json{")
                .build();

        client.execute(request);

        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(delegate).execute(captor.capture());
        assertThat(captor.getValue().url()).isEqualTo(url);
    }

    /**
     * Query parameters must be appended before the fragment, not inside it.
     * A naive {@code contains("?")} check would treat a {@code ?} inside a
     * URL fragment as an existing query string and corrupt the output.
     * URIBuilder parses the fragment separately and reattaches it after the
     * rebuilt query.
     */
    @Test
    void queryParamsRespectExistingFragment() {
        when(delegate.execute(any(HttpRequest.class)))
                .thenReturn(SuccessfulHttpResponse.builder()
                        .statusCode(200)
                        .body("{}")
                        .build());

        var configuration = Map.of(
                "url_query_params", "{\"api-version\":\"2024-08-01-preview\"}");
        var client = new InterceptingHttpClient(delegate, configuration, "dummy-key");
        var request = HttpRequest.builder()
                .method(HttpMethod.POST)
                .url("https://example.test/chat/completions#section?notAQuery")
                .body("{\"model\":\"gpt-4o\"}")
                .build();

        client.execute(request);

        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(delegate).execute(captor.capture());
        assertThat(captor.getValue().url())
                .isEqualTo("https://example.test/chat/completions?api-version=2024-08-01-preview#section?notAQuery");
    }

    /**
     * Malformed URLs must not bubble a parse error up the call stack. The
     * decorator logs and forwards the URL unchanged, so the downstream
     * provider can return its own error instead of us crashing the request
     * pipeline.
     */
    @Test
    void malformedUrlIsForwardedUnchangedWithWarning() {
        when(delegate.execute(any(HttpRequest.class)))
                .thenReturn(SuccessfulHttpResponse.builder()
                        .statusCode(200)
                        .body("{}")
                        .build());

        var configuration = Map.of(
                "url_query_params", "{\"api-version\":\"2024-08-01-preview\"}");
        var client = new InterceptingHttpClient(delegate, configuration, "dummy-key");
        var malformed = "ht!tp://not a url";
        var request = HttpRequest.builder()
                .method(HttpMethod.POST)
                .url(malformed)
                .body("{\"model\":\"gpt-4o\"}")
                .build();

        client.execute(request);

        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(delegate).execute(captor.capture());
        assertThat(captor.getValue().url()).isEqualTo(malformed);
    }

    /**
     * {@code suppress_default_auth=true} must not cause the decorator to send
     * an unauthenticated request upstream. When no custom auth header is
     * configured, the suppression flag is ignored and the default
     * {@code Authorization} header is preserved. Guard against the foot-gun
     * flagged in PR review.
     */
    @Test
    void suppressDefaultAuthIgnoredWhenNoCustomHeaderConfigured() {
        when(delegate.execute(any(HttpRequest.class)))
                .thenReturn(SuccessfulHttpResponse.builder()
                        .statusCode(200)
                        .body("{}")
                        .build());

        var configuration = Map.of("suppress_default_auth", "true");
        var client = new InterceptingHttpClient(delegate, configuration, "dummy-key");
        var request = HttpRequest.builder()
                .method(HttpMethod.POST)
                .url("https://example.test/chat/completions")
                .addHeader("Authorization", "Bearer dummy-key")
                .body("{\"model\":\"gpt-4o\"}")
                .build();

        client.execute(request);

        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(delegate).execute(captor.capture());
        assertThat(captor.getValue().headers())
                .containsEntry("Authorization", List.of("Bearer dummy-key"));
    }

    // --- dynamic token auth ---

    private HttpRequest chatRequest() {
        return HttpRequest.builder()
                .method(HttpMethod.POST)
                .url("https://example.test/chat/completions")
                .body("{\"model\":\"gpt-4o\"}")
                .build();
    }

    private SuccessfulHttpResponse ok() {
        return SuccessfulHttpResponse.builder().statusCode(200).body("{}").build();
    }

    @Test
    void tokenIsInjectedAsBearerOnEveryRequest() {
        when(delegate.execute(any(HttpRequest.class))).thenReturn(ok());
        var client = new InterceptingHttpClient(delegate, Map.of(), null, () -> "tok-1", () -> {
        });

        client.execute(chatRequest());

        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(delegate).execute(captor.capture());
        assertThat(captor.getValue().headers()).containsEntry("Authorization", List.of("Bearer tok-1"));
    }

    @Test
    void tokenReplacesPreConfiguredStaticAuthorizationHeader() {
        when(delegate.execute(any(HttpRequest.class))).thenReturn(ok());
        var client = new InterceptingHttpClient(delegate, Map.of(), null, () -> "tok-1", () -> {
        });
        var request = HttpRequest.builder()
                .method(HttpMethod.POST)
                .url("https://example.test/chat/completions")
                .addHeader("Authorization", "Bearer stale-static-key")
                .addHeader("X-Extra", "kept")
                .body("{}")
                .build();

        client.execute(request);

        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(delegate).execute(captor.capture());
        assertThat(captor.getValue().headers())
                .containsEntry("Authorization", List.of("Bearer tok-1"))
                .containsEntry("X-Extra", List.of("kept"));
    }

    @Test
    void tokenGoesRawUnderConfiguredAuthHeaderName() {
        when(delegate.execute(any(HttpRequest.class))).thenReturn(ok());
        var configuration = Map.of("auth_header_name", "api-key");
        var client = new InterceptingHttpClient(delegate, configuration, null, () -> "tok-1", () -> {
        });
        var request = HttpRequest.builder()
                .method(HttpMethod.POST)
                .url("https://example.test/chat/completions")
                .addHeader("Authorization", "Bearer stale")
                .body("{}")
                .build();

        client.execute(request);

        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(delegate).execute(captor.capture());
        assertThat(captor.getValue().headers())
                .containsEntry("api-key", List.of("tok-1"))
                .doesNotContainKey("Authorization");
    }

    @Test
    void authFailureInvalidatesAndRetriesOnceWithAFreshToken() {
        var tokens = new java.util.concurrent.atomic.AtomicInteger();
        var invalidated = new java.util.concurrent.atomic.AtomicBoolean();
        when(delegate.execute(any(HttpRequest.class)))
                .thenThrow(new HttpException(401, "token rejected"))
                .thenReturn(ok());
        var client = new InterceptingHttpClient(delegate, Map.of(), null,
                () -> "tok-" + tokens.incrementAndGet(), () -> invalidated.set(true));

        client.execute(chatRequest());

        assertThat(invalidated).isTrue();
        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(delegate, times(2)).execute(captor.capture());
        assertThat(captor.getAllValues().getFirst().headers())
                .containsEntry("Authorization", List.of("Bearer tok-1"));
        assertThat(captor.getAllValues().getLast().headers())
                .containsEntry("Authorization", List.of("Bearer tok-2"));
    }

    @Test
    void a403WithATokenRejectionHintIsRetried() {
        var invalidated = new java.util.concurrent.atomic.AtomicBoolean();
        when(delegate.execute(any(HttpRequest.class)))
                .thenThrow(new HttpException(403,
                        "{\"error\": \"invalid_token\", \"error_description\": \"The access token expired\"}"))
                .thenReturn(ok());
        var client = new InterceptingHttpClient(delegate, Map.of(), null, () -> "tok", () -> invalidated.set(true));

        client.execute(chatRequest());

        assertThat(invalidated).isTrue();
        verify(delegate, times(2)).execute(any(HttpRequest.class));
    }

    @Test
    void aPolicy403IsNotRetried() {
        // a policy/quota/WAF 403 must not replay a non-idempotent LLM request
        // nor invalidate the shared token cache
        var invalidated = new java.util.concurrent.atomic.AtomicBoolean();
        when(delegate.execute(any(HttpRequest.class)))
                .thenThrow(new HttpException(403, "request blocked by content policy"));
        var client = new InterceptingHttpClient(delegate, Map.of(), null, () -> "tok", () -> invalidated.set(true));

        assertThatThrownBy(() -> client.execute(chatRequest()))
                .isInstanceOf(HttpException.class);
        assertThat(invalidated).isFalse();
        verify(delegate, times(1)).execute(any(HttpRequest.class));
    }

    @Test
    void authFailureIsRetriedExactlyOnce() {
        when(delegate.execute(any(HttpRequest.class))).thenThrow(new HttpException(401, "still rejected"));
        var client = new InterceptingHttpClient(delegate, Map.of(), null, () -> "tok", () -> {
        });

        assertThatThrownBy(() -> client.execute(chatRequest()))
                .isInstanceOf(HttpException.class);
        verify(delegate, times(2)).execute(any(HttpRequest.class));
    }

    @Test
    void nonAuthFailureIsNotRetried() {
        when(delegate.execute(any(HttpRequest.class))).thenThrow(new HttpException(500, "gateway down"));
        var client = new InterceptingHttpClient(delegate, Map.of(), null, () -> "tok", () -> {
        });

        assertThatThrownBy(() -> client.execute(chatRequest()))
                .isInstanceOf(HttpException.class);
        verify(delegate, times(1)).execute(any(HttpRequest.class));
    }

    @Test
    void streamedAuthFailureRetriesOnlyBeforeTheFirstDeliveredEvent() {
        var downstream = mock(ServerSentEventListener.class);
        var parser = mock(ServerSentEventParser.class);
        var tokens = new java.util.concurrent.atomic.AtomicInteger();
        var client = new InterceptingHttpClient(delegate, Map.of(), null,
                () -> "tok-" + tokens.incrementAndGet(), () -> {
                });

        client.execute(chatRequest(), parser, downstream);

        var requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        var listenerCaptor = ArgumentCaptor.forClass(ServerSentEventListener.class);
        verify(delegate).execute(requestCaptor.capture(), any(), listenerCaptor.capture());
        assertThat(requestCaptor.getValue().headers()).containsEntry("Authorization", List.of("Bearer tok-1"));

        // 401 arrives before any event: the wrapper retries once, straight to the downstream listener
        listenerCaptor.getValue().onError(new HttpException(401, "token rejected"));

        verify(delegate).execute(requestCaptor.capture(), any(), same(downstream));
        assertThat(requestCaptor.getValue().headers()).containsEntry("Authorization", List.of("Bearer tok-2"));
        verify(downstream, never()).onError(any());
    }

    @Test
    void streamedAuthFailureAfterAnEventPropagatesWithoutRetry() {
        var downstream = mock(ServerSentEventListener.class);
        var parser = mock(ServerSentEventParser.class);
        var client = new InterceptingHttpClient(delegate, Map.of(), null, () -> "tok", () -> {
        });

        client.execute(chatRequest(), parser, downstream);

        var listenerCaptor = ArgumentCaptor.forClass(ServerSentEventListener.class);
        verify(delegate).execute(any(HttpRequest.class), any(), listenerCaptor.capture());

        var event = new ServerSentEvent("message", "chunk");
        listenerCaptor.getValue().onEvent(event);
        var failure = new HttpException(401, "cut mid-stream");
        listenerCaptor.getValue().onError(failure);

        verify(downstream).onEvent(event);
        verify(downstream).onError(failure);
        // no second execute: content already flowed, splicing two half-responses would be worse
        verify(delegate, times(1)).execute(any(HttpRequest.class), any(), any());
    }
}
