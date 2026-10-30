package com.comet.opik.domain.cost;

import com.comet.opik.domain.cost.ModelPriceResolver.NameForm;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ModelPriceResolverTest {

    private static Stream<Arguments> matchedNames() {
        return Stream.of(
                Arguments.of("gpt-5-mini-2025-08-07", "openai", "gpt-5-mini-2025-08-07", "openai", NameForm.EXACT),
                Arguments.of("GPT-5-MINI", "openai", "gpt-5-mini", "openai", NameForm.NORMALIZED),
                Arguments.of("gpt-5.2-2025-12-17", "openai", "gpt-5.2", "openai", NameForm.DATE_STRIPPED),
                Arguments.of("anthropic.claude-opus-4-6-v1:0", "bedrock", "anthropic.claude-opus-4-6-v1",
                        "bedrock_converse", NameForm.VERSION_STRIPPED),
                Arguments.of("perplexity/sonar", "openrouter", "sonar", "perplexity", NameForm.EXACT),
                Arguments.of("openai/gpt-5-mini", null, "gpt-5-mini", "openai", NameForm.EXACT));
    }

    @ParameterizedTest(name = "{0} on {1}")
    @MethodSource("matchedNames")
    void resolveReportsMatchedRowProviderAndNameForm(String modelName, String provider, String rowName,
            String rowProvider, NameForm nameForm) {
        var expected = ModelPriceResolver.PriceMatch.builder()
                .price(ModelPriceTable.get(rowName, rowProvider))
                .provider(rowProvider)
                .nameForm(nameForm)
                .build();

        assertThat(ModelPriceResolver.resolve(modelName, provider)).contains(expected);
    }

    private static Stream<Arguments> unmatchedNames() {
        return Stream.of(
                Arguments.of(null, "openai"),
                Arguments.of("", "openai"),
                Arguments.of("  ", "openai"),
                Arguments.of("gpt-5-mini", null),
                Arguments.of("gpt-5-mini", ""),
                Arguments.of("aide-gpt-5-mini", "azure"),
                Arguments.of("gpt-5-mini", "llm-gateway.example.com"));
    }

    @ParameterizedTest(name = "{0} on {1}")
    @MethodSource("unmatchedNames")
    void resolveWhenNothingMatchesReturnsEmpty(String modelName, String provider) {
        assertThat(ModelPriceResolver.resolve(modelName, provider)).isEmpty();
    }
}
