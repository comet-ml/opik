package com.comet.opik.infrastructure.llm.vertexai;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Vertex AI provider")
class LlmProviderVertexAITest {

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

        provider.generateStream(
                ChatCompletionRequest.builder().model("vertex_ai/gemini-2.5-flash").build(),
                "workspace",
                message -> {
                },
                closed::countDown,
                error::set);

        assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(error.get()).isNotNull();
    }
}
