package com.comet.opik.infrastructure.llm.requesty;

import com.comet.opik.infrastructure.llm.openrouter.OpenRouterModelName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class RequestyModelNameTest {

    @ParameterizedTest
    @EnumSource(RequestyModelName.class)
    @DisplayName("every enum value is exposed to Opik with the requesty/ prefix and to the router without it")
    void toStringCarriesPrefixAndRouterModelDoesNot(RequestyModelName model) {
        assertThat(model.toString()).startsWith(RequestyModelName.REQUESTY_MODEL_PREFIX);
        assertThat(model.routerModel()).doesNotStartWith(RequestyModelName.REQUESTY_MODEL_PREFIX);
        assertThat(model.toString()).isEqualTo(RequestyModelName.REQUESTY_MODEL_PREFIX + model.routerModel());
        assertThat(RequestyModelName.stripPrefix(model.toString())).isEqualTo(model.routerModel());
    }

    @ParameterizedTest
    @EnumSource(RequestyModelName.class)
    @DisplayName("byValue resolves both the prefixed Opik id and the bare router id")
    void byValueAcceptsPrefixedAndBareIds(RequestyModelName model) {
        assertThat(RequestyModelName.byValue(model.toString())).contains(model);
        assertThat(RequestyModelName.byValue(model.routerModel())).contains(model);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "requesty/", "requesty/some-vendor/some-future-model", "openai/does-not-exist"})
    @DisplayName("byValue is empty for unknown ids instead of throwing")
    void byValueIsEmptyForUnknownIds(String value) {
        assertThat(RequestyModelName.byValue(value)).isEmpty();
    }

    @Test
    @DisplayName("the prefix alone decides whether a model is a Requesty one")
    void isRequestyModelLooksOnlyAtThePrefix() {
        assertThat(RequestyModelName.isRequestyModel("requesty/openai/gpt-4o")).isTrue();
        assertThat(RequestyModelName.isRequestyModel("requesty/some-vendor/some-future-model")).isTrue();
        assertThat(RequestyModelName.isRequestyModel("openai/gpt-4o")).isFalse();
        assertThat(RequestyModelName.isRequestyModel("gpt-4o")).isFalse();
        assertThat(RequestyModelName.isRequestyModel("openrouter/openai/gpt-4o")).isFalse();
    }

    @Test
    @DisplayName("stripPrefix removes exactly one requesty/ prefix and leaves other ids alone")
    void stripPrefixOnlyTouchesPrefixedIds() {
        assertThat(RequestyModelName.stripPrefix("requesty/openai/gpt-4o")).isEqualTo("openai/gpt-4o");
        assertThat(RequestyModelName.stripPrefix("requesty/some-vendor/some-future-model"))
                .isEqualTo("some-vendor/some-future-model");
        assertThat(RequestyModelName.stripPrefix("openai/gpt-4o")).isEqualTo("openai/gpt-4o");
        assertThat(RequestyModelName.stripPrefix("gpt-4o")).isEqualTo("gpt-4o");
    }

    @ParameterizedTest
    @EnumSource(OpenRouterModelName.class)
    @DisplayName("no OpenRouter id is mistaken for a Requesty one, even when both routers serve the same model")
    void openRouterIdsAreNeverRequestyModels(OpenRouterModelName openRouterModel) {
        assertThat(RequestyModelName.isRequestyModel(openRouterModel.toString())).isFalse();
    }

    @Test
    @DisplayName("structured output support follows the curated list")
    void structuredOutputSupportFollowsCuratedList() {
        assertThat(RequestyModelName.OPENAI_GPT_4O.isStructuredOutputSupported()).isTrue();
        assertThat(RequestyModelName.ANTHROPIC_CLAUDE_SONNET_4_5.isStructuredOutputSupported()).isTrue();
        assertThat(RequestyModelName.GOOGLE_GEMINI_2_5_PRO.isStructuredOutputSupported()).isTrue();
        assertThat(RequestyModelName.DEEPSEEK_DEEPSEEK_REASONER.isStructuredOutputSupported()).isFalse();
        assertThat(RequestyModelName.MISTRAL_MISTRAL_LARGE_LATEST.isStructuredOutputSupported()).isFalse();
        assertThat(RequestyModelName.XAI_GROK_4.isStructuredOutputSupported()).isFalse();
    }
}
