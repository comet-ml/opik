package com.comet.opik.infrastructure.llm;

import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.UserMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class CustomParametersOverridesTest {

    private static final ChatCompletionRequest TYPED = ChatCompletionRequest.builder()
            .model("gpt-4o")
            .messages(List.of(UserMessage.builder().content("hello").build()))
            .temperature(0.25)
            .topP(0.5)
            .maxTokens(300)
            .maxCompletionTokens(66)
            .presencePenalty(0.1)
            .frequencyPenalty(0.2)
            .seed(7)
            .reasoningEffort("low")
            .build();

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void customValueReplacesTheTypedField(String key, Object customValue,
            Function<ChatCompletionRequest, Object> typedField) {
        var result = CustomParametersOverrides.apply(withCustomParameters(Map.of(key, customValue)));

        assertThat(typedField.apply(result)).isNull();
        assertThat(result.customParameters()).containsExactlyEntriesOf(Map.of(key, customValue));
    }

    private static Stream<Arguments> customValueReplacesTheTypedField() {
        return Stream.of(
                arguments("temperature", 0.95, field(ChatCompletionRequest::temperature)),
                arguments("top_p", 0.9, field(ChatCompletionRequest::topP)),
                arguments("max_tokens", 12, field(ChatCompletionRequest::maxTokens)),
                arguments("max_completion_tokens", 12, field(ChatCompletionRequest::maxCompletionTokens)),
                arguments("presence_penalty", 0.6, field(ChatCompletionRequest::presencePenalty)),
                arguments("frequency_penalty", 0.7, field(ChatCompletionRequest::frequencyPenalty)),
                arguments("seed", 42, field(ChatCompletionRequest::seed)),
                arguments("reasoning_effort", "high", field(ChatCompletionRequest::reasoningEffort)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"max_tokens", "max_completion_tokens"})
    void eitherTokenLimitNameReplacesBothTypedLimits(String key) {
        var result = CustomParametersOverrides.apply(withCustomParameters(Map.of(key, 12)));

        assertThat(result.maxTokens()).isNull();
        assertThat(result.maxCompletionTokens()).isNull();
        assertThat(result.customParameters()).containsExactlyEntriesOf(Map.of(key, 12));
    }

    @Test
    void keepsTheTypedFieldsNoCustomKeyRepeats() {
        var result = CustomParametersOverrides.apply(withCustomParameters(Map.of("temperature", 0.95)));

        assertThat(result.topP()).isEqualTo(0.5);
        assertThat(result.maxTokens()).isEqualTo(300);
        assertThat(result.maxCompletionTokens()).isEqualTo(66);
        assertThat(result.presencePenalty()).isEqualTo(0.1);
        assertThat(result.frequencyPenalty()).isEqualTo(0.2);
        assertThat(result.seed()).isEqualTo(7);
        assertThat(result.reasoningEffort()).isEqualTo("low");
        assertThat(result.model()).isEqualTo("gpt-4o");
        assertThat(result.messages()).isEqualTo(TYPED.messages());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void withTokenLimitUnderLeavesTheLimitOnceUnderTheGivenName(String name, String limitKey,
            Map<String, Object> customParameters, Map<String, Object> expected) {
        assertThat(CustomParametersOverrides.withTokenLimitUnder(limitKey, customParameters))
                .isEqualTo(expected);
    }

    private static Stream<Arguments> withTokenLimitUnderLeavesTheLimitOnceUnderTheGivenName() {
        return Stream.of(
                arguments("max_completion_tokens moves to max_tokens", "max_tokens",
                        Map.of("max_completion_tokens", 12, "top_k", 7), Map.of("max_tokens", 12, "top_k", 7)),
                arguments("max_tokens moves to max_completion_tokens", "max_completion_tokens",
                        Map.of("max_tokens", 40), Map.of("max_completion_tokens", 40)),
                arguments("the value already under the name wins", "max_tokens",
                        Map.of("max_tokens", 12, "max_completion_tokens", 30), Map.of("max_tokens", 12)),
                arguments("a limit under the name stays", "max_tokens",
                        Map.of("max_tokens", 12), Map.of("max_tokens", 12)),
                arguments("other keys stay", "max_tokens", Map.of("top_k", 7), Map.of("top_k", 7)));
    }

    @Test
    void withTokenLimitUnderLeavesNoCustomParametersAsIs() {
        assertThat(CustomParametersOverrides.withTokenLimitUnder("max_tokens", null)).isNull();
    }

    @Test
    void leavesARequestWithOnlyNewCustomKeysAsIs() {
        var request = withCustomParameters(Map.of("top_k", 40, "keep_alive", "5m"));

        assertThat(CustomParametersOverrides.apply(request)).isSameAs(request);
    }

    @Test
    void leavesARequestWithoutCustomParametersAsIs() {
        assertThat(CustomParametersOverrides.apply(TYPED)).isSameAs(TYPED);
    }

    private static ChatCompletionRequest withCustomParameters(Map<String, Object> customParameters) {
        return ChatCompletionRequest.builder().from(TYPED).customParameters(customParameters).build();
    }

    private static Function<ChatCompletionRequest, Object> field(Function<ChatCompletionRequest, Object> getter) {
        return getter;
    }
}
