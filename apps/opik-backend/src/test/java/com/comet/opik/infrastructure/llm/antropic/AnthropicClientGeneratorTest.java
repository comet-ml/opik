package com.comet.opik.infrastructure.llm.antropic;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.utils.JsonUtils;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicChatRequestParameters;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicClientGeneratorTest {

    private static final int DEFAULT_MAX_TOKENS = LlmProviderAnthropicMapper.DEFAULT_MAX_COMPLETION_TOKENS;

    private final AnthropicClientGenerator generator = new AnthropicClientGenerator(new LlmProviderClientConfig());

    private AnthropicChatRequestParameters generateChatParameters(LlmAsJudgeModelParameters modelParameters) {
        var config = LlmProviderClientApiConfig.builder().apiKey("test-key").build();
        var model = (AnthropicChatModel) generator.generateChat(config, modelParameters);
        return (AnthropicChatRequestParameters) model.defaultRequestParameters();
    }

    @Nested
    @DisplayName("Sampling params capability")
    class SamplingParamsCapability {

        @ParameterizedTest
        @ValueSource(strings = {"claude-sonnet-5", "claude-opus-4-7", "claude-opus-4-8"})
        void supportsSamplingParamsReturnsFalseForAdaptiveThinkingModels(String modelName) {
            assertThat(AnthropicModelName.supportsSamplingParams(modelName)).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"claude-3-7-sonnet-20250219", "claude-haiku-4-5-20251001", "claude-sonnet-4-5",
                "claude-opus-4-6"})
        void supportsSamplingParamsReturnsTrueForNonAdaptiveModels(String modelName) {
            assertThat(AnthropicModelName.supportsSamplingParams(modelName)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"some-unknown-model", "claude-future-99"})
        void supportsSamplingParamsDefaultsTrueForUnknownModels(String modelName) {
            assertThat(AnthropicModelName.supportsSamplingParams(modelName)).isTrue();
        }
    }

    @Nested
    @DisplayName("Temperature gating on the judge path")
    class TemperatureGating {

        @ParameterizedTest
        @ValueSource(strings = {"claude-sonnet-5", "claude-opus-4-8"})
        void temperatureIsNotForwardedForAdaptiveThinkingModels(String modelName) {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name(modelName)
                    .temperature(0.7)
                    .build());

            assertThat(parameters.temperature()).isNull();
        }

        @Test
        void temperatureIsForwardedForModelsThatSupportSamplingParams() {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name("claude-3-7-sonnet-20250219")
                    .temperature(0.7)
                    .build());

            assertThat(parameters.temperature()).isEqualTo(0.7);
        }

        @ParameterizedTest
        @ValueSource(strings = {"enabled", "adaptive", "some-future-mode"})
        void temperatureIsNotForwardedWhenThinkingNotDisabledOnSamplingCapableModel(String thinkingType) {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name("claude-3-7-sonnet-20250219")
                    .temperature(0.7)
                    .customParameters(JsonUtils.getJsonNodeFromString(
                            "{\"thinking\": {\"type\": \"%s\", \"budget_tokens\": 1024}}".formatted(thinkingType)))
                    .build());

            assertThat(parameters.temperature()).isNull();
        }

        @Test
        void temperatureIsForwardedWhenThinkingDisabledOnSamplingCapableModel() {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name("claude-3-7-sonnet-20250219")
                    .temperature(0.7)
                    .customParameters(JsonUtils.getJsonNodeFromString("{\"thinking\": {\"type\": \"disabled\"}}"))
                    .build());

            assertThat(parameters.temperature()).isEqualTo(0.7);
        }

        @ParameterizedTest
        @ValueSource(strings = {"{\"thinking\": {}}", "{\"thinking\": {\"type\": \"\"}}",
                "{\"thinking\": {\"budget_tokens\": 1024}}"})
        void temperatureIsForwardedWhenThinkingTypeMissingOrBlankOnSamplingCapableModel(String customParameters) {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name("claude-3-7-sonnet-20250219")
                    .temperature(0.7)
                    .customParameters(JsonUtils.getJsonNodeFromString(customParameters))
                    .build());

            assertThat(parameters.temperature()).isEqualTo(0.7);
        }
    }

    @Nested
    @DisplayName("max_tokens resolution on the judge path")
    class MaxTokensResolution {

        @Test
        void defaultsMaxTokensWhenNotProvided() {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name("claude-sonnet-5")
                    .build());

            assertThat(parameters.maxOutputTokens()).isEqualTo(DEFAULT_MAX_TOKENS);
        }

        @Test
        void forwardsMaxTokensFromCustomParameters() {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name("claude-sonnet-5")
                    .customParameters(JsonUtils.getJsonNodeFromString("{\"max_tokens\": 2048}"))
                    .build());

            assertThat(parameters.maxOutputTokens()).isEqualTo(2048);
        }

        @Test
        void maxTokensAddsHeadroomAboveThinkingBudgetWhenOnlyBudgetProvided() {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name("claude-sonnet-5")
                    .customParameters(JsonUtils.getJsonNodeFromString(
                            "{\"thinking\": {\"type\": \"enabled\", \"budget_tokens\": 4096}}"))
                    .build());

            assertThat(parameters.maxOutputTokens())
                    .isEqualTo(4096 + DEFAULT_MAX_TOKENS)
                    .isGreaterThan(parameters.thinkingBudgetTokens());
        }

        @Test
        void raisesExplicitMaxTokensThatDoesNotClearThinkingBudget() {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name("claude-sonnet-5")
                    .customParameters(JsonUtils.getJsonNodeFromString(
                            "{\"max_tokens\": 1000, \"thinking\": {\"type\": \"enabled\", \"budget_tokens\": 4096}}"))
                    .build());

            assertThat(parameters.maxOutputTokens())
                    .isEqualTo(4096 + DEFAULT_MAX_TOKENS)
                    .isGreaterThan(parameters.thinkingBudgetTokens());
        }

        @Test
        void honorsExplicitMaxTokensThatAlreadyClearsThinkingBudget() {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name("claude-sonnet-5")
                    .customParameters(JsonUtils.getJsonNodeFromString(
                            "{\"max_tokens\": 8000, \"thinking\": {\"type\": \"enabled\", \"budget_tokens\": 2048}}"))
                    .build());

            assertThat(parameters.maxOutputTokens()).isEqualTo(8000);
        }

        @Test
        void ignoresNonPositiveMaxTokensAndBudget() {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name("claude-sonnet-5")
                    .customParameters(JsonUtils.getJsonNodeFromString(
                            "{\"max_tokens\": 0, \"thinking\": {\"type\": \"enabled\", \"budget_tokens\": -5}}"))
                    .build());

            assertThat(parameters.maxOutputTokens()).isEqualTo(DEFAULT_MAX_TOKENS);
            assertThat(parameters.thinkingBudgetTokens()).isNull();
        }

        @Test
        void doesNotOverflowMaxTokensForExtremeThinkingBudget() {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name("claude-sonnet-5")
                    .customParameters(JsonUtils.getJsonNodeFromString(
                            "{\"thinking\": {\"type\": \"enabled\", \"budget_tokens\": 2147483647}}"))
                    .build());

            assertThat(parameters.maxOutputTokens()).isEqualTo(Integer.MAX_VALUE).isPositive();
        }
    }

    @Nested
    @DisplayName("thinking forwarding from custom_parameters")
    class ThinkingForwarding {

        @Test
        void forwardsThinkingDisabledFromCustomParameters() {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name("claude-sonnet-5")
                    .customParameters(JsonUtils.getJsonNodeFromString("{\"thinking\": {\"type\": \"disabled\"}}"))
                    .build());

            assertThat(parameters.thinkingType()).isEqualTo("disabled");
        }

        @ParameterizedTest
        @CsvSource({"enabled,1024", "enabled,4096"})
        void forwardsThinkingTypeAndBudgetFromCustomParameters(String type, int budget) {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name("claude-sonnet-5")
                    .customParameters(JsonUtils.getJsonNodeFromString(
                            "{\"thinking\": {\"type\": \"%s\", \"budget_tokens\": %d}}".formatted(type, budget)))
                    .build());

            assertThat(parameters.thinkingType()).isEqualTo(type);
            assertThat(parameters.thinkingBudgetTokens()).isEqualTo(budget);
        }

        @Test
        void leavesThinkingUnsetWhenNoCustomParameters() {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name("claude-sonnet-5")
                    .build());

            assertThat(parameters.thinkingType()).isNull();
        }

        @Test
        void dropsBudgetWhenThinkingTypeMissing() {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name("claude-sonnet-5")
                    .customParameters(JsonUtils.getJsonNodeFromString("{\"thinking\": {\"budget_tokens\": 1024}}"))
                    .build());

            assertThat(parameters.thinkingType()).isNull();
            assertThat(parameters.thinkingBudgetTokens()).isNull();
            assertThat(parameters.maxOutputTokens()).isEqualTo(DEFAULT_MAX_TOKENS);
        }

        @Test
        void dropsBudgetWhenThinkingDisabled() {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name("claude-sonnet-5")
                    .customParameters(JsonUtils.getJsonNodeFromString(
                            "{\"thinking\": {\"type\": \"disabled\", \"budget_tokens\": 1024}}"))
                    .build());

            assertThat(parameters.thinkingType()).isEqualTo("disabled");
            assertThat(parameters.thinkingBudgetTokens()).isNull();
            assertThat(parameters.maxOutputTokens()).isEqualTo(DEFAULT_MAX_TOKENS);
        }
    }
}
