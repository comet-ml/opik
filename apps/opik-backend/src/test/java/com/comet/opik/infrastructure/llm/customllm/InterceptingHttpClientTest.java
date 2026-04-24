package com.comet.opik.infrastructure.llm.customllm;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// Unit coverage for behavioural edge cases in the Custom LLM decorator that
/// are awkward to reach through the WireMock integration tests.
@ExtendWith(MockitoExtension.class)
class InterceptingHttpClientTest {

    @Mock
    private HttpClient delegate;

    /// Regression guard for the NPE risk flagged in PR review: if the decorator is
    /// constructed with `configuration == null` and the incoming URL carries a
    /// `{model}` placeholder, `mutate()` must still route the request through the
    /// delegate without dereferencing the null map. The constructor normalizes
    /// null to `Map.of()` so `applyQueryParams` and `applyAuthHeaders` can call
    /// `configuration.get(...)` safely.
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

    /// Empty configuration + no placeholder is a pure pass-through — the request
    /// object handed to the delegate must be identity-equal to the one we
    /// received, proving zero allocations on the legacy no-op path.
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

    /// Malformed JSON body in the request with a `{model}` placeholder must not
    /// bubble the parse error up — it logs and forwards the URL unchanged so
    /// the downstream provider sees what LangChain4j built. Confirms the narrow
    /// `UncheckedIOException` catch from the Baz review fix.
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
}
