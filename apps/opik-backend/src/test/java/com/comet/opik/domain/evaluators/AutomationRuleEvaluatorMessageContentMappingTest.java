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
            "[{\"type\": 42}]", // content part with a non-string type
            "[null]", // null element — reached convertToMessageContent and NPE'd
            "[{}]", // object with no discriminator, which the renderer's switch dereferences
            "[{\"foo\": \"bar\"}]", // e.g. few-shot examples pasted as JSON
            "[{\"type\": \"text\", \"text\": \"ok\"}, {}]", // one good part is not enough
            "[]", // an empty array is no content at all; the renderer cannot build a message from it
            "[]\n\nNow evaluate {{input}}",
            "[{\"type\": \"text\", \"text\": \"Example\"}]\n\nNow evaluate {{input}}" // array then prose
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

    /**
     * {@code type} is unconstrained on write, so an array carrying a discriminator this renderer does not
     * know is still an array the API accepted. Downgrading it to a string would lose the shape for good:
     * the next save stores what it reads back.
     */
    /**
     * Preserved, but not renderable: a part whose type the renderer does not know is skipped, so an array
     * of nothing but unknown parts builds a UserMessage with no contents, which langchain4j rejects
     * ("contents cannot be null or empty"). Unchanged from before this fix, and unreachable from the UI —
     * its types make the discriminator a required literal — but the mapper cannot close it, since knowing
     * which types render means duplicating the renderer's switch here. The renderer declining to build an
     * empty message is what actually fixes it.
     */
    @Test
    void contentArrayWithAnUnknownTypeIsPreserved() {
        var message = MAPPER.map(stored("[{\"type\": \"pdf_url\", \"pdf_url\": {\"url\": \"https://x/a.pdf\"}}]"));

        assertThat(message.content()).isNull();
        // The discriminator survives, but the payload does not: LlmAsJudgeMessageContent has a field per
        // known type and no overflow, so convertToMessageContent has nowhere to put pdf_url. Asserted in
        // full rather than by type alone so the loss is visible here instead of being discovered later —
        // carrying unknown payloads through needs a model change, not a mapper change.
        assertThat(message.contentArray()).containsExactly(
                LlmAsJudgeMessageContent.builder().type("pdf_url").build());
    }

    @Test
    void nullContentStaysNull() {
        var message = MAPPER.map(stored(null));

        assertThat(message.content()).isNull();
        assertThat(message.contentArray()).isNull();
    }
}
