package com.comet.opik.infrastructure.llm.vertexai;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.google.cloud.vertexai.VertexAI;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

@DisplayName("Vertex AI provider")
class LlmProviderVertexAITest {

    private static final String MODEL = "vertex_ai/gemini-2.5-flash";

    private LlmProviderVertexAI providerStreaming(StreamingChatModel delegate, VertexAI vertexAI) {
        var generator = mock(VertexAIClientGenerator.class);
        when(generator.newVertexAIStreamingClient(any(), any()))
                .thenReturn(new CloseableVertexAiStreamingChatModel(delegate, vertexAI));
        return new LlmProviderVertexAI(generator,
                LlmProviderClientApiConfig.builder().apiKey("key").configuration(Map.of()).build());
    }

    @Test
    @DisplayName("a streaming client that fails to build notifies the caller instead of hanging")
    void streamingBuildFailureNotifiesTheCaller() throws Exception {
        var generator = new VertexAIClientGenerator(TestConfigUtils.loadConfigTest().getLlmProviderClient());
        // A malformed key makes the streaming client throw at build time, before the stream starts.
        var apiConfig = LlmProviderClientApiConfig.builder().apiKey("not-a-service-account").configuration(Map.of())
                .build();
        var provider = new LlmProviderVertexAI(generator, apiConfig);

        var error = new AtomicReference<Throwable>();
        var closed = new CountDownLatch(1);

        provider.generateStream(ChatCompletionRequest.builder().model(MODEL).build(), "workspace",
                message -> {
                },
                closed::countDown,
                error::set);

        assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(error.get()).isNotNull();
    }

    @Test
    @DisplayName("a synchronous chat failure delivers one error then closes the client")
    void streamingSynchronousFailureDeliversErrorThenClose() throws Exception {
        var vertexAI = mock(VertexAI.class);
        var errors = new AtomicInteger();
        var closes = new AtomicInteger();
        var terminals = new CountDownLatch(2);
        var provider = providerStreaming(new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                throw new RuntimeException("boom before any terminal");
            }
        }, vertexAI);

        provider.generateStream(ChatCompletionRequest.builder().model(MODEL).build(), "workspace",
                message -> {
                },
                () -> {
                    closes.incrementAndGet();
                    terminals.countDown();
                },
                throwable -> {
                    errors.incrementAndGet();
                    terminals.countDown();
                });

        assertThat(terminals.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(errors.get()).isEqualTo(1);
        assertThat(closes.get()).isEqualTo(1);
        verify(vertexAI, timeout(2_000)).close();
    }

    @Test
    @DisplayName("closing the streaming wrapper closes the VertexAI and the delegate")
    void streamingWrapperCloseClosesBoth() throws Exception {
        var vertexAI = mock(VertexAI.class);
        var delegate = mock(StreamingChatModel.class, withSettings().extraInterfaces(AutoCloseable.class));

        new CloseableVertexAiStreamingChatModel(delegate, vertexAI).close();

        verify(vertexAI).close();
        verify((AutoCloseable) delegate).close();
    }

    @Test
    @DisplayName("closing the chat wrapper closes the VertexAI and the delegate")
    void chatWrapperCloseClosesBoth() throws Exception {
        var vertexAI = mock(VertexAI.class);
        var delegate = mock(ChatModel.class, withSettings().extraInterfaces(AutoCloseable.class));

        new CloseableVertexAiChatModel(delegate, vertexAI).close();

        verify(vertexAI).close();
        verify((AutoCloseable) delegate).close();
    }
}
