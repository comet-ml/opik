package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.opentelemetry.api.common.AttributeKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

@DisplayName("OnlineScoringMetrics char-sizing")
class OnlineScoringMetricsTest {

    @Test
    void messageCharsForSystemMessage() {
        assertThat(OnlineScoringMetrics.messageChars(SystemMessage.from("system prompt"))).isEqualTo(13L);
    }

    @Test
    void messageCharsForUserMessageWithText() {
        assertThat(OnlineScoringMetrics.messageChars(UserMessage.from("hello world"))).isEqualTo(11L);
    }

    @Test
    void messageCharsForUserMessageCountsOnlyTextParts() {
        var userMessage = UserMessage.from(
                TextContent.from("hello"),
                ImageContent.from("https://example.com/image.png"),
                TextContent.from("!"));

        // Only the two text parts are counted; the image URL is skipped.
        assertThat(OnlineScoringMetrics.messageChars(userMessage)).isEqualTo(6L);
    }

    @Test
    void messageCharsForAiMessage() {
        assertThat(OnlineScoringMetrics.messageChars(AiMessage.from("scored"))).isEqualTo(6L);
    }

    @Test
    void messageCharsForAiMessageWithoutText() {
        var aiMessage = AiMessage.builder()
                .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                        .id("1").name("read").arguments("{}").build()))
                .build();

        assertThat(OnlineScoringMetrics.messageChars(aiMessage)).isZero();
    }

    @Test
    void messageCharsForToolExecutionResult() {
        assertThat(OnlineScoringMetrics.messageChars(
                ToolExecutionResultMessage.from("1", "read", "result"))).isEqualTo(6L);
    }

    @Test
    void messageCharsForNullMessageIsZero() {
        assertThat(OnlineScoringMetrics.messageChars(null)).isZero();
    }

    @Test
    void inputCharsSumsAllMessages() {
        var request = ChatRequest.builder()
                .messages(SystemMessage.from("abc"), UserMessage.from("de"), AiMessage.from("f"))
                .build();

        assertThat(OnlineScoringMetrics.inputChars(request)).isEqualTo(6L);
    }

    @Test
    void inputCharsForNullRequestIsZero() {
        assertThat(OnlineScoringMetrics.inputChars(null)).isZero();
    }

    @Test
    void outputCharsForResponseText() {
        var response = ChatResponse.builder().aiMessage(AiMessage.from("a response")).build();

        assertThat(OnlineScoringMetrics.outputChars(response)).isEqualTo(10L);
    }

    @Test
    void outputCharsForResponseWithoutTextIsZero() {
        var response = ChatResponse.builder()
                .aiMessage(AiMessage.builder()
                        .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                                .id("1").name("read").arguments("{}").build()))
                        .build())
                .build();

        assertThat(OnlineScoringMetrics.outputChars(response)).isZero();
    }

    @Test
    void outputCharsForNullResponseIsZero() {
        assertThat(OnlineScoringMetrics.outputChars(null)).isZero();
    }

    @Test
    void buildAttributesTagsEvaluatorTypeAndModelValues() {
        var attributes = OnlineScoringMetrics.buildAttributes(
                AutomationRuleEvaluatorType.TRACE_THREAD_LLM_AS_JUDGE, "gpt-4o");

        assertThat(attributes.asMap())
                .containsOnly(
                        entry(AttributeKey.stringKey("evaluator_type"), "trace_thread_llm_as_judge"),
                        entry(AttributeKey.stringKey("model"), "gpt-4o"));
    }

    @Test
    void buildAttributesNeverTagsUnboundedIdentifiers() {
        var attributes = OnlineScoringMetrics.buildAttributes(
                AutomationRuleEvaluatorType.LLM_AS_JUDGE, "gpt-4o");

        // Cardinality guard: rule_id / workspace_id must never become histogram tags.
        assertThat(attributes.asMap().keySet())
                .extracting(AttributeKey::getKey)
                .containsExactlyInAnyOrder("evaluator_type", "model");
    }
}
