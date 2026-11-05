package com.comet.opik.domain.cost;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class UsedModelTest {

    @ParameterizedTest(name = "{0} on {1}")
    @CsvSource(nullValues = "null", value = {
            "GPT-5-Mini, openai, gpt-5-mini, openai",
            "'  gpt-4o  ', '  openai  ', gpt-4o, openai",
            "custom-llm/local/llama3.2, ollama:local, custom-llm/local/llama3.2, ollama:local",
            "meta-llama/Llama-3-8B, openai, meta-llama/llama-3-8b, openai",
            "openai/gpt-4o, null, openai/gpt-4o, openai",
            "aide-gpt-5-mini, null, aide-gpt-5-mini, ''",
            "mixtral-8x7b, api.together.xyz, mixtral-8x7b, together_ai"})
    void keepsTheSpansNamesAsLookupSeesThem(String model, String provider, String expectedModel,
            String expectedProvider) {
        assertThat(UsedModel.of(model, provider)).contains(new UsedModel(expectedModel, expectedProvider));
    }

    @Test
    void spanWithoutAModelHasNone() {
        assertThat(UsedModel.of(null, "openai")).isEmpty();
        assertThat(UsedModel.of("   ", "openai")).isEmpty();
    }

    @Test
    void namesTooLongForAPriceRowAreLeftOut() {
        assertThat(UsedModel.of("m".repeat(UsedModel.MAX_MODEL_NAME_LENGTH + 1), "openai")).isEmpty();
        assertThat(UsedModel.of("gpt-4o", "p".repeat(UsedModel.MAX_PROVIDER_LENGTH + 1))).isEmpty();
        assertThat(UsedModel.of("m".repeat(UsedModel.MAX_MODEL_NAME_LENGTH), "openai")).isPresent();
    }
}
