package com.comet.opik.infrastructure.llm.orcarouter;

import dev.langchain4j.model.openai.internal.OpenAiClient;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;

@DisplayName("OrcaRouterProvider outgoing model transform")
class OrcaRouterProviderTest {

    private final OrcaRouterProvider provider = new OrcaRouterProvider(mock(OpenAiClient.class));

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource
    void transformModel(String incomingModel, String expectedOutgoingModel) {
        var request = ChatCompletionRequest.builder()
                .model(incomingModel)
                .addUserMessage("ping")
                .build();

        var transformed = provider.transformModel(request);

        assertThat(transformed.model()).isEqualTo(expectedOutgoingModel);
    }

    private static Stream<Arguments> transformModel() {
        return Stream.of(
                // Bare router alias: the "orcarouter/" prefix is kept (the API rejects "auto").
                arguments("orcarouter/auto", "orcarouter/auto"),
                arguments("orcarouter/some-future-router", "orcarouter/some-future-router"),
                // Namespaced upstream: the "orcarouter/" prefix is stripped.
                arguments("orcarouter/openai/gpt-4o-mini", "openai/gpt-4o-mini"),
                arguments("orcarouter/anthropic/claude-opus-4.8", "anthropic/claude-opus-4.8"),
                // Non-OrcaRouter model: left untouched.
                arguments("openai/gpt-4o", "openai/gpt-4o"),
                arguments("gpt-4o", "gpt-4o"));
    }
}
