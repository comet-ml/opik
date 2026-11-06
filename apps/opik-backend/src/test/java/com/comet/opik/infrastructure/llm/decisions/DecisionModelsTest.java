package com.comet.opik.infrastructure.llm.decisions;

import com.comet.opik.api.evaluators.LlmAsJudgeModelApi;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionModelsTest {

    @ParameterizedTest(name = "{0}, api {1} -> decisions rule {2}")
    @MethodSource
    void resolvesOnModelNameAndApi(String model, LlmAsJudgeModelApi api, boolean decisionsRule) {
        var parameters = LlmAsJudgeModelParameters.builder().name(model).api(api).build();

        assertThat(DecisionModels.isDecisionsRule(parameters)).isEqualTo(decisionsRule);
    }

    static Stream<Arguments> resolvesOnModelNameAndApi() {
        return Stream.of(
                Arguments.of("~typesafe/jev-latest", null, true),
                Arguments.of("typesafe/jev-1.13", LlmAsJudgeModelApi.DECISIONS, true),
                Arguments.of("gpt-6-luna", null, false),
                Arguments.of("gpt-6-luna", LlmAsJudgeModelApi.DECISIONS, true),
                Arguments.of("gpt-4o", null, false),
                Arguments.of("gpt-4o", LlmAsJudgeModelApi.DECISIONS, false));
    }
}
