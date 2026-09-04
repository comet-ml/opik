package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.LlmAsJudgeMessageContent;
import dev.langchain4j.data.message.ChatMessageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stored message content carries no discriminator for its shape, so a leading '[' only hints that
 * it might be a structured content array. Content that does not parse into content parts must read back
 * as the plain string it is — throwing there fails the whole evaluator page, not just the
 * one rule.
 */
class AutomationRuleEvaluatorMessageContentMappingTest {

    private static final AutomationModelEvaluatorMapper MAPPER = AutomationModelEvaluatorMapper.INSTANCE;

    private static LlmAsJudgeCodeMessage stored(String content) {
        return new LlmAsJudgeCodeMessage(ChatMessageType.USER.name(), content);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[Source Text]\n<<<<< SOURCE_TEXT START >>>>>\n{{input_text}}", // prose opening with '['
            "[test]",
            "[1, 2]", // valid JSON array, but not content parts
            "[\"just a string\"]",
            "[{\"type\": 42}]" // content part with a non-string type
    })
    void plainContentThatLooksLikeAnArrayReadsBackAsAString(String content) {
        var message = MAPPER.map(stored(content));

        assertThat(message.role()).isEqualTo(ChatMessageType.USER);
        assertThat(message.content()).isEqualTo(content);
        assertThat(message.contentArray()).isNull();
    }

    @Test
    void structuredContentStillReadsBackAsContentParts() {
        var message = MAPPER.map(stored("""
                [{"type": "text", "text": "describe this"},
                 {"type": "image_url", "image_url": {"url": "https://example.com/a.png", "detail": "high"}}]
                """));

        assertThat(message.content()).isNull();
        assertThat(message.contentArray()).hasSize(2);
        assertThat(message.contentArray().getFirst().text()).isEqualTo("describe this");

        LlmAsJudgeMessageContent image = message.contentArray().get(1);
        assertThat(image.imageUrl().url()).isEqualTo("https://example.com/a.png");
        assertThat(image.imageUrl().detail()).isEqualTo("high");
    }

    @Test
    void ordinaryPromptStillReadsBackAsAString() {
        var message = MAPPER.map(stored("You are an impartial judge."));

        assertThat(message.content()).isEqualTo("You are an impartial judge.");
        assertThat(message.contentArray()).isNull();
    }

    @Test
    void nullContentStaysNull() {
        var message = MAPPER.map(stored(null));

        assertThat(message.content()).isNull();
        assertThat(message.contentArray()).isNull();
    }
}
