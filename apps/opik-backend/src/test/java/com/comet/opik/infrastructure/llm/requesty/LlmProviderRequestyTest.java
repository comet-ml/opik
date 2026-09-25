package com.comet.opik.infrastructure.llm.requesty;

import dev.langchain4j.model.openai.internal.OpenAiClient;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.UserMessage;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class LlmProviderRequestyTest {

    private final LlmProviderRequesty provider = new LlmProviderRequesty(mock(OpenAiClient.class));

    private static ChatCompletionRequest requestFor(String model) {
        return ChatCompletionRequest.builder()
                .model(model)
                .messages(List.of(UserMessage.builder().content("hello").build()))
                .temperature(0.2)
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"requesty/", "requesty/ ", "requesty/   "})
    @DisplayName("a prefix-only Requesty id is rejected before it can reach the router as an empty model")
    void validateRequestRejectsPrefixOnlyModel(String model) {
        assertThatThrownBy(() -> provider.validateRequest(requestFor(model)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(LlmProviderRequesty.ERROR_EMPTY_ROUTER_MODEL.formatted(model));
    }

    @ParameterizedTest
    @ValueSource(strings = {"requesty/openai/gpt-4o", "requesty/some-vendor/some-future-model", "openai/gpt-4o",
            "gpt-4o"})
    @DisplayName("ids with a router model after the prefix, and non Requesty ids, pass validation")
    void validateRequestAcceptsRealModels(String model) {
        assertThatCode(() -> provider.validateRequest(requestFor(model))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("cleanModelName strips the prefix and keeps every other field of the request")
    void cleanModelNameStripsPrefixAndKeepsOtherFields() {
        var request = requestFor("requesty/openai/gpt-4o");

        var cleaned = LlmProviderRequesty.cleanModelName(request);

        assertThat(cleaned.model()).isEqualTo("openai/gpt-4o");
        assertThat(cleaned.messages()).isEqualTo(request.messages());
        assertThat(cleaned.temperature()).isEqualTo(request.temperature());
    }

    @Test
    @DisplayName("cleanModelName leaves a request without the prefix untouched")
    void cleanModelNameLeavesOtherIdsAlone() {
        var request = requestFor("openai/gpt-4o");

        assertThat(LlmProviderRequesty.cleanModelName(request)).isSameAs(request);
    }
}
