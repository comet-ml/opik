package com.comet.opik.domain.llm;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class ModelAnswerTextTest {

    private static final String ANSWER = "{\"a\": 2}";

    private static Stream<Arguments> answersAfterReasoning() {
        return Stream.of(
                arguments("<reasoning>draft {\"a\": 1}</reasoning>" + ANSWER),
                arguments("  <think>x</think>\n<reasoning>y\nz</reasoning>" + ANSWER),
                arguments("<THINKING>x</THINKING>" + ANSWER));
    }

    @ParameterizedTest
    @MethodSource("answersAfterReasoning")
    void dropsTheReasoningBlocksAnAnswerStartsWith(String text) {
        assertThat(ModelAnswerText.withoutLeadingReasoning(text)).isEqualTo(ANSWER);
    }

    @ParameterizedTest
    @ValueSource(strings = {ANSWER, "{\"a\": \"<reasoning>x</reasoning>\"}", "<reasoning>cut off {\"a\": 1}",
            "Answer: <think>x</think>" + ANSWER, "<reasoning>x</think>" + ANSWER})
    void leavesAnAnswerWithoutALeadingClosedBlockAsItIs(String text) {
        assertThat(ModelAnswerText.withoutLeadingReasoning(text)).isEqualTo(text);
    }
}
